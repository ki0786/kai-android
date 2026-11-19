package com.kai.assistant.tools

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * "remind me to … at 7" and the next time that clock time will occur.
 */
object ReminderUtterance {
    data class Parsed(
        val text: String,
        val hour: Int,
        val minute: Int,
        val meridiem: String?,
    )

    private val form = Regex(
        """^(?:please\s+)?remind\s+me\s+to\s+(.+?)\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""",
    )

    fun parse(normalizedText: String): Parsed? {
        val match = form.find(normalizedText.trim()) ?: return null
        val text = match.groupValues[1].trim()
        val hour = match.groupValues[2].toIntOrNull() ?: return null
        val minute = match.groupValues[3].toIntOrNull() ?: 0
        val meridiem = match.groupValues[4].ifBlank { null }
        if (text.isEmpty() || hour !in 0..23 || minute !in 0..59) return null
        if (meridiem != null && hour !in 1..12) return null
        return Parsed(text, hour, minute, meridiem)
    }
}

internal fun nextReminderMillis(
    nowMillis: Long,
    zone: ZoneId,
    hour: Int,
    minute: Int,
    meridiem: String?,
): Long {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
    val soonest = candidateHours(hour, meridiem).minOf { clockHour ->
        var whenAt = now.withHour(clockHour).withMinute(minute).withSecond(0).withNano(0)
        if (!whenAt.isAfter(now)) whenAt = whenAt.plusDays(1)
        whenAt.toInstant().toEpochMilli()
    }
    return soonest
}

private fun candidateHours(hour: Int, meridiem: String?): List<Int> {
    if (!meridiem.isNullOrBlank()) return listOf(to24Hour(hour, meridiem))
    if (hour == 0 || hour > 12) return listOf(hour.coerceIn(0, 23))
    if (hour == 12) return listOf(12)
    return listOf(hour, hour + 12)
}

private fun to24Hour(hour: Int, meridiem: String): Int = when {
    meridiem.equals("am", ignoreCase = true) && hour == 12 -> 0
    meridiem.equals("pm", ignoreCase = true) && hour in 1..11 -> hour + 12
    else -> hour
}
