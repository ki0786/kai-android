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

data class StoredReminder(
    val id: Int,
    val text: String,
    val atMillis: Long,
)

class ReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<StoredReminder> {
        val raw = prefs.getString(REMINDERS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val parts = line.split("\t", limit = 3)
            if (parts.size != 3) return@mapNotNull null
            val id = parts[0].toIntOrNull() ?: return@mapNotNull null
            val at = parts[1].toLongOrNull() ?: return@mapNotNull null
            StoredReminder(id, parts[2], at)
        }.toList()
    }

    fun add(text: String, atMillis: Long): StoredReminder {
        val clean = text.replace('\n', ' ').replace('\t', ' ').trim()
        val next = (all().maxOfOrNull { it.id } ?: 0) + 1
        val created = StoredReminder(next, clean, atMillis)
        save(all() + created)
        return created
    }

    fun remove(id: Int) {
        save(all().filterNot { it.id == id })
    }

    private fun save(items: List<StoredReminder>) {
        val raw = items.joinToString("\n") { "${it.id}\t${it.atMillis}\t${it.text}" }
        prefs.edit().putString(REMINDERS, raw).apply()
    }
}

class ReminderScheduler(private val context: Context) {
    private val store = ReminderStore(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(text: String, atMillis: Long): ToolResult {
        val whenAt = atMillis.coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val saved = store.add(text, whenAt)
        scheduleOne(saved)
        val clock = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(whenAt))
        return ToolResult.Ok("I'll remind you to ${saved.text} at $clock. It stays on this phone.")
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
        store.remove(reminder.id)
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
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        if (canExact) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, pending)
        }
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
