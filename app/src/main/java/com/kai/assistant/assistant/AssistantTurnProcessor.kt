package com.kai.assistant.assistant

import android.content.Context
import com.kai.assistant.api.KaiApiClient
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.data.SessionMessageStore
import com.kai.assistant.tools.CallResolution
import com.kai.assistant.tools.ContactMatcher
import com.kai.assistant.tools.LocalCommand
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
    data class NeedsContactsPermission(
        val pending: LocalMatch,
        val explanation: String,
    ) : TurnOutcome()
    data class CloudReady(val reply: String, val offline: Boolean) : TurnOutcome()
}

/**
 * Local-first turn handler: match phone commands confidently, else call backend chat.
 */
class AssistantTurnProcessor(
    context: Context,
    private val api: KaiApiClient = KaiApiClient(),
    private val intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val tools: LocalToolRouter = LocalToolRouter(context.applicationContext),
) {
    fun ingestUserText(text: String, deviceOnly: Boolean = false): ChatMessage {
        val message = ChatMessage(
            role = MessageRole.User,
            text = text.trim(),
            deviceOnly = deviceOnly,
        )
        SessionMessageStore.add(message)
        return message
    }

    suspend fun handleUserText(text: String): TurnOutcome {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return TurnOutcome.LocalReady("I didn't catch that. Try again.")
        }
        val match = intentRouter.match(trimmed)
        val callOnDevice = match?.command is LocalCommand.CallContact
        ingestUserText(trimmed, deviceOnly = callOnDevice)
        if (match != null) {
            // Calls are resolved on the device. No match, several matches, and
            // permission prompts stay here and never fall through to the backend.
            if (match.command is LocalCommand.CallContact) {
                return prepareCall(match, requestPermission = true)
            }
            if (match.requiresConfirmation) {
                return TurnOutcome.NeedsConfirmation(match)
            }
            return executeLocal(match)
        }
        return callCloud()
    }

    suspend fun confirmAndExecute(match: LocalMatch): TurnOutcome = executeLocal(match)

    /**
     * Continues a call after the contacts permission dialog.
     * Does not add the user utterance again and does not call the backend.
     */
    suspend fun continueCall(match: LocalMatch): TurnOutcome =
        prepareCall(match, requestPermission = false)

    fun noteContactsPermissionDenied(): String {
        val reply = "I can't look up contacts without permission, so I didn't place a call. " +
            "Allow Contacts for KAI in Settings, or in the system app settings. " +
            "Nothing was sent to the server."
        remember(reply, deviceOnly = true)
        return reply
    }

    fun cancelPendingConfirmation(prompt: String = "Okay, cancelled.", deviceOnly: Boolean = false) {
        remember(prompt, deviceOnly)
    }

    private suspend fun prepareCall(match: LocalMatch, requestPermission: Boolean): TurnOutcome {
        val command = match.command as? LocalCommand.CallContact
            ?: return rememberReady("I couldn't start that call on this phone.", deviceOnly = true)
        if (!command.rawNumber && !tools.contactsGranted()) {
            if (!requestPermission) {
                return TurnOutcome.LocalReady(noteContactsPermissionDenied())
            }
            val name = ContactMatcher.displayQuery(command.query)
            val explanation = "I need Contacts permission to find $name on this phone. " +
                "Allow contacts when Android asks. The lookup stays on this device " +
                "and is not sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsContactsPermission(pending = match, explanation = explanation)
        }

        val resolution = try {
            withContext(Dispatchers.IO) { tools.resolveCall(command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CallResolution.Speak(
                "I couldn't look up contacts on this phone. Nothing was sent to the server.",
            )
        }

        return when (resolution) {
            is CallResolution.Speak -> rememberReady(resolution.message, deviceOnly = true)
            is CallResolution.Ready -> {
                val ready = match.copy(
                    command = command.copy(
                        resolvedNumber = resolution.number,
                        resolvedName = resolution.displayName,
                    ),
                    confirmationPrompt = resolution.confirmationPrompt,
                    spokenLabel = "Calling ${resolution.displayName}",
                )
                if (ready.requiresConfirmation) {
                    TurnOutcome.NeedsConfirmation(ready)
                } else {
                    executeLocal(ready)
                }
            }
        }
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
        val deviceOnly = match.command is LocalCommand.CallContact
        remember(reply, deviceOnly)
        return TurnOutcome.LocalReady(reply)
    }

    private fun remember(text: String, deviceOnly: Boolean) {
        SessionMessageStore.add(
            ChatMessage(role = MessageRole.Assistant, text = text, deviceOnly = deviceOnly),
        )
    }

    private fun rememberReady(text: String, deviceOnly: Boolean): TurnOutcome {
        remember(text, deviceOnly)
        return TurnOutcome.LocalReady(text)
    }

    private suspend fun callCloud(): TurnOutcome {
        val transcript = SessionMessageStore.snapshot()
            .filter { it.role != MessageRole.System && !it.deviceOnly }
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
