package com.kai.assistant.tools

import java.net.URLEncoder

internal fun parseYouTubeSearch(raw: String): String? {
    val text = raw.trim()
        .replace(Regex("[?.!,]+$"), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()
    if (text.isBlank()) return null
    val match = youtubeSearch.find(text) ?: return null
    val query = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (query.length < 1 || query == "youtube") return null
    return query
}

internal fun youtubeResultsUrl(query: String): String {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")
    return "https://www.youtube.com/results?search_query=$encoded"
}

private val youtubeSearch = Regex(
    """^(?:please\s+)?(?:search|look up|lookup)\s+(?:for\s+)?(.+?)\s+on\s+youtube$""" +
        """|^(?:please\s+)?(?:search|look up|lookup)\s+youtube\s+for\s+(.+)$""" +
        """|^(?:please\s+)?youtube\s+search(?:\s+for)?\s+(.+)$""",
)
