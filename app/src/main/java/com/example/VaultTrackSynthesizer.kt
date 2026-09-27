package com.example

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * VaultTrackSynthesizer: High-Performance Procedural Audio Engine for XP Music Vault.
 *
 * Synthesizes high-fidelity 16-bit 44.1kHz stereo PCM WAV tracks with authentic
 * Phonk drift beats (deep 808 sub-bass, 808 cowbell leads, punchy snares, and sizzle hi-hats).
 *
 * Ensures the Vault always has tracks ready to play out of the box, even when no user
 * music exists on the emulator or device storage.
 */
object VaultTrackSynthesizer {

    private const val TAG = "TrackSynthesizer"
    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = 2

    data class TrackDefinition(
        val fileName: String,
        val title: String,
        val artist: String,
        val album: String,
        val bpm: Double,
        val durationSeconds: Double,
        val baseFreq: Double,
        val melodyNotes: DoubleArray,
        val style: String
    )

    private val DEFAULT_TRACKS = listOf(
        TrackDefinition(
            fileName = "Midnight_Tokyo_Drift.wav",
            title = "Midnight Tokyo Drift",
            artist = "Tokyo Phonk Syndicate",
            album = "XP Phonk Drift Vol. 1",
            bpm = 132.0,
            durationSeconds = 11.0,
            baseFreq = 55.0, // A1
            melodyNotes = doubleArrayOf(440.0, 523.25, 587.33, 622.25, 659.25, 587.33, 523.25, 440.0),
            style = "drift"
        ),
        TrackDefinition(
            fileName = "Neon_Cyberdrive.wav",
            title = "Neon Cyberdrive",
            artist = "Night Rider Vault",
            album = "Cyberpunk Overdrive",
            bpm = 128.0,
            durationSeconds = 11.25,
            baseFreq = 41.2, // E1
            melodyNotes = doubleArrayOf(329.63, 392.0, 440.0, 493.88, 587.33, 493.88, 392.0, 329.63),
            style = "cyber"
        ),
        TrackDefinition(
            fileName = "Shadow_Katana_808.wav",
            title = "Shadow Katana 808",
            artist = "Ghost Drift Core",
            album = "Osaka Midnight Session",
            bpm = 136.0,
            durationSeconds = 10.6,
            baseFreq = 36.7, // D1
            melodyNotes = doubleArrayOf(293.66, 349.23, 392.0, 440.0, 523.25, 440.0, 392.0, 349.23),
            style = "memphis"
        ),
        TrackDefinition(
            fileName = "Hyperdrive_Velocity.wav",
            title = "Hyperdrive Velocity",
            artist = "Phonk Master Series",
            album = "Bass District 99",
            bpm = 130.0,
            durationSeconds = 11.1,
            baseFreq = 46.25, // F#1
            melodyNotes = doubleArrayOf(369.99, 440.0, 493.88, 554.37, 659.25, 554.37, 493.88, 369.99),
            style = "wave"
        ),
        TrackDefinition(
            fileName = "Yokohama_Street_Night.wav",
            title = "Yokohama Street Night",
            artist = "XP Sound Engine",
            album = "Retro Street Drift",
            bpm = 126.0,
            durationSeconds = 11.4,
            baseFreq = 32.7, // C1
            melodyNotes = doubleArrayOf(261.63, 311.13, 349.23, 392.0, 466.16, 392.0, 311.13, 261.63),
            style = "lofi"
        )
    )

    fun ensureStarterTracks(context: Context): List<File> {
        val folder = File(context.filesDir, "phonks")
        if (!folder.exists()) {
            folder.mkdirs()
        }

        val generatedFiles = mutableListOf<File>()
        for (track in DEFAULT_TRACKS) {
            val file = File(folder, track.fileName)
            if (!file.exists() || file.length() < 100000L) {
                try {
                    synthesizeTrack(file, track)
                    Log.i(TAG, "Synthesized vault starter track: ${track.title} (${file.length()} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to synthesize ${track.fileName}: ${e.message}")
                }
            }
            if (file.exists() && file.length() > 0) {
                generatedFiles.add(file)
            }
        }
        return generatedFiles
    }

