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
    "I need permission to change brightness. I opened that settings screen. Allow it, then ask again."

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
 * Android 10 and later, for an app that targets 29 or later, will not let the
 * app switch Wi-Fi. Bluetooth enable and disable fail for an app that targets
 * Android 13 or later, and there is no Bluetooth quick-settings panel intent.
 * This app targets 35, so Wi-Fi opens the system panel and Bluetooth opens
 * Bluetooth settings. Neither call is the blocked toggle API.
 */
internal fun radioUsesPanel(sdkInt: Int): Boolean = sdkInt >= 29

internal fun blockedRadioSpeech(label: String, enabled: Boolean, usesPanel: Boolean): String {
    val state = if (enabled) "on" else "off"
    val where = if (usesPanel) "panel" else "settings screen"
    return "Android won't let an app turn $label $state. I opened the $label $where so you can tap it."
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
