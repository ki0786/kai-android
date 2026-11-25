package com.kai.assistant.voice.wake

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Sherpa-ONNX keyword spotter for "hey kai".
 * Samples stay in this process. There is no network call on the audio path.
 */
internal class SherpaHeyKaiSpotter : HeyKaiSpotter {
    private val lock = Any()
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null
    private var sensitivity: Float = 0.5f

    @Volatile
    private var pendingSensitivity: Float? = null

    override fun load(modelDir: File, sensitivity: Float) {
        synchronized(lock) {
            releaseLocked()
            this.sensitivity = sensitivity.coerceIn(0f, 1f)
            val keywords = File(modelDir, "keywords.txt")
            keywords.writeText(heyKaiKeywordLine(this.sensitivity) + "\n")
            val config = KeywordSpotterConfig(
                // 16 kHz, 80 mel bins, dither off. Sherpa reads T=45 from the
                // encoder and feeds that many frames. The chunk-16 downsample
                // then sees kwsSubsampledFrames(45) == 16, which matches
                // reshape {8, 2, 1, 128}.
                featConfig = FeatureConfig(
                    sampleRate = SAMPLE_RATE,
                    featureDim = KWS_FEATURE_DIM,
                    dither = 0f,
                ),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = File(modelDir, "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx").path,
                        decoder = File(modelDir, "decoder-epoch-12-avg-2-chunk-16-left-64.onnx").path,
                        joiner = File(modelDir, "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx").path,
                    ),
                    tokens = File(modelDir, "tokens.txt").path,
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                    modelType = "zipformer2",
                ),
                maxActivePaths = 4,
                keywordsFile = keywords.path,
                keywordsScore = KEYWORD_BOOST,
                keywordsThreshold = keywordsThreshold(this.sensitivity),
                numTrailingBlanks = 2,
            )
            spotter = KeywordSpotter(assetManager = null, config = config)
            openStreamLocked()
        }
    }

    override fun setSensitivity(sensitivity: Float) {
        pendingSensitivity = sensitivity.coerceIn(0f, 1f)
    }

    override fun accept(samples: FloatArray): Boolean {
        if (samples.isEmpty()) return false
        synchronized(lock) {
            applyPendingLocked()
            val active = stream ?: return false
            val engine = spotter ?: return false
            active.acceptWaveform(samples, SAMPLE_RATE)
            while (engine.isReady(active)) {
                engine.decode(active)
                val keyword = engine.getResult(active).keyword
                if (keyword.isNotBlank()) {
                    engine.reset(active)
                    if (isHeyKaiSpot(keyword)) return true
                }
            }
            return false
        }
    }

    override fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun applyPendingLocked() {
        val next = pendingSensitivity ?: return
        pendingSensitivity = null
        if (next == sensitivity || spotter == null) {
            sensitivity = next
            return
        }
        sensitivity = next
        openStreamLocked()
    }

    private fun openStreamLocked() {
        val engine = spotter ?: return
        stream?.release()
        val line = heyKaiKeywordLine(sensitivity)
        val created = engine.createStream(line)
        stream = if (created.ptr != 0L) {
            created
        } else {
            created.release()
            val fallback = engine.createStream()
            if (fallback.ptr == 0L) {
                fallback.release()
                null
            } else {
                fallback
            }
        }
    }

    private fun releaseLocked() {
        stream?.release()
        stream = null
        spotter?.release()
        spotter = null
    }

    private companion object {
        const val SAMPLE_RATE = 16000
    }
}
