package com.kai.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun heyKaiStartsASession() {
        assertEquals("", matchHeyKai("Hey KAI")!!.command)
        assertEquals("", matchHeyKai("hey, kai!")!!.command)
        assertEquals("", matchHeyKai("hi kai")!!.command)
        assertEquals("what's on my schedule today", matchHeyKai("Hey KAI what's on my schedule today")!!.command)
        assertNull(matchHeyKai("what's on my schedule today"))
        assertNull(matchHeyKai("set an alarm for 6"))
        assertNull(matchHeyKai("kai"))
    }

    @Test
    fun wakeStaysOnTheScreenAndOffTheCloud() {
        assertFalse(wakeUsesCloudStream())
        assertTrue(WAKE_BACKGROUND_NOTE.contains("background"))
        assertTrue(WAKE_BACKGROUND_NOTE.contains("this screen is open"))
        assertTrue(WAKE_NO_ON_DEVICE.contains("not sent to a cloud stream"))
    }
}
