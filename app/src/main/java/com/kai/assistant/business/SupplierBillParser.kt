package com.kai.assistant.business

/**
 * Reads the total a supplier wrote. Returns null when the message does not state one amount,
 * so KAI does not invent a price. The number is never used to send a payment.
 */
object SupplierBillParser {
    private val labels = listOf(
        "grand total",
        "total bill",
        "total amount",
        "bill amount",
        "total",
        "bill",
        "amount",
        "rakam",
        "kul",
    )

    private val unitAfter = Regex(
        """\s*(?:pcs|pc|pieces?|sheets?|kg|kgs|ft|feet|box(?:es)?|nos|sets?|bags?)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val labeled = labels.flatMap { word -> amountsAfter(trimmed, word) }.distinct()
        if (labeled.size == 1) return labeled.first()
        if (labeled.size > 1) return null
        val currency = Regex("""(?:₹|rs\.?|inr|rupees?)\s*(\d[\d,]*(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
            .findAll(trimmed)
            .mapNotNull { rupees(it.groupValues[1]) }
            .distinct()
            .toList()
        if (currency.size == 1) return currency.first()
        if (currency.size > 1) return null
        val only = Regex(
            """^(?:bill|total|amount|kul|rakam)?\s*(\d[\d,]*)\s*(?:only|hai)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(trimmed) ?: return null
        return rupees(only.groupValues[1])
    }

    private fun amountsAfter(text: String, word: String): List<Long> {
        val pattern = Regex(
            """$word(?:\s+is|\s+hai|:)?\s*(?:₹|rs\.?|inr|rupees?)?\s*(\d[\d,]*(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.findAll(text).mapNotNull { match ->
            val tail = text.substring(match.range.last + 1)
            if (unitAfter.containsMatchIn(tail.take(12))) return@mapNotNull null
            rupees(match.groupValues[1])
        }.toList()
    }

    private fun rupees(raw: String): Long? {
        val value = raw.replace(",", "").toDoubleOrNull() ?: return null
        if (value <= 0 || value > 100_000_000) return null
        val whole = value.toLong()
        if (whole.toDouble() != value) return null
        return whole
    }
}
