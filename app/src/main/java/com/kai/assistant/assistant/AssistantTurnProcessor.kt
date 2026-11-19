package com.kai.assistant.assistant

import android.content.Context
import com.kai.assistant.api.KaiApiClient
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.data.SessionMessageStore
import com.kai.assistant.data.SettingsRepository
import com.kai.assistant.tools.AccessibilityTarget
import com.kai.assistant.tools.CallResolution
import com.kai.assistant.tools.ContactMatcher
import com.kai.assistant.tools.LocalCommand
import com.kai.assistant.tools.LocalIntentRouter
import com.kai.assistant.tools.LocalMatch
import com.kai.assistant.tools.LocalToolRouter
import com.kai.assistant.tools.ToolResult
import com.kai.assistant.tools.WhatsAppCallResolution
import com.kai.assistant.tools.messageText
import com.kai.assistant.tools.WhatsAppResolution
import com.kai.assistant.tools.RESEARCH_OFFLINE
import com.kai.assistant.tools.RESEARCH_UNAVAILABLE
import com.kai.assistant.tools.formatResearchReply
import com.kai.assistant.tools.formatResearchSpoken
import com.kai.assistant.tools.needsNotification
import com.kai.assistant.tools.researchQuery
import com.kai.assistant.tools.staysOnDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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

    /** Ask once to enable an accessibility service. The chat or reply is already on device. */
    data class NeedsAccessibilitySettings(
        val explanation: String,
        val target: AccessibilityTarget = AccessibilityTarget.WhatsAppSend,
    ) : TurnOutcome()

    data class NeedsNotificationPermission(
        val pending: LocalMatch,
        val explanation: String,
    ) : TurnOutcome()

    /** First screenshot in this session. Later shots reuse the grant. */
    data class NeedsScreenshotConsent(val explanation: String) : TurnOutcome()
    data class CloudReady(
        val reply: String,
        val offline: Boolean,
        val spoken: String = reply,
    ) : TurnOutcome()
}

/**
 * Local-first turn handler: match phone commands confidently, else call backend chat.
 */
