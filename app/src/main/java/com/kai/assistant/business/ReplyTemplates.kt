package com.kai.assistant.business

import java.time.LocalDateTime

/**
 * Fixed wording filled only from [BusinessInfo]. Used when the backend is unreachable,
 * and whenever the backend's wording fails [AnswerGuard].
 */
object ReplyTemplates {
    private val topicOrder = listOf(
        Topic.Address, Topic.Hours, Topic.Phone, Topic.Website,
        Topic.Catalog, Topic.Price, Topic.Delivery, Topic.Offers,
    )

    /** Sent once while the owner is being alerted. Promises nothing but a reply. */
    fun holding(info: BusinessInfo, language: ReplyLanguage): String {
        val name = info.businessName.trim()
        return when (language) {
            ReplyLanguage.English ->
                if (name.isEmpty()) {
                    "Thank you for your message. We will reply to you shortly."
                } else {
                    "Thank you for contacting $name. We will reply to you shortly."
                }
            ReplyLanguage.Hindi ->
                if (name.isEmpty()) {
                    "आपके संदेश के लिए धन्यवाद। हम जल्द ही आपको जवाब देंगे।"
                } else {
                    "$name से संपर्क करने के लिए धन्यवाद। हम जल्द ही आपको जवाब देंगे।"
                }
            ReplyLanguage.Hinglish ->
                if (name.isEmpty()) {
                    "Aapke message ke liye dhanyavaad. Hum jald hi aapko reply karenge."
                } else {
                    "$name se sampark karne ke liye dhanyavaad. Hum jald hi aapko reply karenge."
                }
        }
    }

    /**
     * One line per topic, in a fixed order. Null when the owner has not filled in
     * every topic asked about: KAI then passes the question on instead of half-answering.
     */
    fun answer(
        topics: Set<Topic>,
        info: BusinessInfo,
        language: ReplyLanguage,
        customerText: String = "",
    ): String? {
        if (topics.isEmpty() || topics.any { !info.has(it) }) return null
        return topicOrder.filter { it in topics }
            .joinToString("\n") { line(it, info, language, customerText) }
    }

    fun visitProposal(info: BusinessInfo, language: ReplyLanguage, slot: LocalDateTime): String {
        val day = visitDayText(slot, language)
        val time = visitTimeText(slot, language)
        val address = info.address.trim()
        return when (language) {
            ReplyLanguage.English ->
                "You are welcome to visit our showroom. Would $day at $time suit you?" +
                    if (address.isEmpty()) "" else "\nAddress: $address"
            ReplyLanguage.Hindi ->
                "आपका हमारे शोरूम में स्वागत है। क्या आप $day को $time आ सकते हैं?" +
                    if (address.isEmpty()) "" else "\nपता: $address"
            ReplyLanguage.Hinglish ->
                "Aapka hamare showroom mein swagat hai. Kya aap $day ko $time aa sakte hain?" +
                    if (address.isEmpty()) "" else "\nAddress: $address"
        }
    }

    fun visitConfirmed(info: BusinessInfo, language: ReplyLanguage, slot: LocalDateTime): String {
        val day = visitDayText(slot, language)
        val time = visitTimeText(slot, language)
        val address = info.address.trim()
        return when (language) {
            ReplyLanguage.English ->
                "Thank you. See you on $day at $time." +
                    if (address.isEmpty()) "" else "\nAddress: $address"
            ReplyLanguage.Hindi ->
                "धन्यवाद। $day को $time मिलते हैं।" +
                    if (address.isEmpty()) "" else "\nपता: $address"
            ReplyLanguage.Hinglish ->
                "Dhanyavaad. $day ko $time milte hain." +
                    if (address.isEmpty()) "" else "\nAddress: $address"
        }
    }

    private fun line(topic: Topic, info: BusinessInfo, language: ReplyLanguage, customerText: String): String =
        when (topic) {
            Topic.Address -> labelled(language, "Our showroom address", "हमारे शोरूम का पता", "Hamare showroom ka address", info.address)
            Topic.Hours -> labelled(language, "Showroom timings", "शोरूम का समय", "Showroom ka time", hoursText(info.hours, language))
            Topic.Phone -> labelled(language, "Our phone number", "हमारा फ़ोन नंबर", "Hamara phone number", info.phone)
            Topic.Website -> labelled(language, "Our website", "हमारी वेबसाइट", "Hamari website", info.website)
            Topic.Catalog -> catalog(info, language)
            Topic.Price -> prices(info, language, customerText)
            Topic.Delivery -> labelled(language, "We deliver in", "हम यहाँ डिलीवरी करते हैं", "Hum yahan delivery karte hain", info.deliveryArea)
            Topic.Offers -> labelled(language, "Current offers", "अभी के ऑफ़र", "Abhi ke offers", info.offers)
        }

    private fun labelled(
        language: ReplyLanguage,
        english: String,
        hindi: String,
        hinglish: String,
        value: String,
    ): String {
        val label = when (language) {
            ReplyLanguage.English -> english
            ReplyLanguage.Hindi -> hindi
            ReplyLanguage.Hinglish -> hinglish
        }
        return "$label: ${value.trim()}"
    }

    private fun catalog(info: BusinessInfo, language: ReplyLanguage): String {
        val names = info.products.map { it.name.trim() }.filter { it.isNotEmpty() }
        val site = info.website.trim()
        val lines = mutableListOf<String>()
        if (names.isNotEmpty()) {
            lines += labelled(language, "We make", "हम बनाते हैं", "Hum banate hain", names.joinToString(", "))
        }
        if (site.isNotEmpty()) {
            lines += labelled(language, "See our catalog here", "हमारा कैटलॉग यहाँ देखें", "Hamara catalog yahan dekhein", site)
        }
        return lines.joinToString("\n")
    }

    /** The ranges for the products named in the message, or every range when none is named. */
    private fun prices(info: BusinessInfo, language: ReplyLanguage, customerText: String): String {
        val priced = info.products.filter { it.name.isNotBlank() && it.priceRange.isNotBlank() }
        val asked = customerText.lowercase()
        val named = priced.filter { product ->
            Regex("[\\p{L}]{3,}").findAll(product.name.lowercase()).any { asked.contains(it.value) }
        }
        val shown = named.ifEmpty { priced }
        val header = when (language) {
            ReplyLanguage.English -> "Our price range"
            ReplyLanguage.Hindi -> "हमारी कीमतें"
            ReplyLanguage.Hinglish -> "Hamare price range"
        }
        return "$header:\n" + shown.joinToString("\n") { "${it.name.trim()}: ${it.priceRange.trim()}" }
    }
}
