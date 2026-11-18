package dev.habittracker.backend

import dev.habittracker.shared.ChatGptLinkPreview
import java.net.URLEncoder
import java.time.Instant

data class AuthorizationRequest(val redirect: String, val state: String, val challenge: String, val scopes: Set<String>, val expires: Instant)
data class CodeGrant(val request: AuthorizationRequest, val identity: Identity)
data class BrowserAuthorization(val nonce: String, val request: AuthorizationRequest, val appCode: String?)
class AccountOAuth(private val settings: Settings, private val store: Store) {
    private data class Pending(val request: AuthorizationRequest, val appCodeHash: String?, var approved: Identity? = null)
    private val pending = mutableMapOf<String,Pending>()
    private val codes = mutableMapOf<String,CodeGrant>()
    @Synchronized fun begin(params: Map<String,String>): BrowserAuthorization {
        prune()
        require(pending.size < 100)
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
        pending[digest(nonce)] = Pending(request, appCode?.let(::digest))
        return BrowserAuthorization(nonce, request, appCode)
    }
    @Synchronized fun finish(nonce: String, identity: Identity?, allow: Boolean): String {
        prune()
        val key = digest(nonce)
        val entry = pending[key] ?: throw IllegalArgumentException("Запрос подключения истёк")
        require(!allow || entry.approved == null) { "Подключение уже подтверждено в приложении. Нажми «Завершить подключение»." }
        pending.remove(key)
        return redirect(entry.request, if (allow) requireNotNull(identity) else null)
    }
    @Synchronized fun previewAppLink(code: String): ChatGptLinkPreview {
        val entry = findAppLink(code)
        return ChatGptLinkPreview(entry.request.scopes, entry.request.expires.toString())
    }
    @Synchronized fun approveAppLink(code: String, identity: Identity) {
        val entry = findAppLink(code)
        require(identity.scopes.containsAll(entry.request.scopes)) { "Недостаточно прав для подключения" }
        entry.approved = identity
    }
    @Synchronized fun finishAppLink(nonce: String): String? {
        prune()
        if (!settings.publicPlugin) throw ApiFailure(404, "Подключение ChatGPT не настроено")
        val key = digest(nonce)
        val entry = pending[key] ?: throw ApiFailure(410, "Запрос подключения истёк. Начни заново в ChatGPT.")
        val identity = entry.approved ?: return null
        pending.remove(key)
        return redirect(entry.request, identity)
    }
    private fun findAppLink(code: String): Pending {
        if (!settings.publicPlugin) throw ApiFailure(404, "Подключение ChatGPT не настроено на этом сервере")
        prune()
        val normalized = code.trim()
        require(Regex("[A-Za-z0-9_-]{16}").matches(normalized)) { "Введи код из 16 символов со страницы подключения" }
        val hash = digest(normalized)
        return pending.values.firstOrNull { it.appCodeHash == hash && it.approved == null }
            ?: throw ApiFailure(404, "Код неверный, уже использован или истёк")
    }
    private fun redirect(request: AuthorizationRequest, identity: Identity?): String {
        val parameter = if (identity != null) {
            val code = secret()
            codes[digest(code)] = CodeGrant(request.copy(expires=Instant.now().plusSeconds(60)),identity.copy(scopes=request.scopes))
            "code=${encode(code)}"
        } else "error=access_denied"
        return request.redirect + (if('?' in request.redirect) "&" else "?") + parameter + "&state=${encode(request.state)}&iss=${encode(settings.publicUrl)}"
    }
    @Synchronized fun exchange(params: Map<String,String>): OAuthTokens {
        prune()
        require(params["client_id"] == settings.clientId && equalSecrets(params["client_secret"].orEmpty(),settings.clientSecret))
        require(params["resource"] == settings.resource)
        if(params["grant_type"]=="refresh_token") return store.refreshOAuth(params["refresh_token"].orEmpty(),settings.clientId,settings.resource)
        require(params["grant_type"] == "authorization_code")
        val key = digest(params["code"].orEmpty())
        val grant = codes[key] ?: throw IllegalArgumentException("Invalid grant")
        require(params["redirect_uri"] == grant.request.redirect)
        val verifier = params["code_verifier"].orEmpty()
        require(Regex("[A-Za-z0-9._~-]{43,128}").matches(verifier) && equalSecrets(challenge(verifier),grant.request.challenge))
        codes.remove(key)
        return store.issueOAuth(grant.identity,settings.clientId,settings.resource)
    }
    private fun prune() {
        pending.entries.removeIf { !it.value.request.expires.isAfter(Instant.now()) }
        codes.entries.removeIf { !it.value.request.expires.isAfter(Instant.now()) }
    }
}
fun encode(value: String): String = URLEncoder.encode(value,Charsets.UTF_8)
fun escape(value: String): String = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;")

/** In-memory throttling is intentionally per process; deploy one instance for this demo. */
class LoginThrottle {
    private val attempts = mutableMapOf<String,Pair<Instant,Int>>()
    @Synchronized fun check(key: String) {
        val now = Instant.now()
        attempts.entries.removeIf { it.value.first.plusSeconds(60).isBefore(now) }
        if(attempts.size > 10000) throw ApiFailure(429,"Слишком много запросов")
        val existing = attempts[key] ?: (now to 0)
        if(existing.second >= 20) throw ApiFailure(429,"Подожди минуту перед следующей попыткой")
        attempts[key] = existing.first to existing.second + 1
    }
}
