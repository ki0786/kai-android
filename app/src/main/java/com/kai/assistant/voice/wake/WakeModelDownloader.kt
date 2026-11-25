package com.kai.assistant.voice.wake

import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun modelFilesReady(dir: File): Boolean =
    WakeModelSpec.files.all { spec ->
        val file = File(dir, spec.name)
        file.isFile && file.length() == spec.bytes && sha256Hex(file) == spec.sha256
    }

/**
 * Downloads the official GigaSpeech KWS archive once and keeps the four files the
 * spotter needs. The archive is a model, not audio from the microphone.
 */
internal class WakeModelDownloader(
    private val dir: File,
    private val cacheDir: File,
    private val client: OkHttpClient = defaultClient(),
    private val onStatus: (WakeModelStatus) -> Unit,
) {
    fun ensureReady(): Boolean {
        if (modelFilesReady(dir)) {
            onStatus(WakeModelStatus.Ready)
            return true
        }
        onStatus(WakeModelStatus.Downloading(0, -1))
        val archive = File(cacheDir, "hey-kai-kws.tar.bz2")
        return try {
            download(archive)
            extract(archive)
            archive.delete()
            if (!modelFilesReady(dir)) {
                dir.listFiles()?.forEach { it.delete() }
                onStatus(WakeModelStatus.Failed("The downloaded model did not match the expected files."))
                false
            } else {
                onStatus(WakeModelStatus.Ready)
                true
            }
        } catch (error: Exception) {
            archive.delete()
            onStatus(WakeModelStatus.Failed(error.message ?: "Download failed."))
            false
        }
    }

    private fun download(archive: File) {
        archive.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(WakeModelSpec.ARCHIVE_URL)
            .header("User-Agent", "KAI-Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Model download failed (${response.code}).")
            }
            val body = response.body ?: error("Model download was empty.")
            val total = body.contentLength()
            body.byteStream().use { input ->
                archive.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var received = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        received += read
                        val pct = if (total > 0) ((100 * received) / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onStatus(WakeModelStatus.Downloading(received, total))
                        }
                    }
                }
            }
        }
    }

    private fun extract(archive: File) {
        dir.mkdirs()
        BZip2CompressorInputStream(FileInputStream(archive)).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    val name = archiveEntryName(entry.name) ?: continue
                    if (!shouldKeepArchiveEntry(entry.name) || entry.isDirectory) continue
                    val spec = WakeModelSpec.file(name) ?: continue
                    if (entry.size > spec.bytes + 1024 && entry.size > 0) continue
                    val out = File(dir, name)
                    out.outputStream().use { dest ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = tar.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > spec.bytes + 1024) error("Model entry $name is too large.")
                            dest.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
