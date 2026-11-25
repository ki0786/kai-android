package com.kai.assistant.business

import java.time.DayOfWeek
import java.time.LocalDateTime

/** KAI replies in the language the customer wrote in. */
enum class ReplyLanguage { English, Hindi, Hinglish }

private val hinglishStrong = setOf(
    "kya", "hai", "hain", "kitna", "kitne", "kitni", "kahan", "kaha", "kidhar", "kab", "kaise", "kaisa",
    "chahiye", "chaiye", "batao", "bataiye", "bataye", "dijiye", "dedo", "bhejo", "bhejiye", "karo",
    "mujhe", "mujhko", "hume", "humko", "aap", "aapka", "aapki", "apka", "apki", "nahi", "nahin",
    "haan", "bhai", "bhaiya", "dukaan", "dukan", "daam", "kimat", "keemat", "khula", "khulta", "khulti",
    "baje", "aaj", "parso", "subah", "shaam", "milega", "milegi", "hoga", "hogi", "kaun", "konsa",
    "kaunsa", "sasta", "mehenga", "namaste", "namaskar", "shukriya", "dhanyavad", "dhanyavaad", "accha",
    "acha", "theek", "thik", "abhi", "yahan", "wahan", "sakta", "sakte", "sakti", "dekhna", "dekhne",
    "chahte", "chahta", "chahti", "hoon", "hun", "raha", "rahe", "rahi", "wala", "wali", "aur", "bhi",
    "pata", "kal", "ji",
)
private val hinglishWeak = setOf("ka", "ki", "ke", "ko", "se", "ho", "mein", "toh", "ek", "tak")

internal fun hasDevanagari(text: String): Boolean = text.count { it in '\u0900'..'\u097F' } >= 2

/** Devanagari is Hindi; Latin letters with Hindi words is Hinglish; anything else is English. */
fun detectReplyLanguage(text: String): ReplyLanguage {
    if (hasDevanagari(text)) return ReplyLanguage.Hindi
    val words = Regex("[a-z]+").findAll(text.lowercase()).map { it.value }.toList()
    if (words.any { it in hinglishStrong }) return ReplyLanguage.Hinglish
    if (words.count { it in hinglishWeak } >= 2) return ReplyLanguage.Hinglish
    return ReplyLanguage.English
}

/** True when the reply is in the same script the customer used. */
internal fun scriptMatches(reply: String, language: ReplyLanguage): Boolean =
    hasDevanagari(reply) == (language == ReplyLanguage.Hindi)

private val dayShortEnglish = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val dayShortHindi = listOf("सोम", "मंगल", "बुध", "गुरु", "शुक्र", "शनि", "रवि")
private val dayFullEnglish =
    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val dayFullHindi =
    listOf("सोमवार", "मंगलवार", "बुधवार", "गुरुवार", "शुक्रवार", "शनिवार", "रविवार")
private val monthEnglish =
    listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val monthHindi = listOf(
    "जनवरी", "फ़रवरी", "मार्च", "अप्रैल", "मई", "जून",
    "जुलाई", "अगस्त", "सितंबर", "अक्टूबर", "नवंबर", "दिसंबर",
)

private fun dayShort(day: DayOfWeek, language: ReplyLanguage): String =
    (if (language == ReplyLanguage.Hindi) dayShortHindi else dayShortEnglish)[day.value - 1]

internal fun clock12(minuteOfDay: Int): String {
    val hour24 = minuteOfDay / 60
    val hour12 = if (hour24 % 12 == 0) 12 else hour24 % 12
    return "$hour12:${"%02d".format(minuteOfDay % 60)}"
}

internal fun clockEnglish(minuteOfDay: Int): String =
    "${clock12(minuteOfDay)} ${if (minuteOfDay < 12 * 60) "AM" else "PM"}"

private fun partOfDay(minuteOfDay: Int, language: ReplyLanguage): String {
    val hindi = language == ReplyLanguage.Hindi
    return when (minuteOfDay / 60) {
        in 0..11 -> if (hindi) "सुबह" else "subah"
        in 12..15 -> if (hindi) "दोपहर" else "dopahar"
        in 16..19 -> if (hindi) "शाम" else "shaam"
        else -> if (hindi) "रात" else "raat"
    }
}

private fun spanText(span: DayHours?, language: ReplyLanguage): String {
    if (span == null) {
        return when (language) {
            ReplyLanguage.English -> "closed"
            ReplyLanguage.Hindi -> "बंद"
            ReplyLanguage.Hinglish -> "band"
        }
    }
    val open = span.openMinute
    val close = span.closeMinute
    return when (language) {
        ReplyLanguage.English -> "${clockEnglish(open)} – ${clockEnglish(close)}"
        ReplyLanguage.Hindi ->
            "${partOfDay(open, language)} ${clock12(open)} से ${partOfDay(close, language)} ${clock12(close)} तक"
        ReplyLanguage.Hinglish ->
            "${partOfDay(open, language)} ${clock12(open)} se ${partOfDay(close, language)} ${clock12(close)} tak"
    }
}

/** "Mon–Sat: 10:00 AM – 8:30 PM; Sun: closed". Blank when no hours were entered. */
fun hoursText(hours: OpeningHours, language: ReplyLanguage): String {
    if (!hours.isSet) return ""
    val days = DayOfWeek.entries
    val groups = mutableListOf<Triple<DayOfWeek, DayOfWeek, DayHours?>>()
    days.forEach { day ->
        val span = hours.forDay(day)
        val last = groups.lastOrNull()
        if (last != null && last.third == span) {
            groups[groups.lastIndex] = Triple(last.first, day, span)
        } else {
            groups += Triple(day, day, span)
        }
    }
    if (groups.size == 1) {
        val everyDay = when (language) {
            ReplyLanguage.English -> "Every day"
            ReplyLanguage.Hindi -> "रोज़"
            ReplyLanguage.Hinglish -> "Roz"
        }
        return "$everyDay: ${spanText(groups[0].third, language)}"
    }
    return groups.joinToString("; ") { (first, last, span) ->
        val label = if (first == last) {
            dayShort(first, language)
        } else {
            "${dayShort(first, language)}–${dayShort(last, language)}"
        }
        "$label: ${spanText(span, language)}"
    }
}

/** "Thursday, 26 Nov" */
internal fun visitDayText(at: LocalDateTime, language: ReplyLanguage): String {
    val day = at.dayOfWeek.value - 1
    val month = at.monthValue - 1
    return if (language == ReplyLanguage.Hindi) {
        "${dayFullHindi[day]}, ${at.dayOfMonth} ${monthHindi[month]}"
    } else {
        "${dayFullEnglish[day]}, ${at.dayOfMonth} ${monthEnglish[month]}"
    }
}

/** "11:00 AM", "सुबह 11:00 बजे" or "subah 11:00 baje" */
internal fun visitTimeText(at: LocalDateTime, language: ReplyLanguage): String {
    val minuteOfDay = at.hour * 60 + at.minute
    return when (language) {
        ReplyLanguage.English -> clockEnglish(minuteOfDay)
        ReplyLanguage.Hindi -> "${partOfDay(minuteOfDay, language)} ${clock12(minuteOfDay)} बजे"
        ReplyLanguage.Hinglish -> "${partOfDay(minuteOfDay, language)} ${clock12(minuteOfDay)} baje"
    }
}
