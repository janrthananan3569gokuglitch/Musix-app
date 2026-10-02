package com.example

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.util.LruCache
import android.webkit.JavascriptInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.math.PI
import kotlin.math.sin

/**
 * AndroidBridge: High-Speed IPC Gateway & Native Nervous System.
 *
 * Exposes native audio controls, hardware sensors (Gyroscope / 3D Spatial Panning),
 * native Visualizer FFT streaming, and high-efficiency SD folder scanning to the WebView.
 */
class AndroidBridge(
    private val activity: MainActivity
) : SensorEventListener {

    private val tag = "AndroidBridge"

    // LRU Caches for Cover Art & Dynamic Theme Color Extraction
    private val artBase64Cache = LruCache<String, String>(100)
    private val dominantColorCache = LruCache<String, String>(200)

    // Log Ring Buffer
    private val logRingBuffer = ConcurrentLinkedDeque<String>()
    private val maxLogs = 150
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var configuredPath: String = "/storage/3263-3638/phonks"

    // Native SensorManager & 3D Spatial Panning Engine
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null

    @Volatile private var sensorAzimuth = 0f
    @Volatile private var sensorPitch = 0f
    @Volatile private var sensorRoll = 0f
    @Volatile private var gyroX = 0f
    @Volatile private var gyroY = 0f
    @Volatile private var gyroZ = 0f
    @Volatile private var integratedYaw = 0f
    @Volatile private var lastGyroTimeNs: Long = 0L
    @Volatile private var hasDirectRotation = false
    @Volatile private var refYaw = 0f
    @Volatile private var hasCalibrated = false

    // Spatial Mode: "gyro", "orbit", "center"
    @Volatile private var spatialMode: String = "center"
    private var orbitAngle = 0.0
    private var lastOrbitTime = 0L

    // Pre-allocated arrays to eliminate GC overhead
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    init {
        // Asynchronously synthesize starter tracks if needed so songs are always available
        Thread {
            try {
                VaultTrackSynthesizer.ensureStarterTracks(activity)
            } catch (_: Exception) {}
        }.start()
        setupHardwareSensors()
        setupServiceHooks()
    }

    private fun getAudioService(): AudioVaultService? {
        return AudioVaultService.instance ?: activity.getAudioService()
    }

    private fun setupServiceHooks() {
        AudioVaultService.onPlaybackCompleted = {
            activity.runOnUiThread {
                activity.evaluateJs("if (window.onNativePlaybackCompleted) window.onNativePlaybackCompleted();")
            }
        }
        AudioVaultService.onPlaybackStateChanged = { playing, pos, dur ->
            activity.runOnUiThread {
                if (!playing) {
                    activity.evaluateJs("window.isPlaying = false; if (window.onNativePlaybackState) window.onNativePlaybackState(false, $pos, $dur);")
                } else {
                    activity.evaluateJs("if (window.onNativePlaybackState) window.onNativePlaybackState(true, $pos, $dur);")
                }
            }
        }
        AudioVaultService.onActionCallback = { action ->
            activity.runOnUiThread {
                if (action == "PAUSE") {
                    activity.evaluateJs("window.isPlaying = false; if (window.onNativeMediaAction) window.onNativeMediaAction('$action');")
                } else {
                    activity.evaluateJs("if (window.onNativeMediaAction) window.onNativeMediaAction('$action');")
                }
            }
        }
        AudioVaultService.onAudioFocusChanged = { isLost ->
            nativeAudioFocusLost = isLost
            activity.runOnUiThread {
                if (isLost) {
                    activity.evaluateJs("window.isSystemInterrupted = true; window.isPlaying = false; window.isSystemPaused = true; if (typeof window.setSystemPaused === 'function') window.setSystemPaused(true); if (window.onNativeAudioFocusChanged) window.onNativeAudioFocusChanged(true);")
                } else {
                    activity.evaluateJs("window.isSystemInterrupted = false; if (window.onNativeAudioFocusChanged) window.onNativeAudioFocusChanged(false);")
                }
            }
        }
    }

    @Volatile
    private var nativeAudioFocusLost = false

    @JavascriptInterface
    fun isAudioFocusLost(): Boolean = nativeAudioFocusLost

    // =========================================================================
    // NATIVE AUDIO PLAYBACK JAVASCRIPT INTERFACES
    // =========================================================================

    @JavascriptInterface
    fun playTrack(pathOrUri: String, title: String, artist: String, album: String): Boolean {
        logEvent("PLAY", "playTrack invoked for: $title ($pathOrUri)")
        val svc = getAudioService() ?: return false
        if (svc.getCurrentTrackPath() == pathOrUri && !svc.isAudioPlaying()) {
            logEvent("PLAY", "Seamlessly resuming existing paused track: $pathOrUri")
            return svc.resumePlayback()
        }
        return svc.playTrack(pathOrUri, title, artist, album)
    }

    @JavascriptInterface
    fun playTrackSimple(pathOrUri: String): Boolean {
        val fName = File(pathOrUri).nameWithoutExtension
        return playTrack(pathOrUri, fName, "XP Phonk Vault", "Phonk Drift Edition")
    }

    @JavascriptInterface
    fun resume(): Boolean {
        logEvent("PLAY", "resume invoked")
        return getAudioService()?.resumePlayback() ?: false
    }

    @JavascriptInterface
    fun pause() {
        logEvent("PAUSE", "pause invoked")
        getAudioService()?.pausePlayback()
    }

    @JavascriptInterface
    fun seek(positionMs: Long) {
        getAudioService()?.seekTo(positionMs)
    }

    @JavascriptInterface
    fun isPlaying(): Boolean {
        return getAudioService()?.isPlaying() ?: false
    }

    @JavascriptInterface
    fun getCurrentPosition(): Long {
        return getAudioService()?.getCurrentPosition() ?: 0L
    }

    @JavascriptInterface
    fun getDuration(): Long {
        return getAudioService()?.getDuration() ?: 0L
    }

    @JavascriptInterface
    fun setEq(lowDb: Float, midDb: Float, highDb: Float) {
        getAudioService()?.setEq(lowDb, midDb, highDb)
    }

    @JavascriptInterface
    fun setMasterVolume(vol: Float) {
        getAudioService()?.setMasterVolume(vol)
    }

    @JavascriptInterface
    fun setVolume(vol: Float) {
        getAudioService()?.setMasterVolume(vol)
    }

    // =========================================================================
    // NATIVE VISUALIZER & FREQUENCY ANALYZER DATA-BINDING BRIDGE (30-60 FPS)
    // =========================================================================

    @JavascriptInterface
    fun getVisualizerData(): String {
        return getAudioService()?.getVisualizerData() ?: "[]"
    }

    @JavascriptInterface
    fun getFrequencySpectrum(): String {
        return getAudioService()?.getVisualizerData() ?: "[]"
    }

    @JavascriptInterface
    fun getAudioEngineAnalyzerData(): String {
        return getAudioService()?.getVisualizerData() ?: "[]"
    }

    // =========================================================================
    // SENSOR & 3D SPATIAL PANNING CONTROLLER
    // =========================================================================

    private fun setupHardwareSensors() {
        try {
            sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

            gyroscopeSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            rotationSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (_: Exception) {
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                sensorAzimuth = orientation[0]
                sensorPitch = orientation[1]
                sensorRoll = orientation[2]
                hasDirectRotation = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyroX = event.values[0]
                gyroY = event.values[1]
                gyroZ = event.values[2]
                val currentNs = event.timestamp
                if (lastGyroTimeNs != 0L) {
                    val dt = (currentNs - lastGyroTimeNs) * 1.0e-9f
                    if (dt in 0.0001f..0.2f) {
                        integratedYaw += gyroZ * dt
                    }
                }
                lastGyroTimeNs = currentNs
            }
        }

        // Apply real-time 3D spatial panning directly into native audio engine ONLY when in gyro mode
        if (spatialMode == "gyro") {
            applyNativeSpatialPanning()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun applyNativeSpatialPanning() {
        if (spatialMode == "center") return
        val service = getAudioService() ?: return
        if (!service.isPlaying()) return

        when (spatialMode) {
            "gyro" -> {
                val rawYaw = if (hasDirectRotation) sensorAzimuth else integratedYaw
                if (!hasCalibrated) {
                    refYaw = rawYaw
                    hasCalibrated = true
                }
                var dYaw = rawYaw - refYaw
                while (dYaw > PI) dYaw -= (2 * PI).toFloat()
                while (dYaw < -PI) dYaw += (2 * PI).toFloat()
                val pan = (-sin(dYaw.toDouble())).toFloat().coerceIn(-1.0f, 1.0f)
                service.setSpatialPan(pan)
            }
            "atmos" -> {
                val now = System.currentTimeMillis()
                if (lastOrbitTime == 0L) lastOrbitTime = now
                val dt = (now - lastOrbitTime) / 1000.0
                lastOrbitTime = now
                // Realistic slow cinema rotation (0.24 rad/s ~ 26s per rotation for Dolby Atmos 360)
                orbitAngle += dt * 0.24
                val pan = (sin(orbitAngle) * 0.82).toFloat().coerceIn(-1.0f, 1.0f)
                service.setSpatialPan(pan)
            }
            "orbit", "8d" -> {
                val now = System.currentTimeMillis()
                if (lastOrbitTime == 0L) lastOrbitTime = now
                val dt = (now - lastOrbitTime) / 1000.0
                lastOrbitTime = now
                // Realistic 8D head-to-round orbit speed (0.35 rad/s ~ 18s per rotation instead of 1.5 rad/s)
                orbitAngle += dt * 0.35
                val pan = (sin(orbitAngle) * 0.88).toFloat().coerceIn(-1.0f, 1.0f)
                service.setSpatialPan(pan)
            }
            "center" -> {
                service.setSpatialPan(0f)
            }
        }
    }

    @JavascriptInterface
    fun setSpatialMode(mode: String): String {
        spatialMode = mode.lowercase(Locale.ROOT)
        logEvent("SPATIAL", "Spatial mode set to: $spatialMode")
        getAudioService()?.setSpatialMode(spatialMode)
        if (spatialMode == "gyro") {
            hasCalibrated = false
        } else if (spatialMode == "orbit" || spatialMode == "8d" || spatialMode == "atmos") {
            lastOrbitTime = System.currentTimeMillis()
        } else {
            getAudioService()?.setSpatialPan(0f)
        }
        return spatialMode
    }

    @JavascriptInterface
    fun setSpatialPan(pan: Float) {
        getAudioService()?.setSpatialPan(pan)
    }

    @JavascriptInterface
    fun resetHeadTracking() {
        hasCalibrated = false
        refYaw = 0f
        integratedYaw = 0f
        lastGyroTimeNs = 0L
        getAudioService()?.setSpatialPan(0f)
        logEvent("SPATIAL", "Head tracking recalibrated to origin")
    }

    @JavascriptInterface
    fun calibrateHeadTracking() {
        resetHeadTracking()
    }

    @JavascriptInterface
    fun getHeadTrackingData(): String {
        val effectiveYaw = if (hasDirectRotation) sensorAzimuth else integratedYaw
        return JSONObject().apply {
            put("available", true)
            put("mode", spatialMode)
            put("yaw", effectiveYaw)
            put("pitch", sensorPitch)
            put("roll", sensorRoll)
        }.toString()
    }

    // =========================================================================
    // LOCAL STORAGE PATH & SMART FOLDER SCANNING
    // =========================================================================

    private fun normalizeTrackKey(title: String, fileName: String): String {
        val raw = (if (fileName.isNotBlank()) fileName else title).lowercase(Locale.ROOT)
            .replace(Regex("\\.(mp3|wav|flac|m4a|aac|ogg|opus|aiff|aif|alac|ape|wv|pcm|wma)$"), "")
            .replace(Regex("^(\\d{1,3}[\\s\\-_.]+)"), "")
            .replace(Regex("\\b(official\\s*(audio|video|music\\s*video)?|slowed\\s*(\\+|and)?\\s*reverb|bass\\s*boosted|remix|hd|hq|lyrics?|320kbps|flac)\\b"), "")
            .replace(Regex("[^a-z0-9]"), "")
        return raw.ifBlank { (title.ifBlank { fileName }).lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "") }
    }

    @JavascriptInterface
    fun setTargetStoragePath(path: String): String {
        if (path.isNotBlank()) {
            configuredPath = path.trim()
            logEvent("CONFIG", "Active target path set to: $configuredPath")
        }
        return getTargetStorageInfo()
    }

    @JavascriptInterface
    fun getTargetStoragePath(): String = configuredPath

    @JavascriptInterface
    fun getTargetStorageInfo(): String {
        val targetPath = configuredPath
        val resolved = resolveTargetDirectory(targetPath)
        val exists = resolved?.exists() == true && resolved.isDirectory
        var count = 0
        if (exists && resolved != null) {
            val audioExts = setOf("flac", "wav", "aiff", "aif", "aifc", "alac", "ape", "wv", "pcm", "m4a", "mp3", "aac", "ogg", "opus", "wma")
            resolved.listFiles()?.forEach { f ->
                if (f.isFile && audioExts.contains(f.extension.lowercase(Locale.ROOT))) count++
            }
        }
        val detected = getAvailableStoragePaths()
        return JSONObject().apply {
            put("targetPath", targetPath)
            put("resolvedPath", resolved?.absolutePath ?: targetPath)
            put("exists", exists)
            put("tracksCount", count)
            put("isConnected", exists && count > 0)
            put("availablePaths", JSONArray(detected))
        }.toString()
    }

    @JavascriptInterface
    fun getAvailableStoragePaths(): List<String> {
        val paths = LinkedHashSet<String>()
        paths.add(configuredPath)
        paths.add("/storage/3263-3638/phonks")

        try {
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                storageRoot.listFiles()?.forEach { disk ->
                    if (disk.isDirectory && disk.name != "emulated" && disk.name != "self") {
                        paths.add("${disk.absolutePath}/phonks")
                        paths.add("${disk.absolutePath}/Music")
                        paths.add(disk.absolutePath)
                    }
                }
            }
        } catch (_: Exception) {}

        paths.add("/sdcard/phonks")
        paths.add("/storage/emulated/0/phonks")
        paths.add("/storage/emulated/0/Music")
        paths.add("/storage/emulated/0/Download")
        return paths.toList()
    }

    private fun resolveTargetDirectory(target: String = configuredPath): File? {
        val cleanTarget = target.trim()
        val direct = File(cleanTarget)
        if (direct.exists() && direct.isDirectory) return direct

        val candidates = mutableListOf(
            direct,
            File(cleanTarget.replace("/phonks", "/Phonks")),
            File(cleanTarget.replace("/phonks", "/PHONKS")),
            File("/storage/3263-3638/phonks"),
            File("/storage/3263-3638/Phonks")
        )
        try {
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                storageRoot.listFiles()?.forEach { disk ->
                    if (disk.isDirectory && disk.name != "emulated" && disk.name != "self") {
                        candidates.add(File(disk, "phonks"))
                        candidates.add(File(disk, "Phonks"))
                    }
                }
            }
        } catch (_: Exception) {}

        return candidates.firstOrNull { it.exists() && it.isDirectory } ?: direct
    }

    @Volatile private var cachedScanJson: String = "[]"

    @JavascriptInterface
    fun scanPhonks(): String = scanPhonksFolder()

    @JavascriptInterface
    fun scanCustomPath(path: String): String {
        if (path.isNotBlank()) configuredPath = path.trim()
        scanPhonksFolderAsync()
        return cachedScanJson
    }

    @JavascriptInterface
    fun scanPhonksFolderAsync() {
        CoroutineScope(Dispatchers.IO).launch {
            val json = performShallowScan(configuredPath)
            cachedScanJson = json
            activity.runOnUiThread {
                activity.evaluateJs("if (window.onScanComplete) { window.onScanComplete(${JSONObject.quote(json)}); } else if (window.onTracksDiscovered) { window.onTracksDiscovered(${JSONObject.quote(json)}); }")
            }
        }
    }

    @JavascriptInterface
    fun scanPhonksFolder(): String {
        // Asynchronously scan in background on IO dispatcher to NEVER block the Main/JS thread
        CoroutineScope(Dispatchers.IO).launch {
            val json = performShallowScan(configuredPath)
            cachedScanJson = json
            activity.runOnUiThread {
                activity.evaluateJs("if (window.onScanComplete) { window.onScanComplete(${JSONObject.quote(json)}); } else if (window.onTracksDiscovered) { window.onTracksDiscovered(${JSONObject.quote(json)}); }")
            }
        }
        return cachedScanJson
    }

    fun performShallowScan(targetPath: String): String {
        logEvent("SCAN", "Native target scan triggered for: $targetPath")
        val jsonArray = JSONArray()

        val seenPaths = HashSet<String>()
        val exactMatches = ArrayList<JSONObject>()
        val audioExtensions = setOf("flac", "wav", "aiff", "aif", "aifc", "alac", "ape", "wv", "pcm", "m4a", "mp3", "aac", "ogg", "opus", "wma", "mp4", "m4b")

        // 1. Direct Scan on Target Folder (/storage/3263-3638/phonks) - immediate & subdirectories
        val primaryDir = resolveTargetDirectory(targetPath)
        var directId = 1000L

        if (primaryDir != null && primaryDir.exists() && primaryDir.isDirectory) {
            val mediaStoreCache = HashMap<String, Triple<String?, String?, Long>>()
            try {
                val proj = arrayOf(
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION
                )
                val targetName = File(targetPath).name.ifBlank { "phonks" }
                val sel = "${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?"
                val selArgs = arrayOf("%$targetPath/%", "%/$targetName/%")
                activity.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    proj,
                    sel,
                    selArgs,
                    null
                )?.use { c ->
                    val dCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                    val tCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                    val aCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                    val durCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    while (c.moveToNext()) {
                        val p = if (dCol != -1) c.getString(dCol) else null
                        if (!p.isNullOrBlank()) {
                            val t = if (tCol != -1) c.getString(tCol) else null
                            val a = if (aCol != -1) c.getString(aCol) else null
                            val dur = if (durCol != -1) c.getLong(durCol) else 0L
                            mediaStoreCache[p.lowercase(Locale.ROOT)] = Triple(t, a, dur)
                        }
                    }
                }
            } catch (_: Exception) {}

            var mmr: MediaMetadataRetriever? = null
            try {
                val filesToScan = mutableListOf<File>()
                // Collect immediate files
                primaryDir.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        filesToScan.add(f)
                    } else if (f.isDirectory) {
                        // Include songs in sub-folders within the target phonks directory
                        try {
                            f.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { sub ->
                                filesToScan.add(sub)
                            }
                        } catch (_: Exception) {}
                    }
                }

                for (file in filesToScan) {
                    val ext = file.extension.lowercase(Locale.ROOT)
                    if (audioExtensions.contains(ext)) {
                        val path = file.absolutePath
                        val pathLower = path.lowercase(Locale.ROOT)

                        // Deduplicate ONLY on exact unique file path so ALL songs are shown
                        if (seenPaths.contains(pathLower)) {
                            continue
                        }
                        seenPaths.add(pathLower)

                        val defaultTitle = file.nameWithoutExtension.ifBlank { "Phonk Track" }
                        val parentName = file.parentFile?.name ?: ""
                        val defaultAlbumName = if (parentName.isNotBlank() && !parentName.equals("phonks", true) && !parentName.equals("Music", true) && !parentName.equals("Download", true)) {
                            parentName
                        } else {
                            "Phonk Master Series"
                        }
                        val bitDepth = if (ext in listOf("flac", "alac", "aiff", "aif", "ape", "wv")) 24 else 16

                        var durationMs = 0L
                        var metaTitle: String? = null
                        var metaArtist: String? = null

                        val cachedMeta = mediaStoreCache[pathLower]
                        if (cachedMeta != null) {
                            metaTitle = cachedMeta.first
                            metaArtist = cachedMeta.second
                            durationMs = cachedMeta.third
                        } else {
                            try {
                                if (mmr == null) mmr = MediaMetadataRetriever()
                                mmr.setDataSource(path)
                                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                metaTitle = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                                metaArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                            } catch (_: Exception) {}
                        }

                        var title = if (!metaTitle.isNullOrBlank()) metaTitle else defaultTitle
                        var artist = if (!metaArtist.isNullOrBlank()) metaArtist else "SD Phonk Vault"
                        var album = defaultAlbumName

                        // Check permanent user metadata overrides
                        val override = TrackMetadataStore.getOverride(activity, path)
                        var isCoverRemoved = false
                        var updatedAt = 0L
                        if (override != null) {
                            val overTitle = override.optString("title")
                            val overArtist = override.optString("artist")
                            val overAlbum = override.optString("album")
                            if (!overTitle.isNullOrBlank()) title = overTitle
                            if (!overArtist.isNullOrBlank()) artist = overArtist
                            if (!overAlbum.isNullOrBlank()) album = overAlbum
                            isCoverRemoved = override.optBoolean("isCoverRemoved", false)
                            updatedAt = override.optLong("updatedAt", 0L)
                        }

                        val artUrl = if (isCoverRemoved) "" else "https://appassets.androidplatform.net/album-art/${Uri.encode(path)}${if (updatedAt > 0) "?t=$updatedAt" else ""}"

                        val item = JSONObject().apply {
                            put("id", directId++)
                            put("path", path)
                            put("contentUri", "file://$path")
                            put("streamUrl", "https://appassets.androidplatform.net/local-audio/${Uri.encode(path)}")
                            put("title", title)
                            put("artist", artist)
                            put("album", album)
                            put("bitDepth", bitDepth)
                            put("duration", durationMs)
                            put("size", file.length())
                            put("lastModified", file.lastModified())
                            put("mimeType", resolveAudioMime(path))
                            put("isExactTarget", true)
                            put("isTagEdited", override != null)
                            put("isCoverRemoved", isCoverRemoved)
                            put("artUrl", artUrl)
                        }
                        exactMatches.add(item)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { mmr?.release() } catch (_: Exception) {}
            }
        }

        // 2. MediaStore Query for Target Path (ensures Scoped Storage on Android 11+ yields all songs)
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED
            )
            val selection = "${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%3263-3638/phonks/%", "%/phonks/%")
            activity.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                val titleCol = cursor.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val modCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val path = if (dataCol != -1) cursor.getString(dataCol) else null
                    if (!path.isNullOrBlank()) {
                        val pathLower = path.lowercase(Locale.ROOT)
                        if (!seenPaths.contains(pathLower)) {
                            seenPaths.add(pathLower)
                            val f = File(path)
                            val ext = f.extension.lowercase(Locale.ROOT)
                            if (audioExtensions.contains(ext)) {
                                val bitDepth = if (ext in listOf("flac", "alac", "aiff", "aif", "ape", "wv")) 24 else 16
                                var title = (if (titleCol != -1) cursor.getString(titleCol) else null)?.ifBlank { f.nameWithoutExtension } ?: f.nameWithoutExtension
                                var artist = (if (artistCol != -1) cursor.getString(artistCol) else null)?.ifBlank { "SD Phonk Vault" } ?: "SD Phonk Vault"
                                var album = (if (albumCol != -1) cursor.getString(albumCol) else null)?.ifBlank { "Phonk Master Series" } ?: "Phonk Master Series"
                                val durationMs = if (durCol != -1) cursor.getLong(durCol) else 0L
                                val size = if (sizeCol != -1) cursor.getLong(sizeCol) else f.length()
                                val mod = if (modCol != -1) cursor.getLong(modCol) * 1000L else f.lastModified()

                                // Check permanent user metadata overrides
                                val override = TrackMetadataStore.getOverride(activity, path)
                                var isCoverRemoved = false
                                var updatedAt = 0L
                                if (override != null) {
                                    val overTitle = override.optString("title")
                                    val overArtist = override.optString("artist")
                                    val overAlbum = override.optString("album")
                                    if (!overTitle.isNullOrBlank()) title = overTitle
                                    if (!overArtist.isNullOrBlank()) artist = overArtist
                                    if (!overAlbum.isNullOrBlank()) album = overAlbum
                                    isCoverRemoved = override.optBoolean("isCoverRemoved", false)
                                    updatedAt = override.optLong("updatedAt", 0L)
                                }

                                val artUrl = if (isCoverRemoved) "" else "https://appassets.androidplatform.net/album-art/${Uri.encode(path)}${if (updatedAt > 0) "?t=$updatedAt" else ""}"

                                exactMatches.add(JSONObject().apply {
                                    put("id", directId++)
                                    put("path", path)
                                    put("contentUri", "file://$path")
                                    put("streamUrl", "https://appassets.androidplatform.net/local-audio/${Uri.encode(path)}")
                                    put("title", title)
                                    put("artist", artist)
                                    put("album", album)
                                    put("bitDepth", bitDepth)
                                    put("duration", durationMs)
                                    put("size", size)
                                    put("lastModified", mod)
                                    put("mimeType", resolveAudioMime(path))
                                    put("isExactTarget", true)
                                    put("isTagEdited", override != null)
                                    put("isCoverRemoved", isCoverRemoved)
                                    put("artUrl", artUrl)
                                })
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logEvent("SCAN", "MediaStore query fallback: ${e.message}")
        }

        // 3. Ensure internal Starter Tracks ONLY when NO songs exist in the target folder
        val starterDir = File(activity.filesDir, "phonks")
        if (exactMatches.isEmpty() && (!starterDir.exists() || (starterDir.listFiles()?.size ?: 0) < 5)) {
            try {
                VaultTrackSynthesizer.ensureStarterTracks(activity)
            } catch (_: Exception) {}
        }
        val starterMatches = ArrayList<JSONObject>()
        if (exactMatches.isEmpty() && starterDir.exists()) {
            starterDir.listFiles()?.forEach { file ->
                if (file.isFile && file.extension.equals("wav", ignoreCase = true) && file.length() > 0) {
                    val path = file.absolutePath
                    val fName = file.nameWithoutExtension.replace("_", " ")
                    val item = JSONObject().apply {
                        put("id", directId++)
                        put("path", path)
                        put("contentUri", "file://$path")
                        put("streamUrl", "https://appassets.androidplatform.net/local-audio/${Uri.encode(path)}")
                        put("title", fName)
                        put("artist", "XP Phonk Syndicate")
                        put("album", "XP Phonk Vault Master Series")
                        put("bitDepth", 24)
                        put("duration", 11000L)
                        put("size", file.length())
                        put("lastModified", file.lastModified())
                        put("mimeType", "audio/wav")
                        put("isExactTarget", false)
                        put("isStarter", true)
                    }
                    starterMatches.add(item)
                }
            }
        }

        // Sort: newest songs first, oldest songs last
        exactMatches.sortWith(compareByDescending<JSONObject> { it.optLong("lastModified", 0L) }.thenByDescending { it.optLong("id", 0L) })
        starterMatches.sortWith(compareByDescending<JSONObject> { it.optLong("lastModified", 0L) }.thenByDescending { it.optLong("id", 0L) })

        for (item in exactMatches) {
            jsonArray.put(item)
        }
        if (jsonArray.length() == 0) {
            for (item in starterMatches) {
                jsonArray.put(item)
            }
        }

        logEvent("SCAN", "Strict immediate shallow scan completed: ${jsonArray.length()} tracks loaded natively")
        return jsonArray.toString()
    }

    @JavascriptInterface
    fun scanMusicTracks(): String = scanPhonksFolder()

    @JavascriptInterface
    fun playSingleTrack(pathOrUri: String): Boolean = playTrackSimple(pathOrUri)

    // =========================================================================
    // METADATA & COVER ART
    // =========================================================================

    @JavascriptInterface
    fun getArtBase64(pathOrUri: String): String {
        if (pathOrUri.isBlank()) return ""
        artBase64Cache.get(pathOrUri)?.let {
            if (it.isNotBlank()) return it
        }

        val base64 = CoverArtResolver.getArtBase64(activity, pathOrUri)
        if (base64.isNotBlank()) {
            artBase64Cache.put(pathOrUri, base64)
            return base64
        }
        return ""
    }

    @JavascriptInterface
    fun getArtDominantColor(pathOrUri: String): String {
        if (pathOrUri.isBlank()) return "0,245,212"
        dominantColorCache.get(pathOrUri)?.let { return it }

        val artBase64 = getArtBase64(pathOrUri)
        if (artBase64.isBlank() || !artBase64.startsWith("data:image")) {
            dominantColorCache.put(pathOrUri, "0,245,212")
            return "0,245,212"
        }

        try {
            val commaIdx = artBase64.indexOf(',')
            if (commaIdx != -1) {
                val base64Data = artBase64.substring(commaIdx + 1)
                val artBytes = Base64.decode(base64Data, Base64.DEFAULT)
                if (artBytes != null && artBytes.isNotEmpty()) {
                    val fullBmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                    if (fullBmp != null) {
                        val thumb = Bitmap.createScaledBitmap(fullBmp, 16, 16, true)
                        if (thumb != fullBmp) fullBmp.recycle()

                        var totalR = 0L; var totalG = 0L; var totalB = 0L
                        var maxSat = 0f; var bestR = 0; var bestG = 245; var bestB = 212

                        val hsv = FloatArray(3)
                        for (x in 0 until 16) {
                            for (y in 0 until 16) {
                                val pixel = thumb.getPixel(x, y)
                                val r = Color.red(pixel); val g = Color.green(pixel); val b = Color.blue(pixel)
                                totalR += r; totalG += g; totalB += b
                                Color.colorToHSV(pixel, hsv)
                                val sat = hsv[1]
                                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                                if (lum in 25.0..230.0 && sat > maxSat) {
                                    maxSat = sat; bestR = r; bestG = g; bestB = b
                                }
                            }
                        }
                        thumb.recycle()

                        val targetR = if (maxSat > 0.2f) bestR else (totalR / 256).toInt()
                        val targetG = if (maxSat > 0.2f) bestG else (totalG / 256).toInt()
                        val targetB = if (maxSat > 0.2f) bestB else (totalB / 256).toInt()

                        Color.RGBToHSV(targetR, targetG, targetB, hsv)
                        hsv[1] = (hsv[1] * 1.35f).coerceIn(0.45f, 1.0f)
                        hsv[2] = hsv[2].coerceIn(0.35f, 0.90f)
                        val vibrant = Color.HSVToColor(hsv)
                        val result = "${Color.red(vibrant)},${Color.green(vibrant)},${Color.blue(vibrant)}"
                        dominantColorCache.put(pathOrUri, result)
                        return result
                    }
                }
            }
        } catch (_: Exception) {}

        dominantColorCache.put(pathOrUri, "0,245,212")
        return "0,245,212"
    }

    private fun resolveAudioMime(path: String): String {
        val lower = path.lowercase(Locale.ROOT)
        return when {
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".wav") -> "audio/wav"
            lower.endsWith(".aiff") || lower.endsWith(".aif") -> "audio/aiff"
            lower.endsWith(".alac") -> "audio/alac"
            lower.endsWith(".m4a") || lower.endsWith(".mp4") -> "audio/mp4"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            else -> "audio/mpeg"
        }
    }

    @JavascriptInterface
    fun getTrackSpecs(pathOrUri: String): String {
        if (pathOrUri.isBlank()) {
            return JSONObject().apply {
                put("bitDepth", 16)
                put("album", "XP Phonk Vault")
                put("bitrate", 320000)
                put("sampleRate", 44100)
            }.toString()
        }

        val result = JSONObject()
        var bitDepth = 16
        var album = ""
        var sampleRate = 44100
        var bitrate = 320000

        try {
            val isFlac = pathOrUri.endsWith(".flac", ignoreCase = true)
            val isWav = pathOrUri.endsWith(".wav", ignoreCase = true)

            val inputStream = if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                activity.contentResolver.openInputStream(Uri.parse(pathOrUri))
            } else {
                val f = File(pathOrUri)
                if (f.exists()) FileInputStream(f) else null
            }

            inputStream?.use { stream ->
                val header = ByteArray(64)
                val read = stream.read(header)
                if (read >= 36) {
                    if (header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte()) {
                        val bits = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
                        if (bits in 8..64) bitDepth = bits
                    } else if (header[0] == 'f'.code.toByte() && header[1] == 'L'.code.toByte() &&
                               header[2] == 'a'.code.toByte() && header[3] == 'C'.code.toByte()) {
                        val b20 = header[20].toInt() and 0xFF
                        val b21 = header[21].toInt() and 0xFF
                        val bits = (((b20 and 0x01) shl 4) or ((b21 and 0xF0) ushr 4)) + 1
                        if (bits in 8..32) bitDepth = bits
                    }
                }
            }
        } catch (_: Exception) {}

        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            if (pathOrUri.startsWith("content://")) {
                retriever.setDataSource(activity, Uri.parse(pathOrUri))
            } else {
                retriever.setDataSource(pathOrUri)
            }
            val metaAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            if (!metaAlbum.isNullOrBlank()) album = metaAlbum.trim()
            val metaBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            if (!metaBitrate.isNullOrBlank()) bitrate = metaBitrate.toIntOrNull() ?: bitrate
            val metaSr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
            if (!metaSr.isNullOrBlank()) sampleRate = metaSr.toIntOrNull() ?: sampleRate
        } catch (_: Exception) {
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }

        if (album.isBlank()) album = "XP Phonk Vault"
        val pLower = pathOrUri.lowercase(Locale.ROOT)
        if (bitDepth == 16 && (pLower.endsWith(".flac") || pLower.endsWith(".wav") || pLower.endsWith(".aiff") || pLower.endsWith(".alac"))) {
            bitDepth = 24
        }

        result.put("bitDepth", bitDepth)
        result.put("album", album)
        result.put("bitrate", bitrate)
        result.put("sampleRate", sampleRate)
        return result.toString()
    }

    @JavascriptInterface
    fun deleteTrack(path: String): Boolean {
        if (path.isBlank()) return false
        logEvent("DELETE", "Request to delete track: $path")
        var deleted = false
        try {
            val file = File(path)
            if (file.exists()) {
                deleted = file.delete()
                logEvent("DELETE", "File delete result: $deleted for $path")
            }
        } catch (e: Exception) {
            logEvent("ERROR", "Error deleting physical file: ${e.message}")
        }

        try {
            val rows = activity.contentResolver.delete(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Audio.Media.DATA} = ?",
                arrayOf(path)
            )
            if (rows > 0) deleted = true
        } catch (_: Exception) {}

        try {
            TrackMetadataStore.deleteOverride(activity, path)
        } catch (_: Exception) {}

        try {
            CoverArtResolver.updateCachedArt(activity, path, null, isCoverRemoved = true)
        } catch (_: Exception) {}

        return deleted
    }

    // =========================================================================
    // SYSTEM & DIAGNOSTICS
    // =========================================================================

    @JavascriptInterface
    fun getNativeLogs(): String {
        val sb = StringBuilder()
        for (log in logRingBuffer) sb.append(log).append("\n")
        return sb.toString()
    }

    @JavascriptInterface
    fun clearNativeLogs() {
        logRingBuffer.clear()
        logEvent("KERNEL", "Native diagnostic logs cleared")
    }

    @JavascriptInterface
    fun logEvent(tag: String, msg: String) {
        val timestamp = dateFormat.format(Date())
        val formatted = "[$timestamp][$tag] $msg"
        // Avoid excessive Logcat noise: only log errors
        if (tag == "ERROR" || tag == "CRITICAL") {
            Log.e("XPVault", msg)
        }
        logRingBuffer.addLast(formatted)
        while (logRingBuffer.size > maxLogs) logRingBuffer.pollFirst()
    }

    @JavascriptInterface
    fun toggleSystemFullScreen(on: Boolean) {
        activity.runOnUiThread { activity.setSystemFullscreen(on) }
    }

    @JavascriptInterface
    fun exitApp() {
        activity.runOnUiThread {
            try {
                activity.stopPlaybackService()
            } catch (_: Exception) {}
            try {
                activity.finishAndRemoveTask()
            } catch (_: Exception) {}
            try {
                activity.finishAffinity()
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(0)
        }
    }

    @JavascriptInterface
    fun triggerFilePicker() {
        activity.runOnUiThread { activity.launchFilePicker() }
    }

    @JavascriptInterface
    fun pickCoverImage() {
        activity.runOnUiThread { activity.launchCoverPicker() }
    }

    @JavascriptInterface
    fun saveTrackMetadata(
        pathOrUri: String,
        title: String,
        artist: String,
        album: String,
        coverArtBase64: String,
        isCoverRemoved: Boolean
    ): Boolean {
        logEvent("METADATA", "saveTrackMetadata invoked for: $title by $artist (isCoverRemoved=$isCoverRemoved)")
        val cleanPath = CoverArtResolver.resolveCleanPath(pathOrUri)

        // 1. Process cover art bytes if new art provided
        val artBytes: ByteArray? = when {
            isCoverRemoved -> null
            !coverArtBase64.isNullOrBlank() && coverArtBase64 != "KEEP_EXISTING" -> {
                try {
                    val clean = if (coverArtBase64.contains(",")) coverArtBase64.substringAfter(",") else coverArtBase64
                    Base64.decode(clean.trim(), Base64.DEFAULT)
                } catch (_: Exception) { null }
            }
            else -> null
        }

        // 2. Persist custom cover art to internal disk storage
        if (isCoverRemoved || (artBytes != null && artBytes.isNotEmpty())) {
            CoverArtResolver.updateCachedArt(activity, cleanPath, artBytes, isCoverRemoved)
        }

        // 3. Persist metadata overrides permanently in TrackMetadataStore
        val hasCustomArt = (!isCoverRemoved && (artBytes != null && artBytes.isNotEmpty())) ||
                CoverArtResolver.getCustomArtFile(activity, cleanPath).exists()
        TrackMetadataStore.saveOverride(
            activity,
            cleanPath,
            title,
            artist,
            album,
            hasCustomArt = hasCustomArt,
            isCoverRemoved = isCoverRemoved
        )

        // 4. Also try native ID3 modification on the physical file
        try {
            Id3TagWriter.saveMetadata(activity, pathOrUri, title, artist, album, coverArtBase64, isCoverRemoved)
        } catch (e: Exception) {
            logEvent("METADATA", "Native Id3TagWriter: ${e.message}")
        }

        logEvent("METADATA", "Permanent metadata & artwork saved for: $title")
        return true
    }

    @JavascriptInterface
    fun updateMediaMetadata(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long, audioPath: String) {
        activity.updateServiceMetadata(title, artist, isPlaying, durationMs, positionMs, audioPath)
    }

    @JavascriptInterface
    fun updateMediaMetadata(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long) {
        activity.updateServiceMetadata(title, artist, isPlaying, durationMs, positionMs, "")
    }

    @JavascriptInterface
    fun hasAudioPermission(): Boolean = activity.checkAudioPermission()

    @JavascriptInterface
    fun requestAudioPermissions() {
        activity.runOnUiThread { activity.requestAudioPermission() }
    }

    @JavascriptInterface
    fun triggerHaptic(intensity: String) {
        // Zero vibration policy
    }
}
