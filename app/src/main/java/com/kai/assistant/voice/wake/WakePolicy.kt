package com.kai.assistant.voice.wake

import java.util.Locale

/** Android 15 (API 35) blocks microphone foreground services started from BOOT_COMPLETED. */
internal const val ANDROID_15_API = 35

/** Android 10 (API 29) blocks activity starts from a background service. */
internal const val ANDROID_10_API = 29

/**
 * Flags that bring the existing KAI task forward from the wake service.
 * [android.content.Intent.FLAG_ACTIVITY_NEW_TASK] is required from a service.
 * The other three deliver the voice extras to the running screen and move it
 * in front of whatever app is open.
 */
internal const val VOICE_SCREEN_FLAGS: Int =
    0x10000000 or 0x04000000 or 0x20000000 or 0x00020000

/**
 * Android 10 and newer only let a background service start an activity when
 * the user has allowed the app to draw over other apps. Older versions allow
 * the start directly.
 */
internal fun voiceScreenAllowedFromBackground(sdkInt: Int, canDrawOverlays: Boolean): Boolean =
    sdkInt < ANDROID_10_API || canDrawOverlays

/**
 * Vivo's "background pop-up" op (`checkOpNoThrow` 10021). `0` is allowed,
 * `2` means the op does not apply on this phone, anything else is denied.
 */
internal fun vivoPopupGrant(opMode: Int?): Boolean? = when (opMode) {
    null, 2 -> null
    0 -> true
    else -> false
}

/**
 * True when Hey KAI still needs a grant before it can open the voice screen
 * over another app. Vivo has a separate pop-up switch from the standard overlay.
 */
internal fun needsVoiceOverAppsGrant(
    sdkInt: Int,
    canDrawOverlays: Boolean,
    manufacturer: String,
    vivoPopupMode: Int?,
): Boolean {
    if (!voiceScreenAllowedFromBackground(sdkInt, canDrawOverlays)) return true
    if (!manufacturer.equals("vivo", ignoreCase = true)) return false
    return vivoPopupGrant(vivoPopupMode) == false
}

/**
 * Phrase sherpa returns for this keyword. It is one word on purpose.
 * EncodeKeywords splits the line on whitespace and looks up every word that
 * does not start with `:`, `#`, or `@`. `@HEY KAI` looks up `KAI`, which this
 * model does not have, and that failure aborts the process.
 */
internal const val HEY_KAI_PHRASE = "HEYKAI"

/**
 * BPE pieces for "HEY KAI" from the GigaSpeech zipformer KWS model
 * (`sherpa-onnx-kws-zipformer-gigaspeech-3.3M`, bpe.model). Same tokenizer that
 * encodes the bundled "HEY SIRI" line as `▁HE Y ▁S I RI`.
 * The pieces are `▁HE`, `Y`, `▁K`, `A`, `I` — there is no whole-word `KAI`.
 */
internal const val HEY_KAI_TOKENS = "▁HE Y ▁K A I"

/**
 * Spellings of "hey kai" that this GigaSpeech token list can represent.
 * The first is the clear TTS path. The others are how the same words come
 * out of a phone mic: no pause, "hi" or "hai" for hey, and "kai" as
 * `K Y` (the same diphthong piece as "hey"), `K A Y`, `CA I`, or `K E`.
 * Every piece is in tokens.txt. A missing piece aborts the process.
 */
internal val HEY_KAI_SPELLINGS: List<String> = listOf(
    "▁HE Y ▁K A I",
    "▁HE Y K A I",
    "▁HI ▁K A I",
    "▁HI ▁K A Y",
    "▁HE Y ▁K Y",
    "▁HE Y K Y",
    "▁HE Y ▁K A Y",
    "▁HE Y ▁K E",
    "▁HE Y ▁CA I",
    "▁HA I ▁K A I",
    "▁HE ▁K A I",
)

/**
 * Boost applied to each keyword token so the path stays in the beam.
 * 1.5 kept studio TTS and dropped a live voice. 3.0 is the value the
 * sherpa C API uses when a keyword is hard to keep.
 */
internal const val KEYWORD_BOOST = 3.0f

/** Log a miss at most this often while speech is coming in. */
internal const val KEYWORD_MISS_LOG_MS = 2_000L

/**
 * RMS of 16 kHz float samples (`pcm / 32768`) above which a chunk counts as
 * speech for the miss log. Quiet-room noise stays under this.
 */
internal const val KEYWORD_MISS_RMS = 0.015f

