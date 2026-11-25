package com.kai.assistant.business

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFilterTest {
    private fun facts(
        packageName: String = WHATSAPP_BUSINESS_PACKAGE,
        title: String? = "Asha",
        text: String? = "Where is your shop?",
        messages: List<NotifiedMessage> = emptyList(),
        ids: List<String> = listOf("919876543210@s.whatsapp.net"),
        hasReplyAction: Boolean = true,
        isGroupSummary: Boolean = false,
        isGroupConversation: Boolean = false,
        conversationTitle: String? = null,
        category: String? = "msg",
        postedAtMs: Long = 1_000L,
        selfName: String? = "Sagar Furniture",
    ) = NotificationFacts(
        packageName = packageName,
        title = title,
        text = text,
        postedAtMs = postedAtMs,
        isGroupSummary = isGroupSummary,
        category = category,
        conversationTitle = conversationTitle,
        isGroupConversation = isGroupConversation,
        ids = ids,
        hasReplyAction = hasReplyAction,
        messages = messages,
        selfName = selfName,
    )

    @Test
    fun ignoresOtherApps() {
        val decision = ChatFilter.evaluate(facts(packageName = "com.whatsapp"))
        assertEquals(IgnoreReason.OtherApp, (decision as FilterDecision.Ignore).reason)
    }

    @Test
    fun ignoresGroups() {
        val group = ChatFilter.evaluate(
            facts(
                title = "Family: Asha",
                ids = listOf("120363@g.us"),
                isGroupConversation = true,
            ),
        )
        assertEquals(IgnoreReason.Group, (group as FilterDecision.Ignore).reason)
    }

    @Test
    fun ignoresStatus() {
        val status = ChatFilter.evaluate(
            facts(title = "Status", ids = listOf("status@broadcast")),
        )
        assertEquals(IgnoreReason.StatusOrBroadcast, (status as FilterDecision.Ignore).reason)
    }

    @Test
    fun ignoresWithoutReplyAction() {
        val decision = ChatFilter.evaluate(facts(hasReplyAction = false))
        assertEquals(IgnoreReason.NoReplyAction, (decision as FilterDecision.Ignore).reason)
    }

    @Test
    fun ignoresOwnAndEcho() {
        val own = ChatFilter.evaluate(
            facts(
                messages = listOf(
                    NotifiedMessage("Thanks for visiting", 2_000L, sender = null),
                ),
            ),
            recentKaiReplies = listOf("Thanks for visiting our showroom tomorrow"),
        )
        assertEquals(IgnoreReason.OwnMessage, (own as FilterDecision.Ignore).reason)
    }

    @Test
    fun ignoresAlreadySeen() {
        val decision = ChatFilter.evaluate(
            facts(
                messages = listOf(NotifiedMessage("hi", 500L, sender = "Asha")),
            ),
            lastSeenAtMs = 1_000L,
        )
        assertEquals(IgnoreReason.AlreadySeen, (decision as FilterDecision.Ignore).reason)
    }

    @Test
    fun processesCustomerMessage() {
        val decision = ChatFilter.evaluate(
            facts(
                messages = listOf(NotifiedMessage("Address please", 2_000L, sender = "Asha")),
            ),
            lastSeenAtMs = 100L,
        )
        assertTrue(decision is FilterDecision.Process)
        decision as FilterDecision.Process
        assertEquals("Address please", decision.text)
        assertTrue(decision.chatKey.startsWith("id:"))
    }
}
