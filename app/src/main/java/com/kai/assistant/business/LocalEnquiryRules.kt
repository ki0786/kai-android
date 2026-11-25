package com.kai.assistant.business

/** How KAI treats one customer message. */
enum class EnquiryCategory { CommonQuestion, VisitRequest, Other }

/**
 * Rules that run on the phone with no backend. They are deliberately narrow: they
 * only recognise an address or opening-hours question, and they send anything that
 * looks like a complaint, an order, a payment or a negotiation straight to the owner.
 */
object LocalEnquiryRules {
    private const val OFFLINE_MAX_WORDS = 25

    private val addressPatterns = listOf(
        Regex("""\b(address|adress|addres|adres|location|located|landmark|directions?|google maps?|map link|maps?)\b"""),
        Regex("""\bwhere (is|are|r) (your|the|you|ur|u)\b"""),
        Regex("""\bhow (to|do i|can i) reach\b"""),
        Regex("""\b(kahan|kahaan|kaha|kidhar) (hai|h|he|hain|pe|par|padta|padti)\b"""),
        Regex("""\b(shop|showroom|dukaan|dukan|store|factory) (kahan|kahaan|kaha|kidhar)\b"""),
        Regex("""\bpata (bata|bhej|do|de|dijiye|chahiye|kya)"""),
        Regex("""\bka pata\b"""),
        Regex("""पता(?! नह)|कहाँ|कहां|किधर|लोकेशन|एड्रेस|नक्शा|मैप"""),
    )

    private const val DAY = "sunday|monday|tuesday|wednesday|thursday|friday|saturday|today|tomorrow|aaj|kal|now|abhi"
    private val hoursPatterns = listOf(
        Regex("""\b(timings?|time table|opening hours?|working hours?|business hours?|shop hours?|store hours?|opening time|closing time)\b"""),
        Regex("""\b(what time|when|till when|until when|kab|kab tak|kitne baje)\b.*\b(open|opens|close|closes|closed|closing|khul\w*|band)\b"""),
        Regex("""\b(are|is|r) (you|u|shop|showroom|store|it) (open|closed)\b"""),
        Regex("""\b(open|closed|khula|khuli|khulta|khulti|band|chalu) (on )?($DAY)\b"""),
        Regex("""\b($DAY) (ko )?(open|closed|khula|khuli|khulta|khulti|band|chalu)\b"""),
        Regex("""\b(shop|showroom|store|dukaan|dukan) (ka |ki )?(time|timing|timings)\b"""),
        Regex("""\bkitne baje (tak|se|khul|band)"""),
        Regex("""\b(khula|khuli|khulta|khulti|khulega|khulegi) (hai|h|he|hain|rehta|rahega|kya)\b"""),
        Regex("""\bkab se kab tak\b"""),
        Regex("""समय|टाइमिंग|टाइम|कब खुल|कब बंद|कब तक|कितने बजे|खुला|खुली|खुलता|खुलती|खुलेगा"""),
    )

    /** A message with any of these asks for more than an address or hours. */
    private val otherIntent = Regex(
        """\b(price|prices|rate|rates|cost|costs|kitna|kitne (ka|ki|me|mein)|daam|kimat|keemat|charges?|""" +
            """catalog|catalogue|photos?|pics?|images?|designs?|delivery|deliver|offers?|order|book|booking|""" +
            """visit|come|coming|aana|aau|aaun|aaunga|aaungi|aa (sakta|sakti|sakte|jau|jaun)|stock|available|""" +
            """custom\w*|size|warranty|guarantee)\b""" +
            """|कीमत|दाम|रेट|प्राइस|कितने का|कितने की|कितना|डिलीवरी|डिलिवरी|ऑफर|ऑफ़र|ऑर्डर|आना|आऊं|आऊँ|आ सकत|""" +
            """कैटलॉग|फोटो|डिज़ाइन|डिजाइन|स्टॉक|वारंटी""",
    )

