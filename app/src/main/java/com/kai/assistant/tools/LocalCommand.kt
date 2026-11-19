package com.kai.assistant.tools

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
    data class SetVolume(val percent: Int) : LocalCommand()
    data class AdjustVolume(val deltaSteps: Int) : LocalCommand()
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val message: String,
        val repeat: ReminderRepeat = ReminderRepeat.Once,
        val weekday: Int? = null,
    ) : LocalCommand()
    data class StartTimer(val seconds: Int, val message: String) : LocalCommand()
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
}

fun LocalCommand.staysOnDevice(): Boolean =
    this is LocalCommand.CallContact ||
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
        this is LocalCommand.SaveMemory ||
        this is LocalCommand.DeleteMemory ||
        this is LocalCommand.EditMemory ||
        this is LocalCommand.RecallMemory

fun LocalCommand.needsNotification(): Boolean =
    this is LocalCommand.StartTimer ||
        this is LocalCommand.SetReminder ||
        this is LocalCommand.SetReminders ||
        this is LocalCommand.ChangeReminder

enum class ReminderRepeat { Once, Daily, Weekdays, Weekly }

data class LocalMatch(
    val command: LocalCommand,
    val requiresConfirmation: Boolean,
    val confirmationPrompt: String,
    val spokenLabel: String,
)