    private fun synthesizeTrack(file: File, def: TrackDefinition) {
        val totalSamples = (SAMPLE_RATE * def.durationSeconds).toInt()
        val totalAudioBytes = totalSamples * CHANNELS * 2
        val secPerBeat = 60.0 / def.bpm
        val secPer16th = secPerBeat / 4.0
        val random = Random(42)

        FileOutputStream(file).use { fos ->
            BufferedOutputStream(fos, 64 * 1024).use { out ->
                writeWavHeader(out, totalAudioBytes, SAMPLE_RATE, CHANNELS)

                val buffer = ByteArray(4096)
                var bufIdx = 0

                var kickPhase = 0.0
                var cowbellPhase1 = 0.0
                var cowbellPhase2 = 0.0
                var bassPhase = 0.0
                var leadPhase = 0.0

                for (i in 0 until totalSamples) {
                    val t = i.toDouble() / SAMPLE_RATE
                    val beatIndex = (t / secPerBeat).toInt()
                    val tInBeat = t % secPerBeat
                    val tInMeasure = t % (secPerBeat * 4.0)

                    // 1. Kick / 808 Bass Drum (Heavy pitch-swept sine + saturation)
                    // Kick triggers on beat 0, beat 1.75, beat 2.5, beat 3
                    val step16th = ((tInMeasure / secPer16th) % 16).toInt()
                    val tIn16th = tInMeasure % secPer16th
                    val isKickHit = step16th == 0 || step16th == 3 || step16th == 8 || step16th == 11
                    var kickSample = 0.0
                    if (isKickHit && tIn16th < 0.35) {
                        val decay = exp(-tIn16th * 18.0)
                        val pitch = def.baseFreq + 110.0 * exp(-tIn16th * 45.0)
                        kickPhase += 2.0 * PI * pitch / SAMPLE_RATE
                        var rawKick = sin(kickPhase) * decay * 0.95
                        // Soft clipping / saturation for punchy 808 distortion
                        rawKick = (rawKick * 1.35).coerceIn(-1.0, 1.0)
                        kickSample = rawKick
                    } else {
                        // Sustained sub-bass hum
                        bassPhase += 2.0 * PI * def.baseFreq / SAMPLE_RATE
                        kickSample = sin(bassPhase) * 0.25
                    }

                    // 2. Snare / Clap (White noise burst + tonal body on beats 1 and 3 in 0-indexed measures)
                    val isSnareBeat = (beatIndex % 4 == 1) || (beatIndex % 4 == 3)
                    var snareSample = 0.0
                    if (isSnareBeat && tInBeat < 0.22) {
                        val decay = exp(-tInBeat * 24.0)
                        val noise = (random.nextDouble() * 2.0 - 1.0) * 0.65
                        val snap = sin(2.0 * PI * 220.0 * tInBeat) * 0.35
                        snareSample = (noise + snap) * decay * 0.8
                    }

                    // 3. Hi-Hat (Sharp short noise on every 8th or 16th note)
                    var hatSample = 0.0
                    if (tIn16th < 0.05) {
                        val decay = exp(-tIn16th * 85.0)
                        val isAccent = step16th % 2 == 0
                        val vol = if (isAccent) 0.25 else 0.15
                        val noise = (random.nextDouble() * 2.0 - 1.0) * vol
                        hatSample = noise * decay
                    }

                    // 4. Phonk Cowbell Lead Synth (Classic 808 cowbell with two detuned oscillators: 540Hz & 800Hz ratio)
                    val noteIndex = ((t / (secPerBeat * 0.5)).toInt()) % def.melodyNotes.size
                    val noteFreq = def.melodyNotes[noteIndex]
                    val tInNote = t % (secPerBeat * 0.5)
                    var cowbellSample = 0.0
                    if (tInNote < 0.28) {
                        val decay = exp(-tInNote * 12.0)
                        val f1 = noteFreq
                        val f2 = noteFreq * 1.485
                        cowbellPhase1 += 2.0 * PI * f1 / SAMPLE_RATE
                        cowbellPhase2 += 2.0 * PI * f2 / SAMPLE_RATE

                        // Square-ish / Bandpass wave for classic Memphis cowbell character
                        val s1 = if (sin(cowbellPhase1) > 0.0) 0.5 else -0.5
                        val s2 = if (sin(cowbellPhase2) > 0.0) 0.5 else -0.5
                        var rawBell = (s1 * 0.5 + s2 * 0.5) * decay * 0.55
                        // Bandpass tone
                        leadPhase += 2.0 * PI * (noteFreq * 2.0) / SAMPLE_RATE
                        rawBell += sin(leadPhase) * decay * 0.15
                        cowbellSample = rawBell
                    }

                    // 5. Stereo Master Mix & Slight Stereo Spread
                    val mixMono = (kickSample * 0.75 + snareSample * 0.65 + hatSample * 0.45 + cowbellSample * 0.55)
                    // Master limiter
                    val limitedMono = (mixMono * 1.15).coerceIn(-0.95, 0.95)

                    // Slight stereo panned delays
                    val panL = (limitedMono + hatSample * 0.15).coerceIn(-0.98, 0.98)
                    val panR = (limitedMono - hatSample * 0.15).coerceIn(-0.98, 0.98)

                    val sampleIntL = (panL * 32767.0).toInt().coerceIn(-32768, 32767)
                    val sampleIntR = (panR * 32767.0).toInt().coerceIn(-32768, 32767)

                    // Left Channel (Little Endian)
                    buffer[bufIdx++] = (sampleIntL and 0xFF).toByte()
                    buffer[bufIdx++] = ((sampleIntL shr 8) and 0xFF).toByte()

                    // Right Channel (Little Endian)
                    buffer[bufIdx++] = (sampleIntR and 0xFF).toByte()
                    buffer[bufIdx++] = ((sampleIntR shr 8) and 0xFF).toByte()

                    if (bufIdx >= buffer.size) {
                        out.write(buffer, 0, bufIdx)
                        bufIdx = 0
                    }
                }

                if (bufIdx > 0) {
                    out.write(buffer, 0, bufIdx)
                }
                out.flush()
            }
        }
    }

    private fun writeWavHeader(out: OutputStream, totalAudioBytes: Int, sampleRate: Int, channels: Int) {
        val totalDataLen = totalAudioBytes + 36
        val byteRate = sampleRate * channels * 2
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = (channels * 2).toByte()
        header[33] = 0
        header[34] = 16
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioBytes and 0xff).toByte()
        header[41] = ((totalAudioBytes shr 8) and 0xff).toByte()
        header[42] = ((totalAudioBytes shr 16) and 0xff).toByte()
        header[43] = ((totalAudioBytes shr 24) and 0xff).toByte()
        out.write(header)
    }
}
