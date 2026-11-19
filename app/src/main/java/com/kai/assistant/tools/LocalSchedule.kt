package com.kai.assistant.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kai.assistant.MainActivity
import java.text.DateFormat
import java.util.Date

private const val PREFS = "kai_local_schedule"
private const val TIMER_END = "timer_end_ms"
private const val REMINDERS = "reminders"
private const val CHANNEL_ID = "kai_timers"
private const val TIMER_NOTIFICATION = 7101
private const val TIMER_REQUEST = 7101

internal fun durationLabel(seconds: Int): String = when {
    seconds % 3600 == 0 -> {
        val hours = seconds / 3600
        if (hours == 1) "1 hour" else "$hours hours"
    }
    seconds % 60 == 0 -> {
        val minutes = seconds / 60
        if (minutes == 1) "1 minute" else "$minutes minutes"
    }
    else -> "$seconds seconds"
}

class TimerScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun start(seconds: Int): ToolResult {
        val safe = seconds.coerceIn(1, 24 * 3600)
        val end = System.currentTimeMillis() + safe * 1000L
        synchronized(lock) {
            prefs.edit().putLong(TIMER_END, end).apply()
            schedule(end)
            notifyRunning(safe, end)
        }
        return ToolResult.Ok(
            "Timer started for ${durationLabel(safe)}. I'll notify you when it ends.",
        )
    }

    fun restore() {
        val speak = synchronized(lock) {
            val end = prefs.getLong(TIMER_END, 0L)
            if (end == 0L) return
            if (end <= System.currentTimeMillis()) {
                finishLocked()
            } else {
                schedule(end)
                false
            }
        }
        if (speak) SpokenAlert.speak(context, timerFinishedSpokenLine())
    }

    fun finish(onSpoken: () -> Unit = {}) {
        val speak = synchronized(lock) { finishLocked() }
        if (speak) SpokenAlert.speak(context, timerFinishedSpokenLine(), onSpoken) else onSpoken()
    }

    private fun finishLocked(): Boolean {
        if (prefs.getLong(TIMER_END, 0L) == 0L) return false
        prefs.edit().remove(TIMER_END).apply()
        notifyDone()
        return true
    }

    private fun schedule(end: Long) {
        val pending = timerIntent()
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        }
    }

    private fun timerIntent(): PendingIntent {
        val intent = Intent(context, TimerAlarmReceiver::class.java).setAction(ACTION_TIMER)
        return PendingIntent.getBroadcast(
            context,
            TIMER_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notifyRunning(seconds: Int, end: Long) {
        val whenLabel = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(end))
        notify(
            "Timer for ${durationLabel(seconds)}",
            "I'll notify you at $whenLabel.",
            ongoing = true,
        )
    }

    private fun notifyDone() {
        notify("Timer done", "Your timer finished.", ongoing = false)
    }

    private fun notify(title: String, text: String, ongoing: Boolean) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(TIMER_NOTIFICATION, notification)
    }

    companion object {
        private val lock = Any()
        const val ACTION_TIMER = "com.kai.assistant.TIMER_DONE"
    }
}

data class ReminderSpec(
    val text: String,
    val atMillis: Long,
    val hour: Int,
    val minute: Int,
    val repeat: ReminderRepeat = ReminderRepeat.Once,
    val weekday: Int? = null,
)

data class StoredReminder(
    val id: Int,
    val text: String,
    val atMillis: Long,
    val hour: Int = -1,
    val minute: Int = 0,
    val repeat: ReminderRepeat = ReminderRepeat.Once,
    val weekday: Int? = null,
)

class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<StoredReminder> {
        val raw = prefs.getString(REMINDERS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line -> decodeReminder(line) }.toList()
    }

    fun add(spec: ReminderSpec): StoredReminder {
        val clean = spec.text.replace('\n', ' ').replace('\t', ' ').trim()
        val next = (all().maxOfOrNull { it.id } ?: 0) + 1
        val created = StoredReminder(
            id = next,
            text = clean,
            atMillis = spec.atMillis,
            hour = spec.hour,
            minute = spec.minute,
            repeat = spec.repeat,
            weekday = spec.weekday,
        )
        save(all() + created)
        return created
    }

    fun replace(updated: StoredReminder) {
        save(all().map { if (it.id == updated.id) updated else it })
    }

    fun remove(id: Int) {
        save(all().filterNot { it.id == id })
    }

    private fun save(items: List<StoredReminder>) {
        val raw = items.joinToString("\n") {
            "${it.id}\t${it.atMillis}\t${it.hour}\t${it.minute}\t${it.repeat.name}\t${it.weekday ?: "-"}\t${it.text}"
        }
        prefs.edit().putString(REMINDERS, raw).apply()
    }

    private fun decodeReminder(line: String): StoredReminder? {
        val parts = line.split("\t", limit = 7)
        if (parts.size >= 7) {
            val id = parts[0].toIntOrNull() ?: return null
            val at = parts[1].toLongOrNull() ?: return null
            val hour = parts[2].toIntOrNull() ?: -1
            val minute = parts[3].toIntOrNull() ?: 0
            val repeat = runCatching { ReminderRepeat.valueOf(parts[4]) }.getOrDefault(ReminderRepeat.Once)
            val weekday = parts[5].toIntOrNull()
            return StoredReminder(id, parts[6], at, hour, minute, repeat, weekday)
        }
        val legacy = line.split("\t", limit = 3)
        if (legacy.size != 3) return null
        val id = legacy[0].toIntOrNull() ?: return null
        val at = legacy[1].toLongOrNull() ?: return null
        return StoredReminder(id, legacy[2], at)
    }
}

