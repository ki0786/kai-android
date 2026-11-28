package com.kai.assistant.tools

import com.kai.assistant.books.BooksAction

/**
 * Device actions the local engine can run without a cloud round-trip.
 */
sealed class LocalCommand {
    data class Flashlight(val enabled: Boolean) : LocalCommand()
    data object BatteryStatus : LocalCommand()
    data object DateTime : LocalCommand()
    data object Connectivity : LocalCommand()
    data object OpenWifiSettings : LocalCommand()
    data object OpenBluetoothSettings : LocalCommand()
    data object OpenAppSettings : LocalCommand()
    data class LaunchAppByName(val appName: String) : LocalCommand()

    data class SearchYouTube(val query: String) : LocalCommand()
    data class SetVolume(val percent: Int) : LocalCommand()
    data class AdjustVolume(val deltaSteps: Int) : LocalCommand()
    /** Ringer silent. Not a media-volume change and not a web question. */
    data object SilencePhone : LocalCommand()
    data class SetDoNotDisturb(val enabled: Boolean) : LocalCommand()
    /** [up] raises the screen brightness one step. */
    data class AdjustBrightness(val up: Boolean) : LocalCommand()
    /** Screen brightness as a percent of the system range. Turns automatic mode off. */
    data class SetBrightness(val percent: Int) : LocalCommand()
    data class SetWifi(val enabled: Boolean) : LocalCommand()
    data class SetBluetooth(val enabled: Boolean) : LocalCommand()
    data class MediaControl(val action: MediaAction) : LocalCommand()
    data object ReadLatestNotification : LocalCommand()
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val message: String,
        val repeat: ReminderRepeat = ReminderRepeat.Once,
        val weekday: Int? = null,
        val dayOffset: Int = 0,
        /** "Set an alarm for 7" with no a.m. or p.m.: ring at the next 7 o'clock. */
        val flexible: Boolean = false,
    ) : LocalCommand()
    data class StartTimer(val seconds: Int, val message: String) : LocalCommand()
    data object CancelTimer : LocalCommand()
    data class OpenSystemSettings(val section: SettingsSection) : LocalCommand()

    /**
     * Opens the SMS app with [message] ready for [target]. Sending without a tap needs
     * SEND_SMS, which Google Play limits to default SMS apps.
     */
    data class SendSms(
        val options: List<WhatsAppUtterance.Option>,
        val resolvedNumber: String? = null,
        val resolvedName: String? = null,
        val resolvedMessage: String? = null,
    ) : LocalCommand() {
        val nameQuery: String
            get() = options.firstOrNull()?.nameQuery.orEmpty()
        val rawNumber: Boolean
            get() = options.size == 1 && looksLikePhoneNumber(nameQuery)
    }
    data object TakeScreenshot : LocalCommand()
    data class SetReminder(
        val text: String,
        val hour: Int,
        val minute: Int,
        val meridiem: String?,
        val repeat: ReminderRepeat = ReminderRepeat.Once,
        val weekday: Int? = null,
        val inMinutes: Int? = null,
        val dayOffset: Int = 0,
    ) : LocalCommand()

    data class SetReminders(val items: List<SetReminder>) : LocalCommand()

    data class CancelReminder(val query: String) : LocalCommand()

    data class ChangeReminder(
        val hour: Int,
        val minute: Int,
        val meridiem: String?,
        val newHour: Int,
        val newMinute: Int,
        val newMeridiem: String?,
    ) : LocalCommand()
    data object DescribeScreen : LocalCommand()

    /** [useBackend] is true only when the user asked to send the photo off the phone. */
    data class DescribePhoto(val useBackend: Boolean) : LocalCommand()
    data class OpenMaps(val query: String) : LocalCommand()
    data object OpenCamera : LocalCommand()

    /**
     * Look up [query] in on-device contacts and hand the number to the phone app.
     * [rawNumber] skips the lookup and dials [query] (or [resolvedNumber]) directly.
     * The resolved number is fixed before confirmation so dialing does not search again.
     */
    data class CallContact(
        val query: String,
        val phoneType: PhoneKind? = null,
        val rawNumber: Boolean = false,
        val resolvedNumber: String? = null,
        val resolvedName: String? = null,
    ) : LocalCommand()

    /**
     * Hand [resolvedMessage] to WhatsApp for one on-device contact.
     * [options] is longest contact name first.
     */
    data class SendWhatsApp(
        val options: List<WhatsAppUtterance.Option>,
        val resolvedNumber: String? = null,
        val resolvedName: String? = null,
        val resolvedMessage: String? = null,
    ) : LocalCommand() {
        val nameQuery: String
            get() = options.firstOrNull()?.nameQuery.orEmpty()
    }

    data class SaveMemory(val text: String, val project: Boolean) : LocalCommand()

    data class DeleteMemory(val query: String, val all: Boolean = false) : LocalCommand()

    data class EditMemory(val query: String, val text: String) : LocalCommand()

    data class RecallMemory(
        val mode: RecallMode,
        val query: String = "",
    ) : LocalCommand()

    data class RunPlan(val steps: List<PlanStep>) : LocalCommand()

    data class AddCalendarEvent(
        val title: String,
        val dayOffset: Int,
        val hour: Int,
        val minute: Int,
        val meridiem: String?,
    ) : LocalCommand()

    data class ReadSchedule(val dayOffset: Int) : LocalCommand()

    data class SaveNote(val text: String) : LocalCommand()

    data object DailyBriefing : LocalCommand()

    /**
     * Start a WhatsApp voice or video call to one on-device contact.
     * [video] is false for a voice call.
     */
    data class WhatsAppCall(
        val nameQuery: String,
        val video: Boolean,
        val resolvedContactId: Long? = null,
        val resolvedNumber: String? = null,
        val resolvedName: String? = null,
    ) : LocalCommand()

    /** A business-books entry or summary. Kept on the phone. Does not pay or message anyone. */
    data class Books(val action: BooksAction) : LocalCommand()
}

