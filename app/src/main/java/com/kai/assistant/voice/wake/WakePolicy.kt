package com.kai.assistant.voice.wake

import java.util.Locale

/** Android 15 (API 35) blocks microphone foreground services started from BOOT_COMPLETED. */
internal const val ANDROID_15_API = 35

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

internal const val KEYWORD_BOOST = 1.5f

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
 * Official k2-fsa mobile bundle. Int8 encoder and joiner, fp32 decoder (no int8
 * decoder is published). Hashes are of those four files inside the archive.
 */
internal object WakeModelSpec {
    const val ARCHIVE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/" +
            "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2"

    val files: List<WakeModelFile> = listOf(
        WakeModelFile(
            name = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            bytes = 4_000_908,
            sha256 = "7d5a25fbc14b423074fe0c58e1be388ccf3f1a815c649980232a0c47f81875e4",
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
 * 0 → 0.45, 0.5 → 0.25 (sherpa's usual default), 1 → 0.05.
 */
internal fun keywordsThreshold(sensitivity: Float): Float {
    val clamped = sensitivity.coerceIn(0f, 1f)
    return 0.45f - clamped * 0.40f
}

internal fun heyKaiKeywordLine(sensitivity: Float): String {
    val threshold = keywordsThreshold(sensitivity)
    // `@` must be a single whitespace-delimited word. A space after it is
    // another token, and sherpa looks that token up in tokens.txt.
    return "%s :%.2f #%.2f @%s".format(
        Locale.US,
        HEY_KAI_TOKENS,
        KEYWORD_BOOST,
        threshold,
        HEY_KAI_PHRASE,
    )
}

internal fun isHeyKaiSpot(result: String): Boolean {
    val text = result.trim()
    if (text.isEmpty()) return false
    if (text.contains(HEY_KAI_TOKENS)) return true
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
