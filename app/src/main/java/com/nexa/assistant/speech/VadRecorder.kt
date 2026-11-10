package com.nexa.assistant.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/**
 * Registra dal microfono e capisce da solo quando hai finito di parlare
 * (rilevamento della voce in base al volume, con soglia adattata al rumore della stanza).
 */
class VadRecorder(
    private val maxSpeechMs: Int = 30_000,
    private val noSpeechTimeoutMs: Int = 8_000,
    private val endSilenceMs: Int = 1_300,
) {
    sealed interface Outcome {
        data class Speech(val pcm: ByteArray) : Outcome
        data object NoSpeech : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /** Bloccante: va chiamata da un thread in background. */
    @SuppressLint("MissingPermission")
    fun record(onLevel: (Float) -> Unit, onSpeechStart: () -> Unit): Outcome {
        cancelled = false
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 4),
            )
        } catch (e: Exception) {
            return Outcome.Failed("Microfono non disponibile: ${e.message}")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Outcome.Failed("Microfono non disponibile")
        }
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true } else null

        val out = ByteArrayOutputStream()
        // Teniamo ~300 ms di audio prima dell'inizio della voce, per non perdere la prima sillaba.
        val preRoll = ArrayDeque<ByteArray>()
        val frame = ByteArray(FRAME_BYTES)
        var noiseFloor = 0f
        var calibrationFrames = 0
        var speaking = false
        var speechMs = 0
        var silenceMs = 0
        var waitedMs = 0

        try {
            record.startRecording()
            while (!cancelled) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val rms = rms(frame, n)
                onLevel((rms / 3000f).coerceIn(0f, 1f))

                if (calibrationFrames < CALIBRATION_FRAMES) {
                    noiseFloor += rms / CALIBRATION_FRAMES
                    calibrationFrames++
                    preRoll.addLast(frame.copyOf(n))
                    // Se l'utente parla subito, la calibrazione misurerebbe la voce invece del rumore: mettiamo un tetto.
                    if (calibrationFrames == CALIBRATION_FRAMES) noiseFloor = noiseFloor.coerceAtMost(MAX_NOISE_FLOOR)
                    continue
                }
                val threshold = maxOf(noiseFloor * 2.8f, MIN_THRESHOLD)
                val loud = rms > threshold

                if (!speaking) {
                    preRoll.addLast(frame.copyOf(n))
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    // Lentamente adatta la soglia al rumore di fondo.
                    if (!loud) noiseFloor = (noiseFloor * 0.97f + rms * 0.03f).coerceAtMost(MAX_NOISE_FLOOR)
                    if (loud) {
                        speaking = true
                        preRoll.forEach { out.write(it) }
                        preRoll.clear()
                        onSpeechStart()
                    } else {
                        waitedMs += FRAME_MS
                        if (waitedMs >= noSpeechTimeoutMs) return Outcome.NoSpeech
                    }
                } else {
                    out.write(frame, 0, n)
                    speechMs += FRAME_MS
                    silenceMs = if (loud) 0 else silenceMs + FRAME_MS
                    if (silenceMs >= endSilenceMs || speechMs >= maxSpeechMs) break
                }
            }
        } catch (e: Exception) {
            return Outcome.Failed("Errore del microfono: ${e.message}")
        } finally {
            runCatching { record.stop() }
            ns?.release()
            agc?.release()
            record.release()
        }
        if (cancelled) return Outcome.Cancelled
        return if (speaking && speechMs > 300) Outcome.Speech(out.toByteArray()) else Outcome.NoSpeech
    }

    private fun rms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            sum += (s.toShort().toDouble()).let { it * it }
            i += 2
        }
        return sqrt(sum / (len / 2).coerceAtLeast(1)).toFloat()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val FRAME_MS = 30
        private const val FRAME_BYTES = SAMPLE_RATE / 1000 * FRAME_MS * 2
        private const val CALIBRATION_FRAMES = 6
        private const val PRE_ROLL_FRAMES = 10
        private const val MIN_THRESHOLD = 450f
        private const val MAX_NOISE_FLOOR = 900f

        /** Aggiunge l'intestazione WAV a un PCM 16 bit mono. */
        fun wav(pcm: ByteArray): ByteArray {
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(pcm.size)
            }.array()
            return header + pcm
        }
    }
}