enum class SettingsSection(val label: String) {
    Main("settings"),
    MobileData("mobile data settings"),
    Airplane("airplane mode settings"),
    Hotspot("hotspot settings"),
    Location("location settings"),
    DoNotDisturb("Do Not Disturb settings"),
    Display("display settings"),
    Sound("sound settings"),
    Battery("battery settings"),
}

fun LocalCommand.staysOnDevice(): Boolean =
    this is LocalCommand.CallContact ||
        this is LocalCommand.SendSms ||
        this is LocalCommand.CancelTimer ||
        this is LocalCommand.OpenSystemSettings ||
        this is LocalCommand.Flashlight ||
        this is LocalCommand.BatteryStatus ||
        this is LocalCommand.DateTime ||
        this is LocalCommand.Connectivity ||
        this is LocalCommand.OpenWifiSettings ||
        this is LocalCommand.OpenBluetoothSettings ||
        this is LocalCommand.OpenAppSettings ||
        this is LocalCommand.SetVolume ||
        this is LocalCommand.AdjustVolume ||
        this is LocalCommand.SilencePhone ||
        this is LocalCommand.SetDoNotDisturb ||
        this is LocalCommand.AdjustBrightness ||
        this is LocalCommand.SetBrightness ||
        this is LocalCommand.SetWifi ||
        this is LocalCommand.SetBluetooth ||
        this is LocalCommand.MediaControl ||
        this is LocalCommand.ReadLatestNotification ||
        this is LocalCommand.SendWhatsApp ||
        this is LocalCommand.WhatsAppCall ||
        this is LocalCommand.SetAlarm ||
        this is LocalCommand.StartTimer ||
        this is LocalCommand.TakeScreenshot ||
        this is LocalCommand.SetReminder ||
        this is LocalCommand.SetReminders ||
        this is LocalCommand.CancelReminder ||
        this is LocalCommand.ChangeReminder ||
        this is LocalCommand.DescribeScreen ||
        this is LocalCommand.DescribePhoto ||
        this is LocalCommand.SaveMemory ||
        this is LocalCommand.DeleteMemory ||
        this is LocalCommand.EditMemory ||
        this is LocalCommand.RecallMemory ||
        this is LocalCommand.RunPlan ||
        this is LocalCommand.AddCalendarEvent ||
        this is LocalCommand.ReadSchedule ||
        this is LocalCommand.SaveNote ||
        this is LocalCommand.DailyBriefing ||
        this is LocalCommand.LaunchAppByName ||
        this is LocalCommand.SearchYouTube ||
        this is LocalCommand.OpenMaps ||
        this is LocalCommand.OpenCamera ||
        this is LocalCommand.Books

fun LocalCommand.needsNotification(): Boolean = when (this) {
    is LocalCommand.StartTimer,
    is LocalCommand.SetReminder,
    is LocalCommand.SetReminders,
    is LocalCommand.ChangeReminder,
    -> true
    is LocalCommand.RunPlan -> steps.any { it.command.needsNotification() }
    else -> false
}

fun LocalCommand.needsCalendar(): Boolean =
    this is LocalCommand.AddCalendarEvent ||
        this is LocalCommand.ReadSchedule ||
        this is LocalCommand.DailyBriefing ||
        this is LocalCommand.RunPlan && steps.any { it.command.needsCalendar() }

enum class ReminderRepeat { Once, Daily, Weekdays, Weekly }

data class LocalMatch(
    val command: LocalCommand,
    val requiresConfirmation: Boolean,
    val confirmationPrompt: String,
    val spokenLabel: String,
)
