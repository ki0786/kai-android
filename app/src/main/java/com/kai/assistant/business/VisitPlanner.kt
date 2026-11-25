package com.kai.assistant.business

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Picks showroom visit times. Every time it returns is inside the owner's opening hours. */
object VisitPlanner {
    /** Earliest a same-day visit is offered, so the showroom has notice. */
    const val LEAD_MINUTES = 120L
    const val SLOT_MINUTES = 30
    /** A visit never starts in the last hour before closing. */
    const val BEFORE_CLOSE_MINUTES = 60
    const val HORIZON_DAYS = 14L
    private const val MAX_AHEAD_DAYS = 30L

    /** The next time KAI offers when the customer names none. Null when no hours are set. */
    fun nextSlot(now: LocalDateTime, hours: OpeningHours): LocalDateTime? {
        if (!hours.isSet) return null
        val earliest = roundUp(now.plusMinutes(LEAD_MINUTES))
        for (offset in 0..HORIZON_DAYS) {
            val day = now.toLocalDate().plusDays(offset)
            slotOn(day, hours, notBefore = earliest)?.let { return it }
        }
        return null
    }

    /**
     * The time the customer asked for when it is inside opening hours and still ahead.
     * A day with no time gets that day's first slot. Null means "offer [nextSlot] instead".
     */
    fun requested(date: LocalDate?, time: LocalTime?, now: LocalDateTime, hours: OpeningHours): LocalDateTime? {
        if (!hours.isSet) return null
        if (date == null && time == null) return null
        if (time == null) {
            return slotOn(date!!, hours, notBefore = roundUp(now.plusMinutes(LEAD_MINUTES)))
        }
        val days = if (date != null) listOf(date) else (0..7L).map { now.toLocalDate().plusDays(it) }
        return days.map { it.atTime(time) }.firstOrNull { accepts(it, now, hours) }
    }

    /** True when a visit starting at [at] is in the future and inside opening hours. */
    fun accepts(at: LocalDateTime, now: LocalDateTime, hours: OpeningHours): Boolean {
        if (at.isBefore(now.plusMinutes(SLOT_MINUTES.toLong()))) return false
        if (at.toLocalDate().isAfter(now.toLocalDate().plusDays(MAX_AHEAD_DAYS))) return false
        val span = hours.forDay(at.dayOfWeek) ?: return false
        val minute = at.hour * 60 + at.minute
        return minute >= span.openMinute && minute <= span.closeMinute - SLOT_MINUTES
    }

    private fun slotOn(day: LocalDate, hours: OpeningHours, notBefore: LocalDateTime): LocalDateTime? {
        val span = hours.forDay(day.dayOfWeek) ?: return null
        val latest = span.closeMinute - BEFORE_CLOSE_MINUTES
        val start = when {
            day.isBefore(notBefore.toLocalDate()) -> return null
            day == notBefore.toLocalDate() ->
                maxOf(span.openMinute, notBefore.hour * 60 + notBefore.minute)
            // Not the minute the shutter goes up.
            else -> minOf(span.openMinute + 60, latest)
        }
        if (start > latest || start < span.openMinute) return null
        return day.atStartOfDay().plusMinutes(start.toLong())
    }

    private fun roundUp(at: LocalDateTime): LocalDateTime {
        val clean = at.withSecond(0).withNano(0)
        val base = if (clean.isBefore(at)) clean.plusMinutes(1) else clean
        val over = base.minute % SLOT_MINUTES
        return if (over == 0) base else base.plusMinutes((SLOT_MINUTES - over).toLong())
    }
}
