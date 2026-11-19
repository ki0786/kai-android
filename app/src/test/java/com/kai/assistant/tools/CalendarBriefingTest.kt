package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarBriefingTest {
    private val router = LocalIntentRouter()

    @Test
    fun addMeetingTomorrowAtTen() {
        val match = router.match("add a meeting tomorrow at 10")
        val command = match!!.command as LocalCommand.AddCalendarEvent
        assertEquals("meeting", command.title)
        assertEquals(1, command.dayOffset)
        assertEquals(10, command.hour)
        assertEquals(0, command.minute)
        assertNull(command.meridiem)
        assertEquals(10, eventHour24(command.hour, command.meridiem))
        assertFalse(match.requiresConfirmation)
        assertTrue(command.staysOnDevice())
        assertTrue(command.needsCalendar())
        assertNull(researchQuery("add a meeting tomorrow at 10"))
        assertNull(planLocalSteps("add a meeting tomorrow at 10") { router.match(it) })
    }

    @Test
    fun readTodaysSchedule() {
        val match = router.match("what's on my schedule today")
        val command = match!!.command as LocalCommand.ReadSchedule
        assertEquals(0, command.dayOffset)
        assertTrue(command.staysOnDevice())
        assertTrue(command.needsCalendar())
        val tomorrow = router.match("show me my calendar tomorrow")!!.command as LocalCommand.ReadSchedule
        assertEquals(1, tomorrow.dayOffset)
    }

    @Test
    fun noteStaysOnThePhone() {
        val match = router.match("note that the supplier called")
        val command = match!!.command as LocalCommand.SaveNote
        assertEquals("the supplier called", command.text)
        assertTrue(command.staysOnDevice())
        assertFalse(command.needsCalendar())
        assertNull(parseMemoryRequest("note that the supplier called"))
        assertNull(researchQuery("note that the supplier called"))
    }

    @Test
    fun dailyBriefingSpeaksEventsRemindersAndAlarm() {
        val match = router.match("daily briefing")
        assertTrue(match!!.command is LocalCommand.DailyBriefing)
        assertTrue(match.command.staysOnDevice())
        assertTrue(match.command.needsCalendar())
        assertNull(researchQuery("daily briefing"))
        val spoken = dailyBriefingLine(
            events = listOf("meeting at 10:00 a.m."),
            reminders = listOf("stretch at 8:00 a.m."),
            nextAlarm = "6:27 p.m.",
        )
        assertEquals(
            "Today: meeting at 10:00 a.m. Pending reminders: stretch at 8:00 a.m. Next alarm: 6:27 p.m.",
            spoken,
        )
        val empty = dailyBriefingLine(emptyList(), emptyList(), null, calendarReadable = false)
        assertTrue(empty.contains("can't read your calendar"))
        assertTrue(empty.contains("No pending reminders."))
        assertTrue(empty.contains("No alarm set."))
    }

    @Test
    fun screenQuestionIsNotTheSchedule() {
        val screen = router.match("what's on my screen")
        assertTrue(screen!!.command is LocalCommand.DescribeScreen)
    }
}
