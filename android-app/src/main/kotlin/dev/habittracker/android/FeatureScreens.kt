package dev.habittracker.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import dev.habittracker.shared.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val shortDate=DateTimeFormatter.ofPattern("d MMM",Locale.forLanguageTag("ru"))
@Composable private fun FeatureDialog(title: String,close: ()->Unit,body: @Composable ColumnScope.()->Unit) {
    AlertDialog(onDismissRequest=close,title={Text(title)},text={Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp),content=body)},confirmButton={TextButton(onClick=close){Text("Закрыть")}})
}
@Composable fun QuickEntryDialog(close: ()->Unit,create: (TaskContent)->Unit) {
    var input by rememberSaveableState("")
    var draft by remember {mutableStateOf<QuickDraft?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    FeatureDialog("Быстрый ввод",close) {
        Text("Например: «Сдать отчёт завтра в 18:00». Сначала проверь распознанную задачу.")
        OutlinedTextField(input,{input=it;draft=null;error=null},label={Text("Что нужно сделать?")},modifier=Modifier.fillMaxWidth())
        Button(onClick={try{draft=parseQuickTask(input,LocalDate.now(),ZoneId.systemDefault().id);error=null}catch(e:Exception){error=e.message}},enabled=input.isNotBlank()){Text("Распознать")}
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        draft?.let {value->
            Text(value.content.title,fontWeight=FontWeight.Bold)
            Text("Дата: ${value.content.dueDate ?: "без срока"} ${value.content.dueTime.orEmpty()}")
            value.warnings.forEach {Text(it,color=MaterialTheme.colorScheme.tertiary)}
            Button(onClick={create(value.content)}){Text("Открыть редактор перед сохранением")}
        }
    }
}
@Composable private fun rememberSaveableState(value: String)=androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(value)}

