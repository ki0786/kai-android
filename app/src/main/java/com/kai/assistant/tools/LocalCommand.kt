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
    /** Open an app, then trigger a play/search intent for [query] (e.g. a song). */
    data class OpenAppAndPlay(val appName: String, val query: String) : LocalCommand()
    data class SetVolume(val percent: Int) : LocalCommand()
    data class AdjustVolume(val deltaSteps: Int) : LocalCommand()
    data class SetAlarm(val hour: Int, val minute: Int, val message: String) : LocalCommand()
    data class StartTimer(val seconds: Int, val message: String) : LocalCommand()
    data class OpenMaps(val query: String) : LocalCommand()
    data object OpenCamera : LocalCommand()
}

data class LocalMatch(
    val command: LocalCommand,
    val requiresConfirmation: Boolean,
    val confirmationPrompt: String,
    val spokenLabel: String,
)
