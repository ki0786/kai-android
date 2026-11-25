package com.kai.assistant.business

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

enum class Speaker { Customer, Kai, Owner }

data class EnquiryMessage(val from: Speaker, val text: String, val atMs: Long)

/** What the inbox shows for one chat. */
enum class EnquiryStatus(val label: String) {
    NeedsYou("Needs you"),
    FollowUpDue("Follow-up due"),
    VisitBooked("Visit booked"),
    AnsweredByKai("Answered by KAI"),
    HandledByYou("Handled by you"),
    Done("Done"),
}

/** One customer chat, kept on this phone only. */
data class Enquiry(
    val chatKey: String,
    val customerName: String,
    val messages: List<EnquiryMessage> = emptyList(),
    val firstAtMs: Long = 0L,
    val lastCustomerAtMs: Long = 0L,
    /** Time of the newest customer line already handled, so a repost isn't answered twice. */
    val lastSeenAtMs: Long = 0L,
    val language: ReplyLanguage = ReplyLanguage.English,
    val needsOwner: Boolean = false,
    val needsOwnerReason: String = "",
    val visitAtMs: Long? = null,
    val visitConfirmed: Boolean = false,
    /** The owner is talking in this chat himself. KAI sends nothing here while true. */
    val ownerHandling: Boolean = false,
    val kaiReplyTimes: List<Long> = emptyList(),
    val lastHoldingAtMs: Long = 0L,
    /** The quiet period the owner was already reminded about. */
    val followUpNotifiedForMs: Long = 0L,
    val done: Boolean = false,
) {
    val lastCustomerText: String
        get() = messages.lastOrNull { it.from == Speaker.Customer }?.text.orEmpty()

    fun status(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): EnquiryStatus = when {
        done -> EnquiryStatus.Done
        needsOwner -> EnquiryStatus.NeedsYou
        FollowUpPolicy.isDue(this, nowMs, zone) -> EnquiryStatus.FollowUpDue
        visitAtMs != null -> EnquiryStatus.VisitBooked
        ownerHandling -> EnquiryStatus.HandledByYou
        else -> EnquiryStatus.AnsweredByKai
    }

    fun withMessage(from: Speaker, text: String, atMs: Long): Enquiry =
        copy(messages = (messages + EnquiryMessage(from, text, atMs)).takeLast(MAX_MESSAGES))

    /** What KAI sent here recently, to recognise its own lines when WhatsApp shows them back. */
    fun recentKaiReplies(nowMs: Long): List<String> =
        messages.filter { it.from == Speaker.Kai && nowMs - it.atMs < ECHO_WINDOW_MS }.map { it.text }

    fun toJson(): JSONObject = JSONObject().apply {
        put("chatKey", chatKey)
        put("customerName", customerName)
        put("firstAtMs", firstAtMs)
        put("lastCustomerAtMs", lastCustomerAtMs)
        put("lastSeenAtMs", lastSeenAtMs)
        put("language", language.name)
        put("needsOwner", needsOwner)
        put("needsOwnerReason", needsOwnerReason)
        if (visitAtMs != null) put("visitAtMs", visitAtMs)
        put("visitConfirmed", visitConfirmed)
        put("ownerHandling", ownerHandling)
        put("kaiReplyTimes", JSONArray(kaiReplyTimes))
        put("lastHoldingAtMs", lastHoldingAtMs)
        put("followUpNotifiedForMs", followUpNotifiedForMs)
        put("done", done)
        put(
            "messages",
            JSONArray().also { array ->
                messages.forEach {
                    array.put(JSONObject().put("from", it.from.name).put("text", it.text).put("atMs", it.atMs))
                }
            },
        )
    }

    companion object {
        const val MAX_MESSAGES = 30
        const val ECHO_WINDOW_MS = 30 * 60_000L

        fun fromJson(json: JSONObject): Enquiry? {
            val key = json.optString("chatKey")
            if (key.isBlank()) return null
            val messages = mutableListOf<EnquiryMessage>()
            val array = json.optJSONArray("messages") ?: JSONArray()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val from = runCatching { Speaker.valueOf(item.optString("from")) }.getOrNull() ?: continue
                messages += EnquiryMessage(from, item.optString("text"), item.optLong("atMs"))
            }
            val times = mutableListOf<Long>()
            val replyTimes = json.optJSONArray("kaiReplyTimes") ?: JSONArray()
            for (index in 0 until replyTimes.length()) times += replyTimes.optLong(index)
            return Enquiry(
                chatKey = key,
                customerName = json.optString("customerName"),
                messages = messages,
                firstAtMs = json.optLong("firstAtMs"),
                lastCustomerAtMs = json.optLong("lastCustomerAtMs"),
                lastSeenAtMs = json.optLong("lastSeenAtMs"),
                language = runCatching { ReplyLanguage.valueOf(json.optString("language")) }
                    .getOrDefault(ReplyLanguage.English),
                needsOwner = json.optBoolean("needsOwner"),
                needsOwnerReason = json.optString("needsOwnerReason"),
                visitAtMs = if (json.has("visitAtMs")) json.optLong("visitAtMs") else null,
                visitConfirmed = json.optBoolean("visitConfirmed"),
                ownerHandling = json.optBoolean("ownerHandling"),
                kaiReplyTimes = times,
                lastHoldingAtMs = json.optLong("lastHoldingAtMs"),
                followUpNotifiedForMs = json.optLong("followUpNotifiedForMs"),
                done = json.optBoolean("done"),
            )
        }
    }
}

/**
 * When to remind the owner that a customer has gone quiet. The reminder goes to the
 * owner only; KAI never writes to a customer who has not just written to it.
 */
object FollowUpPolicy {
    const val QUIET_MS = 24 * 3_600_000L
    private const val DAY_START_HOUR = 9
    private const val DAY_END_HOUR = 20

    /** The moment the customer's silence is counted from. */
    fun quietSince(enquiry: Enquiry): Long {
        val visit = enquiry.visitAtMs
        return if (enquiry.visitConfirmed && visit != null && visit > enquiry.lastCustomerAtMs) {
            visit
        } else {
            enquiry.lastCustomerAtMs
        }
    }

    /** A day after the customer went quiet, moved into daytime. Null when no reminder applies. */
    fun dueAt(enquiry: Enquiry, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (enquiry.done || enquiry.lastCustomerAtMs == 0L) return null
        val raw = ZonedDateTime.ofInstant(Instant.ofEpochMilli(quietSince(enquiry) + QUIET_MS), zone)
        val daytime = when {
            raw.hour < DAY_START_HOUR -> raw.withHour(DAY_START_HOUR).withMinute(0).withSecond(0).withNano(0)
            raw.hour >= DAY_END_HOUR ->
                raw.plusDays(1).withHour(DAY_START_HOUR).withMinute(0).withSecond(0).withNano(0)
            else -> raw
        }
        return daytime.toInstant().toEpochMilli()
    }

    fun isDue(enquiry: Enquiry, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val due = dueAt(enquiry, zone) ?: return false
        return nowMs >= due
    }

    /** Due chats the owner has not been reminded about for this quiet period. */
    fun toRemind(enquiries: List<Enquiry>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): List<Enquiry> =
        enquiries.filter { isDue(it, nowMs, zone) && it.followUpNotifiedForMs != quietSince(it) }

    /** The next time a reminder could become due, for scheduling one wake-up. */
    fun nextDueAt(enquiries: List<Enquiry>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long? =
        enquiries
            .filter { it.followUpNotifiedForMs != quietSince(it) }
            .mapNotNull { dueAt(it, zone) }
            .filter { it > nowMs }
            .minOrNull()
}
