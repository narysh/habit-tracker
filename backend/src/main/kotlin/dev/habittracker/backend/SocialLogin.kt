package dev.habittracker.backend

import dev.habittracker.shared.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class ProviderConfig(val id: String,val title: String,val clientId: String,val clientSecret: String,val callback: String)
data class ProviderPerson(val subject: String,val email: String?)
fun form(values: Map<String,String>) = values.entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }

/** Exchanges provider-issued authorization codes only. Identity fields supplied by Android are never trusted. */
class ProviderGateway {
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private fun request(url: String,body: Map<String,String>?=null,bearer: String?=null,scheme: String="Bearer"): JsonObject {
        val builder=HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).header("Accept","application/json")
        if(body!=null) builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(body)))
        if(bearer!=null) builder.header("Authorization","$scheme $bearer")
        val response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString())
        if(response.statusCode()!=200) throw ApiFailure(502,"Провайдер не подтвердил вход")
        val json=WireJson.parseToJsonElement(response.body()).jsonObject
        if("error" in json) throw ApiFailure(401,"Провайдер отклонил вход")
        return json
    }
    fun exchange(config: ProviderConfig,code: String,verifier: String,state: String,device: String?): ProviderPerson {
        val tokenUrl=when(config.id) { "google"->"https://oauth2.googleapis.com/token"; "vk"->"https://id.vk.com/oauth2/auth"; else->"https://oauth.yandex.ru/token" }
        val params=mutableMapOf("grant_type" to "authorization_code","code" to code,"client_id" to config.clientId,"redirect_uri" to config.callback,"code_verifier" to verifier)
        if(config.id=="vk") { require(!device.isNullOrBlank());params["device_id"]=device;params["state"]=state }
        else params["client_secret"]=config.clientSecret
        val token=request(tokenUrl,params)
        if(config.id=="vk" && token["state"]?.jsonPrimitive?.content!=state) throw ApiFailure(401,"Некорректный state провайдера")
        val access=token["access_token"]?.jsonPrimitive?.content ?: throw ApiFailure(401,"Провайдер не выдал токен")
        val user=when(config.id) {
            "google"->request("https://openidconnect.googleapis.com/v1/userinfo",bearer=access)
            "vk"->request("https://id.vk.com/oauth2/user_info",mapOf("client_id" to config.clientId,"access_token" to access))["user"]?.jsonObject ?: throw ApiFailure(401,"Нет профиля VK")
            else->request("https://login.yandex.ru/info?format=json",bearer=access,scheme="OAuth")
        }
        val subject=user[when(config.id){"google"->"sub";"vk"->"user_id";else->"id"}]?.jsonPrimitive?.content ?: throw ApiFailure(401,"Нет идентификатора пользователя")
        if(config.id=="yandex" && user["client_id"]?.jsonPrimitive?.content!=config.clientId) throw ApiFailure(401,"Профиль другого приложения")
        val email=when(config.id) {
            "google"->if(user["email_verified"]?.jsonPrimitive?.booleanOrNull==true) user["email"]?.jsonPrimitive?.content else null
            "vk"->user["email"]?.jsonPrimitive?.content
            else->user["default_email"]?.jsonPrimitive?.content
        }
        return ProviderPerson(subject,email)
    }
}

