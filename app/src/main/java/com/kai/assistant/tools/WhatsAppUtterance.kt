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
        """^(?:please\s+)?send\s+(.+?)\s+to\s+(.+?)\s+(?:on|via|using|through)\s+whats\s?app$""",
    )
    private val whatsAppFirst = Regex(
        """^(?:please\s+)?whats\s?app\s+(.+)$""",
    )
    private val nameWord = Regex("""^\p{L}[\p{L}.'’\-]*$""")
    private val notAName = setOf(
        "me", "you", "him", "her", "them", "us", "it", "someone", "somebody",
        "anyone", "everybody", "a", "an", "the", "to", "on", "via", "whatsapp",
        "message", "text", "send",
    )

    fun parse(normalizedText: String): Parsed? {
        val text = normalizedText.trim().removeSuffix(" please").trim()
        if (text.isEmpty()) return null
        sendForm.find(text)?.let { match ->
            val message = match.groupValues[1].trim()
            val name = match.groupValues[2].trim()
            if (message.isEmpty() || !isContactName(name)) return null
            return Parsed(listOf(Option(name, message)))
        }
        val tail = whatsAppFirst.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        if (tail.isEmpty()) return null
        val options = splitNameAndMessage(tail)
        if (options.isEmpty()) return null
        return Parsed(options)
    }

    fun splitNameAndMessage(tail: String): List<Option> {
        val tokens = tail.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size < 2) return emptyList()
        val maxNameWords = minOf(4, tokens.size - 1)
        return (maxNameWords downTo 1).mapNotNull { count ->
            val name = tokens.take(count).joinToString(" ")
            val message = tokens.drop(count).joinToString(" ")
            if (message.isBlank() || !isContactName(name)) null else Option(name, message)
        }
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
    ) : WhatsAppResolution()

    data class Speak(val message: String) : WhatsAppResolution()
}

object WhatsAppResolver {
    fun resolve(options: List<WhatsAppUtterance.Option>, phones: List<ContactPhone>): WhatsAppResolution {
        if (options.isEmpty()) {
            return WhatsAppResolution.Speak("I need a name and a message to open WhatsApp.")
        }
        for (option in options) {
            when (val matched = ContactMatcher.match(option.nameQuery, phones, preferredKind = null)) {
                is ContactMatchResult.One -> return WhatsAppResolution.Ready(
                    displayName = matched.displayName,
                    number = matched.number,
                    message = option.message,
                )
                is ContactMatchResult.ManyNumbers -> {
                    val mobiles = matched.options.filter { it.kind == PhoneKind.Mobile }
                    if (mobiles.size == 1) {
                        return WhatsAppResolution.Ready(
                            displayName = matched.displayName,
                            number = mobiles.single().number,
                            message = option.message,
                        )
                    }
                    return WhatsAppResolution.Speak(
                        "${matched.displayName} has several numbers, so I didn't open WhatsApp. " +
                            "Nothing was sent to the server.",
                    )
                }
                is ContactMatchResult.ManyContacts ->
                    return WhatsAppResolution.Speak(ContactMatcher.describe(matched))
                is ContactMatchResult.None,
                is ContactMatchResult.MissingKind,
                -> Unit
            }
        }
        return WhatsAppResolution.Speak(ContactMatcher.describe(ContactMatchResult.None(options.first().nameQuery)))
    }
}
