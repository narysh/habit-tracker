package dev.habittracker.probe

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class HttpTest {
    @Test fun `OAuth HTTP flow returns bound token and denies anonymous MCP`() = testApplication {
        val client = createClient { followRedirects = false }
        val config = testConfig()
        application { probeModule(config) }
        val unauthorized = client.post("/mcp")
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)
        assertTrue(unauthorized.headers[HttpHeaders.WWWAuthenticate]!!.contains("oauth-protected-resource"))
        val metadata = Json.parseToJsonElement(client.get("/.well-known/oauth-protected-resource").bodyAsText()).jsonObject
        assertEquals(config.resource, metadata["resource"]!!.jsonPrimitive.content)
        val page = client.get("/oauth/authorize") { url { authParams(config).forEach { (k,v) -> parameters.append(k,v) } } }
        assertEquals(HttpStatusCode.OK, page.status)
        val nonce = Regex("name=\"nonce\" value=\"([^\"]+)\"").find(page.bodyAsText())!!.groupValues[1]
        val consent = client.post("/oauth/consent") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("nonce" to nonce, "secret" to config.pairingSecret, "decision" to "allow").formUrlEncode())
        }
        assertEquals(HttpStatusCode.Found, consent.status)
        val code = query(consent.headers[HttpHeaders.Location]!!).getValue("code")
        val response = client.post("/oauth/token") {
            contentType(ContentType.Application.FormUrlEncoded); setBody(tokenParams(config, code).toList().formUrlEncode())
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val token = Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("access_token").jsonPrimitive.content
        val tools = rpc(client, token, "tools/list")
        assertNotNull(tools["result"])
    }

    @Test fun `real SDK serves tools creates reads validates and deletes`() = testApplication {
        val config = testConfig(); val oauth = OAuth(config); val store = TaskStore(config.dataFile)
        application { probeModule(config, oauth, store) }
        val token = issueToken(config, oauth)
        val init = rpc(client, token, "initialize", buildJsonObject {
            put("protocolVersion", "2025-11-25"); putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "test"); put("version", "1") }
        })
        assertNotNull(init["result"])
        val tools = rpc(client, token, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
        val deletion = tools.single { it.jsonObject["name"]!!.jsonPrimitive.content == "delete_task" }.jsonObject
        assertTrue(deletion["annotations"]!!.jsonObject["destructiveHint"]!!.jsonPrimitive.boolean)
        val args = buildJsonObject { put("title", "Тест ChatGPT"); put("operation_id", UUID.randomUUID().toString()) }
        repeat(2) { assertFalse(call(client, token, "create_task", args)["isError"]!!.jsonPrimitive.boolean) }
        assertEquals(1, store.list().size)
        val task = store.list().single()
        val invalid = call(client, token, "delete_task", buildJsonObject { put("id", task.id); put("expected_version", 9) })
        assertTrue(invalid["isError"]!!.jsonPrimitive.boolean); assertEquals(1, store.list().size)
        val result = call(client, token, "delete_task", buildJsonObject { put("id", task.id); put("expected_version", 1) })
        assertFalse(result["isError"]!!.jsonPrimitive.boolean); assertTrue(store.list().isEmpty())
        oauth.revoke(token)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/mcp") { bearerAuth(token) }.status)
    }

    @Test fun `read only scope blocks mutations and hostile origins are rejected`() = testApplication {
        val config = testConfig(); val oauth = OAuth(config); val store = TaskStore(config.dataFile)
        application { probeModule(config, oauth, store) }
        val token = issueToken(config, oauth, "tracker:read")
        val result = call(client, token, "create_task", buildJsonObject { put("title", "Blocked"); put("operation_id", UUID.randomUUID().toString()) })
        assertTrue(result["isError"]!!.jsonPrimitive.boolean); assertTrue(store.list().isEmpty())
        assertEquals(HttpStatusCode.Forbidden, client.post("/mcp") { bearerAuth(token); header(HttpHeaders.Origin, "https://attacker.example") }.status)
    }
}

private fun issueToken(config: Config, oauth: OAuth, scope: String = "tracker:read tracker:write"): String {
    val nonce = oauth.begin(authParams(config) + ("scope" to scope))
    val code = query(oauth.consent(nonce, config.pairingSecret, true)).getValue("code")
    return oauth.exchange(tokenParams(config, code)).first
}
private suspend fun rpc(client: io.ktor.client.HttpClient, token: String, method: String, params: JsonObject = buildJsonObject {}): JsonObject {
    val response = client.post("/mcp") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        header(HttpHeaders.Host, "tracker.example")
        header(HttpHeaders.Accept, "application/json, text/event-stream")
        header("MCP-Protocol-Version", "2025-11-25")
        setBody(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method); put("params", params) }.toString())
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject
}
private suspend fun call(client: io.ktor.client.HttpClient, token: String, tool: String, args: JsonObject): JsonObject =
    rpc(client, token, "tools/call", buildJsonObject { put("name", tool); put("arguments", args) })["result"]!!.jsonObject
