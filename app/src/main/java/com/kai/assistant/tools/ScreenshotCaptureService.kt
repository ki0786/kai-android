package com.kai.assistant.tools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.kai.assistant.data.ChatMessage
import com.kai.assistant.data.MessageRole
import com.kai.assistant.data.SessionMessageStore
import java.io.File
import java.io.FileOutputStream

/**
 * Captures one screenshot after the system screen-capture grant.
 * The grant is kept for this process so later screenshots do not ask again.
 * Pictures stay on the device.
 */
class ScreenshotCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var wantFrame = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val worker = HandlerThread("kai-screenshot")
        worker.start()
        thread = worker
        handler = Handler(worker.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_GRANT -> grant(intent)
            ACTION_CAPTURE -> requestFrame()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ready = false
        releaseCapture()
        thread?.quitSafely()
        super.onDestroy()
    }

    private fun grant(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            note("I couldn't take a screenshot.")
            stopSelf()
            return
        }
        if (projection == null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            val created = try {
                manager.getMediaProjection(resultCode, data)
            } catch (_: SecurityException) {
                null
            }
            if (created == null) {
                note("I couldn't take a screenshot.")
                stopSelf()
                return
            }
            created.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    ready = false
                    stopSelf()
                }
            }, handler)
            projection = created
            ready = true
        }
        requestFrame()
    }

    private fun openDisplay(projection: MediaProjection) {
        val (width, height) = screenSize()
        val dpi = resources.displayMetrics.densityDpi
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            if (!wantFrame) {
                image.close()
                return@setOnImageAvailableListener
            }
            wantFrame = false
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                buffer.rewind()
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                if (cropped != bitmap) bitmap.recycle()
                val where = savePng(cropped)
                cropped.recycle()
                note("Saved a screenshot to $where.")
            } catch (_: Exception) {
                note("I couldn't save the screenshot.")
            } finally {
                image.close()
            }
        }, handler)
        reader = imageReader
        display = projection.createVirtualDisplay(
            "kai-screenshot",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface,
            null,
            handler,
        )
    }

    private fun requestFrame() {
        val current = projection
        if (current == null) {
            ready = false
            note("I need screen capture permission again.")
            stopSelf()
            return
        }
        display?.release()
        reader?.close()
        display = null
        reader = null
        wantFrame = true
        openDisplay(current)
        handler?.postDelayed({
            if (wantFrame) {
                wantFrame = false
                note("I couldn't save the screenshot.")
            }
        }, 4_000)
    }

    private fun screenSize(): Pair<Int, Int> {
        val window = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = window.currentWindowMetrics.bounds
            bounds.width().coerceAtLeast(1) to bounds.height().coerceAtLeast(1)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            window.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels.coerceAtLeast(1) to metrics.heightPixels.coerceAtLeast(1)
        }
    }

    private fun savePng(bitmap: Bitmap): String {
        val name = "kai-${System.currentTimeMillis()}.png"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/KAI")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("no media uri")
            contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return "Pictures/KAI"
        }
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: filesDir
        FileOutputStream(File(dir, name)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return "this app's Pictures folder"
    }

    private fun note(text: String) {
        val main = Handler(mainLooper)
        main.post {
            SessionMessageStore.add(
                ChatMessage(role = MessageRole.Assistant, text = text, deviceOnly = true),
            )
        }
    }

    private fun startInForeground() {
        ensureShotChannel()
        val notification = NotificationCompat.Builder(this, SHOT_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Screen capture")
            .setContentText("KAI can save a screenshot on this phone.")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                SHOT_NOTIFICATION,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(SHOT_NOTIFICATION, notification)
        }
    }

    private fun ensureShotChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(SHOT_CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                SHOT_CHANNEL,
                "Screenshots",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun releaseCapture() {
        display?.release()
        reader?.close()
        projection?.stop()
        display = null
        reader = null
        projection = null
    }

    companion object {
        private const val ACTION_GRANT = "grant"
        private const val ACTION_CAPTURE = "capture"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val SHOT_CHANNEL = "kai_screenshots"
        private const val SHOT_NOTIFICATION = 42

        @Volatile
        var ready: Boolean = false
            private set

        fun grantAndCapture(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenshotCaptureService::class.java).apply {
                action = ACTION_GRANT
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun captureAgain(context: Context) {
            val intent = Intent(context, ScreenshotCaptureService::class.java).apply {
                action = ACTION_CAPTURE
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
