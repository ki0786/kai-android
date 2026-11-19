package com.kai.assistant.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Whether the user has turned on the one-purpose WhatsApp send service, and the
 * settings screen that asks for that once.
 */
object WhatsAppSendAccess {
    private const val PREFS = "kai_whatsapp_send"
    private const val ASKED = "access_asked"
    private const val ACCESSIBILITY_DETAILS_SETTINGS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, WhatsAppSendAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val listed = enabled.split(':').any { raw ->
            ComponentName.unflattenFromString(raw) == component
        }
        if (!listed) return false
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
    }

    /** True only the first time the service is still off. */
    fun askOnce(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean(ASKED, false)
        if (!shouldAskToEnable(serviceEnabled = false, alreadyAsked = alreadyAsked)) return false
        prefs.edit().putBoolean(ASKED, true).commit()
        return true
    }

    fun settingsIntent(context: Context): Intent {
        val component = ComponentName(context, WhatsAppSendAccessibilityService::class.java)
            .flattenToString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val details = Intent(ACCESSIBILITY_DETAILS_SETTINGS).apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (details.resolveActivity(context.packageManager) != null) return details
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
