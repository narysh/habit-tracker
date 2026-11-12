package dev.habittracker.backend

import dev.habittracker.shared.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.modelcontextprotocol.kotlin.sdk.server.mcpStatelessStreamableHttp
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

private val identityKey = AttributeKey<Identity>("identity")
private val tokenKey = AttributeKey<String>("token")
fun main() {
    val settings = Settings.environment()
    val store = Store(settings.jdbcUrl,settings.dbUser,settings.dbPassword)
    embeddedServer(Netty,host="0.0.0.0",port=settings.port) { backendModule(settings,store) }.start(wait=true)
}
fun Application.backendModule(settings: Settings, store: Store) {
    val logger = environment.log
    val oauth = AccountOAuth(settings,store)
    val throttle = LoginThrottle()
    Files.createDirectories(settings.files)
    install(ContentNegotiation) { json(Json(WireJson) { explicitNulls=false; serializersModule=io.modelcontextprotocol.kotlin.sdk.types.McpJson.serializersModule }) }
    install(RequestBodyLimit) { bodyLimit { if(it.request.path().startsWith("/api/v1/attachments/")) 20L*1024*1024 else 2L*1024*1024 } }
    install(StatusPages) {
        exception<io.ktor.server.plugins.PayloadTooLargeException> { call,_ -> call.respond(HttpStatusCode.PayloadTooLarge,ApiError("Слишком большой запрос")) }
        exception<ApiFailure> { call,e -> call.respond(HttpStatusCode.fromValue(e.status),ApiError(e.message)) }
        exception<IllegalArgumentException> { call,e -> call.respond(HttpStatusCode.BadRequest,ApiError(e.message ?: "Некорректный запрос")) }
        exception<kotlinx.serialization.SerializationException> { call,_ -> call.respond(HttpStatusCode.BadRequest,ApiError("Некорректный JSON")) }
        exception<Throwable> { call,e ->
            if(e is kotlinx.coroutines.CancellationException) throw e
            logger.error("Request failed: ${e.javaClass.simpleName}")
            call.respond(HttpStatusCode.InternalServerError,ApiError("Внутренняя ошибка сервера"))
        }
    }
    install(createApplicationPlugin("Access") {
        onCall { call ->
            call.response.header(HttpHeaders.CacheControl,"no-store")
            call.response.header("X-Content-Type-Options","nosniff")
            call.response.header("Referrer-Policy","no-referrer")
            call.response.header("Content-Security-Policy","default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
            val origin = call.request.header(HttpHeaders.Origin)
            if(origin != null && origin !in setOf(settings.publicUrl,"https://chatgpt.com")) throw ApiFailure(403,"Origin rejected")
            val path = call.request.path()
            if(path == "/mcp" || (path.startsWith("/api/v1/") && path !in setOf("/api/v1/auth/register","/api/v1/auth/login"))) {
                val token = call.request.header(HttpHeaders.Authorization)?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ").orEmpty()
                val identity = withContext(Dispatchers.IO) { store.authenticate(token) }
                if(identity == null) {
                    call.response.header(HttpHeaders.WWWAuthenticate,"Bearer resource_metadata=\"${settings.publicUrl}/.well-known/oauth-protected-resource\", scope=\"tracker:read tracker:write\"")
                    throw ApiFailure(401,"Войди в аккаунт")
                }
                call.attributes.put(identityKey,identity); call.attributes.put(tokenKey,token)
                val write = call.request.httpMethod != HttpMethod.Get
                if(path != "/mcp" && (if(write) "tracker:write" else "tracker:read") !in identity.scopes) throw ApiFailure(403,"Недостаточно прав")
            }
        }
    })
    routing {
        get("/health") { call.respond(buildJsonObject { put("status","ok"); put("stage","backend") }) }
        route("/api/v1") {
            post("/auth/register") {
                throttle.check(call.request.local.remoteHost)
                val credentials = call.receive<Credentials>()
                call.respond(HttpStatusCode.Created,withContext(Dispatchers.IO) { store.register(credentials) })
            }
            post("/auth/login") {
                throttle.check(call.request.local.remoteHost)
                val credentials = call.receive<Credentials>()
                call.respond(withContext(Dispatchers.IO) { store.login(credentials) })
            }
            post("/logout") { withContext(Dispatchers.IO) { store.revoke(call.attributes[tokenKey]) }; call.respond(HttpStatusCode.NoContent) }
            post("/account/revoke-sessions") { withContext(Dispatchers.IO) { store.revokeAll(call.attributes[identityKey].userId) }; call.respond(HttpStatusCode.NoContent) }
            get("/items") { call.respond(withContext(Dispatchers.IO) { store.list(call.attributes[identityKey].userId) }) }
            get("/items/{id}") {
                val id = call.parameters["id"].orEmpty(); UUID.fromString(id)
                call.respond(withContext(Dispatchers.IO) { store.get(call.attributes[identityKey].userId,id) } ?: throw ApiFailure(404,"Запись не найдена"))
            }
            post("/mutations") {
                val mutation = call.receive<Mutation>()
                call.respond(withContext(Dispatchers.IO) { store.mutate(call.attributes[identityKey].userId,mutation) })
            }
            post("/sync") {
                val request = call.receive<SyncRequest>()
                call.respond(withContext(Dispatchers.IO) { store.sync(call.attributes[identityKey].userId,request) })
            }
            get("/notes/{id}/attachments") {
                val id = call.parameters["id"].orEmpty(); UUID.fromString(id)
                call.respond(withContext(Dispatchers.IO) { store.attachments(call.attributes[identityKey].userId,id) })
            }
            put("/attachments/{id}") {
                val id = call.parameters["id"].orEmpty(); UUID.fromString(id)
                val userId = call.attributes[identityKey].userId
                val noteId = call.request.queryParameters["noteId"].orEmpty(); UUID.fromString(noteId)
                val name = call.request.queryParameters["name"].orEmpty()
                require(name.isNotBlank() && name.length <= 255 && '\r' !in name && '\n' !in name)
                val mime = call.request.queryParameters["mediaType"] ?: "application/octet-stream"
                require(mime.length <= 200 && Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+").matches(mime))
                val bytes = call.receive<ByteArray>()
                require(bytes.size <= 20*1024*1024)
                val key = UUID.randomUUID().toString()
                val target = settings.files.resolve(key)
                withContext(Dispatchers.IO) {
                    Files.write(target,bytes)
                    try {
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        val savedKey = store.saveAttachment(userId,Attachment(id,noteId,name,mime,bytes.size.toLong()),key,hash)
                        if(savedKey != key) Files.deleteIfExists(target)
                    } catch(e: Throwable) { Files.deleteIfExists(target); throw e }
                }
                call.respond(Attachment(id,noteId,name,mime,bytes.size.toLong()))
            }
            get("/attachments/{id}") {
                val id = call.parameters["id"].orEmpty(); UUID.fromString(id)
                val file = withContext(Dispatchers.IO) { store.attachment(call.attributes[identityKey].userId,id) } ?: throw ApiFailure(404,"Файл не найден")
                call.response.header(HttpHeaders.ContentDisposition,"attachment; filename*=UTF-8''${encode(file.first.name)}")
                call.respondFile(settings.files.resolve(file.second).toFile())
            }
        }
        get("/.well-known/oauth-protected-resource") { call.respond(buildJsonObject {
            put("resource",settings.resource); putJsonArray("authorization_servers") { add(settings.publicUrl) }
            putJsonArray("scopes_supported") { add("tracker:read"); add("tracker:write") }
        }) }
        get("/.well-known/oauth-authorization-server") { call.respond(buildJsonObject {
            put("issuer",settings.publicUrl); put("authorization_response_iss_parameter_supported",true)
            put("authorization_endpoint","${settings.publicUrl}/oauth/authorize"); put("token_endpoint","${settings.publicUrl}/oauth/token")
            put("revocation_endpoint","${settings.publicUrl}/oauth/revoke")
            putJsonArray("response_types_supported") { add("code") }; putJsonArray("grant_types_supported") { add("authorization_code");add("refresh_token") }
            putJsonArray("token_endpoint_auth_methods_supported") { add("client_secret_post") }; putJsonArray("code_challenge_methods_supported") { add("S256") }
            putJsonArray("scopes_supported") { add("tracker:read"); add("tracker:write") }
        }) }
        get("/oauth/authorize") {
            val (nonce,request) = oauth.begin(call.request.queryParameters.entries().associate { it.key to it.value.single() })
            call.respondText("""<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Habit Tracker</title>
                <style>body{font:18px system-ui;max-width:600px;margin:50px auto;padding:24px}input,button{font:inherit;padding:12px;margin:8px 0}input{width:90%}</style>
                <h1>Подключить Habit Tracker</h1><p>Разрешения: ${escape(request.scopes.joinToString())}</p><p>Клиент: ${escape(settings.clientId)}</p><p>Возврат: ${escape(request.redirect)}</p>
                <form method="post" action="/oauth/consent"><input type="hidden" name="nonce" value="${escape(nonce)}"><label>Email<input name="email" type="email" autocomplete="username"></label>
                <label>Пароль<input name="password" type="password" autocomplete="current-password" maxlength="128"></label><button name="decision" value="allow">Разрешить</button><button name="decision" value="deny">Отменить</button></form></html>""".trimIndent(),ContentType.Text.Html)
        }
        post("/oauth/consent") {
            throttle.check(call.request.local.remoteHost)
            val form = call.receiveParameters()
            val allow = form["decision"] == "allow"
            val identity = if(allow) withContext(Dispatchers.IO) { store.checkPassword(Credentials(form["email"].orEmpty(),form["password"].orEmpty())) } ?: throw ApiFailure(401,"Неверный email или пароль") else null
            call.respondRedirect(oauth.finish(form["nonce"].orEmpty(),identity,allow))
        }
        post("/oauth/token") {
            val params = call.receiveParameters().entries().associate { it.key to it.value.single() }
            call.respond(withContext(Dispatchers.IO) { oauth.exchange(params) })
        }
        post("/oauth/revoke") {
            val form = call.receiveParameters()
            require(form["client_id"] == settings.clientId && equalSecrets(form["client_secret"].orEmpty(),settings.clientSecret))
            withContext(Dispatchers.IO) { store.revoke(form["token"].orEmpty()) }; call.respond(HttpStatusCode.OK)
        }
    }
    mcpStatelessStreamableHttp(path="/mcp",allowedHosts=listOf(URI(settings.publicUrl).host,"localhost","127.0.0.1"),allowedOrigins=listOf(settings.publicUrl,"https://chatgpt.com")) {
        mainMcp(store,call.attributes[identityKey],settings)
    }
}
