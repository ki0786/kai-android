package com.kai.assistant.tools

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Reminder phrases, including several in one sentence, natural times, and repeats.
 * Scheduling stays on the device.
 */
object ReminderUtterance {
    data class Parsed(
        val text: String,
        val hour: Int,
        val minute: Int,
        val meridiem: String?,
        val repeat: ReminderRepeat = ReminderRepeat.Once,
        val weekday: Int? = null,
        val inMinutes: Int? = null,
        val dayOffset: Int = 0,
    )

    fun parse(normalizedText: String): Parsed? = parseAll(normalizedText)?.singleOrNull()

    fun parseAll(normalizedText: String): List<Parsed>? {
        val text = prepare(normalizedText)
        val body = Regex("""^(?:please\s+)?remind\s+me\s+to\s+(.+)$""").find(text)?.groupValues?.get(1)
            ?: return null
        val parts = body.split(Regex("""\s+and\s+to\s+"""))
        if (parts.isEmpty()) return null
        return parts.map { parseClause(it.trim()) ?: return null }
    }

    fun parseCancel(normalizedText: String): String? {
        val text = prepare(normalizedText)
        val match = Regex("""^cancel\s+(?:the\s+)?reminder\s+(?:to\s+)?(.+)$""").find(text) ?: return null
        val query = match.groupValues[1].trim()
        return query.ifBlank { null }
    }

    fun parseChange(normalizedText: String): LocalCommand.ChangeReminder? {
        val text = prepare(normalizedText)
        val match = Regex(
            """^change\s+(?:the\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s+reminder\s+to\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""",
        ).find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].ifBlank { null }
        val newHour = match.groupValues[4].toIntOrNull() ?: return null
        val newMinute = match.groupValues[5].toIntOrNull() ?: 0
        val newMeridiem = match.groupValues[6].ifBlank { null }
        if (hour !in 0..23 || newHour !in 0..23 || minute !in 0..59 || newMinute !in 0..59) return null
        if (meridiem != null && hour !in 1..12) return null
        if (newMeridiem != null && newHour !in 1..12) return null
        return LocalCommand.ChangeReminder(hour, minute, meridiem, newHour, newMinute, newMeridiem)
    }

    private fun parseClause(raw: String): Parsed? {
        val (clause, repeat, weekday) = stripRepeat(raw)
        val relative = Regex("""^(.*?)\s+in\s+(\d{1,4})\s+minutes?$""").find(clause)
        if (relative != null) {
            val task = relative.groupValues[1].trim()
            val minutes = relative.groupValues[2].toIntOrNull() ?: return null
            if (task.isEmpty() || minutes !in 1..(24 * 60)) return null
            return Parsed(task, 0, 0, null, repeat, weekday, inMinutes = minutes)
        }
        if (clause.endsWith("tomorrow morning")) {
            val task = clause.removeSuffix("tomorrow morning").trim()
            if (task.isEmpty()) return null
            return Parsed(task, 8, 0, "am", repeat, weekday, dayOffset = 1)
        }
        if (clause.endsWith("tonight")) {
            val task = clause.removeSuffix("tonight").trim()
            if (task.isEmpty()) return null
            return Parsed(task, 8, 0, "pm", repeat, weekday)
        }
        val half = Regex(
            """^(.*?)\s+at\s+half\s+past\s+(\d{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s*(am|pm)?$""",
        ).find(clause)
        if (half != null) {
            val task = half.groupValues[1].trim()
            val hour = clockWord(half.groupValues[2]) ?: return null
            val meridiem = half.groupValues[3].ifBlank { null }
            if (task.isEmpty() || meridiem != null && hour !in 1..12) return null
            return Parsed(task, hour, 30, meridiem, repeat, weekday)
        }
        val clock = Regex("""^(.*?)\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""").find(clause)
            ?: return null
        val task = clock.groupValues[1].trim()
        val hour = clock.groupValues[2].toIntOrNull() ?: return null
        val minute = clock.groupValues[3].toIntOrNull() ?: 0
        val meridiem = clock.groupValues[4].ifBlank { null }
        if (task.isEmpty() || hour !in 0..23 || minute !in 0..59) return null
        if (meridiem != null && hour !in 1..12) return null
        return Parsed(task, hour, minute, meridiem, repeat, weekday)
    }

    private fun stripRepeat(raw: String): Triple<String, ReminderRepeat, Int?> {
        val weekly = Regex(
            """\bevery\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""",
        ).find(raw)
        if (weekly != null) {
            val day = weekdays.getValue(weekly.groupValues[1])
            return Triple(remove(raw, weekly.value), ReminderRepeat.Weekly, day)
        }
        val daily = Regex("""\bevery\s+day\b|\bdaily\b""").find(raw)
        if (daily != null) return Triple(remove(raw, daily.value), ReminderRepeat.Daily, null)
        val weekdaysOnly = Regex("""\bevery\s+weekday\b|\bon\s+weekdays\b|\bweekdays\b""").find(raw)
        if (weekdaysOnly != null) return Triple(remove(raw, weekdaysOnly.value), ReminderRepeat.Weekdays, null)
        return Triple(raw.trim(), ReminderRepeat.Once, null)
    }

    private fun remove(raw: String, phrase: String): String =
        raw.replace(phrase, " ").replace(Regex("\\s+"), " ").trim()

    /**
     * Speech often says "6:00 p.m." The router also strips a trailing period,
     * which leaves "p.m". Fold those into "pm" / "am" before matching.
     */
    private fun prepare(raw: String): String {
        return raw.trim()
            .replace(Regex("[?.!,]+$"), "")
            .replace(Regex("""(?i)\b([ap])\s*\.\s*m\s*\.?(?=\s|$)""")) {
                it.groupValues[1].lowercase() + "m"
            }
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}