class ReminderScheduler(private val context: Context) {
    private val store = ReminderStore(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(spec: ReminderSpec): ToolResult {
        val whenAt = spec.atMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val saved = store.add(spec.copy(atMillis = whenAt))
        scheduleOne(saved)
        val repeat = repeatPhrase(saved.repeat, saved.weekday)
        return ToolResult.Ok(
            "I'll remind you to ${saved.text} at ${clockLabel(saved.hour, saved.minute)}$repeat. It stays on this phone.",
        )
    }

    fun cancelMatching(query: String): ToolResult {
        val needle = query.trim().lowercase()
        val matches = store.all().filter { it.text.lowercase().contains(needle) }
        if (matches.isEmpty()) {
            return ToolResult.Ok("I don't have a reminder to $query.")
        }
        matches.forEach {
            cancelPending(it.id)
            store.remove(it.id)
        }
        val noun = if (matches.size == 1) "reminder" else "reminders"
        return ToolResult.Ok("Cancelled ${matches.size} $noun to $query.")
    }

    fun changeClock(
        hour: Int,
        minute: Int,
        meridiem: String?,
        newHour: Int,
        newMinute: Int,
        newMeridiem: String?,
    ): ToolResult {
        val match = store.all().firstOrNull { reminder ->
            val storedHour = storedHour(reminder)
            val storedMinute = if (reminder.hour >= 0) reminder.minute else minuteOf(reminder.atMillis)
            if (meridiem != null) {
                storedHour == to24Hour(hour, meridiem) && storedMinute == minute
            } else {
                storedMinute == minute && (storedHour == hour || storedHour == (hour % 12) + 12 || storedHour == hour % 12)
            }
        } ?: return ToolResult.Ok("I don't have a reminder at that time.")
        val nextHour = changedHour(storedHour(match), newHour, newMeridiem)
        val at = nextReminderMillis(
            nowMillis = System.currentTimeMillis(),
            zone = java.time.ZoneId.systemDefault(),
            hour = nextHour,
            minute = newMinute,
            meridiem = null,
        )
        val updated = match.copy(atMillis = at, hour = nextHour, minute = newMinute)
        store.replace(updated)
        scheduleOne(updated)
        return ToolResult.Ok(
            "Changed the reminder to ${updated.text} at ${clockLabel(nextHour, newMinute)}.",
        )
    }

    fun restore() {
        val now = System.currentTimeMillis()
        store.all().forEach { reminder ->
            if (reminder.atMillis <= now) deliver(reminder.id, reminder.text) else scheduleOne(reminder)
        }
    }

    fun deliver(id: Int, text: String, onSpoken: () -> Unit = {}) {
        val reminder = store.all().firstOrNull { it.id == id }
        if (reminder == null) {
            onSpoken()
            return
        }
        if (reminder.repeat == ReminderRepeat.Once) {
            store.remove(reminder.id)
        } else {
            val next = nextRepeatMillis(
                afterMillis = reminder.atMillis,
                zone = java.time.ZoneId.systemDefault(),
                hour24 = storedHour(reminder),
                minute = if (reminder.hour >= 0) reminder.minute else minuteOf(reminder.atMillis),
                repeat = reminder.repeat,
                weekday = reminder.weekday,
            )
            val updated = reminder.copy(atMillis = next)
            store.replace(updated)
            scheduleOne(updated)
        }
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            reminder.id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Reminder")
            .setContentText(reminder.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(8_000 + reminder.id, notification)
        SpokenAlert.speak(context, reminderSpokenLine(reminder.text.ifBlank { text }), onSpoken)
    }

    private fun scheduleOne(reminder: StoredReminder) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_REMINDER)
            .putExtra(EXTRA_ID, reminder.id)
            .putExtra(EXTRA_TEXT, reminder.text)
        val pending = PendingIntent.getBroadcast(
            context,
            reminder.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        scheduleWake(alarms, reminder.atMillis, pending)
    }

    private fun cancelPending(id: Int) {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_REMINDER)
        val pending = PendingIntent.getBroadcast(
            context,
            id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun storedHour(reminder: StoredReminder): Int =
        if (reminder.hour >= 0) reminder.hour else hourOf(reminder.atMillis)

    private fun hourOf(atMillis: Long): Int =
        java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.systemDefault()).hour

    private fun minuteOf(atMillis: Long): Int =
        java.time.Instant.ofEpochMilli(atMillis).atZone(java.time.ZoneId.systemDefault()).minute

    private fun repeatPhrase(repeat: ReminderRepeat, weekday: Int?): String = when (repeat) {
        ReminderRepeat.Daily -> " every day"
        ReminderRepeat.Weekdays -> " on weekdays"
        ReminderRepeat.Weekly -> " every ${weekdayName(weekday)}"
        ReminderRepeat.Once -> ""
    }

    companion object {
        const val ACTION_REMINDER = "com.kai.assistant.REMINDER"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }
}

class TimerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TimerScheduler.ACTION_TIMER) return
        val pending = goAsync()
        TimerScheduler(context).finish { pending.finish() }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ReminderScheduler.ACTION_REMINDER) return
        val id = intent.getIntExtra(ReminderScheduler.EXTRA_ID, -1)
        val text = intent.getStringExtra(ReminderScheduler.EXTRA_TEXT).orEmpty()
        if (id < 0 || text.isBlank()) return
        val pending = goAsync()
        ReminderScheduler(context).deliver(id, text) { pending.finish() }
    }
}

class BootScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        TimerScheduler(context).restore()
        ReminderScheduler(context).restore()
        LocalClockAlarmScheduler(context).restore()
    }
}

/**
 * Schedules with [AlarmManager.setAlarmClock] in the background.
 * The status bar shows the alarm icon. No activity is opened.
 */
class LocalClockAlarmScheduler(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(
        hour: Int,
        minute: Int,
        message: String,
        repeat: ReminderRepeat,
        weekday: Int?,
        spoken: String,
    ): ToolResult {
        val at = firstClockMillis(hour, minute, repeat, weekday)
        val id = (all().maxOfOrNull { it.id } ?: 0) + 1
        val saved = StoredClockAlarm(id, at, hour, minute, message, repeat, weekday)
        save(all() + saved)
        scheduleOne(saved)
        return ToolResult.Ok(spoken)
    }

    fun restore() {
        val now = System.currentTimeMillis()
        val kept = mutableListOf<StoredClockAlarm>()
        all().forEach { alarm ->
            if (alarm.atMillis <= now) {
                cancelPending(alarm.id)
            } else {
                scheduleOne(alarm)
                kept += alarm
            }
        }
        save(kept)
    }

    /** Advances a repeating alarm after it rings. A one-time alarm is removed. */
    fun onFired(id: Int) {
        val alarm = all().firstOrNull { it.id == id } ?: return
        if (alarm.atMillis > System.currentTimeMillis() + 15_000) return
        if (alarm.repeat == ReminderRepeat.Once) {
            save(all().filterNot { it.id == id })
            return
        }
        val next = nextRepeatMillis(
            afterMillis = alarm.atMillis,
            zone = java.time.ZoneId.systemDefault(),
            hour24 = alarm.hour,
            minute = alarm.minute,
            repeat = alarm.repeat,
            weekday = alarm.weekday,
        )
        val updated = alarm.copy(atMillis = next)
        save(all().map { if (it.id == id) updated else it })
        scheduleOne(updated)
    }

    /** Rings in the background. Does not open an activity. */
    fun nextUpcoming(): UpcomingAlarm? {
        val now = System.currentTimeMillis()
        val next = all().filter { it.atMillis > now }.minByOrNull { it.atMillis } ?: return null
        return UpcomingAlarm(hour = next.hour, minute = next.minute, atMillis = next.atMillis)
    }

    fun deliver(id: Int, onSpoken: () -> Unit = {}) {
        val alarm = all().firstOrNull { it.id == id }
        if (alarm == null) {
            onSpoken()
            return
        }
        onFired(id)
        AlarmRinger.start(context, alarm.hour, alarm.minute)
        onSpoken()
    }

    private fun scheduleOne(alarm: StoredClockAlarm) {
        cancelLegacyActivity(alarm.id)
        val pending = ringPending(alarm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.atMillis, null), pending)
        } catch (_: SecurityException) {
            scheduleWake(alarms, alarm.atMillis, pending)
        }
    }

    private fun cancelPending(id: Int) {
        cancelLegacyActivity(id)
        val intent = Intent(context, ClockAlarmReceiver::class.java).setAction(ACTION_CLOCK)
        val pending = PendingIntent.getBroadcast(
            context,
            5_000 + id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    /** Older builds fired an activity. Cancel it so a blank screen cannot open. */
    private fun cancelLegacyActivity(id: Int) {
        val intent = Intent(context, AlarmRingActivity::class.java).setAction(ACTION_CLOCK)
        val pending = PendingIntent.getActivity(
            context,
            5_000 + id,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarms.cancel(pending)
        pending.cancel()
    }

    private fun ringPending(alarm: StoredClockAlarm, flags: Int): PendingIntent {
        val intent = Intent(context, ClockAlarmReceiver::class.java)
            .setAction(ACTION_CLOCK)
            .putExtra(EXTRA_ID, alarm.id)
            .putExtra(EXTRA_HOUR, alarm.hour)
            .putExtra(EXTRA_MINUTE, alarm.minute)
        return PendingIntent.getBroadcast(context, 5_000 + alarm.id, intent, flags)
    }

    private fun all(): List<StoredClockAlarm> {
        val raw = prefs.getString(CLOCK_ALARMS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 7)
            if (parts.size < 7) return@mapNotNull null
            StoredClockAlarm(
                id = parts[0].toIntOrNull() ?: return@mapNotNull null,
                atMillis = parts[1].toLongOrNull() ?: return@mapNotNull null,
                hour = parts[2].toIntOrNull() ?: return@mapNotNull null,
                minute = parts[3].toIntOrNull() ?: return@mapNotNull null,
                message = parts[6],
                repeat = runCatching { ReminderRepeat.valueOf(parts[4]) }.getOrDefault(ReminderRepeat.Once),
                weekday = parts[5].toIntOrNull(),
            )
        }.toList()
    }

    private fun save(items: List<StoredClockAlarm>) {
        val raw = items.joinToString("\n") {
            "${it.id}\t${it.atMillis}\t${it.hour}\t${it.minute}\t${it.repeat.name}\t${it.weekday ?: "-"}\t${it.message}"
        }
        prefs.edit().putString(CLOCK_ALARMS, raw).apply()
    }

    private fun firstClockMillis(hour: Int, minute: Int, rule: ReminderRepeat, weekday: Int?): Long {
        val zone = java.time.ZoneId.systemDefault()
        var at = nextReminderMillis(System.currentTimeMillis(), zone, hour, minute, null)
        if (rule == ReminderRepeat.Once || rule == ReminderRepeat.Daily) return at
        for (step in 0 until 8) {
            val day = java.time.Instant.ofEpochMilli(at).atZone(zone).dayOfWeek
            val ok = when (rule) {
                ReminderRepeat.Weekdays -> day != java.time.DayOfWeek.SATURDAY && day != java.time.DayOfWeek.SUNDAY
                ReminderRepeat.Weekly -> weekday != null && day.value == weekday
                else -> true
            }
            if (ok) return at
            at = nextRepeatMillis(at, zone, hour, minute, rule, weekday)
        }
        return at
    }

    companion object {
        const val ACTION_CLOCK = "com.kai.assistant.CLOCK_ALARM"
        const val ACTION_STOP = "com.kai.assistant.CLOCK_ALARM_STOP"
        const val EXTRA_ID = "id"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        private const val CLOCK_ALARMS = "clock_alarms"
    }
}

data class UpcomingAlarm(
    val hour: Int,
    val minute: Int,
    val atMillis: Long,
)

private data class StoredClockAlarm(
    val id: Int,
    val atMillis: Long,
    val hour: Int,
    val minute: Int,
    val message: String,
    val repeat: ReminderRepeat,
    val weekday: Int?,
)

class ClockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            LocalClockAlarmScheduler.ACTION_STOP -> AlarmRinger.stop(context)
            LocalClockAlarmScheduler.ACTION_CLOCK -> {
                val id = intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_ID, -1)
                if (id < 0) return
                val pending = goAsync()
                LocalClockAlarmScheduler(context).deliver(id) { pending.finish() }
            }
        }
    }
}

private fun scheduleWake(alarms: AlarmManager, atMillis: Long, pending: PendingIntent) {
    val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    try {
        if (canExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    } catch (_: SecurityException) {
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
    }
}

private fun ensureChannel(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Timers and reminders",
        NotificationManager.IMPORTANCE_HIGH,
    )
    manager.createNotificationChannel(channel)
}
