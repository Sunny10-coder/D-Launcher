package com.mylittleworld.shared.logic

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN storage for the adult settings area.
 *
 * Uses PBKDF2-HMAC-SHA256 with a per-device random salt. The encoding is
 * `pbkdf2-sha256$<iterations>$<saltB64>$<hashB64>` so the iteration count can
 * be raised later without invalidating existing PINs.
 */
object PinPolicy {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 60_000
    private const val KEY_LENGTH_BITS = 256
    private const val PREFIX = "pbkdf2-sha256"

    fun isValidPin(pin: String): Boolean =
        pin.length in 4..8 && pin.all { it in '0'..'9' }

    fun hash(pin: String, salt: ByteArray = randomSalt()): String {
        require(isValidPin(pin)) { "PIN must be 4-8 digits" }
        val derived = derive(pin, salt, ITERATIONS)
        return "$PREFIX\$$ITERATIONS\$${Base64Codec.encode(salt)}\$${Base64Codec.encode(derived)}"
    }

    fun verify(pin: String, stored: String?): Boolean {
        if (stored.isNullOrBlank() || !isValidPin(pin)) return false
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != PREFIX) return false
        val iterations = parts[1].toIntOrNull() ?: return false
        val salt = runCatching { Base64Codec.decode(parts[2]) }.getOrNull() ?: return false
        val expected = runCatching { Base64Codec.decode(parts[3]) }.getOrNull() ?: return false
        val actual = derive(pin, salt, iterations)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun randomSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
}

internal object Base64Codec {
    fun encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    fun decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)
}
