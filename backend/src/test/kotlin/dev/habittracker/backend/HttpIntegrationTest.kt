package dev.habittracker.backend

import dev.habittracker.shared.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpIntegrationTest {
    private lateinit var pg: EmbeddedPostgres
    private lateinit var store: Store
    private lateinit var settings: Settings
    @BeforeAll fun start() {
        pg=EmbeddedPostgres.builder().setPort(0).start()
        store=Store(pg.getJdbcUrl("postgres","postgres"),"postgres","")
        settings=Settings(dbPassword="test-only",clientSecret="s".repeat(40),files=Files.createTempDirectory("http-files"))
    }
    @AfterAll fun stop() { if(::store.isInitialized) store.close(); if(::pg.isInitialized) pg.close() }
    private fun user()=store.register(Credentials("${UUID.randomUUID()}@http.test","test-password-123"))
    @Test fun `REST denies anonymous users and isolates attachments`()=testApplication {
        application { backendModule(settings,store) }
        assertEquals(HttpStatusCode.Unauthorized,client.get("/api/v1/items").status)
        val alice=user(); val bob=user(); val id=UUID.randomUUID().toString(); val fileId=UUID.randomUUID().toString()
        val note=Mutation(UUID.randomUUID().toString(),id,0,NoteContent("Файл",attachmentIds=listOf(fileId)))
        assertEquals(HttpStatusCode.OK,client.post("/api/v1/mutations") { bearerAuth(alice.token);contentType(ContentType.Application.Json);setBody(WireJson.encodeToString(note)) }.status)
        val upload=client.put("/api/v1/attachments/$fileId") {
            bearerAuth(alice.token); url { parameters.append("noteId",id);parameters.append("name","test.txt");parameters.append("mediaType","text/plain") }
            contentType(ContentType.Text.Plain);setBody("attachment-data".toByteArray())
        }
        assertEquals(HttpStatusCode.OK,upload.status,upload.bodyAsText())
        assertEquals("attachment-data",client.get("/api/v1/attachments/$fileId") { bearerAuth(alice.token) }.bodyAsText())
        val resource=client.post("/mcp") {
            bearerAuth(alice.token);contentType(ContentType.Application.Json);header(HttpHeaders.Host,"127.0.0.1");header(HttpHeaders.Accept,"application/json, text/event-stream")
            setBody(buildJsonObject {put("jsonrpc","2.0");put("id",1);put("method","tools/call");putJsonObject("params") {put("name","get_attachment");putJsonObject("arguments") {put("id",fileId)}}}.toString())
        }
        assertEquals(HttpStatusCode.OK,resource.status)
        val encoded=WireJson.parseToJsonElement(resource.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("content").jsonArray.last().jsonObject.getValue("resource").jsonObject.getValue("blob").jsonPrimitive.content
        assertEquals("attachment-data",java.util.Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8))
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/attachments/$fileId") { bearerAuth(bob.token) }.status)
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/items/$id") { bearerAuth(bob.token) }.status)
        val deleted=note.copy(operationId=UUID.randomUUID().toString(),baseVersion=1,deleted=true)
        client.post("/api/v1/mutations") { bearerAuth(alice.token);contentType(ContentType.Application.Json);setBody(WireJson.encodeToString(deleted)) }
        assertEquals(HttpStatusCode.NotFound,client.get("/api/v1/attachments/$fileId") { bearerAuth(alice.token) }.status)
    }
    @Test fun `OAuth binds account scopes and PKCE then SDK creates task in same account`()=testApplication {
        val http=createClient { followRedirects=false }
        application { backendModule(settings,store) }
        val email="${UUID.randomUUID()}@oauth.test"; val password="test-password-123"
        val registration=http.post("/api/v1/auth/register") { contentType(ContentType.Application.Json);setBody(WireJson.encodeToString(Credentials(email,password))) }
        assertEquals(HttpStatusCode.Created,registration.status)
        val account=WireJson.decodeFromString<Session>(registration.bodyAsText())
        val verifier="v".repeat(43)
        val page=http.get("/oauth/authorize") { url { mapOf("client_id" to settings.clientId,"redirect_uri" to settings.redirects.single(),"response_type" to "code","state" to "test","code_challenge" to challenge(verifier),"code_challenge_method" to "S256","resource" to settings.resource,"scope" to "tracker:read tracker:write").forEach { (k,v)->parameters.append(k,v) } } }
        assertEquals(HttpStatusCode.OK,page.status)
        val nonce=Regex("name=\"nonce\" value=\"([^\"]+)\"").find(page.bodyAsText())!!.groupValues[1]
        val consent=http.post("/oauth/consent") { contentType(ContentType.Application.FormUrlEncoded);setBody(listOf("nonce" to nonce,"email" to email,"password" to password,"decision" to "allow").formUrlEncode()) }
        assertEquals(HttpStatusCode.Found,consent.status)
        val location=java.net.URI(consent.headers[HttpHeaders.Location]!!)
        val code=location.rawQuery.split('&').single { it.startsWith("code=") }.substringAfter('=')
        val response=http.post("/oauth/token") { contentType(ContentType.Application.FormUrlEncoded);setBody(listOf("client_id" to settings.clientId,"client_secret" to settings.clientSecret,"grant_type" to "authorization_code","redirect_uri" to settings.redirects.single(),"resource" to settings.resource,"code" to code,"code_verifier" to verifier).formUrlEncode()) }
        assertEquals(HttpStatusCode.OK,response.status,response.bodyAsText())
        val token=WireJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("access_token").jsonPrimitive.content
        val rpc=http.post("/mcp") {
            bearerAuth(token);contentType(ContentType.Application.Json);header(HttpHeaders.Host,"127.0.0.1");header(HttpHeaders.Accept,"application/json, text/event-stream");header("MCP-Protocol-Version","2025-11-25")
            setBody(buildJsonObject { put("jsonrpc","2.0");put("id",1);put("method","tools/call");putJsonObject("params") {put("name","create_task");putJsonObject("arguments") {put("title","Создано AI");put("operation_id",UUID.randomUUID().toString())}} }.toString())
        }
        assertEquals(HttpStatusCode.OK,rpc.status,rpc.bodyAsText())
        val result=WireJson.parseToJsonElement(rpc.bodyAsText()).jsonObject.getValue("result").jsonObject
        assertEquals(false,result.getValue("isError").jsonPrimitive.boolean)
        assertEquals("Создано AI",store.list(account.userId).single().content.title)
        for((tool,args) in listOf(
            "create_note" to buildJsonObject {put("operation_id",UUID.randomUUID().toString());put("title","Заметка AI");put("text","Текст заметки")},
            "create_habit" to buildJsonObject {put("operation_id",UUID.randomUUID().toString());put("title","Чтение AI");put("mode","WEEKLY_TARGET");put("target_per_week",3);put("starts_on","2026-11-12");put("zone_id","Europe/Moscow")}
        )) {
            val extra=http.post("/mcp") {
                bearerAuth(token);contentType(ContentType.Application.Json);header(HttpHeaders.Host,"127.0.0.1");header(HttpHeaders.Accept,"application/json, text/event-stream");header("MCP-Protocol-Version","2025-11-25")
                setBody(buildJsonObject {put("jsonrpc","2.0");put("id",2);put("method","tools/call");putJsonObject("params") {put("name",tool);put("arguments",args)}}.toString())
            }
            assertEquals(HttpStatusCode.OK,extra.status)
            assertEquals(false,WireJson.parseToJsonElement(extra.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        }
        assertEquals(setOf("task","note","habit"),store.list(account.userId).map {it.content.typeName()}.toSet())
        assertTrue(store.list(user().userId).isEmpty())
    }
    @Test fun `read only OAuth session cannot mutate via REST or MCP`()=testApplication {
        application { backendModule(settings,store) }
        val account=user(); val token=store.newSession(Identity(account.userId,account.email,setOf("tracker:read")),1).token
        val response=client.post("/api/v1/mutations") { bearerAuth(token);contentType(ContentType.Application.Json);setBody(WireJson.encodeToString(Mutation(UUID.randomUUID().toString(),UUID.randomUUID().toString(),0,TaskContent("Blocked")))) }
        assertEquals(HttpStatusCode.Forbidden,response.status)
        val rpc=client.post("/mcp") {
            bearerAuth(token);contentType(ContentType.Application.Json);header(HttpHeaders.Host,"127.0.0.1");header(HttpHeaders.Accept,"application/json, text/event-stream")
            setBody(buildJsonObject {put("jsonrpc","2.0");put("id",1);put("method","tools/call");putJsonObject("params") {put("name","create_task");putJsonObject("arguments") {put("title","Blocked");put("operation_id",UUID.randomUUID().toString())}}}.toString())
        }
        assertEquals(HttpStatusCode.OK,rpc.status)
        assertTrue(WireJson.parseToJsonElement(rpc.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        assertTrue(store.list(account.userId).isEmpty())
    }
    @Test fun `public plugin accepts only its own OAuth grant and syncs a task to the same Android account`() = testApplication {
        val plugin = settings.copy(
            publicUrl = "https://tracker.example.test",
            clientId = "habit-tracker-chatgpt",
            redirects = setOf("https://chatgpt.com/connector_platform_oauth_redirect"),
            publicPlugin = true,
            openAiDomainChallenge = "test-challenge"
        )
        application { backendModule(plugin, store) }
        val socialAccount = store.socialIdentity("google", UUID.randomUUID().toString(), "${UUID.randomUUID()}@plugin.test", null)
        val owner = store.newSession(socialAccount)
        val other = user()
        val identity = checkNotNull(store.authenticate(owner.token))
        val wrongAudience = store.issueOAuth(identity, plugin.clientId, "https://elsewhere.example.test/mcp")
        val wrongClient = store.issueOAuth(identity, "desktop", plugin.resource)
        val noRedirects = createClient { followRedirects = false }
        val verifier = "v".repeat(43)
        val redirect = plugin.redirects.single()
        val authorization = mapOf(
            "client_id" to plugin.clientId, "redirect_uri" to redirect, "response_type" to "code",
            "state" to "plugin-test", "code_challenge" to challenge(verifier), "code_challenge_method" to "S256",
            "resource" to plugin.resource, "scope" to "tracker:read tracker:write"
        )
        val page = noRedirects.get("/oauth/authorize") { url { authorization.forEach { (key, value) -> parameters.append(key, value) } } }
        assertEquals(HttpStatusCode.OK, page.status)
        val nonce = checkNotNull(Regex("name=\"nonce\" value=\"([^\"]+)\"").find(page.bodyAsText())).groupValues[1]
        val appCode = checkNotNull(Regex("id=\"app-code\">([^<]+)").find(page.bodyAsText())).groupValues[1]
        suspend fun appConsent() = noRedirects.post("/oauth/app-consent") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("nonce" to nonce).formUrlEncode())
        }
        suspend fun appLink(action: String, token: String?) = noRedirects.post("/api/v1/account/chatgpt/$action") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(ChatGptLinkRequest(appCode)))
        }
        assertEquals(HttpStatusCode.Conflict, appConsent().status)
        assertEquals(HttpStatusCode.Unauthorized, appLink("approve", null).status)
        val preview = appLink("preview", owner.token)
        assertEquals(HttpStatusCode.OK, preview.status, preview.bodyAsText())
        assertEquals(setOf("tracker:read", "tracker:write"), WireJson.decodeFromString<ChatGptLinkPreview>(preview.bodyAsText()).scopes)
        assertEquals(HttpStatusCode.NoContent, appLink("approve", owner.token).status)
        assertEquals(HttpStatusCode.NotFound, appLink("approve", other.token).status)
        val consent = appConsent()
        assertEquals(HttpStatusCode.Found, consent.status)
        assertEquals(HttpStatusCode.Gone, appConsent().status)
        val callback = Url(checkNotNull(consent.headers[HttpHeaders.Location]))
        assertEquals("chatgpt.com", callback.host)
        assertEquals(plugin.publicUrl, callback.parameters["iss"])
        assertEquals("plugin-test", callback.parameters["state"])
        val exchange = noRedirects.post("/oauth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("client_id" to plugin.clientId, "client_secret" to plugin.clientSecret,
                "grant_type" to "authorization_code", "redirect_uri" to redirect, "resource" to plugin.resource,
                "code" to checkNotNull(callback.parameters["code"]), "code_verifier" to verifier).formUrlEncode())
        }
        assertEquals(HttpStatusCode.OK, exchange.status, exchange.bodyAsText())
        val allowed = WireJson.decodeFromString<OAuthTokens>(exchange.bodyAsText())
        suspend fun rpc(token: String, body: String) = client.post("/mcp") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Host, "tracker.example.test")
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            setBody(body)
        }
        val listRequest = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        for (token in listOf(owner.token, wrongAudience.accessToken, wrongClient.accessToken)) {
            assertEquals(HttpStatusCode.Unauthorized, rpc(token, listRequest).status)
        }
        assertEquals("test-challenge", client.get("/.well-known/openai-apps-challenge").bodyAsText())
        val catalog = rpc(allowed.accessToken, listRequest)
        assertEquals(HttpStatusCode.OK, catalog.status, catalog.bodyAsText())
        val tools = WireJson.parseToJsonElement(catalog.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
        assertEquals(setOf("create_task", "get_tasks", "complete_task"), tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/items") { bearerAuth(allowed.accessToken) }.status)
        val create = rpc(allowed.accessToken, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"create_task","arguments":{"operation_id":"${UUID.randomUUID()}","title":"С телефона"}}}""")
        assertEquals(HttpStatusCode.OK, create.status, create.bodyAsText())
        assertFalse(WireJson.parseToJsonElement(create.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        suspend fun sync(token: String) = client.post("/api/v1/sync") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"operations":[],"cursor":0}""")
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            WireJson.decodeFromString<SyncResponse>(response.bodyAsText())
        }
        assertEquals("С телефона", sync(owner.token).changes.single().content.title)
        assertTrue(sync(other.token).changes.isEmpty())

        suspend fun checkConnection(token: String): ChatGptConnection {
            val response = noRedirects.get("/api/v1/account/chatgpt") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, response.status)
            return WireJson.decodeFromString(response.bodyAsText())
        }
        suspend fun refresh(token: String) = noRedirects.post("/oauth/token") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("client_id" to plugin.clientId, "client_secret" to plugin.clientSecret,
                "grant_type" to "refresh_token", "resource" to plugin.resource, "refresh_token" to token).formUrlEncode())
        }
        assertEquals(ChatGptConnection(true, true), checkConnection(owner.token))
        val otherGrant = store.issueOAuth(checkNotNull(store.authenticate(other.token)), plugin.clientId, plugin.resource)
        val rotation = refresh(allowed.refreshToken)
        assertEquals(HttpStatusCode.OK, rotation.status, rotation.bodyAsText())
        val rotated = WireJson.decodeFromString<OAuthTokens>(rotation.bodyAsText())
        val denied = noRedirects.delete("/api/v1/account/chatgpt") { bearerAuth(rotated.accessToken) }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)
        val disconnected = noRedirects.delete("/api/v1/account/chatgpt") { bearerAuth(owner.token) }
        assertEquals(HttpStatusCode.NoContent, disconnected.status)
        assertEquals(ChatGptConnection(true, false), checkConnection(owner.token))
        assertEquals(HttpStatusCode.Unauthorized, rpc(rotated.accessToken, listRequest).status)
        assertEquals(HttpStatusCode.Unauthorized, refresh(rotated.refreshToken).status)
        assertEquals(HttpStatusCode.OK, rpc(otherGrant.accessToken, listRequest).status)
        assertNotNull(store.authenticate(wrongClient.accessToken))
        assertEquals("С телефона", sync(owner.token).changes.single().content.title)
    }
}
