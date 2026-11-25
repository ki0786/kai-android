package com.kai.assistant.business

import com.kai.assistant.books.BooksAction
import com.kai.assistant.books.BooksSnapshot
import com.kai.assistant.books.formatRupees
import com.kai.assistant.books.payableRupees
import com.kai.assistant.books.recordedAdvanceRupees

/** One order to a supplier the owner already tagged. [text] has quantities only. */
data class OutboundOrder(
    val supplierName: String,
    val supplierChatKey: String,
    val digits: String,
    val text: String,
    val record: BooksAction.PurchaseOrder,
)

/** What the notification listener should do for a labour or supplier chat. */
sealed class ManagerAction {
    data object Skip : ManagerAction()

    data class Alert(
        val chatKey: String,
        val title: String,
        val body: String,
    ) : ManagerAction()

    data class SendOrders(
        val chatKey: String,
        val labourName: String,
        val orders: List<OutboundOrder>,
        /** Materials that were not ordered, so the owner can see them. */
        val skipped: String,
    ) : ManagerAction()

    /** Tell the owner what to pay. This never sends a WhatsApp message or a payment. */
    data class NotifyPayable(
        val chatKey: String,
        val supplierName: String,
        val billRupees: Long,
        val advanceRupees: Long,
        val payableRupees: Long,
        val notice: String,
        val record: BooksAction.SupplierBill,
    ) : ManagerAction()
}

enum class IncomingRoute { Manager, Enquiry, Ignore }

/** Tagged labour and supplier chats are the manager. Everyone else stays on the enquiry path. */
fun routeWhatsAppBusiness(hasPartyTag: Boolean, autoReplyOn: Boolean, businessReady: Boolean): IncomingRoute =
    when {
        hasPartyTag -> IncomingRoute.Manager
        autoReplyOn && businessReady -> IncomingRoute.Enquiry
        else -> IncomingRoute.Ignore
    }

/**
 * Workshop manager for chats the owner tagged Labour or Supplier.
 * It does not look at any other chat. It does not pay, and it does not message a number
 * that is not on a tagged supplier chat.
 */
