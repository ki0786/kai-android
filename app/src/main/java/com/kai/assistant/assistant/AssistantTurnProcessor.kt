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
import com.kai.assistant.tools.WhatsAppResolution
import com.kai.assistant.tools.staysOnDevice
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
    data class NeedsCallPermission(
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
        val onDevice = match?.command?.staysOnDevice() == true
        ingestUserText(trimmed, deviceOnly = onDevice)
        if (match != null) {
            // Calls and WhatsApp are resolved on the device. No match, several
            // matches, and permission prompts never fall through to the backend.
            if (match.command is LocalCommand.CallContact) {
                return prepareCall(match, requestPermission = true)
            }
            if (match.command is LocalCommand.SendWhatsApp) {
                return prepareWhatsApp(match, requestPermission = true)
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
    suspend fun continueCall(match: LocalMatch): TurnOutcome {
        return when (val command = match.command) {
            is LocalCommand.SendWhatsApp -> {
                if (!tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied(whatsApp = true))
                }
                sendResolvedWhatsApp(match)
            }
            is LocalCommand.CallContact -> {
                if (!command.rawNumber && !tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied())
                }
                placeResolvedCall(match, promptForCallPermission = true)
            }
            else -> rememberReady("I couldn't continue that on this phone.", deviceOnly = true)
        }
    }

    /**
     * Continues a call after the phone-call permission dialog and places it immediately.
     */
    suspend fun continueAfterCallPermission(match: LocalMatch): TurnOutcome {
        if (!tools.callGranted()) {
            return TurnOutcome.LocalReady(noteCallPermissionDenied())
        }
        return placeResolvedCall(match, promptForCallPermission = false)
    }

    fun noteContactsPermissionDenied(whatsApp: Boolean = false): String {
        val action = if (whatsApp) "open WhatsApp" else "place a call"
        val reply = "I can't look up contacts without permission, so I didn't $action. " +
            "Allow Contacts for KAI in Settings, or in the system app settings. " +
            "Nothing was sent to the server."
        remember(reply, deviceOnly = true)
        return reply
    }

    fun noteCallPermissionDenied(): String {
        val reply = "I can't place the call without phone permission. " +
            "Allow phone calls for KAI in Settings. Nothing was sent to the server."
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

        return placeResolvedCall(match, promptForCallPermission = requestPermission)
    }

    private suspend fun prepareWhatsApp(match: LocalMatch, requestPermission: Boolean): TurnOutcome {
        val command = match.command as? LocalCommand.SendWhatsApp
            ?: return rememberReady("I couldn't open WhatsApp on this phone.", deviceOnly = true)
        if (!tools.contactsGranted()) {
            if (!requestPermission) {
                return TurnOutcome.LocalReady(noteContactsPermissionDenied(whatsApp = true))
            }
            val name = ContactMatcher.displayQuery(command.nameQuery)
            val explanation = "I need Contacts permission to find $name on this phone so I can open WhatsApp. " +
                "Allow contacts when Android asks. This stays on the device and is not sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsContactsPermission(pending = match, explanation = explanation)
        }
        return sendResolvedWhatsApp(match)
    }

    private suspend fun sendResolvedWhatsApp(match: LocalMatch): TurnOutcome {
        val command = match.command as? LocalCommand.SendWhatsApp
            ?: return rememberReady("I couldn't open WhatsApp on this phone.", deviceOnly = true)
        if (!command.resolvedNumber.isNullOrBlank() && command.resolvedMessage != null) {
            return executeLocal(match)
        }
        val resolution = try {
            withContext(Dispatchers.IO) { tools.resolveWhatsApp(command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WhatsAppResolution.Speak(
                "I couldn't look up contacts on this phone. Nothing was sent to the server.",
            )
        }
        return when (resolution) {
            is WhatsAppResolution.Speak -> rememberReady(resolution.message, deviceOnly = true)
            is WhatsAppResolution.Ready -> executeLocal(
                match.copy(
                    command = command.copy(
                        resolvedNumber = resolution.number,
                        resolvedName = resolution.displayName,
                        resolvedMessage = resolution.message,
                    ),
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Opening WhatsApp to ${resolution.displayName}",
                ),
            )
        }
    }

    private suspend fun placeResolvedCall(
        match: LocalMatch,
        promptForCallPermission: Boolean,
    ): TurnOutcome {
        val command = match.command as? LocalCommand.CallContact
            ?: return rememberReady("I couldn't start that call on this phone.", deviceOnly = true)
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
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Calling ${resolution.displayName}",
                )
                if (!tools.callGranted()) {
                    if (!promptForCallPermission) {
                        return TurnOutcome.LocalReady(noteCallPermissionDenied())
                    }
                    val who = resolution.displayName
                    val explanation = "I need permission to place the call to $who. " +
                        "Allow phone calls when Android asks, and the call starts right away. " +
                        "The number stays on this phone and is not sent to the server."
                    remember(explanation, deviceOnly = true)
                    return TurnOutcome.NeedsCallPermission(pending = ready, explanation = explanation)
                }
                executeLocal(ready)
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
        val deviceOnly = match.command.staysOnDevice()
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
