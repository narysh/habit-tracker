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
}
