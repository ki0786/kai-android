package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class PhoneActionsTest {
    private val router = LocalIntentRouter()

    @Test
    fun screenshotTimerReminderAndScreenStayLocal() {
        val shot = router.match("take a screenshot")
        assertEquals(LocalCommand.TakeScreenshot, shot!!.command)
        assertFalse(shot.requiresConfirmation)

        val timer = router.match("set a timer for 5 minutes")
        assertEquals(LocalCommand.StartTimer(300, "KAI timer"), timer!!.command)
        assertTrue(timer.requiresConfirmation)
        assertTrue(timer.command.staysOnDevice())

        val reminder = router.match("remind me to call the supplier at 7")
        assertEquals(
            LocalCommand.SetReminder("call the supplier", 7, 0, null),
            reminder!!.command,
        )
        assertFalse(reminder.requiresConfirmation)
        assertTrue(reminder.command.staysOnDevice())

        val screen = router.match("what's on my screen")
        assertEquals(LocalCommand.DescribeScreen, screen!!.command)
        assertFalse(screen.requiresConfirmation)
        assertTrue(screen.command.staysOnDevice())

        assertEquals(LocalCommand.CallContact(query = "kaif"), router.match("call kaif")!!.command)
        assertTrue(router.match("send hi to Mohammed Kaif on WhatsApp")!!.command is LocalCommand.SendWhatsApp)
        assertTrue(
            router.match("video call Mohammed Kaif on WhatsApp")!!.command is LocalCommand.WhatsAppCall,
        )
    }

    @Test
    fun remindMeToCallAtSixPmStaysOnDevice() {
        val match = router.match("remind me to call Mohammed Kaif at 6:00 p.m.")
        assertEquals(
            LocalCommand.SetReminder("call mohammed kaif", 6, 0, "pm"),
            match!!.command,
        )
        assertFalse(match.requiresConfirmation)
        assertTrue(match.command.staysOnDevice())

        val eleven = Instant.parse("2026-11-19T11:00:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-11-19T18:00:00Z").toEpochMilli(),
            nextReminderMillis(eleven, ZoneId.of("UTC"), 6, 0, "pm"),
        )
    }

    @Test
    fun sevenMeansTheNextSevenOClock() {
        val zone = ZoneId.of("UTC")
        val morning = Instant.parse("2026-11-19T06:00:00Z").toEpochMilli()
        val eleven = Instant.parse("2026-11-19T11:00:00Z").toEpochMilli()
        val evening = Instant.parse("2026-11-19T20:00:00Z").toEpochMilli()
        assertEquals(
            Instant.parse("2026-11-19T07:00:00Z").toEpochMilli(),
            nextReminderMillis(morning, zone, 7, 0, null),
        )
        assertEquals(
            Instant.parse("2026-11-19T19:00:00Z").toEpochMilli(),
            nextReminderMillis(eleven, zone, 7, 0, null),
        )
        assertEquals(
            Instant.parse("2026-11-20T07:00:00Z").toEpochMilli(),
            nextReminderMillis(evening, zone, 7, 0, null),
        )
        assertEquals(
            Instant.parse("2026-11-19T19:30:00Z").toEpochMilli(),
            nextReminderMillis(eleven, zone, 7, 30, "pm"),
        )
        assertEquals(
            "call the supplier",
            ReminderUtterance.parse("remind me to call the supplier at 7")!!.text,
        )
    }

    @Test
    fun setAlarmForSixThirtyAmStaysOnDevice() {
        val match = router.match("set an alarm for 6:30 a.m.")
        assertEquals(LocalCommand.SetAlarm(6, 30, "KAI alarm"), match!!.command)
        assertTrue(match.requiresConfirmation)
        assertTrue(match.command.staysOnDevice())
        assertEquals(
            LocalCommand.SetAlarm(18, 30, "KAI alarm"),
            router.match("set an alarm for 6:30 p.m.")!!.command,
        )
    }

    @Test
    fun firedAlertsSpeakTheReminderAndTimer() {
        assertEquals("Reminder. call mohammed kaif", reminderSpokenLine("call mohammed kaif"))
        assertEquals("Your timer finished.", timerFinishedSpokenLine())
    }

    @Test
    fun screenDescriptionUsesVisibleTextAndControls() {
        val spoken = describeScreen(
            "Messages",
            listOf(
                ScreenItem(text = "No chats yet"),
                ScreenItem(contentDescription = "Search", clickable = true),
                ScreenItem(text = "New chat", clickable = true),
            ),
        )
        assertTrue(spoken.contains("Messages"))
        assertTrue(spoken.contains("No chats yet"))
        assertTrue(spoken.contains("Search"))
        assertTrue(spoken.contains("New chat"))
        assertEquals(
            "I don't see any text or controls on this screen.",
            describeScreen(null, emptyList()),
        )
    }
}
