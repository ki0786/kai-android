package com.kai.assistant.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

enum class MessageRole { User, Assistant, System }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    /** Shown in the session, but omitted from backend chat requests. */
    val deviceOnly: Boolean = false,
)

/**
 * In-memory session transcript shared by voice + text chat.
 */
object SessionMessageStore {
    const val SESSION_ID: String = "local-session"

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    fun snapshot(): List<ChatMessage> = _messages.value

    fun add(message: ChatMessage) {
        _messages.update { it + message }
    }

    fun clear() {
        _messages.value = emptyList()
    }
}
