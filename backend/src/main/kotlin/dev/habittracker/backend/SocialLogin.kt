package dev.habittracker.backend

import dev.habittracker.shared.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.time.Duration

internal const val VK_ID_ORIGIN = "https://id.vk.ru"

data class ProviderConfig(val id: String,val title: String,val clientId: String,val clientSecret: String,val callback: String)
data class ProviderPerson(val subject: String,val email: String?)
fun form(values: Map<String,String>) = values.entries.joinToString("&") { URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8") }

/** Exchanges provider-issued authorization codes only. Identity fields supplied by Android are never trusted. */
class ProviderGateway(private val vkIdOrigin: String = VK_ID_ORIGIN) {
    private val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()
    private fun request(url: String,body: Map<String,String>?=null,bearer: String?=null,scheme: String="Bearer",query: Map<String,String> = emptyMap()): JsonObject {
        val target=if(query.isEmpty()) url else "$url?${form(query)}"
        val builder=HttpRequest.newBuilder(URI(target)).timeout(Duration.ofSeconds(20)).header("Accept","application/json")
        if(body!=null) builder.header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(body)))
        if(bearer!=null) builder.header("Authorization","$scheme $bearer")
        val response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString())
        if(response.statusCode()!=200) throw ApiFailure(502,"Провайдер не подтвердил вход")
        val json=WireJson.parseToJsonElement(response.body()).jsonObject
        if("error" in json) throw ApiFailure(401,"Провайдер отклонил вход")
        return json
    }
    fun exchange(config: ProviderConfig,code: String,verifier: String,state: String,device: String?): ProviderPerson {
        val tokenUrl=when(config.id) { "google"->"https://oauth2.googleapis.com/token"; "vk"->"$vkIdOrigin/oauth2/auth"; else->"https://oauth.yandex.ru/token" }
        val params=mutableMapOf("grant_type" to "authorization_code","client_id" to config.clientId,"redirect_uri" to config.callback,"code_verifier" to verifier)
        val token=if(config.id=="vk") {
            require(!device.isNullOrBlank())
            params["device_id"]=device;params["state"]=state
            request(tokenUrl,mapOf("code" to code),query=params)
        } else {
            params["code"]=code;params["client_secret"]=config.clientSecret
            request(tokenUrl,params)
        }
        if(config.id=="vk" && token["state"]?.jsonPrimitive?.content!=state) throw ApiFailure(401,"Некорректный state провайдера")
        val access=token["access_token"]?.jsonPrimitive?.content ?: throw ApiFailure(401,"Провайдер не выдал токен")
        val user=when(config.id) {
            "google"->request("https://openidconnect.googleapis.com/v1/userinfo",bearer=access)
            "vk"->request("$vkIdOrigin/oauth2/user_info",mapOf("access_token" to access),query=mapOf("client_id" to config.clientId))["user"]?.jsonObject ?: throw ApiFailure(401,"Нет профиля VK")
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

class SocialLogin(
    private val store: Store,
    val configs: Map<String,ProviderConfig> = environment(),
    stateEncryptionKey: ByteArray,
    private val exchange: (ProviderConfig,String,String,String,String?)->ProviderPerson = ProviderGateway()::exchange,
) {
    private val stateCipher=SocialStateCipher(stateEncryptionKey)
    fun providers(identity: Identity?): List<SocialProviderInfo> {
        val linked=identity?.let { store.linkedProviders(it.userId) }.orEmpty()
        return listOf("google" to "Google","vk" to "VK","yandex" to "Яндекс").map { (id,title)->SocialProviderInfo(id,title,id in configs,id in linked) }
    }
    fun start(request: SocialStart,identity: Identity?,token: String?=null): SocialAttempt {
        require(Regex("[A-Za-z0-9_-]{43}").matches(request.challenge))
        if(request.link && identity==null) throw ApiFailure(401,"Войди перед привязкой")
        val config=configs[request.provider] ?: throw ApiFailure(503,"Вход через этого провайдера ещё не настроен владельцем сервера")
        val id=secret();val providerVerifier=secret()
        val linkUser=if(request.link) identity!!.userId else null
        val linkSession=if(request.link) token?.let(::digest) ?: throw ApiFailure(401,"Войди перед привязкой") else null
        val stateHash=digest(id)
        store.createSocialAttempt(stateHash,config.id,request.challenge,stateCipher.encrypt(providerVerifier,"$stateHash:${config.id}"),linkUser,linkSession)
        val authorize=when(config.id){"google"->"https://accounts.google.com/o/oauth2/v2/auth";"vk"->"$VK_ID_ORIGIN/authorize";else->"https://oauth.yandex.ru/authorize"}
        val scope=when(config.id){"google"->"openid email";"vk"->"vkid.personal_info email";else->"login:email login:info"}
        val query=mapOf("response_type" to "code","client_id" to config.clientId,"redirect_uri" to config.callback,"scope" to scope,"state" to id,"code_challenge" to challenge(providerVerifier),"code_challenge_method" to if(config.id=="vk") "s256" else "S256")
        return SocialAttempt(id,"$authorize?${form(query)}")
    }
    fun callback(provider: String,state: String,code: String?,error: String?,device: String?) {
        val stateHash=digest(state)
        val attempt=store.claimSocialCallback(stateHash,provider)
        if(error!=null || code.isNullOrBlank()) {
            store.failSocialCallback(stateHash,attempt.callbackClaim,"Вход отменён у провайдера")
            return
        }
        try {
            require(code.length<=4096)
            val config=configs[provider] ?: throw ApiFailure(503,"Вход через этого провайдера ещё не настроен владельцем сервера")
            val person=exchange(config,code,stateCipher.decrypt(attempt.providerVerifierCiphertext,"$stateHash:$provider"),state,device)
            if(!store.completeSocialCallback(stateHash,attempt.callbackClaim,person)) throw ApiFailure(410,"Попытка входа истекла")
        } catch(e: ApiFailure) {
            store.failSocialCallback(stateHash,attempt.callbackClaim,e.message)
        } catch(_: Exception) {
            store.failSocialCallback(stateHash,attempt.callbackClaim,"Не удалось подтвердить вход. Попробуй снова.")
        }
    }
    fun poll(request: SocialPoll): SocialResult {
        require(request.verifier.length in 43..128)
        return store.pollSocialAttempt(digest(request.id),request.verifier)
    }
    fun removeAttemptsForAccount(userId: String) {
        store.removeSocialAttemptsForAccount(userId)
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
