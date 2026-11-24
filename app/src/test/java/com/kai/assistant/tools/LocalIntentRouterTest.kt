package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIntentRouterTest {
    private val router = LocalIntentRouter()

    @Test
    fun flashlightOnAndOff() {
        val on = router.match("turn on the flashlight")
        assertNotNull(on)
        assertEquals(LocalCommand.Flashlight(true), on!!.command)
        assertFalse(on.requiresConfirmation)

        val off = router.match("flashlight off")
        assertEquals(LocalCommand.Flashlight(false), off!!.command)
    }

    @Test
    fun batteryAndDateTime() {
        assertEquals(LocalCommand.BatteryStatus, router.match("what's my battery")!!.command)
        assertEquals(LocalCommand.DateTime, router.match("what time is it")!!.command)
    }

    @Test
    fun settingsIntents() {
        assertEquals(LocalCommand.OpenWifiSettings, router.match("open wifi settings")!!.command)
        assertEquals(
            LocalCommand.OpenBluetoothSettings,
            router.match("open bluetooth settings")!!.command,
        )
    }

    @Test
    fun volumeAndTimerNeedExpectedShapes() {
        assertEquals(LocalCommand.SetVolume(40), router.match("set volume to 40")!!.command)
        assertEquals(LocalCommand.AdjustVolume(2), router.match("volume up")!!.command)

        val timer = router.match("set a timer for 2 minutes")
        assertFalse(timer!!.requiresConfirmation)
        assertEquals(LocalCommand.StartTimer(120, "KAI timer"), timer.command)
    }

    @Test
    fun launchAppOpensImmediately() {
        val match = router.match("open chrome")
        assertNotNull(match)
        assertFalse(match!!.requiresConfirmation)
        assertEquals(LocalCommand.LaunchAppByName("chrome"), match.command)
        assertTrue(match.command.staysOnDevice())
    }

    @Test
    fun unknownGoesToCloud() {
        assertNull(router.match("explain quantum entanglement simply"))
        assertNull(router.match("write a haiku about rain"))
        assertNull(router.match("call me back later"))
    }

    @Test
    fun callByNameStaysLocalWithoutConfirming() {
        val kaif = router.match("call kaif")
        assertNotNull(kaif)
        assertFalse(kaif!!.requiresConfirmation)
        assertEquals(LocalCommand.CallContact(query = "kaif"), kaif.command)
        assertEquals("Calling Kaif", kaif.spokenLabel)

        val titled = router.match("call Kaif")
        assertEquals(LocalCommand.CallContact(query = "kaif"), titled!!.command)
        assertFalse(titled.requiresConfirmation)

        val mom = router.match("phone Mom")
        assertEquals(LocalCommand.CallContact(query = "mom"), mom!!.command)
        assertEquals("Calling Mom", mom.spokenLabel)
        assertFalse(mom.requiresConfirmation)
    }

    @Test
    fun callPhrasesNeverAskForConfirmation() {
        assertFalse(router.match("yes call kaif")!!.requiresConfirmation)
        assertFalse(router.match("yes, call Kaif")!!.requiresConfirmation)
        assertFalse(router.match("call kaif yes")!!.requiresConfirmation)
        assertFalse(router.match("okay, phone mom")!!.requiresConfirmation)
        assertFalse(router.match("go ahead and call kaif")!!.requiresConfirmation)

        val polite = router.match("please call kaif")
        assertFalse(polite!!.requiresConfirmation)
        assertEquals(LocalCommand.CallContact(query = "kaif"), polite.command)
    }

    @Test
    fun callPhoneTypeAndExplicitNumber() {
        assertEquals(
            LocalCommand.CallContact(query = "kaif", phoneType = PhoneKind.Mobile),
            router.match("call kaif on his mobile")!!.command,
        )
        assertEquals(
            LocalCommand.CallContact(query = "mom", phoneType = PhoneKind.Home),
            router.match("phone mom at home")!!.command,
        )
        val number = router.match("call +91 98765 43210")
        assertFalse(number!!.requiresConfirmation)
        assertEquals(
            LocalCommand.CallContact(
                query = "+91 98765 43210",
                rawNumber = true,
                resolvedNumber = "+91 98765 43210",
            ),
            number.command,
        )
        assertEquals(
            LocalCommand.CallContact(
                query = "9876543210",
                rawNumber = true,
                resolvedNumber = "9876543210",
                resolvedName = "Kaif",
            ),
            router.match("call kaif 9876543210")!!.command,
        )
    }

    @Test
    fun whatsAppSendStaysLocalWithoutConfirming() {
        val send = router.match("send hi to Mohammed Kaif on WhatsApp")
        assertNotNull(send)
        assertFalse(send!!.requiresConfirmation)
        assertEquals(
            LocalCommand.SendWhatsApp(
                options = listOf(WhatsAppUtterance.Option("mohammed kaif", "hi")),
            ),
            send.command,
        )
        assertEquals("Sending hi to Mohammed Kaif on WhatsApp", send.spokenLabel)

        val ordered = router.match("WhatsApp Mohammed Kaif hi")
        assertFalse(ordered!!.requiresConfirmation)
        val command = ordered.command as LocalCommand.SendWhatsApp
        assertEquals("mohammed kaif", command.options.first().nameQuery)
        assertEquals("hi", command.options.first().message)

        assertEquals(LocalCommand.CallContact(query = "kaif"), router.match("call kaif")!!.command)
        assertEquals(
            LocalCommand.SendWhatsApp(options = listOf(WhatsAppUtterance.Option("mom", "hi"))),
            router.match("send hi to mom")!!.command,
        )
    }

    @Test
    fun whatsAppVoiceAndVideoStayLocalWithoutConfirming() {
        val voice = router.match("call Mohammed Kaif on WhatsApp")
        assertNotNull(voice)
        assertFalse(voice!!.requiresConfirmation)
        assertEquals(
            LocalCommand.WhatsAppCall(nameQuery = "mohammed kaif", video = false),
            voice.command,
        )
        assertEquals("Calling Mohammed Kaif on WhatsApp", voice.spokenLabel)

        val video = router.match("video call Mohammed Kaif on WhatsApp")
        assertFalse(video!!.requiresConfirmation)
        assertEquals(
            LocalCommand.WhatsAppCall(nameQuery = "mohammed kaif", video = true),
            video.command,
        )

        assertEquals(LocalCommand.CallContact(query = "kaif"), router.match("call kaif")!!.command)
        assertEquals(LocalCommand.CallContact(query = "mom"), router.match("phone mom")!!.command)
    }

    @Test
    fun couldYouCallStillMatchesLocally() {
        val match = router.match("could you phone mom")
        assertNotNull(match)
        assertFalse(match!!.requiresConfirmation)
        assertEquals(LocalCommand.CallContact(query = "mom"), match.command)
    }
}
