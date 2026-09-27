package com.example

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer

class StreamInterceptor(private val context: Context) {

    private val tag = "StreamInterceptor"

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        val localPrefix = "https://appassets.androidplatform.net/local-audio/"
        val isLocalAudio = url.startsWith(localPrefix)
        val isAssetAudio = url.contains("/assets/vault/audio/") || (url.startsWith("https://appassets.androidplatform.net/assets/") && (url.endsWith(".wav") || url.endsWith(".mp3") || url.endsWith(".flac") || url.endsWith(".ogg") || url.endsWith(".m4a")))

        if (!isLocalAudio && !isAssetAudio) {
            return null
        }

        val decodedPath = if (isLocalAudio) {
            Uri.decode(url.substring(localPrefix.length))
        } else {
            val assetPath = url.substringAfter("/assets/")
            // Check if extracted to filesDir/phonks/ first
            val fName = File(assetPath).name
            val extracted = File(File(context.filesDir, "phonks"), fName)
            if (extracted.exists()) {
                extracted.absolutePath
            } else {
                "asset://$assetPath"
            }
        }

        try {
            var pfd: ParcelFileDescriptor? = null
            var fileLength = -1L
            val uri = Uri.parse(decodedPath)

            if (decodedPath.startsWith("content://")) {
                try {
                    pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    fileLength = pfd?.statSize ?: -1L
                } catch (_: Exception) {}

                if (fileLength <= 0) {
                    try {
                        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (sizeIdx != -1) {
                                    fileLength = cursor.getLong(sizeIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            } else if (decodedPath.startsWith("asset://")) {
                val relAsset = decodedPath.removePrefix("asset://")
                try {
                    val afd = context.assets.openFd(relAsset)
                    pfd = afd.parcelFileDescriptor
                    fileLength = afd.length
                } catch (_: Exception) {
                    // In case openFd throws (e.g. compressed asset)
                    val input = context.assets.open(relAsset)
                    val bytes = input.readBytes()
                    val mimeType = resolveMimeType(relAsset, uri, null)
                    val responseHeaders = mutableMapOf(
                        "Accept-Ranges" to "bytes",
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "no-cache",
                        "Content-Type" to mimeType,
                        "Content-Length" to bytes.size.toString()
                    )
                    return WebResourceResponse(
                        mimeType,
                        "UTF-8",
                        200,
                        "OK",
                        responseHeaders,
                        java.io.ByteArrayInputStream(bytes)
                    )
                }
            } else {
                val cleanPath = if (decodedPath.startsWith("file://")) Uri.parse(decodedPath).path ?: "" else decodedPath
                var file = File(cleanPath)
                if (!file.exists()) {
                    val fallbackPhonk = File(File(context.filesDir, "phonks"), file.name)
                    if (fallbackPhonk.exists()) file = fallbackPhonk
                }
                try {
                    pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    fileLength = pfd?.statSize ?: file.length()
                } catch (_: Exception) {
                    try {
                        val proj = arrayOf(android.provider.MediaStore.Audio.Media._ID)
                        val sel = "${android.provider.MediaStore.Audio.Media.DATA} = ?"
                        context.contentResolver.query(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            proj,
                            sel,
                            arrayOf(cleanPath),
                            null
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val id = cursor.getLong(0)
                                val cUri = android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                                pfd = context.contentResolver.openFileDescriptor(cUri, "r")
                                fileLength = pfd?.statSize ?: -1L
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            if (pfd == null) {
                return null
            }

            val mimeType = resolveMimeType(decodedPath, uri, pfd)

            val rangeHeader = request.requestHeaders["Range"] ?: request.requestHeaders["range"]
            val responseHeaders = mutableMapOf(
                "Accept-Ranges" to "bytes",
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache",
                "Content-Type" to mimeType
            )

            if (rangeHeader != null && rangeHeader.startsWith("bytes=") && fileLength > 0) {
                val rangeValue = rangeHeader.substring(6)
                val parts = rangeValue.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toLongOrNull() ?: (fileLength - 1) else (fileLength - 1)
                val contentLength = end - start + 1

                val fis = ParcelFileDescriptor.AutoCloseInputStream(pfd)
                fis.channel.position(start)
                val boundedStream = java.io.BufferedInputStream(BoundedInputStream(fis, contentLength), 65536)

                responseHeaders["Content-Range"] = "bytes $start-$end/$fileLength"
                responseHeaders["Content-Length"] = contentLength.toString()

                return WebResourceResponse(
                    mimeType,
                    "UTF-8",
                    206,
                    "Partial Content",
                    responseHeaders,
                    boundedStream
                )
            } else {
                if (fileLength > 0) {
                    responseHeaders["Content-Length"] = fileLength.toString()
                }
                val fis = java.io.BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd), 65536)
                return WebResourceResponse(
                    mimeType,
                    "UTF-8",
                    200,
                    "OK",
                    responseHeaders,
                    fis
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "Stream error: ${e.message}")
            return null
        }
    }

    private fun resolveMimeType(path: String, uri: Uri, pfd: ParcelFileDescriptor?): String {
        var name = path
        if (path.startsWith("content://")) {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx != -1) {
                            name = c.getString(idx) ?: path
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val lowerName = name.lowercase()
        val byExt = when {
            lowerName.endsWith(".wav") -> "audio/wav"
            lowerName.endsWith(".flac") -> "audio/flac"
            lowerName.endsWith(".aiff") || lowerName.endsWith(".aif") || lowerName.endsWith(".aifc") -> "audio/aiff"
            lowerName.endsWith(".alac") -> "audio/alac"
            lowerName.endsWith(".ape") -> "audio/x-ape"
            lowerName.endsWith(".wv") -> "audio/x-wavpack"
            lowerName.endsWith(".m4a") || lowerName.endsWith(".mp4") || lowerName.endsWith(".aac") -> "audio/mp4"
            lowerName.endsWith(".ogg") || lowerName.endsWith(".oga") -> "audio/ogg"
            lowerName.endsWith(".opus") -> "audio/opus"
            lowerName.endsWith(".mp3") -> "audio/mpeg"
            lowerName.endsWith(".wma") -> "audio/x-ms-wma"
            else -> null
        }
        if (byExt != null) return byExt

        if (path.startsWith("content://")) {
            val type = try { context.contentResolver.getType(uri) } catch (_: Exception) { null }
            if (!type.isNullOrBlank() && type != "application/octet-stream") {
                return type
            }
        }

        // Magic bytes detection for uncompressed & lossless streams
        if (pfd != null) {
            try {
                val fis = FileInputStream(pfd.fileDescriptor)
                val header = ByteArray(12)
                val read = fis.channel.read(ByteBuffer.wrap(header), 0L)
                if (read >= 4) {
                    // FLAC
                    if (header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() && header[2] == 0x61.toByte() && header[3] == 0x43.toByte()) {
                        return "audio/flac"
                    }
                    // RIFF WAVE (Uncompressed PCM)
                    if (header[0] == 0x52.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte() && header[3] == 0x46.toByte()) {
                        if (read >= 12 && header[8] == 0x57.toByte() && header[9] == 0x41.toByte() && header[10] == 0x56.toByte() && header[11] == 0x45.toByte()) {
                            return "audio/wav"
                        }
                    }
                    // FORM AIFF (Uncompressed AIFF)
                    if (header[0] == 0x46.toByte() && header[1] == 0x4F.toByte() && header[2] == 0x52.toByte() && header[3] == 0x4D.toByte()) {
                        return "audio/aiff"
                    }
                    // Ogg
                    if (header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() && header[2] == 0x67.toByte() && header[3] == 0x53.toByte()) {
                        return "audio/ogg"
                    }
                    // MP4 / M4A / ALAC
                    if (read >= 8 && header[4] == 0x66.toByte() && header[5] == 0x74.toByte() && header[6] == 0x79.toByte() && header[7] == 0x70.toByte()) {
                        return "audio/mp4"
                    }
                }
            } catch (_: Exception) {}
        }

        return "audio/mpeg"
    }

    private class BoundedInputStream(
        private val delegate: InputStream,
        private var remaining: Long
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = delegate.read()
            if (b != -1) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = if (len > remaining) remaining.toInt() else len
            val readCount = delegate.read(b, off, toRead)
            if (readCount != -1) remaining -= readCount
            return readCount
        }

        override fun close() {
            delegate.close()
        }
    }
}
