package com.kai.assistant.business

/** Limits that keep the business number from looking like a bot. */
data class RatePolicy(
    /** Two KAI replies in one chat are never closer than this. */
    val minGapMs: Long = 8_000L,
    val maxPerChatPerHour: Int = 8,
    val maxPerChatPerDay: Int = 25,
    /** Across every chat, in case many chats arrive at once. */
    val maxAllChatsPerHour: Int = 60,
    /** The "we will reply shortly" line is not repeated in a chat within this time. */
    val holdingCooldownMs: Long = 6 * HOUR_MS,
) {
    companion object {
        const val HOUR_MS = 3_600_000L
        const val DAY_MS = 24 * HOUR_MS
    }
}

object ReplyRateLimiter {
    sealed class Decision {
        data object Allowed : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    /**
     * [chatReplyTimes] are the times KAI replied in this chat; [allReplyTimes] in every chat.
     */
    fun check(
        nowMs: Long,
        chatReplyTimes: List<Long>,
        allReplyTimes: List<Long>,
        policy: RatePolicy = RatePolicy(),
    ): Decision {
        val last = chatReplyTimes.maxOrNull()
        if (last != null && nowMs - last < policy.minGapMs) {
            return Decision.Blocked("KAI replied in this chat a few seconds ago")
        }
        if (countSince(chatReplyTimes, nowMs - RatePolicy.HOUR_MS) >= policy.maxPerChatPerHour) {
            return Decision.Blocked("KAI already sent ${policy.maxPerChatPerHour} replies in this chat this hour")
        }
        if (countSince(chatReplyTimes, nowMs - RatePolicy.DAY_MS) >= policy.maxPerChatPerDay) {
            return Decision.Blocked("KAI already sent ${policy.maxPerChatPerDay} replies in this chat today")
        }
        if (countSince(allReplyTimes, nowMs - RatePolicy.HOUR_MS) >= policy.maxAllChatsPerHour) {
            return Decision.Blocked("KAI already sent ${policy.maxAllChatsPerHour} replies this hour")
        }
        return Decision.Allowed
    }

    fun holdingAllowed(nowMs: Long, lastHoldingAtMs: Long, policy: RatePolicy = RatePolicy()): Boolean =
        lastHoldingAtMs == 0L || nowMs - lastHoldingAtMs >= policy.holdingCooldownMs

    private fun countSince(times: List<Long>, sinceMs: Long): Int = times.count { it > sinceMs }
}
