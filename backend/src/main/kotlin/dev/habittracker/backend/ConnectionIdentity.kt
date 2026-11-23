package dev.habittracker.backend

import dev.habittracker.shared.*
import java.nio.file.*
import java.security.*
import java.security.spec.*
import java.util.Base64

data class ConnectionKeyMaterial(val privateKeyBase64: String, val publicKeyBase64: String) {
    override fun toString(): String = "ConnectionKeyMaterial(privateKeyBase64=[redacted], publicKeyBase64=[redacted])"
}

class ConnectionIdentity(directory: Path, material: ConnectionKeyMaterial? = null) {
    private val privateKey: PrivateKey
    val publicKey: String
    init {
        val privateBytes: ByteArray
        val publicBytes: ByteArray
        if (material != null) {
            privateBytes = decodeKey(material.privateKeyBase64, "CONNECTION_PRIVATE_KEY_BASE64")
            publicBytes = decodeKey(material.publicKeyBase64, "CONNECTION_PUBLIC_KEY_BASE64")
        } else {
            Files.createDirectories(directory)
            val privateFile=directory.resolve("private.der"); val publicFile=directory.resolve("public.der")
            if(!Files.exists(privateFile) && !Files.exists(publicFile)) {
                val generator=KeyPairGenerator.getInstance("EC");generator.initialize(ECGenParameterSpec("secp256r1"))
                val pair=generator.generateKeyPair()
                Files.write(privateFile,pair.private.encoded,StandardOpenOption.CREATE_NEW)
                Files.write(publicFile,pair.public.encoded,StandardOpenOption.CREATE_NEW)
            }
            privateBytes = Files.readAllBytes(privateFile)
            publicBytes = Files.readAllBytes(publicFile)
        }
        privateKey=runCatching { KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes)) }
            .getOrElse { throw IllegalArgumentException("Invalid connection private key") }
        val publicKeyObject = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes)) }
            .getOrElse { throw IllegalArgumentException("Invalid connection public key") }
        if (material != null) {
            val challenge = ConnectionTrust.payload("https://habit-tracker.invalid", Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes)))
            val signature = Signature.getInstance("SHA256withECDSA").run { initSign(privateKey); update(challenge); sign() }
            require(Signature.getInstance("SHA256withECDSA").run { initVerify(publicKeyObject); update(challenge); verify(signature) }) {
                "Connection signing key pair does not match"
            }
        }
        publicKey=Base64.getEncoder().encodeToString(publicBytes)
    }
    fun proof(url: String,nonce: String): ConnectionProof {
        require(Regex("[A-Za-z0-9_-]{43}").matches(nonce))
        val signature=Signature.getInstance("SHA256withECDSA").run { initSign(privateKey);update(ConnectionTrust.payload(url,nonce));sign() }
        return ConnectionProof(url,nonce,Base64.getEncoder().encodeToString(signature))
    }

    private fun decodeKey(value: String, name: String): ByteArray = runCatching { Base64.getDecoder().decode(value.trim()) }
        .getOrElse { throw IllegalArgumentException("Invalid $name") }
}
