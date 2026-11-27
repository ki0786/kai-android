package com.kai.assistant.tools

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Starts an activity while KAI is in the background. Android 10 blocks that
 * from a service unless the app has a visible window, so a one-pixel overlay
 * is attached first and removed after the start. This does not bring KAI forward.
 */
internal object BackgroundActivityStart {
    fun start(context: Context, intent: Intent) {
        val app = context.applicationContext
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val main = Handler(Looper.getMainLooper())
        val overlay = attachedOverlay(app)
        val launch = Runnable {
            runCatching { app.startActivity(intent) }
            if (overlay != null) {
                main.postDelayed({
                    runCatching { overlay.first.removeView(overlay.second) }
                }, 900)
            }
        }
        if (overlay != null) {
            main.postDelayed(launch, 160)
        } else if (Looper.myLooper() == Looper.getMainLooper()) {
            launch.run()
        } else {
            main.post(launch)
        }
    }

    private fun attachedOverlay(context: Context): Pair<WindowManager, View>? {
        if (!Settings.canDrawOverlays(context)) return null
        val wm = context.getSystemService(WindowManager::class.java) ?: return null
        val view = View(context)
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.01f
        }
        val added = runCatching { wm.addView(view, params) }.isSuccess
        return if (added) wm to view else null
    }
}
