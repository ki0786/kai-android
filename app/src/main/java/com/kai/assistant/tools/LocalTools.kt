package com.kai.assistant.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.Settings
import android.widget.Toast
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Local phone-control via official Android APIs / intents.
 * Sensitive actions should confirm with the user before calling [execute].
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
    private val volume = VolumeTool(context)
    private val deviceInfo = DeviceInfoTool(context)
    private val calls = CallContactTool(context)

    fun contactsGranted(): Boolean = calls.hasContactsPermission()

    fun resolveCall(command: LocalCommand.CallContact): CallResolution = calls.resolve(command)

    fun execute(command: LocalCommand): ToolResult = when (command) {
        is LocalCommand.Flashlight -> flashlight.setEnabled(command.enabled)
        LocalCommand.BatteryStatus -> battery.status()
        LocalCommand.DateTime -> deviceInfo.dateTime()
        LocalCommand.Connectivity -> deviceInfo.connectivity()
        LocalCommand.OpenWifiSettings -> settings.openWifi()
        LocalCommand.OpenBluetoothSettings -> settings.openBluetooth()
        LocalCommand.OpenAppSettings -> settings.openAppDetails()
        is LocalCommand.LaunchAppByName -> launcher.launchByLabel(command.appName)
        is LocalCommand.SetVolume -> volume.setPercent(command.percent)
        is LocalCommand.AdjustVolume -> volume.adjustSteps(command.deltaSteps)
        is LocalCommand.SetAlarm -> alarms.setAlarm(command.hour, command.minute, command.message)
        is LocalCommand.StartTimer -> alarms.startTimer(command.seconds, command.message)
        is LocalCommand.OpenMaps -> openMaps(command.query)
        LocalCommand.OpenCamera -> openCamera()
        is LocalCommand.CallContact -> calls.place(command)
    }

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
    fun setVolume(percent: Int): ToolResult = volume.setPercent(percent)
    fun dateTime(): ToolResult = deviceInfo.dateTime()
    fun connectivity(): ToolResult = deviceInfo.connectivity()

    fun openMaps(query: String): ToolResult {
        val encoded = Uri.encode(query)
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val nav = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=$encoded"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching {
            when {
                nav.resolveActivity(context.packageManager) != null -> {
                    context.startActivity(nav)
                    ToolResult.Ok("Opening navigation to \"$query\".")
                }
                geo.resolveActivity(context.packageManager) != null -> {
                    context.startActivity(geo)
                    ToolResult.Ok("Opening maps for \"$query\".")
                }
                else -> ToolResult.Unsupported("No maps app available to open \"$query\".")
            }
        }.getOrElse { ToolResult.Error(it.message ?: "Could not open maps.") }
    }

    fun openCamera(): ToolResult {
        val capture = Intent("android.media.action.IMAGE_CAPTURE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val still = Intent("android.media.action.STILL_IMAGE_CAMERA")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            when {
                capture.resolveActivity(context.packageManager) != null -> {
                    context.startActivity(capture)
                    ToolResult.Ok("Opening camera.")
                }
                still.resolveActivity(context.packageManager) != null -> {
                    context.startActivity(still)
                    ToolResult.Ok("Opening camera.")
                }
                else -> ToolResult.Unsupported("No camera app available.")
            }
        }.getOrElse { ToolResult.Error(it.message ?: "Could not open camera.") }
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

    fun launchByLabel(labelQuery: String): ToolResult {
        val packageName = resolveByLabel(labelQuery)
            ?: return ToolResult.Error("I couldn't find an installed app named \"$labelQuery\".")
        return launch(packageName)
    }

    fun resolveByLabel(labelQuery: String): String? {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        val exact = apps.firstOrNull {
            it.loadLabel(pm).toString().equals(labelQuery, ignoreCase = true)
        }
        if (exact != null) return exact.activityInfo.packageName

        val partial = apps.firstOrNull {
            it.loadLabel(pm).toString().contains(labelQuery, ignoreCase = true)
        }
        return partial?.activityInfo?.packageName
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
                    "No clock app handles alarms/timers on this device.",
                )
            }
            context.startActivity(intent)
            ToolResult.Ok(okMessage)
        }.getOrElse { ToolResult.Error(it.message ?: "Clock intent failed.") }
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

class VolumeTool(private val context: Context) {
    private val audio: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun setPercent(percent: Int): ToolResult {
        val clamped = percent.coerceIn(0, 100)
        return runCatching {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val index = ((clamped / 100f) * max).toInt().coerceIn(0, max)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
            ToolResult.Ok("Volume set to $clamped%.")
        }.getOrElse { ToolResult.Error(it.message ?: "Could not set volume.") }
    }

    fun adjustSteps(deltaSteps: Int): ToolResult {
        return runCatching {
            val direction = when {
                deltaSteps > 0 -> AudioManager.ADJUST_RAISE
                deltaSteps < 0 -> AudioManager.ADJUST_LOWER
                else -> return ToolResult.Ok("Volume unchanged.")
            }
            repeat(kotlin.math.abs(deltaSteps)) {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    direction,
                    AudioManager.FLAG_SHOW_UI,
                )
            }
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            val percent = (current * 100) / max
            ToolResult.Ok("Volume now about $percent%.")
        }.getOrElse { ToolResult.Error(it.message ?: "Could not adjust volume.") }
    }
}

class DeviceInfoTool(private val context: Context) {
    fun dateTime(): ToolResult {
        val now = Date()
        val date = DateFormat.getDateInstance(DateFormat.FULL, Locale.getDefault()).format(now)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(now)
        return ToolResult.Ok("It's $time on $date.")
    }

    fun connectivity(): ToolResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
            ?: return ToolResult.Ok("You appear to be offline — no active network.")
        val caps = cm.getNetworkCapabilities(network)
            ?: return ToolResult.Ok("Network present but capabilities unavailable.")
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi‑Fi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val via = if (transports.isEmpty()) "unknown transport" else transports.joinToString(" + ")
        return ToolResult.Ok(
            if (online) "Online via $via." else "Network linked ($via) but internet not validated.",
        )
    }
}

fun ToolResult.messageText(): String = when (this) {
    is ToolResult.Ok -> message
    is ToolResult.NeedsConfirmation -> message
    is ToolResult.Unsupported -> message
    is ToolResult.Error -> message
}

fun ToolResult.toast(context: Context) {
    Toast.makeText(context, messageText(), Toast.LENGTH_SHORT).show()
}
