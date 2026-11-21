package com.mylittleworld.shared.logic

/**
 * Screen-time window handling. Windows may cross midnight ("20:00"-"07:00").
 * An empty or malformed window means "no restriction" rather than "blocked",
 * so a bad remote config can never lock a child out of the launcher.
 */
object Schedule {

    data class Window(val startMinutes: Int, val endMinutes: Int) {
        val crossesMidnight: Boolean get() = startMinutes > endMinutes
    }

    fun parseWindow(start: String, end: String): Window? {
        val s = parseTime(start) ?: return null
        val e = parseTime(end) ?: return null
        if (s == e) return null
        return Window(s, e)
    }

    fun parseTime(value: String): Int? {
        val trimmed = value.trim()
        val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(trimmed) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return hour * 60 + minute
    }

    fun minutesOfDay(hour: Int, minute: Int): Int = (hour % 24) * 60 + (minute % 60)

    /** True when [minutesOfDay] falls inside the window (inclusive start, exclusive end). */
    fun isWithin(window: Window, minutesOfDay: Int): Boolean =
        if (window.crossesMidnight) {
            minutesOfDay >= window.startMinutes || minutesOfDay < window.endMinutes
        } else {
            minutesOfDay >= window.startMinutes && minutesOfDay < window.endMinutes
        }

    fun label(window: Window): String = "%02d:%02d".format(
        window.startMinutes / 60,
        window.startMinutes % 60,
    )
}
