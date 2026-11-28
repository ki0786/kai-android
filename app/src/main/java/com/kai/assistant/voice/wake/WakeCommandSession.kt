package com.kai.assistant.voice.wake

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kai.assistant.assistant.AssistantTurnProcessor
import com.kai.assistant.voice.FollowUpWindow
import com.kai.assistant.voice.SpeechToTextManager
import com.kai.assistant.voice.TextToSpeechManager
import com.kai.assistant.voice.isAssistantEcho
import com.kai.assistant.voice.shouldBargeIn
import com.kai.assistant.voice.spokenTurn
import com.kai.assistant.voice.FOLLOW_UP_LISTEN_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hears the command after Hey KAI without bringing [com.kai.assistant.MainActivity]
 * forward. There is no chime. The listening notification is already showing.
 * After the reply, the mic stays open for a few seconds. Speech during the
 * reply stops playback and becomes the next command.
 */
internal object WakeCommandSession {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var generation = 0
    private var listeningArmed = false
    private var speakStartedAt = 0L
    private var job: Job? = null
    private var stt: SpeechToTextManager? = null
    private var tts: TextToSpeechManager? = null
    private var processor: AssistantTurnProcessor? = null
    private var spokenNow = ""
    private var speaking = false
    private var bargeCaptured = false
    private var active = false
    private val followUp = FollowUpWindow()
    private var followUpRunnable: Runnable? = null

    fun start(context: Context) {
        val app = context.applicationContext
        val token = ++generation
        listeningArmed = false
        active = true
        speaking = false
        bargeCaptured = false
        followUp.close()
        clearFollowUpTimer()
        job?.cancel()
        // Hold the wake mic before returning. The audio thread is still in the detect loop.
        WakeTurnGate.onVoiceSession(true)
        main.post {
            if (token != generation) return@post
            ensure(app)
            stt?.cancel()
            main.post {
                if (token != generation) return@post
                listeningArmed = true
                stt?.startListening()
            }
        }
    }

    fun stop() {
        val token = generation
        finish(token)
        stt?.destroy()
        tts?.destroy()
        stt = null
        tts = null
        processor = null
    }

    private fun ensure(context: Context) {
        if (processor == null) processor = AssistantTurnProcessor(context)
        if (tts == null) {
            tts = TextToSpeechManager(
                context = context,
                onSpeakingChanged = { isSpeaking -> onSpeaking(isSpeaking) },
            )
        }
        if (stt == null) {
            stt = SpeechToTextManager(
                context = context,
                onPartial = { partial -> onPartial(partial) },
                onFinal = { text -> onFinal(text) },
                onError = { finish(generation) },
                onSpeechStarted = {
                    if (!speaking) followUp.onSpeech()
                },
            )
        }
    }

    private fun onPartial(partial: String) {
        if (!active) return
        if (speaking && shouldBargeIn(partial, spokenNow)) {
            bargeCaptured = true
            followUp.onSpeech()
            tts?.stop()
            return
        }
        if (!speaking && partial.isNotBlank() && !isAssistantEcho(partial, spokenNow)) {
            followUp.onSpeech()
        }
    }

    private fun onSpeaking(isSpeaking: Boolean) {
        if (isSpeaking) {
            speaking = true
            return
        }
        // Flushing a new reply delivers the previous utterance's end immediately.
        if (SystemClock.elapsedRealtime() - speakStartedAt < 300) return
        if (!speaking || !active || bargeCaptured) {
            speaking = false
            return
        }
        speaking = false
        beginFollowUp(generation)
    }

    private fun onFinal(text: String) {
        if (!active || !listeningArmed) return
        val heard = text.trim()
        val token = generation
        if (heard.isBlank() || isAssistantEcho(heard, spokenNow)) {
            if (speaking) {
                stt?.startListening()
                return
            }
            if (followUp.isOpen && !followUp.expired(SystemClock.elapsedRealtime())) {
                stt?.startListening()
                return
            }
            finish(token)
            return
        }
        clearFollowUpTimer()
        followUp.close()
        bargeCaptured = false
        job?.cancel()
        job = scope.launch {
            val outcome = processor?.handleUserText(heard) ?: return@launch
            if (token != generation) return@launch
            val reply = spokenTurn(outcome)
            if (reply.isBlank()) finish(token) else speak(token, reply)
        }
    }

    private fun speak(token: Int, text: String) {
        if (token != generation) return
        spokenNow = text
        bargeCaptured = false
        speaking = true
        speakStartedAt = SystemClock.elapsedRealtime()
        followUp.close()
        tts?.speak(text)
        main.postDelayed({
            if (token == generation && speaking) stt?.startListening()
        }, 200)
    }

    private fun beginFollowUp(token: Int) {
        if (token != generation || !active) return
        followUp.open(SystemClock.elapsedRealtime())
        if (stt?.isListening() != true) stt?.startListening()
        clearFollowUpTimer()
        val runnable = Runnable {
            if (token != generation) return@Runnable
            if (!followUp.expired(SystemClock.elapsedRealtime())) return@Runnable
            stt?.cancel()
        }
        followUpRunnable = runnable
        main.postDelayed(runnable, FOLLOW_UP_LISTEN_MS)
    }

    private fun finish(token: Int) {
        if (token != generation) return
        generation++
        active = false
        listeningArmed = false
        speaking = false
        bargeCaptured = false
        followUp.close()
        clearFollowUpTimer()
        job?.cancel()
        WakeTurnGate.onVoiceSession(false)
    }

    private fun clearFollowUpTimer() {
        followUpRunnable?.let { main.removeCallbacks(it) }
        followUpRunnable = null
    }
}
