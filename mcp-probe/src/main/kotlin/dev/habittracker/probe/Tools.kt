package dev.habittracker.probe

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*

private val toolJson = Json { encodeDefaults = true }

fun trackerServer(store: TaskStore, grant: Grant): Server {
    val server = Server(
        Implementation("habit-tracker-probe", "0.1.0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    )
    server.addTool(
        name = "get_tasks",
        description = "Read the current tasks from the integration probe. Use this before answering what remains or choosing a task to delete. Only disposable test data is stored here.",
        inputSchema = schema(),
        toolAnnotations = ToolAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ) { guarded(grant, "tracker:read") { toolJson.encodeToJsonElement(store.list()) } }
    server.addTool(
        name = "create_task",
        description = "Create a test task when the user requests it. Generate a UUID operation_id once and reuse it for retries of this same creation. Do not invent the task title.",
        inputSchema = schema("title" to "string", "operation_id" to "string"),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    ) { request -> guarded(grant, "tracker:write") {
        val args = requireNotNull(request.arguments)
        require(args.keys == setOf("title", "operation_id")) { "Unexpected arguments" }
        toolJson.encodeToJsonElement(store.create(args.string("title"), args.string("operation_id")))
    } }
    server.addTool(
        name = "delete_task",
        description = "Delete ONE test task explicitly selected by the user. Destructive: the AI client must ask for confirmation before invoking this tool. First call get_tasks to obtain its current id and version. If the user cancels, do not call this tool.",
        inputSchema = schema("id" to "string", "expected_version" to "integer"),
        toolAnnotations = ToolAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false, openWorldHint = false),
    ) { request -> guarded(grant, "tracker:write") {
        val args = requireNotNull(request.arguments)
        require(args.keys == setOf("id", "expected_version")) { "Unexpected arguments" }
        val version = (args["expected_version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        require(version != null && version > 0) { "expected_version must be a positive integer" }
        toolJson.encodeToJsonElement(store.delete(args.string("id"), version))
    } }
    return server
}

private fun schema(vararg fields: Pair<String, String>) = ToolSchema(
    properties = buildJsonObject {
        fields.forEach { (name, type) -> putJsonObject(name) {
            put("type", type)
            if (name == "title") { put("minLength", 1); put("maxLength", 300) }
            if (name in setOf("id", "operation_id")) put("format", "uuid")
            if (type == "integer") put("minimum", 1)
        } }
    },
    required = fields.map { it.first },
)

private fun JsonObject.string(key: String): String {
    val value = this[key] as? JsonPrimitive
    require(value?.isString == true) { "$key must be a string" }
    return value.content
}

private inline fun guarded(grant: Grant, scope: String, action: () -> JsonElement): CallToolResult = try {
    require(scope in grant.scopes) { "Missing scope $scope; reconnect with the required permission" }
    CallToolResult(content = listOf(TextContent(action().toString())), isError = false)
} catch (e: IllegalArgumentException) {
    CallToolResult(content = listOf(TextContent(e.message ?: "Invalid arguments")), isError = true)
} catch (e: IllegalStateException) {
    CallToolResult(content = listOf(TextContent(e.message ?: "Operation rejected")), isError = true)
}
