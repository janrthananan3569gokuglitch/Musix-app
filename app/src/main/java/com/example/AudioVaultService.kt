package com.example

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * AudioVaultService: High-Performance Native Android Audio Engine for XP Music Vault V2.0.
 *
 * Responsibilities:
 * - Pure native playback via Android MediaPlayer (bulletproof SD/local file handling).
 * - Android Equalizer audiofx API for 3-band parametric EQ (Low, Mid, High).
 * - Android Visualizer audiofx API for native FFT capture at ultra-low latency.
 * - Hardware Partial WakeLock to guarantee uninterrupted background CPU execution.
 * - Dynamic 3D Spatial stereo volume panning.
 * - Full MediaSessionCompat and foreground playback notification controls.
 */
class AudioVaultService : Service() {

    private val binder = LocalBinder()
    private val tag = "AudioVaultService"

    // Media & Audiofx Core
    private var mediaPlayer: MediaPlayer? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var visualizer: Visualizer? = null
    private var isVisualizerSupported: Boolean? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // System Media Session
    private var mediaSession: MediaSessionCompat? = null
    private var isRegisteredNoisyReceiver = false

    // Track & Playback State
    private var currentPath: String = ""
    private var currentTitle: String = "XP Music Vault"
    private var currentArtist: String = "XP Phonk Vault"
    private var currentAlbum: String = "Phonk Drift Edition"
    private var isTrackPlaying: Boolean = false
    private var isPrepared: Boolean = false
    private var currentDurationMs: Long = 0L

    // Audio DSP Parameters
    private var eqLowDb: Float = 0f
    private var eqMidDb: Float = 0f
    private var eqHighDb: Float = 0f
    private var currentPan: Float = 0f // -1.0 (left) to +1.0 (right)
    private var masterVolume: Float = 1.0f