class AssistantTurnProcessor(
    context: Context,
    private val api: KaiApiClient = KaiApiClient(),
    private val intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val tools: LocalToolRouter = LocalToolRouter(context.applicationContext),
    private val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext),
) {
    private val memory get() = tools.memoryStore()
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
        refreshMemoryGate()
        val match = intentRouter.match(trimmed)
        val onDevice = match?.command?.staysOnDevice() == true
        ingestUserText(trimmed, deviceOnly = onDevice)
        return try {
            dispatch(match, trimmed)
        } finally {
            memory.recordTurn(
                role = "user",
                text = trimmed,
                allowSecret = match?.command is LocalCommand.SaveMemory,
            )
        }
    }

    private suspend fun dispatch(match: LocalMatch?, utterance: String): TurnOutcome {
        if (match != null) {
            // Calls and WhatsApp are resolved on the device. No match, several
            // matches, and permission prompts never fall through to the backend.
            if (match.command is LocalCommand.CallContact) {
                return prepareCall(match, requestPermission = true)
            }
            if (match.command is LocalCommand.SendWhatsApp) {
                return prepareWhatsApp(match, requestPermission = true)
            }
            if (match.command is LocalCommand.WhatsAppCall) {
                return prepareWhatsAppCall(match, requestPermission = true)
            }
            if (match.requiresConfirmation) {
                return TurnOutcome.NeedsConfirmation(match)
            }
            return executeLocal(match)
        }
        val query = researchQuery(utterance)
        if (query != null) return callResearch(query)
        return callCloud()
    }

    suspend fun confirmAndExecute(match: LocalMatch): TurnOutcome {
        refreshMemoryGate()
        return executeLocal(match)
    }

    /**
     * Continues a call after the contacts permission dialog.
     * Does not add the user utterance again and does not call the backend.
     */
    suspend fun continueCall(match: LocalMatch): TurnOutcome {
        refreshMemoryGate()
        return when (val command = match.command) {
            is LocalCommand.SendWhatsApp -> {
                if (!tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
                }
                sendResolvedWhatsApp(match)
            }
            is LocalCommand.WhatsAppCall -> {
                if (!tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
                }
                placeWhatsAppCall(match, promptForCallPermission = true)
            }
            is LocalCommand.CallContact -> {
                if (!command.rawNumber && !tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
                }
                placeResolvedCall(match, promptForCallPermission = true)
            }
            else -> rememberReady("I couldn't continue that on this phone.", deviceOnly = true)
        }
    }

    /**
     * Continues after the phone-call permission dialog and starts the call immediately.
     */
    suspend fun continueAfterCallPermission(match: LocalMatch): TurnOutcome {
        refreshMemoryGate()
        if (!tools.callGranted()) {
            return TurnOutcome.LocalReady(noteCallPermissionDenied(match.command))
        }
        return when (match.command) {
            is LocalCommand.WhatsAppCall -> placeWhatsAppCall(match, promptForCallPermission = false)
            is LocalCommand.CallContact -> placeResolvedCall(match, promptForCallPermission = false)
            else -> rememberReady("I couldn't continue that on this phone.", deviceOnly = true)
        }
    }

    suspend fun continueAfterNotificationPermission(match: LocalMatch): TurnOutcome {
        refreshMemoryGate()
        if (!tools.notificationsGranted()) {
            return TurnOutcome.LocalReady(noteNotificationDenied())
        }
        return executeLocal(match)
    }

    fun noteNotificationDenied(): String {
        val reply = "I can't notify you without notification permission. Nothing was sent to the server."
        remember(reply, deviceOnly = true)
        return reply
    }

    fun noteScreenshotDenied(): String {
        val reply = "I didn't take a screenshot. Nothing was sent to the server."
        remember(reply, deviceOnly = true)
        return reply
    }

    fun noteContactsPermissionDenied(command: LocalCommand? = null): String {
        val action = when (command) {
            is LocalCommand.WhatsAppCall -> "start a WhatsApp call"
            is LocalCommand.SendWhatsApp -> "open WhatsApp"
            else -> "place a call"
        }
        val reply = "I can't look up contacts without permission, so I didn't $action. " +
            "Allow Contacts for KAI in Settings, or in the system app settings. " +
            "Nothing was sent to the server."
        remember(reply, deviceOnly = true)
        return reply
    }

    fun noteCallPermissionDenied(command: LocalCommand? = null): String {
        val reply = if (command is LocalCommand.WhatsAppCall) {
            "I can't start the WhatsApp call without phone permission. " +
                "Allow phone calls for KAI in Settings. Nothing was sent to the server."
        } else {
            "I can't place the call without phone permission. " +
                "Allow phone calls for KAI in Settings. Nothing was sent to the server."
        }
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
                return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
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
                return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
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
                    spokenLabel = "Sending ${resolution.message} to ${resolution.displayName} on WhatsApp",
                ),
            )
        }
    }

    private suspend fun prepareWhatsAppCall(match: LocalMatch, requestPermission: Boolean): TurnOutcome {
        val command = match.command as? LocalCommand.WhatsAppCall
            ?: return rememberReady("I couldn't start that WhatsApp call on this phone.", deviceOnly = true)
        if (!tools.contactsGranted()) {
            if (!requestPermission) {
                return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
            }
            val name = ContactMatcher.displayQuery(command.nameQuery)
            val explanation = "I need Contacts permission to find $name on this phone so I can start a WhatsApp call. " +
                "Allow contacts when Android asks. This stays on the device and is not sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsContactsPermission(pending = match, explanation = explanation)
        }
        return placeWhatsAppCall(match, promptForCallPermission = requestPermission)
    }

    private suspend fun placeWhatsAppCall(
        match: LocalMatch,
        promptForCallPermission: Boolean,
    ): TurnOutcome {
        val command = match.command as? LocalCommand.WhatsAppCall
            ?: return rememberReady("I couldn't start that WhatsApp call on this phone.", deviceOnly = true)
        if (
            command.resolvedContactId != null &&
            !command.resolvedNumber.isNullOrBlank() &&
            !command.resolvedName.isNullOrBlank()
        ) {
            return startResolvedWhatsAppCall(match, command, promptForCallPermission)
        }
        val resolution = try {
            withContext(Dispatchers.IO) { tools.resolveWhatsAppCall(command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WhatsAppCallResolution.Speak(
                "I couldn't look up contacts on this phone. Nothing was sent to the server.",
            )
        }
        return when (resolution) {
            is WhatsAppCallResolution.Speak -> rememberReady(resolution.message, deviceOnly = true)
            is WhatsAppCallResolution.Ready -> {
                val readyCommand = command.copy(
                    resolvedContactId = resolution.contactId,
                    resolvedNumber = resolution.number,
                    resolvedName = resolution.displayName,
                )
                startResolvedWhatsAppCall(match, readyCommand, promptForCallPermission)
            }
        }
    }

    private suspend fun startResolvedWhatsAppCall(
        match: LocalMatch,
        command: LocalCommand.WhatsAppCall,
        promptForCallPermission: Boolean,
    ): TurnOutcome {
        val who = command.resolvedName ?: ContactMatcher.displayQuery(command.nameQuery)
        val spoken = if (command.video) {
            "Starting a WhatsApp video call to $who"
        } else {
            "Calling $who on WhatsApp"
        }
        val ready = match.copy(
            command = command,
            requiresConfirmation = false,
            confirmationPrompt = "",
            spokenLabel = spoken,
        )
        if (!tools.callGranted()) {
            if (!promptForCallPermission) {
                return TurnOutcome.LocalReady(noteCallPermissionDenied(command))
            }
            val kind = if (command.video) "video call" else "call"
            val explanation = "I need permission to start the WhatsApp $kind to $who. " +
                "Allow phone calls when Android asks, and the call starts right away. " +
                "The number stays on this phone and is not sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsCallPermission(pending = ready, explanation = explanation)
        }
        return executeLocal(ready)
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
                        return TurnOutcome.LocalReady(noteCallPermissionDenied(command))
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
        if (match.command.needsNotification() && !tools.notificationsGranted()) {
            val explanation = "I need notification permission so I can tell you on this phone. " +
                "Allow notifications when Android asks. Nothing is sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsNotificationPermission(pending = match, explanation = explanation)
        }
        val result = withContext(Dispatchers.Main) {
            tools.execute(match.command)
        }
        val reply = result.messageText()
        val deviceOnly = match.command.staysOnDevice()
        remember(reply, deviceOnly)
        return when (result) {
            is ToolResult.OpenAccessibilitySettings ->
                TurnOutcome.NeedsAccessibilitySettings(reply, result.target)
            is ToolResult.NeedsScreenshotConsent -> TurnOutcome.NeedsScreenshotConsent(reply)
            else -> TurnOutcome.LocalReady(reply)
        }
    }

    private fun remember(text: String, deviceOnly: Boolean) {
        SessionMessageStore.add(
            ChatMessage(role = MessageRole.Assistant, text = text, deviceOnly = deviceOnly),
        )
        memory.recordTurn(role = "assistant", text = text, allowSecret = false)
    }

    private suspend fun refreshMemoryGate() {
        memory.writesEnabled = settingsRepository.settings.first().memoryEnabled
    }

    private fun rememberReady(text: String, deviceOnly: Boolean): TurnOutcome {
        remember(text, deviceOnly)
        return TurnOutcome.LocalReady(text)
    }

    private suspend fun callResearch(query: String): TurnOutcome {
        val result = withContext(Dispatchers.IO) { api.research(query) }
        val reply = when {
            result.offline -> RESEARCH_OFFLINE
            !result.ok -> RESEARCH_UNAVAILABLE
            else -> formatResearchReply(result.answer, result.sources)
        }
        val spoken = when {
            result.offline -> RESEARCH_OFFLINE
            !result.ok -> RESEARCH_UNAVAILABLE
            else -> formatResearchSpoken(result.answer, result.sources)
        }
        SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = reply))
        memory.recordTurn(role = "assistant", text = reply, allowSecret = false)
        return TurnOutcome.CloudReady(
            reply = reply,
            offline = result.offline || !result.ok,
            spoken = spoken,
        )
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
        memory.recordTurn(role = "assistant", text = reply, allowSecret = false)
        return TurnOutcome.CloudReady(reply = reply, offline = result.offline || result.code == 503)
    }
}
