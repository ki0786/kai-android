package com.kai.assistant.voice.wake

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kai.assistant.KaiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * Always-on "Hey KAI" listener. The microphone is held only while spotting the wake
 * word. A hit releases it and listens for the command, with no chime, without opening KAI.
 */
class WakeWordForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mic = WakeMicCapture()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val modelDir by lazy { File(filesDir, "wake/kws") }

    @Volatile
    private var loop = false

    @Volatile
    private var userPaused = false

    @Volatile
    private var sensitivity = 0.5f

    @Volatile
    private var spotter: HeyKaiSpotter? = null

    private var audioThread: Thread? = null
    private var settingsJob: Job? = null
    private var startedWork = false
    private var foreground = false
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WakeTurnGate.setReleaseListener { busy ->
            if (busy) mic.release()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFeature(disable = true)
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> userPaused = true
            ACTION_RESUME -> userPaused = false
            ACTION_RETRY -> userPaused = false
            else -> Unit
        }
        if (!ensureForeground()) return START_NOT_STICKY
        running = true
        WakeNotifications.cancelResume(this)
        if (!startedWork) {
            startedWork = true
            sensitivity = WakePrefs.sensitivity(this)
            begin()
        } else if (intent?.action == ACTION_RETRY) {
            scope.launch { downloadAndListen() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        WakeCommandSession.stop()
        loop = false
        running = false
        WakeTurnGate.setReleaseListener(null)
        mic.release()
        audioThread?.join(2_000)
        spotter?.release()
        spotter = null
        settingsJob?.cancel()
        scope.cancel()
        WakeRuntime.presence(WakePresence.Off)
        super.onDestroy()
    }

    private fun begin() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stopFeature(disable = false)
            return
        }
        settingsJob = scope.launch {
            (application as KaiApplication).settingsRepository.settings.collect { settings ->
                sensitivity = settings.heyKaiSensitivity
                spotter?.setSensitivity(sensitivity)
            }
        }
        scope.launch { downloadAndListen() }
    }

    private fun downloadAndListen() {
        val ready = WakeModelDownloader(
            dir = modelDir,
            cacheDir = cacheDir,
            onStatus = { status ->
                WakeRuntime.model(status)
                when (status) {
                    is WakeModelStatus.Downloading,
                    is WakeModelStatus.Failed,
                    -> show(modelStatusLabel(status), stopOnly())
                    else -> Unit
                }
            },
        ).ensureReady()
        if (!ready || !running) return
        startAudioThread()
    }

    private fun startAudioThread() {
        if (!running || audioThread?.isAlive == true) return
        loop = true
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val engine = SherpaHeyKaiSpotter()
            val loaded = runCatching { engine.load(modelDir, sensitivity) }
            if (loaded.isFailure) {
                val message = loaded.exceptionOrNull()?.message ?: "Could not start the wake model."
                WakeRuntime.model(WakeModelStatus.Failed(message))
                show(modelStatusLabel(WakeModelStatus.Failed(message)), retryAndStop())
                engine.release()
                return@Thread
            }
            spotter = engine
            engine.setSensitivity(sensitivity)
            listen(engine)
            engine.release()
            if (spotter === engine) spotter = null
        }, "hey-kai")
        audioThread = thread
        thread.start()
    }

    private fun listen(engine: HeyKaiSpotter) {
        val buffer = ShortArray(1600)
        var micHeld = false
        while (loop) {
            WakeTurnGate.expire()
            val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            val reason = pauseReason(
                userPaused = userPaused,
                audioMode = mode,
                micHeld = micHeld,
                commandBusy = WakeTurnGate.holdsMic(),
            )
            publish(reason)
            if (reason != null) {
                mic.release()
                if (reason == WakePauseReason.MicHeld && mic.open()) {
                    micHeld = false
                    mic.release()
                }
                sleepQuiet(250)
                continue
            }
            if (!mic.isOpen() && !mic.open()) {
                micHeld = true
                sleepQuiet(400)
                continue
            }
            val read = mic.read(buffer)
            if (read < 0) {
                mic.release()
                micHeld = true
                sleepQuiet(400)
                continue
            }
            if (read == 0) continue
            micHeld = false
            val samples = FloatArray(read) { index -> buffer[index] / 32768.0f }
            val heard = runCatching { engine.accept(samples) }.getOrDefault(false)
            if (heard && loop) {
                runKeywordHandoff(
                    releaseMic = { mic.release() },
                    startListening = { WakeCommandSession.start(this) },
                )
            }
        }
        mic.release()
    }

    private fun publish(reason: WakePauseReason?) {
        val modelReady = WakeRuntime.model.value is WakeModelStatus.Ready
        val presence = if (!modelReady && reason == null) {
            WakePresence.Off
        } else {
            wakePresence(serviceRunning = running, reason = reason)
        }
        WakeRuntime.presence(presence)
        val text = when (reason) {
            WakePauseReason.User -> "Hey KAI is paused"
            WakePauseReason.InCall -> "Paused (in call)"
            WakePauseReason.MicHeld -> "Paused (microphone busy)"
            WakePauseReason.Command -> "Listening for your command"
            null -> LISTENING_NOTIFICATION
        }
        val actions = when (reason) {
            WakePauseReason.User -> resumeAndStop()
            WakePauseReason.InCall, WakePauseReason.MicHeld, WakePauseReason.Command -> stopOnly()
            null -> pauseAndStop()
        }
        show(text, actions)
    }

    private fun show(text: String, actions: List<NotificationCompat.Action>) {
        synchronized(this) {
            if (text == lastText && foreground) return
            lastText = text
            val notification = WakeNotifications.listening(this, text, actions)
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (foreground) {
                runCatching { manager.notify(WakeNotifications.LISTENING_ID, notification) }
            }
        }
    }

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        val notification = WakeNotifications.listening(
            this,
            "Preparing Hey KAI…",
            stopOnly(),
        )
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    WakeNotifications.LISTENING_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } else {
                startForeground(WakeNotifications.LISTENING_ID, notification)
            }
            foreground = true
            true
        } catch (security: SecurityException) {
            running = false
            stopSelf()
            false
        }
    }

    private fun stopFeature(disable: Boolean) {
        loop = false
        running = false
        userPaused = false
        if (disable) {
            WakePrefs.setEnabled(this, false)
            val app = application as? KaiApplication
            if (app != null) {
                settingsScope.launch {
                    runCatching { app.settingsRepository.setBackgroundHeyKai(false) }
                }
            }
        }
        WakeRuntime.presence(WakePresence.Off)
        if (foreground) {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            foreground = false
        }
        stopSelf()
    }

    private fun action(label: String, icon: Int, action: String, request: Int): NotificationCompat.Action {
        val pending = android.app.PendingIntent.getService(
            this,
            request,
            Intent(this, WakeWordForegroundService::class.java).setAction(action),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action(icon, label, pending)
    }

    private fun pauseAndStop() = listOf(
        action("Pause", android.R.drawable.ic_media_pause, ACTION_PAUSE, 1),
        action("Stop", android.R.drawable.ic_delete, ACTION_STOP, 2),
    )

    private fun resumeAndStop() = listOf(
        action("Resume", android.R.drawable.ic_media_play, ACTION_RESUME, 3),
        action("Stop", android.R.drawable.ic_delete, ACTION_STOP, 2),
    )

    private fun stopOnly() = listOf(
        action("Stop", android.R.drawable.ic_delete, ACTION_STOP, 2),
    )

    private fun retryAndStop() = listOf(
        action("Retry", android.R.drawable.ic_menu_rotate, ACTION_RETRY, 4),
        action("Stop", android.R.drawable.ic_delete, ACTION_STOP, 2),
    )

    private fun sleepQuiet(millis: Long) {
        runCatching { Thread.sleep(millis) }
    }

    companion object {
        const val ACTION_START = "com.kai.assistant.wake.START"
        const val ACTION_PAUSE = "com.kai.assistant.wake.PAUSE"
        const val ACTION_RESUME = "com.kai.assistant.wake.RESUME"
        const val ACTION_STOP = "com.kai.assistant.wake.STOP"
        const val ACTION_RETRY = "com.kai.assistant.wake.RETRY"

        @Volatile
        var running: Boolean = false
    }
}
