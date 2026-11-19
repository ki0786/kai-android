package com.kai.assistant.assistant

import android.content.Context
import com.kai.assistant.KaiApplication
import com.kai.assistant.api.KaiApiClient
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.data.SessionMessageStore
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.tools.LocalIntentRouter
import com.kai.assistant.tools.LocalMatch
import com.kai.assistant.tools.LocalToolRouter
import com.kai.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class TurnOutcome {
    data class LocalReady(val reply: String) : TurnOutcome()
    data class NeedsConfirmation(val match: LocalMatch) : TurnOutcome()
    data class CloudReady(val reply: String, val offline: Boolean) : TurnOutcome()
}

/**
 * Local-first turn handler: match phone commands confidently, else call backend chat.
 */
class AssistantTurnProcessor(
    context: Context,
    private val settingsRepository: SettingsRepository =
        (context.applicationContext as KaiApplication).settingsRepository,
    private val intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val tools: LocalToolRouter = LocalToolRouter(context.applicationContext),
) {
    private val api = KaiApiClient(
        baseUrlProvider = { settingsRepository.effectiveBackendBaseUrl() },
    )

    fun ingestUserText(text: String): ChatMessage {
        val message = ChatMessage(role = MessageRole.User, text = text.trim())
        SessionMessageStore.add(message)
        return message
    }

    suspend fun handleUserText(text: String): TurnOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return TurnOutcome.LocalReady("I didn't catch that. Try again.")
        }
        ingestUserText(trimmed)

        val match = intentRouter.match(trimmed)
        if (match != null) {
            if (match.requiresConfirmation) {
                return TurnOutcome.NeedsConfirmation(match)
            }
            return executeLocal(match)
        }
        return callCloud()
    }

    suspend fun confirmAndExecute(match: LocalMatch): TurnOutcome = executeLocal(match)

    fun cancelPendingConfirmation(prompt: String = "Okay, cancelled.") {
        SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = prompt))
    }

    private suspend fun executeLocal(match: LocalMatch): TurnOutcome {
        val result = withContext(Dispatchers.Main) {
            tools.execute(match.command)
        }
        val reply = when (result) {
            is ToolResult.Ok -> result.message
            is ToolResult.Unsupported -> result.message
            is ToolResult.Error -> result.message
            is ToolResult.NeedsConfirmation -> result.message
        }
        SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = reply))
        return TurnOutcome.LocalReady(reply)
    }

    private suspend fun callCloud(): TurnOutcome {
        val transcript = SessionMessageStore.snapshot()
            .filter { it.role != MessageRole.System }
            // Keep prompt size reasonable for MVP.
            .takeLast(24)

        val result = withContext(Dispatchers.IO) {
            api.sendChat(SessionMessageStore.SESSION_ID, transcript)
        }
        val reply = result.reply
        SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = reply))
        return TurnOutcome.CloudReady(reply = reply, offline = result.offline || result.code == 503)
    }
}
