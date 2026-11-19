package com.kai.assistant.tools

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
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

    /** Chat is already open, or the screen can't be read. Ask once to turn a service on. */
    data class OpenAccessibilitySettings(
        val message: String,
        val target: AccessibilityTarget = AccessibilityTarget.WhatsAppSend,
    ) : ToolResult()

    /** The system screen-capture dialog. Asked on the first screenshot of a session. */
    data class NeedsScreenshotConsent(val message: String) : ToolResult()
}

enum class AccessibilityTarget { WhatsAppSend, ScreenReader }

class LocalToolRouter(private val context: Context) {
    private val flashlight = FlashlightTool(context)
    private val battery = BatteryTool(context)
    private val launcher = LaunchAppTool(context)
    private val alarms = AlarmTimerTool(context)
    private val settings = OpenSettingsTool(context)
    private val volume = VolumeTool(context)
    private val deviceInfo = DeviceInfoTool(context)
    private val calls = CallContactTool(context)
    private val whatsApp = WhatsAppTool(context)

    fun contactsGranted(): Boolean = calls.hasContactsPermission()

    fun callGranted(): Boolean = calls.hasCallPermission()

    fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun resolveCall(command: LocalCommand.CallContact): CallResolution = calls.resolve(command)

    fun resolveWhatsApp(command: LocalCommand.SendWhatsApp): WhatsAppResolution =
        whatsApp.resolve(command)

