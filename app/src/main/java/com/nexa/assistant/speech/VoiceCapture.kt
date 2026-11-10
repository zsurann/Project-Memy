package com.nexa.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.sqrt

/**
 * Registra dal microfono e usa Silero VAD per capire quando inizi e quando finisci di parlare.
 * Restituisce l'audio della frase (16 kHz, valori tra -1 e 1). Bloccante: va usata da un thread dedicato.
 */
class VoiceCapture(private val context: Context) {

    sealed interface Result {
        class Speech(val samples: FloatArray) : Result
        data object Silence : Result
        data object Cancelled : Result
        class Failed(val message: String) : Result
    }

    private fun vadModelFile(): File {
        val f = File(context.filesDir, "silero_vad.onnx")
        if (!f.exists() || f.length() == 0L) {
            context.assets.open("silero_vad.onnx").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    /**
     * @param isActive deve restare true per continuare ad ascoltare
     * @param onLevel volume (0..1) per animare la sfera
     * @param onSpeech chiamata circa ogni secondo mentre parli, con l'audio finora (per il testo parziale)
     */
    @SuppressLint("MissingPermission")
    fun record(
        isActive: () -> Boolean,
        onReady: () -> Unit,
        onLevel: (Float) -> Unit,
        onSpeech: (FloatArray) -> Unit,
    ): Result {
        val vad = try {
            Vad(
                null,
                VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadModelFile().absolutePath,
                        threshold = 0.5f,
                        // Una pausa così lunga chiude la frase: abbastanza da non tagliarti mentre pensi.
                        minSilenceDuration = END_SILENCE_S,
                        minSpeechDuration = 0.15f,
                        windowSize = WINDOW,
                        maxSpeechDuration = MAX_SPEECH_S + 5f,
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ),
            )
        } catch (e: Throwable) {
            return Result.Failed("rilevamento della voce non disponibile: ${e.message}")
        }

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            // VOICE_RECOGNITION: audio pensato per il riconoscimento vocale, senza effetti che lo distorcono.
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, SAMPLE_RATE * 2 * 2),
            )
        } catch (e: Exception) {
            vad.release()
            return Result.Failed("microfono non disponibile: ${e.message}")
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); vad.release()
            return Result.Failed("microfono non disponibile (forse è usato da un'altra app)")
        }

        val shorts = ShortArray(WINDOW)
        val speech = ArrayList<FloatArray>()
        val preRoll = ArrayDeque<FloatArray>()
        var speaking = false
        var waitedMs = 0
        var sinceSnapshotMs = 0

        try {
            record.startRecording()
            onReady()
            while (isActive()) {
                val n = record.read(shorts, 0, WINDOW)
                if (n <= 0) continue
                val frame = FloatArray(n)
                var sum = 0.0
                for (i in 0 until n) {
                    val v = shorts[i] / 32768f
                    frame[i] = v
                    sum += v * v
                }
                onLevel((sqrt(sum / n).toFloat() * 9f).coerceIn(0f, 1f))
                vad.acceptWaveform(frame)
                val detected = vad.isSpeechDetected()
                // I segmenti già chiusi dal VAD non servono: li scartiamo per non accumulare memoria.
                while (!vad.empty()) vad.pop()

                if (!speaking) {
                    // Ultimo mezzo secondo prima che il VAD si accorga della voce: così non si perde la prima sillaba.
                    preRoll.addLast(frame)
                    if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    if (detected) {
                        speaking = true
                        speech.addAll(preRoll)
                        preRoll.clear()
                    } else {
                        waitedMs += FRAME_MS
                        if (waitedMs >= NO_SPEECH_MS) return Result.Silence
                    }
                } else {
                    speech.add(frame)
                    sinceSnapshotMs += FRAME_MS
                    if (sinceSnapshotMs >= SNAPSHOT_EVERY_MS && detected) {
                        sinceSnapshotMs = 0
                        onSpeech(join(speech))
                    }
                    // Il VAD smette di "sentire" voce solo dopo END_SILENCE_S di pausa: la frase è finita.
                    if (!detected || speech.size * FRAME_MS >= MAX_SPEECH_S * 1000) {
                        // Un breve tratto di silenzio in coda aiuta i modelli a chiudere bene l'ultima parola.
                        return Result.Speech(join(speech))
                    }
                }
            }
            return Result.Cancelled
        } catch (e: Exception) {
            return Result.Failed("errore del microfono: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            vad.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WINDOW = 512
        private const val FRAME_MS = WINDOW * 1000 / SAMPLE_RATE
        private const val PRE_ROLL_FRAMES = 16
        private const val END_SILENCE_S = 1.0f
        private const val MAX_SPEECH_S = 30
        private const val NO_SPEECH_MS = 8_000
        private const val SNAPSHOT_EVERY_MS = 1_000

        fun join(parts: List<FloatArray>): FloatArray {
            val out = FloatArray(parts.sumOf { it.size })
            var pos = 0
            for (p in parts) {
                p.copyInto(out, pos)
                pos += p.size
            }
            return out
        }

        /** WAV 16 bit mono da campioni float. */
        fun wav(samples: FloatArray): ByteArray {
            val pcm = java.nio.ByteBuffer.allocate(samples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
            val data = pcm.array()
            val header = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data.size)
            }.array()
            return header + data
        }
    }
}
