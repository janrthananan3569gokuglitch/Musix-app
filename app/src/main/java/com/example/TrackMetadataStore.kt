package com.example

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Permanent storage for track metadata overrides (Title, Artist, Album, Cover Art state).
 * Guarantees that any track edited by the user stays permanently edited across restarts and folder rescans.
 */
object TrackMetadataStore {
    private const val FILE_NAME = "track_metadata_overrides.json"
    private val memoryMap = mutableMapOf<String, JSONObject>()
    @Volatile private var isLoaded = false

    private fun normalizeKey(pathOrUri: String): String {
        return CoverArtResolver.resolveCleanPath(pathOrUri).lowercase()
    }

    private fun loadIfNeeded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (file.exists()) {
                    val raw = file.readText()
                    if (raw.isNotBlank()) {
                        val json = JSONObject(raw)
                        val keys = json.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            memoryMap[k] = json.getJSONObject(k)
                        }
                    }
                }
            } catch (_: Exception) {}
            isLoaded = true
        }
    }

    private fun flushToDisk(context: Context) {
        synchronized(this) {
            try {
                val root = JSONObject()
                for ((k, v) in memoryMap) {
                    root.put(k, v)
                }
                val file = File(context.filesDir, FILE_NAME)
                file.writeText(root.toString())
            } catch (_: Exception) {}
        }
    }

    fun saveOverride(
        context: Context,
        pathOrUri: String,
        title: String,
        artist: String,
        album: String,
        hasCustomArt: Boolean,
        isCoverRemoved: Boolean
    ) {
        loadIfNeeded(context)
        val key = normalizeKey(pathOrUri)
        val obj = JSONObject().apply {
            put("title", title)
            put("artist", artist)
            put("album", album)
            put("hasCustomArt", hasCustomArt)
            put("isCoverRemoved", isCoverRemoved)
            put("updatedAt", System.currentTimeMillis())
        }
        memoryMap[key] = obj
        flushToDisk(context)
    }

    fun getOverride(context: Context, pathOrUri: String): JSONObject? {
        loadIfNeeded(context)
        val key = normalizeKey(pathOrUri)
        return memoryMap[key]
    }

    fun deleteOverride(context: Context, pathOrUri: String) {
        loadIfNeeded(context)
        val key = normalizeKey(pathOrUri)
        memoryMap.remove(key)
        flushToDisk(context)
    }
}
