package com.kai.assistant.voice.wake

import java.io.File

/** On-device keyword spotter. Implementations must not send samples off the phone. */
internal interface HeyKaiSpotter {
    fun load(modelDir: File, sensitivity: Float)
    fun setSensitivity(sensitivity: Float)
    fun accept(samples: FloatArray): Boolean
    fun release()
}