@Composable fun OrganizationFields(value: Organization,id: String,all: List<Item>,change: (Organization)->Unit,open: (Item)->Unit) {
    var tags by remember(id){mutableStateOf(value.tags.joinToString(", "))}
    var projectMenu by remember {mutableStateOf(false)};var goalMenu by remember {mutableStateOf(false)}
    var links by remember {mutableStateOf(false)};var query by remember {mutableStateOf("")}
    val projects=all.filter {!it.deleted && it.content is ProjectContent && it.id!=id}
    OutlinedTextField(tags,{tags=it;change(value.copy(tags=it.split(',').map {it.trim().removePrefix("#")}.filter {it.isNotEmpty()}.toSet()))},label={Text("Теги через запятую")},modifier=Modifier.fillMaxWidth())
    Box { TextButton(onClick={projectMenu=true}){Text("Проект: ${projects.firstOrNull {it.id==value.projectId}?.content?.title ?: "не выбран"}")}
        DropdownMenu(projectMenu,{projectMenu=false}){DropdownMenuItem(text={Text("Без проекта")},onClick={change(value.copy(projectId=null));projectMenu=false});projects.forEach {item->DropdownMenuItem(text={Text(item.content.title)},onClick={change(value.copy(projectId=item.id));projectMenu=false})}}
    }
    Box {TextButton(onClick={goalMenu=true}){Text("Цель: ${projects.firstOrNull {it.id==value.goalId}?.content?.title ?: "не выбрана"}")}
        DropdownMenu(goalMenu,{goalMenu=false}){DropdownMenuItem(text={Text("Без цели")},onClick={change(value.copy(goalId=null));goalMenu=false});projects.filter {(it.content as ProjectContent).isGoal}.forEach {item->DropdownMenuItem(text={Text(item.content.title)},onClick={change(value.copy(goalId=item.id));goalMenu=false})}}
    }
    TextButton(onClick={links=!links}){Text("Связанные записи: ${value.linkedIds.size}")}
    value.linkedIds.forEach {linkedId->all.firstOrNull {it.id==linkedId && !it.deleted}?.let {item->TextButton(onClick={open(item)}){Text("↗ ${item.content.title}")}}}
    if(links){
        OutlinedTextField(query,{query=it},label={Text("Найти запись для связи")},modifier=Modifier.fillMaxWidth())
        Column(Modifier.heightIn(max=220.dp).verticalScroll(rememberScrollState())) {
            all.filter {!it.deleted && it.id!=id && it.content !is HabitMarkContent && it.content !is WeekPlanContent && it.content.searchableText().contains(query,true)}.take(100).forEach {item->
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(item.id in value.linkedIds,{checked->change(value.copy(linkedIds=if(checked)value.linkedIds+item.id else value.linkedIds-item.id))});Text(item.content.title)}
            }
        }
    }
}
@Composable fun ProjectsDialog(all: List<Item>,close: ()->Unit,edit: (Item)->Unit,create: (Boolean)->Unit) {
    FeatureDialog("Проекты и цели",close) {
        Row {TextButton(onClick={create(false)}){Text("+ Проект")};TextButton(onClick={create(true)}){Text("+ Цель")}}
        val projects=all.filter {!it.deleted && it.content is ProjectContent}
        if(projects.isEmpty())Text("Объедини задачи, привычки и заметки вокруг одного результата.")
        projects.forEach {item->val project=item.content as ProjectContent;val members=all.filter {!it.deleted && (it.content.organization.projectId==item.id || it.content.organization.goalId==item.id)};val tasks=members.mapNotNull {it.content as? TaskContent}
            Card(onClick={edit(item)}){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text((if(project.isGoal)"◎ " else "▣ ")+project.title,fontWeight=FontWeight.Bold)
                if(project.description.isNotBlank())Text(project.description)
                Text("Связано записей: ${members.size} · задач выполнено: ${tasks.count {it.completed}}/${tasks.size}")
                if(tasks.isNotEmpty())LinearProgressIndicator(progress={tasks.count {it.completed}.toFloat()/tasks.size},modifier=Modifier.fillMaxWidth())
            }}
        }
    }
}
@Composable fun SearchDialog(all: List<Item>,close: ()->Unit,open: (Item)->Unit) {
    var query by rememberSaveableState("");var kind by remember {mutableStateOf("")};var project by remember {mutableStateOf<String?>(null)};var tag by remember {mutableStateOf<String?>(null)}
    val records=all.filter {!it.deleted && it.content !is HabitMarkContent}
    FeatureDialog("Поиск по всем записям",close){
        OutlinedTextField(query,{query=it},label={Text("Название, текст или тег")},modifier=Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("" to "Все","task" to "Задачи","note" to "Заметки","habit" to "Привычки","project" to "Проекты").forEach {(id,label)->FilterChip(kind==id,{kind=id},label={Text(label)})}}
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(project==null,{project=null},label={Text("Все проекты")});records.filter {it.content is ProjectContent}.forEach {item->FilterChip(project==item.id,{project=if(project==item.id)null else item.id},label={Text(item.content.title)})}}
        val tags=records.flatMap {it.content.organization.tags}.toSortedSet()
        if(tags.isNotEmpty())Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){FilterChip(tag==null,{tag=null},label={Text("Все теги")});tags.forEach {value->FilterChip(tag==value,{tag=if(tag==value)null else value},label={Text("#$value")})}}
        val matches=records.filter {(kind.isBlank() || it.content.typeName()==kind) && (project==null || it.content.organization.projectId==project || it.content.organization.goalId==project || it.id==project) && (tag==null || tag in it.content.organization.tags) && it.content.searchableText().contains(query,true)}.sortedByDescending {it.updatedAt}
        Text("Найдено: ${matches.size}")
        matches.take(200).forEach {item->OutlinedCard(onClick={open(item)}){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(item.content.title,fontWeight=FontWeight.Bold);Text(item.content.searchableText().take(160),maxLines=3);if(item.content.organization.tags.isNotEmpty())Text(item.content.organization.tags.joinToString(" "){"#$it"},style=MaterialTheme.typography.labelSmall)}}}
        if(matches.size>200)Text("Уточни поиск, чтобы увидеть остальные результаты.")
    }
}
@Composable fun WeekDialog(all: List<Item>,close: ()->Unit,open: (Item)->Unit,move: (Item,LocalDate)->Unit) {
    var monday by remember {mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))}
    var moving by remember {mutableStateOf<Item?>(null)}
    FeatureDialog("План недели",close){
        Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={monday=monday.minusWeeks(1)}){Text("←")};Text("${monday.format(shortDate)} — ${monday.plusDays(6).format(shortDate)}",Modifier.weight(1f));TextButton(onClick={monday=monday.plusWeeks(1)}){Text("→")}}
        moving?.let {item->
            Text("Куда перенести «${item.content.title}»?")
            Row(Modifier.horizontalScroll(rememberScrollState())){repeat(7){index->val date=monday.plusDays(index.toLong());TextButton(onClick={move(item,date);moving=null}){Text(date.format(shortDate))}}}
            TextButton(onClick={moving=null}){Text("Отмена переноса")}
        }
        repeat(7){index->val date=monday.plusDays(index.toLong());val tasks=all.filter {!it.deleted && (it.content as? TaskContent)?.dueDate==date.toString()};val habits=all.filter {!it.deleted && (it.content as? HabitContent)?.isDue(date)==true}
            Card {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
                Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMM",Locale.forLanguageTag("ru"))),fontWeight=FontWeight.Bold)
                if(tasks.isEmpty() && habits.isEmpty())Text("Свободный день")
                tasks.forEach {item->Row(verticalAlignment=Alignment.CenterVertically){TextButton(onClick={open(item)},modifier=Modifier.weight(1f)){Text((if((item.content as TaskContent).completed)"✓ " else "")+item.content.title)};TextButton(onClick={moving=item}){Text("Перенести")}}}
                habits.forEach {item->TextButton(onClick={open(item)}){Text("↻ ${item.content.title}")}}
            }}
        }
        val undated=all.filter {!it.deleted && (it.content as? TaskContent)?.let {!it.completed && it.dueDate==null}==true}
        if(undated.isNotEmpty()){Text("Без срока",fontWeight=FontWeight.Bold);undated.forEach {item->Row(verticalAlignment=Alignment.CenterVertically){Text(item.content.title,Modifier.weight(1f));TextButton(onClick={moving=item}){Text("Запланировать")}}}}
    }
}
@Composable fun StatisticsDialog(all: List<Item>,close: ()->Unit) {
    var length by remember {mutableIntStateOf(30)}
    FeatureDialog("Статистика привычек",close){
        Row {FilterChip(length==7,{length=7},label={Text("7 дней")});Spacer(Modifier.width(8.dp));FilterChip(length==30,{length=30},label={Text("30 дней")})}
        Text("Пропуск — часть процесса. Отметь следующий день и продолжай; историю можно исправить в разделе привычек.")
        all.filter {!it.deleted && it.content is HabitContent}.forEach {item->val today=LocalDate.now(ZoneId.of((item.content as HabitContent).zoneId));val start=today.minusDays(length.toLong()-1);val stats=habitPeriod(item,all,start,today)
            Card {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text(stats.title,fontWeight=FontWeight.Bold);Text("${stats.completedDays}/${stats.targetDays} · ${stats.percentage}% · прошлый период ${stats.previousPercentage}%")
                val completed=stats.completedDates.toSet()
                repeat((length+6)/7){week->Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){repeat(7){day->val offset=week*7+day;if(offset<length){val date=start.plusDays(offset.toLong());val done=date.toString() in completed;Box(Modifier.size(30.dp).background(if(done)MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center){Text(date.dayOfMonth.toString(),style=MaterialTheme.typography.labelSmall,color=if(done)MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)}}}}}
            }}
        }
    }
}
@Composable fun ReviewDialog(all: List<Item>,close: ()->Unit,openPlan: (Item)->Unit) {
    var monday by remember {mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))}
    val context=LocalContext.current
    val review=remember(all,monday){weekReview(all,monday,ZoneId.systemDefault())}
    FeatureDialog("Разбор недели с AI",close){
        Row {TextButton(onClick={monday=monday.minusWeeks(1)}){Text("←")};Text(monday.format(shortDate),Modifier.weight(1f));TextButton(onClick={monday=monday.plusWeeks(1)}){Text("→")}}
        Text("Выполнено задач за неделю: ${review.completedTasks}")
        if(review.completedWithoutDate>0)Text("У ${review.completedWithoutDate} старых выполненных задач дата завершения неизвестна; они не включены в недельный итог.")
        Text("Осталось задач: ${review.remainingTasks.size}")
        review.tasksByDay.toSortedMap().forEach {(date,count)->Text("$date: $count задач")}
        review.habits.forEach {Text("${it.title}: ${it.completedDays}/${it.targetDays}")}
        Text("Разбор делает твой внешний AI-клиент, подключённый через MCP к этому же аккаунту. Он читает факты и сохраняет предложение; даты меняются только после подтверждения здесь.")
        Button(onClick={val text="Разбери мою неделю с ${review.weekStart}. Получи get_week_review с week_start=${review.weekStart}, zone_id=${ZoneId.systemDefault().id}. Уточни ограничения времени, предложи реалистичный план и сохрани его через propose_week_plan. Не меняй даты задач автоматически.";(context.getSystemService(android.content.ClipboardManager::class.java)).setPrimaryClip(android.content.ClipData.newPlainText("Запрос AI",text))}){Text("Скопировать запрос для AI")}
        val plans=all.filter {!it.deleted && it.content is WeekPlanContent}.sortedByDescending {it.updatedAt}
        if(plans.isEmpty())Text("Предложений пока нет. После ответа AI они появятся здесь при синхронизации.")
        plans.forEach {item->OutlinedCard(onClick={openPlan(item)}){Column(Modifier.fillMaxWidth().padding(12.dp)){Text(item.content.title,fontWeight=FontWeight.Bold);Text(if((item.content as WeekPlanContent).applied)"Применён" else "Ожидает подтверждения")}}}
    }
}
@Composable fun PlanDialog(item: Item,all: List<Item>,busy: Boolean,close: ()->Unit,apply: ()->Unit) {
    val plan=item.content as WeekPlanContent;var confirm by remember {mutableStateOf(false)}
    FeatureDialog(item.content.title,close){Text(plan.summary);plan.moves.forEach {move->val task=all.firstOrNull {it.id==move.itemId};Text(task?.content?.title ?: "Задача недоступна",fontWeight=FontWeight.Bold);Text("${(task?.content as? TaskContent)?.dueDate ?: "без срока"} → ${move.dueDate}");if(move.reason.isNotBlank())Text(move.reason)}
        if(plan.applied)Text("Этот план уже применён")else Button(onClick={confirm=true},enabled=!busy){Text("Применить предложение")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Применить план?")},text={Text("Перенести ${plan.moves.size} задач на показанные даты? Если задачи успели измениться, весь план будет отклонён.")},confirmButton={TextButton(onClick={confirm=false;apply()},enabled=!busy){Text("Подтвердить")}},dismissButton={TextButton(onClick={confirm=false}){Text("Отмена")}})
}
@Composable fun ReminderSettingsDialog(initial: ReminderSettings,close: ()->Unit,save: (ReminderSettings)->Unit) {
    var value by remember {mutableStateOf(initial)};var error by remember {mutableStateOf<String?>(null)}
    FeatureDialog("Напоминания",close){
        Row(verticalAlignment=Alignment.CenterVertically){Switch(value.quietEnabled,{value=value.copy(quietEnabled=it)});Text("Тихие часы")}
        if(value.quietEnabled){OutlinedTextField(value.quietStart,{value=value.copy(quietStart=it)},label={Text("Начало · чч:мм")});OutlinedTextField(value.quietEnd,{value=value.copy(quietEnd=it)},label={Text("Конец · чч:мм")})}
        Row(verticalAlignment=Alignment.CenterVertically){Switch(value.summaryEnabled,{value=value.copy(summaryEnabled=it,summaryOnly=value.summaryOnly && it)});Text("Ежедневная сводка")}
        if(value.summaryEnabled){OutlinedTextField(value.summaryTime,{value=value.copy(summaryTime=it)},label={Text("Время сводки · чч:мм")});Row(verticalAlignment=Alignment.CenterVertically){Switch(value.summaryOnly,{value=value.copy(summaryOnly=it)});Text("Только сводка, без отдельных напоминаний")}}
        Text("В уведомлениях доступны «Выполнено», «Через час» и «Завтра». Android может задерживать уведомления для экономии батареи.")
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Button(onClick={try{value.validate();save(value)}catch(e:Exception){error=e.message}}){Text("Сохранить настройки")}
    }
}
@Composable fun BackupDialog(preview: BackupPreview?,busy: Boolean,close: ()->Unit,export: ()->Unit,choose: ()->Unit,restore: ()->Unit,cancel: ()->Unit) {
    FeatureDialog("Резервные копии",close){
        Text("ZIP содержит тексты и вложения, включая неотправленные записи и обе версии конфликтов. Пароли, сессии и OAuth-ключи в него не входят. Храни копию в безопасном месте: содержимое не зашифровано.")
        Button(onClick=export,enabled=!busy){Text("Экспортировать ZIP")};OutlinedButton(onClick=choose,enabled=!busy){Text("Выбрать копию для восстановления")}
        preview?.let {Text("Копия от ${it.manifest.exportedAt}");Text("Записей: ${it.manifest.items.size} · файлов: ${it.manifest.files.size} · конфликтов: ${it.manifest.conflicts.size}");Text("Будут созданы копии. Текущие записи не перезаписываются. Повторный импорт того же файла не создаёт дубликаты. Версии конфликтов и старые AI-планы сохраняются отдельными заметками.");Button(onClick=restore,enabled=!busy){Text("Восстановить копиями")};TextButton(onClick=cancel,enabled=!busy){Text("Отмена импорта")}}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable fun ShareDraftDialog(draft: SharedDraft,busy: Boolean,accept: (SharedDraft,Boolean)->Unit,cancel: ()->Unit) {
    var title by remember(draft.id){mutableStateOf(draft.title)};var text by remember(draft.id){mutableStateOf(draft.text)}
    FeatureDialog("Сохранить из другого приложения",cancel){
        OutlinedTextField(title,{title=it},label={Text("Название")},modifier=Modifier.fillMaxWidth());OutlinedTextField(text,{text=it},label={Text("Текст или ссылка")},minLines=3,modifier=Modifier.fillMaxWidth());draft.files.forEach {Text("📎 ${it.name}")}
        Button(onClick={accept(draft.copy(title=title,text=text),false)},enabled=!busy && title.isNotBlank()){Text("Создать заметку")}
        if(draft.files.isEmpty())OutlinedButton(onClick={accept(draft.copy(title=title,text=text),true)},enabled=!busy && title.isNotBlank()){Text("Создать задачу")}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
