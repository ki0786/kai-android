package com.kai.assistant.assistant

import android.content.Context
import android.net.Uri
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
import com.kai.assistant.tools.BRAIN_OFFLINE
import com.kai.assistant.tools.RESEARCH_OFFLINE
import com.kai.assistant.tools.RESEARCH_UNAVAILABLE
import com.kai.assistant.tools.formatResearchReply
import com.kai.assistant.tools.formatResearchSpoken
import com.kai.assistant.tools.PhotoDescriber
import com.kai.assistant.tools.PlanStep
import com.kai.assistant.tools.StepReport
import com.kai.assistant.tools.needsCalendar
import com.kai.assistant.tools.needsNotification
import com.kai.assistant.tools.planIntro
import com.kai.assistant.tools.KAI_SYSTEM_PROMPT
import com.kai.assistant.tools.PartialPlan
import com.kai.assistant.tools.fixCantActReply
import com.kai.assistant.tools.phoneActionHint
import com.kai.assistant.tools.planSteps
import com.kai.assistant.tools.planStepLine
import com.kai.assistant.tools.planSummary
import com.kai.assistant.tools.researchQuery
import com.kai.assistant.tools.shouldRetry
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

    data class NeedsCalendarPermission(
        val pending: LocalMatch,
        val explanation: String,
    ) : TurnOutcome()

    /** First screenshot in this session. Later shots reuse the grant. */
    data class NeedsScreenshotConsent(val explanation: String) : TurnOutcome()

    /** Open the photo picker. The image stays on this phone. */
    data class NeedsPhoto(
        val explanation: String,
        val useBackend: Boolean,
    ) : TurnOutcome()
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
    private val api: KaiApiClient = KaiApiClient.forDevice(context.applicationContext),
    private val intentRouter: LocalIntentRouter = LocalIntentRouter(),
    private val tools: LocalToolRouter = LocalToolRouter(context.applicationContext),
    private val settingsRepository: SettingsRepository = SettingsRepository(context.applicationContext),
    private val photos: PhotoDescriber = PhotoDescriber(context.applicationContext),
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
        val plan = if (match == null) planSteps(trimmed) { clause -> intentRouter.match(clause) } else null
        val hint = if (match == null && plan == null) phoneActionHint(trimmed) else null
        val researched = if (match == null && plan == null && hint == null) researchQuery(trimmed) else null
        val onDevice = match?.command?.staysOnDevice() == true ||
            (plan != null && plan.leftover.isEmpty()) ||
            hint != null ||
            researched != null
        ingestUserText(trimmed, deviceOnly = onDevice)
        return try {
            when {
                hint != null -> rememberReady(hint, deviceOnly = true)
                plan != null -> dispatchPlan(plan)
                else -> dispatch(match, researched)
            }
        } finally {
            memory.recordTurn(
                role = "user",
                text = trimmed,
                allowSecret = match?.command is LocalCommand.SaveMemory,
            )
        }
    }

    /**
     * Phone steps run on the device. Clauses that aren't phone actions go to chat
     * afterwards, so one odd clause doesn't send the whole command to the cloud.
     */
    private suspend fun dispatchPlan(plan: PartialPlan): TurnOutcome {
        val outcome = if (plan.steps.size == 1) {
            val step = plan.steps.single()
            dispatch(
                LocalMatch(command = step.command, requiresConfirmation = false, confirmationPrompt = "", spokenLabel = step.label),
                researched = null,
            )
        } else {
            executeLocal(
                LocalMatch(
                    command = LocalCommand.RunPlan(plan.steps),
                    requiresConfirmation = false,
                    confirmationPrompt = "",
                    spokenLabel = "Doing ${plan.steps.size} steps",
                ),
            )
        }
        if (plan.leftover.isEmpty() || outcome !is TurnOutcome.LocalReady) return outcome
        val hints = plan.leftover.mapNotNull { phoneActionHint(it) }
        if (hints.size == plan.leftover.size) {
            val reply = (listOf(outcome.reply) + hints.distinct()).joinToString(" ")
            return TurnOutcome.LocalReady(reply)
        }
        val cloud = callCloud() as TurnOutcome.CloudReady
        val reply = "${outcome.reply}\n\n${cloud.reply}"
        return TurnOutcome.CloudReady(reply = reply, offline = cloud.offline, spoken = "${outcome.reply} ${cloud.spoken}")
    }

    private suspend fun dispatch(
        match: LocalMatch?,
        researched: String?,
    ): TurnOutcome {
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
            if (match.command is LocalCommand.SendSms) {
                return prepareSms(match, requestPermission = true)
            }
            if (match.requiresConfirmation) {
                return TurnOutcome.NeedsConfirmation(match)
            }
            return executeLocal(match)
        }
        if (researched != null) return callResearch(researched)
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
            is LocalCommand.SendSms -> {
                if (!command.rawNumber && !tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
                }
                sendResolvedSms(match)
            }
            is LocalCommand.RunPlan -> {
                if (!tools.contactsGranted()) {
                    return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
                }
                executeLocal(match)
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
            is LocalCommand.RunPlan -> executeLocal(match)
            else -> rememberReady("I couldn't continue that on this phone.", deviceOnly = true)
        }
    }

    suspend fun continueAfterCalendarPermission(match: LocalMatch): TurnOutcome {
        refreshMemoryGate()
        if (!tools.calendarGranted()) {
            if (match.command is LocalCommand.DailyBriefing || match.command is LocalCommand.RunPlan) {
                return executeLocal(match, promptForCalendar = false)
            }
            return TurnOutcome.LocalReady(noteCalendarDenied())
        }
        return executeLocal(match)
    }

    fun noteCalendarDenied(): String {
        val reply = "I can't read or change your calendar without permission. Nothing was sent to the server."
        remember(reply, deviceOnly = true)
        return reply
    }

    suspend fun continueAfterNotificationPermission(match: LocalMatch): TurnOutcome {
        refreshMemoryGate()
        if (tools.notificationsGranted()) return executeLocal(match)
        val outcome = executeLocal(match, promptForNotifications = false)
        if (outcome !is TurnOutcome.LocalReady) return outcome
        val note = "$NOTIFICATIONS_OFF_NOTE"
        remember(note, deviceOnly = true)
        return TurnOutcome.LocalReady("${outcome.reply} $note")
    }

    fun noteNotificationDenied(): String {
        val reply = NOTIFICATIONS_OFF_NOTE
        remember(reply, deviceOnly = true)
        return reply
    }

    suspend fun describePickedPhoto(uri: Uri, useBackend: Boolean): TurnOutcome {
        val reply = withContext(Dispatchers.IO) {
            photos.describe(uri, askedForBackend = useBackend)
        }
        return rememberReady(reply, deviceOnly = true)
    }

    fun notePhotoCancelled(): String {
        val reply = "I didn't open a photo. Nothing was sent to the server."
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
            is LocalCommand.SendSms -> "open Messages"
            is LocalCommand.RunPlan -> "run those steps"
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

    private suspend fun prepareSms(match: LocalMatch, requestPermission: Boolean): TurnOutcome {
        val command = match.command as? LocalCommand.SendSms
            ?: return rememberReady("I couldn't open Messages on this phone.", deviceOnly = true)
        if (!command.rawNumber && !tools.contactsGranted()) {
            if (!requestPermission) {
                return TurnOutcome.LocalReady(noteContactsPermissionDenied(command))
            }
            val name = ContactMatcher.displayQuery(command.nameQuery)
            val explanation = "I need Contacts permission to find $name on this phone so I can open Messages. " +
                "Allow contacts when Android asks. This stays on the device and is not sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsContactsPermission(pending = match, explanation = explanation)
        }
        return sendResolvedSms(match)
    }

    private suspend fun sendResolvedSms(match: LocalMatch): TurnOutcome {
        val command = match.command as? LocalCommand.SendSms
            ?: return rememberReady("I couldn't open Messages on this phone.", deviceOnly = true)
        if (!command.resolvedNumber.isNullOrBlank()) return executeLocal(match)
        val resolution = try {
            withContext(Dispatchers.IO) { tools.resolveSms(command) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            WhatsAppResolution.Speak("I couldn't look up contacts on this phone. Nothing was sent to the server.")
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
                    spokenLabel = "Texting ${resolution.displayName}",
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

    private suspend fun executeLocal(
        match: LocalMatch,
        promptForCalendar: Boolean = true,
        promptForNotifications: Boolean = true,
    ): TurnOutcome {
        (match.command as? LocalCommand.RunPlan)?.let { plan ->
            planPermissionOutcome(match, plan.steps)?.let { return it }
        }
        if (promptForCalendar && match.command.needsCalendar() && !tools.calendarGranted()) {
            val explanation = "I need calendar permission to read and add events on this phone. " +
                "Allow calendar when Android asks. Nothing is sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsCalendarPermission(pending = match, explanation = explanation)
        }
        if (promptForNotifications && match.command.needsNotification() && !tools.notificationsGranted()) {
            val explanation = "I need notification permission so I can tell you on this phone. " +
                "Allow notifications when Android asks. Nothing is sent to the server."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsNotificationPermission(pending = match, explanation = explanation)
        }
        if (match.command is LocalCommand.RunPlan) {
            return runPlan(match.command.steps)
        }
        val result = withContext(Dispatchers.IO) {
            tools.execute(match.command)
        }
        val reply = result.messageText()
        val deviceOnly = match.command.staysOnDevice()
        remember(reply, deviceOnly)
        return when (result) {
            is ToolResult.OpenAccessibilitySettings ->
                TurnOutcome.NeedsAccessibilitySettings(reply, result.target)
            is ToolResult.NeedsScreenshotConsent -> TurnOutcome.NeedsScreenshotConsent(reply)
            is ToolResult.NeedsPhoto -> TurnOutcome.NeedsPhoto(reply, result.useBackend)
            else -> TurnOutcome.LocalReady(reply)
        }
    }

    /** Contacts and phone permission are asked before a plan starts, not mid-way. */
    private fun planPermissionOutcome(match: LocalMatch, steps: List<PlanStep>): TurnOutcome? {
        val commands = steps.map { it.command }
        val needsContacts = commands.any {
            (it is LocalCommand.CallContact && !it.rawNumber) ||
                it is LocalCommand.SendWhatsApp ||
                it is LocalCommand.WhatsAppCall ||
                (it is LocalCommand.SendSms && !it.rawNumber)
        }
        if (needsContacts && !tools.contactsGranted()) {
            val explanation = "I need Contacts permission to find people for these steps. " +
                "Allow contacts when Android asks. The lookup stays on this device."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsContactsPermission(pending = match, explanation = explanation)
        }
        val needsCall = commands.any { it is LocalCommand.CallContact || it is LocalCommand.WhatsAppCall }
        if (needsCall && !tools.callGranted()) {
            val explanation = "I need phone-call permission for these steps. " +
                "Allow phone calls when Android asks and I'll carry on."
            remember(explanation, deviceOnly = true)
            return TurnOutcome.NeedsCallPermission(pending = match, explanation = explanation)
        }
        return null
    }

    private suspend fun runPlan(steps: List<PlanStep>): TurnOutcome {
        remember(planIntro(steps.size), deviceOnly = true)
        val reports = mutableListOf<StepReport>()
        for ((index, step) in steps.withIndex()) {
            remember(planStepLine(index, steps.size, step.label), deviceOnly = true)
            var result = runStep(step)
            if (shouldRetry(failed = stepFailed(result), alreadyRetried = false)) {
                remember("That step didn't work. Trying once more.", deviceOnly = true)
                result = runStep(step)
            }
            when (result) {
                is ToolResult.OpenAccessibilitySettings -> {
                    remember(result.message, deviceOnly = true)
                    return TurnOutcome.NeedsAccessibilitySettings(result.message, result.target)
                }
                is ToolResult.NeedsScreenshotConsent -> {
                    remember(result.message, deviceOnly = true)
                    return TurnOutcome.NeedsScreenshotConsent(result.message)
                }
                is ToolResult.NeedsPhoto -> {
                    remember(result.message, deviceOnly = true)
                    return TurnOutcome.NeedsPhoto(result.message, result.useBackend)
                }
                else -> {
                    val ok = !stepFailed(result)
                    val detail = result.messageText()
                    remember(if (ok) "Finished: $detail" else "Failed: $detail", deviceOnly = true)
                    reports += StepReport(ok = ok, detail = detail)
                }
            }
        }
        val summary = planSummary(reports)
        remember(summary, deviceOnly = true)
        return TurnOutcome.LocalReady(summary)
    }

    private suspend fun runStep(step: PlanStep): ToolResult {
        return withContext(Dispatchers.IO) {
            runCatching { tools.execute(step.command) }
                .getOrElse { error -> ToolResult.Error(error.message ?: "That step failed.") }
        }
    }

    private fun stepFailed(result: ToolResult): Boolean = when (result) {
        is ToolResult.Ok, is ToolResult.NeedsConfirmation -> false
        is ToolResult.OpenAccessibilitySettings,
        is ToolResult.NeedsScreenshotConsent,
        is ToolResult.NeedsPhoto,
        -> false
        else -> true
    }

    private fun remember(text: String, deviceOnly: Boolean) {
        SessionMessageStore.add(
            ChatMessage(role = MessageRole.Assistant, text = text, deviceOnly = deviceOnly),
        )
        memory.recordTurn(role = "assistant", text = text, allowSecret = false)
    }

    private suspend fun refreshMemoryGate() {
        val settings = settingsRepository.settings.first()
        memory.writesEnabled = settings.memoryEnabled
        memory.historyEnabled = settings.chatHistoryEnabled
    }

    /**
     * Chat and research wait on the brain. A failed health ping stays on the phone:
     * no crash, and the calls, alarms, books, photos, and device controls still run.
     */
    private suspend fun brainOfflineOrNull(): TurnOutcome? {
        val online = withContext(Dispatchers.IO) {
            try {
                api.pingHealth()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }
        if (online) return null
        remember(BRAIN_OFFLINE, deviceOnly = true)
        return TurnOutcome.CloudReady(reply = BRAIN_OFFLINE, offline = true, spoken = BRAIN_OFFLINE)
    }

    private fun rememberReady(text: String, deviceOnly: Boolean): TurnOutcome {
        remember(text, deviceOnly)
        return TurnOutcome.LocalReady(text)
    }

    private suspend fun callResearch(query: String): TurnOutcome {
        brainOfflineOrNull()?.let { return it }
        val result = withContext(Dispatchers.IO) {
            api.research(query, SessionMessageStore.SESSION_ID)
        }
        val empty = result.answer.isBlank() && result.sources.isEmpty()
        val reply = when {
            result.offline -> RESEARCH_OFFLINE
            !result.ok || empty -> RESEARCH_UNAVAILABLE
            else -> formatResearchReply(result.answer, result.sources)
        }
        val spoken = when {
            result.offline -> RESEARCH_OFFLINE
            !result.ok || empty -> RESEARCH_UNAVAILABLE
            else -> formatResearchSpoken(result.answer, result.sources)
        }
        SessionMessageStore.add(
            ChatMessage(role = MessageRole.Assistant, text = reply, deviceOnly = true),
        )
        memory.recordTurn(role = "assistant", text = reply, allowSecret = false)
        return TurnOutcome.CloudReady(
            reply = reply,
            offline = result.offline || !result.ok,
            spoken = spoken,
        )
    }

    private suspend fun callCloud(): TurnOutcome {
        brainOfflineOrNull()?.let { return it }
        val history = SessionMessageStore.snapshot()
            .filter { it.role != MessageRole.System && !it.deviceOnly && it.text.isNotBlank() }
            .takeLast(24)
        val transcript = listOf(ChatMessage(role = MessageRole.System, text = KAI_SYSTEM_PROMPT)) + history

        val result = withContext(Dispatchers.IO) {
            api.sendChat(SessionMessageStore.SESSION_ID, transcript)
        }
        val reply = if (result.ok) fixCantActReply(result.reply) else result.reply
        SessionMessageStore.add(ChatMessage(role = MessageRole.Assistant, text = reply, deviceOnly = !result.ok))
        memory.recordTurn(role = "assistant", text = reply, allowSecret = false)
        return TurnOutcome.CloudReady(reply = reply, offline = result.offline || result.code == 503)
    }
}

private const val NOTIFICATIONS_OFF_NOTE =
    "Notifications are off for KAI, so I'll ring and speak but won't show a notification. " +
        "Turn notifications on in Settings to see them."
