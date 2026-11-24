package com.kai.assistant.voice

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kai.assistant.KaiApplication
import com.kai.assistant.assistant.AssistantTurnProcessor
import com.kai.assistant.assistant.TurnOutcome
import com.kai.assistant.data.SessionMessageStore
import com.kai.assistant.tools.AccessibilityTarget
import com.kai.assistant.tools.LocalMatch
import com.kai.assistant.tools.SpokenAlert
import com.kai.assistant.tools.recognizerLanguageTag
import com.kai.assistant.tools.replyLanguage
import com.kai.assistant.tools.staysOnDevice
import com.kai.assistant.ui.components.MicStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoicePhase {
    Idle,
    Listening,
    Processing,
    Speaking,
}

data class ContactsPermissionRequest(
    val token: Long,
    val match: LocalMatch,
)

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.Idle,
    val statusLine: String = "Say Hey KAI, or hold the mic to talk.",
    val partialText: String = "",
    val pendingConfirmation: LocalMatch? = null,
    val ttsReady: Boolean = false,
    val sttAvailable: Boolean = true,
    val wakeArmed: Boolean = false,
)

class VoiceSessionViewModel(application: Application) : AndroidViewModel(application) {
    private val settingsRepository = (application as KaiApplication).settingsRepository
    private val processor = AssistantTurnProcessor(application)

    private val _ui = MutableStateFlow(VoiceUiState())
    val uiState: StateFlow<VoiceUiState> = _ui.asStateFlow()

    private val _contactsPermission = MutableStateFlow<ContactsPermissionRequest?>(null)
    val contactsPermissionRequest: StateFlow<ContactsPermissionRequest?> =
        _contactsPermission.asStateFlow()

    private val _callPermission = MutableStateFlow<ContactsPermissionRequest?>(null)
    val callPermissionRequest: StateFlow<ContactsPermissionRequest?> =
        _callPermission.asStateFlow()

    private val _accessibilitySettings = MutableStateFlow(0L)
    val accessibilitySettingsRequest: StateFlow<Long> = _accessibilitySettings.asStateFlow()
    private val _accessibilityTarget = MutableStateFlow(AccessibilityTarget.WhatsAppSend)
    val accessibilityTarget: StateFlow<AccessibilityTarget> = _accessibilityTarget.asStateFlow()

    private val _notificationPermission = MutableStateFlow<ContactsPermissionRequest?>(null)
    val notificationPermissionRequest: StateFlow<ContactsPermissionRequest?> =
        _notificationPermission.asStateFlow()

    private val _calendarPermission = MutableStateFlow<ContactsPermissionRequest?>(null)
    val calendarPermissionRequest: StateFlow<ContactsPermissionRequest?> =
        _calendarPermission.asStateFlow()

    private val _screenshotConsent = MutableStateFlow(0L)
    val screenshotConsentRequest: StateFlow<Long> = _screenshotConsent.asStateFlow()

    private val _photoPick = MutableStateFlow<Pair<Long, Boolean>?>(null)
    val photoPickRequest: StateFlow<Pair<Long, Boolean>?> = _photoPick.asStateFlow()

    val messages = SessionMessageStore.messages

