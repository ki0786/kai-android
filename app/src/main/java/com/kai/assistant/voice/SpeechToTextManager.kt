package com.kai.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Push-to-talk SpeechRecognizer wrapper. Mic is only active between [startListening]
 * and [stopListening] / [cancel] — never continuous background capture.
 */
class SpeechToTextManager(
    context: Context,
    private val onPartial: (String) -> Unit = {},
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningChanged: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private val listening = AtomicBoolean(false)
    private var languageTag: String = Locale.getDefault().toLanguageTag()

    fun setLanguageTag(tag: String) {
        languageTag = tag
    }

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun startListening() {
        mainHandler.post {
            if (!isAvailable()) {
                onError("Speech recognition is not available on this device.")
                return@post
            }
            ensureRecognizer()
            if (!listening.compareAndSet(false, true)) return@post
            onListeningChanged(true)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            }
            runCatching { recognizer?.startListening(intent) }
                .onFailure {
                    listening.set(false)
                    onListeningChanged(false)
                    onError(it.message ?: "Could not start listening.")
                }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (!listening.get()) return@post
            runCatching { recognizer?.stopListening() }
        }
    }

    fun cancel() {
        mainHandler.post {
            listening.set(false)
            onListeningChanged(false)
            runCatching { recognizer?.cancel() }
        }
    }

    fun destroy() {
        mainHandler.post {
            listening.set(false)
            onListeningChanged(false)
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit

                override fun onError(error: Int) {
                    listening.set(false)
                    onListeningChanged(false)
                    // Ignore cancel/no-match when user released without speaking.
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        onFinal("")
                        return
                    }
                    onError(errorMessage(error))
                }

                override fun onResults(results: Bundle?) {
                    listening.set(false)
                    onListeningChanged(false)
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    onFinal(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (text.isNotBlank()) onPartial(text)
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        -> "Speech recognition needs a network connection on this device."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy. Try again."
        SpeechRecognizer.ERROR_SERVER -> "Speech recognition server error."
        else -> "Speech recognition error ($code)."
    }
}
