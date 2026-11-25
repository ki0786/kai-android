package com.kai.assistant.books

import android.content.Context
import com.kai.assistant.tools.ReminderScheduler
import com.kai.assistant.tools.ReminderSpec
import com.kai.assistant.tools.nextReminderMillis
import java.time.ZoneId

/**
 * Low-stock reminder on this phone. This does not text, call, or WhatsApp the supplier.
 */
object BooksReminders {
    fun sync(context: Context, outcome: BooksOutcome) {
        if (outcome.reorderReminders.isEmpty() && outcome.clearReorderFor.isEmpty()) return
        runCatching {
            val scheduler = ReminderScheduler(context)
            outcome.clearReorderFor.forEach { item -> scheduler.cancelMatching("Reorder $item") }
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            outcome.reorderReminders.forEach { reminder ->
                scheduler.cancelMatching("Reorder ${reminder.item}")
                val at = nextReminderMillis(nowMillis = now, zone = zone, hour = 9, minute = 0, meridiem = "am")
                scheduler.schedule(
                    ReminderSpec(
                        text = reorderReminderText(reminder),
                        atMillis = at,
                        hour = 9,
                        minute = 0,
                    ),
                )
            }
        }
    }
}