internal fun nextReminderMillis(
    nowMillis: Long,
    zone: ZoneId,
    hour: Int,
    minute: Int,
    meridiem: String?,
    dayOffset: Int = 0,
    inMinutes: Int? = null,
): Long {
    if (inMinutes != null) return nowMillis + inMinutes * 60_000L
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
    val hours = candidateHours(hour, meridiem)
    if (dayOffset > 0) {
        val clockHour = hours.min()
        return now.plusDays(dayOffset.toLong())
            .withHour(clockHour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()
    }
    return hours.minOf { clockHour ->
        var whenAt = now.withHour(clockHour).withMinute(minute).withSecond(0).withNano(0)
        if (!whenAt.isAfter(now)) whenAt = whenAt.plusDays(1)
        whenAt.toInstant().toEpochMilli()
    }
}

internal fun resolvedHour(hour: Int, meridiem: String?, atMillis: Long, zone: ZoneId): Int {
    if (!meridiem.isNullOrBlank()) return to24Hour(hour, meridiem)
    return Instant.ofEpochMilli(atMillis).atZone(zone).hour
}

/** Move a 6 p.m. reminder "to 7" to 7 p.m., keeping the half of the day. */
internal fun changedHour(oldHour24: Int, newHour: Int, newMeridiem: String?): Int {
    if (!newMeridiem.isNullOrBlank()) return to24Hour(newHour, newMeridiem)
    if (newHour > 12) return newHour
    if (oldHour24 >= 12 && newHour in 1..11) return newHour + 12
    return newHour
}

internal fun nextRepeatMillis(
    afterMillis: Long,
    zone: ZoneId,
    hour24: Int,
    minute: Int,
    repeat: ReminderRepeat,
    weekday: Int?,
): Long {
    var cursor = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
        .withHour(hour24)
        .withMinute(minute)
        .withSecond(0)
        .withNano(0)
    val start = ZonedDateTime.ofInstant(Instant.ofEpochMilli(afterMillis), zone)
    repeat(370) {
        cursor = when (repeat) {
            ReminderRepeat.Daily -> cursor.plusDays(1)
            ReminderRepeat.Weekdays -> cursor.plusDays(1)
            ReminderRepeat.Weekly -> cursor.plusDays(1)
            ReminderRepeat.Once -> cursor.plusDays(1)
        }
        val dayOk = when (repeat) {
            ReminderRepeat.Weekdays -> cursor.dayOfWeek != DayOfWeek.SATURDAY &&
                cursor.dayOfWeek != DayOfWeek.SUNDAY
            ReminderRepeat.Weekly -> weekday != null && cursor.dayOfWeek.value == weekday
            else -> true
        }
        if (dayOk && cursor.isAfter(start)) return cursor.toInstant().toEpochMilli()
    }
    return cursor.toInstant().toEpochMilli()
}

private fun candidateHours(hour: Int, meridiem: String?): List<Int> {
    if (!meridiem.isNullOrBlank()) return listOf(to24Hour(hour, meridiem))
    if (hour == 0 || hour > 12) return listOf(hour.coerceIn(0, 23))
    if (hour == 12) return listOf(12)
    return listOf(hour, hour + 12)
}

internal fun to24Hour(hour: Int, meridiem: String): Int = when {
    meridiem.equals("am", ignoreCase = true) && hour == 12 -> 0
    meridiem.equals("pm", ignoreCase = true) && hour in 1..11 -> hour + 12
    else -> hour
}

private fun clockWord(raw: String): Int? {
    raw.toIntOrNull()?.let { return it.takeIf { hour -> hour in 1..12 } }
    return words[raw]
}

private val words = mapOf(
    "one" to 1,
    "two" to 2,
    "three" to 3,
    "four" to 4,
    "five" to 5,
    "six" to 6,
    "seven" to 7,
    "eight" to 8,
    "nine" to 9,
    "ten" to 10,
    "eleven" to 11,
    "twelve" to 12,
)

private val weekdays = mapOf(
    "monday" to DayOfWeek.MONDAY.value,
    "tuesday" to DayOfWeek.TUESDAY.value,
    "wednesday" to DayOfWeek.WEDNESDAY.value,
    "thursday" to DayOfWeek.THURSDAY.value,
    "friday" to DayOfWeek.FRIDAY.value,
    "saturday" to DayOfWeek.SATURDAY.value,
    "sunday" to DayOfWeek.SUNDAY.value,
)

internal fun weekdayName(value: Int?): String = when (value) {
    DayOfWeek.MONDAY.value -> "Monday"
    DayOfWeek.TUESDAY.value -> "Tuesday"
    DayOfWeek.WEDNESDAY.value -> "Wednesday"
    DayOfWeek.THURSDAY.value -> "Thursday"
    DayOfWeek.FRIDAY.value -> "Friday"
    DayOfWeek.SATURDAY.value -> "Saturday"
    DayOfWeek.SUNDAY.value -> "Sunday"
    else -> "week"
}

internal fun alarmSpokenLine(
    hour24: Int,
    minute: Int,
    repeat: ReminderRepeat = ReminderRepeat.Once,
    weekday: Int? = null,
): String {
    val tail = when (repeat) {
        ReminderRepeat.Daily -> " every day"
        ReminderRepeat.Weekdays -> " on weekdays"
        ReminderRepeat.Weekly -> " every ${weekdayName(weekday)}"
        ReminderRepeat.Once -> ""
    }
    val whenLabel = clockLabel(hour24, minute)
    return if (tail.isEmpty()) "Alarm set for $whenLabel" else "Alarm set for $whenLabel$tail."
}

internal fun alarmFiredLine(hour24: Int, minute: Int): String =
    "Alarm for ${clockLabel(hour24, minute)}"

/** Spoken line after opening the clock. A KAI notification is not an alarm. */
internal fun alarmOpenLine(
    openedSetAlarm: Boolean,
    hour24: Int,
    minute: Int,
    repeat: ReminderRepeat = ReminderRepeat.Once,
    weekday: Int? = null,
): String = if (openedSetAlarm) {
    alarmSpokenLine(hour24, minute, repeat, weekday)
} else {
    "Opening the clock"
}

internal fun clockLabel(hour24: Int, minute: Int): String {
    val h12 = hour24 % 12
    val hourLabel = if (h12 == 0) 12 else h12
    val meridiem = if (hour24 < 12) "a.m." else "p.m."
    return "$hourLabel:${"%02d".format(minute)} $meridiem"
}

internal fun scriptLanguage(text: String): String? {
    if (text.any { it in '\u0900'..'\u097F' }) return "hi"
    if (text.any { it in '\u0C80'..'\u0CFF' }) return "kn"
    return null
}

internal fun replyLanguage(text: String, settingsLanguage: String): String {
    scriptLanguage(text)?.let { return it }
    return if (settingsLanguage == "hi" || settingsLanguage == "kn") settingsLanguage else "en"
}

internal fun recognizerLanguageTag(languageCode: String): String = when (languageCode) {
    "hi" -> "hi-IN"
    "kn" -> "kn-IN"
    else -> "en-US"
}

/** Spoken words. A translated line is used only when that voice is actually installed. */
internal fun speechText(english: String, localized: String?, languageAvailable: Boolean): String =
    if (languageAvailable && !localized.isNullOrBlank()) localized else english

internal fun localizedReply(english: String, language: String): String? {
    if (language != "hi" && language != "kn") return null
    val alarm = Regex("""Alarm set for (\d{1,2}:\d{2} [ap]\.m\.)(.*)""").find(english) ?: return null
    val whenLabel = alarm.groupValues[1]
    val tail = alarm.groupValues[2]
    return when (language) {
        "hi" -> "$whenLabel का अलार्म सेट हो गया$tail"
        "kn" -> "$whenLabel ಅಲಾರ್ಮ್ ಸೆಟ್ ಆಗಿದೆ$tail"
        else -> null
    }
}