    val micStatus: StateFlow<MicStatus> = _ui.map { state ->
        when {
            state.phase == VoicePhase.Listening -> MicStatus.Listening
            state.phase == VoicePhase.Processing -> MicStatus.Processing
            state.phase == VoicePhase.Speaking -> MicStatus.Speaking
            state.wakeArmed -> MicStatus.Wake
            else -> MicStatus.Off
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MicStatus.Off)

    private var settingsLanguage: String = "en"
    private var turnLanguage: String = "en"

    private val tts = TextToSpeechManager(
        context = application,
        onSpeakingChanged = { speaking ->
            _ui.update { state ->
                state.copy(
                    phase = when {
                        speaking -> VoicePhase.Speaking
                        state.phase == VoicePhase.Speaking -> VoicePhase.Idle
                        else -> state.phase
                    },
                )
            }
        },
        onReady = { ready -> _ui.update { it.copy(ttsReady = ready) } },
    )

    private val stt = SpeechToTextManager(
        context = application,
        onPartial = { partial ->
            _ui.update {
                it.copy(
                    partialText = partial,
                    statusLine = partial.ifBlank { "Listening…" },
                )
            }
        },
        onFinal = { finalText -> onSpeechFinal(finalText) },
        onError = { error ->
            _ui.update {
                it.copy(
                    phase = VoicePhase.Idle,
                    statusLine = error,
                    partialText = "",
                )
            }
        },
        onListeningChanged = { listening ->
            if (listening) {
                _ui.update {
                    it.copy(phase = VoicePhase.Listening, statusLine = "Listening…", partialText = "")
                }
            }
        },
    )

    private val wake = WakeWordListener(
        context = application,
        onPhrase = { heard -> onWakePhrase(heard) },
        onListening = { listening ->
            _ui.update { it.copy(wakeArmed = listening) }
        },
        onBlocked = { message ->
            _ui.update { it.copy(wakeArmed = false, statusLine = message) }
        },
    )

    private var wakeScreenOpen = false
    private var wakeMicGranted = false

    init {
        _ui.update { it.copy(sttAvailable = stt.isAvailable()) }
        viewModelScope.launch {
            _ui.map { it.phase to (it.pendingConfirmation != null) }
                .distinctUntilChanged()
                .collect { (phase, confirming) ->
                    if (!wakeScreenOpen) return@collect
                    if (phase != VoicePhase.Idle || confirming) wake.disarm() else armWake()
                }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                settingsLanguage = settings.languageCode
                stt.setLanguageTag(recognizerLanguageTag(settings.languageCode))
                tts.updatePreferences(
                    languageCode = settings.languageCode,
                    voiceGender = settings.voiceGender,
                    rate = settings.speechRate,
                )
            }
        }
    }

    fun onWakeScreenOpen(micGranted: Boolean) {
        wakeScreenOpen = true
        wakeMicGranted = micGranted
        if (!micGranted) {
            wake.disarm()
            _ui.update {
                it.copy(
                    wakeArmed = false,
                    statusLine = "Allow the microphone to say Hey KAI. $WAKE_BACKGROUND_NOTE",
                )
            }
            return
        }
        if (_ui.value.phase == VoicePhase.Idle && _ui.value.pendingConfirmation == null) {
            _ui.update { it.copy(statusLine = "Say Hey KAI, or hold the mic to talk.") }
            armWake()
        }
    }

    fun onWakeScreenClosed() {
        wakeScreenOpen = false
        wake.disarm()
        _ui.update { it.copy(wakeArmed = false) }
    }

    fun onPushToTalkPressed() {
        if (_ui.value.pendingConfirmation != null) return
        wake.disarm()
        tts.stop()
        SpokenAlert.stop()
        if (!stt.isAvailable()) {
            _ui.update {
                it.copy(statusLine = "Speech recognition unavailable. Use text chat instead.")
            }
            return
        }
        stt.startListening()
    }

    fun onPushToTalkReleased() {
        if (_ui.value.phase == VoicePhase.Listening) {
            _ui.update { it.copy(statusLine = "Processing…") }
            stt.stopListening()
        }
    }

    fun interruptSpeech() {
        tts.stop()
        SpokenAlert.stop()
        _ui.update {
            it.copy(
                phase = VoicePhase.Idle,
                statusLine = "Interrupted. Ready when you are.",
            )
        }
    }

    fun confirmPending() {
        val match = _ui.value.pendingConfirmation ?: return
        _ui.update { it.copy(pendingConfirmation = null, phase = VoicePhase.Processing) }
        viewModelScope.launch {
            present(processor.confirmAndExecute(match))
        }
    }

    fun onContactsPermissionResult(granted: Boolean) {
        val request = _contactsPermission.value ?: return
        _contactsPermission.value = null
        viewModelScope.launch {
            _ui.update { it.copy(phase = VoicePhase.Processing) }
            val outcome = if (granted) {
                processor.continueCall(request.match)
            } else {
                TurnOutcome.LocalReady(processor.noteContactsPermissionDenied(request.match.command))
            }
            present(outcome)
        }
    }

