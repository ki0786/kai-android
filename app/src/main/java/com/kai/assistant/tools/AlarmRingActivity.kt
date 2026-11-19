package com.kai.assistant.tools

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

/**
 * Leftover from an older build that opened a screen while scheduling.
 * It closes immediately so KAI stays in front.
 */
class AlarmRingActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_ID, -1)
        if (id >= 0) {
            LocalClockAlarmScheduler(applicationContext).onFired(id)
        }
        AlarmRinger.start(
            applicationContext,
            intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_HOUR, 0),
            intent.getIntExtra(LocalClockAlarmScheduler.EXTRA_MINUTE, 0),
        )
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

/** Plays the alarm sound without opening a screen. */
internal object AlarmRinger {
    private const val CHANNEL_ID = "kai_alarm_ring"
    private const val NOTIFICATION_ID = 9_200
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null
    private var wake: PowerManager.WakeLock? = null

    fun start(context: Context, hour: Int, minute: Int) {
        val app = context.applicationContext
        stop(app)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        if (uri != null) {
            val tone = RingtoneManager.getRingtone(app, uri)
            tone.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tone.isLooping = true
            }
            ringtone = tone
            tone.play()
        }
        val vib = app.getSystemService(Vibrator::class.java)
        vibrator = vib
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0))
        }
        val power = app.getSystemService(PowerManager::class.java)
        wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kai:alarm").apply {
            setReferenceCounted(false)
            acquire(2 * 60 * 1000L)
        }
        postStopNotice(app, hour, minute)
    }

    fun stop(context: Context) {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
        if (wake?.isHeld == true) wake?.release()
        wake = null
        context.applicationContext.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    private fun postStopNotice(context: Context, hour: Int, minute: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Alarms",
                    NotificationManager.IMPORTANCE_HIGH,
                )
                channel.setSound(null, null)
                manager.createNotificationChannel(channel)
            }
        }
        val stop = PendingIntent.getBroadcast(
            context,
            9_201,
            Intent(context, ClockAlarmReceiver::class.java).setAction(LocalClockAlarmScheduler.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm")
            .setContentText(alarmFiredLine(hour, minute))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(stop)
            .addAction(0, "Stop", stop)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }
}
