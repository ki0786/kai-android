package com.kai.assistant.tools

/** "SMS Kaif running late", "send a text message to mom saying hi", "send hi to 98765 43210 by sms". */
internal object SmsUtterance {
    private val saying = Regex(
        """^(?:send\s+)?(?:an?\s+)?(?:sms|text\s+message)\s+(?:to\s+)?(.+?)\s+(?:saying|that says|that|with the message|with)\s+(.+)$""",
    )
    private val messageFirst = Regex(
        """^send\s+(.+?)\s+to\s+(.+?)\s+(?:by|via|on|over|as\s+an?)\s+(?:sms|text message|text|messages)$""",
    )
    private val nameFirst = Regex("""^(?:send\s+)?(?:an?\s+)?(?:sms|text\s+message)\s+(?:to\s+)?(.+)$""")

    fun parse(text: String): List<WhatsAppUtterance.Option>? {
        saying.find(text)?.let { match ->
            return single(match.groupValues[1], match.groupValues[2])
        }
        messageFirst.find(text)?.let { match ->
            return single(match.groupValues[2], match.groupValues[1])
        }
        val tail = nameFirst.find(text)?.groupValues?.get(1)?.trim() ?: return null
        numberFirst(tail)?.let { return it }
        val options = WhatsAppUtterance.splitNameAndMessage(tail).filter { it.message.isNotBlank() }
        return options.ifEmpty { null }
    }

    private fun single(rawTarget: String, rawMessage: String): List<WhatsAppUtterance.Option>? {
        val target = rawTarget.trim()
        val message = rawMessage.trim()
        if (message.isEmpty()) return null
        if (!looksLikePhoneNumber(target) && !WhatsAppUtterance.isContactName(target)) return null
        return listOf(WhatsAppUtterance.Option(target, message))
    }

    private fun numberFirst(tail: String): List<WhatsAppUtterance.Option>? {
        val match = Regex("""^([+(]?\d[\d\s().-]{2,}\d)\s+(\D.*)$""").find(tail) ?: return null
        val number = match.groupValues[1].trim()
        if (!looksLikePhoneNumber(number)) return null
        return listOf(WhatsAppUtterance.Option(number, match.groupValues[2].trim()))
    }
}