class ManagerEngine(
    private val tags: PartyTagRepository,
    private val books: () -> BooksSnapshot,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ratePolicy: RatePolicy = RatePolicy(),
) {
    fun handle(facts: NotificationFacts): ManagerAction {
        val chatKey = ChatFilter.chatKey(facts)
        val name = ChatFilter.customerName(facts)
        val tag = tags.find(chatKey, name) ?: return ManagerAction.Skip
        val decision = ChatFilter.evaluate(
            facts = facts,
            lastSeenAtMs = tag.lastSeenAtMs,
            lastSeenText = tag.lastText.ifBlank { null },
            recentKaiReplies = emptyList(),
        )
        return when (decision) {
            is FilterDecision.Ignore -> {
                if (decision.reason == IgnoreReason.OwnMessage) noteOwner(tag, decision.ownText, chatKey)
                ManagerAction.Skip
            }
            is FilterDecision.Process -> when (tag.role) {
                PartyRole.LABOUR -> labour(tag, decision)
                PartyRole.SUPPLIER -> supplier(tag, decision)
            }
        }
    }

    private fun labour(tag: PartyTag, decision: FilterDecision.Process): ManagerAction {
        val current = refreshSeen(tag, decision)
        if (current.ownerHandling) {
            return ManagerAction.Alert(
                chatKey = decision.chatKey,
                title = decision.customerName,
                body = "You replied in this chat, so KAI did not message a supplier.\n\n${decision.text}",
            )
        }
        val lines = MaterialRequestParser.parse(decision.text)
        if (lines.isEmpty()) {
            if (isChatter(decision.text)) return ManagerAction.Skip
            return ManagerAction.Alert(
                chatKey = decision.chatKey,
                title = decision.customerName,
                body = "No material list in this labour message, so nobody was messaged.\n\n${decision.text}",
            )
        }
        val matches = SupplierMatch.match(lines, books().materials, tags.all())
        val skipped = matches.mapNotNull { match ->
            when (match) {
                is MaterialMatch.UnknownMaterial -> "${match.line.name} is not in raw-material stock"
                is MaterialMatch.UnknownSupplier -> "${match.line.name}: ${match.reason}"
                is MaterialMatch.Ready -> null
            }
        }
        val ready = matches.filterIsInstance<MaterialMatch.Ready>()
        if (ready.isEmpty()) {
            return ManagerAction.Alert(
                chatKey = decision.chatKey,
                title = decision.customerName,
                body = "I did not message anyone.\n" + skipped.joinToString("\n"),
            )
        }
        val nowMs = clock()
        val rate = rateDecision(nowMs)
        if (rate is ReplyRateLimiter.Decision.Blocked) {
            return ManagerAction.Alert(
                chatKey = decision.chatKey,
                title = decision.customerName,
                body = "${rate.reason}. I did not message a supplier.",
            )
        }
        val orders = ready.groupBy { it.supplier.chatKey }.mapNotNull { (_, group) ->
            val supplier = group.first().supplier
            val materialLines = group.map { it.line }
            val text = orderMessage(materialLines)
            val digits = whatsAppDigits(supplier.chatKey)
            if (!orderTextIsSafe(text) || digits == null || digits != group.first().digits) {
                null
            } else {
                noteReply(supplier, nowMs)
                OutboundOrder(
                    supplierName = supplier.displayName,
                    supplierChatKey = supplier.chatKey,
                    digits = digits,
                    text = text,
                    record = BooksAction.PurchaseOrder(
                        supplier = supplier.displayName,
                        requestedBy = decision.customerName,
                        lines = MaterialRequestParser.describe(materialLines),
                    ),
                )
            }
        }
        if (orders.isEmpty()) {
            return ManagerAction.Alert(
                chatKey = decision.chatKey,
                title = decision.customerName,
                body = "I did not message anyone.\n" + skipped.joinToString("\n"),
            )
        }
        return ManagerAction.SendOrders(
            chatKey = decision.chatKey,
            labourName = decision.customerName,
            orders = orders,
            skipped = skipped.joinToString("\n"),
        )
    }

    private fun supplier(tag: PartyTag, decision: FilterDecision.Process): ManagerAction {
        refreshSeen(tag, decision)
        if (isChatter(decision.text)) return ManagerAction.Skip
        val bill = SupplierBillParser.parse(decision.text)
            ?: return ManagerAction.Alert(
                chatKey = decision.chatKey,
                title = decision.customerName,
                body = "I could not read one total bill, so nothing was recorded and nothing was paid.\n\n${decision.text}",
            )
        val advance = recordedAdvanceRupees(books(), decision.customerName)
        val payable = payableRupees(bill, advance)
        return ManagerAction.NotifyPayable(
            chatKey = decision.chatKey,
            supplierName = decision.customerName,
            billRupees = bill,
            advanceRupees = advance,
            payableRupees = payable,
            notice = payableNotice(decision.customerName, bill, advance, payable),
            record = BooksAction.SupplierBill(decision.customerName, bill),
        )
    }

    private fun refreshSeen(tag: PartyTag, decision: FilterDecision.Process): PartyTag {
        val updated = tag.copy(
            chatKey = decision.chatKey,
            displayName = decision.customerName.ifBlank { tag.displayName },
            lastSeenAtMs = decision.newestAtMs,
            lastText = decision.text,
        )
        tags.put(updated)
        return tags.find(decision.chatKey, decision.customerName) ?: updated
    }

    private fun noteOwner(tag: PartyTag, ownText: String?, chatKey: String) {
        val text = ownText?.trim().orEmpty()
        if (text.isEmpty()) return
        tags.put(
            tag.copy(
                chatKey = chatKey.ifBlank { tag.chatKey },
                ownerHandling = true,
                lastText = text,
                lastSeenAtMs = maxOf(tag.lastSeenAtMs, clock()),
            ),
        )
    }

    private fun noteReply(supplier: PartyTag, nowMs: Long) {
        val current = tags.find(supplier.chatKey, supplier.displayName) ?: supplier
        tags.put(current.copy(kaiReplyTimes = current.kaiReplyTimes + nowMs))
    }

    private fun rateDecision(nowMs: Long): ReplyRateLimiter.Decision {
        val supplierTimes = tags.all()
            .filter { it.role == PartyRole.SUPPLIER }
            .flatMap { it.kaiReplyTimes }
        return ReplyRateLimiter.check(
            nowMs = nowMs,
            chatReplyTimes = supplierTimes,
            allReplyTimes = supplierTimes,
            policy = ratePolicy,
        )
    }

    private fun isChatter(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower in setOf(
            "ok", "okay", "ji", "haan", "han", "yes", "no", "thanks", "thank you",
            "hi", "hello", "coming", "on the way", "reached",
        )
    }
}

fun payableNotice(supplier: String, bill: Long, advance: Long, payable: Long): String {
    val advanceLine = if (advance > 0) {
        "Advance already in the books: ${formatRupees(advance)}. "
    } else {
        "No advance is recorded for $supplier. "
    }
    return "Bill from $supplier is ${formatRupees(bill)}. $advanceLine" +
        "Pay ${formatRupees(payable)}. I did not pay, and I did not send UPI or a payment message."
}
