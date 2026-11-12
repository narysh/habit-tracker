package dev.habittracker.probe

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import kotlinx.serialization.json.*
import java.net.URI

fun main() {
    val config = Config.fromEnvironment()
    embeddedServer(Netty, host = "127.0.0.1", port = config.port) { probeModule(config) }.start(wait = true)
}

private val grantKey = io.ktor.util.AttributeKey<Grant>("grant")

fun Application.probeModule(config: Config, oauth: OAuth = OAuth(config), store: TaskStore = TaskStore(config.dataFile)) {
    install(RequestBodyLimit) { bodyLimit { 16384 } }
    val guards = createApplicationPlugin("ProbeGuards") {
        onCall { call ->
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header("Referrer-Policy", "no-referrer")
            call.response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            if ((call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0) > 16384) {
                call.respondText("Request too large", status = HttpStatusCode.PayloadTooLarge)
                return@onCall
            }
            val origin = call.request.header(HttpHeaders.Origin)
            if (origin != null && origin !in setOf(config.publicUrl, "https://chatgpt.com")) {
                call.respondText("Origin rejected", status = HttpStatusCode.Forbidden)
                return@onCall
            }
            if (call.request.path() == "/mcp") {
                val token = call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
                val grant = token?.let(oauth::authenticate)
                if (grant == null) {
                    call.response.header(HttpHeaders.WWWAuthenticate, "Bearer resource_metadata=\"${config.publicUrl}/.well-known/oauth-protected-resource\", scope=\"tracker:read tracker:write\"")
                    call.respondText("Authentication required", status = HttpStatusCode.Unauthorized)
                } else call.attributes.put(grantKey, grant)
            }
        }
    }
    install(guards)

    routing {
        get("/health") { call.json(buildJsonObject { put("status", "ok"); put("stage", "mcp-probe") }) }
        get("/.well-known/oauth-protected-resource") {
            call.json(buildJsonObject {
                put("resource", config.resource)
                putJsonArray("authorization_servers") { add(config.publicUrl) }
                putJsonArray("scopes_supported") { add("tracker:read"); add("tracker:write") }
            })
        }
        get("/.well-known/oauth-authorization-server") {
            call.json(buildJsonObject {
                put("issuer", config.publicUrl)
                put("authorization_response_iss_parameter_supported", true)
                put("authorization_endpoint", "${config.publicUrl}/oauth/authorize")
                put("token_endpoint", "${config.publicUrl}/oauth/token")
                put("revocation_endpoint", "${config.publicUrl}/oauth/revoke")
                putJsonArray("response_types_supported") { add("code") }
                putJsonArray("grant_types_supported") { add("authorization_code") }
                putJsonArray("token_endpoint_auth_methods_supported") { add("client_secret_post") }
                putJsonArray("code_challenge_methods_supported") { add("S256") }
                putJsonArray("scopes_supported") { add("tracker:read"); add("tracker:write") }
            })
        }
        get("/oauth/authorize") {
            try {
                val nonce = oauth.begin(call.request.queryParameters.entries().associate { it.key to it.value.single() })
                val auth = requireNotNull(oauth.pending(nonce))
                call.respondText(consentPage(nonce, auth), ContentType.Text.Html)
            } catch (e: IllegalArgumentException) { call.badRequest(e.message ?: "Invalid authorization request") }
              catch (e: IllegalStateException) { call.badRequest(e.message ?: "Invalid authorization request") }
        }
        post("/oauth/consent") {
            try {
                val form = call.receiveParameters()
                val redirect = oauth.consent(form["nonce"].orEmpty(), form["secret"].orEmpty(), form["decision"] == "allow")
                call.respondRedirect(redirect)
            } catch (e: IllegalArgumentException) { call.badRequest(e.message ?: "Invalid consent") }
              catch (e: IllegalStateException) { call.badRequest(e.message ?: "Invalid consent") }
        }
        post("/oauth/token") {
            try {
                val form = call.receiveParameters().entries().associate { it.key to it.value.single() }
                val (token, grant) = oauth.exchange(form)
                call.json(buildJsonObject {
                    put("access_token", token); put("token_type", "Bearer"); put("expires_in", 3600)
                    put("scope", grant.scopes.sorted().joinToString(" "))
                })
            } catch (_: IllegalArgumentException) { call.badRequest("invalid_grant") }
              catch (_: IllegalStateException) { call.badRequest("invalid_grant") }
        }
        post("/oauth/revoke") {
            val form = call.receiveParameters()
            if (form["client_id"] != config.clientId || !constantTimeEquals(form["client_secret"].orEmpty(), config.clientSecret)) {
                call.respondText("Invalid client", status = HttpStatusCode.Unauthorized)
            } else { oauth.revoke(form["token"].orEmpty()); call.respond(HttpStatusCode.OK) }
        }
    }
    mcpStatelessStreamableHttp(
        path = "/mcp",
        allowedHosts = listOf(URI(config.publicUrl).host, "127.0.0.1", "localhost"),
        allowedOrigins = listOf(config.publicUrl, "https://chatgpt.com"),
    ) { trackerServer(store, call.attributes[grantKey]) }
}

private suspend fun ApplicationCall.json(value: JsonObject) = respondText(value.toString(), ContentType.Application.Json)
private suspend fun ApplicationCall.badRequest(message: String) = respondText(
    buildJsonObject { put("error", message) }.toString(), ContentType.Application.Json, HttpStatusCode.BadRequest,
)
private fun html(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
private fun consentPage(nonce: String, auth: Authorization): String = """
<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Подключение Habit Tracker</title><style>body{font:18px system-ui;max-width:640px;margin:60px auto;padding:20px}input,button{font:inherit;padding:12px;margin:8px 0;max-width:100%;box-sizing:border-box}input{width:100%}</style>
<h1>Подключить тестовый Habit Tracker</h1><p>Это проверочный сервер с отдельными тестовыми задачами.</p>
<p>Клиент: <strong>${html(auth.clientId)}</strong></p><p>Разрешения: ${html(auth.scopes.joinToString(", "))}</p>
<p>Возврат: ${html(auth.redirectUri)}</p><p>Введите секрет подключения из локального файла .local/probe.env. Не отправляйте его в чат.</p>
<form method="post" action="/oauth/consent"><input type="hidden" name="nonce" value="${html(nonce)}">
<label>Секрет подключения<input type="password" name="secret" required autocomplete="off" maxlength="128"></label>
<button name="decision" value="allow">Разрешить</button> <button name="decision" value="deny">Отклонить</button></form></html>
""".trimIndent()
