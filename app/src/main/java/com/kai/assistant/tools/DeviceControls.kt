package com.kai.assistant.tools

/**
 * Spoken results and pure decisions for the on-phone controls.
 * Android applies these in [com.kai.assistant.tools] device tools. No network.
 */

internal const val BRAIN_OFFLINE =
    "The brain is offline. Calls, alarms, books, photos, and the phone controls still work on this phone."

internal const val DND_NEEDS_ACCESS =
    "I need Do Not Disturb access before I can change it. I opened that settings screen. Allow it, then ask again."

internal const val BRIGHTNESS_NEEDS_ACCESS =
    "I need Modify system settings before I can change the brightness. Allow that for KAI, then ask again."

internal const val HOTSPOT_BLOCKED =
    "Android won't let an app turn the hotspot on or off."

internal const val NOTIFICATION_READ_NEEDS_ACCESS =
    "I can't read notifications until you allow KAI in notification access. I opened that settings screen."

internal const val NOTIFICATION_READ_NOT_READY =
    "Notification access is on, but I can't see notifications yet. Try again in a moment."

internal const val NOTIFICATION_READ_EMPTY =
    "There are no notifications to read."

internal const val MEDIA_NEEDS_ACCESS =
    "I couldn't reach the player. Allow KAI in notification access, then try again. I opened that settings screen."

/** One step is about ten percent of the 1–255 system brightness range. */
internal const val BRIGHTNESS_MAX = 255
internal const val BRIGHTNESS_STEP = 26

enum class MediaAction { Play, Pause, Next, Previous }

internal data class NoticeLine(
    val packageName: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
    val groupSummary: Boolean,
)

/** GET {baseUrl}/health. The address is the one already saved in settings. */
internal fun healthUrl(baseUrl: String): String = "${baseUrl.trim().trimEnd('/')}/health"

/**
 * The public Wi-Fi and Bluetooth toggles are blocked for an app that targets
 * this SDK. The phone still switches them through the radio service when the
 * ROM allows it, and otherwise through the quick-settings tile, which is
 * dismissed so the current app stays on screen.
 */
internal fun radioUsesPanel(sdkInt: Int): Boolean = sdkInt >= 29

internal fun radioSpoken(label: String, enabled: Boolean): String =
    if (enabled) "$label is on." else "$label is off."

internal fun radioFailed(label: String, enabled: Boolean): String =
    "I couldn't turn $label ${if (enabled) "on" else "off"}."

internal fun blockedRadioSpeech(label: String, enabled: Boolean, usesPanel: Boolean): String {
    val state = if (enabled) "on" else "off"
    val where = if (usesPanel) "panel" else "settings screen"
    return "Android won't let an app turn $label $state. I opened the $label $where so you can tap it."
}

/** Music-stream index for a spoken percent. 100 is the stream maximum. */
internal fun indexForPercent(percent: Int, min: Int, max: Int): Int {
    val low = min.coerceAtMost(max)
    val high = max.coerceAtLeast(low)
    val clamped = percent.coerceIn(0, 100)
    if (clamped <= 0) return low
    if (clamped >= 100) return high
    val span = high - low
    return (low + (clamped * span) / 100).coerceIn(low, high)
}

/** What to say after a volume change. This is the level the stream reports. */
internal fun percentForIndex(index: Int, min: Int, max: Int): Int {
    val low = min.coerceAtMost(max)
    val high = max.coerceAtLeast(low)
    val span = (high - low).coerceAtLeast(1)
    val clamped = index.coerceIn(low, high)
    if (clamped <= low) return 0
    if (clamped >= high) return 100
    return ((clamped - low) * 100) / span
}

internal fun steppedVolumeIndex(current: Int, deltaSteps: Int, min: Int, max: Int): Int {
    val low = min.coerceAtMost(max)
    val high = max.coerceAtLeast(low)
    return (current + deltaSteps).coerceIn(low, high)
}

internal fun volumeSpoken(percent: Int): String = "Volume is ${percent.coerceIn(0, 100)}%."

