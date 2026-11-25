package com.kai.assistant.business

import java.time.LocalDateTime

/**
 * The answer-only-from-info rule, enforced on the phone. Wording that comes back from
 * the backend is sent to a customer only if every number, link and commitment in it
 * can be found in what the owner typed into [BusinessInfo]. Anything else is dropped
 * and [ReplyTemplates] is used instead.
 */
object AnswerGuard {
    private const val MAX_REPLY_CHARS = 700

    data class Verdict(val ok: Boolean, val reason: String? = null)

    private val number = Regex("""\d[\d,]*(?:\.\d+)?(\s?k\b)?""", RegexOption.IGNORE_CASE)
    private const val CURRENCY =
        """₹|(?<![a-z])(?:rs\.?|inr|rupees?|rupaye|rupay)(?![a-z])|रु\.?|रुपये|रुपए|रूपये|रुपया"""
    private val pricedBefore = Regex("""(?:$CURRENCY)\s*(\d[\d,]*(?:\.\d+)?(?:\s?k\b)?)""", RegexOption.IGNORE_CASE)
    private val pricedAfter = Regex("""(\d[\d,]*(?:\.\d+)?(?:\s?k\b)?)\s*(?:/-|$CURRENCY)""", RegexOption.IGNORE_CASE)
    private val link = Regex(
        """(?:https?://|www\.)[^\s]+|\b[a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:com|in|net|org|co|store|shop|me|info|biz)\b[^\s]*""",
        RegexOption.IGNORE_CASE,
    )

    /** Amount words: a price spelled out is still a price. */
    private val amountWords = listOf(
        Regex("""\b(hundred|thousand|lakhs?|lacs?|crores?|hazaar|hazar|hajar|hajaar|sau)\b"""),
        Regex("""हज़ार|हजार|लाख|करोड़|करोड"""),
    )

    /** Commitments. Each is allowed only when the owner's own text contains it. */
    private val claims = listOf(
        Regex("""\bdiscount\w*|%|\bpercent\w*|\bcashback\b|(?<!feel )\bfree\b|\bsale\b|\bemi\b|\bcoupon\w*"""),
        Regex("""छूट|डिस्काउंट|मुफ्त|मुफ़्त|फ्री|प्रतिशत|\b(chhoot|chhut|muft)\b"""),
        Regex("""\bstock\b|\bavailab\w*|\buplabdh\b|उपलब्ध|स्टॉक"""),
        Regex("""\bmil (jayega|jayegi|jaega|jaegi|jaayega|jaayegi)\b|मिल जाएगा|मिल जाएगी|\bpahunch\w*|\bpahuch\w*|पहुंच|पहुँच"""),
        Regex("""\bwarrant\w*|\bguarant\w*|\blowest\b|\bcheapest\b|\bnegotiable\b|वारंटी|गारंटी|सबसे सस्ता|\bsabse sasta\b"""),
        Regex("""\b(best|final|last|fixed) (price|rate)\b"""),
        Regex("""\bcod\b|\bcash on delivery\b|\badvance\b|एडवांस"""),
    )
    private val offerWord = Regex("""\boffers?\b|ऑफर|ऑफ़र""")
    private val deliveryWord = Regex("""\bdeliver\w*|\bshipping\b|\bship\b|\bdispatch\w*|\bcourier\b|डिलीवरी|डिलिवरी""")
    private val deliveryTiming = Regex(
        """\b(today|tomorrow|aaj|kal|parso|same day|next day|days?|din|weeks?|hafta|hafte|hours?|ghante|within)\b""" +
            """|आज|कल|परसों|दिन|हफ्ता|हफ्ते|घंटे""",
    )

