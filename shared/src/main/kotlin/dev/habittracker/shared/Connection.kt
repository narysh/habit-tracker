package dev.habittracker.shared

import kotlinx.serialization.Serializable
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@Serializable data class ConnectionAddress(val url: String)
@Serializable data class ConnectionProof(val url: String,val nonce: String,val signature: String)
object ConnectionTrust {
    fun payload(url: String,nonce: String)="habit-tracker-connection-v1\n$url\n$nonce".toByteArray(Charsets.UTF_8)
    fun verify(proof: ConnectionProof,expectedUrl: String,nonce: String,publicKey: String): Boolean = runCatching {
        val uri=URI(expectedUrl)
        require(uri.scheme=="https" && uri.host!=null && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty())
        require(proof.url==expectedUrl && proof.nonce==nonce && Regex("[A-Za-z0-9_-]{43}").matches(nonce))
        val key=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey.trim())))
        Signature.getInstance("SHA256withECDSA").run { initVerify(key);update(payload(expectedUrl,nonce));verify(Base64.getDecoder().decode(proof.signature)) }
    }.getOrDefault(false)
}
