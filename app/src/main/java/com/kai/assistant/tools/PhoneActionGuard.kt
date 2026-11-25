package com.kai.assistant.tools

/**
 * Sent first in every cloud chat so the model answers as the assistant that runs on
 * this phone. Phone actions themselves never reach the cloud; they run on the device.
 */
internal const val KAI_SYSTEM_PROMPT =
    "You are KAI, a voice-first personal assistant running on the user's Android phone. " +
        "The KAI app on this phone can place phone calls, send WhatsApp messages and start WhatsApp " +
        "voice and video calls, open SMS, open apps, set alarms, timers and reminders, read and add " +
        "calendar events, toggle the flashlight, change volume, take screenshots, describe photos, " +
        "open maps, and remember things the user asks it to. Those requests are handled on the phone " +
        "before they reach you. Never say you can't make calls, send messages or control the phone. " +
        "If the user asks for one of those actions, tell them the exact words to say, for example " +
        "\"call Kaif\", \"WhatsApp Kaif I'm on my way\" or \"set an alarm for 7 a.m.\". " +
        "Business books for the furniture shop — sales, expenses, orders, labour, stock, bills and debts — " +
        "are saved on this phone only. You cannot pay anyone, place a supplier order, or message a supplier. " +
        "If the user is recording money, tell them to say it as a command, for example " +
        "\"I paid 5000 to the wood supplier\". " +
        "Keep answers short and easy to speak aloud."

private val questionLead = Regex(
    """^(?:what|what's|whats|how|why|who|whom|whose|when|where|which|is|are|was|were|do|does|did|""" +
        """should|would|could|explain|define|describe|tell me|give me|show me how)\b""",
)

private val phoneVerbs: List<Pair<Regex, String>> = listOf(
    Regex("""^(?:video\s+call|whats\s?app\s+(?:video\s+)?call)\b""") to
        "Say \"video call\" and a contact name, like \"video call Kaif on WhatsApp\".",
    Regex("""^(?:call|dial|ring|phone)\b(?!\s+me\s+back)""") to
        "Say \"call\" and a contact name or number, like \"call Kaif\" or \"call 98765 43210\".",
    Regex("""^(?:whats\s?app|message|msg|text|sms|send\s+(?:a\s+)?(?:message|text|whatsapp|sms))\b""") to
        "Say who and what, like \"WhatsApp Kaif I'm on my way\" or \"SMS mom running late\".",
    Regex("""^(?:set|create|add|make|put)\s+(?:an?\s+|the\s+|my\s+)?(?:alarm|timer|reminder)\b|^wake\s+me\b""") to
        "Say the time, like \"set an alarm for 7 a.m.\", \"timer for 10 minutes\" or \"remind me at 5 to call mom\".",
    Regex("""^remind\s+me\b""") to
        "Say when and what, like \"remind me at 5 p.m. to call mom\" or \"remind me in 20 minutes to check the oven\".",
    Regex("""^(?:cancel|stop|delete|remove|turn\s+off)\s+(?:the\s+|my\s+)?(?:alarm|timer|reminder)s?\b""") to
        "Say \"cancel the timer\" or \"cancel the reminder to\" and what it was for.",
)

/**
 * A line that starts like a phone command but didn't fully parse gets a local hint
 * instead of going to cloud chat, where the model may claim it can't act.
 * Questions ("how do I call abroad?") and "call me back" still go to chat.
 */
internal fun phoneActionHint(utterance: String): String? {
    val text = PhraseNormalizer.clean(utterance).lowercase().trim()
    if (text.isEmpty() || text.endsWith("?") || questionLead.containsMatchIn(text)) return null
    return phoneVerbs.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.let { (_, hint) ->
        "I didn't catch all of that. $hint"
    }
}

private val cantAct = Regex(
    """\b(?:i\s+(?:can't|cannot|can not|am unable to|am not able to|don't have the ability to|do not have the ability to)|""" +
        """i'm\s+(?:unable|not able)\s+to|as an ai[^.]*?(?:can't|cannot))\s+""" +
        """(?:directly\s+|actually\s+|physically\s+)?""" +
        """(?:make|place|initiate|start|send|set|open|access|control|dial|call|text|message|whatsapp)""" +
        """[^.!?\n]*(?:call|phone|message|text|sms|whatsapp|alarm|timer|reminder|app|device|contacts?)[^.!?\n]*[.!?]?""",
    RegexOption.IGNORE_CASE,
)

/**
 * Removes "I can't make calls" style sentences a cloud model may still produce, and
 * points at the words that do it on this phone.
 */
internal fun fixCantActReply(reply: String): String {
    if (!cantAct.containsMatchIn(reply)) return reply
    val stripped = cantAct.replace(reply, "").replace(Regex("""\s{2,}"""), " ").trim()
    val hint = "I can do that on this phone. Say it as a command, like \"call Kaif\", " +
        "\"WhatsApp Kaif I'm on my way\" or \"set an alarm for 7 a.m.\""
    return if (stripped.length < 12) hint else "$hint $stripped"
}
