package com.kai.assistant.business

const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

/** One message line from a chat notification. */
data class NotifiedMessage(
    val text: String,
    val timestampMs: Long,
    /** Null for the phone's own user, as Android's messaging style defines it. */
    val sender: String?,
)

/**
 * The parts of a notification the filter needs, copied out so the rules can be
 * tested without Android. Built only for WhatsApp Business notifications.
 */
data class NotificationFacts(
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAtMs: Long,
    val isGroupSummary: Boolean = false,
    val isOngoing: Boolean = false,
    val category: String? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean = false,
    /** Shortcut id and tag. WhatsApp puts the chat's id in one of them on some versions. */
    val ids: List<String> = emptyList(),
    val hasReplyAction: Boolean = false,
    val messages: List<NotifiedMessage> = emptyList(),
    /** The phone user's own display name in the messaging style, if any. */
    val selfName: String? = null,
)

enum class IgnoreReason {
    OtherApp, Summary, NotAMessage, Group, StatusOrBroadcast, NoReplyAction, Empty, OwnMessage, AlreadySeen,
}

sealed class FilterDecision {
    data class Ignore(val reason: IgnoreReason, val ownText: String? = null) : FilterDecision()

    /** [text] is every unseen customer line, oldest first. */
    data class Process(
        val chatKey: String,
        val customerName: String,
        val text: String,
        val newestAtMs: Long,
    ) : FilterDecision()
}

/**
 * Decides whether KAI may answer a notification. Only a one-to-one WhatsApp Business
 * chat whose newest line was written by the customer gets through.
 */
object ChatFilter {
    private val selfLabels = setOf("you", "me", "आप", "तुम", "मैं")
    private val systemTitles = setOf("whatsapp business", "whatsapp", "wa business", "status", "updates")
    private val notMessages = listOf(
        Regex("""^(this message was deleted|you deleted this message|waiting for this message.*)$"""),
        Regex("""^(missed|incoming|ongoing|calling|ringing).*\b(call|calls)\b.*$"""),
        Regex("""^(checking for new messages|backup in progress.*|you may have new messages|restoring.*)$"""),
        Regex("""^\d+ (new )?messages?( from \d+ chats?)?$"""),
        Regex("""^यह संदेश हटा दिया गया.*$"""),
    )
    private val countSuffix = Regex("""\s*\(\d+\s+[^)]*\)\s*$""")
    private val allowedCategories = setOf("msg")

    private val personalId = Regex("""[\w.+-]+@(s\.whatsapp\.net|c\.us|lid)""")

    /** The chat's own id when the notification carries one, else the name the owner sees. */
    fun chatKey(facts: NotificationFacts): String {
        val id = facts.ids.firstNotNullOfOrNull { personalId.find(it)?.value }
        return if (id != null) "id:$id" else "name:${customerName(facts).lowercase()}"
    }

    fun customerName(facts: NotificationFacts): String =
        facts.title.orEmpty().replace(countSuffix, "").trim()

    /**
     * @param lastSeenAtMs newest customer line already handled in this chat, or the
     *   time auto-reply was switched on if that is later: older lines are not KAI's to answer
     * @param recentKaiReplies what KAI itself sent in this chat a short while ago
     */
    fun evaluate(
        facts: NotificationFacts,
        lastSeenAtMs: Long = 0L,
        lastSeenText: String? = null,
        recentKaiReplies: List<String> = emptyList(),
    ): FilterDecision {
        if (facts.packageName != WHATSAPP_BUSINESS_PACKAGE) return ignore(IgnoreReason.OtherApp)
        if (facts.isGroupSummary) return ignore(IgnoreReason.Summary)
        if (facts.isOngoing) return ignore(IgnoreReason.NotAMessage)
        if (facts.category != null && facts.category !in allowedCategories) return ignore(IgnoreReason.NotAMessage)

        val title = customerName(facts)
        if (isStatusOrBroadcast(facts, title)) return ignore(IgnoreReason.StatusOrBroadcast)
        if (isGroup(facts, title)) return ignore(IgnoreReason.Group)
        if (!facts.hasReplyAction) return ignore(IgnoreReason.NoReplyAction)
        if (title.isEmpty()) return ignore(IgnoreReason.Empty)

        val lines = facts.messages.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return fromPlainText(facts, title, lastSeenAtMs, lastSeenText, recentKaiReplies)

        val newest = lines.maxByOrNull { it.timestampMs }!!
        if (isOwn(newest, facts, title, recentKaiReplies)) {
            return FilterDecision.Ignore(IgnoreReason.OwnMessage, ownText = newest.text)
        }
        val unseen = lines
            .filter { it.timestampMs > lastSeenAtMs }
            .filterNot { isOwn(it, facts, title, recentKaiReplies) }
            .filterNot { isSystemLine(it.text) }
            .sortedBy { it.timestampMs }
        if (unseen.isEmpty()) return ignore(IgnoreReason.AlreadySeen)
        return FilterDecision.Process(
            chatKey = chatKey(facts),
            customerName = title,
            text = unseen.joinToString("\n") { it.text.trim() },
            newestAtMs = unseen.last().timestampMs,
        )
    }

