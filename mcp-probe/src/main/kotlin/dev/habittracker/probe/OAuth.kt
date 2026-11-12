package dev.habittracker.probe

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64

private val random = SecureRandom()
fun randomSecret(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
fun sha256(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
fun constantTimeEquals(a: String, b: String): Boolean = MessageDigest.isEqual(
    MessageDigest.getInstance("SHA-256").digest(a.toByteArray(UTF_8)),
    MessageDigest.getInstance("SHA-256").digest(b.toByteArray(UTF_8)),
)
fun encode(value: String): String = URLEncoder.encode(value, UTF_8)

data class Grant(val scopes: Set<String>, val expires: Instant)
data class Authorization(
    val clientId: String,
    val redirectUri: String,
    val state: String,
    val challenge: String,
    val scopes: Set<String>,
    val resource: String,
    val expires: Instant,
)

/** Probe-only authorization server: explicit client registration, PKCE, consent and opaque tokens. */
class OAuth(private val config: Config, private val clock: Clock = Clock.systemUTC()) {
    private val pending = mutableMapOf<String, Authorization>()
    private val codes = mutableMapOf<String, Authorization>()
    private val tokens = mutableMapOf<String, Grant>()
    private var attemptWindow = clock.instant()
    private var attempts = 0

    @Synchronized fun begin(params: Map<String, String>): String {
        prune()
        require(pending.size < 100) { "Too many pending authorizations" }
        require(params["client_id"] == config.clientId) { "Unknown client" }
        val redirect = params["redirect_uri"] ?: error("Missing redirect_uri")
        require(redirect in config.redirects) { "Unregistered redirect_uri" }
        require(params["response_type"] == "code") { "response_type must be code" }
        require(params["code_challenge_method"] == "S256") { "S256 PKCE required" }
        val challenge = params["code_challenge"].orEmpty()
        require(Regex("[A-Za-z0-9_-]{43}").matches(challenge)) { "Invalid code_challenge" }
        val resource = params["resource"] ?: error("Missing resource")
        require(resource == config.resource) { "Invalid resource" }
        val scopes = (params["scope"] ?: "tracker:read tracker:write").split(' ').filter { it.isNotEmpty() }.toSet()
        require(scopes.isNotEmpty() && scopes.all { it in setOf("tracker:read", "tracker:write") }) { "Invalid scope" }
        val state = params["state"].orEmpty()
        require(state.length in 1..2048) { "state is required" }
        val nonce = randomSecret()
        pending[sha256(nonce)] = Authorization(config.clientId, redirect, state, challenge, scopes, resource, clock.instant().plusSeconds(300))
        return nonce
    }

    @Synchronized fun pending(nonce: String): Authorization? {
        prune()
        return pending[sha256(nonce)]
    }

    @Synchronized fun consent(nonce: String, secret: String, allowed: Boolean): String {
        prune()
        if (clock.instant().isAfter(attemptWindow.plusSeconds(60))) {
            attemptWindow = clock.instant()
            attempts = 0
        }
        check(++attempts <= 20) { "Too many attempts; wait one minute" }
        require(constantTimeEquals(secret, config.pairingSecret)) { "Invalid pairing secret" }
        val auth = pending.remove(sha256(nonce)) ?: error("Consent expired or already used")
        val result = if (allowed) {
            val code = randomSecret()
            codes[sha256(code)] = auth.copy(expires = clock.instant().plusSeconds(60))
            "code=${encode(code)}"
        } else "error=access_denied"
        val separator = if ('?' in auth.redirectUri) "&" else "?"
        return "${auth.redirectUri}$separator$result&state=${encode(auth.state)}&iss=${encode(config.publicUrl)}"
    }

    @Synchronized fun exchange(params: Map<String, String>): Pair<String, Grant> {
        prune()
        require(params["client_id"] == config.clientId && constantTimeEquals(params["client_secret"].orEmpty(), config.clientSecret)) { "Invalid client credentials" }
        require(params["grant_type"] == "authorization_code") { "Unsupported grant_type" }
        val key = sha256(params["code"].orEmpty())
        val auth = codes[key] ?: error("Invalid or expired authorization code")
        require(params["redirect_uri"] == auth.redirectUri && params["resource"] == auth.resource) { "Grant binding mismatch" }
        val verifier = params["code_verifier"].orEmpty()
        require(Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier) && constantTimeEquals(sha256(verifier), auth.challenge)) { "Invalid PKCE verifier" }
        codes.remove(key)
        val token = randomSecret()
        val grant = Grant(auth.scopes, clock.instant().plusSeconds(3600))
        tokens[sha256(token)] = grant
        return token to grant
    }

    @Synchronized fun authenticate(token: String): Grant? {
        prune()
        return tokens[sha256(token)]
    }

    @Synchronized fun revoke(token: String) { tokens.remove(sha256(token)) }

    private fun prune() {
        val now = clock.instant()
        pending.entries.removeIf { !it.value.expires.isAfter(now) }
        codes.entries.removeIf { !it.value.expires.isAfter(now) }
        tokens.entries.removeIf { !it.value.expires.isAfter(now) }
    }
}
