package dev.habittracker.backend

import dev.habittracker.shared.*
import com.sun.net.httpserver.HttpServer
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SocialLoginTest {
    private lateinit var pg: EmbeddedPostgres
    private lateinit var store: Store
    @BeforeAll fun start(){pg=EmbeddedPostgres.builder().setPort(0).start();store=Store(pg.getJdbcUrl("postgres","postgres"),"postgres","")}
    @AfterAll fun stop(){store.close();pg.close()}
    private fun config(provider: String)=ProviderConfig(provider,provider,"test-client","test-secret","https://app.test/auth/social/$provider/callback")
    @Test fun `all provider adapters bind state and both PKCE keys and consume results once`() {
        for(provider in listOf("google","vk","yandex")) {
            val subject=UUID.randomUUID().toString();val verifier=secret()
            val social=SocialLogin(store,mapOf(provider to config(provider))) { cfg,code,serverVerifier,state,device ->
                assertEquals(provider,cfg.id);assertEquals("provider-code",code);assertTrue(serverVerifier.length>=43);assertTrue(state.isNotBlank());assertEquals("device",device)
                ProviderPerson(subject,null)
            }
            val attempt=social.start(SocialStart(provider,challenge(verifier)),null)
            assertTrue(attempt.authorizationUrl.contains("code_challenge="));assertTrue(attempt.authorizationUrl.contains("state=${attempt.id}"))
            if(provider=="vk") assertEquals("id.vk.ru",java.net.URI(attempt.authorizationUrl).host)
            assertFailsWith<ApiFailure>{social.poll(SocialPoll(attempt.id,secret()))}
            assertEquals("WAITING",social.poll(SocialPoll(attempt.id,verifier)).status)
            social.callback(provider,attempt.id,"provider-code",null,"device")
            assertFailsWith<ApiFailure>{social.callback(provider,attempt.id,"provider-code",null,"device")}
            val result=social.poll(SocialPoll(attempt.id,verifier))
            assertEquals("DONE",result.status);assertNotNull(store.authenticate(result.session!!.token))
            assertFailsWith<ApiFailure>{social.poll(SocialPoll(attempt.id,verifier))}
        }
    }
    @Test fun `VK ID gateway uses current endpoints and sends PKCE parameters in the expected locations`() {
        val requests=mutableListOf<Pair<String,Map<String,String>>>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/oauth2/auth") { exchange ->
            val query=decodeForm(exchange.requestURI.rawQuery.orEmpty())
            val body=decodeForm(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            synchronized(requests) { requests += "/oauth2/auth" to (query+body.mapKeys { "body:${it.key}" }) }
            val result="""{"access_token":"vk-access","state":"expected-state"}""".toByteArray()
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,result.size.toLong());exchange.responseBody.use { it.write(result) }
        }
        server.createContext("/oauth2/user_info") { exchange ->
            val query=decodeForm(exchange.requestURI.rawQuery.orEmpty())
            val body=decodeForm(exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8))
            synchronized(requests) { requests += "/oauth2/user_info" to (query+body.mapKeys { "body:${it.key}" }) }
            val result="""{"user":{"user_id":741,"email":"vk-user@example.test"}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type","application/json")
            exchange.sendResponseHeaders(200,result.size.toLong());exchange.responseBody.use { it.write(result) }
        }
        server.start()
        try {
            val gateway=ProviderGateway("http://127.0.0.1:${server.address.port}")
            val person=gateway.exchange(config("vk"),"provider-code","pkce-verifier","expected-state","device-id")
            assertEquals(ProviderPerson("741","vk-user@example.test"),person)
            assertEquals(mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "test-client",
                "redirect_uri" to "https://app.test/auth/social/vk/callback",
                "code_verifier" to "pkce-verifier",
                "device_id" to "device-id",
                "state" to "expected-state",
                "body:code" to "provider-code",
            ),requests[0].second)
            assertEquals(mapOf("client_id" to "test-client","body:access_token" to "vk-access"),requests[1].second)
        } finally { server.stop(0) }
    }
    @Test fun `explicit linking shares the existing account and cannot steal a linked identity`() {
        val alice=store.register(Credentials("${UUID.randomUUID()}@social.test","test-password-123"))
        val bob=store.register(Credentials("${UUID.randomUUID()}@social.test","test-password-123"))
        val subject=UUID.randomUUID().toString()
        val linked=store.socialIdentity("google",subject,alice.email,alice.userId)
        assertEquals(alice.userId,linked.userId)
        assertEquals(alice.userId,store.socialIdentity("google",subject,null,null).userId)
        assertEquals(setOf("google"),store.linkedProviders(alice.userId))
        assertFailsWith<ApiFailure>{store.socialIdentity("google",subject,null,bob.userId)}
        assertFailsWith<ApiFailure>{store.socialIdentity("google",UUID.randomUUID().toString(),null,alice.userId)}
    }
    @Test fun `email collisions never merge accounts automatically`() {
        val alice=store.register(Credentials("${UUID.randomUUID()}@social.test","test-password-123"))
        assertFailsWith<ApiFailure>{store.socialIdentity("yandex",UUID.randomUUID().toString(),alice.email,null)}
        assertTrue(store.linkedProviders(alice.userId).isEmpty())
        val fresh=store.socialIdentity("vk",UUID.randomUUID().toString(),null,null)
        assertNull(store.checkPassword(Credentials(fresh.email,"test-password-123")))
    }
    @Test fun `disabled provider anonymous linking provider mixup and denial do not issue a session`() {
        val verifier=secret();val social=SocialLogin(store,mapOf("google" to config("google"))) {_,_,_,_,_->error("Exchange must not run")}
        assertFailsWith<ApiFailure>{social.start(SocialStart("vk",challenge(verifier)),null)}
        assertFailsWith<ApiFailure>{social.start(SocialStart("google",challenge(verifier),true),null)}
        val attempt=social.start(SocialStart("google",challenge(verifier)),null)
        assertFailsWith<ApiFailure>{social.callback("vk",attempt.id,"code",null,null)}
        social.callback("google",attempt.id,null,"access_denied",null)
        val result=social.poll(SocialPoll(attempt.id,verifier));assertEquals("FAILED",result.status);assertNull(result.session)
    }
    @Test fun `connection proof binds host nonce and persistent server key`() {
        val path=Files.createTempDirectory("identity-test")
        val identity=ConnectionIdentity(path);val nonce=secret();val url="https://server.test"
        val proof=identity.proof(url,nonce)
        assertTrue(ConnectionTrust.verify(proof,url,nonce,identity.publicKey))
        assertEquals(identity.publicKey,ConnectionIdentity(path).publicKey)
        assertFalse(ConnectionTrust.verify(proof,"https://attacker.test",nonce,identity.publicKey))
        assertFalse(ConnectionTrust.verify(proof,url,secret(),identity.publicKey))
        assertFalse(ConnectionTrust.verify(proof,url,nonce,ConnectionIdentity(Files.createTempDirectory("other-key")).publicKey))
    }
    @Test fun `link callback requires the original session to remain valid`() {
        val account=store.register(Credentials("${UUID.randomUUID()}@social.test","test-password-123"))
        val subject=UUID.randomUUID().toString()
        val social=SocialLogin(store,mapOf("google" to config("google"))) {_,_,_,_,_->ProviderPerson(subject,null)}
        val verifier=secret()
        val attempt=social.start(SocialStart("google",challenge(verifier),true),store.authenticate(account.token),account.token)
        store.revoke(account.token)
        social.callback("google",attempt.id,"code",null,null)
        assertEquals("FAILED",social.poll(SocialPoll(attempt.id,verifier)).status)
        assertTrue(store.linkedProviders(account.userId).isEmpty())
        val current=store.login(Credentials(account.email,"test-password-123"))
        val next=social.start(SocialStart("google",challenge(verifier),true),store.authenticate(current.token),current.token)
        social.callback("google",next.id,"code",null,null)
        assertEquals("LINKED",social.poll(SocialPoll(next.id,verifier)).status)
        assertEquals(setOf("google"),store.linkedProviders(account.userId))
    }
    private fun decodeForm(value: String)=value.split('&').filter(String::isNotEmpty).associate { pair ->
        val parts=pair.split('=',limit=2)
        URLDecoder.decode(parts[0],StandardCharsets.UTF_8) to URLDecoder.decode(parts.getOrElse(1){""},StandardCharsets.UTF_8)
    }
}