    private fun fromPlainText(
        facts: NotificationFacts,
        title: String,
        lastSeenAtMs: Long,
        lastSeenText: String?,
        recentKaiReplies: List<String>,
    ): FilterDecision {
        val text = facts.text?.trim().orEmpty()
        if (text.isEmpty() || isSystemLine(text)) return ignore(IgnoreReason.Empty)
        if (recentKaiReplies.any { isEcho(it, text) }) {
            return FilterDecision.Ignore(IgnoreReason.OwnMessage, ownText = text)
        }
        // Without message times, a repost of the same line can't be told from a new one.
        if (facts.postedAtMs <= lastSeenAtMs || (lastSeenText != null && sameText(lastSeenText, text))) {
            return ignore(IgnoreReason.AlreadySeen)
        }
        return FilterDecision.Process(chatKey(facts), title, text, facts.postedAtMs)
    }

    private fun isGroup(facts: NotificationFacts, title: String): Boolean {
        if (facts.isGroupConversation) return true
        if (!facts.conversationTitle.isNullOrBlank()) return true
        if (facts.ids.any { it.contains("@g.us") }) return true
        // "Family: Asha" and "Asha @ Family" are how group lines are titled.
        if (title.contains(": ") || title.contains(" @ ")) return true
        val senders = facts.messages.mapNotNull { it.sender?.trim()?.lowercase() }
            .filter { it.isNotEmpty() && it !in selfLabels && it != facts.selfName?.trim()?.lowercase() }
            .toSet()
        return senders.size > 1
    }

    private fun isStatusOrBroadcast(facts: NotificationFacts, title: String): Boolean {
        if (facts.ids.any { it.contains("@broadcast") || it.contains("@newsletter") || it.contains("status@") }) {
            return true
        }
        return title.lowercase() in systemTitles
    }

    private fun isOwn(
        line: NotifiedMessage,
        facts: NotificationFacts,
        title: String,
        recentKaiReplies: List<String>,
    ): Boolean {
        if (recentKaiReplies.any { isEcho(it, line.text) }) return true
        val sender = line.sender?.trim()?.lowercase()
        // Some WhatsApp versions leave the customer's name off their own lines, so a
        // missing sender only counts as "me" when other lines do carry a sender.
        if (sender == null) return facts.messages.any { it.sender != null }
        if (sender in selfLabels) return true
        val self = facts.selfName?.trim()?.lowercase()
        return !self.isNullOrEmpty() && sender == self && sender != title.lowercase()
    }

    private fun isSystemLine(text: String): Boolean {
        val lower = text.trim().lowercase()
        return notMessages.any { it.matches(lower) }
    }

    internal fun sameText(a: String, b: String): Boolean = squash(a) == squash(b)

    /** True when [seen] is what KAI [sent], allowing for the notification cutting it short. */
    internal fun isEcho(sent: String, seen: String): Boolean {
        val a = squash(sent)
        val b = squash(seen).trimEnd('…', '.')
        if (a == squash(seen)) return true
        return b.length >= 15 && a.startsWith(b)
    }

    private fun squash(text: String): String = text.replace(Regex("""\s+"""), " ").trim()

    private fun ignore(reason: IgnoreReason) = FilterDecision.Ignore(reason)
}
