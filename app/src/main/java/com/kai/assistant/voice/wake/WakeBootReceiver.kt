package com.kai.assistant.voice.wake

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Restores background Hey KAI after boot.
 * On Android 15 and newer this never starts the microphone foreground service.
 * It posts a notification, and the service starts only when that notification is tapped.
 */
class WakeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = WakePrefs.isEnabled(context)
        applyBootHandoff(
            enabled = enabled,
            sdkInt = Build.VERSION.SDK_INT,
            actions = object : BootActions {
                override fun startMicrophoneService() {
                    WakeWordController.start(context)
                }

                override fun postTapNotification() {
                    WakeWordController.postTapToStart(context)
                }
            },
        )
    }
}
