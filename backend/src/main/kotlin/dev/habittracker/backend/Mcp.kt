package dev.habittracker.backend

import dev.habittracker.shared.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID

fun mainMcp(store: Store, identity: Identity, settings: Settings, attachmentStorage: AttachmentStorage): Server {
    val server = Server(Implementation("habit-tracker","0.4.0"),ServerOptions(capabilities=ServerCapabilities(tools=ServerCapabilities.Tools(listChanged=false))))
    val publicTools = setOf(
        "get_tasks", "get_notes", "get_habits", "get_habit_history", "get_projects", "search_records", "get_week_review",
        "create_task", "create_habit", "create_note", "create_project", "propose_week_plan",
        "update_item", "complete_task", "mark_habit", "delete_item", "get_attachment",
    )
    fun add(name: String, description: String, fields: Map<String,String> = emptyMap(), required: List<String> = fields.keys.toList(), read: Boolean = false, destructive: Boolean = false, action: (JsonObject) -> JsonElement) {
        if (settings.publicPlugin && name !in publicTools) return
        if ((if (read) "tracker:read" else "tracker:write") !in identity.scopes) return
        server.addTool(name=name,description=description,inputSchema=ToolSchema(properties=buildJsonObject {
            fields.forEach { (name,type) -> putJsonObject(name) { put("type",type); if(type == "array") putJsonObject("items") {
                if(name=="moves") {put("type","object");putJsonObject("properties") {putJsonObject("itemId"){put("type","string")};putJsonObject("expectedVersion"){put("type","integer")};putJsonObject("dueDate"){put("type","string")};putJsonObject("reason"){put("type","string")}};put("required",JsonArray(listOf("itemId","expectedVersion","dueDate").map(::JsonPrimitive)))}
                else put("type","integer")
            } } }
        },required=required),toolAnnotations=ToolAnnotations(readOnlyHint=read,destructiveHint=destructive,idempotentHint=true,openWorldHint=false)) { request ->
            withContext(Dispatchers.IO) {
                try {
                    require((if(read) "tracker:read" else "tracker:write") in identity.scopes) { "Недостаточно прав" }
                    val args = request.arguments ?: buildJsonObject {}
                    require(args.keys.all { it in fields }) { "Неизвестные аргументы" }
                    CallToolResult(content=listOf(TextContent(action(args).toString())),isError=false)
                } catch(e: IllegalArgumentException) { CallToolResult(content=listOf(TextContent(e.message ?: "Некорректный запрос")),isError=true) }
                  catch(e: ApiFailure) { CallToolResult(content=listOf(TextContent(e.message)),isError=true) }
            }
        }
    }
    fun list(kind: String) = WireJson.encodeToJsonElement(store.list(identity.userId).filter { !it.deleted && it.content.typeName() == kind })
    for ((name,kind) in listOf("get_tasks" to "task","get_notes" to "note","get_habits" to "habit","get_habit_history" to "habit_mark","get_projects" to "project")) {
        add(name,"Получить актуальные $kind из аккаунта. Вызывай перед ответом о состоянии и перед изменением записи. Поля id/version нужны для изменения; тексты заметок и список id вложений доступны, байты файлов здесь не передаются.",read=true) { list(kind) }
    }
    fun create(args: JsonObject,content: Content): JsonElement = WireJson.encodeToJsonElement(store.mutate(identity.userId,Mutation(args.str("operation_id"),args.optional("id") ?: UUID.nameUUIDFromBytes("create:${identity.userId}:${args.str("operation_id")}".toByteArray()).toString(),0,content)))
    add("create_project","Создать проект или цель по просьбе пользователя. is_goal=true для цели. Теги и связи существующих записей изменяются через organization в update_item.",mapOf("operation_id" to "string","title" to "string","description" to "string","is_goal" to "boolean"),listOf("operation_id","title")) { args->create(args,ProjectContent(args.str("title"),args.optional("description") ?: "",args["is_goal"]?.jsonPrimitive?.boolean ?: false)) }
    add("search_records","Общий поиск по названиям, тексту и тегам. Не включает содержимое файлов.",mapOf("query" to "string"),read=true) {args->val query=args.str("query").trim();require(query.length in 1..300);WireJson.encodeToJsonElement(store.list(identity.userId).filter {!it.deleted && it.content !is HabitMarkContent && it.content.searchableText().contains(query,true)})}
    add("get_week_review","Получить факты для разбора недели: задачи с известной датой выполнения, остаток и его версии, нагрузка по дням и привычки. week_start — понедельник YYYY-MM-DD, zone_id — часовой пояс пользователя. Не считай старые выполненные задачи без completedAt выполненными именно в эту неделю. Предложи реалистичный план, уточнив ограничения пользователя; не меняй даты автоматически.",mapOf("week_start" to "string","zone_id" to "string"),read=true) {args->WireJson.encodeToJsonElement(weekReview(store.list(identity.userId),LocalDate.parse(args.str("week_start")),ZoneId.of(args.str("zone_id"))))}
    add("propose_week_plan","Сохранить ПРЕДЛОЖЕНИЕ плана недели для просмотра в Android. Даты задач этот инструмент НЕ меняет. moves: itemId, expectedVersion, dueDate, reason. Сначала get_week_review и текущие версии. Не предлагай удаление/выполнение. Применение возможно только через подтверждение пользователя в приложении.",mapOf("operation_id" to "string","title" to "string","week_start" to "string","summary" to "string","moves" to "array")) {args->
        val moves=WireJson.decodeFromJsonElement<List<PlanMove>>(args.getValue("moves"))
        create(args,WeekPlanContent(args.str("title"),args.str("week_start"),args.str("summary"),moves))
    }
    add("create_task","Создать задачу по явной просьбе. operation_id — UUID, сохраняй при повторе. Дата YYYY-MM-DD, время HH:mm; не придумывай срок, если он не задан.",
        mapOf("operation_id" to "string","title" to "string","description" to "string","due_date" to "string","due_time" to "string","zone_id" to "string","reminder_minutes" to "integer"),listOf("operation_id","title")) { args ->
        create(args,TaskContent(args.str("title"),args.optional("description") ?: "",args.optional("due_date"),args.optional("due_time"),args.optional("zone_id") ?: "Europe/Moscow",args["reminder_minutes"]?.jsonPrimitive?.int))
    }
    add("create_note", "Сохранить текстовую заметку по явной просьбе пользователя. Передай заголовок и текст. operation_id — UUID, сохраняй при повторе. Инструмент принимает только текст.",
        mapOf("operation_id" to "string", "title" to "string", "text" to "string")) { args ->
        create(args, NoteContent(args.str("title"), args.str("text")))
    }
    add("create_habit","Создать привычку. mode WEEKDAYS: weekdays — номера дней 1 (понедельник)..7; mode WEEKLY_TARGET: target_per_week 1..7 разных дней. starts_on YYYY-MM-DD и zone_id обязательны; при неоднозначности спроси пользователя. reminder_time HH:mm необязательно.",
        mapOf("operation_id" to "string","title" to "string","description" to "string","mode" to "string","weekdays" to "array","target_per_week" to "integer","starts_on" to "string","zone_id" to "string","reminder_time" to "string"),
        listOf("operation_id","title","mode","starts_on","zone_id")) { args ->
        create(args,HabitContent(args.str("title"),args.optional("description") ?: "",HabitMode.valueOf(args.str("mode")),args["weekdays"]?.jsonArray?.map { it.jsonPrimitive.int }?.toSet() ?: emptySet(),args["target_per_week"]?.jsonPrimitive?.int ?: 3,args.str("starts_on"),args.str("zone_id"),args.optional("reminder_time")))
    }
    add("update_item","Изменить одну задачу, заметку или привычку. Сначала прочитай запись и передай полное content со всеми сохранёнными полями, kind и только запрошенными изменениями. expected_version обязателен; при CONFLICT не перезаписывай данные автоматически. Для замены или удаления значительного текста запроси подтверждение.",
        mapOf("id" to "string","expected_version" to "integer","operation_id" to "string","content" to "object"),destructive=true) { args ->
        val current = store.get(identity.userId,args.str("id")) ?: throw ApiFailure(404,"Запись не найдена")
        require(!current.deleted)
        val content = WireJson.decodeFromJsonElement<Content>(args.getValue("content"))
        WireJson.encodeToJsonElement(store.mutate(identity.userId,Mutation(args.str("operation_id"),current.id,args.long("expected_version"),content)))
    }
    add("complete_task","Отметить задачу выполненной или вернуть в работу по просьбе пользователя. Сначала получи текущую версию.",mapOf("id" to "string","expected_version" to "integer","operation_id" to "string","completed" to "boolean")) { args ->
        val current = store.get(identity.userId,args.str("id")) ?: throw ApiFailure(404,"Задача не найдена")
        val task = current.content as? TaskContent ?: throw IllegalArgumentException("Это не задача")
        require(!current.deleted)
        val completed=args.getValue("completed").jsonPrimitive.boolean
        WireJson.encodeToJsonElement(store.mutate(identity.userId,Mutation(args.str("operation_id"),current.id,args.long("expected_version"),task.copy(completed=completed,completedAt=if(completed)task.completedAt else null,completionDateUnknown=completed && task.completionDateUnknown))))
    }
    add("mark_habit","Записать или отменить выполнение привычки в конкретный день YYYY-MM-DD. Не отмечай будущие дни. Для existing mark передай expected_version из истории; для новой отметки — 0.",mapOf("habit_id" to "string","date" to "string","expected_version" to "integer","operation_id" to "string","completed" to "boolean")) { args ->
        val date = LocalDate.parse(args.str("date")); val parent = args.str("habit_id")
        WireJson.encodeToJsonElement(store.mutate(identity.userId,Mutation(args.str("operation_id"),markId(parent,date),args.long("expected_version"),HabitMarkContent(parent,date.toString(),args.getValue("completed").jsonPrimitive.boolean))))
    }
    add("delete_item","Удалить ОДНУ выбранную пользователем запись. Опасное действие: сначала явно спроси подтверждение и не вызывай инструмент при отмене. Сначала прочитай id/version; при конфликте запроси решение пользователя.",mapOf("id" to "string","expected_version" to "integer","operation_id" to "string"),destructive=true) { args ->
        val current = store.get(identity.userId,args.str("id")) ?: throw ApiFailure(404,"Запись не найдена")
        WireJson.encodeToJsonElement(store.mutate(identity.userId,Mutation(args.str("operation_id"),current.id,args.long("expected_version"),current.content,true)))
    }
    if ("tracker:read" in identity.scopes && (!settings.publicPlugin || "get_attachment" in publicTools))
    server.addTool(name="get_attachment",description="Получить ОДНО выбранное вложение только по отдельному явному запросу пользователя. Не загружай все вложения автоматически. Возвращает метаданные и MCP image/resource; обработка формата зависит от клиента.",
        inputSchema=ToolSchema(properties=buildJsonObject {putJsonObject("id") {put("type","string")}},required=listOf("id")),
        toolAnnotations=ToolAnnotations(readOnlyHint=true,destructiveHint=false,idempotentHint=true,openWorldHint=false)) { request -> withContext(Dispatchers.IO) {
        try {
            require("tracker:read" in identity.scopes)
            val args=requireNotNull(request.arguments); require(args.keys==setOf("id"))
            val file=store.attachment(identity.userId,args.str("id")) ?: throw ApiFailure(404,"Файл не найден")
            require(file.first.size<=5*1024*1024) {"Для передачи AI поддерживаются файлы до 5 МБ"}
            val blob=attachmentStorage.get(file.second) ?: throw ApiFailure(404,"Файл не найден")
            val bytes=Base64.getEncoder().encodeToString(blob)
            val payload: ContentBlock=if(file.first.mediaType in setOf("image/png","image/jpeg","image/webp")) ImageContent(data=bytes,mimeType=file.first.mediaType)
                else EmbeddedResource(resource=BlobResourceContents(blob=bytes,uri="habittracker://attachment/${file.first.id}",mimeType=file.first.mediaType))
            CallToolResult(content=listOf(TextContent(WireJson.encodeToString(file.first)),payload),isError=false)
        } catch(e: IllegalArgumentException) {CallToolResult(content=listOf(TextContent(e.message ?: "Некорректный запрос")),isError=true)}
          catch(e: ApiFailure) {CallToolResult(content=listOf(TextContent(e.message)),isError=true)}
    }
    }
    return server
}
private fun JsonObject.str(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key must be a string")
private fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.let { str(key) }
private fun JsonObject.long(key: String): Long = get(key)?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: throw IllegalArgumentException("$key must be an integer")
