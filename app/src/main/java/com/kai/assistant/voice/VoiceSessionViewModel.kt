package com.kai.assistant.voice

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.kai.assistant.voice.wake.WakePrefs
import com.kai.assistant.voice.wake.WakeTurnGate
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var spokenNow: String = ""
    private var conversation = false
    private var bargeCaptured = false
    private var followUpActive = false
    private var speakStartedAt = 0L
    private val followUp = FollowUpWindow()
    private var followUpRunnable: Runnable? = null

    private val tts = TextToSpeechManager(
        context = application,
        onSpeakingChanged = { speaking -> onSpeakingChanged(speaking) },
        onReady = { ready -> _ui.update { it.copy(ttsReady = ready) } },
    )

    private val stt = SpeechToTextManager(
        context = application,
        onPartial = { partial ->
            if (_ui.value.phase == VoicePhase.Speaking && shouldBargeIn(partial, spokenNow)) {
                bargeCaptured = true
                followUp.onSpeech()
                tts.stop()
            } else if (followUpActive && partial.isNotBlank() && !isAssistantEcho(partial, spokenNow)) {
                followUp.onSpeech()
            }
            _ui.update {
                it.copy(
                    partialText = partial,
                    statusLine = partial.ifBlank { "Listening…" },
                )
            }
        },
        onSpeechStarted = {
            if (_ui.value.phase != VoicePhase.Speaking) followUp.onSpeech()
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
            releaseWakeIfIdle()
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
    private var backgroundHeyKai = WakePrefs.isEnabled(application)

    init {
        _ui.update { it.copy(sttAvailable = stt.isAvailable()) }
        viewModelScope.launch {
            _ui.map { it.phase to (it.pendingConfirmation != null) }
                .distinctUntilChanged()
                .collect { (phase, confirming) ->
                    val busy = phase != VoicePhase.Idle || confirming
                    WakeTurnGate.onVoiceSession(busy)
                    if (!wakeScreenOpen) return@collect
                    if (backgroundHeyKai || busy) wake.disarm() else armWake()
                }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                backgroundHeyKai = settings.backgroundHeyKai
                if (backgroundHeyKai) wake.disarm()
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
        if (backgroundHeyKai) {
            wake.disarm()
            _ui.update { it.copy(wakeArmed = false, statusLine = "Say your command, or hold the mic.") }
            return
        }
        if (_ui.value.phase == VoicePhase.Idle && _ui.value.pendingConfirmation == null) {
            _ui.update { it.copy(statusLine = "Say Hey KAI, or hold the mic to talk.") }
            armWake()
        }
    }

    /** Background Hey KAI already released its microphone. Start the command recognizer. */
    fun onBackgroundWake() {
        if (_ui.value.pendingConfirmation != null) return
        wake.disarm()
        onPushToTalkPressed()
    }

    fun onWakeScreenClosed() {
        wakeScreenOpen = false
        wake.disarm()
        if (_ui.value.phase == VoicePhase.Listening) {
            stt.cancel()
        }
        _ui.update { it.copy(wakeArmed = false) }
    }

    fun onPushToTalkPressed() {
        if (_ui.value.pendingConfirmation != null) return
        conversation = false
        followUpActive = false
        bargeCaptured = true
        followUp.close()
        clearFollowUpTimer()
        WakeTurnGate.onVoiceSession(true)
        wake.disarm()
        tts.stop()
        SpokenAlert.stop()
        if (!stt.isAvailable()) {
            _ui.update {
                it.copy(statusLine = "Speech recognition unavailable. Use text chat instead.")
            }
            releaseWakeIfIdle()
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
        conversation = false
        followUpActive = false
        bargeCaptured = false
        followUp.close()
        clearFollowUpTimer()
        tts.stop()
        stt.cancel()
        SpokenAlert.stop()
        _ui.update {
            it.copy(
                phase = VoicePhase.Idle,
                statusLine = "Interrupted. Ready when you are.",
            )
        }
        releaseWakeIfIdle()
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
        say("Okay, cancelled.")
    }

    private fun onSpeechFinal(finalText: String) {
        val text = finalText.trim().ifBlank { _ui.value.partialText.trim() }
        if (text.isBlank() || isAssistantEcho(text, spokenNow)) {
            if (_ui.value.phase == VoicePhase.Speaking) {
                stt.startListening()
                return
            }
            if (followUpActive && !followUp.expired(SystemClock.elapsedRealtime())) {
                stt.startListening()
                return
            }
            if (followUpActive) {
                endFollowUp()
                return
            }
            _ui.update {
                it.copy(
                    phase = VoicePhase.Idle,
                    statusLine = "Didn't catch that — hold to try again.",
                    partialText = "",
                )
            }
            releaseWakeIfIdle()
            return
        }
        followUpActive = false
        followUp.close()
        clearFollowUpTimer()
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
                say(outcome.match.confirmationPrompt)
            }
            is TurnOutcome.NeedsContactsPermission -> {
                _contactsPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                say(outcome.explanation)
            }
            is TurnOutcome.NeedsCallPermission -> {
                _callPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                say(outcome.explanation)
            }
            is TurnOutcome.NeedsAccessibilitySettings -> {
                _accessibilityTarget.value = outcome.target
                _accessibilitySettings.value = System.nanoTime()
                say(outcome.explanation)
            }
            is TurnOutcome.NeedsCalendarPermission -> {
                _calendarPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                say(outcome.explanation)
            }
            is TurnOutcome.NeedsNotificationPermission -> {
                _notificationPermission.value = ContactsPermissionRequest(
                    token = System.nanoTime(),
                    match = outcome.pending,
                )
                say(outcome.explanation)
            }
            is TurnOutcome.NeedsPhoto -> {
                _photoPick.value = System.nanoTime() to outcome.useBackend
                say(outcome.explanation)
            }
            is TurnOutcome.NeedsScreenshotConsent -> {
                _screenshotConsent.value = System.nanoTime()
                say(outcome.explanation)
            }
            is TurnOutcome.LocalReady -> say(outcome.reply)
            is TurnOutcome.CloudReady -> say(outcome.spoken)
        }
    }

    private fun say(reply: String) {
        if (reply.isBlank()) {
            releaseWakeIfIdle()
            return
        }
        spokenNow = reply
        bargeCaptured = false
        conversation = true
        followUpActive = false
        followUp.close()
        clearFollowUpTimer()
        speakStartedAt = SystemClock.elapsedRealtime()
        _ui.update {
            it.copy(
                phase = VoicePhase.Speaking,
                statusLine = reply,
            )
        }
        tts.speak(reply, turnLanguage)
        mainHandler.postDelayed({
            if (conversation && _ui.value.phase == VoicePhase.Speaking) stt.startListening()
        }, 200)
    }

    private fun onSpeakingChanged(speaking: Boolean) {
        if (speaking) {
            _ui.update { it.copy(phase = VoicePhase.Speaking) }
            return
        }
        if (SystemClock.elapsedRealtime() - speakStartedAt < 300) return
        if (bargeCaptured) return
        if (conversation) {
            beginFollowUp()
            return
        }
        _ui.update { state ->
            if (state.phase == VoicePhase.Speaking) state.copy(phase = VoicePhase.Idle) else state
        }
    }

    private fun beginFollowUp() {
        followUpActive = true
        followUp.open(SystemClock.elapsedRealtime())
        _ui.update {
            it.copy(phase = VoicePhase.Listening, statusLine = "Listening…", partialText = "")
        }
        if (!stt.isListening()) stt.startListening()
        clearFollowUpTimer()
        val runnable = Runnable {
            if (!followUpActive) return@Runnable
            if (!followUp.expired(SystemClock.elapsedRealtime())) return@Runnable
            stt.cancel()
        }
        followUpRunnable = runnable
        mainHandler.postDelayed(runnable, FOLLOW_UP_LISTEN_MS)
    }

    private fun endFollowUp() {
        conversation = false
        followUpActive = false
        followUp.close()
        clearFollowUpTimer()
        _ui.update {
            it.copy(
                phase = VoicePhase.Idle,
                statusLine = "Say Hey KAI, or hold the mic to talk.",
                partialText = "",
            )
        }
        releaseWakeIfIdle()
    }

    private fun clearFollowUpTimer() {
        followUpRunnable?.let { mainHandler.removeCallbacks(it) }
        followUpRunnable = null
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

    private fun releaseWakeIfIdle() {
        val state = _ui.value
        val busy = state.phase != VoicePhase.Idle || state.pendingConfirmation != null
        WakeTurnGate.onVoiceSession(busy)
    }

    private fun armWake() {
        if (backgroundHeyKai) return
        if (!wakeScreenOpen || !wakeMicGranted) return
        if (_ui.value.phase != VoicePhase.Idle || _ui.value.pendingConfirmation != null) return
        if (!wake.onDeviceAvailable()) {
            _ui.update { it.copy(wakeArmed = false, statusLine = WAKE_NO_ON_DEVICE) }
            return
        }
        wake.arm()
    }

    override fun onCleared() {
        clearFollowUpTimer()
        WakeTurnGate.onVoiceSession(false)
        wake.destroy()
        stt.destroy()
        tts.destroy()
        super.onCleared()
    }
}
