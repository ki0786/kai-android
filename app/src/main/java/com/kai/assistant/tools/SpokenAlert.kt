package com.kai.assistant.tools

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

internal fun reminderSpokenLine(text: String): String = "Reminder. $text"

internal fun timerFinishedSpokenLine(): String = "Your timer finished."

/**
 * Speaks a short alert when a reminder or timer fires, including if the app
 * is not on screen. The picture and the words stay on this phone.
 */
object SpokenAlert {
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
                runCatching {
                    holder[0]?.stop()
                    holder[0]?.shutdown()
                }
                holder[0] = null
                onFinished()
            }
        }
        main.post {
            holder[0] = TextToSpeech(app) { status ->
                val tts = holder[0]
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
