package com.kai.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlanTest {
    private val router = LocalIntentRouter()

    @Test
    fun timerAndReminderStayOnThePhone() {
        val utterance = "set a timer for 2 minutes and remind me to stretch at 8"
        assertNull(router.match(utterance))
        val steps = planLocalSteps(utterance) { router.match(it) }
        assertEquals(2, steps!!.size)
        val timer = steps[0].command as LocalCommand.StartTimer
        assertEquals(120, timer.seconds)
        assertEquals("Starting a timer", steps[0].label)
        val reminder = steps[1].command as LocalCommand.SetReminder
        assertEquals("stretch", reminder.text)
        assertEquals(8, reminder.hour)
        assertEquals(0, reminder.minute)
        assertTrue(steps.all { it.command.staysOnDevice() })
        val plan = LocalCommand.RunPlan(steps)
        assertTrue(plan.staysOnDevice())
        assertTrue(plan.needsNotification())
    }

    @Test
    fun oneActionIsNotAPlan() {
        assertNull(planLocalSteps("call kaif") { router.match(it) })
        assertNull(planLocalSteps("what did I say about the supplier") { router.match(it) })
        assertNull(planLocalSteps("search the web for the supplier") { router.match(it) })
        val single = router.match("remind me to call the supplier at 7 and to pack tomorrow morning")
        assertTrue(single!!.command is LocalCommand.SetReminders)
        assertNull(planLocalSteps("hello there") { router.match(it) })
    }

    @Test
    fun failedStepRetriesOnceAndTheSummarySaysWhatHappened() {
        assertTrue(shouldRetry(failed = true, alreadyRetried = false))
        assertFalse(shouldRetry(failed = true, alreadyRetried = true))
        assertFalse(shouldRetry(failed = false, alreadyRetried = false))
        assertEquals(
            "Finished: Timer set for 2 minutes. Failed: Could not set the reminder.",
            planSummary(
                listOf(
                    StepReport(ok = true, detail = "Timer set for 2 minutes."),
                    StepReport(ok = false, detail = "Could not set the reminder."),
                ),
            ),
        )
        assertEquals("Step 1 of 2: Starting a timer.", planStepLine(0, 2, "Starting a timer"))
        assertEquals("I'll do 2 steps on this phone.", planIntro(2))
    }
}
