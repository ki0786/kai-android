package com.kai.assistant.business

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What the notification listener should do after [EnquiryEngine.handle]. */
sealed class EngineAction {
    data object Skip : EngineAction()

    data class Reply(
        val chatKey: String,
        val customerName: String,
        val text: String,
        val alertOwner: Boolean,
        val alertReason: String = "",
    ) : EngineAction()

    /** Owner is handling this chat; still surface the new customer line. */
    data class AlertOnly(
        val chatKey: String,
        val customerName: String,
        val customerText: String,
        val reason: String,
    ) : EngineAction()
}

/**
 * Turns one filtered WhatsApp Business message into a reply (or an owner alert).
 * Pure of Android so the rules can be unit-tested with a fake [EnquiryBrain].
 */
class EnquiryEngine(
    private val infoStore: () -> BusinessInfo,
    private val repository: EnquiryRepository,
    private val brain: EnquiryBrain?,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ratePolicy: RatePolicy = RatePolicy(),
    /** Epoch ms when auto-reply was last switched on. Older notification lines are ignored. */
    private val enabledAtMs: () -> Long = { 0L },
) {
    suspend fun handle(facts: NotificationFacts): EngineAction {
        val nowMs = clock()
        val existing = repository.get(ChatFilter.chatKey(facts))
        val lastSeenFloor = maxOf(
            existing?.lastSeenAtMs ?: 0L,
            enabledAtMs(),
        )
        val decision = ChatFilter.evaluate(
            facts = facts,
            lastSeenAtMs = lastSeenFloor,
            lastSeenText = existing?.lastCustomerText,
            recentKaiReplies = existing?.recentKaiReplies(nowMs).orEmpty(),
        )
        return when (decision) {
            is FilterDecision.Ignore -> {
                if (decision.reason == IgnoreReason.OwnMessage) {
                    noteOwnMessage(decision.ownText, facts, nowMs)
                }
                EngineAction.Skip
            }
            is FilterDecision.Process -> process(decision, nowMs)
        }
    }

    private suspend fun process(decision: FilterDecision.Process, nowMs: Long): EngineAction {
        val info = infoStore()
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        val previous = repository.get(decision.chatKey)
        val language = detectReplyLanguage(decision.text)
        var enquiry = (previous ?: Enquiry(
            chatKey = decision.chatKey,
            customerName = decision.customerName,
            firstAtMs = nowMs,
        )).copy(
            customerName = decision.customerName.ifBlank { previous?.customerName.orEmpty() },
            language = language,
            lastCustomerAtMs = nowMs,
            lastSeenAtMs = decision.newestAtMs,
            done = false,
        ).withMessage(Speaker.Customer, decision.text, nowMs)

        if (enquiry.ownerHandling) {
            repository.put(enquiry)
            return EngineAction.AlertOnly(
                chatKey = enquiry.chatKey,
                customerName = enquiry.customerName,
                customerText = decision.text,
                reason = "You are handling this chat",
            )
        }

        if (LocalEnquiryRules.needsOwner(decision.text) || LocalEnquiryRules.isMediaOnly(decision.text)) {
            return holdAndAlert(
                enquiry = enquiry,
                info = info,
                language = language,
                nowMs = nowMs,
                reason = if (LocalEnquiryRules.isMediaOnly(decision.text)) {
                    "Media-only message"
                } else {
                    "Needs you"
                },
            )
        }

        // Customer confirming a proposed visit.
        if (enquiry.visitAtMs != null && !enquiry.visitConfirmed && LocalEnquiryRules.isYes(decision.text)) {
            val slot = LocalDateTime.ofInstant(Instant.ofEpochMilli(enquiry.visitAtMs), zone)
            val reply = ReplyTemplates.visitConfirmed(info, language, slot)
            return sendAnswer(
                enquiry = enquiry.copy(visitConfirmed = true, needsOwner = false, needsOwnerReason = ""),
                reply = reply,
                nowMs = nowMs,
                alertOwner = false,
            )
        }

        val rate = ReplyRateLimiter.check(
            nowMs = nowMs,
            chatReplyTimes = enquiry.kaiReplyTimes,
            allReplyTimes = repository.all().flatMap { it.kaiReplyTimes },
            policy = ratePolicy,
        )
        if (rate is ReplyRateLimiter.Decision.Blocked) {
            repository.put(enquiry)
            return EngineAction.AlertOnly(
                chatKey = enquiry.chatKey,
                customerName = enquiry.customerName,
                customerText = decision.text,
                reason = rate.reason,
            )
        }

        val nextSlot = VisitPlanner.nextSlot(now, info.hours)
        val brainAnswer = runCatching {
            brain?.ask(
                BrainRequest(
                    info = info,
                    customerText = decision.text,
                    history = enquiry.messages.dropLast(1),
                    now = now,
                    nextSlot = nextSlot,
                ),
            )
        }.getOrNull()

        return if (brainAnswer != null) {
            applyBrain(enquiry, info, language, now, nowMs, brainAnswer, nextSlot)
        } else {
            applyOffline(enquiry, info, language, nowMs)
        }
    }

    private fun applyBrain(
        enquiry: Enquiry,
        info: BusinessInfo,
        languageHint: ReplyLanguage,
        now: LocalDateTime,
        nowMs: Long,
        answer: BrainAnswer,
        nextSlot: LocalDateTime?,
    ): EngineAction {
        val language = answer.language ?: languageHint
        if (LocalEnquiryRules.needsOwner(enquiry.lastCustomerText)) {
            return holdAndAlert(enquiry, info, language, nowMs, "Needs you")
        }
        return when (answer.category) {
            EnquiryCategory.CommonQuestion -> {
                val topics = answer.topics.filter { info.has(it) }.toSet()
                if (topics.isEmpty() || topics.size != answer.topics.size) {
                    return holdAndAlert(enquiry, info, language, nowMs, "Missing business info")
                }
                val candidate = answer.reply.trim()
                val guarded = AnswerGuard.check(candidate, info, topics)
                val reply = if (guarded.ok && scriptMatches(candidate, language)) {
                    candidate
                } else {
                    ReplyTemplates.answer(topics, info, language, enquiry.lastCustomerText)
                        ?: return holdAndAlert(enquiry, info, language, nowMs, "Could not answer safely")
                }
                sendAnswer(
                    enquiry = enquiry.copy(
                        language = language,
                        needsOwner = false,
                        needsOwnerReason = "",
                    ),
                    reply = reply,
                    nowMs = nowMs,
                    alertOwner = false,
                )
            }
            EnquiryCategory.VisitRequest -> {
                val requested = VisitPlanner.requested(
                    date = answer.visitDate,
                    time = answer.visitTime,
                    now = now,
                    hours = info.hours,
                )
                val slot = requested ?: nextSlot
                    ?: return holdAndAlert(enquiry, info, language, nowMs, "No opening hours set")
                val candidate = answer.reply.trim()
                val guarded = AnswerGuard.check(candidate, info, visit = slot)
                val reply = if (guarded.ok && scriptMatches(candidate, language)) {
                    candidate
                } else {
                    ReplyTemplates.visitProposal(info, language, slot)
                }
                val visitMs = slot.atZone(zone).toInstant().toEpochMilli()
                sendAnswer(
                    enquiry = enquiry.copy(
                        language = language,
                        visitAtMs = visitMs,
                        visitConfirmed = false,
                        needsOwner = false,
                        needsOwnerReason = "",
                    ),
                    reply = reply,
                    nowMs = nowMs,
                    alertOwner = false,
                )
            }
            EnquiryCategory.Other -> holdAndAlert(enquiry, info, language, nowMs, "Needs you")
        }
    }

    private fun applyOffline(
        enquiry: Enquiry,
        info: BusinessInfo,
        language: ReplyLanguage,
        nowMs: Long,
    ): EngineAction {
        val topics = LocalEnquiryRules.offlineTopics(enquiry.lastCustomerText)
        if (topics.isNotEmpty()) {
            val reply = ReplyTemplates.answer(topics, info, language, enquiry.lastCustomerText)
            if (reply != null) {
                val guarded = AnswerGuard.check(reply, info, topics)
                if (guarded.ok) {
                    return sendAnswer(
                        enquiry = enquiry.copy(
                            language = language,
                            needsOwner = false,
                            needsOwnerReason = "",
                        ),
                        reply = reply,
                        nowMs = nowMs,
                        alertOwner = false,
                    )
                }
            }
        }
        return holdAndAlert(enquiry, info, language, nowMs, "Offline — needs you")
    }

    private fun holdAndAlert(
        enquiry: Enquiry,
        info: BusinessInfo,
        language: ReplyLanguage,
        nowMs: Long,
        reason: String,
    ): EngineAction {
        val updated = enquiry.copy(needsOwner = true, needsOwnerReason = reason, language = language)
        val canHold = ReplyRateLimiter.holdingAllowed(nowMs, updated.lastHoldingAtMs, ratePolicy)
        if (!canHold) {
            repository.put(updated)
            return EngineAction.AlertOnly(
                chatKey = updated.chatKey,
                customerName = updated.customerName,
                customerText = updated.lastCustomerText,
                reason = reason,
            )
        }
        val holding = ReplyTemplates.holding(info, language)
        return sendAnswer(
            enquiry = updated.copy(lastHoldingAtMs = nowMs),
            reply = holding,
            nowMs = nowMs,
            alertOwner = true,
            alertReason = reason,
        )
    }

    private fun sendAnswer(
        enquiry: Enquiry,
        reply: String,
        nowMs: Long,
        alertOwner: Boolean,
        alertReason: String = "",
    ): EngineAction {
        val withReply = enquiry
            .withMessage(Speaker.Kai, reply, nowMs)
            .copy(kaiReplyTimes = enquiry.kaiReplyTimes + nowMs)
        repository.put(withReply)
        return EngineAction.Reply(
            chatKey = withReply.chatKey,
            customerName = withReply.customerName,
            text = reply,
            alertOwner = alertOwner,
            alertReason = alertReason,
        )
    }

    /**
     * A line from the phone user that is not an echo of KAI means the owner took over.
     * KAI stops auto-replying in that chat until the owner re-enables it in the inbox.
     */
    private fun noteOwnMessage(ownText: String?, facts: NotificationFacts, nowMs: Long) {
        val text = ownText?.trim().orEmpty()
        if (text.isEmpty()) return
        val key = ChatFilter.chatKey(facts)
        val existing = repository.get(key) ?: return
        if (existing.recentKaiReplies(nowMs).any { ChatFilter.isEcho(it, text) }) return
        repository.put(
            existing
                .withMessage(Speaker.Owner, text, nowMs)
                .copy(ownerHandling = true),
        )
    }
}