    fun check(
        reply: String,
        info: BusinessInfo,
        topics: Set<Topic> = emptySet(),
        visit: LocalDateTime? = null,
    ): Verdict {
        val text = asciiDigits(reply).trim()
        if (text.isEmpty()) return Verdict(false, "empty")
        if (text.length > MAX_REPLY_CHARS) return Verdict(false, "too long")

        val lower = text.lowercase()
        val facts = asciiDigits(info.factText()).lowercase()

        link.findAll(lower).forEach { match ->
            val target = bareLink(match.value)
            if (target.isNotEmpty() && !facts.contains(target)) return Verdict(false, "link not in business info")
        }

        val allowed = numbersIn(facts) + phoneForms(info.phone) + hoursNumbers(info.hours) + visitNumbers(visit)
        numbersIn(lower).forEach { value ->
            if (value !in allowed) return Verdict(false, "number $value not in business info")
        }

        val priceFacts = numbersIn(
            asciiDigits(info.products.joinToString(" ") { it.priceRange } + " " + info.offers).lowercase(),
        )
        (pricedBefore.findAll(lower) + pricedAfter.findAll(lower)).forEach { match ->
            val value = normaliseNumber(match.groupValues[1])
            if (value !in priceFacts) return Verdict(false, "price $value not in business info")
        }

        amountWords.forEach { pattern ->
            pattern.findAll(lower).forEach { match ->
                if (!facts.contains(match.value)) return Verdict(false, "amount '${match.value}' not in business info")
            }
        }

        claims.forEach { pattern ->
            pattern.findAll(lower).forEach { match ->
                if (!facts.contains(match.value)) return Verdict(false, "'${match.value}' not in business info")
            }
        }

        val offersAnswer = Topic.Offers in topics && info.has(Topic.Offers)
        if (!offersAnswer) {
            offerWord.find(lower)?.let { match ->
                if (!facts.contains(match.value)) return Verdict(false, "offer not in business info")
            }
        }

        if (deliveryWord.containsMatchIn(lower)) {
            val deliveryAnswer = Topic.Delivery in topics && info.has(Topic.Delivery)
            if (!deliveryAnswer) return Verdict(false, "delivery not asked or not in business info")
            deliveryTiming.findAll(lower).forEach { match ->
                if (!facts.contains(match.value)) return Verdict(false, "delivery time not in business info")
            }
        }

        if (visit != null) {
            val said = numbersIn(lower)
            val hour12 = if (visit.hour % 12 == 0) 12 else visit.hour % 12
            if (visit.dayOfMonth.toString() !in said) return Verdict(false, "visit date missing")
            if (hour12.toString() !in said && visit.hour.toString() !in said) return Verdict(false, "visit time missing")
            if (visit.minute != 0 && visit.minute.toString() !in said) return Verdict(false, "visit time missing")
        }
        return Verdict(true)
    }

    internal fun asciiDigits(text: String): String = buildString(text.length) {
        text.forEach { char -> append(if (char in '०'..'९') '0' + (char - '०') else char) }
    }

    internal fun numbersIn(text: String): Set<String> =
        number.findAll(text).map { normaliseNumber(it.value) }.toSet()

    /** "25,000" and "25k" are 25000; "08" is 8; "1.50" is 1.5. */
    internal fun normaliseNumber(raw: String): String {
        val thousands = raw.trimEnd().endsWith("k", ignoreCase = true)
        val digits = raw.replace(",", "").replace(Regex("""\s?k$""", RegexOption.IGNORE_CASE), "").trim()
        if (digits.contains('.')) {
            val value = digits.toBigDecimalOrNull() ?: return digits
            val scaled = if (thousands) value.multiply(java.math.BigDecimal(1000)) else value
            return scaled.stripTrailingZeros().toPlainString()
        }
        val whole = digits.trimStart('0').ifEmpty { "0" }
        return if (thousands) whole + "000" else whole
    }

    /** A phone number may be written joined up or without its country code. */
    private fun phoneForms(phone: String): Set<String> {
        val forms = mutableSetOf<String>()
        phone.split(Regex("""[,/;]|\bor\b""")).forEach { part ->
            val digits = part.filter { it.isDigit() }.trimStart('0')
            if (digits.length < 6) return@forEach
            forms += digits
            if (digits.length == 12 && digits.startsWith("91")) forms += digits.drop(2)
        }
        return forms
    }

    private fun hoursNumbers(hours: OpeningHours): Set<String> {
        val values = mutableSetOf<String>()
        hours.days.values.forEach { span ->
            listOf(span.openMinute, span.closeMinute).forEach { minute ->
                val hour24 = minute / 60
                values += hour24.toString()
                values += (if (hour24 % 12 == 0) 12 else hour24 % 12).toString()
                values += (minute % 60).toString()
            }
        }
        return values
    }

    private fun visitNumbers(visit: LocalDateTime?): Set<String> {
        if (visit == null) return emptySet()
        return setOf(
            visit.dayOfMonth.toString(),
            visit.year.toString(),
            visit.hour.toString(),
            (if (visit.hour % 12 == 0) 12 else visit.hour % 12).toString(),
            visit.minute.toString(),
        )
    }

    private fun bareLink(raw: String): String =
        raw.lowercase()
            .removePrefix("https://").removePrefix("http://").removePrefix("www.")
            .trimEnd('.', ',', ')', '!', '?', ';', ':', '/')
}
