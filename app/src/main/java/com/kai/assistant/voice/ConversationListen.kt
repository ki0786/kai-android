package com.kai.assistant.voice

import com.kai.assistant.assistant.TurnOutcome

/** How long to keep the mic open after KAI finishes speaking. */
internal const val FOLLOW_UP_LISTEN_MS = 6_000L

/**
 * True when the recognizer heard KAI's own voice. That must not stop speech
 * and must not be sent back as the next command.
 */
internal fun isAssistantEcho(heard: String, spoken: String): Boolean {
    val h = heard.trim().lowercase().trimEnd('.', '!', '?')
    val s = spoken.trim().lowercase().trimEnd('.', '!', '?')
    if (h.length < 2 || s.isEmpty()) return false
    return s.contains(h) || (s.length > 8 && h.contains(s))
}

/** User speech during playback. Echo of the reply is ignored. */
internal fun shouldBargeIn(partial: String, spoken: String): Boolean {
    val text = partial.trim()
    if (text.length < 3) return false
    return !isAssistantEcho(text, spoken)
}

/** The six-second window after a reply. Speech keeps it from closing. */
internal class FollowUpWindow {
    var isOpen: Boolean = false
        private set

    private var openedAt = 0L
    private var speechHeard = false

    fun open(now: Long) {
        isOpen = true
        openedAt = now
        speechHeard = false
    }

    fun onSpeech() {
        speechHeard = true
    }

    fun expired(now: Long, windowMs: Long = FOLLOW_UP_LISTEN_MS): Boolean {
        if (!isOpen || speechHeard) return false
        return now - openedAt >= windowMs
    }

    fun close() {
        isOpen = false
        speechHeard = false
    }
}

internal fun spokenTurn(outcome: TurnOutcome): String = when (outcome) {
    is TurnOutcome.LocalReady -> outcome.reply
    is TurnOutcome.CloudReady -> outcome.spoken
    is TurnOutcome.NeedsConfirmation -> outcome.match.confirmationPrompt
    is TurnOutcome.NeedsContactsPermission -> outcome.explanation
    is TurnOutcome.NeedsCallPermission -> outcome.explanation
    is TurnOutcome.NeedsAccessibilitySettings -> outcome.explanation
    is TurnOutcome.NeedsNotificationPermission -> outcome.explanation
    is TurnOutcome.NeedsCalendarPermission -> outcome.explanation
    is TurnOutcome.NeedsScreenshotConsent -> outcome.explanation
    is TurnOutcome.NeedsPhoto -> outcome.explanation
}
