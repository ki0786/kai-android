package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompoundCommandTest {
    private val router = LocalIntentRouter()

    @Test
    fun openYoutubeAndPlayStaysOnThePhone() {
        val match = router.match("open youtube and play believer")
        val command = match!!.command as LocalCommand.SearchYouTube
        assertEquals("believer", command.query)
        assertFalse(match.requiresConfirmation)
        assertTrue(command.staysOnDevice())
        assertNull(researchQuery("open youtube and play believer"))
    }

    @Test
    fun whatsAppCallsAndMessagesDoNotAskOrGoToChat() {
        val video = router.match("open whatsapp and call mohamed on video call")!!.command as LocalCommand.WhatsAppCall
        assertEquals("mohamed", video.nameQuery)
        assertTrue(video.video)
        assertTrue(video.staysOnDevice())

        val kev = router.match("video call muhammad kev on whatsapp")!!.command as LocalCommand.WhatsAppCall
        assertEquals("muhammad kev", kev.nameQuery)
        assertTrue(kev.video)
        assertFalse(router.match("video call muhammad kev on whatsapp")!!.requiresConfirmation)

        val voice = router.match("call nuruddin on whatsapp")!!.command as LocalCommand.WhatsAppCall
        assertEquals("nuruddin", voice.nameQuery)
        assertFalse(voice.video)

        val noisy = router.match("noir call nuruddin on whatsapp")!!.command as LocalCommand.WhatsAppCall
        assertEquals("nuruddin", noisy.nameQuery)
        assertFalse(noisy.video)
        assertTrue(noisy.staysOnDevice())

        val message = router.match("message hi to muhammad kef on whatsapp")!!.command as LocalCommand.SendWhatsApp
        assertEquals("muhammad kef", message.nameQuery)
        assertEquals("hi", message.options.first().message)
        assertFalse(router.match("message hi to muhammad kef on whatsapp")!!.requiresConfirmation)
        assertTrue(message.staysOnDevice())
        assertNull(researchQuery("message hi to muhammad kef on whatsapp"))
        assertNull(researchQuery("noir call nuruddin on whatsapp"))
    }

    @Test
    fun looseNamesResolveAndTheCallProceeds() {
        val phones = listOf(
            ContactPhone(1, "Muhammad Ali", "111", PhoneKind.Mobile),
            ContactPhone(2, "Muhammad Kef", "222", PhoneKind.Mobile),
            ContactPhone(3, "Nuruddin", "333", PhoneKind.Mobile),
        )
        val video = WhatsAppResolver.resolveContact("muhammad kev", phones) as WhatsAppCallResolution.Ready
        assertEquals("Muhammad Kef", video.displayName)
        assertEquals("222", video.number)

        val voice = WhatsAppResolver.resolveContact("nurdine", phones) as WhatsAppCallResolution.Ready
        assertEquals("Nuruddin", voice.displayName)

        val message = WhatsAppResolver.resolve(
            WhatsAppUtterance.parse("message hi to mohamed on whatsapp")!!.options,
            listOf(ContactPhone(4, "Muhammad", "444", PhoneKind.Mobile)),
        ) as WhatsAppResolution.Ready
        assertEquals("Muhammad", message.displayName)
        assertEquals("hi", message.message)
    }
}
