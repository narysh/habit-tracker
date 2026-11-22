package dev.habittracker.android

import android.content.Context
import dev.habittracker.shared.*
import java.security.SecureRandom
import java.util.Base64

class ServerDiscovery(context: Context) {
    private val publicKey=context.resources.openRawResource(R.raw.server_public_key).bufferedReader().use { it.readText().trim() }
    private var checkedAt=0L
    private var cached: String?=null
    @Synchronized fun resolve(force: Boolean=false): String {
        if(BuildConfig.BACKEND_ORIGIN.isNotEmpty()) return BuildConfig.BACKEND_ORIGIN
        if(!force && cached!=null && System.currentTimeMillis()-checkedAt<60_000) return cached!!
        val stamp=System.currentTimeMillis()/30_000
        val address=Api("https://raw.githubusercontent.com/narysh/habit-tracker/connection").get<ConnectionAddress>("/connection.json?refresh=$stamp").url.trimEnd('/')
        val uri=java.net.URI(address)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.path.isNullOrEmpty() && uri.query==null && uri.fragment==null) { "Некорректный адрес автоподключения" }
        val nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val proof=Api(address).get<ConnectionProof>("/connection/proof?nonce=$nonce")
        if(!ConnectionTrust.verify(proof,address,nonce,publicKey)) throw NetworkFailure(403,"Не удалось проверить подлинность сервера. Данные аккаунта не отправлены.")
        cached=address;checkedAt=System.currentTimeMillis()
        return address
    }
}
