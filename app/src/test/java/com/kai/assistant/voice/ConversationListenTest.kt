package com.kai.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationListenTest {
    @Test
    fun followUpStaysOpenForSixSecondsUnlessHeSpeaks() {
        assertEquals(6_000L, FOLLOW_UP_LISTEN_MS)
        val window = FollowUpWindow()
        window.open(now = 1_000)
        assertFalse(window.expired(now = 6_999))
        assertTrue(window.expired(now = 7_000))
        window.open(now = 1_000)
        window.onSpeech()
        assertFalse(window.expired(now = 20_000))
    }

    @Test
    fun bargeInIgnoresKaiAndKeepsHisWords() {
        assertFalse(shouldBargeIn("volume is", "Volume is 53%."))
        assertFalse(shouldBargeIn("sh", "Phone is silent."))
        assertTrue(shouldBargeIn("volume up", "Volume is 53%."))
        assertTrue(shouldBargeIn("play a song on youtube", "Bluetooth is on."))
        assertTrue(isAssistantEcho("phone is silent", "Phone is silent."))
        assertFalse(isAssistantEcho("what is shenzhen", "Phone is silent."))
    }
}
