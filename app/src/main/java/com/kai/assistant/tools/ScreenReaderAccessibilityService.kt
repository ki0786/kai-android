package com.kai.assistant.tools

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the current screen only when the user asks what is on it.
 * It does not tap, and it is separate from the WhatsApp send tap.
 */
@Suppress("DEPRECATION")
class ScreenReaderAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun describeNow(): String {
        val window = preferredWindow()
        val root = window?.root ?: rootInActiveWindow
        if (root == null) return "I can't read a window right now."
        return try {
            val items = ArrayList<ScreenItem>()
            collect(root, items, depth = 0)
            describeScreen(appLabel(root.packageName?.toString()), items)
        } finally {
            root.recycle()
        }
    }

    private fun preferredWindow(): AccessibilityWindowInfo? {
        val windows = windows ?: return null
        var chosen: AccessibilityWindowInfo? = null
        for (window in windows) {
            val root = window.root ?: continue
            val pkg = root.packageName?.toString()
            val match = chosen == null &&
                window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                pkg != null &&
                pkg != packageName
            root.recycle()
            if (match) chosen = window
        }
        return chosen
    }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<ScreenItem>, depth: Int) {
        if (depth > 12 || out.size >= 80) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()
            val description = node.contentDescription?.toString()
            if (!text.isNullOrBlank() || !description.isNullOrBlank()) {
                out.add(
                    ScreenItem(
                        text = text,
                        contentDescription = description,
                        clickable = node.isClickable,
                        editable = node.isEditable,
                    ),
                )
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun appLabel(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    companion object {
        @Volatile
        var instance: ScreenReaderAccessibilityService? = null
            private set
    }
}

object ScreenReaderAccess {
    private const val PREFS = "kai_screen_reader"
    private const val ASKED = "access_asked"
    private const val DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    fun isEnabled(context: Context): Boolean {
        val component = ComponentName(context, ScreenReaderAccessibilityService::class.java)
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

    fun askOnce(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(ASKED, false)) return false
        prefs.edit().putBoolean(ASKED, true).commit()
        return true
    }

    fun settingsIntent(context: Context): Intent {
        val component = ComponentName(context, ScreenReaderAccessibilityService::class.java)
            .flattenToString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val details = Intent(DETAILS).apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (details.resolveActivity(context.packageManager) != null) return details
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
