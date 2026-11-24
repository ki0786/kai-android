package com.kai.assistant.tools

import java.text.Normalizer
import java.util.Locale

/** Which saved number to prefer when a contact has more than one. */
enum class PhoneKind { Mobile, Home, Work, Other }

data class ContactPhone(
    val contactId: Long,
    val displayName: String,
    val number: String,
    val kind: PhoneKind,
)

data class ContactOption(
    val displayName: String,
    val number: String,
)

sealed class ContactMatchResult {
    data class One(
        val displayName: String,
        val number: String,
        val kind: PhoneKind,
    ) : ContactMatchResult()

    data class None(val query: String) : ContactMatchResult()

    data class ManyContacts(
        val query: String,
        val options: List<ContactOption>,
    ) : ContactMatchResult()

    data class ManyNumbers(
        val displayName: String,
        val options: List<ContactPhone>,
    ) : ContactMatchResult()

    data class MissingKind(
        val displayName: String,
        val kind: PhoneKind,
    ) : ContactMatchResult()
}

/**
 * Picks a single on-device contact for a spoken name.
 * Every saved contact is scored with edit distance and a phonetic key tuned for
 * Indian English speech ("Kev", "Kef" and "Cab" all sound like "Kaif"), and the
 * closest one wins. Only an exact tie between two contacts asks for the full name.
 */
object ContactMatcher {
    /** Lowest score that still counts as the spoken name. */
    const val MIN_SCORE = 0.6
    private const val TIE = 0.001
    private const val EXACT = 1.05
    private const val EXACT_WITHOUT_TITLES = 1.03
    private const val UNMATCHED_QUERY_TOKEN = 0.25

    fun match(
        query: String,
        phones: List<ContactPhone>,
        preferredKind: PhoneKind?,
    ): ContactMatchResult {
        val normalizedQuery = normalizeName(query)
        if (normalizedQuery.isBlank()) return ContactMatchResult.None(query)

        val scored = phones
            .groupBy { it.contactId }
            .mapNotNull { (id, rows) ->
                val name = rows.firstOrNull { it.displayName.isNotBlank() }?.displayName ?: return@mapNotNull null
                val score = nameScore(normalizedQuery, name)
                if (score < MIN_SCORE) null else ScoredContact(id, name, score, rows)
            }
        if (scored.isEmpty()) return ContactMatchResult.None(query)

        val best = scored.maxOf { it.score }
        val pool = scored.filter { best - it.score < TIE }
        if (pool.size > 1) {
            val options = pool
                .map { contact ->
                    ContactOption(
                        displayName = contact.name,
                        number = sampleNumber(contact.phones)?.number.orEmpty(),
                    )
                }
                .sortedBy { normalizeName(it.displayName) }
            return ContactMatchResult.ManyContacts(query, options)
        }

        val contact = pool.single()
        return pickNumber(contact.name, contact.phones, preferredKind)
    }

    fun describe(result: ContactMatchResult): String = when (result) {
        is ContactMatchResult.None ->
            "I couldn't find a contact named ${displayQuery(result.query)} on this phone."
        is ContactMatchResult.ManyContacts -> {
            val shown = result.options.take(5).joinToString(", ") { option ->
                val number = normalizeNumber(option.number)
                if (number.isBlank()) option.displayName else "${option.displayName} ($number)"
            }
            val extra = result.options.size - 5
            val more = if (extra > 0) ", and $extra more" else ""
            "I found several contacts matching ${displayQuery(result.query)}: $shown$more. Say the full name."
        }
        is ContactMatchResult.ManyNumbers -> {
            val shown = result.options.take(5).joinToString(", ") { phone ->
                "${kindLabel(phone.kind)} ${normalizeNumber(phone.number)}"
            }
            "${result.displayName} has several numbers: $shown. Say call ${result.displayName} and the number you want."
        }
        is ContactMatchResult.MissingKind ->
            "${result.displayName} doesn't have a ${kindLabel(result.kind)} number saved."
        is ContactMatchResult.One ->
            confirmationPrompt(result.displayName, result.number, null)
    }

    fun confirmationPrompt(
        displayName: String,
        number: String,
        preferredKind: PhoneKind?,
    ): String {
        val who = when (preferredKind) {
            PhoneKind.Mobile -> "$displayName's mobile"
            PhoneKind.Home -> "$displayName's home number"
            PhoneKind.Work -> "$displayName's work number"
            PhoneKind.Other, null -> displayName
        }
        return "Call $who at $number?"
    }

