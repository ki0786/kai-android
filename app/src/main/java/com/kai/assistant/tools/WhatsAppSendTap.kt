package com.kai.assistant.tools

import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides when a WhatsApp Send control belongs to the message the user just asked to send.
 * The accessibility service is the only caller, and only while a request is armed.
 */
internal data class SendNode(
    val contentDescription: String?,
    val viewId: String?,
    val text: String?,
    val editable: Boolean,
    val clickable: Boolean,
)

internal fun isSendControl(contentDescription: String?, viewId: String?): Boolean {
    val label = contentDescription?.trim()?.lowercase(Locale.ROOT)
    if (label == "send" || label == "send message") return true
    val id = viewId?.substringAfterLast('/')?.lowercase(Locale.ROOT)
    return id == "send"
}

internal fun textMatchesMessage(text: String?, message: String): Boolean {
    val wanted = message.trim()
    if (wanted.isEmpty()) return false
    return text?.trim() == wanted
}

/**
 * Index of the Send control once the composer holds [message].
 * Null means the chat is not ready, so the service must not tap.
 */
internal fun sendButtonIndex(nodes: List<SendNode>, message: String): Int? {
    if (nodes.none { it.editable && textMatchesMessage(it.text, message) }) return null
    val matches = nodes.indices.filter { index ->
        val node = nodes[index]
        isSendControl(node.contentDescription, node.viewId)
    }
    return matches.firstOrNull { nodes[it].clickable } ?: matches.firstOrNull()
}

internal fun shouldAskToEnable(serviceEnabled: Boolean, alreadyAsked: Boolean): Boolean =
    !serviceEnabled && !alreadyAsked

internal data class ArmedWhatsAppSend(
    val packageName: String,
    val message: String,
    val deadlineMs: Long,
)

/**
 * One in-flight WhatsApp send. Cleared after the tap, a failed open, or the deadline.
 */
internal object WhatsAppSendRequest {
    private val pending = AtomicReference<ArmedWhatsAppSend?>(null)
    const val WINDOW_MS = 20_000L

    fun arm(packageName: String, message: String, nowMs: Long, windowMs: Long = WINDOW_MS) {
        pending.set(
            ArmedWhatsAppSend(
                packageName = packageName,
                message = message,
                deadlineMs = nowMs + windowMs,
            ),
        )
    }

    fun peek(nowMs: Long): ArmedWhatsAppSend? {
        val current = pending.get() ?: return null
        if (nowMs > current.deadlineMs) {
            pending.compareAndSet(current, null)
            return null
        }
        return current
    }

    fun clear() {
        pending.set(null)
    }
}
