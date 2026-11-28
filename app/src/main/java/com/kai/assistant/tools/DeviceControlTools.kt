package com.kai.assistant.tools

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import com.kai.assistant.business.AutoReplySettings
import com.kai.assistant.business.WhatsAppBusinessNotificationListenerService

/**
 * On-phone toggles. Wi-Fi and Bluetooth are switched on the phone. Hotspot
 * stays a settings note because Android blocks that radio.
 */
internal class RadioPanelTool(context: Context) {
    private val radios = RadioSwitch(context)

    fun wifi(enabled: Boolean): ToolResult = radios.wifi(enabled)

    fun bluetooth(enabled: Boolean): ToolResult = radios.bluetooth(enabled)
}

internal class RingerMemory(private val context: Context) {
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberBeforeSilence(ringerMode: Int, ringVolume: Int, dndAlreadyOn: Boolean) {
        val hasSession = prefs.getBoolean(KEY_HAS_SESSION, false)
        val legacyOwns = !hasSession &&
            !prefs.getBoolean(KEY_EXPLICIT_KNOWN, false) &&
            dndAlreadyOn
        val owned = prefs.getBoolean(KEY_APP_DND, false) || legacyOwns
        val storedVolume = prefs.getInt(KEY_VOLUME, -1)
        val volumeToKeep = when {
            ringVolume > 0 -> ringVolume
            hasSession && storedVolume > 0 -> storedVolume
            else -> ringVolume
        }
        val modeToKeep = if (ringVolume > 0 || !hasSession) {
            ringerMode
        } else {
            prefs.getInt(KEY_MODE, ringerMode)
        }
        prefs.edit()
            .putInt(KEY_MODE, modeToKeep)
            .putInt(KEY_VOLUME, volumeToKeep)
            .putBoolean(KEY_APP_DND, owned)
            .putBoolean(KEY_HAS_SESSION, true)
            .apply()
    }

    fun setAppEnabledDnd(enabled: Boolean) {
        if (!enabled) return
        prefs.edit().putBoolean(KEY_APP_DND, true).apply()
    }

    fun markExplicitDnd(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_EXPLICIT, enabled)
            .putBoolean(KEY_EXPLICIT_KNOWN, true)
            .putBoolean(KEY_APP_DND, false)
            .apply()
    }

    fun clearAppEnabledDnd() {
        prefs.edit().putBoolean(KEY_APP_DND, false).putBoolean(KEY_HAS_SESSION, true).apply()
    }

    fun snapshot(): RingerMemorySnapshot {
        val hasSession = prefs.getBoolean(KEY_HAS_SESSION, false)
        return RingerMemorySnapshot(
            previousMode = if (hasSession) prefs.getInt(KEY_MODE, RINGER_NORMAL) else null,
            previousVolume = if (hasSession) prefs.getInt(KEY_VOLUME, -1).takeIf { it >= 0 } else null,
            appEnabledDnd = prefs.getBoolean(KEY_APP_DND, false),
            explicitDnd = prefs.getBoolean(KEY_EXPLICIT, false) && prefs.getBoolean(KEY_EXPLICIT_KNOWN, false),
            unmarkedLegacy = !hasSession && !prefs.getBoolean(KEY_EXPLICIT_KNOWN, false),
        )
    }

    private companion object {
        const val PREFS = "kai_ringer"
        const val KEY_MODE = "mode"
        const val KEY_VOLUME = "volume"
        const val KEY_APP_DND = "app_dnd"
        const val KEY_EXPLICIT = "explicit_dnd"
        const val KEY_EXPLICIT_KNOWN = "explicit_dnd_known"
        const val KEY_HAS_SESSION = "has_session"
    }
}

internal data class RingerMemorySnapshot(
    val previousMode: Int?,
    val previousVolume: Int?,
    val appEnabledDnd: Boolean,
    val explicitDnd: Boolean,
    val unmarkedLegacy: Boolean,
)

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
            enabled && onNow -> {
                RingerMemory(context).markExplicitDnd(true)
                ToolResult.Ok(dndSpoken(true))
            }
            !enabled && !onNow -> {
                RingerMemory(context).markExplicitDnd(false)
                ToolResult.Ok(dndSpoken(false))
            }
            enabled -> ToolResult.Error("I couldn't turn Do Not Disturb on.")
            else -> ToolResult.Error("I couldn't turn Do Not Disturb off.")
        }
    }
}

internal class BrightnessTool(private val context: Context) {
    fun adjust(up: Boolean): ToolResult {
        if (!canWrite()) return ToolResult.Ok(BRIGHTNESS_NEEDS_ACCESS)
        val current = readLevel()
        return writeLevel(steppedBrightness(current, up))
    }

    fun setPercent(percent: Int): ToolResult {
        if (!canWrite()) return ToolResult.Ok(BRIGHTNESS_NEEDS_ACCESS)
        return writeLevel(indexForPercent(percent, 1, BRIGHTNESS_MAX))
    }

    private fun canWrite(): Boolean = Settings.System.canWrite(context)

    private fun readLevel(): Int =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)

    private fun writeLevel(level: Int): ToolResult {
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
            val wrote = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            if (!wrote) return ToolResult.Error("I couldn't change the brightness.")
            val now = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, level)
            val spoken = "Brightness is ${brightnessPercent(now)}%."
            ToolResult.Ok(if (automatic) "$spoken Automatic brightness is off." else spoken)
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
