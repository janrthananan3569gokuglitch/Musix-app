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
                activity.evaluateJs("window.onNativePlaybackCompleted && window.onNativePlaybackCompleted()")
            }
        }
        AudioVaultService.onPlaybackStateChanged = { playing, pos, dur ->
            activity.runOnUiThread {
                activity.evaluateJs("window.onNativePlaybackState && window.onNativePlaybackState($playing, $pos, $dur)")
            }
        }
        AudioVaultService.onActionCallback = { action ->
            activity.runOnUiThread {
                activity.evaluateJs("window.onNativeMediaAction && window.onNativeMediaAction('$action')")
            }
        }
    }

    // =========================================================================
    // NATIVE AUDIO PLAYBACK JAVASCRIPT INTERFACES
    // =========================================================================

    @JavascriptInterface
    fun playTrack(pathOrUri: String, title: String, artist: String, album: String): Boolean {
        logEvent("PLAY", "playTrack invoked for: $title ($pathOrUri)")
        return getAudioService()?.playTrack(pathOrUri, title, artist, album) ?: false
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

        // Apply real-time 3D spatial panning directly into native audio engine ONLY if enabled
        if (spatialMode != "center") {
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

    @JavascriptInterface
    fun scanPhonks(): String = scanPhonksFolder()

    @JavascriptInterface
    fun scanCustomPath(path: String): String {
        if (path.isNotBlank()) configuredPath = path.trim()
        return scanPhonksFolder()
    }

    @JavascriptInterface
    fun scanPhonksFolder(): String {
        val targetPath = configuredPath
        logEvent("SCAN", "Native zero-duplicate scan triggered for: $targetPath")
        val jsonArray = JSONArray()

        val seenPaths = HashSet<String>()
        val seenFileNames = HashSet<String>()
        val seenNormalizedKeys = HashSet<String>()
        val seenTitleKeys = HashSet<String>()

        val exactMatches = ArrayList<JSONObject>()
        val otherMatches = ArrayList<JSONObject>()
        val audioExtensions = setOf("flac", "wav", "aiff", "aif", "aifc", "alac", "ape", "wv", "pcm", "m4a", "mp3", "aac", "ogg", "opus", "wma")

        // 1. Direct Primary Scan on Target Folder
        val primaryDir = resolveTargetDirectory(targetPath)
        var directId = 1000L

        fun scanDir(dir: File, isTarget: Boolean) {
            try {
                if (!dir.exists() || !dir.isDirectory) return
                val files = dir.listFiles() ?: return
                for (file in files) {
                    if (file.isDirectory && !file.name.startsWith(".")) {
                        scanDir(file, isTarget)
                    } else if (file.isFile) {
                        val ext = file.extension.lowercase(Locale.ROOT)
                        if (audioExtensions.contains(ext)) {
                            val path = file.absolutePath
                            val pathLower = path.lowercase(Locale.ROOT)
                            val fNameLower = file.name.lowercase(Locale.ROOT)
                            val title = file.nameWithoutExtension.ifBlank { "Phonk Track" }
                            val normKey = normalizeTrackKey(title, file.name)
                            val titleKey = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

                            if (seenPaths.contains(pathLower) ||
                                seenFileNames.contains(fNameLower) ||
                                (normKey.isNotBlank() && seenNormalizedKeys.contains(normKey)) ||
                                (titleKey.isNotBlank() && seenTitleKeys.contains(titleKey))) {
                                continue
                            }

                            seenPaths.add(pathLower)
                            seenFileNames.add(fNameLower)
                            if (normKey.isNotBlank()) seenNormalizedKeys.add(normKey)
                            if (titleKey.isNotBlank()) seenTitleKeys.add(titleKey)

                            val isExact = isTarget || path.startsWith(targetPath) || path.contains("/phonks", ignoreCase = true)
                            val parentName = file.parentFile?.name ?: ""
                            val albumName = if (parentName.isNotBlank() && !parentName.equals("phonks", true) && !parentName.equals("Music", true) && !parentName.equals("Download", true)) {
                                parentName
                            } else {
                                "Phonk Master Series"
                            }
                            val bitDepth = if (ext in listOf("flac", "alac", "aiff", "aif", "ape", "wv")) 24 else 16

                            val item = JSONObject().apply {
                                put("id", directId++)
                                put("path", path)
                                put("contentUri", "file://$path")
                                put("streamUrl", "https://appassets.androidplatform.net/local-audio/${Uri.encode(path)}")
                                put("title", title)
                                put("artist", if (isExact) "SD Phonk Vault" else "XP Phonk Vault")
                                put("album", albumName)
                                put("bitDepth", bitDepth)
                                put("duration", 0L)
                                put("size", file.length())
                                put("lastModified", file.lastModified())
                                put("mimeType", resolveAudioMime(path))
                                put("isExactTarget", isExact)
                            }

                            if (isExact) exactMatches.add(item) else otherMatches.add(item)
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (primaryDir != null && primaryDir.exists()) {
            scanDir(primaryDir, isTarget = true)
        }

        // 2. Direct Scan on Common Android Music Folders (SD Card, Music, Download)
        val commonDirs = listOf(
            File("/storage/emulated/0/Music"),
            File("/storage/emulated/0/Download"),
            File("/storage/emulated/0/phonks"),
            File("/storage/emulated/0/Phonks"),
            File("/sdcard/Music"),
            File("/sdcard/Download"),
            File("/sdcard/phonks")
        )
        for (cDir in commonDirs) {
            if (cDir.exists() && cDir.isDirectory && cDir != primaryDir) {
                scanDir(cDir, isTarget = false)
            }
        }

        // Also check any mounted SD card roots
        try {
            val storageRoot = File("/storage")
            if (storageRoot.exists() && storageRoot.isDirectory) {
                storageRoot.listFiles()?.forEach { disk ->
                    if (disk.isDirectory && disk.name != "emulated" && disk.name != "self") {
                        val diskPhonks = File(disk, "phonks")
                        val diskMusic = File(disk, "Music")
                        if (diskPhonks.exists() && diskPhonks != primaryDir) scanDir(diskPhonks, isTarget = false)
                        if (diskMusic.exists() && diskMusic != primaryDir) scanDir(diskMusic, isTarget = false)
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Query MediaStore for deduplicated external tracks
        try {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DATA,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.MIME_TYPE
            )

            val sortOrder = "${MediaStore.Audio.Media.DATE_MODIFIED} DESC"
            val selection = "${MediaStore.Audio.Media.SIZE} > 10000"

            activity.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val titleCol = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val durationCol = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateCol = c.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val mimeCol = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)

                while (c.moveToNext()) {
                    val data = if (dataCol != -1) c.getString(dataCol) ?: "" else ""
                    val rawTitle = if (titleCol != -1) c.getString(titleCol) ?: "" else ""
                    val size = if (sizeCol != -1) c.getLong(sizeCol) else 0L

                    val pathLower = data.lowercase(Locale.ROOT)
                    val fName = if (data.isNotBlank()) File(data).name.lowercase(Locale.ROOT) else ""
                    val normKey = normalizeTrackKey(rawTitle, fName)
                    val titleKey = rawTitle.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

                    if ((pathLower.isNotBlank() && seenPaths.contains(pathLower)) ||
                        (fName.isNotBlank() && seenFileNames.contains(fName)) ||
                        (normKey.isNotBlank() && seenNormalizedKeys.contains(normKey)) ||
                        (titleKey.isNotBlank() && seenTitleKeys.contains(titleKey))) {
                        continue
                    }

                    if (pathLower.isNotBlank()) seenPaths.add(pathLower)
                    if (fName.isNotBlank()) seenFileNames.add(fName)
                    if (normKey.isNotBlank()) seenNormalizedKeys.add(normKey)
                    if (titleKey.isNotBlank()) seenTitleKeys.add(titleKey)

                    val id = c.getLong(idCol)
                    val title = if (rawTitle.isNotBlank()) rawTitle else (File(data).nameWithoutExtension.ifBlank { "Audio Track" })
                    val artist = if (artistCol != -1) c.getString(artistCol) ?: "XP Phonk Vault" else "XP Phonk Vault"
                    val rawAlbum = if (albumCol != -1) c.getString(albumCol) ?: "" else ""
                    val album = if (rawAlbum.isNotBlank()) rawAlbum else "XP Phonk Vault"
                    val duration = if (durationCol != -1) c.getLong(durationCol) else 0L
                    val dateModified = if (dateCol != -1) c.getLong(dateCol) * 1000L else 0L
                    val rawMime = if (mimeCol != -1) c.getString(mimeCol) ?: "" else ""
                    val mime = if (rawMime.isNotBlank()) rawMime else resolveAudioMime(data)
                    val ext = if (data.isNotBlank()) File(data).extension.lowercase(Locale.ROOT) else ""
                    val bitDepth = if (ext in listOf("flac", "alac", "aiff", "aif", "ape", "wv") || mime.contains("flac")) 24 else 16

                    val contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val isExact = data.startsWith(targetPath) || data.contains("/phonks", ignoreCase = true)

                    val actualPath = if (data.isNotBlank()) data else contentUri
                    val streamUrl = if (data.isNotBlank()) "https://appassets.androidplatform.net/local-audio/${Uri.encode(data)}" else contentUri

                    val item = JSONObject().apply {
                        put("id", id)
                        put("path", actualPath)
                        put("contentUri", contentUri)
                        put("streamUrl", streamUrl)
                        put("title", title)
                        put("artist", artist)
                        put("album", album)
                        put("bitDepth", bitDepth)
                        put("duration", duration)
                        put("size", size)
                        put("lastModified", dateModified)
                        put("mimeType", mime)
                        put("isExactTarget", isExact)
                    }

                    if (isExact) exactMatches.add(item) else otherMatches.add(item)
                }
            }
        } catch (e: Exception) {
            logEvent("ERROR", "MediaStore query error: ${e.message}")
        }

        // 4. Ensure internal Starter Tracks are available if user tracks are sparse
        val starterDir = File(activity.filesDir, "phonks")
        if (!starterDir.exists() || (starterDir.listFiles()?.size ?: 0) < 5) {
            try {
                VaultTrackSynthesizer.ensureStarterTracks(activity)
            } catch (_: Exception) {}
        }
        val starterMatches = ArrayList<JSONObject>()
        if (starterDir.exists()) {
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

        if (exactMatches.isNotEmpty()) {
            // Target path has exact matches, show them first
            for (item in exactMatches) jsonArray.put(item)
        } else if (otherMatches.isNotEmpty()) {
            // Device has user music
            for (item in otherMatches) jsonArray.put(item)
            // If few tracks, append starter tracks so playlist is rich
            if (otherMatches.size < 3) {
                for (item in starterMatches) jsonArray.put(item)
            }
        } else {
            // No songs on device storage/emulator, show Starter Vault tracks
            for (item in starterMatches) jsonArray.put(item)
        }

        logEvent("SCAN", "Scan completed: ${jsonArray.length()} tracks loaded natively")
        return jsonArray.toString()
    }

    @JavascriptInterface
    fun scanMusicTracks(): String = scanPhonksFolder()

    @JavascriptInterface
    fun playSingleTrack(pathOrUri: String): Boolean = playTrackSimple(pathOrUri)

    // =========================================================================
    // METADATA & COVER ART
    // =========================================================================

    private fun hasEmbeddedPictureFrame(file: File): Boolean {
        val pLower = file.name.lowercase(Locale.ROOT)
        if (pLower.endsWith(".wav") || pLower.endsWith(".wave") || pLower.endsWith(".pcm") ||
            pLower.endsWith(".aiff") || pLower.endsWith(".aif") || pLower.endsWith(".ogg") || pLower.endsWith(".opus")) {
            return false
        }
        if (pLower.endsWith(".mp3")) {
            try {
                FileInputStream(file).use { fis ->
                    val header = ByteArray(10)
                    if (fis.read(header) < 10) return false
                    if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                        return false
                    }
                    val tagSize = (header[6].toInt() and 0x7F shl 21) or
                            (header[7].toInt() and 0x7F shl 14) or
                            (header[8].toInt() and 0x7F shl 7) or
                            (header[9].toInt() and 0x7F)
                    if (tagSize <= 0) return false
                    val scanLimit = minOf(tagSize, 256 * 1024)
                    val buffer = ByteArray(scanLimit)
                    val readBytes = fis.read(buffer)
                    if (readBytes <= 4) return false
                    for (i in 0 until readBytes - 4) {
                        if (buffer[i] == 'A'.code.toByte() && buffer[i+1] == 'P'.code.toByte() &&
                            buffer[i+2] == 'I'.code.toByte() && buffer[i+3] == 'C'.code.toByte()) {
                            return true
                        }
                        if (buffer[i] == 'P'.code.toByte() && buffer[i+1] == 'I'.code.toByte() &&
                            buffer[i+2] == 'C'.code.toByte()) {
                            return true
                        }
                    }
                    return false
                }
            } catch (_: Exception) {
                return false
            }
        } else if (pLower.endsWith(".flac")) {
            try {
                FileInputStream(file).use { fis ->
                    val magic = ByteArray(4)
                    if (fis.read(magic) < 4) return false
                    if (magic[0] != 'f'.code.toByte() || magic[1] != 'L'.code.toByte() ||
                        magic[2] != 'a'.code.toByte() || magic[3] != 'C'.code.toByte()) return false
                    var isLast = false
                    while (!isLast) {
                        val blockHeader = ByteArray(4)
                        if (fis.read(blockHeader) < 4) break
                        val b0 = blockHeader[0].toInt() and 0xFF
                        isLast = (b0 and 0x80) != 0
                        val blockType = b0 and 0x7F
                        val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                                (blockHeader[3].toInt() and 0xFF)
                        if (blockType == 6) return true
                        fis.skip(length.toLong())
                    }
                    return false
                }
            } catch (_: Exception) {
                return false
            }
        } else if (pLower.endsWith(".m4a") || pLower.endsWith(".mp4") || pLower.endsWith(".aac")) {
            try {
                FileInputStream(file).use { fis ->
                    val buf = ByteArray(minOf(file.length().toInt(), 128 * 1024))
                    val readBytes = fis.read(buf)
                    for (i in 0 until readBytes - 4) {
                        if (buf[i] == 'c'.code.toByte() && buf[i+1] == 'o'.code.toByte() &&
                            buf[i+2] == 'v'.code.toByte() && buf[i+3] == 'r'.code.toByte()) {
                            return true
                        }
                    }
                    return false
                }
            } catch (_: Exception) {
                return false
            }
        }
        return false
    }

    @JavascriptInterface
    fun getArtBase64(pathOrUri: String): String {
        if (pathOrUri.isBlank()) return ""
        artBase64Cache.get(pathOrUri)?.let { return it }

        val localFile = if (!pathOrUri.startsWith("content://") && !pathOrUri.startsWith("file:///android_asset/")) {
            val cleanPath = if (pathOrUri.startsWith("file://")) Uri.parse(pathOrUri).path ?: pathOrUri else pathOrUri
            File(cleanPath)
        } else null

        if (localFile != null && (!localFile.exists() || !localFile.isFile)) {
            artBase64Cache.put(pathOrUri, "")
            return ""
        }

        // 1. First check directory for folder cover image (cover.jpg, folder.jpg) - zero JNI overhead
        try {
            if (localFile != null) {
                val parentDir = localFile.parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    val candidateNames = listOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "album.jpg", "album.png", "art.jpg", "art.png")
                    for (cName in candidateNames) {
                        val cand = File(parentDir, cName)
                        if (cand.exists() && cand.isFile && cand.length() in 1024..5000000) {
                            val bytes = cand.readBytes()
                            val mime = if (cName.endsWith(".png")) "image/png" else "image/jpeg"
                            val base64 = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            artBase64Cache.put(pathOrUri, base64)
                            return base64
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Only invoke JNI MediaMetadataRetriever if file actually contains verified embedded picture
        val hasVerifiedPicture = if (localFile != null) {
            hasEmbeddedPictureFrame(localFile)
        } else if (pathOrUri.startsWith("content://")) {
            try {
                activity.contentResolver.openInputStream(Uri.parse(pathOrUri))?.use { input ->
                    val buf = ByteArray(64 * 1024)
                    val read = input.read(buf)
                    var found = false
                    for (i in 0 until read - 4) {
                        if ((buf[i] == 'A'.code.toByte() && buf[i+1] == 'P'.code.toByte() && buf[i+2] == 'I'.code.toByte() && buf[i+3] == 'C'.code.toByte()) ||
                            (buf[i] == 'c'.code.toByte() && buf[i+1] == 'o'.code.toByte() && buf[i+2] == 'v'.code.toByte() && buf[i+3] == 'r'.code.toByte())) {
                            found = true
                            break
                        }
                    }
                    found
                } ?: false
            } catch (_: Exception) { false }
        } else false

        if (hasVerifiedPicture) {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                if (pathOrUri.startsWith("content://")) {
                    retriever.setDataSource(activity, Uri.parse(pathOrUri))
                } else {
                    retriever.setDataSource(pathOrUri)
                }
                val artBytes = retriever.embeddedPicture
                if (artBytes != null && artBytes.isNotEmpty()) {
                    val base64 = "data:image/jpeg;base64," + Base64.encodeToString(artBytes, Base64.NO_WRAP)
                    artBase64Cache.put(pathOrUri, base64)
                    return base64
                }
            } catch (_: Exception) {
            } finally {
                try { retriever?.release() } catch (_: Exception) {}
            }
        }

        // Cache negative result so we never query again
        artBase64Cache.put(pathOrUri, "")
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
            activity.stopPlaybackService()
            activity.finishAffinity()
        }
    }

    @JavascriptInterface
    fun triggerFilePicker() {
        activity.runOnUiThread { activity.launchFilePicker() }
    }

    @JavascriptInterface
    fun updateMediaMetadata(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long) {
        activity.updateServiceMetadata(title, artist, isPlaying, durationMs, positionMs)
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
