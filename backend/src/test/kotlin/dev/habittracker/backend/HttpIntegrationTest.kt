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
    private suspend fun io.ktor.client.HttpClient.requestAccountDeletion(token: String?, confirmation: String = "DELETE") =
        post("/api/v1/account/delete") {
            token?.let { bearerAuth(it) }
            contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(AccountDeletionRequest(confirmation)))
        }

    @Test fun `account deletion requires recent app sign in and confirmation and preserves other accounts`() = testApplication {
        application { backendModule(settings, store) }
        val owner = user()
        val credentials = Credentials(owner.email, "test-password-123")
        val other = user()
        val identity = checkNotNull(store.authenticate(owner.token))
        val legacy = store.login(credentials)
        pg.postgresDatabase.connection.use { c ->
            c.prepareStatement("UPDATE sessions SET issued_at=CASE WHEN token_hash=? THEN NULL ELSE now()-INTERVAL '11 minutes' END WHERE token_hash IN (?,?)").use {
                it.setString(1, digest(legacy.token))
                it.setString(2, digest(legacy.token))
                it.setString(3, digest(owner.token))
                assertEquals(2, it.executeUpdate())
            }
        }
        val current = store.login(credentials)
        val oauth = store.issueOAuth(identity, settings.clientId, settings.resource)
        val noteId = UUID.randomUUID().toString()
        val note = Mutation(UUID.randomUUID().toString(), noteId, 0, NoteContent("Только владельцу"))
        store.mutate(owner.userId, note)
        store.mutate(other.userId, note.copy(content = NoteContent("Сохранить")))

        assertEquals(HttpStatusCode.Unauthorized, client.requestAccountDeletion(null).status)
        assertEquals(HttpStatusCode.Unauthorized, client.requestAccountDeletion(oauth.accessToken).status)
        assertEquals(HttpStatusCode.Forbidden, client.requestAccountDeletion(owner.token).status)
        assertEquals(HttpStatusCode.Forbidden, client.requestAccountDeletion(legacy.token).status)
        assertEquals(HttpStatusCode.BadRequest, client.requestAccountDeletion(current.token, "CANCEL").status)
        assertEquals("Только владельцу", store.get(owner.userId, noteId)?.content?.title)

        val deleted = client.requestAccountDeletion(current.token)
        assertEquals(HttpStatusCode.NoContent, deleted.status, deleted.bodyAsText())
        for (token in listOf(owner.token, legacy.token, current.token, oauth.accessToken)) {
            assertNull(store.authenticate(token))
        }
        assertFailsWith<ApiFailure> { store.refreshOAuth(oauth.refreshToken, settings.clientId, settings.resource) }
        assertFailsWith<ApiFailure> { store.login(credentials) }
        assertNull(store.get(owner.userId, noteId))
        assertEquals("Сохранить", store.get(other.userId, noteId)?.content?.title)
        assertNotNull(store.authenticate(other.token))
        val recreated = store.register(credentials)
        assertNotEquals(owner.userId, recreated.userId)
        assertTrue(store.list(recreated.userId).isEmpty())
    }

    @Test fun `social account deletion revokes approved app pairing and rejects pending social linking`() = testApplication {
        val plugin = settings.copy(publicPlugin = true, publicUrl = "https://tracker.example.test",
            redirects = setOf("https://chatgpt.com/connector_platform_oauth_redirect"))
        val config = ProviderConfig("google", "Google", "fake-id", "fake-secret", "https://tracker.example.test/provider-callback")
        val subject = UUID.randomUUID().toString()
        val social = SocialLogin(store, mapOf("google" to config),plugin.socialStateEncryptionKey) { _, _, _, _, _ -> ProviderPerson(subject, null) }
        application { backendModule(plugin, store, social) }
        val verifier = secret()
        val signIn = social.start(SocialStart("google", challenge(verifier)), null)
        social.callback("google", signIn.id, "code", null, null)
        val owner = checkNotNull(social.poll(SocialPoll(signIn.id, verifier)).session)
        val linking = social.start(SocialStart("google", challenge(verifier), true), store.authenticate(owner.token), owner.token)
        val page = client.get("/oauth/authorize") {
            url {
                parameters.appendAll(Parameters.build {
                    append("client_id", plugin.clientId)
                    append("redirect_uri", plugin.redirects.single())
                    append("response_type", "code")
                    append("state", "deletion-test")
                    append("code_challenge", challenge(verifier))
                    append("code_challenge_method", "S256")
                    append("resource", plugin.resource)
                })
            }
        }
        assertEquals(HttpStatusCode.OK, page.status)
        val consent = page.bodyAsText()
        val nonce = checkNotNull(Regex("name=\"nonce\" value=\"([^\"]+)\"").find(consent)).groupValues[1]
        val appCode = checkNotNull(Regex("id=\"app-code\">([^<]+)").find(consent)).groupValues[1]
        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/account/chatgpt/approve") {
            bearerAuth(owner.token)
            contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(ChatGptLinkRequest(appCode)))
        }.status)

        assertEquals(HttpStatusCode.NoContent, client.requestAccountDeletion(owner.token).status)
        assertEquals(HttpStatusCode.Gone, client.post("/oauth/app-consent") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(listOf("nonce" to nonce).formUrlEncode())
        }.status)
        assertEquals(400, assertFailsWith<ApiFailure> { social.callback("google", linking.id, "code", null, null) }.status)
        assertTrue(store.linkedProviders(owner.userId).isEmpty())
        assertNotEquals(owner.userId, store.socialIdentity("google", subject, null, null).userId)
    }

    @Test fun `readiness detects unavailable database without leaking error details`() {
        val independentStore=Store(pg.getJdbcUrl("postgres","postgres"),"postgres","")
        independentStore.use {
            testApplication {
                application { backendModule(settings,independentStore) }
                assertEquals(HttpStatusCode.OK,client.get("/readyz").status)
                independentStore.close()
                val unavailable=client.get("/readyz")
                assertEquals(HttpStatusCode.ServiceUnavailable,unavailable.status)
                assertEquals(buildJsonObject { put("status","unavailable") },WireJson.parseToJsonElement(unavailable.bodyAsText()))
                assertEquals(HttpStatusCode.OK,client.get("/health").status)
            }
        }
    }
    @Test fun `client ip header is trusted only when explicitly configured and OAuth authorize is throttled`() = testApplication {
        application { backendModule(settings,store) }
        repeat(21) { index ->
            val response=client.post("/api/v1/auth/login") {
                header("CF-Connecting-IP", "192.0.2.${index+1}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(if (index==20) HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest,response.status)
        }
    }

    @Test fun `default login throttle is shared by independent backend instances`() {
        val proxied = settings.copy(trustedClientIpHeader="CF-Connecting-IP")
        val address = "203.0.113.239"
        testApplication {
            application { backendModule(proxied,store) }
            repeat(20) {
                val response=client.post("/api/v1/auth/login") {
                    header("CF-Connecting-IP",address)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.BadRequest,response.status)
            }
            val limited=client.post("/api/v1/auth/login") {
                header("CF-Connecting-IP",address)
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.TooManyRequests,limited.status)
        }

        Store(pg.getJdbcUrl("postgres","postgres"),"postgres","").use { restarted ->
            testApplication {
                application { backendModule(proxied,restarted) }
                val limited=client.post("/api/v1/auth/login") {
                    header("CF-Connecting-IP",address)
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }
                assertEquals(HttpStatusCode.TooManyRequests,limited.status)
            }
        }
    }

    @Test fun `configured proxy header is used and OAuth authorize is throttled`() = testApplication {
        application { backendModule(settings.copy(trustedClientIpHeader="CF-Connecting-IP"),store) }
        repeat(20) { index ->
            val response=client.post("/api/v1/auth/login") {
                header("CF-Connecting-IP", "192.0.2.${index+1}")
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
            assertEquals(HttpStatusCode.BadRequest,response.status)
        }
        repeat(21) { index ->
            val response=client.get("/oauth/authorize") {
                header("CF-Connecting-IP", "198.51.100.42")
                url { parameters.append("invalid", index.toString()) }
            }
            assertEquals(if (index==20) HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest,response.status)
        }
    }

    @Test fun `password change revokes other app and ChatGPT sessions`() = testApplication {
        application { backendModule(settings,store) }
        val account=user()
        val otherSession=store.login(Credentials(account.email,"test-password-123"))
        val oauth=store.issueOAuth(checkNotNull(store.authenticate(account.token)),settings.clientId,settings.resource)
        val oauthAttempt=client.post("/api/v1/account/password") {
            bearerAuth(oauth.accessToken);contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(PasswordChangeRequest("test-password-123","new-password-456")))
        }
        assertEquals(HttpStatusCode.Unauthorized,oauthAttempt.status)
        val response=client.post("/api/v1/account/password") {
            bearerAuth(account.token);contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(PasswordChangeRequest("test-password-123","new-password-456")))
        }
        assertEquals(HttpStatusCode.NoContent,response.status,response.bodyAsText())
        assertNotNull(store.authenticate(account.token))
        assertNull(store.authenticate(otherSession.token))
        assertNull(store.authenticate(oauth.accessToken))
        assertFailsWith<ApiFailure> { store.refreshOAuth(oauth.refreshToken,settings.clientId,settings.resource) }
        assertNotNull(store.login(Credentials(account.email,"new-password-456")))
    }

    @Test fun `social account can add a password from its authenticated app session`() = testApplication {
        application { backendModule(settings,store) }
        val identity=store.socialIdentity("google","subject-${UUID.randomUUID()}","social-${UUID.randomUUID()}@http.test",null)
        val account=store.newSession(identity)
        pg.postgresDatabase.connection.use { c ->
            c.prepareStatement("UPDATE sessions SET issued_at=now()-INTERVAL '11 minutes' WHERE token_hash=?").use {
                it.setString(1,digest(account.token));assertEquals(1,it.executeUpdate())
            }
        }
        val stale=client.post("/api/v1/account/password") {
            bearerAuth(account.token);contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(PasswordChangeRequest("","social-password-456")))
        }
        assertEquals(HttpStatusCode.Unauthorized,stale.status)
        assertFailsWith<ApiFailure> { store.login(Credentials(account.email,"social-password-456")) }
        val fresh=store.newSession(identity)
        val response=client.post("/api/v1/account/password") {
            bearerAuth(fresh.token);contentType(ContentType.Application.Json)
            setBody(WireJson.encodeToString(PasswordChangeRequest("","social-password-456")))
        }
        assertEquals(HttpStatusCode.NoContent,response.status,response.bodyAsText())
        assertNotNull(store.login(Credentials(account.email,"social-password-456")))
    }
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

    @Test fun `OAuth app approval survives coordinator recreation and authorization code is single use`() {
        val plugin = settings.copy(publicPlugin = true, publicUrl = "https://tracker.example.test",
            redirects = setOf("https://chatgpt.com/connector_platform_oauth_redirect"))
        val owner = user()
        val verifier = secret()
        val authorizeParams = mapOf(
            "client_id" to plugin.clientId,
            "redirect_uri" to plugin.redirects.single(),
            "response_type" to "code",
            "state" to "restart-safe-state",
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "resource" to plugin.resource,
            "scope" to "tracker:read tracker:write",
        )
        val firstInstance = AccountOAuth(plugin, store)
        val begun = firstInstance.begin(authorizeParams)
        val secondStore = Store(pg.getJdbcUrl("postgres", "postgres"), "postgres", "")
        val thirdStore = Store(pg.getJdbcUrl("postgres", "postgres"), "postgres", "")
        try {
            val restartedInstance = AccountOAuth(plugin, secondStore)
            assertEquals(setOf("tracker:read", "tracker:write"), restartedInstance.previewAppLink(begun.appCode!!).scopes)
            restartedInstance.approveAppLink(begun.appCode, owner.token)
            val callback = restartedInstance.finishAppLink(begun.nonce)
            assertNotNull(callback)
            val code = java.net.URLDecoder.decode(java.net.URI(callback).rawQuery.split('&').single { it.startsWith("code=") }.substringAfter('='), Charsets.UTF_8)
            val tokenParams = mapOf(
                "client_id" to plugin.clientId,
                "client_secret" to plugin.clientSecret,
                "resource" to plugin.resource,
                "grant_type" to "authorization_code",
                "redirect_uri" to plugin.redirects.single(),
                "code" to code,
                "code_verifier" to verifier,
            )
            val tokens = restartedInstance.exchange(tokenParams)
            assertEquals(owner.userId, secondStore.authenticatePlugin(tokens.accessToken, plugin.clientId, plugin.resource)?.userId)
            assertFailsWith<ApiFailure> { AccountOAuth(plugin, thirdStore).exchange(tokenParams) }
        } finally {
            secondStore.close()
            thirdStore.close()
        }
    }

    @Test fun `OAuth disconnect and account deletion revoke pending and unredeemed grants`() {
        val plugin = settings.copy(publicPlugin = true, publicUrl = "https://tracker.example.test",
            redirects = setOf("https://chatgpt.com/connector_platform_oauth_redirect"))
        val owner = user()
        val oauth = AccountOAuth(plugin, store)
        fun params(state: String, verifier: String) = mapOf(
            "client_id" to plugin.clientId,
            "redirect_uri" to plugin.redirects.single(),
            "response_type" to "code",
            "state" to state,
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "resource" to plugin.resource,
            "scope" to "tracker:read tracker:write",
        )
        fun codeFrom(url: String) = java.net.URLDecoder.decode(
            java.net.URI(url).rawQuery.split('&').single { it.startsWith("code=") }.substringAfter('='), Charsets.UTF_8
        )

        val verifier = secret()
        val browser = oauth.begin(params("disconnect-code", verifier))
        val identity = checkNotNull(store.authenticate(owner.token))
        val redirect = oauth.finish(browser.nonce, identity, true)
        val code = codeFrom(redirect)
        oauth.disconnect(owner.userId)
        assertFailsWith<ApiFailure> {
            oauth.exchange(mapOf("client_id" to plugin.clientId, "client_secret" to plugin.clientSecret,
                "resource" to plugin.resource, "grant_type" to "authorization_code", "redirect_uri" to plugin.redirects.single(),
                "code" to code, "code_verifier" to verifier))
        }

        val deleteVerifier = secret()
        val deleteBrowser = oauth.begin(params("delete-code", deleteVerifier))
        val deleteRedirect = oauth.finish(deleteBrowser.nonce, identity, true)
        val deleteCode = codeFrom(deleteRedirect)

        val appVerifier = secret()
        val pending = oauth.begin(params("delete-pending", appVerifier))
        oauth.approveAppLink(pending.appCode!!, owner.token)
        oauth.deleteAccount(owner.userId, owner.token)
        assertEquals(410, assertFailsWith<ApiFailure> { oauth.finishAppLink(pending.nonce) }.status)
        assertFailsWith<ApiFailure> {
            oauth.exchange(mapOf("client_id" to plugin.clientId, "client_secret" to plugin.clientSecret,
                "resource" to plugin.resource, "grant_type" to "authorization_code", "redirect_uri" to plugin.redirects.single(),
                "code" to deleteCode, "code_verifier" to deleteVerifier))
        }
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
    @Test fun `public MCP exposes scoped tools for CRUD habits attachments and same Android account`() = testApplication {
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
        suspend fun callTool(token: String, requestId: Int, name: String, arguments: JsonObject): JsonObject {
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", requestId)
                put("method", "tools/call")
                putJsonObject("params") {
                    put("name", name)
                    put("arguments", arguments)
                }
            }.toString()
            val response = rpc(token, body)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            return WireJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("result").jsonObject
        }
        val listRequest = """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""
        for (token in listOf(owner.token, wrongAudience.accessToken, wrongClient.accessToken)) {
            assertEquals(HttpStatusCode.Unauthorized, rpc(token, listRequest).status)
        }
        assertEquals("test-challenge", client.get("/.well-known/openai-apps-challenge").bodyAsText())
        val catalog = rpc(allowed.accessToken, listRequest)
        assertEquals(HttpStatusCode.OK, catalog.status, catalog.bodyAsText())
        val tools = WireJson.parseToJsonElement(catalog.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
        val publicTools = setOf(
            "get_tasks", "get_notes", "get_habits", "get_habit_history", "get_projects", "search_records", "get_week_review",
            "create_task", "create_habit", "create_note", "create_project", "propose_week_plan",
            "update_item", "complete_task", "mark_habit", "delete_item", "get_attachment",
        )
        assertEquals(publicTools, tools.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        val malformedAttachmentId = callTool(allowed.accessToken, 13, "get_attachment", buildJsonObject { put("id", "not-a-uuid") })
        assertTrue(malformedAttachmentId.getValue("isError").jsonPrimitive.boolean)
        val malformedOperationId = callTool(allowed.accessToken, 14, "create_task", buildJsonObject {
            put("operation_id", "not-a-uuid")
            put("title", "Не должна появиться")
        })
        assertTrue(malformedOperationId.getValue("isError").jsonPrimitive.boolean)
        assertTrue(store.list(owner.userId).none { (it.content as? TaskContent)?.title == "Не должна появиться" })
        val malformedWeek = callTool(allowed.accessToken, 15, "get_week_review", buildJsonObject {
            put("week_start", "вчера")
            put("zone_id", "Europe/Moscow")
        })
        assertTrue(malformedWeek.getValue("isError").jsonPrimitive.boolean)

        val readOnly = store.issueOAuth(identity.copy(scopes = setOf("tracker:read")), plugin.clientId, plugin.resource)
        val readCatalog = rpc(readOnly.accessToken, listRequest)
        assertEquals(HttpStatusCode.OK, readCatalog.status, readCatalog.bodyAsText())
        val readTools = WireJson.parseToJsonElement(readCatalog.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("tools").jsonArray
        assertEquals(setOf("get_tasks", "get_notes", "get_habits", "get_habit_history", "get_projects", "search_records", "get_week_review", "get_attachment"),
            readTools.map { it.jsonObject.getValue("name").jsonPrimitive.content }.toSet())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/items") { bearerAuth(allowed.accessToken) }.status)
        val create = rpc(allowed.accessToken, """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"create_task","arguments":{"operation_id":"${UUID.randomUUID()}","title":"С телефона"}}}""")
        assertEquals(HttpStatusCode.OK, create.status, create.bodyAsText())
        assertFalse(WireJson.parseToJsonElement(create.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        val noteArguments = buildJsonObject {
            put("operation_id", UUID.randomUUID().toString())
            put("title", "Заметка с телефона")
            put("text", "Строка 1\nСтрока 2")
        }
        val noteRequest = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 3)
            put("method", "tools/call")
            putJsonObject("params") {
                put("name", "create_note")
                put("arguments", noteArguments)
            }
        }.toString()
        val rejectedNote = rpc(readOnly.accessToken, noteRequest)
        assertEquals(HttpStatusCode.OK, rejectedNote.status, rejectedNote.bodyAsText())
        val rejectedBody = WireJson.parseToJsonElement(rejectedNote.bodyAsText()).jsonObject
        assertTrue(rejectedBody.containsKey("error") || rejectedBody["result"]?.jsonObject?.get("isError")?.jsonPrimitive?.boolean == true)
        repeat(2) {
            val note = rpc(allowed.accessToken, noteRequest)
            assertEquals(HttpStatusCode.OK, note.status, note.bodyAsText())
            assertFalse(WireJson.parseToJsonElement(note.bodyAsText()).jsonObject.getValue("result").jsonObject.getValue("isError").jsonPrimitive.boolean)
        }
        val noteBeforeUpdate = store.list(owner.userId).single { (it.content as? NoteContent)?.title == "Заметка с телефона" }
        val updatedNoteContent = (noteBeforeUpdate.content as NoteContent).copy(title = "Заметка с телефона — обновлена")
        val update = callTool(allowed.accessToken, 12, "update_item", buildJsonObject {
            put("id", noteBeforeUpdate.id)
            put("expected_version", noteBeforeUpdate.version)
            put("operation_id", UUID.randomUUID().toString())
            put("content", WireJson.encodeToJsonElement<Content>(updatedNoteContent))
        })
        assertFalse(update.getValue("isError").jsonPrimitive.boolean)
        assertTrue(callTool(readOnly.accessToken, 13, "get_notes", buildJsonObject {}).getValue("isError").jsonPrimitive.boolean.not())
        assertEquals("Заметка с телефона — обновлена", (checkNotNull(store.get(owner.userId, noteBeforeUpdate.id)).content as NoteContent).title)
        val habitCreate = callTool(allowed.accessToken, 4, "create_habit", buildJsonObject {
            put("operation_id", UUID.randomUUID().toString())
            put("title", "Бег")
            put("mode", "WEEKLY_TARGET")
            put("target_per_week", 2)
            put("starts_on", "2026-11-01")
            put("zone_id", "Europe/Moscow")
        })
        assertFalse(habitCreate.getValue("isError").jsonPrimitive.boolean)
        val habit = store.list(owner.userId).single { it.content is HabitContent }
        val mark = callTool(allowed.accessToken, 5, "mark_habit", buildJsonObject {
            put("habit_id", habit.id)
            put("date", "2026-11-20")
            put("expected_version", 0)
            put("operation_id", UUID.randomUUID().toString())
            put("completed", true)
        })
        assertFalse(mark.getValue("isError").jsonPrimitive.boolean)
        assertFalse(callTool(readOnly.accessToken, 6, "get_habits", buildJsonObject {}).getValue("isError").jsonPrimitive.boolean)
        assertFalse(callTool(readOnly.accessToken, 7, "get_habit_history", buildJsonObject {}).getValue("isError").jsonPrimitive.boolean)

        val task = store.list(owner.userId).single { it.content is TaskContent }
        val complete = callTool(allowed.accessToken, 8, "complete_task", buildJsonObject {
            put("id", task.id)
            put("expected_version", task.version)
            put("operation_id", UUID.randomUUID().toString())
            put("completed", true)
        })
        assertFalse(complete.getValue("isError").jsonPrimitive.boolean)
        val completedTask = checkNotNull(store.get(owner.userId, task.id))
        assertTrue((completedTask.content as TaskContent).completed)
        val deleted = callTool(allowed.accessToken, 9, "delete_item", buildJsonObject {
            put("id", task.id)
            put("expected_version", completedTask.version)
            put("operation_id", UUID.randomUUID().toString())
        })
        assertFalse(deleted.getValue("isError").jsonPrimitive.boolean)
        assertTrue(checkNotNull(store.get(owner.userId, task.id)).deleted)

        val fileId = UUID.randomUUID().toString()
        val fileNoteId = UUID.randomUUID().toString()
        store.mutate(owner.userId, Mutation(UUID.randomUUID().toString(), fileNoteId, 0, NoteContent("Вложение", attachmentIds = listOf(fileId))))
        val upload = client.put("/api/v1/attachments/$fileId") {
            bearerAuth(owner.token)
            url { parameters.append("noteId", fileNoteId); parameters.append("name", "selected.txt"); parameters.append("mediaType", "text/plain") }
            contentType(ContentType.Text.Plain)
            setBody("only this selected file".toByteArray())
        }
        assertEquals(HttpStatusCode.OK, upload.status, upload.bodyAsText())
        val selectedFile = callTool(readOnly.accessToken, 10, "get_attachment", buildJsonObject { put("id", fileId) })
        assertFalse(selectedFile.getValue("isError").jsonPrimitive.boolean)
        val fileBlock = selectedFile.getValue("content").jsonArray.last().jsonObject.getValue("resource").jsonObject
        val encodedFile = fileBlock.getValue("blob").jsonPrimitive.content
        assertEquals("only this selected file", java.util.Base64.getDecoder().decode(encodedFile).toString(Charsets.UTF_8))
        val otherGrant = store.issueOAuth(checkNotNull(store.authenticate(other.token)), plugin.clientId, plugin.resource)
        val otherFile = callTool(otherGrant.accessToken, 11, "get_attachment", buildJsonObject { put("id", fileId) })
        assertTrue(otherFile.getValue("isError").jsonPrimitive.boolean)

        suspend fun sync(token: String) = client.post("/api/v1/sync") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"operations":[],"cursor":0}""")
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            WireJson.decodeFromString<SyncResponse>(response.bodyAsText())
        }
        val synced = sync(owner.token).changes
        assertTrue(synced.any { it.deleted && (it.content as? TaskContent)?.title == "С телефона" })
        assertTrue(synced.any { (it.content as? NoteContent)?.title == "Заметка с телефона — обновлена" && (it.content as NoteContent).text == "Строка 1\nСтрока 2" })
        assertTrue(synced.any { (it.content as? HabitContent)?.title == "Бег" })
        assertTrue(synced.any { it.content is HabitMarkContent })
        assertTrue(synced.any { (it.content as? NoteContent)?.title == "Вложение" })
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
        assertEquals(synced, sync(owner.token).changes)
    }
}
