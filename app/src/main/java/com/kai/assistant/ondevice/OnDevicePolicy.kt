package com.kai.assistant.ondevice

import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole

/**
 * Public MediaPipe LLM Inference model.
 *
 * Gemma 3 1B int4 is the usual small Gemma build, but
 * `litert-community/Gemma3-1B-IT` is a gated Hugging Face repo (HTTP 401 without a token).
 * No API keys are embedded in this app, so KAI downloads this public Qwen2.5 0.5B
 * `.task` instead. It is built for the same LLM Inference API and fits a mid-range phone.
 */
object OnDeviceModel {
    const val DISPLAY_NAME = "Qwen2.5 0.5B Instruct"
    const val FILE_NAME = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val DOWNLOAD_URL =
        "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/" +
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    const val SIZE_BYTES = 546_660_344L
    const val SIZE_LABEL = "547 MB"

    /** Spoken and shown whenever a general question had to use the backend. */
    const val BACKEND_NOTE = "This reply used the backend."
}

enum class OnDevicePhase {
    NotDownloaded,
    Downloading,
    Downloaded,
    Loading,
    Ready,
    Failed,
}

data class OnDeviceStatus(
    val phase: OnDevicePhase,
    val bytesRead: Long = 0,
    val bytesTotal: Long = OnDeviceModel.SIZE_BYTES,
    val detail: String = "",
) {
    val fraction: Float
        get() = if (bytesTotal <= 0L) 0f else (bytesRead.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)

    fun shortLabel(): String = when (phase) {
        OnDevicePhase.NotDownloaded -> "not downloaded"
        OnDevicePhase.Downloading -> "downloading ${percent()}%"
        OnDevicePhase.Downloaded -> "downloaded"
        OnDevicePhase.Loading -> "loading"
        OnDevicePhase.Ready -> "ready"
        OnDevicePhase.Failed -> "failed"
    }

    private fun percent(): Int = (fraction * 100).toInt().coerceIn(0, 100)
}

enum class AnswerPath {
    /** Phone command. Never sent to a model or the backend. */
    LocalCommand,
    OnDevice,
    /** On-device was requested but the model is missing, loading, or failed this turn. */
    BackendFallback,
    /** User turned on-device answers off. Existing backend path. */
    Backend,
}

fun answerPath(
    hasLocalMatch: Boolean,
    onDeviceEnabled: Boolean,
    modelReady: Boolean,
): AnswerPath {
    if (hasLocalMatch) return AnswerPath.LocalCommand
    if (!onDeviceEnabled) return AnswerPath.Backend
    if (modelReady) return AnswerPath.OnDevice
    return AnswerPath.BackendFallback
}

fun withBackendNote(reply: String): String {
    val body = reply.trim()
    if (body.startsWith(OnDeviceModel.BACKEND_NOTE)) return body
    return "${OnDeviceModel.BACKEND_NOTE}\n\n$body"
}

fun buildOnDevicePrompt(messages: List<ChatMessage>): String {
    val conversational = messages
        .asSequence()
        .filter { it.role == MessageRole.User || it.role == MessageRole.Assistant }
        .filter { !it.staysOnDevice }
        .map { message -> message.copy(text = stripBackendNote(message.text).take(240)) }
        .filter { it.text.isNotBlank() }
        .toList()
        .takeLast(4)

    val current = conversational.lastOrNull { it.role == MessageRole.User }?.text.orEmpty()
    val prior = conversational.dropLastWhile { it.role == MessageRole.User }

    val prompt = buildString {
        append("You are KAI, a concise assistant on an Android phone. ")
        append("Answer in a few sentences. ")
        append("Do not claim you opened apps or changed phone settings.\n")
        if (prior.isNotEmpty()) {
            append("Recent chat:\n")
            prior.forEach { message ->
                val speaker = if (message.role == MessageRole.User) "User" else "KAI"
                append(speaker)
                append(": ")
                append(message.text.replace('\n', ' '))
                append('\n')
            }
        }
        append("User: ")
        append(current.replace('\n', ' '))
    }
    return if (prompt.length > 1200) prompt.take(1200) else prompt
}

internal fun stripBackendNote(text: String): String {
    val trimmed = text.trim()
    val note = OnDeviceModel.BACKEND_NOTE
    if (!trimmed.startsWith(note)) return trimmed
    return trimmed.removePrefix(note).trim()
}
