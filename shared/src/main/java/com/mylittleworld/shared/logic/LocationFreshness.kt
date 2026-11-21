package com.mylittleworld.shared.logic

import java.time.Duration
import java.time.Instant

/**
 * Location freshness rules shared by both apps so parents are never shown a
 * stale fix as if it were live.
 */
object LocationFreshness {

    enum class State { LIVE, RECENT, STALE, EXPIRED, FUTURE }

    const val RECENT_SECONDS = 120L
    const val STALE_SECONDS = 60L * 30L
    const val EXPIRED_SECONDS = 60L * 60L * 24L

    fun state(capturedAt: Instant, now: Instant = Instant.now()): State {
        val seconds = Duration.between(capturedAt, now).seconds
        return when {
            seconds < -60 -> State.FUTURE
            seconds <= RECENT_SECONDS -> State.LIVE
            seconds <= STALE_SECONDS -> State.RECENT
            seconds <= EXPIRED_SECONDS -> State.STALE
            else -> State.EXPIRED
        }
    }

    /** Human facing label, e.g. "12 seconds ago" / "3 hours ago". */
    fun describe(capturedAt: Instant, now: Instant = Instant.now()): String {
        val seconds = Duration.between(capturedAt, now).seconds.coerceAtLeast(0)
        return when {
            seconds < 10 -> "just now"
            seconds < 60 -> "$seconds seconds ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    fun accuracyLabel(meters: Double): String = when {
        meters <= 0 -> "accuracy unknown"
        meters < 15 -> "very accurate (±${meters.toInt()} m)"
        meters < 60 -> "good (±${meters.toInt()} m)"
        meters < 250 -> "approximate (±${meters.toInt()} m)"
        else -> "coarse (±${meters.toInt()} m)"
    }

    fun isTrustworthy(sample: com.mylittleworld.shared.model.LocationSample): Boolean =
        !sample.isMock && sample.lat in -90.0..90.0 && sample.lng in -180.0..180.0 &&
            sample.accuracyMeters in 0.0..5_000.0
}
