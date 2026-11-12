package dev.habittracker.backend

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val secureRandom = SecureRandom()
fun secret(): String = ByteArray(32).also(secureRandom::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
fun challenge(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
fun equalSecrets(a: String, b: String): Boolean = MessageDigest.isEqual(digest(a).toByteArray(), digest(b).toByteArray())
object Passwords {
    private const val rounds = 600_000
    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    fun hash(password: String): String {
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val encoder = Base64.getEncoder()
        return "pbkdf2:$rounds:${encoder.encodeToString(salt)}:${encoder.encodeToString(derive(password, salt, rounds))}"
    }
    fun verify(password: String, encoded: String): Boolean = try {
        val fields = encoded.split(':'); require(fields.size == 4 && fields[0] == "pbkdf2")
        val iterations = fields[1].toInt(); require(iterations in rounds..2_000_000)
        MessageDigest.isEqual(Base64.getDecoder().decode(fields[3]), derive(password, Base64.getDecoder().decode(fields[2]), iterations))
    } catch (_: IllegalArgumentException) { false }
}

class ApiFailure(val status: Int, override val message: String) : RuntimeException(message)
