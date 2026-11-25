package com.kai.assistant.business

import com.kai.assistant.books.formatQty
import com.kai.assistant.books.titleParty

/** One material a labour message asked for. Quantity comes from the message; it is never guessed. */
data class MaterialLine(
    val name: String,
    val qty: Double,
    val unit: String,
)

/**
 * Pulls material lines out of a labour message.
 * Prices, phone numbers, and UPI text are dropped so they cannot become an order.
 */
object MaterialRequestParser {
    private val unitWords = listOf(
        "pieces", "piece", "pcs", "pc", "nos", "numbers", "number",
        "sheets", "sheet", "feet", "foot", "ft", "sqft",
        "kgs", "kg", "kilo", "boxes", "box", "bags", "bag",
        "rolls", "roll", "litres", "liters", "litre", "liter", "ltr",
        "sets", "set", "bundles", "bundle", "packets", "packet", "pkt",
        "tins", "tin", "bottles", "bottle", "mtr", "meter", "metre",
    ).sortedByDescending { it.length }

    private val unitPattern = unitWords.joinToString("|") { Regex.escape(it) }

    private val split = Regex("""[\n,;]+|\s+&\s+|\s+\band\b\s+|\s+\baur\b\s+""", RegexOption.IGNORE_CASE)

    private val filler = Regex(
        """^(?:please|kindly|bhai|sir|ji|need|needs|needed|require|required|want|wanted|send|order|chahiye|mangwana|manga|bhej(?:o| do)?|materials?|raw materials?|saman|samaan|maal|we need|i need)\s+""",
        RegexOption.IGNORE_CASE,
    )

    private val price = Regex(
        """(?:@|\bat\b|\bfor\b|rs\.?|inr|rupees?|₹)\s*\d[\d,]*(?:\.\d+)?(?:\s*/\s*[a-z]+)?""",
        RegexOption.IGNORE_CASE,
    )

    private val thickness = Regex("""\d+(?:\.\d+)?\s*(?:mm|cm)\b""", RegexOption.IGNORE_CASE)

    private val upi = Regex("""\b[\w.+-]+@[\w.-]+\b|upi|gpay|phonepe|paytm""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<MaterialLine> {
        if (text.isBlank()) return emptyList()
        val cleaned = upi.replace(text, " ")
        return cleaned.split(split).mapNotNull { clause -> parseClause(clause) }.distinctBy {
            "${it.name.lowercase()}|${formatQty(it.qty)}|${it.unit}"
        }
    }

    fun describe(lines: List<MaterialLine>): String =
        lines.joinToString("; ") { line ->
            val unit = if (line.unit.isBlank()) "" else " ${line.unit}"
            "${line.name} ${formatQty(line.qty)}$unit"
        }

    private fun parseClause(raw: String): MaterialLine? {
        var text = price.replace(raw, " ").replace(Regex("""\s+"""), " ").trim()
        if (text.isEmpty()) return null
        repeat(3) {
            val next = filler.replaceFirst(text, "").trim()
            if (next == text) return@repeat
            text = next
        }
        if (text.length < 2 || isChatter(text)) return null

        val thicknesses = mutableListOf<String>()
        val protected = thickness.replace(text) { match ->
            thicknesses += match.value
            "THK${thicknesses.lastIndex}"
        }
        val qtyPattern = Regex("""(?<qty>\d+(?:\.\d+)?)\s*(?<unit>$unitPattern)?""", RegexOption.IGNORE_CASE)
        val hits = qtyPattern.findAll(protected).toList()
        val match = when {
            hits.isEmpty() -> return null
            hits.size == 1 -> hits.first()
            else -> hits.firstOrNull { !it.groups["unit"]?.value.isNullOrBlank() } ?: return null
        }
        val qty = match.groups["qty"]?.value?.toDoubleOrNull() ?: return null
        if (qty <= 0.0 || qty > 100_000.0) return null
        val unit = canonicalUnit(match.groups["unit"]?.value.orEmpty())
        val nameRaw = restoreThickness(protected.removeRange(match.range), thicknesses)
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '-', ':', '/')
        if (nameRaw.length < 2 || !nameRaw.any { it.isLetter() }) return null
        if (Regex("""\b(?:pay|payment|upi|gpay|phonepe|paytm|rupee|rupees)\b""", RegexOption.IGNORE_CASE).containsMatchIn(nameRaw)) {
            return null
        }
        return MaterialLine(titleParty(nameRaw), qty, unit)
    }

    private fun restoreThickness(text: String, thicknesses: List<String>): String =
        text.replace(Regex("""THK(\d+)""")) { match ->
            thicknesses.getOrNull(match.groupValues[1].toInt()) ?: ""
        }

    private fun canonicalUnit(raw: String): String = when (raw.lowercase()) {
        "" -> ""
        "piece", "pieces", "pc", "pcs", "number", "numbers", "nos" -> "pcs"
        "sheet", "sheets" -> "sheet"
        "foot", "feet", "ft" -> "ft"
        "kilo", "kgs" -> "kg"
        "box", "boxes" -> "box"
        "bag", "bags" -> "bag"
        "roll", "rolls" -> "roll"
        "litre", "liter", "litres", "liters", "ltr" -> "ltr"
        "set", "sets" -> "set"
        "bundle", "bundles" -> "bundle"
        "packet", "packets", "pkt" -> "packet"
        "tin", "tins" -> "tin"
        "bottle", "bottles" -> "bottle"
        "meter", "metre", "mtr" -> "m"
        else -> raw.lowercase()
    }

    private fun isChatter(text: String): Boolean {
        val lower = text.trim().lowercase()
        return lower in setOf(
            "ok", "okay", "ji", "haan", "han", "yes", "no", "thanks", "thank you",
            "hi", "hello", "coming", "on the way", "reached", "theek", "thik",
        )
    }
}
