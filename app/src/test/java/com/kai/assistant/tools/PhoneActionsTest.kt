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
    fun sixTwentySevenPmIsALocalAlarm() {
        val match = router.match("set an alarm for 6:27 p.m.")
        assertEquals(LocalCommand.SetAlarm(18, 27, "KAI alarm"), match!!.command)
        assertTrue(match.command.staysOnDevice())
        assertEquals("Alarm set for 6:27 p.m.", alarmSpokenLine(18, 27))
        assertEquals("Alarm for 6:27 p.m.", alarmFiredLine(18, 27))
    }

    @Test
    fun naturalRepeatAndSeveralRemindersStayLocal() {
        val relative = router.match("remind me to leave in 10 minutes")
        assertEquals(10, (relative!!.command as LocalCommand.SetReminder).inMinutes)
        assertTrue(relative.command.staysOnDevice())

        val morning = router.match("remind me to pack tomorrow morning")!!.command as LocalCommand.SetReminder
        assertEquals(8, morning.hour)
        assertEquals("am", morning.meridiem)
        assertEquals(1, morning.dayOffset)

        val tonight = router.match("remind me to call Kaif tonight")!!.command as LocalCommand.SetReminder
        assertEquals(8, tonight.hour)
        assertEquals("pm", tonight.meridiem)

        val half = router.match("remind me to leave at half past six")!!.command as LocalCommand.SetReminder
        assertEquals(6, half.hour)
        assertEquals(30, half.minute)

        val daily = router.match("remind me to take medicine at 9 every day")!!.command as LocalCommand.SetReminder
        assertEquals(ReminderRepeat.Daily, daily.repeat)

        val monday = router.match("remind me to call Kaif every Monday at 6")!!.command as LocalCommand.SetReminder
        assertEquals(ReminderRepeat.Weekly, monday.repeat)
        assertEquals(java.time.DayOfWeek.MONDAY.value, monday.weekday)

        val weekdays = router.match("remind me to stand up at 9 on weekdays")!!.command as LocalCommand.SetReminder
        assertEquals(ReminderRepeat.Weekdays, weekdays.repeat)

        val both = router.match("remind me to call Kaif at 6 and to take medicine at 9")!!.command
        assertTrue(both is LocalCommand.SetReminders)
        val items = (both as LocalCommand.SetReminders).items
        assertEquals("call kaif", items[0].text)
        assertEquals("take medicine", items[1].text)
        assertTrue(both.staysOnDevice())

        val cancel = router.match("cancel the reminder to call Kaif")!!.command
        assertEquals(LocalCommand.CancelReminder("call kaif"), cancel)
        assertFalse(cancel is LocalCommand.CallContact)

        val change = router.match("change the 6 p.m. reminder to 7")!!.command as LocalCommand.ChangeReminder
        assertEquals(6, change.hour)
        assertEquals("pm", change.meridiem)
        assertEquals(7, change.newHour)
        assertTrue(change.staysOnDevice())
    }

    @Test
    fun speechUsesTheRequestLanguageOnlyWhenTheVoiceExists() {
        assertEquals("hi", replyLanguage("अलार्म लगाओ", "en"))
        assertEquals("kn", replyLanguage("ಅಲಾರ್ಮ್", "en"))
        assertEquals("en", replyLanguage("set an alarm for 6:30 a.m.", "en"))
        assertEquals("hi-IN", recognizerLanguageTag("hi"))
        assertEquals("kn-IN", recognizerLanguageTag("kn"))
        assertEquals("en-US", recognizerLanguageTag("en"))
        val english = "Alarm set for 6:30 a.m."
        assertEquals(english, speechText(english, localizedReply(english, "hi"), languageAvailable = false))
        assertTrue(speechText(english, localizedReply(english, "hi"), languageAvailable = true).contains("अलार्म"))
        assertTrue(reminderSpokenLine("call Kaif").contains("call Kaif"))
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
