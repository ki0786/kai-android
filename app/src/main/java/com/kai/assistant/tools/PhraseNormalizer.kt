package com.kai.assistant.tools

/**
 * Cleans a spoken or typed command before intent matching.
 * Case is kept so WhatsApp messages and calendar titles read as the user said them.
 */
internal object PhraseNormalizer {
    private val wakeLead = Regex("""^(?:(?:hey|hi|hay|ok|okay)\s+)?(?:kai|kay|kye)\b[\s,!.:]*""", RegexOption.IGNORE_CASE)
    private val politeLead = Regex(
        """^(?:please|pls|plz|kindly|just|quickly|now|can\s+you|could\s+you|would\s+you|will\s+you|can\s+u|""" +
            """i\s+want\s+you\s+to|i\s+want\s+to|i\s+need\s+you\s+to|i\s+need\s+to|i'?d\s+like\s+to|""" +
            """i\s+would\s+like\s+to|let'?s|help\s+me|go\s+ahead\s+and)\b[\s,]*""",
        RegexOption.IGNORE_CASE,
    )
    private val politeTail = Regex(
        """[\s,]+(?:please|pls|plz|for\s+me|right\s+now|quickly|thanks|thank\s+you|kai)$""",
        RegexOption.IGNORE_CASE,
    )

    private val spellings: List<Pair<Regex, String>> = listOf(
        """\b(?:vedio|vidio|viedo|vedeo|vidoe|vido|vdo|veedio)\b""" to "video",
        """\b(?:on|via|using|through|in)\s+what'?s\s?up\b""" to "on whatsapp",
        """^what'?s\s?up\s+(?=(?:video\s+|voice\s+)?call\b)""" to "whatsapp ",
        """\b(?:whats\s+app|what'?s\s+app|whatsap|watsapp|watsap|whatsaap|whatapp|whtsapp|wtsapp|whatsupp|watsaap|wattsapp|whatsapps)\b""" to "whatsapp",
        """\b(?:alram|alaram|allarm|alarn|alrm|alarum)\b""" to "alarm",
        """\b(?:timmer|timar|timr)\b""" to "timer",
        """\b(?:remaind|remined|remid|reminde|remond)\b""" to "remind",
        """\b(?:reminer|remainder)\b""" to "reminder",
        """\bflash\s+light\b""" to "flashlight",
        """\btorch\s+light\b""" to "torch",
        """\bmsg\b""" to "message",
        """\btxt\b""" to "text",
        """\bcalender\b""" to "calendar",
        """\b(?:you\s+tube|youtub|utube|yutube)\b""" to "youtube",
        """\bcamra\b""" to "camera",
        """\bscreen\s+shot\b""" to "screenshot",
        """\bwi\s+fi\b""" to "wifi",
        """\bblue\s+tooth\b""" to "bluetooth",
        """\b(?:volme|volum|voulme|volumn|voulume|voluem|vloume)\b""" to "volume",
        """\b(?:percnt|persent|precent|pecent|percen|percet)\b""" to "percent",
        """\b(?:brightnes|brightnss|briteness|brightess)\b""" to "brightness",
        """^dail\b""" to "dial",
        """^(?:cal|cll|kall)\s+(?=\p{L})""" to "call ",
        """\bphn\b""" to "phone",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    fun clean(raw: String): String {
        var text = raw.trim()
            .replace(Regex("""[?.!,]+$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        text = wakeLead.replace(text, "").trim()
        while (true) {
            val next = politeLead.replace(text, "").trim()
            if (next == text) break
            text = next
        }
        for ((pattern, replacement) in spellings) {
            text = pattern.replace(text, replacement)
        }
        while (true) {
            val next = politeTail.replace(text, "").trim()
            if (next == text) break
            text = next
        }
        return text.replace(Regex("""\s+"""), " ").trim()
    }
}

private val units = mapOf(
    "zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
    "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11,
    "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
    "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
)
private val tens = mapOf(
    "twenty" to 20,
    "thirty" to 30,
    "forty" to 40,
    "fifty" to 50,
    "sixty" to 60,
    "seventy" to 70,
    "eighty" to 80,
    "ninety" to 90,
)

/**
 * "five minutes" -> "5 minutes", "half an hour" -> "30 minutes", "seven thirty" -> "7:30".
 * Used only for times, durations and volume, never for message text.
 */
internal fun digitsForNumberWords(lower: String): String {
    var text = lower
        .replace(Regex("""\b(?:one|a)\s+hundred\b"""), "100")
        .replace(Regex("""\bhundred\b"""), "100")
        .replace(Regex("""\bhalf\s+an?\s+hour\b"""), "30 minutes")
        .replace(Regex("""\b(?:a\s+)?quarter\s+(?:of\s+)?an?\s+hour\b"""), "15 minutes")
        .replace(Regex("""\ban?\s+hour\s+and\s+a\s+half\b"""), "90 minutes")
        .replace(Regex("""\ba\s+couple\s+(?:of\s+)?(minutes?|hours?|seconds?)\b"""), "2 $1")
        .replace(Regex("""\ban?\s+(minute|hour|second)\b"""), "1 $1")
        .replace(Regex("""\bo'?\s?clock\b"""), "")
    val tensPattern = tens.keys.joinToString("|")
    val unitPattern = units.keys.joinToString("|")
    text = Regex("""\b($tensPattern)[\s-]+($unitPattern)\b""").replace(text) { match ->
        val unit = units.getValue(match.groupValues[2])
        if (unit in 1..9) (tens.getValue(match.groupValues[1]) + unit).toString() else match.value
    }
    text = Regex("""\b($tensPattern)\b""").replace(text) { tens.getValue(it.groupValues[1]).toString() }
    text = Regex("""\b($unitPattern)\b""").replace(text) { units.getValue(it.groupValues[1]).toString() }
    text = Regex("""\b(\d{1,2})\s+(\d{2})(?=\s*(?:am|pm|in the|tonight|tomorrow|$))""").replace(text) { match ->
        val minute = match.groupValues[2].toInt()
        if (minute in 0..59 && match.groupValues[1].toInt() in 0..23) {
            "${match.groupValues[1]}:${match.groupValues[2]}"
        } else {
            match.value
        }
    }
    return text.replace(Regex("""\s+"""), " ").trim()
}