internal const val PHONE_SILENT = "Phone is silent."

internal const val RINGER_SILENT = 0
internal const val RINGER_VIBRATE = 1
internal const val RINGER_NORMAL = 2

internal const val FILTER_ALL = 1
internal const val FILTER_PRIORITY = 2
internal const val FILTER_NONE = 3
internal const val FILTER_ALARMS = 4

/**
 * Silent sets the ringer to silent and the ring stream to zero.
 * It does not request a zen, interruption-filter, or notification-policy change.
 */
internal data class SilentSideEffects(
    val setsRingerModeSilent: Boolean,
    val setsInterruptionFilter: Boolean,
    val setsZenMode: Boolean,
    val setsNotificationPolicy: Boolean,
)

internal fun silentSideEffects(): SilentSideEffects = SilentSideEffects(
    setsRingerModeSilent = true,
    setsInterruptionFilter = false,
    setsZenMode = false,
    setsNotificationPolicy = false,
)

/** "Phone is silent." only after the ringer reads silent and the ring stream is 0. */
internal fun silentSpeech(mode: Int, ringVolume: Int): String? =
    if (mode == RINGER_SILENT && ringVolume == 0) PHONE_SILENT else null

/** The ringer is audible only in normal mode with the ring stream above 0. */
internal fun ringerIsAudible(mode: Int, ringVolume: Int): Boolean =
    mode == RINGER_NORMAL && ringVolume > 0

internal fun isDoNotDisturbFilter(filter: Int): Boolean =
    filter == FILTER_PRIORITY || filter == FILTER_NONE || filter == FILTER_ALARMS

/** True when a ringer write flipped Do Not Disturb on and it should be put back. */
internal fun shouldRevertDoNotDisturb(dndBefore: Boolean, dndAfterWrite: Boolean): Boolean =
    !dndBefore && dndAfterWrite

/** True when the revert failed and this app left Do Not Disturb on. */
internal fun appEnabledDndAfterSilence(dndBefore: Boolean, dndAfterRevert: Boolean): Boolean =
    !dndBefore && dndAfterRevert

/**
 * Undo silent turns Do Not Disturb off only when this app turned it on.
 * An explicit "turn on DND" is left alone. [unmarkedLegacySilent] covers a
 * silent command from before this app started recording that fact.
 */
internal fun shouldTurnOffDndOnRestore(
    appEnabledDoNotDisturb: Boolean,
    explicitUserDoNotDisturb: Boolean,
    currentlyDoNotDisturb: Boolean,
    unmarkedLegacySilent: Boolean,
): Boolean {
    if (explicitUserDoNotDisturb) return false
    if (appEnabledDoNotDisturb) return true
    return unmarkedLegacySilent && currentlyDoNotDisturb
}

internal data class RingerRestorePlan(
    val ringerMode: Int,
    val ringVolume: Int,
)

/**
 * Undo always uses normal mode. A saved ring volume above 0 is restored.
 * Zero or a missing volume becomes about half the stream (15 -> 8) so it can be heard.
 */
internal fun planRingerRestore(previousVolume: Int?, maxVolume: Int): RingerRestorePlan {
    val cap = maxVolume.coerceAtLeast(1)
    val saved = previousVolume ?: 0
    val volume = if (saved > 0) saved.coerceIn(1, cap) else fallbackRingVolume(cap)
    return RingerRestorePlan(RINGER_NORMAL, volume)
}

/** About half. 15 becomes 8. */
internal fun fallbackRingVolume(maxVolume: Int): Int {
    val cap = maxVolume.coerceAtLeast(1)
    return ((cap + 1) / 2).coerceIn(1, cap)
}

internal fun ringerRestoredSpoken(turnedDoNotDisturbOff: Boolean): String =
    if (turnedDoNotDisturbOff) "The ringer is on. Do Not Disturb is off." else "The ringer is on."

/** Null when the readback is still silent, vibrate, or volume 0. */
internal fun ringerRestoreSpeech(mode: Int, ringVolume: Int, turnedDoNotDisturbOff: Boolean): String? {
    if (!ringerIsAudible(mode, ringVolume)) return null
    return ringerRestoredSpoken(turnedDoNotDisturbOff)
}

