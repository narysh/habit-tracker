package dev.habittracker.backend

import dev.habittracker.shared.*
import java.nio.file.*
import java.security.*
import java.security.spec.*
import java.util.Base64

class ConnectionIdentity(directory: Path) {
    private val privateKey: PrivateKey
    val publicKey: String
    init {
        Files.createDirectories(directory)
        val privateFile=directory.resolve("private.der"); val publicFile=directory.resolve("public.der")
        if(!Files.exists(privateFile) && !Files.exists(publicFile)) {
            val generator=KeyPairGenerator.getInstance("EC");generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair=generator.generateKeyPair()
            Files.write(privateFile,pair.private.encoded,StandardOpenOption.CREATE_NEW)
            Files.write(publicFile,pair.public.encoded,StandardOpenOption.CREATE_NEW)
        }
        privateKey=KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Files.readAllBytes(privateFile)))
        publicKey=Base64.getEncoder().encodeToString(Files.readAllBytes(publicFile))
    }
    fun proof(url: String,nonce: String): ConnectionProof {
        require(Regex("[A-Za-z0-9_-]{43}").matches(nonce))
        val signature=Signature.getInstance("SHA256withECDSA").run { initSign(privateKey);update(ConnectionTrust.payload(url,nonce));sign() }
        return ConnectionProof(url,nonce,Base64.getEncoder().encodeToString(signature))
    }
}
