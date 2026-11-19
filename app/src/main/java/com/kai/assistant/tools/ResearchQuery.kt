package com.kai.assistant.tools

data class ResearchSource(val title: String, val url: String)

internal const val RESEARCH_OFFLINE =
    "I'm offline right now, so I couldn't search the web. I don't have sources for that."

internal const val RESEARCH_UNAVAILABLE =
    "The research service isn't available right now. I don't have sources for that."

/**
 * Query for POST /v1/research, or null when the utterance is ordinary chat.
 * Local phone commands are decided first and never reach this.
 */
internal fun researchQuery(raw: String): String? {
    val text = raw.trim().replace(Regex("[?.!,]+$"), "").replace(Regex("""\s+"""), " ").trim()
    if (text.isBlank()) return null
    val lower = text.lowercase()
    for (pattern in researchPatterns) {
        val match = pattern.find(lower) ?: continue
        val captured = match.groups[1]
        if (captured == null || captured.value.isBlank()) return patternFallback(lower)
        val query = if (text.length == lower.length) {
            text.substring(captured.range).trim()
        } else {
            captured.value.trim()
        }
        if (query.length >= 2) return query
    }
    return null
}

internal fun formatResearchReply(answer: String, sources: List<ResearchSource>): String {
    val body = answer.trim()
    val listed = sources.map { source ->
        val title = source.title.trim().ifBlank { source.url }
        "$title\n${source.url}"
    }
    return when {
        body.isBlank() && listed.isEmpty() ->
            "The research service didn't return an answer or sources."
        listed.isEmpty() -> body
        body.isBlank() -> "Sources:\n" + listed.joinToString("\n")
        else -> body + "\n\nSources:\n" + listed.joinToString("\n")
    }
}

internal fun formatResearchSpoken(answer: String, sources: List<ResearchSource>): String {
    val body = answer.trim().ifBlank { "I couldn't get an answer." }
    if (sources.isEmpty()) return body
    val titles = sources.map { it.title.trim().ifBlank { "a source" } }.distinct().take(3)
    return "$body Sources: ${titles.joinToString(", ")}."
}

private fun patternFallback(lower: String): String {
    return when {
        lower.contains("news") || lower.contains("headline") -> "news"
        else -> lower
    }
}

private val researchPatterns = listOf(
    Regex(
        """^(?:please\s+)?(?:search|google|look up|lookup)\s+(?:the\s+)?(?:web|internet|online)\s+(?:for\s+)?(.+)$""",
    ),
    Regex("""^(?:please\s+)?(?:web|internet|online)\s+search(?:\s+for)?\s+(.+)$"""),
    Regex("""^(?:please\s+)?look\s+up\s+(.+?)\s+on\s+the\s+(?:web|internet)$"""),
    Regex("""^(?:please\s+)?search\s+for\s+(.+)$"""),
    Regex(
        """^(?:please\s+)?(?:what(?:'s| is)|whats)\s+(?:the\s+)?(?:latest\s+)?news(?:\s+(?:about|on)\s+(.+))?$""",
    ),
    Regex("""^(?:please\s+)?(?:latest|today's|todays|current)\s+news(?:\s+(?:about|on)\s+(.+))?$"""),
    Regex("""^(?:please\s+)?(?:news|headlines)(?:\s+(?:about|on)\s+(.+))?$"""),
    Regex("""^(?:please\s+)?(?:what(?:'s| is)|whats)\s+(?:the\s+)?current\s+(.+)$"""),
    Regex("""^(?:please\s+)?(?:what(?:'s| is)|whats)\s+happening(?:\s+(?:with|in|to)\s+(.+))?$"""),
    Regex("""^(?:please\s+)?(?:latest|recent)\s+(?:on|about)\s+(.+)$"""),
)
