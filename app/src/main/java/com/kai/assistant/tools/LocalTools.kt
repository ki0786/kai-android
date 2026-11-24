package com.kai.assistant.tools

import android.Manifest
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

    /** The user still has to pick a photo. The image is not uploaded. */
    data class NeedsPhoto(val message: String, val useBackend: Boolean) : ToolResult()
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
    private val memory = PersonalMemoryStore(context)
    private val calendar = CalendarTool(context)
    private val notes = PhoneNoteStore(context)
    private val briefing = DailyBriefingTool(context)

    fun contactsGranted(): Boolean = calls.hasContactsPermission()

    fun callGranted(): Boolean = calls.hasCallPermission()

    fun calendarGranted(): Boolean = calendar.granted()

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

    fun resolveSms(command: LocalCommand.SendSms): WhatsAppResolution {
        if (command.rawNumber) {
            val option = command.options.single()
            return WhatsAppResolution.Ready(option.nameQuery, option.nameQuery, option.message, contactId = -1)
        }
        return WhatsAppResolver.resolve(command.options, calls.allPhones(), app = "Messages")
    }

    fun execute(command: LocalCommand): ToolResult = when (command) {
        is LocalCommand.Flashlight -> flashlight.setEnabled(command.enabled)
        LocalCommand.BatteryStatus -> battery.status()
        LocalCommand.DateTime -> deviceInfo.dateTime()
        LocalCommand.Connectivity -> deviceInfo.connectivity()
        LocalCommand.OpenWifiSettings -> settings.openWifi()
        LocalCommand.OpenBluetoothSettings -> settings.openBluetooth()
        LocalCommand.OpenAppSettings -> settings.openAppDetails()
        is LocalCommand.LaunchAppByName -> launcher.launchByLabel(command.appName)
        is LocalCommand.SearchYouTube -> launcher.searchYouTube(command.query)
        is LocalCommand.SetVolume -> volume.setPercent(command.percent)
        is LocalCommand.AdjustVolume -> volume.adjustSteps(command.deltaSteps)
        is LocalCommand.SetAlarm -> alarms.setAlarm(
            command.hour,
            command.minute,
            command.message,
            command.repeat,
            command.weekday,
            command.dayOffset,
            command.flexible,
        )
        is LocalCommand.StartTimer -> alarms.startTimer(command.seconds, command.message)
        LocalCommand.CancelTimer -> TimerScheduler(context).cancel()
        is LocalCommand.OpenSystemSettings -> settings.openSection(command.section)
        is LocalCommand.SendSms -> openSms(command)
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
        is LocalCommand.DescribePhoto -> describePhoto(command)
        is LocalCommand.OpenMaps -> openMaps(command.query)
        LocalCommand.OpenCamera -> openCamera()
        is LocalCommand.CallContact -> calls.place(command)
        is LocalCommand.SendWhatsApp -> whatsApp.open(command)
        is LocalCommand.WhatsAppCall -> whatsApp.call(command)
        is LocalCommand.SaveMemory -> saveMemory(command)
        is LocalCommand.DeleteMemory -> deleteMemory(command)
        is LocalCommand.EditMemory -> editMemory(command)
        is LocalCommand.RecallMemory -> recallMemory(command)
        is LocalCommand.RunPlan -> ToolResult.Unsupported("That plan is run by the assistant.")
        is LocalCommand.AddCalendarEvent -> calendar.addEvent(
            command.title,
            command.dayOffset,
            command.hour,
            command.minute,
            command.meridiem,
        )
        is LocalCommand.ReadSchedule -> calendar.readSchedule(command.dayOffset)
        is LocalCommand.SaveNote -> saveNote(command)
        LocalCommand.DailyBriefing -> briefing.speak()
    }

    private fun openSms(command: LocalCommand.SendSms): ToolResult {
        val number = command.resolvedNumber
            ?: return ToolResult.Error("I need a contact or number to text.")
        val name = command.resolvedName ?: number
        val message = command.resolvedMessage ?: command.options.firstOrNull()?.message.orEmpty()
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(ContactMatcher.normalizeNumber(number))))
            .putExtra("sms_body", message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolResult.Unsupported("There's no SMS app on this phone.")
            }
            context.startActivity(intent)
            ToolResult.Ok("Opened Messages to $name with your text. Tap send.")
        }.getOrElse { ToolResult.Error("I couldn't open Messages.") }
    }

    private fun saveNote(command: LocalCommand.SaveNote): ToolResult {
        notes.add(command.text)
        return ToolResult.Ok("Noted: ${command.text}. Saved on this phone.")
    }

    fun memoryStore(): PersonalMemoryStore = memory

    private fun saveMemory(command: LocalCommand.SaveMemory): ToolResult {
        if (!memory.writesEnabled) return ToolResult.Ok(formatMemoryOffReply())
        val kind = if (command.project) MemoryKind.Project else MemoryKind.Fact
        memory.save(command.text, kind) ?: return ToolResult.Ok(formatMemoryOffReply())
        return ToolResult.Ok(formatRememberReply(command.text, command.project))
    }

    private fun deleteMemory(command: LocalCommand.DeleteMemory): ToolResult {
        if (command.all) {
            val count = memory.deleteAll()
            return ToolResult.Ok(formatDeleteReply(command.query, removed = null, all = true, count = count))
        }
        val removed = memory.deleteMatching(command.query)
        return ToolResult.Ok(formatDeleteReply(command.query, removed, all = false, count = if (removed == null) 0 else 1))
    }

    private fun editMemory(command: LocalCommand.EditMemory): ToolResult {
        val updated = memory.editMatching(command.query, command.text)
        return ToolResult.Ok(formatEditReply(command.query, updated))
    }

    private fun recallMemory(command: LocalCommand.RecallMemory): ToolResult {
        val reply = when (command.mode) {
            RecallMode.List -> formatListReply(memory.all())
            RecallMode.Projects -> formatProjectsReply(memory.all())
            RecallMode.Recent -> formatRecentReply(memory.recentTurns())
            RecallMode.Search -> formatSearchReply(command.query, memory.all(), memory.recentTurns())
        }
        return ToolResult.Ok(reply)
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

    private fun describePhoto(command: LocalCommand.DescribePhoto): ToolResult =
        ToolResult.NeedsPhoto(
            message = "Pick a photo. I'll describe it on this phone and I won't upload it.",
            useBackend = command.useBackend,
        )

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
            .map { AppLabel(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        return pickApp(labelQuery, apps)
    }

    fun searchYouTube(query: String): ToolResult {
        val uri = Uri.parse(youtubeResultsUrl(query))
        val youtube = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(YOUTUBE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val view = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            if (youtube.resolveActivity(context.packageManager) != null) {
                context.startActivity(youtube)
            } else {
                context.startActivity(view)
            }
            ToolResult.Ok("Searching YouTube for $query.")
        }.getOrElse {
            ToolResult.Error("I couldn't open YouTube.")
        }
    }

    private companion object {
        const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    }
}

data class AppLabel(val label: String, val packageName: String)

private val appAliases = mapOf(
    "insta" to listOf("instagram"),
    "ig" to listOf("instagram"),
    "fb" to listOf("facebook"),
    "yt" to listOf("youtube"),
    "gpay" to listOf("google pay", "gpay"),
    "google pay" to listOf("google pay", "gpay"),
    "phone pe" to listOf("phonepe"),
    "paytm" to listOf("paytm"),
    "whatsapp business" to listOf("wa business", "whatsapp business"),
    "gmail" to listOf("gmail"),
    "mail" to listOf("gmail", "email"),
    "email" to listOf("gmail", "email"),
    "maps" to listOf("maps", "google maps"),
    "google maps" to listOf("maps"),
    "play store" to listOf("play store", "google play store"),
    "playstore" to listOf("play store"),
    "dialer" to listOf("phone"),
    "phone" to listOf("phone", "dialer"),
    "messages" to listOf("messages", "messaging"),
    "sms" to listOf("messages", "messaging"),
    "gallery" to listOf("gallery", "photos"),
    "photos" to listOf("photos", "gallery"),
    "files" to listOf("files", "file manager", "my files"),
    "clock" to listOf("clock"),
    "calculator" to listOf("calculator"),
    "chrome" to listOf("chrome"),
    "browser" to listOf("chrome", "browser", "internet"),
    "music" to listOf("music", "spotify", "youtube music"),
    "x" to listOf("x", "twitter"),
    "twitter" to listOf("x", "twitter"),
)

/**
 * Exact label, then an alias, then a label that starts with or contains the words,
 * then the closest-sounding label. "Insta", "whats app" and "spotfy" all open.
 */
internal fun pickApp(labelQuery: String, apps: List<AppLabel>): String? {
    val query = ContactMatcher.normalizeName(labelQuery)
    if (query.isEmpty() || apps.isEmpty()) return null
    val compact = query.replace(" ", "")
    val labelled = apps.map { it to ContactMatcher.normalizeName(it.label) }
    labelled.firstOrNull { it.second == query || it.second.replace(" ", "") == compact }?.let { return it.first.packageName }
    appAliases[query]?.forEach { alias ->
        labelled.firstOrNull { it.second == alias }?.let { return it.first.packageName }
    }
    labelled.filter { it.second.startsWith(query) }.minByOrNull { it.second.length }?.let { return it.first.packageName }
    if (query.length >= 3) {
        labelled.filter { Regex("""\b${Regex.escape(query)}\b""").containsMatchIn(it.second) }
            .minByOrNull { it.second.length }
            ?.let { return it.first.packageName }
    }
    val best = labelled.maxByOrNull { ContactMatcher.nameScore(query, it.first.label) } ?: return null
    return if (ContactMatcher.nameScore(query, best.first.label) >= 0.8) best.first.packageName else null
}

class AlarmTimerTool(private val context: Context) {
    fun setAlarm(
        hour: Int,
        minute: Int,
        message: String,
        repeat: ReminderRepeat = ReminderRepeat.Once,
        weekday: Int? = null,
        dayOffset: Int = 0,
        flexible: Boolean = false,
    ): ToolResult {
        val zone = java.time.ZoneId.systemDefault()
        val hour24 = if (flexible && repeat == ReminderRepeat.Once) {
            val at = nextReminderMillis(System.currentTimeMillis(), zone, hour, minute, null, dayOffset)
            java.time.Instant.ofEpochMilli(at).atZone(zone).hour
        } else {
            hour
        }
        val spoken = alarmSpokenLine(hour24, minute, repeat, weekday) +
            if (dayOffset == 1 && repeat == ReminderRepeat.Once) " tomorrow" else ""
        return LocalClockAlarmScheduler(context).schedule(hour24, minute, message, repeat, weekday, spoken, dayOffset)
    }

    fun startTimer(seconds: Int, message: String): ToolResult {
        return TimerScheduler(context).start(seconds)
    }
}

class OpenSettingsTool(private val context: Context) {
    /** Android 10+ blocks apps from switching Wi‑Fi themselves; the quick panel is one tap. */
    fun openWifi(): ToolResult =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            open(Settings.Panel.ACTION_WIFI, "the Wi‑Fi panel", "Android only lets you flip Wi‑Fi yourself, so tap the switch.")
        } else {
            open(Settings.ACTION_WIFI_SETTINGS, "Wi‑Fi settings")
        }

    fun openBluetooth(): ToolResult = open(
        Settings.ACTION_BLUETOOTH_SETTINGS,
        "Bluetooth settings",
        "Android only lets you flip Bluetooth yourself, so tap the switch.",
    )

    fun openSection(section: SettingsSection): ToolResult {
        val action = when (section) {
            SettingsSection.Main -> Settings.ACTION_SETTINGS
            SettingsSection.MobileData ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                else Settings.ACTION_DATA_ROAMING_SETTINGS
            SettingsSection.Airplane -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsSection.Hotspot -> Settings.ACTION_WIRELESS_SETTINGS
            SettingsSection.Location -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            SettingsSection.DoNotDisturb -> Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS
            SettingsSection.Display -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsSection.Sound -> Settings.ACTION_SOUND_SETTINGS
            SettingsSection.Battery -> Settings.ACTION_BATTERY_SAVER_SETTINGS
        }
        val note = if (section == SettingsSection.Main) "" else "Android doesn't let apps change this, so tap the switch."
        val opened = open(action, section.label, note)
        if (opened !is ToolResult.Error || section == SettingsSection.Main) return opened
        return open(Settings.ACTION_SETTINGS, "settings", "I couldn't open ${section.label} directly.")
    }
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

    private fun open(action: String, label: String, note: String = ""): ToolResult {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.Ok(listOf("Opened $label.", note).filter { it.isNotBlank() }.joinToString(" "))
        }.getOrElse { ToolResult.Error("Could not open $label.") }
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
    is ToolResult.NeedsPhoto -> message
}

fun ToolResult.toast(context: Context) {
    Toast.makeText(context, messageText(), Toast.LENGTH_SHORT).show()
}
