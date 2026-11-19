package com.kai.assistant.tools

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun reminderSpokenLine(text: String): String = "Reminder. $text"

internal fun timerFinishedSpokenLine(): String = "Your timer finished."

/**
 * Speaks a short alert when a reminder or timer fires, including if the app
 * is not on screen. The picture and the words stay on this phone.
 */
object SpokenAlert {
    private val active = AtomicReference<TextToSpeech?>(null)

    fun stop() {
        val engine = active.getAndSet(null)
        if (engine == null) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                engine.stop()
                engine.shutdown()
            }
        }
    }

    fun speak(context: Context, text: String, onFinished: () -> Unit = {}) {
        if (text.isBlank()) {
            onFinished()
            return
        }
        val app = context.applicationContext
        val finished = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        val holder = arrayOfNulls<TextToSpeech>(1)
        fun done() {
            if (!finished.compareAndSet(false, true)) return
            main.post {
                val engine = holder[0]
                holder[0] = null
                if (active.get() === engine) active.set(null)
                runCatching {
                    engine?.stop()
                    engine?.shutdown()
                }
                onFinished()
            }
        }
        main.post {
            holder[0] = TextToSpeech(app) { status ->
                val tts = holder[0]
                active.set(tts)
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    done()
                    return@TextToSpeech
                }
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) = done()

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = done()

                    override fun onError(utteranceId: String?, errorCode: Int) = done()
                })
                tts.language = Locale.getDefault()
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "kai-alert")
                if (result == TextToSpeech.ERROR) done()
            }
        }
        main.postDelayed({ done() }, 12_000)
    }
}
