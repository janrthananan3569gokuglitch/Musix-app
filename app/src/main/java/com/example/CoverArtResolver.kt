package com.example

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.util.Size
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Ultra-fast, zero-lag cover art resolver for XP Music Vault.
 *
 * Prioritizes:
 * 1. Fast in-memory LRU cache for decoded art bytes and base64 strings
 * 2. Negative cache (never re-query tracks known to lack art)
 * 3. Hardware-accelerated Android 10+ MediaStore thumbnail loader (256x256)
 * 4. Native MediaMetadataRetriever embedded picture extractor
 * 5. Adjacent album folder artwork (cover.jpg, folder.jpg)
 *
 * Never performs blocking CPU-intensive canvas generation or heavy loops.
 */
object CoverArtResolver {

    private const val TAG = "CoverArtResolver"

    // High performance byte cache (stores up to 100 recent cover artworks)
    private val artByteCache = LruCache<String, ByteArray>(100)

    // Base64 string cache for instant returns to AndroidBridge
    private val artBase64Cache = LruCache<String, String>(60)

    // Negative cache: paths/URIs verified to have NO album art.
    // Avoids redundant disk I/O, MediaStore queries, and retriever calls.
    private val noArtCache: MutableSet<String> = Collections.synchronizedSet(object : LinkedHashSet<String>() {
        override fun add(element: String): Boolean {
            if (size > 600) {
                val it = iterator()
                if (it.hasNext()) {
                    it.next()
                    it.remove()
                }
            }
            return super.add(element)
        }
    })

    fun resolveCleanPath(pathOrUri: String): String {
        var clean = pathOrUri.trim()
        val localPrefix = "https://appassets.androidplatform.net/local-audio/"
        if (clean.startsWith(localPrefix)) {
            clean = Uri.decode(clean.substring(localPrefix.length))
        }
        val artPrefix = "https://appassets.androidplatform.net/album-art/"
        if (clean.startsWith(artPrefix)) {
            clean = Uri.decode(clean.substring(artPrefix.length))
        }
        if (clean.startsWith("file://")) {
            clean = Uri.parse(clean).path ?: clean.removePrefix("file://")
        }
        return clean
    }

    fun getCustomArtFile(context: Context, pathOrUri: String): File {
        val clean = resolveCleanPath(pathOrUri).lowercase()
        val dir = File(context.filesDir, "custom_artwork")
        if (!dir.exists()) dir.mkdirs()
        val hash = clean.hashCode().toString().replace("-", "n") + "_" + clean.takeLast(16).replace(Regex("[^a-zA-Z0-9]"), "_")
        return File(dir, "$hash.jpg")
    }

    fun getNoArtMarkerFile(context: Context, pathOrUri: String): File {
        val clean = resolveCleanPath(pathOrUri).lowercase()
        val dir = File(context.filesDir, "custom_artwork")
        if (!dir.exists()) dir.mkdirs()
        val hash = clean.hashCode().toString().replace("-", "n") + "_" + clean.takeLast(16).replace(Regex("[^a-zA-Z0-9]"), "_")
        return File(dir, "$hash.noart")
    }

    fun getArtBytes(context: Context, pathOrUri: String): ByteArray? {
        if (pathOrUri.isBlank()) return null
        val cleanPath = resolveCleanPath(pathOrUri)

        // 0. Check Persistent User-Removed Marker
        val noArtMarker = getNoArtMarkerFile(context, cleanPath)
        if (noArtMarker.exists()) {
            noArtCache.add(cleanPath)
            noArtCache.add(pathOrUri)
            return null
        }

        // 1. Check Persistent Custom Artwork on Disk
        val customArt = getCustomArtFile(context, cleanPath)
        if (customArt.exists() && customArt.length() > 0) {
            try {
                val bytes = customArt.readBytes()
                if (bytes.isNotEmpty()) {
                    artByteCache.put(cleanPath, bytes)
                    artByteCache.put(pathOrUri, bytes)
                    return bytes
                }
            } catch (_: Exception) {}
        }

        // 2. Check Negative Cache
        if (noArtCache.contains(cleanPath) || noArtCache.contains(pathOrUri)) {
            return null
        }

        // 3. Check Memory Cache
        artByteCache.get(cleanPath)?.let {
            if (it.isNotEmpty()) return it
        }
        artByteCache.get(pathOrUri)?.let {
            if (it.isNotEmpty()) return it
        }

        // 4. Extract Real Cover Art
        val extracted = extractRealArtBytes(context, cleanPath)
        if (extracted != null && extracted.isNotEmpty()) {
            artByteCache.put(cleanPath, extracted)
            artByteCache.put(pathOrUri, extracted)
            return extracted
        }

        // Mark as having no album art so we never query again
        noArtCache.add(cleanPath)
        noArtCache.add(pathOrUri)
        return null
    }

