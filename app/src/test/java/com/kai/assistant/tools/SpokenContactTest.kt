package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenContactTest {
    private val router = LocalIntentRouter()
    private val phones = listOf(
        ContactPhone(1, "Mohammed Kaif", "111", PhoneKind.Mobile),
        ContactPhone(2, "Muhammad Kev", "222", PhoneKind.Mobile),
    )

    @Test
    fun phoneCallsUseTheClosestSpokenName() {
        val onThisPhone = router.match("Call Mohammad kev on this phone")
        val kevCall = onThisPhone!!.command as LocalCommand.CallContact
        assertEquals("mohammad kev", kevCall.query)
        assertFalse(onThisPhone.requiresConfirmation)
        assertTrue(kevCall.staysOnDevice())
        assertNull(researchQuery("Call Mohammad kev on this phone"))

        val short = router.match("Call Mohammad ke")
        val keCall = short!!.command as LocalCommand.CallContact
        assertEquals("mohammad ke", keCall.query)
        assertFalse(short.requiresConfirmation)
        assertTrue(keCall.staysOnDevice())

        val closest = ContactMatcher.match("mohammad kev", phones, preferredKind = null) as ContactMatchResult.One
        assertEquals("Muhammad Kev", closest.displayName)
        assertEquals("222", closest.number)

        val shorter = ContactMatcher.match("mohammad ke", phones, preferredKind = null) as ContactMatchResult.One
        assertEquals("Muhammad Kev", shorter.displayName)

        val onlyKaif = ContactMatcher.match(
            "mohammad ke",
            listOf(ContactPhone(1, "Mohammed Kaif", "111", PhoneKind.Mobile)),
            preferredKind = null,
        ) as ContactMatchResult.One
        assertEquals("Mohammed Kaif", onlyKaif.displayName)
    }

    @Test
    fun vedioAndKefStayOnWhatsAppVideo() {
        val kef = router.match("video call muhammad kef on whatsapp")
        val kefCall = kef!!.command as LocalCommand.WhatsAppCall
        assertEquals("muhammad kef", kefCall.nameQuery)
        assertTrue(kefCall.video)
        assertFalse(kef.requiresConfirmation)
        assertTrue(kefCall.staysOnDevice())
        assertNull(researchQuery("video call muhammad kef on whatsapp"))

        val vedio = router.match("vedio call Mohammed Kaif on WhatsApp")
        val vedioCall = vedio!!.command as LocalCommand.WhatsAppCall
        assertEquals("mohammed kaif", vedioCall.nameQuery)
        assertTrue(vedioCall.video)
        assertFalse(vedio.requiresConfirmation)
        assertTrue(vedioCall.staysOnDevice())
        assertNull(researchQuery("vedio call Mohammed Kaif on WhatsApp"))

        val ready = WhatsAppResolver.resolveContact("muhammad kef", phones) as WhatsAppCallResolution.Ready
        assertEquals("Muhammad Kev", ready.displayName)
        assertEquals("222", ready.number)

        val kaif = WhatsAppResolver.resolveContact(
            "mohammad ke",
            listOf(ContactPhone(1, "Mohammed Kaif", "111", PhoneKind.Mobile)),
        ) as WhatsAppCallResolution.Ready
        assertEquals("Mohammed Kaif", kaif.displayName)
    }
}