/** Persistent foreground notification while the wake microphone is open. */
internal const val LISTENING_NOTIFICATION = "KAI is listening for 'Hey KAI'"

internal const val DETECT_WINDOW_MS = 12_000L

internal enum class BootHandoff {
    Ignore,
    StartService,
    PostTapNotification,
}

internal enum class WakePauseReason {
    User,
    InCall,
    MicHeld,
    Command,
}

internal enum class WakePresence {
    Off,
    Standby,
    Listening,
    Paused,
}

internal sealed class WakeModelStatus {
    data object Missing : WakeModelStatus()
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : WakeModelStatus()
    data object Ready : WakeModelStatus()
    data class Failed(val message: String) : WakeModelStatus()
}

internal data class WakeModelFile(
    val name: String,
    val bytes: Long,
    val sha256: String,
)

/**
 * Mel bins on the encoder input `[1, T, 80]`.
 */
internal const val KWS_FEATURE_DIM = 80

/**
 * Subsampled frames per call for the `chunk-16` zipformer2 encoder.
 * The first downsample reshapes them as `{8, 2, 1, 128}`.
 */
internal const val KWS_CHUNK_SIZE = 16

/**
 * Fbank frames the encoder consumes per call (`T` in the model metadata).
 * `decode_chunk_len` is `chunk_size * 2` (32). Conv2dSubsampling needs 7
 * frames of context and ConvNeXt keeps 3 frames of right context, so
 * `T = 32 + 7 + 2 * 3 = 45`.
 */
internal const val KWS_ENCODER_FRAMES = 45

/**
 * Frames left after the streaming embed: `(T - 7) / 2` from Conv2dSubsampling,
 * minus the 3 right-context frames ConvNeXt holds back. For this model that
 * is 16. The downsample reshape is `{frames/2, 2, batch, channels}`, so an
 * odd count (17) aborts the process.
 */
internal fun kwsSubsampledFrames(featureFrames: Int): Int = (featureFrames - 7) / 2 - 3

internal fun kwsDownsampleAccepts(frames: Int): Boolean = frames > 0 && frames % 2 == 0

/**
 * Official k2-fsa GigaSpeech bundle. Int8 encoder and joiner, fp32 decoder.
 * The `-mobile` encoder fixes the batch at 1 and constant-folds a downsample
 * pad: it concatenates one extra frame (17) and then reshapes to
 * `{8, 2, 1, 128}`, which aborts. The non-mobile int8 encoder keeps the
 * 16-frame chunk. Hashes are of the four files inside the archive.
 */
internal object WakeModelSpec {
    const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/" +
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"

    val files: List<WakeModelFile> = listOf(
        WakeModelFile(
            name = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            bytes = 4_807_159,
            sha256 = "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678",
        ),
        WakeModelFile(
            name = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            bytes = 1_063_189,
            sha256 = "f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3",
        ),
        WakeModelFile(
            name = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            bytes = 163_380,
            sha256 = "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c",
        ),
        WakeModelFile(
            name = "tokens.txt",
            bytes = 5_006,
            sha256 = "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53",
        ),
    )

    fun file(name: String): WakeModelFile? = files.firstOrNull { it.name == name }
}

/**
 * Higher sensitivity lowers the sherpa trigger threshold (easier to fire).
 * 0 → 0.15, 0.5 → 0.10, 1 → 0.05. The old default (0.25) caught studio TTS
 * and missed a live voice.
 */
internal fun keywordsThreshold(sensitivity: Float): Float {
    val clamped = sensitivity.coerceIn(0f, 1f)
    return 0.15f - clamped * 0.10f
}

internal fun heyKaiKeywordLine(sensitivity: Float): String {
    val threshold = keywordsThreshold(sensitivity)
    // `@` must be a single whitespace-delimited word. A space after it is
    // another token, and sherpa looks that token up in tokens.txt.
    // One line per spelling. The same tokens cannot carry two thresholds.
    return HEY_KAI_SPELLINGS.joinToString("\n") { tokens ->
        "%s :%.2f #%.2f @%s".format(
            Locale.US,
            tokens,
            KEYWORD_BOOST,
            threshold,
            HEY_KAI_PHRASE,
        )
    }
}

internal fun pcmRms(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (sample in samples) sum += sample.toDouble() * sample.toDouble()
    return kotlin.math.sqrt(sum / samples.size).toFloat()
}

