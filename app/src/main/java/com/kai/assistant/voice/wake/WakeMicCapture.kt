package com.kai.assistant.voice.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Short-lived microphone capture for the wake word. [release] drops the recorder immediately. */
internal class WakeMicCapture {
    private val lock = Any()
    private var record: AudioRecord? = null

    fun isOpen(): Boolean = synchronized(lock) { record != null }

    @SuppressLint("MissingPermission")
    fun open(): Boolean = synchronized(lock) {
        releaseLocked()
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, channel, encoding)
        if (minBytes <= 0) return false
        val built = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(channel)
                        .build(),
                )
                .setBufferSizeInBytes(minBytes * 2)
                .build()
        }.getOrNull() ?: return false
        if (built.state != AudioRecord.STATE_INITIALIZED) {
            built.release()
            return false
        }
        val started = runCatching { built.startRecording() }
        if (started.isFailure || built.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            runCatching { built.stop() }
            built.release()
            return false
        }
        record = built
        true
    }

    fun read(buffer: ShortArray): Int {
        val active = synchronized(lock) { record } ?: return -1
        // stop() from [release] unblocks a read that is already in progress.
        return runCatching { active.read(buffer, 0, buffer.size) }.getOrDefault(-1)
    }

    fun release() {
        val active = synchronized(lock) {
            record.also { record = null }
        } ?: return
        runCatching { active.stop() }
        runCatching { active.release() }
    }

    private fun releaseLocked() {
        val active = record ?: return
        record = null
        runCatching { active.stop() }
        runCatching { active.release() }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
