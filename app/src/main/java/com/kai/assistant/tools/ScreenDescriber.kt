package com.kai.assistant.tools

/** One visible node from the accessibility tree. */
internal data class ScreenItem(
    val text: String? = null,
    val contentDescription: String? = null,
    val clickable: Boolean = false,
    val editable: Boolean = false,
)

/**
 * Speaks visible text and controls. Does not include anything that was not on the tree.
 */
internal fun describeScreen(appLabel: String?, items: List<ScreenItem>): String {
    val texts = LinkedHashSet<String>()
    val controls = LinkedHashSet<String>()
    for (item in items) {
        val text = item.text?.trim().orEmpty()
        val description = item.contentDescription?.trim().orEmpty()
        if (item.clickable || item.editable) {
            val label = description.ifBlank { text }
            if (label.isNotEmpty()) controls.add(label)
        } else if (text.isNotEmpty()) {
            texts.add(text)
        }
    }
    val textList = texts.take(MAX_ITEMS)
    val controlList = controls.take(MAX_ITEMS)
    if (textList.isEmpty() && controlList.isEmpty()) {
        return "I don't see any text or controls on this screen."
    }
    return buildString {
        append("On this screen")
        if (!appLabel.isNullOrBlank()) append(" in $appLabel")
        append(". ")
        if (textList.isNotEmpty()) {
            append("Text: ")
            append(textList.joinToString("; "))
            append(". ")
        }
        if (controlList.isNotEmpty()) {
            append("Controls: ")
            append(controlList.joinToString(", "))
            append(".")
        }
    }.trim()
}

private const val MAX_ITEMS = 8