/**
 * Sherpa only returns a keyword once its acoustic score clears the threshold,
 * so a miss has no finer number than that bound. Null when the chunk is quiet
 * or a miss was logged a moment ago.
 */
internal fun keywordMissLog(
    nowMs: Long,
    lastLogMs: Long,
    rms: Float,
    threshold: Float,
): String? {
    if (rms < KEYWORD_MISS_RMS) return null
    if (nowMs - lastLogMs < KEYWORD_MISS_LOG_MS) return null
    return "Hey KAI miss score<%.2f rms=%.3f".format(Locale.US, threshold, rms)
}

internal fun keywordHitLog(threshold: Float, tokens: String): String =
    "Hey KAI hit score>=%.2f tokens=%s".format(Locale.US, threshold, tokens)

internal fun isHeyKaiSpot(result: String): Boolean {
    val text = result.trim()
    if (text.isEmpty()) return false
    if (HEY_KAI_SPELLINGS.any { text.contains(it) }) return true
    val norm = text.lowercase(Locale.US)
    return norm == "hey kai" ||
        norm == "heykai" ||
        norm.endsWith("@hey kai") ||
        norm.endsWith("@heykai")
}

/**
 * Microphone foreground services must not be started from [android.content.Intent.ACTION_BOOT_COMPLETED]
 * on Android 15+. The receiver only posts a notification; the service starts when that notification is tapped.
 */
internal fun bootHandoff(enabled: Boolean, sdkInt: Int): BootHandoff {
    if (!enabled) return BootHandoff.Ignore
    if (sdkInt >= ANDROID_15_API) return BootHandoff.PostTapNotification
    return BootHandoff.StartService
}

internal interface BootActions {
    fun startMicrophoneService()
    fun postTapNotification()
}

internal fun applyBootHandoff(enabled: Boolean, sdkInt: Int, actions: BootActions) {
    when (bootHandoff(enabled, sdkInt)) {
        BootHandoff.Ignore -> Unit
        BootHandoff.StartService -> actions.startMicrophoneService()
        BootHandoff.PostTapNotification -> actions.postTapNotification()
    }
}

/** Phone-call and in-communication audio modes. MODE_NORMAL is 0. */
internal fun isCallAudioMode(mode: Int): Boolean = mode in 1..6

internal fun pauseReason(
    userPaused: Boolean,
    audioMode: Int,
    micHeld: Boolean,
    commandBusy: Boolean,
): WakePauseReason? = when {
    userPaused -> WakePauseReason.User
    isCallAudioMode(audioMode) -> WakePauseReason.InCall
    // A command turn wins over "mic held" so standby does not reopen the recorder
    // while speech recognition or TTS is using it.
    commandBusy -> WakePauseReason.Command
    micHeld -> WakePauseReason.MicHeld
    else -> null
}

internal fun wakePresence(
    serviceRunning: Boolean,
    reason: WakePauseReason?,
): WakePresence = when {
    !serviceRunning -> WakePresence.Off
    reason == WakePauseReason.Command -> WakePresence.Listening
    reason != null -> WakePresence.Paused
    else -> WakePresence.Standby
}

internal fun archiveEntryName(path: String): String? {
    val trimmed = path.trim().replace('\\', '/')
    if (trimmed.isEmpty() || trimmed.endsWith("/")) return null
    if (trimmed.split('/').any { it == ".." }) return null
    val name = trimmed.substringAfterLast('/')
    if (name.isEmpty() || name.contains('\u0000')) return null
    return name
}

internal fun shouldKeepArchiveEntry(path: String): Boolean {
    val name = archiveEntryName(path) ?: return false
    return WakeModelSpec.file(name) != null
}

internal fun modelStatusLabel(status: WakeModelStatus): String = when (status) {
    WakeModelStatus.Missing -> "Hey KAI model is not on this phone yet."
    is WakeModelStatus.Downloading -> {
        val pct = if (status.totalBytes > 0) {
            ((100 * status.receivedBytes) / status.totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
        "Downloading the Hey KAI model… $pct%. Audio is not uploaded."
    }
    WakeModelStatus.Ready -> "Hey KAI model is on this phone. Audio stays on the phone."
    is WakeModelStatus.Failed -> "Could not download the Hey KAI model. ${status.message}"
}

/** Release the wake mic, then chime, then open the command screen. Order is the contract. */
internal fun runKeywordHandoff(
    releaseMic: () -> Unit,
    chime: () -> Unit,
    openVoice: () -> Unit,
) {
    releaseMic()
    chime()
    openVoice()
}
