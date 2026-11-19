package com.kai.assistant.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads a photo the user picked and describes it on this phone.
 * Image bytes are not sent to the backend.
 */
class PhotoDescriber(private val context: Context) {
    suspend fun describe(uri: Uri, askedForBackend: Boolean): String {
        val bitmap = loadBitmap(uri)
            ?: return if (askedForBackend) {
                "I couldn't open that photo. I didn't upload it."
            } else {
                "I couldn't open that photo."
            }
        return try {
            val labels = runCatching { readLabels(bitmap) }.getOrDefault(emptyList())
            val text = runCatching { readText(bitmap) }.getOrDefault(emptyList())
            val subjects = labels.ifEmpty { colorLabels(sampleColors(bitmap)) }
            describePhoto(subjects, text, askedForBackend)
        } catch (_: Throwable) {
            describePhoto(colorLabels(sampleColors(bitmap)), emptyList(), askedForBackend)
        } finally {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private suspend fun readLabels(bitmap: Bitmap): List<String> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(PHOTO_LABEL_MINIMUM)
                .build(),
        )
        return try {
            val labels = labeler.process(image).awaitResult()
            acceptedPhotoLabels(labels.map { it.text to it.confidence })
        } finally {
            labeler.close()
        }
    }

    private suspend fun readText(bitmap: Bitmap): List<String> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val vision = recognizer.process(image).awaitResult()
            vision.textBlocks.flatMap { block -> block.lines.map { it.text } }
        } finally {
            recognizer.close()
        }
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= 28) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val width = info.size.width
                    val height = info.size.height
                    val largest = maxOf(width, height)
                    if (largest > MAX_EDGE) {
                        val scale = MAX_EDGE.toFloat() / largest
                        decoder.setTargetSize(
                            (width * scale).toInt().coerceAtLeast(1),
                            (height * scale).toInt().coerceAtLeast(1),
                        )
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                decodeSampled(uri)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun sampleColors(bitmap: Bitmap): List<Int> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val colors = ArrayList<Int>(64)
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        var y = (stepY / 2).coerceAtMost(bitmap.height - 1)
        while (y < bitmap.height) {
            var x = (stepX / 2).coerceAtMost(bitmap.width - 1)
            while (x < bitmap.width) {
                colors += bitmap.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return colors
    }

    private companion object {
        const val MAX_EDGE = 1024
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value ->
        if (cont.isActive) cont.resume(value)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
