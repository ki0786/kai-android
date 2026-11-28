package com.kai.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class MicEarconSilenceTest {
    @Test
    fun readyDoesNotOpenTheMuteWindowEarly() {
        val opened = earconMuteUntil(now = 0, currentDeadline = 0, holdMs = 1_500)
        assertEquals(1_500L, opened)
        val ready = earconMuteUntil(now = 400, currentDeadline = opened, holdMs = 200)
        assertEquals(1_500L, ready)
        val closed = earconMuteUntil(now = 1_200, currentDeadline = ready, holdMs = 1_500)
        assertEquals(2_700L, closed)
    }
}
