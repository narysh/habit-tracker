package dev.habittracker.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.habittracker.shared.*
import kotlinx.serialization.encodeToString
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("account",Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("session",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("session",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun current(): Session? { return try {
        val encoded = prefs.getString("session",null) ?: return null
        val parts = encoded.split(':')
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        WireJson.decodeFromString<Session>(String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8))
    } catch (_: Exception) { null } }
    fun save(session: Session,baseUrl: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val payload = cipher.doFinal(WireJson.encodeToString(session).toByteArray())
        val encoded = Base64.encodeToString(cipher.iv,Base64.NO_WRAP) + ":" + Base64.encodeToString(payload,Base64.NO_WRAP)
        check(prefs.edit().putString("session",encoded).putString("url",baseUrl).commit())
    }
    fun url(): String = prefs.getString("url","http://10.0.2.2:8081")!!
    fun clear() { prefs.edit().remove("session").commit() }
}
class NetworkFailure(val status: Int,message: String) : java.io.IOException(message)
class Api(private val baseUrl: String,private val token: () -> String? = { null }) {
    init { val uri = URI(baseUrl); require(uri.scheme in setOf("http","https") && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Некорректный адрес сервера" } }
    fun request(path: String,method: String="GET",body: ByteArray?=null,mediaType: String="application/json"): ByteArray {
        val connection = URL(baseUrl.trimEnd('/')+path).openConnection() as HttpURLConnection
        connection.connectTimeout=10000; connection.readTimeout=20000; connection.requestMethod=method
        connection.instanceFollowRedirects=false
        connection.setRequestProperty("Accept","application/json")
        token()?.let { connection.setRequestProperty("Authorization","Bearer $it") }
        try {
            if(body != null) { connection.doOutput=true; connection.setRequestProperty("Content-Type",mediaType); connection.outputStream.use { it.write(body) } }
            val code = connection.responseCode
            if(code !in 200..299) {
                val text = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                val message = runCatching { WireJson.decodeFromString<ApiError>(text).error }.getOrDefault("Ошибка сервера: $code")
                throw NetworkFailure(code,if(code==401) "Сессия истекла. Войди снова; локальные изменения сохранены." else message)
            }
            return connection.inputStream.use { it.readBytes() }
        } finally { connection.disconnect() }
    }
    inline fun <reified T> get(path: String): T = WireJson.decodeFromString(request(path).toString(Charsets.UTF_8))
    inline fun <reified I,reified O> post(path: String,value: I): O = WireJson.decodeFromString(request(path,"POST",WireJson.encodeToString(value).toByteArray()).toString(Charsets.UTF_8))
}
