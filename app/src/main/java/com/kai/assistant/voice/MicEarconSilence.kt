package com.kai.assistant.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The speech recognizer plays its own open and close earcon. On this phone that
 * tone follows the assistant and system streams, so a mute flag on music alone
 * does not stop it. Drop those volumes to zero for a short window, then put
 * the saved levels back. The ring stream is not touched.
 */
internal object MicEarconSilence {
    private const val HOLD_MS = 1_500L

    /** AudioManager.STREAM_ASSISTANT. The recognizer earcon is played on this stream. */
    private const val STREAM_ASSISTANT = 11
    private val streams = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_DTMF,
        STREAM_ASSISTANT,
    )
    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var deadline = 0L
    private val saved = LinkedHashMap<Int, Int>()
    private val release = Runnable { restore() }

    fun coverStart(context: Context) = cover(context, HOLD_MS)

    fun coverReady(context: Context) = cover(context, HOLD_MS)

    fun coverStop(context: Context) = cover(context, HOLD_MS)

    private fun cover(context: Context, holdMs: Long) {
        val action = Runnable { applyCover(context, holdMs) }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }

    private fun applyCover(context: Context, holdMs: Long) {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        audio = manager
        zero(manager)
        deadline = earconMuteUntil(SystemClock.elapsedRealtime(), deadline, holdMs)
        handler.removeCallbacks(release)
        handler.postDelayed(release, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    private fun zero(manager: AudioManager) {
        for (stream in streams) {
            if (saved.containsKey(stream)) continue
            val volume = runCatching { manager.getStreamVolume(stream) }.getOrNull() ?: continue
            if (volume <= 0) continue
            val wrote = runCatching {
                manager.setStreamVolume(stream, 0, 0)
                manager.getStreamVolume(stream) == 0
            }.getOrDefault(false)
            if (wrote) saved[stream] = volume
        }
    }

    private fun restore() {
        val manager = audio ?: return
        val now = SystemClock.elapsedRealtime()
        if (now + 20 < deadline) {
            handler.postDelayed(release, deadline - now)
            return
        }
        val levels = saved.toMap()
        saved.clear()
        deadline = 0L
        for ((stream, volume) in levels) {
            runCatching {
                if (manager.getStreamVolume(stream) == 0) manager.setStreamVolume(stream, volume, 0)
            }
        }
    }
}

/** A later event can extend the quiet window. It cannot open it early. */
internal fun earconMuteUntil(now: Long, currentDeadline: Long, holdMs: Long): Long =
    maxOf(currentDeadline, now + holdMs)