    fun resolveWhatsAppCall(command: LocalCommand.WhatsAppCall): WhatsAppCallResolution =
        whatsApp.resolveCall(command)

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
        is LocalCommand.SetAlarm -> alarms.setAlarm(
            command.hour,
            command.minute,
            command.message,
            command.repeat,
            command.weekday,
        )
        is LocalCommand.StartTimer -> alarms.startTimer(command.seconds, command.message)
        LocalCommand.TakeScreenshot -> takeScreenshot()
        is LocalCommand.SetReminder -> scheduleReminder(command)
        is LocalCommand.SetReminders -> scheduleReminders(command.items)
        is LocalCommand.CancelReminder -> ReminderScheduler(context).cancelMatching(command.query)
        is LocalCommand.ChangeReminder -> ReminderScheduler(context).changeClock(
            command.hour,
            command.minute,
            command.meridiem,
            command.newHour,
            command.newMinute,
            command.newMeridiem,
        )
        LocalCommand.DescribeScreen -> describeScreen()
        is LocalCommand.OpenMaps -> openMaps(command.query)
        LocalCommand.OpenCamera -> openCamera()
        is LocalCommand.CallContact -> calls.place(command)
        is LocalCommand.SendWhatsApp -> whatsApp.open(command)
        is LocalCommand.WhatsAppCall -> whatsApp.call(command)
    }

    private fun takeScreenshot(): ToolResult {
        if (ScreenshotCaptureService.ready) {
            ScreenshotCaptureService.captureAgain(context)
            return ToolResult.Ok("Saving a screenshot on this phone.")
        }
        return ToolResult.NeedsScreenshotConsent(
            "Allow screen capture when Android asks. I'll save the picture on this phone " +
                "and reuse that permission for later screenshots in this session.",
        )
    }

    private fun scheduleReminder(command: LocalCommand.SetReminder): ToolResult =
        ReminderScheduler(context).schedule(reminderSpec(command))

    private fun scheduleReminders(items: List<LocalCommand.SetReminder>): ToolResult {
        val scheduler = ReminderScheduler(context)
        val specs = items.map { reminderSpec(it) }
        specs.forEach { scheduler.schedule(it) }
        val summary = specs.joinToString(" and ") { spec ->
            "${spec.text} at ${clockLabel(spec.hour, spec.minute)}"
        }
        return ToolResult.Ok("I'll remind you to $summary. They stay on this phone.")
    }

    private fun reminderSpec(command: LocalCommand.SetReminder): ReminderSpec {
        val zone = java.time.ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val at = nextReminderMillis(
            nowMillis = now,
            zone = zone,
            hour = command.hour,
            minute = command.minute,
            meridiem = command.meridiem,
            dayOffset = command.dayOffset,
            inMinutes = command.inMinutes,
        )
        val hour = resolvedHour(command.hour, command.meridiem, at, zone)
        val minute = if (command.inMinutes != null) {
            java.time.Instant.ofEpochMilli(at).atZone(zone).minute
        } else {
            command.minute
        }
        return ReminderSpec(
            text = command.text,
            atMillis = at,
            hour = hour,
            minute = minute,
            repeat = command.repeat,
            weekday = command.weekday,
        )
    }

    private fun describeScreen(): ToolResult {
        if (!ScreenReaderAccess.isEnabled(context)) {
            val line = "I can't see this screen because accessibility is off."
            return if (ScreenReaderAccess.askOnce(context)) {
                ToolResult.OpenAccessibilitySettings(line, AccessibilityTarget.ScreenReader)
            } else {
                ToolResult.Ok(line)
            }
        }
        val service = ScreenReaderAccessibilityService.instance
            ?: return ToolResult.Ok("I can't see this screen because accessibility is off.")
        return ToolResult.Ok(service.describeNow())
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
    private companion object {
        const val BBK_CLOCK = "com.android.BBKClock"
    }

    fun setAlarm(
        hour: Int,
        minute: Int,
        message: String,
        repeat: ReminderRepeat = ReminderRepeat.Once,
        weekday: Int? = null,
    ): ToolResult {
        val spoken = alarmSpokenLine(hour, minute, repeat, weekday)
        val silent = trySilentClock(hour, minute, message, repeat, weekday)
        if (alarmSetPath(silent) == AlarmSetPath.SilentClock) {
            return ToolResult.Ok(spoken)
        }
        return LocalClockAlarmScheduler(context).schedule(hour, minute, message, repeat, weekday, spoken)
    }

    /**
     * Starts [AlarmClock.ACTION_SET_ALARM] only on an exported activity, with
     * [AlarmClock.EXTRA_SKIP_UI] so the clock saves the alarm without a tap.
     * Vivo's BBK Clock does not export that activity.
     */
    private fun trySilentClock(
        hour: Int,
        minute: Int,
        message: String,
        repeat: ReminderRepeat,
        weekday: Int?,
    ): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, message)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        alarmDays(repeat, weekday)?.let { intent.putExtra(AlarmClock.EXTRA_DAYS, it) }
        val pm = context.packageManager
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .filter { it.exported }
            .sortedBy { clockRank(it.packageName) }
        for (info in activities) {
            val explicit = Intent(intent).setComponent(ComponentName(info.packageName, info.name))
            if (startClock(explicit)) return true
        }
        return false
    }

    private fun clockRank(packageName: String): Int = when (packageName) {
        BBK_CLOCK -> 0
        "com.google.android.deskclock" -> 1
        "com.android.deskclock" -> 2
        else -> 3
    }

    private fun startClock(intent: Intent): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun alarmDays(repeat: ReminderRepeat, weekday: Int?): java.util.ArrayList<Int>? {
        val days = when (repeat) {
            ReminderRepeat.Once -> return null
            ReminderRepeat.Daily -> listOf(
                java.util.Calendar.SUNDAY,
                java.util.Calendar.MONDAY,
                java.util.Calendar.TUESDAY,
                java.util.Calendar.WEDNESDAY,
                java.util.Calendar.THURSDAY,
                java.util.Calendar.FRIDAY,
                java.util.Calendar.SATURDAY,
            )
            ReminderRepeat.Weekdays -> listOf(
                java.util.Calendar.MONDAY,
                java.util.Calendar.TUESDAY,
                java.util.Calendar.WEDNESDAY,
                java.util.Calendar.THURSDAY,
                java.util.Calendar.FRIDAY,
            )
            ReminderRepeat.Weekly -> listOf(isoWeekdayToCalendar(weekday ?: java.time.DayOfWeek.MONDAY.value))
        }
        return java.util.ArrayList(days)
    }

    /** ISO Monday=1 … Sunday=7 to [java.util.Calendar] day constants. */
    private fun isoWeekdayToCalendar(iso: Int): Int = if (iso == 7) {
        java.util.Calendar.SUNDAY
    } else {
        iso + 1
    }

    fun startTimer(seconds: Int, message: String): ToolResult {
        return TimerScheduler(context).start(seconds)
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
    is ToolResult.OpenAccessibilitySettings -> message
    is ToolResult.NeedsScreenshotConsent -> message
}

fun ToolResult.toast(context: Context) {
    Toast.makeText(context, messageText(), Toast.LENGTH_SHORT).show()
}
