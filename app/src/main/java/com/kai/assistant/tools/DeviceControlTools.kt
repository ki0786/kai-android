package com.kai.assistant.tools

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import com.kai.assistant.business.AutoReplySettings
import com.kai.assistant.business.WhatsAppBusinessNotificationListenerService

/**
 * On-phone toggles. Wi-Fi, Bluetooth, and hotspot cannot be switched by an app
 * that targets this SDK, so those open the system screen and say so.
 */
internal class RadioPanelTool(private val context: Context) {
    fun wifi(enabled: Boolean): ToolResult {
        val panel = radioUsesPanel(Build.VERSION.SDK_INT)
        val action = if (panel) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
        return open("Wi-Fi", enabled, action, panel)
    }

    fun bluetooth(enabled: Boolean): ToolResult =
        open("Bluetooth", enabled, Settings.ACTION_BLUETOOTH_SETTINGS, usesPanel = false)

    private fun open(label: String, enabled: Boolean, action: String, usesPanel: Boolean): ToolResult {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { context.startActivity(intent) }.isSuccess
        if (!opened) {
            val state = if (enabled) "on" else "off"
            return ToolResult.Error(
                "Android won't let an app turn $label $state, and I couldn't open the settings screen.",
            )
        }
        return ToolResult.Ok(blockedRadioSpeech(label, enabled, usesPanel))
    }
}

internal class DoNotDisturbTool(private val context: Context) {
    fun setEnabled(enabled: Boolean): ToolResult {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return ToolResult.Error("I couldn't change Do Not Disturb.")
        if (!manager.isNotificationPolicyAccessGranted) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            return if (opened) {
                ToolResult.Ok(DND_NEEDS_ACCESS)
            } else {
                ToolResult.Error("I need Do Not Disturb access, and I couldn't open that settings screen.")
            }
        }
        val filter = if (enabled) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            NotificationManager.INTERRUPTION_FILTER_ALL
        }
        val applied = runCatching {
            manager.setInterruptionFilter(filter)
            manager.currentInterruptionFilter
        }.getOrNull() ?: return ToolResult.Error("I couldn't change Do Not Disturb.")
        val onNow = applied != NotificationManager.INTERRUPTION_FILTER_ALL
        return when {
            enabled && onNow -> ToolResult.Ok(dndSpoken(true))
            !enabled && !onNow -> ToolResult.Ok(dndSpoken(false))
            enabled -> ToolResult.Error("I couldn't turn Do Not Disturb on.")
            else -> ToolResult.Error("I couldn't turn Do Not Disturb off.")
        }
    }
}

internal class BrightnessTool(private val context: Context) {
    fun adjust(up: Boolean): ToolResult {
        if (!Settings.System.canWrite(context)) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            return if (opened) {
                ToolResult.Ok(BRIGHTNESS_NEEDS_ACCESS)
            } else {
                ToolResult.Error("I need permission to change brightness, and I couldn't open that settings screen.")
            }
        }
        return runCatching {
            val resolver = context.contentResolver
            val mode = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            val automatic = mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            if (automatic) {
                val wroteMode = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                )
                if (!wroteMode) return ToolResult.Error("I couldn't turn off automatic brightness.")
            }
            val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            val next = steppedBrightness(current, up)
            val wrote = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
            if (!wrote) return ToolResult.Error("I couldn't change the brightness.")
            ToolResult.Ok(brightnessSpoken(current, next, automatic))
        }.getOrElse { ToolResult.Error("I couldn't change the brightness.") }
    }
}

internal class MediaControlTool(private val context: Context) {
    fun control(action: MediaAction): ToolResult {
        val controller = pickController(activeControllers(), action)
        if (controller != null) {
            val sent = runCatching {
                val controls = controller.transportControls
                when (action) {
                    MediaAction.Play -> controls.play()
                    MediaAction.Pause -> controls.pause()
                    MediaAction.Next -> controls.skipToNext()
                    MediaAction.Previous -> controls.skipToPrevious()
                }
            }.isSuccess
            if (sent) {
                val title = if (action == MediaAction.Play || action == MediaAction.Pause) {
                    controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                } else {
                    null
                }
                return ToolResult.Ok(mediaSpoken(action, title))
            }
        }
        if (sendKey(action)) return ToolResult.Ok(mediaSpoken(action, null))
        if (!AutoReplySettings.notificationAccessGranted(context)) {
            val intent = AutoReplySettings.notificationAccessIntent()
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
            return ToolResult.Ok(MEDIA_NEEDS_ACCESS)
        }
        return ToolResult.Ok("I couldn't control playback.")
    }

    private fun activeControllers(): List<MediaController> {
        if (!AutoReplySettings.notificationAccessGranted(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        val component = ComponentName(context, WhatsAppBusinessNotificationListenerService::class.java)
        return runCatching { manager.getActiveSessions(component) }.getOrDefault(emptyList())
    }

    private fun pickController(sessions: List<MediaController>, action: MediaAction): MediaController? {
        if (sessions.isEmpty()) return null
        val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val paused = sessions.firstOrNull {
            val state = it.playbackState?.state
            state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_STOPPED
        }
        return when (action) {
            MediaAction.Pause, MediaAction.Next, MediaAction.Previous -> playing ?: sessions.first()
            MediaAction.Play -> paused ?: playing ?: sessions.first()
        }
    }

    private fun sendKey(action: MediaAction): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val code = when (action) {
            MediaAction.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaAction.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaAction.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaAction.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
        }
        return runCatching {
            val now = SystemClock.uptimeMillis()
            audio.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            audio.dispatchMediaKeyEvent(KeyEvent(now + 1, now + 1, KeyEvent.ACTION_UP, code, 0))
        }.isSuccess
    }
}

internal class LatestNotificationTool(private val context: Context) {
    fun read(): ToolResult {
        if (!AutoReplySettings.notificationAccessGranted(context)) {
            val intent = AutoReplySettings.notificationAccessIntent()
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { context.startActivity(intent) }.isSuccess
            return if (opened) {
                ToolResult.Ok(NOTIFICATION_READ_NEEDS_ACCESS)
            } else {
                ToolResult.Ok("I can't read notifications until you allow KAI in notification access.")
            }
        }
        val notices = WhatsAppBusinessNotificationListenerService.postedNotices()
        if (notices == null) {
            val component = ComponentName(context, WhatsAppBusinessNotificationListenerService::class.java)
            runCatching { NotificationListenerService.requestRebind(component) }
            return ToolResult.Ok(NOTIFICATION_READ_NOT_READY)
        }
        val latest = pickLatestNotice(notices, context.packageName)
            ?: return ToolResult.Ok(NOTIFICATION_READ_EMPTY)
        return ToolResult.Ok(speakNotice(appLabel(latest.packageName), latest.title, latest.text))
    }

    private fun appLabel(packageName: String): String {
        val pm = context.packageManager
        val label = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
        if (!label.isNullOrBlank()) return label
        return packageName.substringAfterLast('.').ifBlank { "an app" }
    }
}
