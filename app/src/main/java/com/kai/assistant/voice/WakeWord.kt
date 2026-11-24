package com.kai.assistant.voice

internal data class WakeMatch(val command: String)

/**
 * "Hey KAI" and a short command spoken in the same breath.
 * [command] is empty when the phrase is only the wake word.
 */
internal fun matchHeyKai(raw: String): WakeMatch? {
    var text = raw.lowercase()
        .replace(Regex("[^a-z0-9:.\\s']"), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (text.isBlank()) return null
    text = text.replace(Regex("""\bk\s+a\s+i\b"""), "kai")
    val match = wakePattern.find(text) ?: return null
    return WakeMatch(command = match.groupValues[1].trim())
}

/** Android does not keep this microphone on after the session screen closes. */
internal const val WAKE_BACKGROUND_NOTE =
    "Android won't keep the microphone on in the background. Hey KAI only listens while this screen is open."

/** The wake listener never opens the cloud recognizer. */
internal const val WAKE_NO_ON_DEVICE =
    "This phone has no on-device recognizer, so Hey KAI is off. The microphone is not sent to a cloud stream."

internal fun wakeUsesCloudStream(): Boolean = false

private val wakePattern = Regex(
    """^(?:hey|hi|hay|ok|okay)\s+(?:kai|kaai|kye|cai|kay|ky)\b[\s.]*(.*)$""",
)
