package com.kai.assistant.voice.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kai.assistant.MainActivity
import com.kai.assistant.R

internal object WakeNotifications {
    const val CHANNEL_LISTENING = "kai_hey_kai"
    const val CHANNEL_RESUME = "kai_hey_kai_resume"
    const val CHANNEL_VOICE = "kai_hey_kai_voice"
    const val LISTENING_ID = 8101
    const val RESUME_ID = 8102
    const val VOICE_ID = 8103

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_LISTENING) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LISTENING,
                    "Hey KAI",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows while KAI is listening for Hey KAI"
                    setShowBadge(false)
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_RESUME) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_RESUME,
                    "Hey KAI resume",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Tap to start Hey KAI after the phone restarts"
                },
            )
        }
        if (manager.getNotificationChannel(CHANNEL_VOICE) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_VOICE,
                    "Hey KAI voice screen",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Opens the voice screen when you say Hey KAI"
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
    }

    fun listening(
        context: Context,
        text: String,
        actions: List<NotificationCompat.Action>,
    ): Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KAI")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        actions.forEach { builder.addAction(it) }
        return builder.build()
    }

    /**
     * Shown after reboot on Android 15. Tapping it is what starts the microphone service.
     * Posting this notification does not start that service.
     */
    fun postTapToStart(context: Context) {
        ensureChannels(context)
        val start = Intent(context, WakeWordForegroundService::class.java)
            .setAction(WakeWordForegroundService.ACTION_START)
        val pending = PendingIntent.getForegroundService(
            context,
            RESUME_ID,
            start,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESUME)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Hey KAI")
            .setContentText("Tap to start listening for Hey KAI")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(RESUME_ID, notification) }
    }

    fun cancelResume(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(RESUME_ID)
    }

    /**
     * Asks the system to open the voice screen. A full-screen intent is how the
     * lock screen is allowed to start an activity. On an unlocked phone the same
     * notification is the tap target when a direct start is blocked. This does
     * not replace the ongoing listening notification.
     */
    fun postVoiceScreen(context: Context, open: Intent) {
        ensureChannels(context)
        val pending = voiceScreenPending(context, open)
        val builder = NotificationCompat.Builder(context, CHANNEL_VOICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("KAI")
            .setContentText("Listening for your command")
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setTimeoutAfter(20_000)
        if (canUseFullScreen(context)) {
            builder.setFullScreenIntent(pending, true)
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(VOICE_ID, builder.build()) }
    }

    fun cancelVoiceScreen(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(VOICE_ID)
    }

    private fun voiceScreenPending(context: Context, open: Intent): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return if (Build.VERSION.SDK_INT >= 34) {
            val options = android.app.ActivityOptions.makeBasic()
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
            PendingIntent.getActivity(context, VOICE_ID, open, flags, options.toBundle())
        } else {
            PendingIntent.getActivity(context, VOICE_ID, open, flags)
        }
    }

    private fun canUseFullScreen(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.canUseFullScreenIntent()
    }
}