/** "Bluetooth is on." only when the adapter readback matches the request. */
internal fun radioSpeechForReadback(label: String, wantedOn: Boolean, adapterMatches: Boolean?): String? {
    if (adapterMatches != true) return null
    return radioSpoken(label, wantedOn)
}

/**
 * Quick-settings tile decision. [Already] means the tile already matches,
 * so it must not be tapped. [Click] changes it. [Ignore] is a different tile.
 */
internal enum class QuickSettingAction { Ignore, Already, Click }

internal fun quickSettingAction(label: String, names: List<String>, wantOn: Boolean): QuickSettingAction {
    val text = label.lowercase()
    if (names.none { name -> text.contains(name) }) return QuickSettingAction.Ignore
    val on = quickSettingIsOn(text)
    return when {
        on == null -> QuickSettingAction.Click
        on == wantOn -> QuickSettingAction.Already
        else -> QuickSettingAction.Click
    }
}

internal fun quickSettingIsOn(label: String): Boolean? {
    val text = label.lowercase()
    val off = Regex("""\b(?:off|disabled|disconnected)\b""").containsMatchIn(text)
    val on = Regex("""\b(?:on|enabled|connected)\b""").containsMatchIn(text)
    return when {
        off && !on -> false
        on && !off -> true
        else -> null
    }
}

internal fun dndSpoken(enabled: Boolean): String =
    if (enabled) "Do Not Disturb is on." else "Do Not Disturb is off."

internal fun steppedBrightness(current: Int, up: Boolean): Int {
    val start = current.coerceIn(1, BRIGHTNESS_MAX)
    val delta = if (up) BRIGHTNESS_STEP else -BRIGHTNESS_STEP
    return (start + delta).coerceIn(1, BRIGHTNESS_MAX)
}

internal fun brightnessPercent(level: Int): Int =
    (level.coerceIn(0, BRIGHTNESS_MAX) * 100) / BRIGHTNESS_MAX

internal fun brightnessSpoken(before: Int, after: Int, switchedToManual: Boolean): String {
    val stuck = before.coerceIn(1, BRIGHTNESS_MAX) == after
    val base = when {
        stuck && after <= 1 -> "Brightness is already as low as it goes."
        stuck -> "Brightness is already as high as it goes."
        else -> "Brightness is about ${brightnessPercent(after)} percent."
    }
    return if (switchedToManual) "$base I turned off automatic brightness so this sticks." else base
}

internal fun mediaSpoken(action: MediaAction, title: String?): String {
    val name = title?.trim().orEmpty()
    val named = name.isNotEmpty()
    return when (action) {
        MediaAction.Play -> if (named) "Playing $name." else "Playing."
        MediaAction.Pause -> if (named) "Paused $name." else "Paused."
        MediaAction.Next -> "Next track."
        MediaAction.Previous -> "Previous track."
    }
}

/**
 * Newest posted notification that is not from this app.
 * A group summary is used only when nothing else has text.
 */
internal fun pickLatestNotice(items: List<NoticeLine>, ownPackage: String): NoticeLine? {
    val others = items.filter {
        it.packageName.isNotBlank() &&
            it.packageName != ownPackage &&
            (it.title.isNotBlank() || it.text.isNotBlank())
    }
    val detailed = others.filter { !it.groupSummary }
    return (detailed.ifEmpty { others }).maxByOrNull { it.postedAtMs }
}

internal fun speakNotice(appName: String, title: String, text: String): String {
    val from = appName.trim().ifBlank { "an app" }
    val headline = title.trim()
    val body = text.trim()
    return when {
        headline.isBlank() && body.isBlank() -> "The latest notification has no text."
        headline.isBlank() -> "Latest notification from $from. $body"
        body.isBlank() || body.equals(headline, ignoreCase = true) ->
            "Latest notification from $from. $headline"
        else -> "Latest notification from $from. $headline. $body"
    }
}
