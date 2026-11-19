package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppSendTapTest {
    @Test
    fun sendControlIsTheSendLabelOrSendId() {
        assertTrue(isSendControl("Send", null))
        assertTrue(isSendControl("send message", "com.whatsapp:id/voice_note"))
        assertTrue(isSendControl(null, "com.whatsapp:id/send"))
        assertFalse(isSendControl("Voice message", "com.whatsapp:id/voice_note"))
        assertFalse(isSendControl("Send payment", "com.whatsapp:id/send_payment"))
    }

    @Test
    fun tapsOnlyWhenTheComposerHoldsThisMessage() {
        val nodes = listOf(
            SendNode("Message", "com.whatsapp:id/entry", "hi", editable = true, clickable = true),
            SendNode("Send", "com.whatsapp:id/send", null, editable = false, clickable = true),
        )
        assertEquals(1, sendButtonIndex(nodes, "hi"))
        assertNull(sendButtonIndex(nodes, "hello"))
        assertNull(
            sendButtonIndex(
                nodes.map { it.copy(text = if (it.editable) "" else it.text) },
                "hi",
            ),
        )
    }

    @Test
    fun requestExpiresAndDoesNotStayArmed() {
        WhatsAppSendRequest.clear()
        WhatsAppSendRequest.arm("com.whatsapp", "hi", nowMs = 1_000)
        val armed = WhatsAppSendRequest.peek(1_000)
        assertEquals("com.whatsapp", armed!!.packageName)
        assertEquals("hi", armed.message)
        assertNull(WhatsAppSendRequest.peek(1_000 + WhatsAppSendRequest.WINDOW_MS + 1))
        assertNull(WhatsAppSendRequest.peek(1_000 + WhatsAppSendRequest.WINDOW_MS + 2))
    }

    @Test
    fun askToEnableOnlyOnceWhileTheServiceIsOff() {
        assertTrue(shouldAskToEnable(serviceEnabled = false, alreadyAsked = false))
        assertFalse(shouldAskToEnable(serviceEnabled = false, alreadyAsked = true))
        assertFalse(shouldAskToEnable(serviceEnabled = true, alreadyAsked = false))
    }
}