    fun displayQuery(query: String): String =
        query.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
                }
            }

    fun kindLabel(kind: PhoneKind): String = when (kind) {
        PhoneKind.Mobile -> "mobile"
        PhoneKind.Home -> "home"
        PhoneKind.Work -> "work"
        PhoneKind.Other -> "phone"
    }

    /** Lowercase letters and digits separated by single spaces. Accents, emoji and punctuation go. */
    fun normalizeName(value: String): String {
        val stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return stripped
            .lowercase(Locale.ROOT)
            .replace(Regex("[’'.]"), "")
            .replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
            .trim()
    }

    fun normalizeNumber(value: String): String {
        val compact = value.trim()
        val leadingPlus = compact.startsWith("+")
        val digits = compact.filter { it.isDigit() }
        return if (leadingPlus) "+$digits" else digits
    }

    /** The closest any saved contact comes to [query]; 0 when none clears [MIN_SCORE]. */
    fun bestScore(query: String, phones: List<ContactPhone>): Double {
        val normalizedQuery = normalizeName(query)
        if (normalizedQuery.isBlank()) return 0.0
        val best = phones.asSequence()
            .map { it.displayName }
            .filter { it.isNotBlank() }
            .distinct()
            .maxOfOrNull { nameScore(normalizedQuery, it) } ?: 0.0
        return if (best < MIN_SCORE) 0.0 else best
    }

    /**
     * How close a normalized spoken name is to a saved display name.
     * 1.05 is an exact match; [MIN_SCORE] is the lowest that still counts.
     */
    fun nameScore(normalizedQuery: String, displayName: String): Double {
        val name = normalizeName(displayName)
        if (normalizedQuery.isEmpty() || name.isEmpty()) return 0.0
        if (normalizedQuery == name) return EXACT
        val queryTokens = withoutTitles(normalizedQuery.split(" "))
        val nameTokens = withoutTitles(name.split(" "))
        if (queryTokens == nameTokens) return EXACT_WITHOUT_TITLES
        val aligned = alignedScore(queryTokens, nameTokens)
        val joined = tokenSimilarity(queryTokens.joinToString(""), nameTokens.joinToString("")) * 0.97
        return maxOf(aligned, joined)
    }

    /** Each spoken word pairs with its closest unused saved word. */
    private fun alignedScore(query: List<String>, name: List<String>): Double {
        val pairs = mutableListOf<Triple<Int, Int, Double>>()
        for (q in query.indices) {
            for (n in name.indices) {
                pairs += Triple(q, n, tokenSimilarity(query[q], name[n]))
            }
        }
        pairs.sortByDescending { it.third }
        val usedQuery = BooleanArray(query.size)
        val usedName = BooleanArray(name.size)
        var total = 0.0
        var matched = 0
        var firstAligned = false
        for ((q, n, sim) in pairs) {
            if (usedQuery[q] || usedName[n] || sim <= 0.0) continue
            usedQuery[q] = true
            usedName[n] = true
            total += sim
            matched++
            if (q == 0 && n == 0) firstAligned = true
        }
        total += (query.size - matched) * UNMATCHED_QUERY_TOKEN
        val average = total / query.size
        val coverage = matched.toDouble() / name.size
        val order = if (firstAligned) 0.02 else 0.0
        return average * (0.88 + 0.12 * coverage) + order
    }

    internal fun tokenSimilarity(left: String, right: String): Double {
        if (left == right) return 1.0
        if (left.isEmpty() || right.isEmpty()) return 0.0
        var spelled = 1.0 - editDistance(left, right).toDouble() / maxOf(left.length, right.length)
        val shorter = if (left.length <= right.length) left else right
        val longer = if (shorter === left) right else left
        if (shorter.length >= 3 && longer.startsWith(shorter)) spelled = maxOf(spelled, 0.8)
        val leftKey = phoneticKey(left)
        val rightKey = phoneticKey(right)
        val sounded = if (leftKey == rightKey) {
            0.9 + 0.08 * spelled
        } else {
            val keyLength = maxOf(leftKey.length, rightKey.length, 1)
            (1.0 - editDistance(leftKey, rightKey).toDouble() / keyLength) * 0.9
        }
        val similarity = maxOf(spelled, sounded)
        val sameStart = leftKey.firstOrNull() == rightKey.firstOrNull()
        return if (sameStart) similarity else similarity * 0.6
    }

    /**
     * Consonant skeleton. Labials (b p f v w) share one sound because spoken
     * "Kaif" comes back as "Kev", "Kef" or "Cab"; h is silent so
     * Mohamed, Mohammed, Muhammad and Mohd all read as "md".
     */
    internal fun phoneticKey(token: String): String {
        if (token.isEmpty()) return ""
        val folded = StringBuilder()
        var index = 0
        while (index < token.length) {
            val char = token[index]
            val next = token.getOrNull(index + 1)
            val pair = if (next != null) "$char$next" else ""
            val digraph = when (pair) {
                "ph", "bh" -> 'p'
                "kh", "ck", "gh" -> if (pair == "gh") 'g' else 'k'
                "th" -> 't'
                "dh" -> 'd'
                "sh" -> 's'
                "ch" -> 'c'
                else -> null
            }
            if (digraph != null) {
                folded.append(digraph)
                index += 2
                continue
            }
            val mapped = when (char) {
                'c' -> if (next == 'e' || next == 'i') 's' else 'k'
                'k', 'q' -> 'k'
                'x' -> 'k'
                'b', 'p', 'f', 'v', 'w' -> 'p'
                'z', 's' -> 's'
                'h' -> null
                'a', 'e', 'i', 'o', 'u', 'y' -> 'a'
                else -> char
            }
            if (mapped != null) folded.append(mapped)
            index++
        }
        val key = StringBuilder()
        folded.forEachIndexed { position, char ->
            if (position == 0) {
                key.append(char)
            } else if (char != 'a' && char != key.last()) {
                key.append(char)
            }
        }
        return key.toString()
    }

    private val titles = setOf(
        "bhai", "bhaiya", "bhaijaan", "bhaijan", "bhayya", "sir", "ji", "jee", "sahab", "saab",
        "sahib", "saheb", "uncle", "aunty", "auntie", "madam", "maam", "mam", "bro", "mr",
        "mrs", "ms", "dr", "new", "old",
    )

    private fun withoutTitles(tokens: List<String>): List<String> {
        val kept = tokens.filter { it.isNotBlank() && it !in titles }
        return kept.ifEmpty { tokens.filter { it.isNotBlank() } }
    }

    private fun editDistance(left: String, right: String): Int {
        val rows = Array(left.length + 1) { IntArray(right.length + 1) }
        for (i in left.indices) rows[i + 1][0] = i + 1
        for (j in right.indices) rows[0][j + 1] = j + 1
        for (i in left.indices) {
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                rows[i + 1][j + 1] = minOf(
                    rows[i][j + 1] + 1,
                    rows[i + 1][j] + 1,
                    rows[i][j] + cost,
                )
            }
        }
        return rows[left.length][right.length]
    }

    private fun pickNumber(
        displayName: String,
        phones: List<ContactPhone>,
        preferredKind: PhoneKind?,
    ): ContactMatchResult {
        val unique = phones
            .filter { it.number.any { char -> char.isDigit() } }
            .distinctBy { normalizeNumber(it.number) }
        if (unique.isEmpty()) return ContactMatchResult.None(displayName)

        if (preferredKind != null) {
            val typed = unique.filter { it.kind == preferredKind }
            return when {
                typed.isEmpty() -> ContactMatchResult.MissingKind(displayName, preferredKind)
                typed.size == 1 -> typed.single().asOne(displayName)
                else -> ContactMatchResult.ManyNumbers(displayName, typed)
            }
        }

        if (unique.size == 1) return unique.single().asOne(displayName)

        val mobiles = unique.filter { it.kind == PhoneKind.Mobile }
        if (mobiles.size == 1) return mobiles.single().asOne(displayName)
        return ContactMatchResult.ManyNumbers(displayName, unique)
    }

    private fun sampleNumber(phones: List<ContactPhone>): ContactPhone? {
        val unique = phones
            .filter { it.number.any { char -> char.isDigit() } }
            .distinctBy { normalizeNumber(it.number) }
        return unique.firstOrNull { it.kind == PhoneKind.Mobile } ?: unique.firstOrNull()
    }

    private fun ContactPhone.asOne(displayName: String) =
        ContactMatchResult.One(displayName, number, kind)

    private data class ScoredContact(
        val id: Long,
        val name: String,
        val score: Double,
        val phones: List<ContactPhone>,
    )
}