    private val ownerOnly = Regex(
        """\b(complaint|complain\w*|damaged?|broken|defect\w*|(bad|poor) quality|not working|fraud|cheat\w*|scam|""" +
            """refund|money back|return|replace\w*|cancel\w*|late|delay\w*|not (received|delivered)|""" +
            """nahi (aaya|aayi|mila|mili)|kharab|toota|tuta|tooti|shikayat|wapas|""" +
            """(my|mera|meri|hamara) order|order (status|kab|kahan)|kab (aayega|aayegi|milega|milegi)|tracking|""" +
            """payment|paid|advance|upi|gpay|phonepe|paytm|account number|invoice|bill|receipt|emi|loan|finance|""" +
            """(last|final|best|lowest) (price|rate)|kam (karo|kar|ho|kijiye|nahi)|thoda kam|negotia\w*|bargain|""" +
            """less price|reduce|discount\w*|chhoot|chhut)\b""" +
            """|शिकायत|खराब|टूटा|टूटी|रिफंड|वापस|कैंसल|रद्द|पेमेंट|भुगतान|एडवांस|कम कर|कम हो|लास्ट प्राइस|फाइनल|""" +
            """मेरा ऑर्डर|ऑर्डर कब|कब आएगा|कब मिलेगा|नहीं आया|नहीं मिला|ईएमआई|किस्त|छूट|डिस्काउंट""",
    )

    private val mediaOnly = Regex(
        """^[\p{So}\p{Sk}\p{Cs}\uFE0F\u200D\s]*(photo|video|voice message|audio|document|location|contact|sticker|gif|image)?[\s.]*$""",
        RegexOption.IGNORE_CASE,
    )

    private val yesWords = setOf(
        "yes", "yeah", "yep", "ok", "okay", "okk", "okey", "sure", "fine", "done", "confirm", "confirmed",
        "haan", "han", "ha", "ji", "theek", "thik", "sahi", "chalega", "pakka", "bilkul", "great", "perfect",
        "हाँ", "हां", "जी", "ठीक", "पक्का", "बिल्कुल", "चलेगा",
    )
    private val noWords = setOf(
        "no", "not", "nope", "cant", "cannot", "can't", "dont", "don't", "nahi", "nahin", "nhi", "mat",
        "नहीं", "नही", "मत", "but", "lekin", "par", "लेकिन",
    )

    internal fun normalise(text: String): String =
        text.lowercase()
            .replace(Regex("""[^\p{L}\p{M}\p{N}']+"""), " ")
            .trim()

    /**
     * Address and hours are the only questions answered without the backend, and only
     * when the message asks nothing else.
     */
    fun offlineTopics(text: String): Set<Topic> {
        val clean = normalise(text)
        if (clean.isEmpty() || clean.split(' ').size > OFFLINE_MAX_WORDS) return emptySet()
        if (needsOwner(text) || otherIntent.containsMatchIn(clean)) return emptySet()
        val topics = mutableSetOf<Topic>()
        if (addressPatterns.any { it.containsMatchIn(clean) }) topics += Topic.Address
        if (hoursPatterns.any { it.containsMatchIn(clean) }) topics += Topic.Hours
        return topics
    }

    /** Complaints, orders, payments and price negotiation are never answered by KAI. */
    fun needsOwner(text: String): Boolean = ownerOnly.containsMatchIn(normalise(text))

    /** A photo, voice note or sticker with no words. KAI can't read it, so the owner is alerted. */
    fun isMediaOnly(text: String): Boolean = mediaOnly.matches(text.trim())

    /** A short plain "yes" to a time KAI proposed. A new time or a "but" is not a yes. */
    fun isYes(text: String): Boolean {
        if (text.contains('?') || text.any { it.isDigit() }) return false
        if (text.contains("👍") || text.contains("👌")) return normalise(text).split(' ').size <= 6
        val words = normalise(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 6) return false
        if (words.any { it in noWords }) return false
        return words.any { it in yesWords }
    }
}
