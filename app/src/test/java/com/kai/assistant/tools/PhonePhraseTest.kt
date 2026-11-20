package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePhraseTest {
    private val router = LocalIntentRouter()
    private val phones = listOf(
        ContactPhone(1, "Mohammed Kaif", "111", PhoneKind.Mobile),
        ContactPhone(2, "Muhammad Kev", "222", PhoneKind.Mobile),
    )

    @Test
    fun timerThenYouTubeRunsOnThePhone() {
        val utterance = "Set a 1 minute timer and then open YouTube"
        assertNull(router.match(utterance))
        assertNull(researchQuery(utterance))
        val steps = planLocalSteps(utterance) { router.match(it) }
        assertEquals(2, steps!!.size)
        val timer = steps[0].command as LocalCommand.StartTimer
        assertEquals(60, timer.seconds)
        val youtube = steps[1].command as LocalCommand.LaunchAppByName
        assertEquals("youtube", youtube.appName)
        assertTrue(steps.all { it.command.staysOnDevice() })
        assertFalse(
            LocalMatch(
                command = LocalCommand.RunPlan(steps),
                requiresConfirmation = false,
                confirmationPrompt = "",
                spokenLabel = "Doing 2 steps",
            ).requiresConfirmation,
        )
    }

    @Test
    fun namedMeetingWritesTheCalendarEvent() {
        val utterance = "Add a meeting tomorrow at 10 a.m. called Standup"
        val match = router.match(utterance)
        val command = match!!.command as LocalCommand.AddCalendarEvent
        assertEquals("Standup", command.title)
        assertEquals(1, command.dayOffset)
        assertEquals(10, command.hour)
        assertEquals(0, command.minute)
        assertEquals("am", command.meridiem)
        assertEquals(10, eventHour24(command.hour, command.meridiem))
        assertFalse(match.requiresConfirmation)
        assertTrue(command.staysOnDevice())
        assertTrue(command.needsCalendar())
        assertNull(researchQuery(utterance))
    }

    @Test
    fun spokenKaifAndCabPickTheClosestContact() {
        val call = router.match("Call Muhammad Kaif")
        val phone = call!!.command as LocalCommand.CallContact
        assertEquals("muhammad kaif", phone.query)
        assertFalse(call.requiresConfirmation)
        assertTrue(phone.staysOnDevice())
        assertNull(researchQuery("Call Muhammad Kaif"))
        val closest = ContactMatcher.match("muhammad kaif", phones, preferredKind = null) as ContactMatchResult.One
        assertEquals("Mohammed Kaif", closest.displayName)
        assertEquals("111", closest.number)

        val whatsApp = router.match("Call Mohamed Cab on WhatsApp")
        val voice = whatsApp!!.command as LocalCommand.WhatsAppCall
        assertEquals("mohamed cab", voice.nameQuery)
        assertFalse(voice.video)
        assertFalse(whatsApp.requiresConfirmation)
        assertTrue(voice.staysOnDevice())
        assertNull(researchQuery("Call Mohamed Cab on WhatsApp"))
        val cab = WhatsAppResolver.resolveContact("mohamed cab", phones) as WhatsAppCallResolution.Ready
        assertEquals("Mohammed Kaif", cab.displayName)
        assertEquals("111", cab.number)

        val message = router.match("Message hi to Muhammad Kaif on WhatsApp")
        val send = message!!.command as LocalCommand.SendWhatsApp
        assertEquals("muhammad kaif", send.nameQuery)
        assertEquals("hi", send.options.first().message)
        assertFalse(message.requiresConfirmation)
        assertTrue(send.staysOnDevice())
        assertNull(researchQuery("Message hi to Muhammad Kaif on WhatsApp"))
        val ready = WhatsAppResolver.resolve(send.options, phones) as WhatsAppResolution.Ready
        assertEquals("Mohammed Kaif", ready.displayName)
        assertEquals("hi", ready.message)
    }
}
