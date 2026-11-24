package com.kai.assistant.tools

/**
 * Parses on-device WhatsApp sends and picks one contact the same way calls do.
 * Longest name is tried first so "WhatsApp Mohammed Kaif hi" stays "hi", not "kaif hi".
 */
object WhatsAppUtterance {
    data class Option(val nameQuery: String, val message: String)

    data class Parsed(val options: List<Option>) {
        val nameQuery: String get() = options.first().nameQuery
        val message: String get() = options.first().message
    }

    private val sendForm = Regex(
        """^(?:please\s+)?(?:send|message|text)\s+(.+?)\s+to\s+(.+?)\s+(?:on|via|using|through)\s+whats\s?app$""",
    )
    private val whatsAppFirst = Regex(
        """^(?:please\s+)?whats\s?app\s+(.+)$""",
    )
    private val sendNoApp = Regex(
        """^(?:send|message|text)\s+(.+?)\s+to\s+(.+)$""",
    )
    private val sendToSaying = Regex(
        """^send\s+(?:an?\s+)?(?:whatsapp\s+)?(?:message|text|msg|whatsapp)\s+to\s+(.+?)\s+(?:saying|that says|that|:)\s+(.+)$""",
    )
    private val sendToOnly = Regex(
        """^send\s+(?:an?\s+)?(?:whatsapp\s+)?(?:message|text|whatsapp)\s+to\s+(.+)$""",
    )
    private val messageFirstNoApp = Regex("""^(?:message|text)\s+(.+)$""")
    private val tellThat = Regex("""^tell\s+(.+?)\s+that\s+(.+)$""")
    private val tellOnApp = Regex("""^tell\s+(.+)\s+(?:on|via|using|through)\s+whatsapp$""")
    private val openChat = Regex(
        """^open\s+(?:the\s+)?(?:whatsapp\s+)?chat\s+(?:with|of|for)\s+(.+)$|^open\s+(.+?)'s\s+(?:whatsapp\s+)?chat$""",
    )
    private val goodsNotText = Regex(
        """\b(money|rupees?|rs|inr|₹|payment|paisa|cash|dollars?|location|photo|photos|picture|pic|file|document|pdf|contact|video)\b""",
    )
    private val nameWord = Regex("""^\p{L}[\p{L}.'’\-]*$""")
    private val notAName = setOf(
        "me", "you", "him", "her", "them", "us", "it", "someone", "somebody",
        "anyone", "everybody", "a", "an", "the", "to", "on", "via", "whatsapp",
        "message", "text", "send", "my", "from", "for", "with", "of", "in", "this", "that",
    )

    data class Call(val nameQuery: String, val video: Boolean)

    private val openThenVideo = Regex(
        """^(?:please\s+)?open\s+whats\s?app\s+and\s+call\s+(.+?)\s+on\s+(?:a\s+)?(?:video|vedio)\s+call$""",
    )
    private val openThenVideoCall = Regex(
        """^(?:please\s+)?open\s+whats\s?app\s+and\s+(?:video|vedio)\s+call\s+(.+)$""",
    )
    private val openThenVoice = Regex(
        """^(?:please\s+)?open\s+whats\s?app\s+and\s+(?:voice\s+)?call\s+(.+)$""",
    )
    private val videoOnApp = Regex(
        """^(?:please\s+)?(?:make\s+a\s+)?(?:video|vedio)\s+call\s+(.+?)\s+(?:on|via|using|through)\s+whats\s?app$""",
    )
    private val videoFirst = Regex(
        """^(?:please\s+)?whats\s?app\s+(?:video|vedio)\s+call\s+(.+)$""",
    )
    private val voiceOnApp = Regex(
        """^(?:please\s+)?(?:make\s+a\s+)?(?:voice\s+)?(?:call|phone)\s+(.+?)\s+(?:on|via|using|through)\s+whats\s?app$""",
    )
    private val voiceFirst = Regex(
        """^(?:please\s+)?whats\s?app\s+(?:voice\s+)?(?:call|phone)\s+(.+)$""",
    )
    private val videoNoApp = Regex(
        """^(?:(?:make|start|do|place)\s+a\s+)?video\s+call\s+(?:to\s+|with\s+)?(.+)$""",
    )
    private val leadIn = Regex("""^(?:please|can you|could you|would you|will you)\s+""")

