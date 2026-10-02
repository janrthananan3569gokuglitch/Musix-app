package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Pure native Kotlin ID3v2 tag editor and album art manipulator for XP Music Vault.
 * Supports updating Title, Artist, Album, and embedding or removing APIC album art frames.
 */
object Id3TagWriter {

    private const val TAG = "Id3TagWriter"

    data class Id3Frame(val id: String, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Id3Frame
            if (id != other.id) return false
            if (!data.contentEquals(other.data)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    fun saveMetadata(
        context: Context,
        pathOrUri: String,
        title: String,
        artist: String,
        album: String,
        coverArtBase64: String?,
        isCoverRemoved: Boolean
    ): Boolean {
        try {
            val cleanPath = CoverArtResolver.resolveCleanPath(pathOrUri)
            val file = File(cleanPath)

            // 1. Decode art bytes if provided
            val artBytes: ByteArray? = when {
                isCoverRemoved -> null
                !coverArtBase64.isNullOrBlank() && coverArtBase64 != "KEEP_EXISTING" -> {
                    decodeBase64ToBytes(coverArtBase64)
                }
                else -> {
                    // Keep existing
                    CoverArtResolver.getArtBytes(context, cleanPath)
                }
            }

            var success = false

            // 2. If it's a writable file on disk
            if (file.exists() && file.isFile) {
                if (file.extension.equals("mp3", ignoreCase = true)) {
                    success = writeMp3Tags(file, title, artist, album, artBytes, isCoverRemoved)
                } else {
                    success = true // Non-mp3 file, metadata cached in DB and CoverArtResolver
                }

                // If album art changed, write/remove adjacent cover.jpg in album directory if applicable
                try {
                    val parentDir = file.parentFile
                    if (parentDir != null && parentDir.exists() && parentDir.canWrite()) {
                        val coverFile = File(parentDir, "cover.jpg")
                        if (isCoverRemoved) {
                            if (coverFile.exists()) coverFile.delete()
                        } else if (artBytes != null && artBytes.isNotEmpty()) {
                            FileOutputStream(coverFile).use { it.write(artBytes) }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Update CoverArtResolver caches immediately
            CoverArtResolver.updateCachedArt(cleanPath, artBytes)
            if (cleanPath != pathOrUri) {
                CoverArtResolver.updateCachedArt(pathOrUri, artBytes)
            }

            // 4. Update MediaStore provider entry if track is indexed
            updateMediaStoreRecord(context, cleanPath, title, artist, album)

            Log.i(TAG, "Metadata successfully saved for $cleanPath (artBytes: ${artBytes?.size ?: 0}, isCoverRemoved: $isCoverRemoved)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save metadata: ${e.message}", e)
            return false
        }
    }

    private fun decodeBase64ToBytes(b64Input: String): ByteArray? {
        return try {
            val clean = if (b64Input.contains(",")) {
                b64Input.substringAfter(",")
            } else {
                b64Input
            }.trim()
            val raw = Base64.decode(clean, Base64.DEFAULT)
            // Verify image integrity and normalize to JPEG
            val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size)
            if (bmp != null) {
                val out = ByteArrayOutputStream()
                val targetBmp = if (bmp.width > 600 || bmp.height > 600) {
                    val maxDim = maxOf(bmp.width, bmp.height)
                    val scale = 600f / maxDim
                    Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
                } else {
                    bmp
                }
                targetBmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                if (targetBmp != bmp) targetBmp.recycle()
                bmp.recycle()
                out.toByteArray()
            } else {
                raw
            }
        } catch (e: Exception) {
            Log.e(TAG, "decodeBase64ToBytes error: ${e.message}")
            null
        }
    }

    private fun writeMp3Tags(
        file: File,
        title: String,
        artist: String,
        album: String,
        artBytes: ByteArray?,
        isCoverRemoved: Boolean
    ): Boolean {
        var tempFile: File? = null
        try {
            val fis = FileInputStream(file)
            val header = ByteArray(10)
            val readHeader = fis.read(header)
            var audioDataOffset = 0L

            val existingFrames = ArrayList<Id3Frame>()

            if (readHeader == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                val majorVer = header[3].toInt() and 0xFF
                val tagSize = decodeSyncSafeInt(header, 6)
                audioDataOffset = 10L + tagSize

                val tagBuffer = ByteArray(tagSize)
                fis.read(tagBuffer)

                // Parse existing frames
                var offset = 0
                while (offset + 10 <= tagSize) {
                    val fId = String(tagBuffer, offset, 4, StandardCharsets.US_ASCII)
                    if (fId.isBlank() || fId[0] == '\u0000' || !fId.all { it.isLetterOrDigit() }) {
                        break
                    }
                    val fSize = if (majorVer == 4) {
                        decodeSyncSafeInt(tagBuffer, offset + 4)
                    } else {
                        decodeInt32(tagBuffer, offset + 4)
                    }
                    if (fSize <= 0 || offset + 10 + fSize > tagSize) break
                    val fData = ByteArray(fSize)
                    System.arraycopy(tagBuffer, offset + 10, fData, 0, fSize)

                    // Keep other frames like TRCK, TCON, COMM, etc.
                    if (fId != "TIT2" && fId != "TPE1" && fId != "TALB" && fId != "APIC") {
                        existingFrames.add(Id3Frame(fId, fData))
                    } else if (fId == "APIC" && !isCoverRemoved && artBytes == null) {
                        // Preserved existing art if art was neither removed nor changed
                        existingFrames.add(Id3Frame(fId, fData))
                    }
                    offset += 10 + fSize
                }
            } else {
                audioDataOffset = 0L
            }
            fis.close()

            // Build replacement frames
            val newFrames = ArrayList<Id3Frame>()
            if (title.isNotBlank()) newFrames.add(createTextFrame("TIT2", title))
            if (artist.isNotBlank()) newFrames.add(createTextFrame("TPE1", artist))
            if (album.isNotBlank()) newFrames.add(createTextFrame("TALB", album))

            // Add APIC frame if art provided and not removed
            if (!isCoverRemoved && artBytes != null && artBytes.isNotEmpty()) {
                newFrames.add(createApicFrame(artBytes))
            }

            // Append preserved existing frames
            newFrames.addAll(existingFrames)

            // Serialize frames into new ID3v2.3 tag
            val framesPayload = ByteArrayOutputStream()
            for (f in newFrames) {
                framesPayload.write(f.id.toByteArray(StandardCharsets.US_ASCII))
                val sizeBytes = encodeInt32(f.data.size)
                framesPayload.write(sizeBytes)
                framesPayload.write(byteArrayOf(0x00, 0x00)) // Flags
                framesPayload.write(f.data)
            }

            // Pad tag with 1024 zero bytes for future rapid in-place edits
            val padding = ByteArray(1024)
            framesPayload.write(padding)

            val tagBody = framesPayload.toByteArray()
            val totalTagSize = tagBody.size

            // Construct new ID3v2.3 Header (10 bytes)
            val newHeader = ByteArray(10)
            newHeader[0] = 'I'.code.toByte()
            newHeader[1] = 'D'.code.toByte()
            newHeader[2] = '3'.code.toByte()
            newHeader[3] = 0x03 // ID3v2.3
            newHeader[4] = 0x00
            newHeader[5] = 0x00 // flags
            encodeSyncSafeInt(totalTagSize, newHeader, 6)

            // Write to a temporary file and atomically replace original
            val tempDir = file.parentFile ?: file.absoluteFile.parentFile
            tempFile = File.createTempFile("xptag_", ".tmp", tempDir)
            val fos = FileOutputStream(tempFile)
            fos.write(newHeader)
            fos.write(tagBody)

            // Stream audio data portion from original file
            val originalRaf = RandomAccessFile(file, "r")
            originalRaf.seek(audioDataOffset)
            val buffer = ByteArray(65536)
            var n: Int
            while (originalRaf.read(buffer).also { n = it } != -1) {
                fos.write(buffer, 0, n)
            }
            originalRaf.close()
            fos.flush()
            fos.close()

            // Atomically replace original file with updated file
            val backup = File(file.parentFile, file.name + ".bak")
            if (backup.exists()) backup.delete()
            if (file.renameTo(backup)) {
                if (tempFile.renameTo(file)) {
                    backup.delete()
                    return true
                } else {
                    backup.renameTo(file)
                }
            } else {
                // If renameTo fails, copy stream
                val targetFos = FileOutputStream(file)
                val tempFis = FileInputStream(tempFile)
                tempFis.copyTo(targetFos)
                tempFis.close()
                targetFos.close()
                tempFile.delete()
                return true
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "writeMp3Tags error on ${file.absolutePath}: ${e.message}", e)
            return false
        } finally {
            try { tempFile?.delete() } catch (_: Exception) {}
        }
    }

    private fun createTextFrame(id: String, text: String): Id3Frame {
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val data = ByteArray(1 + textBytes.size)
        data[0] = 0x03 // UTF-8 text encoding flag
        System.arraycopy(textBytes, 0, data, 1, textBytes.size)
        return Id3Frame(id, data)
    }

    private fun createApicFrame(imageBytes: ByteArray): Id3Frame {
        val mime = if (imageBytes.size >= 8 && imageBytes[0] == 0x89.toByte() && imageBytes[1] == 'P'.code.toByte()) {
            "image/png"
        } else {
            "image/jpeg"
        }
        val mimeBytes = mime.toByteArray(StandardCharsets.US_ASCII)

        val bos = ByteArrayOutputStream()
        bos.write(0x00) // 0: ISO-8859-1 encoding for MIME & description
        bos.write(mimeBytes)
        bos.write(0x00) // Null terminator for MIME
        bos.write(0x03) // 3: Cover (front)
        bos.write(0x00) // Null terminator for Description (empty string)
        bos.write(imageBytes)

        return Id3Frame("APIC", bos.toByteArray())
    }

    private fun decodeSyncSafeInt(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0x7F) shl 21) or
               ((buffer[offset + 1].toInt() and 0x7F) shl 14) or
               ((buffer[offset + 2].toInt() and 0x7F) shl 7) or
               (buffer[offset + 3].toInt() and 0x7F)
    }

    private fun encodeSyncSafeInt(value: Int, buffer: ByteArray, offset: Int) {
        buffer[offset] = ((value shr 21) and 0x7F).toByte()
        buffer[offset + 1] = ((value shr 14) and 0x7F).toByte()
        buffer[offset + 2] = ((value shr 7) and 0x7F).toByte()
        buffer[offset + 3] = (value and 0x7F).toByte()
    }

    private fun decodeInt32(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 24) or
               ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
               ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
               (buffer[offset + 3].toInt() and 0xFF)
    }

    private fun encodeInt32(value: Int): ByteArray {
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte()
        )
    }

    private fun updateMediaStoreRecord(
        context: Context,
        path: String,
        title: String,
        artist: String,
        album: String
    ) {
        try {
            val values = ContentValues().apply {
                if (title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, title)
                if (artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, artist)
                if (album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, album)
            }
            context.contentResolver.update(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values,
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path)
            )
        } catch (_: Exception) {}
    }
}
