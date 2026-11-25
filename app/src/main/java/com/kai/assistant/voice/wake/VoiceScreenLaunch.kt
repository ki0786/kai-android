package com.kai.assistant.voice.wake

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.kai.assistant.MainActivity

/**
 * Brings the voice screen forward after Hey KAI is spotted.
 * Android 10 and newer block [Context.startActivity] from the wake service
 * unless the app may draw over other apps, so the launch also posts a
 * full-screen notification the system is allowed to deliver.
 */
internal object VoiceScreenLaunch {
    private const val POPUP_OP = 10021
    private var screenWake: PowerManager.WakeLock? = null

    fun open(context: Context) {
        val app = context.applicationContext
        val intent = voiceIntent(app)
        // The system may deliver this when a direct start is blocked. The voice
        // screen cancels it, so it does not replace the listening notification.
        WakeNotifications.postVoiceScreen(app, intent)
        val launch = Runnable { showScreen(app, intent) }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            launch.run()
        } else {
            Handler(Looper.getMainLooper()).post(launch)
        }
    }

    fun needsGrant(context: Context): Boolean = needsVoiceOverAppsGrant(
        sdkInt = Build.VERSION.SDK_INT,
        canDrawOverlays = Settings.canDrawOverlays(context),
        manufacturer = Build.MANUFACTURER,
        vivoPopupMode = vivoPopupMode(context),
    )

    fun openSettings(context: Context) {
        if (Build.MANUFACTURER.equals("vivo", ignoreCase = true)) {
            val vivo = Intent().setComponent(
                ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity",
                ),
            ).putExtra("packagename", context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(vivo) }.isSuccess) return
        }
        val overlay = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(overlay) }
    }

    private fun voiceIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            addFlags(VOICE_SCREEN_FLAGS)
            putExtra(MainActivity.EXTRA_OPEN_VOICE, true)
            putExtra(MainActivity.EXTRA_AUTO_LISTEN, System.nanoTime())
        }

    private fun showScreen(context: Context, intent: Intent) {
        wakeScreen(context)
        val overlay = attachedOverlay(context)
        val start = Runnable { runCatching { context.startActivity(intent) } }
        val handler = Handler(Looper.getMainLooper())
        if (overlay != null) {
            // The window has to be attached before the start, or the system
            // still treats the wake service as background.
            handler.postDelayed(start, 150)
            handler.postDelayed({
                runCatching { overlay.first.removeView(overlay.second) }
            }, 1_200)
        } else {
            start.run()
        }
    }

    private fun wakeScreen(context: Context) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        @Suppress("DEPRECATION")
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "kai:hey-kai-screen",
        )
        val previous = screenWake
        screenWake = lock
        if (previous?.isHeld == true) previous.release()
        lock.acquire(3_000L)
    }

    /**
     * A tiny overlay counts as a visible window, which is the other way Android
     * allows a background start. Touches pass through it, and it is removed
     * once the voice screen has been asked to open.
     */
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

    private fun vivoPopupMode(context: Context): Int? {
        if (!Build.MANUFACTURER.equals("vivo", ignoreCase = true)) return null
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
        return try {
            val intClass = Int::class.javaPrimitiveType ?: return null
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                intClass,
                intClass,
                String::class.java,
            )
            method.invoke(ops, POPUP_OP, Process.myUid(), context.packageName) as? Int
        } catch (_: Throwable) {
            null
        }
    }
}