    fun parse(normalizedText: String): Parsed? {
        val text = normalize(normalizedText)
        if (text.isEmpty() || parseCall(text) != null) return null
        sendForm.find(text)?.let { match ->
            val message = match.groupValues[1].trim()
            val name = match.groupValues[2].trim()
            if (message.isEmpty() || !isContactName(name)) return null
            return Parsed(listOf(Option(name, message)))
        }
        tellOnApp.find(text)?.let { match ->
            val tail = match.groupValues[1].trim()
            tellThat.find("tell $tail")?.let { that -> return single(that.groupValues[1], that.groupValues[2]) }
            return splitNameAndMessage(tail).takeIf { it.isNotEmpty() }?.let { Parsed(it) }
        }
        val tail = whatsAppFirst.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (tail.isNotEmpty()) {
            Regex("""^chat\s+(?:with\s+|of\s+|for\s+)?(.+)$""").find(tail)?.let { chat ->
                val name = chat.groupValues[1].trim()
                if (isContactName(name)) return Parsed(listOf(Option(name, "")))
            }
            val options = splitNameAndMessage(tail.removePrefix("to ").trim(), allowEmpty = true)
            if (options.isEmpty()) return null
            return Parsed(options)
        }
        return parseWithoutApp(text)
    }

    /**
     * Messages that don't name an app go to WhatsApp, the messenger this phone's
     * owner uses. "SMS" and "text message" go to [SmsUtterance] first.
     */
    private fun parseWithoutApp(text: String): Parsed? {
        openChat.find(text)?.let { match ->
            val name = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            return if (isContactName(name)) Parsed(listOf(Option(name, ""))) else null
        }
        sendToSaying.find(text)?.let { match ->
            return single(match.groupValues[1], match.groupValues[2])
        }
        sendToOnly.find(text)?.let { match ->
            val tail = match.groupValues[1].trim()
            return splitNameAndMessage(tail, allowEmpty = true).takeIf { it.isNotEmpty() }?.let { Parsed(it) }
        }
        tellThat.find(text)?.let { match ->
            return single(match.groupValues[1], match.groupValues[2])
        }
        val options = mutableListOf<Option>()
        sendNoApp.find(text)?.let { match ->
            val message = match.groupValues[1].trim()
            val name = match.groupValues[2].trim()
            if (text.startsWith("send") && goodsNotText.containsMatchIn(message)) return null
            if (message.isNotEmpty() && isContactName(name)) options += Option(name, message)
        }
        if (!text.startsWith("send")) {
            messageFirstNoApp.find(text)?.let { match ->
                options += splitNameAndMessage(match.groupValues[1].trim())
            }
        }
        val cleaned = options.map { it.copy(message = cleanMessage(it.message)) }.filter { it.message.isNotEmpty() }
        return cleaned.distinct().ifEmpty { null }?.let { Parsed(it) }
    }

    private fun single(rawName: String, rawMessage: String): Parsed? {
        val name = rawName.trim()
        val message = cleanMessage(rawMessage)
        if (message.isEmpty() || !isContactName(name)) return null
        return Parsed(listOf(Option(name, message)))
    }

    private fun cleanMessage(raw: String): String =
        raw.trim()
            .replace(Regex("""^(?:and\s+)?(?:tell|ask)\s+(?:him|her|them)\s+(?:that\s+)?"""), "")
            .replace(Regex("""^(?:saying|that says|that|:)\s+"""), "")
            .trim()

    fun parseCall(normalizedText: String): Call? {
        val text = normalize(normalizedText)
        if (text.isEmpty()) return null
        openThenVideo.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = true)
        }
        openThenVideoCall.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = true)
        }
        openThenVoice.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = false)
        }
        videoOnApp.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = true)
        }
        videoFirst.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = true)
        }
        voiceOnApp.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = false)
        }
        voiceFirst.find(text)?.let { match ->
            return callOrNull(match.groupValues[1], video = false)
        }
        videoNoApp.find(text)?.let { match ->
            return callOrNull(match.groupValues[1].removeSuffix(" now").trim(), video = true)
        }
        return null
    }

    private fun callOrNull(rawName: String, video: Boolean): Call? {
        val name = rawName.trim()
        if (!isContactName(name)) return null
        return Call(name, video)
    }

    private fun normalize(raw: String): String {
        var rest = raw.trim().removeSuffix(" please").trim()
            .replace(Regex("""\bvedio\b"""), "video")
        while (true) {
            val next = leadIn.replace(rest, "").trim()
            if (next == rest) break
            rest = next
        }
        return leadingNoise.replace(rest, "").trim()
    }

    private val leadingNoise = Regex(
        """^(?:noir|noor|nah|now)\s+(?=(?:video\s+)?(?:call|phone|message|text|send|open)\b)""",
    )

    /**
     * Every way to read "<name> <message>", longest name first. With [allowEmpty] the
     * whole tail is also tried as a name with no message, which only opens the chat.
     */
    fun splitNameAndMessage(tail: String, allowEmpty: Boolean = false): List<Option> {
        val tokens = tail.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val maxNameWords = minOf(4, tokens.size - 1)
        val split = (maxNameWords downTo 1).mapNotNull { count ->
            val name = tokens.take(count).joinToString(" ")
            val message = cleanMessage(tokens.drop(count).joinToString(" "))
            if (message.isBlank() || !isContactName(name)) null else Option(name, message)
        }
        val whole = tokens.joinToString(" ")
        return if (allowEmpty && tokens.size <= 4 && isContactName(whole)) split + Option(whole, "") else split
    }

    fun isContactName(target: String): Boolean {
        val parts = target.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 4) return false
        if (!parts.all { nameWord.matches(it) }) return false
        return parts.none { it in notAName }
    }
}

