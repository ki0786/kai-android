package com.kai.assistant.business

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owner switch for WhatsApp Business auto-replies. Off by default. Turning it on
 * records [enabledAtMs] so older notifications are not answered.
 */
class AutoReplySettings private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun isEnabled(): Boolean = _enabled.value

    fun enabledAtMs(): Long = prefs.getLong(KEY_ENABLED_AT, 0L)

    fun setEnabled(on: Boolean) {
        val editor = prefs.edit().putBoolean(KEY_ENABLED, on)
        if (on) editor.putLong(KEY_ENABLED_AT, System.currentTimeMillis())
        editor.apply()
        _enabled.value = on
        EnquiryStatusNotifier.updateListeningIndicator(app, on && notificationAccessGranted(app))
        if (on) FollowUpScheduler.scheduleNext(app)
    }

    companion object {
        private const val PREFS = "kai_auto_reply"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENABLED_AT = "enabled_at_ms"

        @Volatile private var instance: AutoReplySettings? = null

        fun get(context: Context): AutoReplySettings =
            instance ?: synchronized(this) {
                instance ?: AutoReplySettings(context.applicationContext).also { instance = it }
            }

        fun notificationAccessGranted(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val cn = "${context.packageName}/${WhatsAppBusinessNotificationListenerService::class.java.name}"
            return flat.split(':').any { it.equals(cn, ignoreCase = true) || it.endsWith("/${WhatsAppBusinessNotificationListenerService::class.java.name}") }
        }

        fun notificationAccessIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
}
