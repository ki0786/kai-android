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

/**
 * Name to speak after an app launch. YouTube is never spoken as its package name.
 */
internal fun spokenLaunchName(packageName: String, requestedName: String): String {
    val requested = requestedName.trim()
    if (packageName == "com.google.android.youtube" ||
        requested.equals("youtube", ignoreCase = true) ||
        requested.equals("yt", ignoreCase = true)
    ) {
        return "YouTube"
    }
    if (requested.isNotEmpty() && !requested.contains('.')) {
        return requested.replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase(java.util.Locale.US) else char.toString()
        }
    }
    val tail = packageName.substringAfterLast('.')
    return tail.replaceFirstChar { char ->
        if (char.isLowerCase()) char.titlecase(java.util.Locale.US) else char.toString()
    }
}

internal fun youtubeResultsUrl(query: String): String {
    val encoded = URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")
    return "https://www.youtube.com/results?search_query=$encoded"
}

private val youtubeSearch = Regex(
    """^(?:please\s+)?open\s+youtube\s+and\s+(?:play|watch|search(?:\s+for)?)\s+(.+)$""" +
        """|^(?:please\s+)?(?:play|watch)\s+(.+?)\s+on\s+youtube$""" +
        """|^(?:please\s+)?(?:search|look up|lookup)\s+(?:for\s+)?(.+?)\s+on\s+youtube$""" +
        """|^(?:please\s+)?(?:search|look up|lookup)\s+youtube\s+for\s+(.+)$""" +
        """|^(?:please\s+)?youtube\s+search(?:\s+for)?\s+(.+)$""",
)