sealed class WhatsAppResolution {
    data class Ready(
        val displayName: String,
        val number: String,
        val message: String,
        val contactId: Long,
    ) : WhatsAppResolution()

    data class Speak(val message: String) : WhatsAppResolution()
}

sealed class WhatsAppCallResolution {
    data class Ready(
        val displayName: String,
        val number: String,
        val contactId: Long,
    ) : WhatsAppCallResolution()

    data class Speak(val message: String) : WhatsAppCallResolution()
}

object WhatsAppResolver {
    fun resolve(
        options: List<WhatsAppUtterance.Option>,
        phones: List<ContactPhone>,
        app: String = "WhatsApp",
    ): WhatsAppResolution {
        if (options.isEmpty()) {
            return WhatsAppResolution.Speak("I need a name and a message to open $app.")
        }
        for (option in rankByContact(options, phones)) {
            when (val matched = matchOne(option.nameQuery, phones, "so I didn't open $app")) {
                is Hit.Ready -> return WhatsAppResolution.Ready(
                    displayName = matched.displayName,
                    number = matched.number,
                    message = option.message,
                    contactId = matched.contactId,
                )
                is Hit.Stop -> return WhatsAppResolution.Speak(matched.message)
                Hit.Miss -> Unit
            }
        }
        return WhatsAppResolution.Speak(ContactMatcher.describe(ContactMatchResult.None(options.first().nameQuery)))
    }

    /**
     * Readings whose name sounds most like a saved contact come first, so
     * "message Kaif come home" beats reading "come home" as the name.
     * Equal scores keep the parser's order, which tries longer names first.
     */
    fun rankByContact(
        options: List<WhatsAppUtterance.Option>,
        phones: List<ContactPhone>,
    ): List<WhatsAppUtterance.Option> {
        if (options.size < 2) return options
        return options
            .mapIndexed { index, option -> Triple(index, option, ContactMatcher.bestScore(option.nameQuery, phones)) }
            .sortedWith(compareByDescending<Triple<Int, WhatsAppUtterance.Option, Double>> { it.third }.thenBy { it.first })
            .map { it.second }
    }

    fun resolveContact(nameQuery: String, phones: List<ContactPhone>): WhatsAppCallResolution {
        return when (val matched = matchOne(nameQuery, phones, "so I didn't start a WhatsApp call")) {
            is Hit.Ready -> WhatsAppCallResolution.Ready(
                displayName = matched.displayName,
                number = matched.number,
                contactId = matched.contactId,
            )
            is Hit.Stop -> WhatsAppCallResolution.Speak(matched.message)
            Hit.Miss -> WhatsAppCallResolution.Speak(
                ContactMatcher.describe(ContactMatchResult.None(nameQuery)),
            )
        }
    }

    private fun matchOne(nameQuery: String, phones: List<ContactPhone>, stopped: String): Hit {
        return when (val matched = ContactMatcher.match(nameQuery, phones, preferredKind = null)) {
            is ContactMatchResult.One -> {
                val contactId = contactIdFor(phones, matched.displayName, matched.number)
                    ?: return Hit.Stop("I couldn't match a saved number for ${matched.displayName}.")
                Hit.Ready(matched.displayName, matched.number, contactId)
            }
            is ContactMatchResult.ManyNumbers -> {
                val mobiles = matched.options.filter { it.kind == PhoneKind.Mobile }
                if (mobiles.size == 1) {
                    val phone = mobiles.single()
                    Hit.Ready(matched.displayName, phone.number, phone.contactId)
                } else {
                    Hit.Stop(
                        "${matched.displayName} has several numbers, $stopped. " +
                            "Nothing was sent to the server.",
                    )
                }
            }
            is ContactMatchResult.ManyContacts -> Hit.Stop(ContactMatcher.describe(matched))
            is ContactMatchResult.None,
            is ContactMatchResult.MissingKind,
            -> Hit.Miss
        }
    }

    private fun contactIdFor(phones: List<ContactPhone>, displayName: String, number: String): Long? {
        val target = ContactMatcher.normalizeNumber(number)
        return phones.firstOrNull {
            it.displayName.equals(displayName, ignoreCase = true) &&
                ContactMatcher.normalizeNumber(it.number) == target
        }?.contactId
    }

    private sealed class Hit {
        data class Ready(val displayName: String, val number: String, val contactId: Long) : Hit()
        data class Stop(val message: String) : Hit()
        data object Miss : Hit()
    }
}
