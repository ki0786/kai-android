package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppUtteranceTest {
    @Test
    fun sendHiToMohammedKaif() {
        val parsed = WhatsAppUtterance.parse("send hi to mohammed kaif on whatsapp")
        assertEquals("mohammed kaif", parsed!!.nameQuery)
        assertEquals("hi", parsed.message)
        assertEquals(1, parsed.options.size)
    }

    @Test
    fun whatsAppNameThenMessagePrefersLongerName() {
        val parsed = WhatsAppUtterance.parse("whatsapp mohammed kaif hi")
        assertEquals("mohammed kaif", parsed!!.options[0].nameQuery)
        assertEquals("hi", parsed.options[0].message)
        assertEquals("mohammed", parsed.options[1].nameQuery)
        assertEquals("kaif hi", parsed.options[1].message)
    }

    @Test
    fun longerContactWinsOverShorterPrefix() {
        val phones = listOf(
            ContactPhone(1, "Mohammed", "111", PhoneKind.Mobile),
            ContactPhone(2, "Mohammed Kaif", "222", PhoneKind.Mobile),
        )
        val parsed = WhatsAppUtterance.parse("whatsapp mohammed kaif hi")!!
        val ready = WhatsAppResolver.resolve(parsed.options, phones) as WhatsAppResolution.Ready
        assertEquals("Mohammed Kaif", ready.displayName)
        assertEquals("222", ready.number)
        assertEquals("hi", ready.message)
        assertEquals(2L, ready.contactId)
    }

    @Test
    fun noContactAndSeveralStayUnresolved() {
        val none = WhatsAppResolver.resolve(
            WhatsAppUtterance.parse("send hi to mohammed kaif on whatsapp")!!.options,
            emptyList(),
        )
        assertTrue(none is WhatsAppResolution.Speak)
        assertTrue((none as WhatsAppResolution.Speak).message.contains("couldn't find"))

        val several = WhatsAppResolver.resolve(
            listOf(WhatsAppUtterance.Option("kaif", "hi")),
            listOf(
                ContactPhone(1, "Kaif Ahmed", "111", PhoneKind.Mobile),
                ContactPhone(2, "Kaif Khan", "222", PhoneKind.Mobile),
            ),
        )
        assertTrue(several is WhatsAppResolution.Speak)
        assertTrue((several as WhatsAppResolution.Speak).message.contains("several"))
    }

    @Test
    fun unrelatedTextIsNotWhatsApp() {
        assertNull(WhatsAppUtterance.parse("call kaif"))
        assertNull(WhatsAppUtterance.parse("send money to mom"))
        assertNull(WhatsAppUtterance.parse("text me later"))
        assertNull(WhatsAppUtterance.parse("whatsapp"))
        assertNull(WhatsAppUtterance.parseCall("call kaif"))
        assertNull(WhatsAppUtterance.parse("call mohammed kaif on whatsapp"))
    }

    @Test
    fun voiceAndVideoCallsResolveOneContact() {
        val voice = WhatsAppUtterance.parseCall("call mohammed kaif on whatsapp")
        assertEquals("mohammed kaif", voice!!.nameQuery)
        assertEquals(false, voice.video)
        val video = WhatsAppUtterance.parseCall("video call mohammed kaif on whatsapp")
        assertEquals(true, video!!.video)
        assertEquals("mohammed kaif", video.nameQuery)
        assertEquals(
            "kaif",
            WhatsAppUtterance.parseCall("whatsapp call kaif")!!.nameQuery,
        )

        val phones = listOf(ContactPhone(7, "Mohammed Kaif", "+91 90000 11111", PhoneKind.Mobile))
        val ready = WhatsAppResolver.resolveContact("mohammed kaif", phones) as WhatsAppCallResolution.Ready
        assertEquals(7L, ready.contactId)
        assertEquals("Mohammed Kaif", ready.displayName)

        val several = WhatsAppResolver.resolveContact(
            "kaif",
            listOf(
                ContactPhone(1, "Kaif Ahmed", "111", PhoneKind.Mobile),
                ContactPhone(2, "Kaif Khan", "222", PhoneKind.Mobile),
            ),
        )
        assertTrue(several is WhatsAppCallResolution.Speak)
    }

    @Test
    fun messageJidAndCallMime() {
        assertEquals("919000011111@s.whatsapp.net", messageJid("+91 90000 11111"))
        assertNull(messageJid("12"))
        assertEquals(
            "vnd.android.cursor.item/vnd.com.whatsapp.voip.call",
            callMime("com.whatsapp", video = false),
        )
        assertEquals(
            "vnd.android.cursor.item/vnd.com.whatsapp.video.call",
            callMime("com.whatsapp", video = true),
        )
        assertEquals(
            "vnd.android.cursor.item/vnd.com.whatsapp.w4b.voip.call",
            callMime("com.whatsapp.w4b", video = false),
        )
    }
}
