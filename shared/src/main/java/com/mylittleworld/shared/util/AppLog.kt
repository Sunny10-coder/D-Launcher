package com.mylittleworld.shared.util

import android.util.Log

/**
 * Thin structured logger. Never accepts raw tokens or child data: [redact]
 * strips anything that looks like a credential before it reaches logcat.
 */
object AppLog {

    fun d(tag: String, message: String) = Log.d(tag, redact(message))

    fun i(tag: String, message: String) = Log.i(tag, redact(message))

    fun w(tag: String, message: String, throwable: Throwable? = null) =
        Log.w(tag, redact(message), throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) =
        Log.e(tag, redact(message), throwable)

    fun redact(input: String): String {
        var out = input
        out = out.replace(TOKEN_PATTERN, "[redacted]")
        out = out.replace(EMAIL_PATTERN) { m ->
            val value = m.value
            val at = value.indexOf('@')
            if (at <= 1) "[email]" else "${value.first()}***${value.substring(at)}"
        }
        out = out.replace(COORD_PATTERN, "[coords]")
        return out.take(500)
    }

    private val TOKEN_PATTERN = Regex("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}")
    private val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}")
    private val COORD_PATTERN = Regex("-?\\d{1,3}\\.\\d{4,}\\s*,\\s*-?\\d{1,3}\\.\\d{4,}")
}
