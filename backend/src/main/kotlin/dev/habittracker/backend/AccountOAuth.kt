package dev.habittracker.backend

import dev.habittracker.shared.ChatGptLinkPreview
import java.net.URLEncoder
import java.time.Instant

data class AuthorizationRequest(val redirect: String, val state: String, val challenge: String, val scopes: Set<String>, val expires: Instant)
data class BrowserAuthorization(val nonce: String, val request: AuthorizationRequest, val appCode: String?)

/** OAuth handshakes are persisted by Store, so any backend instance can finish them. */
class AccountOAuth(private val settings: Settings, private val store: Store) {
    fun begin(params: Map<String,String>): BrowserAuthorization {
        require(params["client_id"] == settings.clientId && params["response_type"] == "code") { "Unknown client or response type" }
        val redirect = params["redirect_uri"].orEmpty(); require(redirect in settings.redirects) { "Unregistered redirect URI" }
        require(params["resource"] == settings.resource) { "Invalid resource" }
        require(params["code_challenge_method"] == "S256")
        val challenge = params["code_challenge"].orEmpty(); require(Regex("[A-Za-z0-9_-]{43}").matches(challenge))
        val state = params["state"].orEmpty(); require(state.length in 1..2048)
        val scopes = (params["scope"] ?: "tracker:read tracker:write").split(' ').filter(String::isNotEmpty).toSet()
        require(scopes.isNotEmpty() && scopes.all { it in setOf("tracker:read","tracker:write") })
        val request = AuthorizationRequest(redirect,state,challenge,scopes,Instant.now().plusSeconds(300))
        val nonce = secret()
        val appCode = if (settings.publicPlugin) secret().take(16) else null
        store.createOAuthPending(digest(nonce), request, appCode?.let(::digest))
        return BrowserAuthorization(nonce, request, appCode)
    }

    fun finish(nonce: String, identity: Identity?, allow: Boolean): String {
        val code = if (allow) secret() else null
        val completed = store.finishOAuthPending(
            digest(nonce),
            if (allow) Store.OAuthPendingDecision.BROWSER_ALLOW else Store.OAuthPendingDecision.BROWSER_DENY,
            if (allow) requireNotNull(identity).userId else null,
            code?.let(::digest),
            settings.clientId,
            settings.resource,
        ) ?: throw IllegalStateException("OAuth request was not completed")
        return redirect(completed.request, code)
    }

    fun previewAppLink(code: String): ChatGptLinkPreview {
        val entry = findAppLink(code)
        return ChatGptLinkPreview(entry.request.scopes, entry.request.expires.toString())
    }

    fun approveAppLink(code: String, appToken: String) {
        if (!settings.publicPlugin) throw ApiFailure(404, "Подключение ChatGPT не настроено на этом сервере")
        store.approveOAuthPending(appCodeHash(code), appToken)
    }

    fun finishAppLink(nonce: String): String? {
        if (!settings.publicPlugin) throw ApiFailure(404, "Подключение ChatGPT не настроено")
        val code = secret()
        val completed = store.finishOAuthPending(
            digest(nonce), Store.OAuthPendingDecision.APP_FINISH, null, digest(code), settings.clientId, settings.resource
        ) ?: return null
        return redirect(completed.request, code)
    }

    fun disconnect(userId: String) {
        if (!settings.publicPlugin) throw ApiFailure(404, "Подключение ChatGPT не настроено на этом сервере")
        store.revokeOAuthConnection(userId, settings.clientId, settings.resource)
    }

    fun deleteAccount(userId: String, appToken: String) {
        store.deleteAccount(userId, appToken)
    }

    private fun appCodeHash(code: String): String {
        val normalized = code.trim()
        require(Regex("[A-Za-z0-9_-]{16}").matches(normalized)) { "Введи код из 16 символов со страницы подключения" }
        return digest(normalized)
    }

    private fun findAppLink(code: String): OAuthPendingState {
        if (!settings.publicPlugin) throw ApiFailure(404, "Подключение ChatGPT не настроено на этом сервере")
        return store.oauthPendingByAppCode(appCodeHash(code))
            ?: throw ApiFailure(404, "Код неверный, уже использован или истёк")
    }

    private fun redirect(request: AuthorizationRequest, code: String?): String {
        val parameter = if (code != null) "code=${encode(code)}" else "error=access_denied"
        return request.redirect + (if('?' in request.redirect) "&" else "?") + parameter + "&state=${encode(request.state)}&iss=${encode(settings.publicUrl)}"
    }

    fun exchange(params: Map<String,String>): OAuthTokens {
        require(params["client_id"] == settings.clientId && equalSecrets(params["client_secret"].orEmpty(),settings.clientSecret))
        require(params["resource"] == settings.resource)
        if(params["grant_type"]=="refresh_token") return store.refreshOAuth(params["refresh_token"].orEmpty(),settings.clientId,settings.resource)
        require(params["grant_type"] == "authorization_code")
        return store.redeemOAuthCode(
            digest(params["code"].orEmpty()), settings.clientId, settings.resource,
            params["redirect_uri"].orEmpty(), params["code_verifier"].orEmpty()
        )
    }
}

fun encode(value: String): String = URLEncoder.encode(value,Charsets.UTF_8)
fun escape(value: String): String = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")

/** Uses PostgreSQL in the server and an in-memory counter only for isolated unit tests. */
class LoginThrottle(private val maxAttemptsPerMinute: Int = 20, private val store: Store? = null) {
    private val attempts = mutableMapOf<String,Pair<Instant,Int>>()
    @Synchronized fun check(key: String) {
        if (store != null) {
            store.checkRateLimit(key, maxAttemptsPerMinute)
            return
        }
        val now = Instant.now()
        attempts.entries.removeIf { it.value.first.plusSeconds(60).isBefore(now) }
        if(attempts.size > 10000) throw ApiFailure(429,"Слишком много запросов")
        val existing = attempts[key] ?: (now to 0)
        if(existing.second >= maxAttemptsPerMinute) throw ApiFailure(429,"Подожди минуту перед следующей попыткой")
        attempts[key] = existing.first to existing.second + 1
    }
}
