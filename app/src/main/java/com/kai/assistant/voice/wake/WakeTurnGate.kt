package com.kai.assistant.voice.wake

import android.os.SystemClock

/**
 * Holds the wake microphone closed while a command is being heard or spoken,
 * and for a short window after a detection so the voice screen can take the mic
 * before standby recording starts again.
 */
internal class WakeTurnGateState {
    var commandBusy: Boolean = false
        private set

    private var detectArmedAt: Long = 0L

    fun onDetected(now: Long) {
        detectArmedAt = now
        commandBusy = true
    }

    fun onVoiceSession(busy: Boolean) {
        if (busy) {
            commandBusy = true
            detectArmedAt = 0L
        } else if (detectArmedAt == 0L) {
            commandBusy = false
        }
    }

    /** @return true when the post-detect window elapsed without the voice screen taking over. */
    fun expire(now: Long, windowMs: Long = DETECT_WINDOW_MS): Boolean {
        val armed = detectArmedAt
        if (armed == 0L || now - armed <= windowMs) return false
        detectArmedAt = 0L
        commandBusy = false
        return true
    }
}

internal object WakeTurnGate {
    private val state = WakeTurnGateState()

    @Volatile
    private var releaseListener: ((Boolean) -> Unit)? = null

    /** [listener] is called with true when the wake recorder must be released now. */
    fun setReleaseListener(listener: ((Boolean) -> Unit)?) {
        releaseListener = listener
    }

    fun onDetected(now: Long = SystemClock.elapsedRealtime()) {
        state.onDetected(now)
        releaseListener?.invoke(true)
    }

    fun onVoiceSession(busy: Boolean) {
        state.onVoiceSession(busy)
        releaseListener?.invoke(state.commandBusy)
    }

    fun expire(now: Long = SystemClock.elapsedRealtime(), windowMs: Long = DETECT_WINDOW_MS): Boolean {
        val expired = state.expire(now, windowMs)
        if (expired) releaseListener?.invoke(false)
        return expired
    }

    fun holdsMic(): Boolean = state.commandBusy
}
