package com.kai.assistant.tools

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast

/**
 * Local phone-control stubs. Clear intents run where safe; others are TODO-marked.
 * Sensitive / irreversible actions should confirm with the user before calling these.
 */
sealed class ToolResult {
    data class Ok(val message: String) : ToolResult()
    data class NeedsConfirmation(val message: String) : ToolResult()
    data class Unsupported(val message: String) : ToolResult()
    data class Error(val message: String) : ToolResult()
}

class LocalToolRouter(private val context: Context) {
    private val flashlight = FlashlightTool(context)
    private val battery = BatteryTool(context)
    private val launcher = LaunchAppTool(context)
    private val alarms = AlarmTimerTool(context)
    private val settings = OpenSettingsTool(context)

    fun flashlightOn(): ToolResult = flashlight.setEnabled(true)
    fun flashlightOff(): ToolResult = flashlight.setEnabled(false)
    fun batteryStatus(): ToolResult = battery.status()
    fun launchApp(packageName: String): ToolResult = launcher.launch(packageName)
    fun setAlarm(hour: Int, minute: Int, message: String): ToolResult =
        alarms.setAlarm(hour, minute, message)
    fun startTimer(seconds: Int, message: String): ToolResult =
        alarms.startTimer(seconds, message)
    fun openWifiSettings(): ToolResult = settings.openWifi()
    fun openBluetoothSettings(): ToolResult = settings.openBluetooth()
    fun openAppSettings(): ToolResult = settings.openAppDetails()

    // --- stubs with clear TODOs ---

    fun setVolume(percent: Int): ToolResult = ToolResult.Unsupported(
        "TODO: Wire AudioManager volume control (percent=$percent) with user confirmation.",
    )

    fun setBrightness(percent: Int): ToolResult = ToolResult.Unsupported(
        "TODO: Wire WRITE_SETTINGS brightness (percent=$percent); requires special permission UX.",
    )

    fun openMaps(query: String): ToolResult {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.Ok("Opening maps for \"$query\".")
        }.getOrElse { ToolResult.Error(it.message ?: "Could not open maps.") }
    }

    fun openCamera(): ToolResult {
        val intent = Intent("android.media.action.IMAGE_CAPTURE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.Ok("Opening camera.")
        }.getOrElse {
            ToolResult.Unsupported("TODO: Camera capture pipeline with permission flow.")
        }
    }
}

class FlashlightTool(private val context: Context) {
    fun setEnabled(enabled: Boolean): ToolResult {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return runCatching {
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ToolResult.Unsupported("No flashlight hardware available.")
            cameraManager.setTorchMode(cameraId, enabled)
            ToolResult.Ok(if (enabled) "Flashlight on." else "Flashlight off.")
        }.getOrElse { ToolResult.Error(it.message ?: "Flashlight failed.") }
    }
}

class BatteryTool(private val context: Context) {
    fun status(): ToolResult {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ToolResult.Error("Battery status unavailable.")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "unplugged"
        }
        return ToolResult.Ok("Battery $percent% · ${if (charging) "charging" else "not charging"} · $plug")
    }
}

class LaunchAppTool(private val context: Context) {
    fun launch(packageName: String): ToolResult {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage(packageName)
            ?: return ToolResult.Error("App not installed: $packageName")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launch)
            ToolResult.Ok("Launching $packageName")
        }.getOrElse { ToolResult.Error(it.message ?: "Launch failed.") }
    }

    fun resolveByLabel(labelQuery: String): String? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
            .firstOrNull { it.loadLabel(pm).toString().equals(labelQuery, ignoreCase = true) }
            ?.activityInfo
            ?.packageName
    }
}

class AlarmTimerTool(private val context: Context) {
    fun setAlarm(hour: Int, minute: Int, message: String): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startClockIntent(intent, "Opening alarm UI for $hour:${"%02d".format(minute)}.")
    }

    fun startTimer(seconds: Int, message: String): ToolResult {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startClockIntent(intent, "Opening timer UI for ${seconds}s.")
    }

    private fun startClockIntent(intent: Intent, okMessage: String): ToolResult {
        return runCatching {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.Unsupported(
                    "TODO: No AlarmClock handler; consider in-app AlarmManager fallback " +
                        "(needs SCHEDULE_EXACT_ALARM on newer Android).",
                )
            }
            context.startActivity(intent)
            ToolResult.Ok(okMessage)
        }.getOrElse { ToolResult.Error(it.message ?: "Clock intent failed.") }
    }

    fun canScheduleExactAlarms(): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
    }
}

class OpenSettingsTool(private val context: Context) {
    fun openWifi(): ToolResult = open(Settings.ACTION_WIFI_SETTINGS, "Wi‑Fi settings")
    fun openBluetooth(): ToolResult = open(Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings")
    fun openAppDetails(): ToolResult {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.Ok("Opened KAI app settings (permissions).")
        }.getOrElse { ToolResult.Error(it.message ?: "Could not open settings.") }
    }

    private fun open(action: String, label: String): ToolResult {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.Ok("Opened $label.")
        }.getOrElse { ToolResult.Error(it.message ?: "Could not open $label.") }
    }
}

fun ToolResult.toast(context: Context) {
    val text = when (this) {
        is ToolResult.Ok -> message
        is ToolResult.NeedsConfirmation -> message
        is ToolResult.Unsupported -> message
        is ToolResult.Error -> message
    }
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}
