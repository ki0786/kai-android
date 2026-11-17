package com.kai.assistant.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.kai.assistant.data.VoiceGender
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TTS with barge-in support ([stop]), speech rate, and best-effort male/female voice pick.
 */
class TextToSpeechManager(
    context: Context,
    private val onSpeakingChanged: (Boolean) -> Unit = {},
    private val onReady: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ready = AtomicBoolean(false)
    private var speechRate: Float = 1.0f
    private var gender: VoiceGender = VoiceGender.Female
    private var languageTag: String = Locale.getDefault().toLanguageTag()
    private var pendingSpeak: String? = null
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            val engine = tts
            val ok = status == TextToSpeech.SUCCESS && engine != null
            ready.set(ok)
            if (ok && engine != null) {
                applyVoicePreferences(engine)
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post { onSpeakingChanged(true) }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { onSpeakingChanged(false) }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { onSpeakingChanged(false) }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        mainHandler.post { onSpeakingChanged(false) }
                    }
                })
                pendingSpeak?.let { text ->
                    pendingSpeak = null
                    speakInternal(engine, text)
                }
            }
            mainHandler.post { onReady(ok) }
        }
    }

    fun updatePreferences(languageCode: String, voiceGender: VoiceGender, rate: Float) {
        languageTag = when (languageCode) {
            "hi" -> "hi-IN"
            "kn" -> "kn-IN"
            else -> "en-US"
        }
        gender = voiceGender
        speechRate = rate.coerceIn(0.5f, 2.0f)
        if (ready.get()) {
            mainHandler.post { tts?.let { applyVoicePreferences(it) } }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        mainHandler.post {
            val engine = tts
            if (!ready.get() || engine == null) {
                pendingSpeak = text
                return@post
            }
            speakInternal(engine, text)
        }
    }

    fun stop() {
        mainHandler.post {
            pendingSpeak = null
            runCatching { tts?.stop() }
            onSpeakingChanged(false)
        }
    }

    fun destroy() {
        mainHandler.post {
            pendingSpeak = null
            onSpeakingChanged(false)
            runCatching {
                tts?.stop()
                tts?.shutdown()
            }
            tts = null
            ready.set(false)
        }
    }

    private fun speakInternal(engine: TextToSpeech, text: String) {
        applyVoicePreferences(engine)
        val utteranceId = UUID.randomUUID().toString()
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
    }

    private fun applyVoicePreferences(engine: TextToSpeech) {
        val locale = Locale.forLanguageTag(languageTag)
        engine.language = locale
        engine.setSpeechRate(speechRate)
        pickVoice(engine, locale)?.let { engine.voice = it }
    }

    private fun pickVoice(engine: TextToSpeech, locale: Locale): Voice? {
        val voices = runCatching { engine.voices }.getOrNull() ?: return null
        val localeMatches = voices.filter { voice ->
            voice.locale.language.equals(locale.language, ignoreCase = true) &&
                !voice.isNetworkConnectionRequired
        }.ifEmpty {
            voices.filter { it.locale.language.equals(locale.language, ignoreCase = true) }
        }.ifEmpty { voices.toList() }

        fun score(voice: Voice): Int {
            val name = voice.name.lowercase(Locale.US)
            val features = voice.features.joinToString(" ").lowercase(Locale.US)
            val blob = "$name $features"
            return when (gender) {
                VoiceGender.Female -> when {
                    blob.contains("female") || blob.contains("woman") -> 3
                    blob.contains("male") || blob.contains("man") -> 0
                    else -> 1
                }
                VoiceGender.Male -> when {
                    blob.contains("male") || blob.contains("man") -> 3
                    blob.contains("female") || blob.contains("woman") -> 0
                    else -> 1
                }
            }
        }

        return localeMatches.maxByOrNull(::score)
    }
}
