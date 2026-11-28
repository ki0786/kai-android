package com.kai.assistant.tools

import java.net.URLEncoder

internal fun parseYouTubeSearch(raw: String): String? {
    val text = raw.trim()
        .replace(Regex("[?.!,]+$"), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (text.isBlank()) return null
    val match = youtubeSearch.find(text) ?: return null
    val query = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (query.length < 1 || query.equals("youtube", ignoreCase = true)) return null
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

/** A YouTube link opens that video. Anything else opens search results. */
internal fun youtubeOpenUrl(query: String): String {
    val id = youtubeVideoId(query)
    return if (id != null) youtubeWatchUrl(id) else youtubeResultsUrl(query)
}

internal fun youtubeWatchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"

/**
 * First search-result video. YouTube pages often store the id as `\x22videoId\x22`.
 * A bare `videoId` later in the page can be a radio mix, so a videoRenderer wins.
 */
internal fun firstYouTubeVideoId(page: String): String? {
    val text = unescapeYouTubeMarkup(page)
    val renderer = Regex(
        """"videoRenderer"\s*:\s*\{"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""" +
            """|"compactVideoRenderer"\s*:\s*\{"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""",
    )
    renderer.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.let { return it }
    return Regex(""""videoId"\s*:\s*"([A-Za-z0-9_-]{11})"""").find(text)?.groupValues?.get(1)
}

internal fun unescapeYouTubeMarkup(page: String): String {
    if (!page.contains("\\x") && !page.contains("\\u00")) return page
    val hex = Regex("""\\x([0-9a-fA-F]{2})""")
    var text = hex.replace(page) { match -> match.groupValues[1].toInt(16).toChar().toString() }
    text = text.replace("\\u0026", "&").replace("\\u003d", "=").replace("\\u003D", "=")
    return text
}

/** Watch page for a resolved id. Playback uses this, not the search results page. */
internal fun resolvedYouTubeWatchUrl(page: String): String? {
    val id = firstYouTubeVideoId(page) ?: return null
    return youtubeWatchUrl(id)
}

/** Web innertube search. No provider key. The Android client is rejected without one. */
internal fun youTubeInnertubeBody(query: String): String {
    val escaped = query
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")
    return """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20241126.01.00","hl":"en","gl":"US"}},"query":"$escaped"}"""
}

internal fun youtubeVideoId(query: String): String? {
    val match = youtubeVideoUrl.find(query.trim()) ?: return null
    return match.groupValues[1].ifBlank { null }
}

private val youtubeVideoUrl = Regex(
    """(?:https?://)?(?:www\.|m\.)?(?:youtube\.com/(?:watch\?v=|embed/|shorts/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""",
)

private val youtubeSearch = Regex(
    """^(?:please\s+)?open\s+youtube\s+and\s+(?:play|watch|search(?:\s+for)?)\s+(.+)$""" +
        """|^(?:please\s+)?(?:play|watch)\s+(.+?)\s+on\s+youtube$""" +
        """|^(?:please\s+)?(?:search|look up|lookup)\s+(?:for\s+)?(.+?)\s+on\s+youtube$""" +
        """|^(?:please\s+)?(?:search|look up|lookup)\s+youtube\s+for\s+(.+)$""" +
        """|^(?:please\s+)?youtube\s+search(?:\s+for)?\s+(.+)$""",
    RegexOption.IGNORE_CASE,
)