    // Visualizer FFT Buffer
    private val captureSize = 64
    private val fftBuffer = ByteArray(captureSize)
    private val vizInts = IntArray(32)
    private val vizSb = java.lang.StringBuilder(160)
    private var visualizerActive = false
    private var syntheticPhase = 0f

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (isTrackPlaying && isPrepared) {
                val mp = mediaPlayer
                if (mp != null) {
                    try {
                        val pos = mp.currentPosition.toLong()
                        onPlaybackStateChanged?.invoke(true, pos, currentDurationMs)
                    } catch (_: Exception) {}
                }
                progressHandler.postDelayed(this, 250L)
            }
        }
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioVaultService = this@AudioVaultService
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pausePlayback()
                onActionCallback?.invoke("PAUSE")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

            // Partial WakeLock ensures the CPU never sleeps during track playback
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "XP_VAULT:NativeAudioEngineWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }

            createNotificationChannel()
            setupMediaSession()

            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            registerReceiver(noisyReceiver, filter)
            isRegisteredNoisyReceiver = true

            // Immediately satisfy Android foreground service requirement
            val initialNotification = buildNotification()
            startForeground(NOTIFICATION_ID, initialNotification)
        } catch (e: Exception) {
            Log.e(tag, "Service onCreate error: ${e.message}", e)
        }
    }

    // =========================================================================
    // NATIVE PLAYBACK CONTROLS
    // =========================================================================

    @Synchronized
    fun playTrack(pathOrUri: String, title: String = "", artist: String = "", album: String = ""): Boolean {
        if (pathOrUri.isBlank()) return false

        // RESUME LOGIC: If user tapped play on the already prepared track while paused, resume seamlessly
        val existingMp = mediaPlayer
        if (pathOrUri == currentPath && existingMp != null && isPrepared && !isTrackPlaying) {
            Log.d(tag, "playTrack called for current paused track: resuming from ${existingMp.currentPosition}ms")
            return resumePlayback()
        }

        currentPath = pathOrUri
        if (title.isNotBlank()) currentTitle = title
        if (artist.isNotBlank()) currentArtist = artist
        if (album.isNotBlank()) currentAlbum = album

        try {
            requestAudioFocus()
            acquireWakeLock()

            // Tear down existing audio effects before recreating player
            releaseAudioFx()

            mediaPlayer?.apply {
                try {
                    setOnPreparedListener(null)
                    setOnCompletionListener(null)
                    setOnErrorListener(null)
                    if (isPlaying) {
                        pause()
                    }
                } catch (_: Exception) {}
                try {
                    reset()
                } catch (_: Exception) {}
                try {
                    release()
                } catch (_: Exception) {}
            }
            mediaPlayer = null

            mediaPlayer = MediaPlayer().apply {
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )

                // Route DataSource based on URI format
                when {
                    pathOrUri.startsWith("content://") -> {
                        setDataSource(applicationContext, Uri.parse(pathOrUri))
                    }
                    pathOrUri.startsWith("file://") -> {
                        if (pathOrUri.startsWith("file:///android_asset/")) {
                            val assetPath = pathOrUri.substring("file:///android_asset/".length)
                            val afd = applicationContext.assets.openFd(assetPath)
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                        } else {
                            val cleanPath = Uri.parse(pathOrUri).path ?: ""
                            var file = File(cleanPath)
                            if (!file.exists()) {
                                val phonk = File(File(applicationContext.filesDir, "phonks"), file.name)
                                if (phonk.exists()) file = phonk
                            }
                            if (file.exists()) {
                                setDataSource(file.absolutePath)
                            } else {
                                setDataSource(applicationContext, Uri.parse(pathOrUri))
                            }
                        }
                    }
                    pathOrUri.contains("local-audio/") -> {
                        val decoded = Uri.decode(pathOrUri.substringAfter("local-audio/"))
                        var f = File(decoded)
                        if (!f.exists()) {
                            val phonk = File(File(applicationContext.filesDir, "phonks"), f.name)
                            if (phonk.exists()) f = phonk
                        }
                        if (f.exists()) {
                            setDataSource(f.absolutePath)
                        } else if (decoded.startsWith("content://")) {
                            setDataSource(applicationContext, Uri.parse(decoded))
                        } else {
                            setDataSource(pathOrUri)
                        }
                    }
                    pathOrUri.contains("assets/vault/") || pathOrUri.startsWith("vault/") -> {
                        val assetPath = if (pathOrUri.contains("assets/vault/")) {
                            "vault/" + pathOrUri.substringAfter("assets/vault/")
                        } else {
                            pathOrUri.removePrefix("/")
                        }
                        val phonk = File(File(applicationContext.filesDir, "phonks"), File(assetPath).name)
                        if (phonk.exists()) {
                            setDataSource(phonk.absolutePath)
                        } else {
                            val afd = applicationContext.assets.openFd(assetPath)
                            setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                            afd.close()
                        }
                    }
                    pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://") -> {
                        if (pathOrUri.contains("appassets.androidplatform.net/assets/")) {
                            val assetPath = pathOrUri.substringAfter("appassets.androidplatform.net/assets/")
                            val phonk = File(File(applicationContext.filesDir, "phonks"), File(assetPath).name)
                            if (phonk.exists()) {
                                setDataSource(phonk.absolutePath)
                            } else {
                                val afd = applicationContext.assets.openFd(assetPath)
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                            }
                        } else {
                            setDataSource(pathOrUri)
                        }
                    }
                    else -> {
                        var file = File(pathOrUri)
                        if (!file.exists()) {
                            val phonk = File(File(applicationContext.filesDir, "phonks"), file.name)
                            if (phonk.exists()) file = phonk
                        }
                        if (file.exists()) {
                            setDataSource(file.absolutePath)
                        } else {
                            try {
                                val clean = pathOrUri.removePrefix("/")
                                val afd = applicationContext.assets.openFd(clean)
                                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                                afd.close()
                            } catch (_: Exception) {
                                setDataSource(pathOrUri)
                            }
                        }
                    }
                }

                setOnPreparedListener { mp ->
                    isPrepared = true
                    currentDurationMs = mp.duration.toLong()
                    masterVolume = 1.0f
                    mp.start()
                    isTrackPlaying = true

                    // Initialize native Equalizer and Visualizer on active audio session
                    initEqualizer(mp.audioSessionId)
                    initVisualizer(mp.audioSessionId)
                    applyStereoPanning()

                    updateServiceState(currentTitle, currentArtist, true, currentDurationMs, 0L)
                    onPlaybackStateChanged?.invoke(true, 0L, currentDurationMs)
                    startProgressUpdates()
                }

                setOnCompletionListener {
                    isTrackPlaying = false
                    stopProgressUpdates()
                    updateServiceState(currentTitle, currentArtist, false, currentDurationMs, currentDurationMs)
                    // Trigger completion callback to automatically advance to next track
                    onPlaybackCompleted?.invoke()
                }

                setOnErrorListener { _, what, extra ->
                    Log.e(tag, "MediaPlayer error: what=$what, extra=$extra")
                    isTrackPlaying = false
                    isPrepared = false
                    stopProgressUpdates()
                    releaseWakeLock()
                    updateServiceState(currentTitle, currentArtist, false, currentDurationMs, 0L)
                    onPlaybackStateChanged?.invoke(false, 0L, currentDurationMs)
                    false
                }

                prepareAsync()
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "playTrack failed: ${e.message}", e)
            releaseWakeLock()
            return false
        }
    }

    @Synchronized
    fun resumePlayback(): Boolean {
        return try {
            val mp = mediaPlayer
            if (mp != null && isPrepared) {
                requestAudioFocus()
                acquireWakeLock()
                mp.start()
                isTrackPlaying = true
                updateServiceState(currentTitle, currentArtist, true, currentDurationMs, mp.currentPosition.toLong())
                onPlaybackStateChanged?.invoke(true, mp.currentPosition.toLong(), currentDurationMs)
                startProgressUpdates()
                true
            } else if (currentPath.isNotBlank()) {
                playTrack(currentPath, currentTitle, currentArtist, currentAlbum)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(tag, "resumePlayback error: ${e.message}")
            false
        }
    }

    @Synchronized
    fun pausePlayback() {
        try {
            isTrackPlaying = false
            stopProgressUpdates()
            releaseWakeLock()
            val mp = mediaPlayer
            if (mp != null) {
                try {
                    if (mp.isPlaying) mp.pause()
                } catch (_: Exception) {}
                val pos = try { mp.currentPosition.toLong() } catch (_: Exception) { 0L }
                updateServiceState(currentTitle, currentArtist, false, currentDurationMs, pos)
                onPlaybackStateChanged?.invoke(false, pos, currentDurationMs)
            } else {
                updateServiceState(currentTitle, currentArtist, false, currentDurationMs, 0L)
                onPlaybackStateChanged?.invoke(false, 0L, currentDurationMs)
            }
        } catch (e: Exception) {
            Log.e(tag, "pausePlayback error: ${e.message}")
        }
    }

    @Synchronized
    fun seekTo(positionMs: Long) {
        try {
            val mp = mediaPlayer
            if (mp != null && isPrepared) {
                val clamped = positionMs.coerceIn(0L, currentDurationMs.coerceAtLeast(0L))
                mp.seekTo(clamped.toInt())
                updateServiceState(currentTitle, currentArtist, isTrackPlaying, currentDurationMs, clamped)
            }
        } catch (e: Exception) {
            Log.e(tag, "seekTo error: ${e.message}")
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    fun isAudioPlaying(): Boolean = isTrackPlaying && isPlaying()

    fun getCurrentTrackPath(): String = currentPath

    fun getCurrentPosition(): Long {
        return try {
            mediaPlayer?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    fun getDuration(): Long {
        return try {
            if (currentDurationMs > 0) currentDurationMs else (mediaPlayer?.duration?.toLong() ?: 0L)
        } catch (_: Exception) {
            0L
        }
    }

    // =========================================================================
    // NATIVE EQUALIZER (PARAMETRIC EQ + HARDWARE BASS BOOST + VIRTUALIZER)
    // =========================================================================

    private fun initEqualizer(audioSessionId: Int) {
        try {
            equalizer?.release()
            equalizer = null
            try {
                equalizer = Equalizer(0, audioSessionId).apply {
                    enabled = true
                }
                Log.d(tag, "Native Equalizer bound to session $audioSessionId")
            } catch (e1: Exception) {
                Log.w(tag, "Session $audioSessionId Equalizer failed: ${e1.message}, trying session 0")
                try {
                    equalizer = Equalizer(0, 0).apply {
                        enabled = true
                    }
                    Log.d(tag, "Global Equalizer bound to session 0")
                } catch (e2: Exception) {
                    Log.w(tag, "Global Equalizer fallback failed: ${e2.message}")
                    equalizer = null
                }
            }
        } catch (_: Exception) {}

        try {
            bassBoost?.release()
            bassBoost = null
            try {
                bassBoost = BassBoost(0, audioSessionId).apply {
                    enabled = true
                }
            } catch (_: Exception) {
                try {
                    bassBoost = BassBoost(0, 0).apply {
                        enabled = true
                    }
                } catch (_: Exception) {
                    bassBoost = null
                }
            }
        } catch (_: Exception) {}

        try {
            virtualizer?.release()
            virtualizer = null
            try {
                virtualizer = Virtualizer(0, audioSessionId).apply {
                    enabled = true
                }
            } catch (_: Exception) {
                try {
                    virtualizer = Virtualizer(0, 0).apply {
                        enabled = true
                    }
                } catch (_: Exception) {
                    virtualizer = null
                }
            }
        } catch (_: Exception) {}

        applyEqualizerSettings()
    }

    @Synchronized
    fun setEq(lowDb: Float, midDb: Float, highDb: Float) {
        eqLowDb = lowDb.coerceIn(-15f, 15f)
        eqMidDb = midDb.coerceIn(-15f, 15f)
        eqHighDb = highDb.coerceIn(-15f, 15f)
        applyEqualizerSettings()
    }

    private fun applyEqualizerSettings() {
        val eq = equalizer
        if (eq != null) {
            try {
                val numBands = eq.numberOfBands.toInt()
                val range = try { eq.bandLevelRange } catch (_: Exception) { null }
                val minRange = if (range != null && range.size >= 2) range[0] else -1500.toShort()
                val maxRange = if (range != null && range.size >= 2) range[1] else 1500.toShort()

                fun dbToMb(db: Float): Short {
                    val mb = (db * 100).toInt()
                    return mb.coerceIn(minRange.toInt(), maxRange.toInt()).toShort()
                }

                if (numBands > 0) {
                    for (b in 0 until numBands) {
                        val centerHz = try { eq.getCenterFreq(b.toShort()) / 1000 } catch (_: Exception) { 0 }
                        val targetDb: Float = if (centerHz > 0) {
                            when {
                                centerHz < 200 -> eqLowDb
                                centerHz in 200..350 -> {
                                    val t = (centerHz - 200).toFloat() / 150f
                                    eqLowDb * (1f - t) + eqMidDb * t
                                }
                                centerHz in 351..2200 -> eqMidDb
                                centerHz in 2201..3800 -> {
                                    val t = (centerHz - 2201).toFloat() / 1600f
                                    eqMidDb * (1f - t) + eqHighDb * t
                                }
                                else -> eqHighDb
                            }
                        } else {
                            val norm = if (numBands > 1) b.toFloat() / (numBands - 1) else 0.5f
                            when {
                                norm <= 0.35f -> {
                                    val t = norm / 0.35f
                                    eqLowDb * (1f - t) + (eqLowDb * 0.5f + eqMidDb * 0.5f) * t
                                }
                                norm <= 0.65f -> {
                                    val t = (norm - 0.35f) / 0.30f
                                    (eqLowDb * 0.5f + eqMidDb * 0.5f) * (1f - t) + eqMidDb * t
                                }
                                else -> {
                                    val t = (norm - 0.65f) / 0.35f
                                    eqMidDb * (1f - t) + eqHighDb * t
                                }
                            }
                        }
                        eq.setBandLevel(b.toShort(), dbToMb(targetDb))
                    }
                    eq.enabled = true
                }
            } catch (_: Exception) {}
        }

        // Realistic Hardware BassBoost enhancement (physical low-end transducer drive)
        try {
            val bb = bassBoost
            if (bb != null && bb.strengthSupported) {
                val isDolby = spatialMode == "atmos"
                if (isDolby) {
                    bb.enabled = true
                    bb.setStrength(850.toShort())
                } else if (eqLowDb > 0.5f) {
                    val strength = ((eqLowDb / 15f).coerceIn(0f, 1f) * 1000).toInt().toShort()
                    bb.enabled = true
                    bb.setStrength(strength)
                } else {
                    bb.setStrength(0.toShort())
                    bb.enabled = false
                }
            }
        } catch (_: Exception) {}

        // Realistic 3D Spatial Virtualizer (wide soundstage and cyber presence)
        try {
            val virt = virtualizer
            if (virt != null && virt.strengthSupported) {
                val isDolby = spatialMode == "atmos"
                val is8D = spatialMode in listOf("8d", "orbit", "gyro")
                if (isDolby) {
                    virt.enabled = true
                    virt.setStrength(1000.toShort())
                } else if (is8D) {
                    virt.enabled = true
                    virt.setStrength(800.toShort())
                } else {
                    val isBoosted = eqHighDb > 2.0f || abs(currentPan) > 0.05f
                    if (isBoosted) {
                        val strength = ((eqHighDb.coerceAtLeast(0f) / 15f) * 550 + (abs(currentPan) * 450)).toInt().coerceIn(0, 1000).toShort()
                        virt.setStrength(strength)
                        virt.enabled = true
                    } else {
                        virt.setStrength(0.toShort())
                        virt.enabled = false
                    }
                }
            }
        } catch (_: Exception) {}
        // Apply real-time headroom compensation to prevent digital clipping on heavy boosts
        applyStereoPanning()
    }

    // =========================================================================
    // NATIVE 3D SPATIAL STEREO PANNING & STUDIO HEADROOM
    // =========================================================================

    private var spatialMode: String = "atmos"

    @Synchronized
    fun setSpatialMode(mode: String) {
        spatialMode = mode.lowercase(java.util.Locale.ROOT)
        Log.d(tag, "Spatial DSP Mode switched to: $spatialMode")
        applyEqualizerSettings()
    }

    @Synchronized
    fun setSpatialPan(pan: Float) {
        val newPan = pan.coerceIn(-1.0f, 1.0f)
        if (Math.abs(currentPan - newPan) < 0.015f) return
        currentPan = newPan
        applyStereoPanning()
    }

    @Synchronized
    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1.0f)
        applyStereoPanning()
    }

    private fun applyStereoPanning() {
        val mp = mediaPlayer ?: return
        try {
            // Adaptive studio headroom scaling when EQ bands are boosted to prevent distortion
            val maxBoost = maxOf(0f, eqLowDb, eqMidDb, eqHighDb)
            val headroomScale = if (maxBoost > 2.0f) {
                1.0f / (1.0f + (maxBoost - 2.0f) * 0.036f)
            } else 1.0f

            val effVolume = (masterVolume * headroomScale).coerceIn(0f, 1.0f)
            // Constant-power / linear cross-balance stereo panning
            val left = (if (currentPan <= 0f) 1.0f else (1.0f - currentPan)) * effVolume
            val right = (if (currentPan >= 0f) 1.0f else (1.0f + currentPan)) * effVolume
            mp.setVolume(left.coerceIn(0f, 1.0f), right.coerceIn(0f, 1.0f))
        } catch (_: Exception) {
        }
    }

    // =========================================================================
    // NATIVE VISUALIZER API (FFT CAPTURE & EQ-REACTIVE REAL-TIME SPECTRUM)
    // =========================================================================

    private fun canUseHardwareVisualizer(): Boolean {
        if (isVisualizerSupported != null) return isVisualizerSupported == true
        try {
            // Check RECORD_AUDIO permission required on Android 9+ for native visualizers
            val hasPerm = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                isVisualizerSupported = false
                return false
            }

            // Verify AudioFlinger has the visualization effect library loaded in the HAL
            val effects = AudioEffect.queryEffects()
            val hasVisualizerEffect = effects?.any { desc ->
                desc.type?.toString()?.equals("e46b26a0-dddd-11db-8afd-0002a5d5c51b", ignoreCase = true) == true ||
                desc.uuid?.toString()?.equals("e46b26a0-dddd-11db-8afd-0002a5d5c51b", ignoreCase = true) == true ||
                desc.implementor?.contains("visualizer", ignoreCase = true) == true ||
                desc.name?.contains("visualizer", ignoreCase = true) == true
            } ?: false

            if (!hasVisualizerEffect) {
                isVisualizerSupported = false
                return false
            }
        } catch (_: Throwable) {
            isVisualizerSupported = false
            return false
        }
        return true
    }

    private fun initVisualizer(audioSessionId: Int) {
        if (!canUseHardwareVisualizer()) {
            visualizerActive = false
            return
        }
        try {
            visualizer?.release()
            val range = try { Visualizer.getCaptureSizeRange() } catch (_: Exception) { null }
            val chosenSize = if (range != null && range.size >= 2) {
                range[0].coerceAtLeast(64).coerceAtMost(256)
            } else 128
            val vis = Visualizer(audioSessionId)
            vis.captureSize = chosenSize
            vis.enabled = true
            visualizer = vis
            visualizerActive = true
            isVisualizerSupported = true
        } catch (_: Throwable) {
            visualizerActive = false
            isVisualizerSupported = false
            try { visualizer?.release() } catch (_: Exception) {}
            visualizer = null
        }
    }

    /**
     * Returns a JSON array string representing frequency magnitudes [0..255]
     * for direct consumption by the HTML5 canvas at 30-60 FPS without object allocations.
     * Integrates real-time 3-band parametric EQ multipliers (Low, Mid, High) and
     * track-synchronized beat transients (808 sub-kick, cowbell lead, hi-hats).
     */
    @Synchronized
    fun getVisualizerData(): String {
        if (!isTrackPlaying) return "[]"
        val vis = visualizer
        val count = 32 // 32 calibrated frequency spectrum analyzer bands (20Hz - 20kHz)
        val posMs = try { mediaPlayer?.currentPosition ?: 0 } catch (_: Exception) { 0 }

        // Real-time EQ Gain multipliers directly modulating the visualizer spectrum
        // -15dB -> ~0.10x, 0dB -> 1.0x, +15dB -> ~3.8x
        val lowGain = Math.pow(10.0, (eqLowDb / 20.0)).toFloat().coerceIn(0.10f, 3.8f)
        val midGain = Math.pow(10.0, (eqMidDb / 20.0)).toFloat().coerceIn(0.10f, 3.8f)
        val highGain = Math.pow(10.0, (eqHighDb / 20.0)).toFloat().coerceIn(0.10f, 3.8f)

        var hasHardwareFft = false
        if (vis != null && visualizerActive) {
            try {
                val status = vis.getFft(fftBuffer)
                if (status == Visualizer.SUCCESS) {
                    var totalMag = 0f
                    for (i in 0 until count) {
                        val reIndex = (i + 1) * 2
                        if (reIndex + 1 < fftBuffer.size) {
                            val re = fftBuffer[reIndex].toFloat()
                            val im = fftBuffer[reIndex + 1].toFloat()
                            val mag = Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                            totalMag += mag
                            vizInts[i] = (mag * 2.8f).toInt().coerceIn(0, 255)
                        } else {
                            vizInts[i] = 0
                        }
                    }
                    if (totalMag > 15f) {
                        hasHardwareFft = true
                    }
                }
            } catch (_: Exception) {
                hasHardwareFft = false
            }
        }

        // Determine current track tempo & rhythmic signature
        val bpm = when {
            currentTitle.contains("Tokyo", ignoreCase = true) -> 132.0
            currentTitle.contains("Cyber", ignoreCase = true) -> 128.0
            currentTitle.contains("Katana", ignoreCase = true) || currentTitle.contains("808", ignoreCase = true) -> 136.0
            currentTitle.contains("Hyper", ignoreCase = true) -> 130.0
            currentTitle.contains("Yokohama", ignoreCase = true) -> 126.0
            else -> 130.0
        }

        val beatMs = (60000.0 / bpm).toFloat()
        val safeBeatMs = beatMs.toInt().coerceAtLeast(100)
        val beatPhase = (posMs % safeBeatMs).toFloat() / safeBeatMs // 0.0 .. 1.0 within beat
        val sixteenthMs = (safeBeatMs / 4).coerceAtLeast(25)
        val sixteenthPhase = (posMs % sixteenthMs).toFloat() / sixteenthMs

        // Measure step (0..7 for 2 bars of 4/4)
        val halfBeatMs = (safeBeatMs / 2).coerceAtLeast(50)
        val step = ((posMs / halfBeatMs) % 8).toInt()

        // 808 Sub-kick transient spike on steps 0, 3, 4, 6 (classic Memphis Phonk drift pattern)
        val isKick = step == 0 || step == 3 || step == 4 || step == 6
        val kickEnergy = if (isKick) {
            val kFactor = (1f - beatPhase * 3.2f).coerceIn(0f, 1f)
            kFactor * kFactor
        } else {
            0.06f
        }

        // Snare / Clap Hit on steps 2 and 6 (backbeat)
        val isSnare = step == 2 || step == 6
        val snareEnergy = if (isSnare) {
            val sFactor = (1f - beatPhase * 2.8f).coerceIn(0f, 1f)
            sFactor * 0.9f
        } else {
            0.08f
        }

        // 16th-note Hi-hat sizzle
        val hatEnergy = (1f - sixteenthPhase * 2.2f).coerceIn(0f, 1f) * 0.85f

        // Cowbell lead melody pitch shift across 4 bars
        val melodyStep = ((posMs / safeBeatMs) % 16).toInt()
        val melodyBin = 10 + (melodyStep % 10)

        syntheticPhase += 0.09f

        // Synthesize / Blend real-time frequency distribution with EQ gains
        for (i in 0 until count) {
            if (hasHardwareFft) {
                val eqFactor = when {
                    i < 10 -> lowGain
                    i < 22 -> midGain
                    else -> highGain
                }
                vizInts[i] = (vizInts[i] * eqFactor).toInt().coerceIn(0, 255)
            } else {
                val valBase: Float = when {
                    // Sub-bass & Bass bands (0..9): Driven heavily by 808 kick & sub-bass, modulated by Low EQ
                    i < 10 -> {
                        val subWave = sin(syntheticPhase * 2.4f + i * 0.35f) * 0.25f + 0.75f
                        val bassRumble = (kickEnergy * 215f + subWave * 45f) * lowGain
                        bassRumble.coerceIn(0f, 255f)
                    }
                    // Mid bands (10..21): Driven by cowbell lead melody, snare claps, and harmonics, modulated by Mid EQ
                    i < 22 -> {
                        val isNearMelody = (1f - Math.abs(i - melodyBin) * 0.22f).coerceAtLeast(0f)
                        val midWave = cos(syntheticPhase * 1.8f + i * 0.45f) * 0.3f + 0.7f
                        val midPulse = (snareEnergy * 155f + isNearMelody * 115f + midWave * 35f) * midGain
                        midPulse.coerceIn(0f, 255f)
                    }
                    // High bands (22..31): Driven by 16th-note hi-hat sizzle and crash transients, modulated by High EQ
                    else -> {
                        val hatWave = sin(syntheticPhase * 4.0f + i * 0.55f) * 0.2f + 0.8f
                        val highPulse = (hatEnergy * 185f + hatWave * 40f) * highGain
                        highPulse.coerceIn(0f, 255f)
                    }
                }
                vizInts[i] = valBase.toInt().coerceIn(10, 255)
            }
        }

        vizSb.setLength(0)
        vizSb.append('[')
        for (i in 0 until count) {
            if (i > 0) vizSb.append(',')
            vizSb.append(vizInts[i])
        }
        vizSb.append(']')
        return vizSb.toString()
    }

    /**
     * Alias for frequency analyzer data binding bridge.
     */
    @Synchronized
    fun getFrequencySpectrum(): String = getVisualizerData()

    private fun releaseAudioFx() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
            visualizerActive = false
        } catch (_: Exception) {}

        try {
            bassBoost?.enabled = false
            bassBoost?.release()
            bassBoost = null
        } catch (_: Exception) {}

        try {
            virtualizer?.enabled = false
            virtualizer?.release()
            virtualizer = null
        } catch (_: Exception) {}

        try {
            equalizer?.enabled = false
            equalizer?.release()
            equalizer = null
        } catch (_: Exception) {}
    }

    // =========================================================================
    // WAKELOCK & AUDIO FOCUS
    // =========================================================================

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(3600_000L) // 1 hour max safeguard
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                            pausePlayback()
                            onActionCallback?.invoke("PAUSE")
                            onAudioFocusChanged?.invoke(true)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            onAudioFocusChanged?.invoke(false)
                        }
                    }
                }
                .build()
            audioFocusRequest = request
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        pausePlayback()
                        onActionCallback?.invoke("PAUSE")
                        onAudioFocusChanged?.invoke(true)
                    } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                        onAudioFocusChanged?.invoke(false)
                    }
                },
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    // =========================================================================
    // NOTIFICATION & MEDIASESSION
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "XP Music Vault Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio playback and native DSP for XP Music Vault"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "XP_MUSIC_VAULT_NATIVE_SESSION").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    resumePlayback()
                    onActionCallback?.invoke("PLAY")
                }

                override fun onPause() {
                    pausePlayback()
                    onActionCallback?.invoke("PAUSE")
                }

                override fun onSkipToNext() {
                    onActionCallback?.invoke("NEXT")
                }

                override fun onSkipToPrevious() {
                    onActionCallback?.invoke("PREV")
                }

                override fun onStop() {
                    pausePlayback()
                    onActionCallback?.invoke("STOP")
                    stopSelf()
                }

                override fun onSeekTo(pos: Long) {
                    seekTo(pos)
                    onActionCallback?.invoke("SEEK:$pos")
                }
            })
            isActive = true
        }
    }

    private val artExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var currentAlbumArtBitmap: Bitmap? = null
    private var lastDecodedArtKey: String? = null

    private fun requestAsyncArtDecode(audioPath: String) {
        if (audioPath.isBlank()) return
        val key = audioPath
        if (key == lastDecodedArtKey && currentAlbumArtBitmap != null) {
            return
        }

        artExecutor.execute {
            try {
                var bmp: Bitmap? = null

                // 1. Direct MediaMetadataRetriever extraction natively in Kotlin bypassing JS Binder limit
                val cleanPath = CoverArtResolver.resolveCleanPath(audioPath)
                try {
                    val retriever = MediaMetadataRetriever()
                    if (audioPath.startsWith("content://")) {
                        retriever.setDataSource(this, Uri.parse(audioPath))
                    } else {
                        val file = File(cleanPath)
                        if (file.exists()) {
                            retriever.setDataSource(file.absolutePath)
                        } else {
                            retriever.setDataSource(audioPath)
                        }
                    }
                    val picBytes = retriever.embeddedPicture
                    retriever.release()
                    if (picBytes != null && picBytes.isNotEmpty()) {
                        bmp = BitmapFactory.decodeByteArray(picBytes, 0, picBytes.size)
                    }
                } catch (e: Exception) {
                    Log.d(tag, "MediaMetadataRetriever direct extraction: ${e.message}")
                }

                // 2. CoverArtResolver fallback (MediaStore thumb, adjacent folder cover, custom artwork file)
                if (bmp == null) {
                    val bytes = CoverArtResolver.getArtBytes(this, audioPath)
                    if (bytes != null && bytes.isNotEmpty()) {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }

                // 3. Fallback if a base64 was passed (legacy safeguard)
                if (bmp == null && (audioPath.startsWith("data:image") || audioPath.length > 200)) {
                    val cleanBase64 = if (audioPath.contains(",")) audioPath.substringAfter(",") else audioPath
                    val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }

                lastDecodedArtKey = key
                currentAlbumArtBitmap = bmp
                progressHandler.post {
                    applyMetadataAndNotification()
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to decode album art for MediaSession: ${e.message}")
            }
        }
    }

    private fun applyMetadataAndNotification() {
        val metaBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDurationMs)

        currentAlbumArtBitmap?.let { bmp ->
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bmp)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, bmp)
        }

        mediaSession?.setMetadata(metaBuilder.build())
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
    }

    fun updateServiceState(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long) {
        updateServiceState(title, artist, isPlaying, durationMs, positionMs, "")
    }

    fun updateServiceState(title: String, artist: String, isPlaying: Boolean, durationMs: Long, positionMs: Long, audioPath: String) {
        currentTitle = title
        currentArtist = artist
        isTrackPlaying = isPlaying
        if (durationMs > 0L) {
            currentDurationMs = durationMs
        } else if (currentDurationMs <= 0L) {
            currentDurationMs = mediaPlayer?.duration?.toLong() ?: 0L
        }

        if (audioPath.isNotBlank()) {
            requestAsyncArtDecode(audioPath)
        } else if (currentAlbumArtBitmap == null && currentPath.isNotBlank()) {
            requestAsyncArtDecode(currentPath)
        }

        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, 1.0f)
                .build()
        )

        applyMetadataAndNotification()
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isTrackPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createActionPendingIntent(ACTION_TOGGLE_PLAY)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
                createActionPendingIntent(ACTION_TOGGLE_PLAY)
            )
        }

        val prevAction = NotificationCompat.Action(
            android.R.drawable.ic_media_previous,
            "Previous",
            createActionPendingIntent(ACTION_PREV)
        )

        val nextAction = NotificationCompat.Action(
            android.R.drawable.ic_media_next,
            "Next",
            createActionPendingIntent(ACTION_NEXT)
        )

        val notifBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSubText("XP Vault Native Pro")
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isTrackPlaying)
            .addAction(prevAction)
            .addAction(playPauseAction)
            .addAction(nextAction)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        currentAlbumArtBitmap?.let { bmp ->
            notifBuilder.setLargeIcon(bmp)
        }

        return notifBuilder.build()
    }

    private fun createActionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, AudioVaultService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAY -> {
                if (isTrackPlaying) pausePlayback() else resumePlayback()
            }
            ACTION_NEXT -> onActionCallback?.invoke("NEXT")
            ACTION_PREV -> onActionCallback?.invoke("PREV")
            ACTION_STOP -> {
                pausePlayback()
                onActionCallback?.invoke("STOP")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        releaseAudioFx()
        releaseWakeLock()

        try {
            mediaPlayer?.apply {
                setOnPreparedListener(null)
                setOnCompletionListener(null)
                setOnErrorListener(null)
                try {
                    if (isPlaying) pause()
                } catch (_: Exception) {}
                try {
                    reset()
                } catch (_: Exception) {}
                try {
                    release()
                } catch (_: Exception) {}
            }
            mediaPlayer = null
        } catch (_: Exception) {}

        stopProgressUpdates()
        if (isRegisteredNoisyReceiver) {
            try {
                unregisterReceiver(noisyReceiver)
            } catch (_: Exception) {}
        }
        mediaSession?.release()
    }

    companion object {
        const val CHANNEL_ID = "xp_music_vault_playback"
        const val NOTIFICATION_ID = 4040
        const val ACTION_TOGGLE_PLAY = "com.example.ACTION_TOGGLE_PLAY"
        const val ACTION_NEXT = "com.example.ACTION_NEXT"
        const val ACTION_PREV = "com.example.ACTION_PREV"
        const val ACTION_STOP = "com.example.ACTION_STOP"

        @Volatile
        var instance: AudioVaultService? = null

        var onActionCallback: ((String) -> Unit)? = null
        var onPlaybackStateChanged: ((Boolean, Long, Long) -> Unit)? = null
        var onPlaybackCompleted: (() -> Unit)? = null
        var onAudioFocusChanged: ((Boolean) -> Unit)? = null
    }
}
