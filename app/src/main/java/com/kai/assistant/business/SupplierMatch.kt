package com.kai.assistant.business

import com.kai.assistant.books.MaterialStock
import com.kai.assistant.books.namesMatch
import com.kai.assistant.books.normalizeParty

sealed class MaterialMatch {
    data class Ready(
        val line: MaterialLine,
        val stock: MaterialStock,
        val supplier: PartyTag,
        val digits: String,
    ) : MaterialMatch()

    data class UnknownMaterial(val line: MaterialLine) : MaterialMatch()

    /** The stock names a supplier the owner has not tagged, or the tagged chat has no number. */
    data class UnknownSupplier(val line: MaterialLine, val supplierName: String, val reason: String) : MaterialMatch()
}

/**
 * Matches a parsed material to raw-material stock, then to a supplier chat the owner tagged.
 * Nothing here picks a contact who is not already saved as that supplier.
 */
object SupplierMatch {
    fun match(
        lines: List<MaterialLine>,
        stock: List<MaterialStock>,
        suppliers: List<PartyTag>,
    ): List<MaterialMatch> = lines.map { line -> matchOne(line, stock, suppliers) }

    fun matchOne(
        line: MaterialLine,
        stock: List<MaterialStock>,
        suppliers: List<PartyTag>,
    ): MaterialMatch {
        val item = bestStock(line, stock)
            ?: return MaterialMatch.UnknownMaterial(line)
        if (item.supplier.isBlank()) {
            return MaterialMatch.UnknownSupplier(line, "", "no supplier is saved on ${item.item}")
        }
        val tag = bestSupplier(item.supplier, suppliers)
            ?: return MaterialMatch.UnknownSupplier(
                line,
                item.supplier,
                "${item.supplier} is not a tagged Supplier",
            )
        val digits = whatsAppDigits(tag.chatKey)
            ?: return MaterialMatch.UnknownSupplier(
                line,
                tag.displayName,
                "${tag.displayName} is tagged, but that chat has no WhatsApp number",
            )
        return MaterialMatch.Ready(line, item, tag, digits)
    }

    fun bestStock(line: MaterialLine, stock: List<MaterialStock>): MaterialStock? {
        val hits = stock.filter { namesMatch(it.item, line.name) }
        if (hits.isEmpty()) return null
        return hits.maxBy { score(it.item, line.name) }
    }

    fun bestSupplier(stockSupplier: String, suppliers: List<PartyTag>): PartyTag? {
        val hits = suppliers.filter { tag ->
            tag.role == PartyRole.SUPPLIER && supplierTagMatches(tag.displayName, stockSupplier)
        }
        if (hits.isEmpty()) return null
        return hits.maxBy { score(it.displayName, stockSupplier) }
    }

    private fun score(saved: String, requested: String): Int {
        val left = normalizeParty(saved)
        val right = normalizeParty(requested)
        return when {
            left == right -> 1_000 + left.length
            right.contains(left) -> 500 + left.length
            left.contains(right) -> 200 + right.length
            else -> 0
        }
    }
}

/** Order text is quantities only. A price, a UPI id, or a payment word makes it unsafe to send. */
fun orderTextIsSafe(text: String): Boolean {
    if (text.isBlank()) return false
    val lower = text.lowercase()
    val banned = listOf("upi", "gpay", "phonepe", "paytm", "payment", "₹", "rupee", "rs.", "rs ")
    if (banned.any { lower.contains(it) }) return false
    if (Regex("""\bpay\b""").containsMatchIn(lower)) return false
    if (Regex("""\d{10,}""").containsMatchIn(text)) return false
    return true
}

fun orderMessage(lines: List<MaterialLine>): String {
    val body = lines.joinToString("\n") { line ->
        val unit = if (line.unit.isBlank()) "" else " ${line.unit}"
        "- ${line.name}: ${com.kai.assistant.books.formatQty(line.qty)}$unit"
    }
    return "Sagar Furniture order\n$body\nPlease reply with the total bill only."
}
