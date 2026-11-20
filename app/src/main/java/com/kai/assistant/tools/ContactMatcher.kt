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
 * Exact display-name matches win over "Kaif" inside "Kaif Ahmed".
 * A name fragment such as "kai" does not match "Kaif".
 */
object ContactMatcher {
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
                val quality = nameQuality(normalizedQuery, name)
                if (quality == 0) null else ScoredContact(id, name, quality, rows)
            }
        if (scored.isEmpty()) return ContactMatchResult.None(query)

        val best = scored.maxOf { it.quality }
        val pool = scored.filter { it.quality == best }
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

    fun normalizeName(value: String): String {
        val stripped = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return stripped
            .trim()
            .lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace(Regex("[.]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun normalizeNumber(value: String): String {
        val compact = value.trim()
        val leadingPlus = compact.startsWith("+")
        val digits = compact.filter { it.isDigit() }
        return if (leadingPlus) "+$digits" else digits
    }

    fun nameQuality(normalizedQuery: String, displayName: String): Int {
        val name = normalizeName(displayName)
        if (normalizedQuery.isEmpty() || name.isEmpty()) return 0
        if (normalizedQuery == name) return 100
        val tokens = name.split(" ")
        val queryTokens = normalizedQuery.split(" ")
        if (queryTokens.size == 1 && normalizedQuery in tokens) return 80
        if (queryTokens.size > 1 &&
            tokens.size >= queryTokens.size &&
            tokens.take(queryTokens.size) == queryTokens
        ) {
            return 80
        }
        if (queryTokens.size == tokens.size && queryTokens.zip(tokens).all { (query, token) ->
                tokensClose(query, token)
            }
        ) {
            val distance = queryTokens.zip(tokens).sumOf { (query, token) -> editDistance(query, token) }
            return 60 - distance
        }
        if (queryTokens.size == 1) {
            val query = queryTokens.single()
            val closest = tokens
                .filter { tokensClose(query, it) }
                .minOfOrNull { editDistance(query, it) }
            if (closest != null) return 40 - closest
        }
        return 0
    }

    private val spokenFamilies = listOf(
        setOf("kaif", "kev", "ke", "kef", "cab"),
        setOf("mohamed", "mohammad", "muhammad", "mohammed", "mohamad", "muhammed", "muhamad"),
    )

    private fun tokensClose(query: String, token: String): Boolean {
        if (query == token) return true
        if (spokenFamilies.any { family -> query in family && token in family }) return true
        if (phoneticKey(query) != phoneticKey(token)) return false
        val distance = editDistance(query, token)
        val limit = when {
            minOf(query.length, token.length) <= 4 -> 1
            minOf(query.length, token.length) <= 8 -> 3
            else -> 4
        }
        return distance <= limit
    }

    private fun phoneticKey(token: String): String {
        if (token.isEmpty()) return ""
        val folded = buildString {
            var index = 0
            while (index < token.length) {
                val char = token[index]
                if (char == 'c' && index + 1 < token.length && token[index + 1] == 'k') {
                    append('k')
                    index += 2
                    continue
                }
                if (char == 'p' && index + 1 < token.length && token[index + 1] == 'h') {
                    append('f')
                    index += 2
                    continue
                }
                append(
                    when (char) {
                        'c', 'q', 'k' -> 'k'
                        'v', 'f' -> 'f'
                        'z', 's' -> 's'
                        'x' -> 'k'
                        else -> char
                    },
                )
                index++
            }
        }
        val key = StringBuilder()
        folded.forEachIndexed { index, char ->
            val vowel = char == 'a' || char == 'e' || char == 'i' || char == 'o' || char == 'u' || char == 'y'
            if (index == 0) {
                key.append(char)
            } else if (!vowel && (key.isEmpty() || char != key.last())) {
                key.append(char)
            }
        }
        return key.toString()
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
        val quality: Int,
        val phones: List<ContactPhone>,
    )
}