    fun onCalendarPermissionResult() {
        val request = _calendarPermission.value ?: return
        _calendarPermission.value = null
        viewModelScope.launch {
            _ui.update { it.copy(phase = VoicePhase.Processing) }
            present(processor.continueAfterCalendarPermission(request.match))
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        val request = _notificationPermission.value ?: return
        _notificationPermission.value = null
        viewModelScope.launch {
            _ui.update { it.copy(phase = VoicePhase.Processing) }
            present(processor.continueAfterNotificationPermission(request.match))
        }
    }

    fun onPhotoPicked(uri: Uri?) {
        val request = _photoPick.value ?: return
        _photoPick.value = null
        viewModelScope.launch {
            _ui.update { it.copy(phase = VoicePhase.Processing) }
            val outcome = if (uri == null) {
                TurnOutcome.LocalReady(processor.notePhotoCancelled())
            } else {
                processor.describePickedPhoto(uri, request.second)
            }
            present(outcome)
        }
    }

    fun onScreenshotConsentDenied() {
        present(TurnOutcome.LocalReady(processor.noteScreenshotDenied()))
    }

    fun onCallPermissionResult(granted: Boolean) {
        val request = _callPermission.value ?: return
        _callPermission.value = null
        viewModelScope.launch {
            _ui.update { it.copy(phase = VoicePhase.Processing) }
            val outcome = if (granted) {
                processor.continueAfterCallPermission(request.match)
            } else {
                TurnOutcome.LocalReady(processor.noteCallPermissionDenied(request.match.command))
            }
            present(outcome)
        }
    }

    fun cancelPending() {
        val pending = _ui.value.pendingConfirmation ?: return
        processor.cancelPendingConfirmation(
            deviceOnly = pending.command.staysOnDevice(),
        )
        _ui.update {
            it.copy(
                pendingConfirmation = null,
                phase = VoicePhase.Idle,
                statusLine = "Cancelled. Ready when you are.",
            )
        }
        tts.speak("Okay, cancelled.")
    }

    private fun onSpeechFinal(finalText: String) {
        val text = finalText.trim().ifBlank { _ui.value.partialText.trim() }
        if (text.isBlank()) {
            _ui.update {
                it.copy(
                    phase = VoicePhase.Idle,
                    statusLine = "Didn't catch that — hold to try again.",
                    partialText = "",
                )
            }
            return
        }
        _ui.update {
            it.copy(
                phase = VoicePhase.Processing,
                statusLine = "You: $text",
                partialText = "",
            )
        }
        if (turnJob?.isActive == true) {
            _ui.update { it.copy(statusLine = "Still working on the last request…") }
            return
        }
        turnJob = viewModelScope.launch { processUtterance(text) }
    }

    private var turnJob: kotlinx.coroutines.Job? = null

    private suspend fun processUtterance(text: String) {
        turnLanguage = replyLanguage(text, settingsLanguage)
        present(processor.handleUserText(text))
    }

    private fun present(outcome: TurnOutcome) {
        when (outcome) {
            is TurnOutcome.NeedsConfirmation -> {
                _ui.update {
                    it.copy(
                        pendingConfirmation = outcome.match,
                        phase = VoicePhase.Idle,
                        statusLine = outcome.match.confirmationPrompt,
                    )
                }
                tts.speak(outcome.match.confirmationPrompt)
            }
            is TurnOutcome.NeedsContactsPermission -> {
                _contactsPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.NeedsCallPermission -> {
                _callPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.NeedsAccessibilitySettings -> {
                _accessibilityTarget.value = outcome.target
                _accessibilitySettings.value = System.nanoTime()
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.NeedsCalendarPermission -> {
                _calendarPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.NeedsNotificationPermission -> {
                _notificationPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.NeedsPhoto -> {
                _photoPick.value = System.nanoTime() to outcome.useBackend
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.NeedsScreenshotConsent -> {
                _screenshotConsent.value = System.nanoTime()
                speakAssistant(outcome.explanation)
            }
            is TurnOutcome.LocalReady -> speakAssistant(outcome.reply)
            is TurnOutcome.CloudReady -> speakAssistant(outcome.spoken)
        }
    }

    private fun speakAssistant(reply: String) {
        _ui.update {
            it.copy(
                phase = VoicePhase.Speaking,
                statusLine = reply,
            )
        }
        tts.speak(reply, turnLanguage)
    }

    private fun onWakePhrase(heard: String) {
        val match = matchHeyKai(heard) ?: return
        wake.disarm()
        if (match.command.isBlank()) {
            onPushToTalkPressed()
        } else {
            onSpeechFinal(match.command)
        }
    }

    private fun armWake() {
        if (!wakeScreenOpen || !wakeMicGranted) return
        if (_ui.value.phase != VoicePhase.Idle || _ui.value.pendingConfirmation != null) return
        if (!wake.onDeviceAvailable()) {
            _ui.update { it.copy(wakeArmed = false, statusLine = WAKE_NO_ON_DEVICE) }
            return
        }
        wake.arm()
    }

    override fun onCleared() {
        wake.destroy()
        stt.destroy()
        tts.destroy()
        super.onCleared()
    }
}
