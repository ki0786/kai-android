package com.kai.assistant.voice

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The platform recognizer plays a short earcon when the mic opens and closes.
 * Mute the streams that earcon uses, then put them back. The ring stream is
 * left alone so this cannot change silent mode or Do Not Disturb.
 */
internal object MicEarconSilence {
    private const val START_MAX_MS = 1_200L
    private const val AFTER_READY_MS = 200L
    private const val STOP_MS = 400L
    private val streams = intArrayOf(
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_MUSIC,
    )
    private val handler = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    private var deadline = 0L
    private val mutedByUs = HashSet<Int>()
    private val release = Runnable { unmute() }

    fun coverStart(context: Context) = cover(context, START_MAX_MS, extendOnly = false)

    fun coverReady(context: Context) = cover(context, AFTER_READY_MS, extendOnly = false)

    fun coverStop(context: Context) = cover(context, STOP_MS, extendOnly = true)

    private fun cover(context: Context, forMs: Long, extendOnly: Boolean) {
        val action = Runnable { applyCover(context, forMs, extendOnly) }
        if (Looper.myLooper() == Looper.getMainLooper()) action.run() else handler.post(action)
    }

    private fun applyCover(context: Context, forMs: Long, extendOnly: Boolean) {
        val manager = context.applicationContext.getSystemService(AudioManager::class.java) ?: return
        audio = manager
        mute(manager)
        val until = SystemClock.elapsedRealtime() + forMs
        deadline = if (extendOnly) maxOf(deadline, until) else until
        handler.removeCallbacks(release)
        val wait = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        handler.postDelayed(release, wait)
    }

    private fun mute(manager: AudioManager) {
        for (stream in streams) {
            if (mutedByUs.contains(stream) || manager.isStreamMute(stream)) continue
            runCatching { manager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) }
            if (manager.isStreamMute(stream)) mutedByUs.add(stream)
        }
    }

    private fun unmute() {
        val manager = audio ?: return
        if (SystemClock.elapsedRealtime() + 20 < deadline) {
            handler.postDelayed(release, deadline - SystemClock.elapsedRealtime())
            return
        }
        val streamsToRestore = mutedByUs.toList()
        mutedByUs.clear()
        deadline = 0L
        for (stream in streamsToRestore) {
            if (!manager.isStreamMute(stream)) continue
            runCatching { manager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) }
        }
    }
}
