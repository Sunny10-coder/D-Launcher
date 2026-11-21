package com.mylittleworld.shared.logic

/**
 * Formats a 6 digit pairing code as `123 456` for readability while keeping the
 * canonical value digit-only.
 */
object PairingCode {

    private val ALLOWED = Regex("^[0-9]{6}$")

    fun normalise(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return if (ALLOWED.matches(digits)) digits else null
    }

    fun display(code: String): String =
        if (code.length == 6) "${code.substring(0, 3)} ${code.substring(3)}" else code

    fun isExpired(expiresAtIso: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expires = runCatching { java.time.Instant.parse(expiresAtIso) }.getOrNull() ?: return true
        return expires.toEpochMilli() <= nowMillis
    }
}
