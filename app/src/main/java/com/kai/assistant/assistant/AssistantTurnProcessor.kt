package com.kai.assistant.assistant

import android.content.Context
import com.kai.assistant.KaiApplication
import com.kai.assistant.api.KaiApiClient
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.data.SessionMessageStore
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.ondevice.AnswerPath
import com.kai.assistant.ondevice.OnDeviceChat
import com.kai.assistant.ondevice.answerPath
import com.kai.assistant.ondevice.buildOnDevicePrompt
import com.kai.assistant.ondevice.withBackendNote
import com.kai.assistant.tools.LocalIntentRouter
import com.kai.assistant.tools.LocalMatch
import com.kai.assistant.tools.LocalToolRouter
import com.kai.assistant.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class TurnOutcome {
    data class LocalReady(val reply: String) : TurnOutcome()
    data class NeedsConfirmation(val match: LocalMatch) : TurnOutcome()
    data class OnDeviceReady(val reply: String) : TurnOutcome()
    data class CloudReady(
        val reply: String,
        val offline: Boolean,
        val usedFallback: Boolean = false,
    ) : TurnOutcome()
}

/**
 * Local phone commands first, then the on-device model, then the backend.
 * Phone-command text is never included in a backend request.
 */
class AssistantTurnProcessor(
    context: Context,
    private val api: KaiApiClient = KaiApiClient(),
    private val intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val tools: LocalToolRouter = LocalToolRouter(context.applicationContext),
    private val settingsRepository: SettingsRepository =
        (context.applicationContext as KaiApplication).settingsRepository,
    private val onDevice: OnDeviceChat =
        (context.applicationContext as KaiApplication).onDeviceChat,
) {
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
        val userMessage = ingestUserText(trimmed)

        val match = intentRouter.match(trimmed)
        if (match != null) {
            SessionMessageStore.markOnDeviceOnly(userMessage.id)
            if (match.requiresConfirmation) {
                return TurnOutcome.NeedsConfirmation(match)
            }
            return executeLocal(match)
        }
        return answerGenerally()
    }

    suspend fun confirmAndExecute(match: LocalMatch): TurnOutcome = executeLocal(match)

    fun cancelPendingConfirmation(prompt: String = "Okay, cancelled.") {
        SessionMessageStore.add(
            ChatMessage(role = MessageRole.Assistant, text = prompt, staysOnDevice = true),
        )
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
        SessionMessageStore.add(
            ChatMessage(role = MessageRole.Assistant, text = reply, staysOnDevice = true),
        )
        return TurnOutcome.LocalReady(reply)
    }

    private suspend fun answerGenerally(): TurnOutcome {
        val onDeviceEnabled = runCatching { settingsRepository.current().onDeviceAnswers }
            .getOrDefault(true)
        val path = answerPath(
            hasLocalMatch = false,
            onDeviceEnabled = onDeviceEnabled,
            modelReady = onDeviceEnabled && onDevice.isReady(),
        )
        if (path == AnswerPath.OnDevice) {
            val prompt = buildOnDevicePrompt(SessionMessageStore.snapshot())
            val generated = try {
                onDevice.generate(prompt)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                null
            }
            if (!generated.isNullOrBlank()) {
                SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = generated))
                return TurnOutcome.OnDeviceReady(generated)
            }
        } else if (onDeviceEnabled) {
            onDevice.ensureAvailable()
        }
        return callCloud(usedFallback = onDeviceEnabled)
    }

    private suspend fun callCloud(usedFallback: Boolean): TurnOutcome {
        val transcript = SessionMessageStore.snapshot()
            .filter { it.role != MessageRole.System }
            .filter { !it.staysOnDevice }
            .takeLast(24)

        val result = withContext(Dispatchers.IO) {
            api.sendChat(SessionMessageStore.SESSION_ID, transcript)
        }
        val reply = if (usedFallback) withBackendNote(result.reply) else result.reply
        SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = reply))
        return TurnOutcome.CloudReady(
            reply = reply,
            offline = result.offline || result.code == 503,
            usedFallback = usedFallback,
        )
    }
}
