package com.kai.assistant.data

import java.util.UUID

enum class MessageRole { User, Assistant, System }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * In-memory session store for the MVP shell.
 * Room / backend sync comes later.
 */
object SessionMessageStore {
    private val _messages = mutableListOf<ChatMessage>()

    fun snapshot(): List<ChatMessage> = _messages.toList()

    fun add(message: ChatMessage) {
        _messages += message
    }

    fun clear() {
        _messages.clear()
    }
}
