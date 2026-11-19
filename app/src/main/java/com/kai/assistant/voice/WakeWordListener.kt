package com.kai.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Short on-device listener for "Hey KAI".
 * It uses [SpeechRecognizer.createOnDeviceSpeechRecognizer] only.
 * It does not open a cloud recognition stream and it does not run after the screen closes.
 */
class WakeWordListener(
    context: Context,
    private val onPhrase: (String) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val onBlocked: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val armed = AtomicBoolean(false)
    private var recognizer: SpeechRecognizer? = null
    private var delivered = false

    fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    fun arm() {
        mainHandler.post {
            if (!onDeviceAvailable()) {
                armed.set(false)
                onListening(false)
                onBlocked(WAKE_NO_ON_DEVICE)
                return@post
            }
            if (!armed.compareAndSet(false, true)) return@post
            delivered = false
            onListening(true)
            listen()
        }
    }

    fun disarm() {
        val wasArmed = armed.getAndSet(false)
        mainHandler.removeCallbacks(restart)
        if (!wasArmed) return
        mainHandler.post {
            onListening(false)
            runCatching { recognizer?.cancel() }
        }
    }

    fun destroy() {
        armed.set(false)
        mainHandler.removeCallbacks(restart)
        mainHandler.post {
            onListening(false)
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private val restart = Runnable {
        if (armed.get()) listen()
    }

    private fun listen() {
        if (!armed.get()) return
        val active = ensureRecognizer() ?: run {
            armed.set(false)
            onListening(false)
            onBlocked(WAKE_NO_ON_DEVICE)
            return
        }
        delivered = false
        val started = runCatching { active.startListening(shortIntent()) }
        if (started.isFailure) {
            armed.set(false)
            onListening(false)
            onBlocked(WAKE_BACKGROUND_NOTE)
        }
    }

    private fun ensureRecognizer(): SpeechRecognizer? {
        if (!onDeviceAvailable()) return null
        recognizer?.let { return it }
        val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                val text = firstText(results)
                if (!delivered && text.isNotBlank() && matchHeyKai(text) != null) {
                    deliver(text)
                } else {
                    scheduleRestart()
                }
            }

            override fun onError(error: Int) {
                if (delivered) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_CLIENT,
                    -> scheduleRestart()
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                        mainHandler.removeCallbacks(restart)
                        mainHandler.postDelayed(restart, 500)
                    }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> block(WAKE_BACKGROUND_NOTE)
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER,
                    -> block(WAKE_NO_ON_DEVICE)
                    else -> scheduleRestart()
                }
            }
        })
        recognizer = created
        return created
    }

    private fun deliver(text: String) {
        if (!armed.get() || delivered) return
        delivered = true
        armed.set(false)
        mainHandler.removeCallbacks(restart)
        runCatching { recognizer?.cancel() }
        onListening(false)
        onPhrase(text)
    }

    private fun scheduleRestart() {
        if (!armed.get()) return
        mainHandler.removeCallbacks(restart)
        mainHandler.postDelayed(restart, 300)
    }

    private fun block(message: String) {
        armed.set(false)
        mainHandler.removeCallbacks(restart)
        onListening(false)
        onBlocked(message)
    }

    private fun firstText(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private fun shortIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 250L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
        }
    }
}