    fun updateCachedArt(context: Context, pathOrUri: String, artBytes: ByteArray?, isCoverRemoved: Boolean = false) {
        val clean = resolveCleanPath(pathOrUri)
        val customFile = getCustomArtFile(context, clean)
        val noArtMarker = getNoArtMarkerFile(context, clean)

        if (isCoverRemoved) {
            try { if (customFile.exists()) customFile.delete() } catch (_: Exception) {}
            try { noArtMarker.createNewFile() } catch (_: Exception) {}
            artByteCache.remove(clean)
            artByteCache.remove(pathOrUri)
            artBase64Cache.remove(clean)
            artBase64Cache.remove(pathOrUri)
            noArtCache.add(clean)
            noArtCache.add(pathOrUri)
        } else if (artBytes != null && artBytes.isNotEmpty()) {
            try { if (noArtMarker.exists()) noArtMarker.delete() } catch (_: Exception) {}
            try { customFile.writeBytes(artBytes) } catch (_: Exception) {}
            artByteCache.put(clean, artBytes)
            artByteCache.put(pathOrUri, artBytes)
            val mime = if (artBytes.size > 8 && artBytes[0] == 0x89.toByte() && artBytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
            val b64 = "data:$mime;base64," + Base64.encodeToString(artBytes, Base64.NO_WRAP)
            artBase64Cache.put(clean, b64)
            artBase64Cache.put(pathOrUri, b64)
            noArtCache.remove(clean)
            noArtCache.remove(pathOrUri)
        } else {
            artByteCache.remove(clean)
            artByteCache.remove(pathOrUri)
            artBase64Cache.remove(clean)
            artBase64Cache.remove(pathOrUri)
        }
    }

    fun updateCachedArt(pathOrUri: String, artBytes: ByteArray?) {
        val clean = resolveCleanPath(pathOrUri)
        if (artBytes != null && artBytes.isNotEmpty()) {
            artByteCache.put(clean, artBytes)
            artByteCache.put(pathOrUri, artBytes)
            val mime = if (artBytes.size > 8 && artBytes[0] == 0x89.toByte() && artBytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
            val b64 = "data:$mime;base64," + Base64.encodeToString(artBytes, Base64.NO_WRAP)
            artBase64Cache.put(clean, b64)
            artBase64Cache.put(pathOrUri, b64)
            noArtCache.remove(clean)
            noArtCache.remove(pathOrUri)
        } else {
            artByteCache.remove(clean)
            artByteCache.remove(pathOrUri)
            artBase64Cache.remove(clean)
            artBase64Cache.remove(pathOrUri)
            noArtCache.add(clean)
            noArtCache.add(pathOrUri)
        }
    }

    fun getArtBase64(context: Context, pathOrUri: String): String {
        if (pathOrUri.isBlank()) return ""
        val cleanPath = resolveCleanPath(pathOrUri)

        if (noArtCache.contains(cleanPath) || noArtCache.contains(pathOrUri)) {
            return ""
        }

        artBase64Cache.get(cleanPath)?.let {
            if (it.isNotBlank()) return it
        }
        artBase64Cache.get(pathOrUri)?.let {
            if (it.isNotBlank()) return it
        }

        val bytes = getArtBytes(context, cleanPath)
        if (bytes != null && bytes.isNotEmpty()) {
            val mime = if (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) "image/png" else "image/jpeg"
            val base64 = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            artBase64Cache.put(cleanPath, base64)
            artBase64Cache.put(pathOrUri, base64)
            return base64
        }
        return ""
    }

    private fun extractRealArtBytes(context: Context, cleanPath: String): ByteArray? {
        val pLower = cleanPath.lowercase()
        // Wave/PCM starter synthesizer tracks never have embedded pictures
        if (pLower.endsWith(".wav") || pLower.endsWith(".wave") || pLower.endsWith(".pcm") ||
            pLower.endsWith(".aiff") || pLower.endsWith(".aif") || pLower.contains("phonk_drift_")) {
            return null
        }

        // 1. Android Content URI handling
        if (cleanPath.startsWith("content://")) {
            return extractArtFromContentUri(context, cleanPath)
        }

        // 2. Direct File Path handling
        return extractArtFromFilePath(context, cleanPath)
    }

    private fun extractArtFromContentUri(context: Context, contentUriStr: String): ByteArray? {
        try {
            val uri = Uri.parse(contentUriStr)

            // Step 1: Android 10+ (API 29+) hardware-accelerated MediaStore thumbnail
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val thumb = context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
                    val bos = ByteArrayOutputStream()
                    thumb.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                    val bytes = bos.toByteArray()
                    thumb.recycle()
                    if (bytes.isNotEmpty()) return bytes
                } catch (_: Exception) {}
            }

            // Step 2: Query MediaStore for ALBUM_ID
            val trackId = uri.lastPathSegment?.toLongOrNull() ?: -1L
            if (trackId > 0) {
                val albumArt = loadArtByTrackOrAlbumId(context, trackId, -1L)
                if (albumArt != null && albumArt.isNotEmpty()) return albumArt
            }

            // Step 3: Native MediaMetadataRetriever with FileDescriptor
            var retriever: MediaMetadataRetriever? = null
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    retriever = MediaMetadataRetriever()
                    retriever.setDataSource(pfd.fileDescriptor)
                    val pic = retriever.embeddedPicture
                    if (pic != null && pic.isNotEmpty()) return pic
                }
            } catch (_: Exception) {
            } finally {
                try { pfd?.close() } catch (_: Exception) {}
                try { retriever?.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return null
    }

    private fun extractArtFromFilePath(context: Context, path: String): ByteArray? {
        var file = File(path)
        if (!file.exists()) {
            val fallback = File(File(context.filesDir, "phonks"), file.name)
            if (fallback.exists()) file = fallback
        }
        if (!file.exists() || file.length() < 1000L) {
            return null
        }

        // Step 1: Check native MediaMetadataRetriever directly
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val pic = retriever.embeddedPicture
            if (pic != null && pic.isNotEmpty()) {
                return pic
            }
        } catch (_: Exception) {
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }

        // Step 2: Query MediaStore to find track ID or Album ID
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.DATA} = ?"
            val args = arrayOf(file.absolutePath)

            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.Audio.Media._ID)
                    val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    val trackId = if (idCol != -1) cursor.getLong(idCol) else -1L
                    val albumId = if (albumIdCol != -1) cursor.getLong(albumIdCol) else -1L
                    val msArt = loadArtByTrackOrAlbumId(context, trackId, albumId)
                    if (msArt != null && msArt.isNotEmpty()) return msArt
                }
            }
        } catch (_: Exception) {}

        // Step 3: Adjacent album folder artwork (e.g. cover.jpg, folder.jpg)
        val folderArt = extractAdjacentFolderArt(file)
        if (folderArt != null && folderArt.isNotEmpty()) {
            return folderArt
        }

        return null
    }

    private fun loadArtByTrackOrAlbumId(context: Context, trackId: Long, albumId: Long): ByteArray? {
        // Android 10+ thumbnail from track item URI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && trackId > 0) {
            try {
                val itemUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, trackId)
                val thumb = context.contentResolver.loadThumbnail(itemUri, Size(256, 256), null)
                val bos = ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                val bytes = bos.toByteArray()
                thumb.recycle()
                if (bytes.isNotEmpty()) return bytes
            } catch (_: Exception) {}
        }

        // Android 10+ thumbnail from album URI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && albumId > 0) {
            try {
                val albumUri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
                val thumb = context.contentResolver.loadThumbnail(albumUri, Size(256, 256), null)
                val bos = ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                val bytes = bos.toByteArray()
                thumb.recycle()
                if (bytes.isNotEmpty()) return bytes
            } catch (_: Exception) {}
        }

        // Legacy albumart stream
        if (albumId > 0) {
            try {
                val legacyUri = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
                context.contentResolver.openInputStream(legacyUri)?.use { stream ->
                    val bytes = stream.readBytes()
                    if (bytes.isNotEmpty()) return bytes
                }
            } catch (_: Exception) {}
        }

        return null
    }

    private fun extractAdjacentFolderArt(file: File): ByteArray? {
        try {
            val parent = file.parentFile ?: return null
            if (!parent.exists() || !parent.isDirectory) return null

            val candidates = arrayOf(
                "cover.jpg", "cover.png", "cover.jpeg",
                "folder.jpg", "folder.png",
                "album.jpg", "album.png",
                "${file.nameWithoutExtension}.jpg", "${file.nameWithoutExtension}.png"
            )

            for (cName in candidates) {
                val cand = File(parent, cName)
                if (cand.exists() && cand.isFile && cand.length() in 200..6_000_000) {
                    val bytes = cand.readBytes()
                    if (bytes.isNotEmpty()) return bytes
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
