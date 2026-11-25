package com.kai.assistant.business

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyRateLimiterTest {
    private val now = 1_000_000L

    @Test
    fun enforcesMinGap() {
        val result = ReplyRateLimiter.check(
            nowMs = now,
            chatReplyTimes = listOf(now - 3_000L),
            allReplyTimes = listOf(now - 3_000L),
        )
        assertTrue(result is ReplyRateLimiter.Decision.Blocked)
    }

    @Test
    fun allowsAfterGap() {
        val result = ReplyRateLimiter.check(
            nowMs = now,
            chatReplyTimes = listOf(now - 9_000L),
            allReplyTimes = listOf(now - 9_000L),
        )
        assertTrue(result is ReplyRateLimiter.Decision.Allowed)
    }

    @Test
    fun enforcesPerChatHour() {
        val times = (1..8).map { now - it * 60_000L }
        val result = ReplyRateLimiter.check(
            nowMs = now,
            chatReplyTimes = times,
            allReplyTimes = times,
        )
        assertTrue(result is ReplyRateLimiter.Decision.Blocked)
    }

    @Test
    fun enforcesGlobalHour() {
        val times = (1..60).map { now - it * 1_000L }
        val result = ReplyRateLimiter.check(
            nowMs = now,
            chatReplyTimes = emptyList(),
            allReplyTimes = times,
        )
        assertTrue(result is ReplyRateLimiter.Decision.Blocked)
    }

    @Test
    fun holdingCooldown() {
        assertTrue(ReplyRateLimiter.holdingAllowed(now, 0L))
        assertFalse(ReplyRateLimiter.holdingAllowed(now, now - 60_000L))
        assertTrue(ReplyRateLimiter.holdingAllowed(now, now - 7 * 3_600_000L))
    }
}
