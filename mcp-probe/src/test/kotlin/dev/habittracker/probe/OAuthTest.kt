package dev.habittracker.probe

import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.net.URI
import java.net.URLDecoder
import kotlin.test.*
import org.junit.jupiter.api.Test

fun testConfig() = Config("https://tracker.example", "probe-client", "s".repeat(40), "p".repeat(40),
    setOf("https://chatgpt.com/connector_platform_oauth_redirect"), Files.createTempDirectory("probe-test").resolve("tasks.json"))
fun authParams(config: Config, verifier: String = "v".repeat(43)) = mapOf(
    "client_id" to config.clientId, "redirect_uri" to config.redirects.single(), "response_type" to "code",
    "state" to "test-state", "code_challenge" to sha256(verifier), "code_challenge_method" to "S256",
    "scope" to "tracker:read tracker:write", "resource" to config.resource,
)
fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
    val pair = it.split('=', limit = 2)
    URLDecoder.decode(pair[0], Charsets.UTF_8) to URLDecoder.decode(pair[1], Charsets.UTF_8)
}
fun tokenParams(config: Config, code: String) = mapOf(
    "client_id" to config.clientId, "client_secret" to config.clientSecret, "grant_type" to "authorization_code",
    "code" to code, "redirect_uri" to config.redirects.single(), "resource" to config.resource,
    "code_verifier" to "v".repeat(43),
)

class OAuthTest {
    @Test fun `native callback requires explicit loopback address port and exact allowlist`() {
        val config = testConfig().copy(redirects = setOf("http://127.0.0.1:45837/callback"))
        val oauth = OAuth(config)
        assertNotNull(oauth.begin(authParams(config)))
        for (redirect in listOf("http://127.0.0.1:45838/callback", "http://127.0.0.1:45837/other")) {
            assertFails { oauth.begin(authParams(config) + ("redirect_uri" to redirect)) }
        }
        for (redirect in listOf("http://attacker.example/callback", "http://127.0.0.1/callback", "http://127.0.0.1.attacker.example:45837/callback")) {
            assertFails { config.copy(redirects = setOf(redirect)) }
        }
    }
    @Test fun `PKCE codes are single use and token can be revoked`() {
        val config = testConfig(); val oauth = OAuth(config)
        val nonce = oauth.begin(authParams(config))
        val redirect = query(oauth.consent(nonce, config.pairingSecret, true))
        assertEquals("test-state", redirect["state"])
        assertEquals(config.publicUrl, redirect["iss"])
        val form = tokenParams(config, redirect.getValue("code"))
        assertFails { oauth.exchange(form + ("code_verifier" to "wrong".repeat(12))) }
        assertFails { oauth.exchange(form + ("resource" to "https://attacker.example")) }
        assertFails { oauth.exchange(form + ("client_secret" to "invalid")) }
        val (token, grant) = oauth.exchange(form)
        assertEquals(setOf("tracker:read", "tracker:write"), grant.scopes)
        assertNotNull(oauth.authenticate(token))
        assertNull(oauth.authenticate("invalid"))
        assertFails { oauth.exchange(form) }
        oauth.revoke(token)
        assertNull(oauth.authenticate(token))
    }
    @Test fun `rejects unsafe redirects weak PKCE and unknown resource`() {
        val config = testConfig(); val oauth = OAuth(config); val params = authParams(config)
        for ((key, value) in listOf("redirect_uri" to "https://attacker.example", "client_id" to "other", "code_challenge_method" to "plain", "resource" to config.publicUrl, "scope" to "admin", "state" to "")) {
            assertFails("Must reject $key") { oauth.begin(params + (key to value)) }
        }
    }
    @Test fun `denial never creates a code and invalid secret cannot authorize`() {
        val config = testConfig(); val oauth = OAuth(config); val nonce = oauth.begin(authParams(config))
        assertFails { oauth.consent(nonce, "invalid", true) }
        val result = query(oauth.consent(nonce, config.pairingSecret, false))
        assertEquals("access_denied", result["error"]); assertNull(result["code"])
        assertFails { oauth.consent(nonce, config.pairingSecret, true) }
    }
    @Test fun `consent codes and tokens expire`() {
        val config = testConfig(); val clock = MutableClock(); val oauth = OAuth(config, clock)
        val nonce = oauth.begin(authParams(config)); clock.advance(301)
        assertFails { oauth.consent(nonce, config.pairingSecret, true) }
        val code = query(oauth.consent(oauth.begin(authParams(config)), config.pairingSecret, true)).getValue("code")
        clock.advance(61); assertFails { oauth.exchange(tokenParams(config, code)) }
        val fresh = query(oauth.consent(oauth.begin(authParams(config)), config.pairingSecret, true)).getValue("code")
        val token = oauth.exchange(tokenParams(config, fresh)).first
        clock.advance(3601); assertNull(oauth.authenticate(token))
    }
    @Test fun `pairing attempts are rate limited`() {
        val config = testConfig(); val oauth = OAuth(config); val nonce = oauth.begin(authParams(config))
        repeat(20) { assertFails { oauth.consent(nonce, "invalid", true) } }
        assertFailsWith<IllegalStateException> { oauth.consent(nonce, config.pairingSecret, true) }
    }
}

private class MutableClock : Clock() {
    private var now = Instant.parse("2026-11-12T00:00:00Z")
    override fun instant() = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(seconds: Long) { now = now.plusSeconds(seconds) }
}
