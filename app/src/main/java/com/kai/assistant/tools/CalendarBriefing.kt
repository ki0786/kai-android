package com.kai.assistant.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

internal sealed class CalendarRequest {
    data class Add(
        val title: String,
        val dayOffset: Int,
        val hour: Int,
        val minute: Int,
        val meridiem: String?,
    ) : CalendarRequest()

    data class Read(val dayOffset: Int) : CalendarRequest()
    data class Note(val text: String) : CalendarRequest()
    data object Briefing : CalendarRequest()
}

internal fun parseCalendarRequest(raw: String): CalendarRequest? {
    val text = raw.trim()
        .replace(Regex("[?.!,]+$"), "")
        .replace(Regex("""\b([aApP])\s*\.\s*[mM]\s*\.?(?=\s|$)"""), { it.groupValues[1] + "m" })
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (text.isBlank()) return null
    val lower = text.lowercase()
    if (lower == "daily briefing" || lower == "morning briefing" ||
        lower == "my daily briefing" || lower == "give me my daily briefing" ||
        lower == "give me a daily briefing" || lower == "what's my daily briefing" ||
        lower == "what is my daily briefing"
    ) {
        return CalendarRequest.Briefing
    }
    parseNote(text, lower)?.let { return it }
    parseRead(text, lower)?.let { return it }
    return parseAdd(text, lower)
}

internal fun eventHour24(hour: Int, meridiem: String?): Int {
    if (meridiem.isNullOrBlank()) return hour.coerceIn(0, 23)
    var adjusted = hour
    if (meridiem == "am" && adjusted == 12) adjusted = 0
    if (meridiem == "pm" && adjusted in 1..11) adjusted += 12
    return adjusted.coerceIn(0, 23)
}

internal fun dailyBriefingLine(
    events: List<String>,
    reminders: List<String>,
    nextAlarm: String?,
    calendarReadable: Boolean = true,
): String {
    val eventLine = when {
        !calendarReadable -> "I can't read your calendar without permission."
        events.isEmpty() -> "Nothing on your calendar today."
        else -> "Today: ${events.joinToString("; ")}"
    }
    val reminderLine = if (reminders.isEmpty()) {
        "No pending reminders."
    } else {
        "Pending reminders: ${reminders.joinToString("; ")}"
    }
    val alarmLine = if (nextAlarm.isNullOrBlank()) "No alarm set." else "Next alarm: $nextAlarm"
    return listOf(eventLine, reminderLine, alarmLine).joinToString(" ") { spokenSentence(it) }
}

private fun spokenSentence(body: String): String {
    val trimmed = body.trim()
    val last = trimmed.lastOrNull()
    return if (last == '.' || last == '?' || last == '!') trimmed else "$trimmed."
}

private fun parseNote(text: String, lower: String): CalendarRequest.Note? {
    val match = Regex("""^(?:please\s+)?note\s+that\s+(.+)$""").find(lower) ?: return null
    val body = match.groups[1] ?: return null
    val note = if (text.length == lower.length) text.substring(body.range).trim() else body.value.trim()
    if (note.length < 2) return null
    return CalendarRequest.Note(note)
}

private fun parseRead(text: String, lower: String): CalendarRequest.Read? {
    val match = Regex(
        """^(?:what(?:'s| is)|whats)\s+on\s+my\s+(?:schedule|calendar)(?:\s+(today|tomorrow))?$""" +
            """|^(?:show|read)\s+(?:me\s+)?my\s+(?:schedule|calendar)(?:\s+(today|tomorrow))?$""",
    ).find(lower) ?: return null
    val day = match.groups.drop(1).firstOrNull { it != null && it.value.isNotBlank() }?.value
    return CalendarRequest.Read(dayOffset = if (day == "tomorrow") 1 else 0)
}

private fun parseAdd(text: String, lower: String): CalendarRequest.Add? {
    val match = Regex(
        """^(?:please\s+)?(?:add|schedule|create)\s+(?:a\s+|an\s+)?(.+?)\s+(today|tomorrow)\s+at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$""",
    ).find(lower) ?: return null
    val titleGroup = match.groups[1] ?: return null
    val day = match.groupValues[2]
    val hour = match.groupValues[3].toIntOrNull() ?: return null
    val minute = match.groupValues[4].toIntOrNull() ?: 0
    val meridiem = match.groupValues[5].ifBlank { null }
    if (hour !in 0..23 || minute !in 0..59) return null
    if (meridiem != null && hour !in 1..12) return null
    val title = if (text.length == lower.length) text.substring(titleGroup.range).trim() else titleGroup.value.trim()
    if (title.length < 2) return null
    return CalendarRequest.Add(
        title = title,
        dayOffset = if (day == "tomorrow") 1 else 0,
        hour = hour,
        minute = minute,
        meridiem = meridiem,
    )
}

class PhoneNoteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun add(text: String) {
        val clean = text.replace('\n', ' ').replace('\t', ' ').trim()
        if (clean.isEmpty()) return
        val notes = all().toMutableList()
        notes += clean
        prefs.edit().putString(NOTES, notes.takeLast(100).joinToString("\n")).apply()
    }

    fun all(): List<String> {
        val raw = prefs.getString(NOTES, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    companion object {
        private const val PREFS = "kai_phone_notes"
        private const val NOTES = "notes"
    }
}

class CalendarTool(private val context: Context) {
    fun granted(): Boolean {
        val read = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val write = context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        return read && write
    }

    fun addEvent(title: String, dayOffset: Int, hour: Int, minute: Int, meridiem: String?): ToolResult {
        if (!granted()) return ToolResult.Error("I need calendar permission to add that.")
        val zone = ZoneId.systemDefault()
        val hour24 = eventHour24(hour, meridiem)
        val start = LocalDate.now(zone).plusDays(dayOffset.toLong())
            .atTime(hour24, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val calendarId = writableCalendarId()
            ?: return ToolResult.Error("I couldn't find a calendar on this phone.")
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + 60 * 60 * 1000)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) {
                ToolResult.Error("I couldn't add that to the calendar.")
            } else {
                val whenLabel = if (dayOffset == 0) "today" else "tomorrow"
                ToolResult.Ok("Added $title $whenLabel at ${clockLabel(hour24, minute)} on your calendar.")
            }
        } catch (_: SecurityException) {
            ToolResult.Error("I need calendar permission to add that.")
        }
    }

    fun eventsOn(dayOffset: Int): List<String> {
        if (!granted()) return emptyList()
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone).plusDays(dayOffset.toLong())
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, start)
        ContentUris.appendId(builder, end)
        return try {
            context.contentResolver.query(
                builder.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val titleIndex = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                buildList {
                    while (cursor.moveToNext()) {
                        val title = if (titleIndex >= 0) cursor.getString(titleIndex).orEmpty() else ""
                        val begin = if (beginIndex >= 0) cursor.getLong(beginIndex) else start
                        val whenLabel = Instant.ofEpochMilli(begin).atZone(zone)
                        val name = title.ifBlank { "Busy" }
                        add("$name at ${clockLabel(whenLabel.hour, whenLabel.minute)}")
                    }
                }
            }.orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    fun readSchedule(dayOffset: Int): ToolResult {
        if (!granted()) return ToolResult.Error("I need calendar permission to read your schedule.")
        val events = eventsOn(dayOffset)
        val dayName = if (dayOffset == 0) "today" else "tomorrow"
        if (events.isEmpty()) return ToolResult.Ok("Nothing on your schedule $dayName.")
        return ToolResult.Ok("On your schedule $dayName: ${events.joinToString("; ")}.")
    }

    private fun writableCalendarId(): Long? {
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CalendarContract.Calendars._ID)
                val primaryIndex = cursor.getColumnIndex(CalendarContract.Calendars.IS_PRIMARY)
                var fallback: Long? = null
                while (cursor.moveToNext()) {
                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                    val primary = primaryIndex >= 0 && cursor.getInt(primaryIndex) == 1
                    if (primary) return@use id
                    if (fallback == null) fallback = id
                }
                fallback
            }
        } catch (_: SecurityException) {
            null
        }
    }
}

class DailyBriefingTool(private val context: Context) {
    private val calendar = CalendarTool(context)

    fun speak(): ToolResult {
        val reminders = ReminderStore(context).all()
            .filter { it.atMillis >= System.currentTimeMillis() }
            .sortedBy { it.atMillis }
            .map { reminderLine(it) }
        val alarm = LocalClockAlarmScheduler(context).nextUpcoming()?.let { clockLabel(it.hour, it.minute) }
        val readable = calendar.granted()
        val events = if (readable) calendar.eventsOn(0) else emptyList()
        return ToolResult.Ok(
            dailyBriefingLine(
                events = events,
                reminders = reminders,
                nextAlarm = alarm,
                calendarReadable = readable,
            ),
        )
    }

    private fun reminderLine(reminder: StoredReminder): String {
        val zone = ZoneId.systemDefault()
        val zoned = Instant.ofEpochMilli(reminder.atMillis).atZone(zone)
        val hour = if (reminder.hour >= 0) reminder.hour else zoned.hour
        val minute = if (reminder.hour >= 0) reminder.minute else zoned.minute
        return "${reminder.text} at ${clockLabel(hour, minute)}"
    }
}
