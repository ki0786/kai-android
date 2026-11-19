package com.kai.assistant.ondevice

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads and runs the on-device chat model. Chat text is not logged.
 * Provider API keys are never read here.
 */
class OnDeviceChat(context: Context) {
    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, "models")
    private val modelFile = File(modelsDir, OnDeviceModel.FILE_NAME)
    private val partialFile = File(modelsDir, "${OnDeviceModel.FILE_NAME}.partial")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val engineLock = Mutex()
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kai-on-device")
    }.asCoroutineDispatcher()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _status = MutableStateFlow(initialStatus())
    val status: StateFlow<OnDeviceStatus> = _status.asStateFlow()

    @Volatile
    private var engine: LlmInference? = null

    @Volatile
    private var running = true

    @Volatile
    private var downloadCall: Call? = null

    private var downloadJob: Job? = null
    private var loadJob: Job? = null

    fun isReady(): Boolean =
        running && engine != null && _status.value.phase == OnDevicePhase.Ready

    /** Load an already downloaded model. Does not start a download. */
    fun warmIfDownloaded() {
        running = true
        if (!modelFileReady()) {
            if (_status.value.phase == OnDevicePhase.Downloading) return
            _status.value = OnDeviceStatus(
                phase = OnDevicePhase.NotDownloaded,
                detail = "Will download on the first question.",
            )
            return
        }
        startLoad()
    }

    /**
     * Make the model available for a question: start a download if the file is missing,
     * or load it if it is already on disk. Returns immediately.
     */
    fun ensureAvailable() {
        running = true
        when {
            isReady() -> Unit
            modelFileReady() -> startLoad()
            _status.value.phase == OnDevicePhase.Downloading -> Unit
            _status.value.phase == OnDevicePhase.Failed && !modelFileReady() -> Unit
            _status.value.phase == OnDevicePhase.Failed -> Unit
            else -> requestDownload()
        }
    }

    fun requestDownload() {
        running = true
        if (downloadJob?.isActive == true) return
        if (modelFileReady()) {
            startLoad(force = true)
            return
        }
        downloadJob = scope.launch { downloadModel() }
    }

    fun retry() {
        running = true
        if (modelFileReady()) startLoad(force = true) else requestDownload()
    }

    /** Stop work and free the model. Safe to call when the setting is turned off. */
    fun release() {
        running = false
        downloadCall?.cancel()
        downloadJob?.cancel()
        loadJob?.cancel()
        scope.launch {
            engineLock.withLock { closeEngine() }
            if (!running) publishIdle()
        }
    }

    /**
     * Run one prompt. Null means this turn should use the backend:
     * the model is missing, still busy, timed out, or threw.
     */
    suspend fun generate(prompt: String): String? {
        if (prompt.isBlank()) return null
        if (!engineLock.tryLock()) return null
        val current = engine
        if (current == null || !running) {
            engineLock.unlock()
            return null
        }
        val deferred = scope.async(inferenceDispatcher) {
            var session: LlmInferenceSession? = null
            var broken: Throwable? = null
            try {
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTemperature(0.8f)
                    .setTopK(40)
                    .setTopP(0.95f)
                    .build()
                val created = LlmInferenceSession.createFromOptions(current, sessionOptions)
                session = created
                created.addQueryChunk(prompt)
                created.generateResponse().trim().ifBlank { null }
            } catch (error: Throwable) {
                broken = error
                Log.e(TAG, "On-device generate failed: ${error.javaClass.simpleName}")
                null
            } finally {
                runCatching { session?.close() }
                if (broken != null && running) {
                    closeEngine()
                    _status.value = OnDeviceStatus(
                        phase = OnDevicePhase.Failed,
                        detail = friendlyFailure(broken),
                    )
                }
                engineLock.unlock()
            }
        }
        return try {
            withTimeout(GENERATE_TIMEOUT_MS) { deferred.await() }
        } catch (timeout: TimeoutCancellationException) {
            null
        }
    }

    private fun initialStatus(): OnDeviceStatus {
        return if (modelFileReady()) {
            OnDeviceStatus(
                phase = OnDevicePhase.Downloaded,
                bytesRead = modelFile.length(),
                bytesTotal = modelFile.length(),
                detail = "Saved on this phone.",
            )
        } else {
            OnDeviceStatus(
                phase = OnDevicePhase.NotDownloaded,
                detail = "Will download on the first question.",
            )
        }
    }

    private fun modelFileReady(): Boolean =
        modelFile.isFile && modelFile.length() >= MIN_MODEL_BYTES

    private fun startLoad(force: Boolean = false) {
        if (!running || !modelFileReady()) return
        if (!force && (isReady() || loadJob?.isActive == true)) return
        loadJob?.cancel()
        loadJob = scope.launch {
            _status.value = OnDeviceStatus(
                phase = OnDevicePhase.Loading,
                bytesRead = modelFile.length(),
                bytesTotal = modelFile.length(),
                detail = "Loading ${OnDeviceModel.DISPLAY_NAME}…",
            )
            val loaded = engineLock.withLock {
                if (!running) return@withLock false
                closeEngine()
                openEngine()
            }
            if (!running) return@launch
            _status.value = if (loaded) {
                OnDeviceStatus(
                    phase = OnDevicePhase.Ready,
                    bytesRead = modelFile.length(),
                    bytesTotal = modelFile.length(),
                    detail = OnDeviceModel.DISPLAY_NAME,
                )
            } else {
                OnDeviceStatus(
                    phase = OnDevicePhase.Failed,
                    detail = lastLoadError,
                )
            }
        }
    }

    private var lastLoadError: String = "Could not load the on-device model."

    private fun openEngine(): Boolean {
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(512)
                .setMaxTopK(40)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            engine = LlmInference.createFromOptions(appContext, options)
            true
        } catch (error: Throwable) {
            lastLoadError = friendlyFailure(error)
            Log.e(TAG, "On-device load failed: ${error.javaClass.simpleName}")
            closeEngine()
            false
        }
    }

    private fun closeEngine() {
        val current = engine
        engine = null
        if (current != null) {
            runCatching { current.close() }
        }
    }

    private suspend fun downloadModel() {
        if (!running) return
        modelsDir.mkdirs()
        if (partialFile.exists()) partialFile.delete()

        val usable = modelsDir.usableSpace
        if (usable in 1 until (OnDeviceModel.SIZE_BYTES + FREE_HEADROOM_BYTES)) {
            publishFailed("Not enough free space. Need about ${OnDeviceModel.SIZE_LABEL}.")
            return
        }

        _status.value = OnDeviceStatus(
            phase = OnDevicePhase.Downloading,
            bytesRead = 0,
            bytesTotal = OnDeviceModel.SIZE_BYTES,
            detail = "Downloading ${OnDeviceModel.DISPLAY_NAME} (0 / ${OnDeviceModel.SIZE_LABEL})",
        )

        val request = Request.Builder()
            .url(OnDeviceModel.DOWNLOAD_URL)
            .header("User-Agent", "KAI-Android")
            .get()
            .build()
        val call = http.newCall(request)
        downloadCall = call
        try {
            call.execute().use { response ->
                if (!running || !coroutineContext.isActive) return
                if (!response.isSuccessful) {
                    publishFailed("Download failed (HTTP ${response.code}).")
                    return
                }
                val body = response.body
                if (body == null) {
                    publishFailed("Download failed (empty response).")
                    return
                }
                val declared = body.contentLength()
                val total = if (declared > 0) declared else OnDeviceModel.SIZE_BYTES
                body.byteStream().use { input ->
                    partialFile.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var readTotal = 0L
                        var lastEmit = 0L
                        while (true) {
                            if (!running || !coroutineContext.isActive) return
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            readTotal += count
                            val now = System.currentTimeMillis()
                            if (now - lastEmit > 300) {
                                lastEmit = now
                                _status.value = OnDeviceStatus(
                                    phase = OnDevicePhase.Downloading,
                                    bytesRead = readTotal,
                                    bytesTotal = total,
                                    detail = "Downloading ${OnDeviceModel.DISPLAY_NAME} " +
                                        "(${megabytes(readTotal)} / ${megabytes(total)})",
                                )
                            }
                        }
                        output.flush()
                        if (declared > 0 && readTotal != declared) {
                            partialFile.delete()
                            publishFailed("Download incomplete. Try again.")
                            return
                        }
                        if (readTotal < MIN_MODEL_BYTES) {
                            partialFile.delete()
                            publishFailed("Download incomplete. Try again.")
                            return
                        }
                        if (modelFile.exists()) modelFile.delete()
                        if (!partialFile.renameTo(modelFile)) {
                            partialFile.copyTo(modelFile, overwrite = true)
                            partialFile.delete()
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (!running || error is kotlinx.coroutines.CancellationException) return
            if (error is IOException && (call.isCanceled() || !running)) return
            partialFile.delete()
            publishFailed("Couldn't download the model. Check your connection and try again.")
            Log.e(TAG, "On-device download failed: ${error.javaClass.simpleName}")
            return
        } finally {
            if (downloadCall === call) downloadCall = null
        }

        if (!running || !coroutineContext.isActive) return
        startLoad(force = true)
    }

    private fun publishFailed(message: String) {
        if (!running) return
        _status.value = OnDeviceStatus(phase = OnDevicePhase.Failed, detail = message)
    }

    private fun publishIdle() {
        _status.value = if (modelFileReady()) {
            OnDeviceStatus(
                phase = OnDevicePhase.Downloaded,
                bytesRead = modelFile.length(),
                bytesTotal = modelFile.length(),
                detail = "On-device answers are off.",
            )
        } else {
            OnDeviceStatus(
                phase = OnDevicePhase.NotDownloaded,
                detail = "On-device answers are off.",
            )
        }
    }

    private fun friendlyFailure(error: Throwable): String {
        val raw = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val cleaned = raw
            .replace(modelFile.absolutePath, "model")
            .take(160)
        return cleaned.ifBlank { "Could not run the on-device model." }
    }

    private fun megabytes(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return "${"%.0f".format(mb)} MB"
    }

    companion object {
        private const val TAG = "OnDeviceChat"
        private const val MIN_MODEL_BYTES = 100L * 1000L * 1000L
        private const val FREE_HEADROOM_BYTES = 64L * 1000L * 1000L
        private const val GENERATE_TIMEOUT_MS = 90_000L
    }
}
