package com.nexa.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Ascolto offline di alta qualità: Silero VAD capisce quando parli e quando hai finito,
 * Parakeet trascrive. Mentre parli mostra il testo parziale, poi la trascrizione finale completa.
 */
class ParakeetSpeechToText(private val context: Context, val model: ParakeetModel) : SpeechToText {

    override val name = "Parakeet (offline)"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session = 0

    override fun isReady(): Boolean = model.isInstalled()

    /** Prepara il modello in memoria in anticipo, così la prima frase non aspetta il caricamento. */
    fun warmUp() {
        if (!model.isInstalled()) return
        thread(name = "memy-asr-warmup") { runCatching { model.recognizer() } }
    }

    private fun vadModelFile(): File {
        val f = File(context.filesDir, "silero_vad.onnx")
        if (!f.exists() || f.length() == 0L) {
            context.assets.open("silero_vad.onnx").use { input -> f.outputStream().use { input.copyTo(it) } }
        }
        return f
    }

    @SuppressLint("MissingPermission")
    override fun start(listener: SpeechToText.Listener) {
        stop()
        val id = ++session
        fun post(block: () -> Unit) = main.post { if (session == id) block() }

        thread(name = "memy-asr") {
            val vad = try {
                Vad(
                    null,
                    VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = vadModelFile().absolutePath,
                            threshold = 0.5f,
                            // Una pausa di questa durata chiude la frase: abbastanza lunga da non tagliarti mentre pensi.
                            minSilenceDuration = END_SILENCE_S,
                            minSpeechDuration = 0.2f,
                            windowSize = WINDOW,
                            maxSpeechDuration = MAX_SPEECH_S + 5f,
                        ),
                        sampleRate = ParakeetModel.SAMPLE_RATE,
                        numThreads = 1,
                    ),
                )
            } catch (e: Throwable) {
                post { listener.onError("Riconoscimento vocale non disponibile: ${e.message}") }
                return@thread
            }

            val minBuf = AudioRecord.getMinBufferSize(ParakeetModel.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, ParakeetModel.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    // Buffer ampio (4 s): mentre si calcola il testo parziale l'audio non va perso.
                    maxOf(minBuf, ParakeetModel.SAMPLE_RATE * 2 * 4),
                )
            } catch (e: Exception) {
                vad.release()
                post { listener.onError("Microfono non disponibile: ${e.message}") }
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); vad.release()
                post { listener.onError("Microfono non disponibile (forse è usato da un'altra app).") }
                return@thread
            }
            val ns = runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()
            val agc = runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true } else null }.getOrNull()

            // Carica il modello mentre già si registra (se non era già in memoria).
            thread(name = "memy-asr-load") { runCatching { model.recognizer() } }

            val shorts = ShortArray(WINDOW)
            val chunk = FloatArray(WINDOW)
            val speech = ArrayList<FloatArray>()
            // Ultimi ~0,5 s prima che il VAD si accorga della voce, per non perdere la prima sillaba.
            val preRoll = ArrayDeque<FloatArray>()
            var speaking = false
            var waitedMs = 0
            var sinceLastPartialMs = 0
            var lastPartial = ""

            try {
                record.startRecording()
                post { listener.onReady() }
                while (session == id) {
                    val n = record.read(shorts, 0, WINDOW)
                    if (n <= 0) continue
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = shorts[i] / 32768f
                        chunk[i] = v
                        sum += v * v
                    }
                    val rms = sqrt(sum / n).toFloat()
                    post { listener.onLevel((rms * 9f).coerceIn(0f, 1f)) }
                    val frame = chunk.copyOf(n)
                    vad.acceptWaveform(frame)
                    val detected = vad.isSpeechDetected()

                    if (!speaking) {
                        preRoll.addLast(frame)
                        if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                        if (detected) {
                            speaking = true
                            speech.addAll(preRoll)
                            preRoll.clear()
                        } else {
                            waitedMs += FRAME_MS
                            if (waitedMs >= NO_SPEECH_MS) break
                        }
                    } else {
                        speech.add(frame)
                        // Il VAD conserva i segmenti già chiusi: non ci servono, li scartiamo per non accumulare memoria.
                        while (!vad.empty()) vad.pop()
                        sinceLastPartialMs += FRAME_MS
                        // Testo parziale circa ogni secondo, solo per frasi non troppo lunghe (resta veloce).
                        if (sinceLastPartialMs >= PARTIAL_EVERY_MS && speech.size * FRAME_MS < 12_000 && detected) {
                            sinceLastPartialMs = 0
                            val text = runCatching { model.transcribe(join(speech)) }.getOrDefault("")
                            if (text.isNotBlank() && text != lastPartial) {
                                lastPartial = text
                                post { listener.onPartial(text) }
                            }
                        }
                        // Fine frase: il VAD smette di "sentire" voce solo dopo END_SILENCE_S di pausa; oppure frase lunghissima.
                        if (!detected || speech.size * FRAME_MS >= MAX_SPEECH_S * 1000) {
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                post { listener.onError("Errore del microfono: ${e.message}") }
                return@thread
            } finally {
                runCatching { record.stop() }
                ns?.release()
                agc?.release()
                record.release()
                vad.release()
            }

            if (session != id) return@thread
            if (!speaking) {
                post { listener.onFinal("") }
                return@thread
            }
            post { listener.onProcessing() }
            val text = try {
                model.transcribe(join(speech))
            } catch (e: Throwable) {
                post { listener.onError("Trascrizione non riuscita: ${e.message}") }
                return@thread
            }
            post { listener.onFinal(text) }
        }
    }

    private fun join(parts: List<FloatArray>): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    override fun stop() {
        session++
    }

    private companion object {
        const val WINDOW = 512
        const val FRAME_MS = WINDOW * 1000 / ParakeetModel.SAMPLE_RATE
        const val PRE_ROLL_FRAMES = 16
        const val END_SILENCE_S = 1.0f
        const val MAX_SPEECH_S = 30f
        const val NO_SPEECH_MS = 8_000
        const val PARTIAL_EVERY_MS = 1_100
    }
}
