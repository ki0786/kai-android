package com.kai.assistant.tools

internal data class PhotoAsk(val useBackend: Boolean)

internal fun parsePhotoAsk(raw: String): PhotoAsk? {
    val text = raw.trim()
        .replace(Regex("[?.!,]+$"), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()
    if (text.isBlank()) return null
    val askedForBackend = backendTail.containsMatchIn(text)
    val core = text.replace(backendTail, "").trim()
    if (core !in photoPhrases) return null
    return PhotoAsk(useBackend = askedForBackend)
}

internal const val PHOTO_LABEL_MINIMUM = 0.55f

internal fun acceptedPhotoLabels(labels: List<Pair<String, Float>>): List<String> =
    labels
        .filter { (name, confidence) -> name.isNotBlank() && confidence >= PHOTO_LABEL_MINIMUM }
        .sortedByDescending { it.second }
        .map { it.first.trim() }
        .distinct()
        .take(6)

/**
 * Speaks subjects and any text read from the picture.
 * [askedForBackend] adds that the photo itself was not uploaded.
 */
internal fun describePhoto(
    labels: List<String>,
    textLines: List<String>,
    askedForBackend: Boolean = false,
): String {
    val subjects = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(6)
    val text = textLines
        .map { it.replace(Regex("""\s+"""), " ").trim() }
        .filter { it.length >= 2 }
        .distinct()
        .take(6)
    val body = when {
        subjects.isEmpty() && text.isEmpty() -> "I couldn't tell what's in this photo."
        subjects.isEmpty() -> "Text in the photo: ${text.joinToString(" ")}."
        text.isEmpty() -> "In this photo: ${subjects.joinToString(", ")}."
        else -> "In this photo: ${subjects.joinToString(", ")}. Text in the photo: ${text.joinToString(" ")}."
    }
    return if (askedForBackend) "$body I didn't upload the photo." else body
}

/** Coarse on-device color labels used when the image model returns nothing. */
internal fun colorLabels(argb: List<Int>): List<String> {
    if (argb.isEmpty()) return emptyList()
    val counts = linkedMapOf<String, Int>()
    argb.forEach { color ->
        val name = colorName(color)
        counts[name] = (counts[name] ?: 0) + 1
    }
    val top = counts.maxBy { it.value }
    val share = top.value.toFloat() / argb.size
    val label = if (share >= 0.45f) "mostly ${top.key}" else top.key
    return listOf(label)
}

internal fun colorName(argb: Int): String {
    val red = (argb shr 16) and 0xFF
    val green = (argb shr 8) and 0xFF
    val blue = argb and 0xFF
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (delta < 28) {
        return when {
            max < 45 -> "black"
            max > 210 -> "white"
            else -> "gray"
        }
    }
    val hue = when (max) {
        red -> ((green - blue) * 60f / delta + 360f) % 360f
        green -> (blue - red) * 60f / delta + 120f
        else -> (red - green) * 60f / delta + 240f
    }
    return when {
        hue < 20f || hue >= 345f -> "red"
        hue < 45f -> "orange"
        hue < 70f -> "yellow"
        hue < 165f -> "green"
        hue < 200f -> "cyan"
        hue < 255f -> "blue"
        hue < 290f -> "purple"
        else -> "pink"
    }
}

private val backendTail = Regex(
    """\s+(?:using|with|via|on)\s+(?:the\s+)?(?:backend|server|cloud)$""",
)

private val photoPhrases = setOf(
    "what's in this photo",
    "whats in this photo",
    "what is in this photo",
    "what's in the photo",
    "what is in the photo",
    "what's in my photo",
    "what's in this picture",
    "whats in this picture",
    "what is in this picture",
    "what's in the picture",
    "what's in this image",
    "what is in this image",
    "what's in the image",
    "describe this photo",
    "describe the photo",
    "describe this picture",
    "describe the picture",
    "describe this image",
    "describe the image",
    "tell me what's in this photo",
    "tell me what is in this photo",
)
