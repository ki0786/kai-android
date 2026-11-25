package com.kai.assistant.voice.wake

import android.content.Context

/** Fast mirror of the DataStore toggle so boot can read it without starting DataStore. */
internal object WakePrefs {
    private const val FILE = "kai_wake"
    private const val ENABLED = "enabled"
    private const val SENSITIVITY = "sensitivity"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED, enabled)
            .apply()
    }

    fun sensitivity(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(SENSITIVITY, 0.5f)
            .coerceIn(0f, 1f)

    fun setSensitivity(context: Context, sensitivity: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putFloat(SENSITIVITY, sensitivity.coerceIn(0f, 1f))
            .apply()
    }
}