class SocialLogin(private val store: Store,val configs: Map<String,ProviderConfig> = environment(),private val exchange: (ProviderConfig,String,String,String,String?)->ProviderPerson = ProviderGateway()::exchange) {
    private class Pending(val config: ProviderConfig,val challenge: String,val linkUser: String?,val linkToken: String?,val verifier: String=secret(),val expires: Instant=Instant.now().plusSeconds(300)) {
        var consumed=false
        var result: SocialResult?=null
    }
    private val pending=ConcurrentHashMap<String,Pending>()
    fun providers(identity: Identity?): List<SocialProviderInfo> {
        val linked=identity?.let { store.linkedProviders(it.userId) }.orEmpty()
        return listOf("google" to "Google","vk" to "VK","yandex" to "Яндекс").map { (id,title)->SocialProviderInfo(id,title,id in configs,id in linked) }
    }
    fun start(request: SocialStart,identity: Identity?,token: String?=null): SocialAttempt {
        require(Regex("[A-Za-z0-9_-]{43}").matches(request.challenge))
        if(request.link && identity==null) throw ApiFailure(401,"Войди перед привязкой")
        val config=configs[request.provider] ?: throw ApiFailure(503,"Вход через этого провайдера ещё не настроен владельцем сервера")
        pending.entries.removeIf { it.value.expires<Instant.now() }
        if(pending.size>=1000) throw ApiFailure(429,"Слишком много попыток входа")
        val id=secret();val attempt=Pending(config,request.challenge,if(request.link) identity!!.userId else null,if(request.link) token else null)
        pending[id]=attempt
        val authorize=when(config.id){"google"->"https://accounts.google.com/o/oauth2/v2/auth";"vk"->"https://id.vk.com/authorize";else->"https://oauth.yandex.ru/authorize"}
        val scope=when(config.id){"google"->"openid email";"vk"->"vkid.personal_info email";else->"login:email login:info"}
        val query=mapOf("response_type" to "code","client_id" to config.clientId,"redirect_uri" to config.callback,"scope" to scope,"state" to id,"code_challenge" to challenge(attempt.verifier),"code_challenge_method" to if(config.id=="vk") "s256" else "S256")
        return SocialAttempt(id,"$authorize?${form(query)}")
    }
    fun callback(provider: String,state: String,code: String?,error: String?,device: String?) {
        val attempt=pending[state] ?: throw ApiFailure(400,"Попытка входа не найдена")
        synchronized(attempt) {
            if(attempt.expires<Instant.now() || attempt.consumed || attempt.config.id!=provider) throw ApiFailure(400,"Попытка входа истекла или уже использована")
            attempt.consumed=true
            attempt.result=try {
                if(error!=null || code.isNullOrBlank()) SocialResult("FAILED",message="Вход отменён у провайдера")
                else {
                    if(attempt.linkUser!=null && store.authenticate(attempt.linkToken.orEmpty())?.userId!=attempt.linkUser) throw ApiFailure(401,"Сессия привязки отозвана. Войди и повтори попытку.")
                    require(code.length<=4096)
                    val person=exchange(attempt.config,code,attempt.verifier,state,device)
                    val identity=store.socialIdentity(provider,person.subject,person.email,attempt.linkUser)
                    if(attempt.linkUser!=null) SocialResult("LINKED") else SocialResult("DONE",store.newSession(identity))
                }
            } catch(e: ApiFailure) { SocialResult("FAILED",message=e.message) }
              catch(_: Exception) { SocialResult("FAILED",message="Не удалось подтвердить вход. Попробуй снова.") }
        }
    }
    fun poll(request: SocialPoll): SocialResult {
        val attempt=pending[request.id] ?: throw ApiFailure(410,"Попытка входа истекла")
        synchronized(attempt) {
            if(attempt.expires<Instant.now()){pending.remove(request.id);throw ApiFailure(410,"Попытка входа истекла")}
            if(!equalSecrets(challenge(request.verifier),attempt.challenge)) throw ApiFailure(403,"Неверный ключ попытки")
            val result=attempt.result ?: return SocialResult("WAITING")
            pending.remove(request.id)
            return result
        }
    }
    companion object {
        fun environment(): Map<String,ProviderConfig> {
            val base=(System.getenv("SOCIAL_CALLBACK_BASE") ?: "https://narysh.github.io/habit-tracker").trimEnd('/')
            require(URI(base).scheme=="https" && URI(base).host!=null)
            return listOf("google" to "Google","vk" to "VK","yandex" to "Яндекс").mapNotNull { (id,title)->
                val prefix=id.uppercase(); val client=System.getenv("${prefix}_CLIENT_ID")?.takeIf {it.isNotBlank()} ?: return@mapNotNull null
                val secret=System.getenv("${prefix}_CLIENT_SECRET").orEmpty()
                if(id!="vk" && secret.isBlank()) return@mapNotNull null
                id to ProviderConfig(id,title,client,secret,"$base/auth/social/$id/callback/")
            }.toMap()
        }
    }
}
