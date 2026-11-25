package com.kai.assistant.voice.wake

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.kai.assistant.MainActivity

/** Starts and stops [WakeWordForegroundService]. Does not record audio itself. */
internal object WakeWordController {
    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
        if (!micGranted(context)) return
        val intent = Intent(context, WakeWordForegroundService::class.java)
            .setAction(WakeWordForegroundService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    /** Ask a running listener to download the model again, or start it if it is not running. */
    fun retry(context: Context) {
        if (!micGranted(context)) return
        val intent = Intent(context, WakeWordForegroundService::class.java)
            .setAction(WakeWordForegroundService.ACTION_RETRY)
        if (WakeWordForegroundService.running) {
            context.startService(intent)
        } else {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** Starts only when the user left background Hey KAI on and this process is already allowed to. */
    fun startIfEnabled(context: Context) {
        if (!WakePrefs.isEnabled(context) || WakeWordForegroundService.running) return
        start(context)
    }

    fun pause(context: Context) {
        context.startService(
            Intent(context, WakeWordForegroundService::class.java)
                .setAction(WakeWordForegroundService.ACTION_PAUSE),
        )
    }

    fun resume(context: Context) {
        context.startService(
            Intent(context, WakeWordForegroundService::class.java)
                .setAction(WakeWordForegroundService.ACTION_RESUME),
        )
    }

    fun stop(context: Context) {
        if (!WakeWordForegroundService.running) {
            WakeRuntime.presence(WakePresence.Off)
            return
        }
        context.startService(
            Intent(context, WakeWordForegroundService::class.java)
                .setAction(WakeWordForegroundService.ACTION_STOP),
        )
    }

    fun openVoice(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_VOICE, true)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, System.nanoTime())
        }
        context.startActivity(intent)
    }

    fun postTapToStart(context: Context) {
        WakeNotifications.postTapToStart(context)
    }
}
