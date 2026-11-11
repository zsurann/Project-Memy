package com.nexa.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.concurrent.thread

/**
 * Ascolta mentre Memy parla, per capire se inizi a parlare tu: in quel caso Memy smette subito e ti ascolta.
 * Usa il microfono "da chiamata", che toglie dall'audio la voce che esce dall'altoparlante (cancellazione dell'eco).
 */
class BargeInDetector(private val context: Context) {

    /** Il registratore ancora attivo e ciò che hai detto finora: passano alla trascrizione senza perdere nulla. */
    class Capture(val record: AudioRecord, val samples: FloatArray) {
        fun release() {
            runCatching { record.stop() }
            record.release()
        }
    }

    @Volatile private var session = 0
    private val lock = Any()

    @SuppressLint("MissingPermission")
    fun start(onSpeech: (Capture) -> Unit) {
        val id = ++session
        thread(name = "memy-barge") {
            synchronized(lock) {
                if (session != id) return@thread
                var record: AudioRecord? = null
                var vad: Vad? = null
                var handedOff = false
                try {
                    vad = Vad(
                        null,
                        VadModelConfig(
                            sileroVadModelConfig = SileroVadModelConfig(
                                model = VoiceCapture.vadModelFile(context).absolutePath,
                                // Più severo del normale: deve scattare solo con una voce vicina e chiara.
                                threshold = 0.8f, minSilenceDuration = 0.3f, minSpeechDuration = 0.25f,
                                windowSize = WINDOW, maxSpeechDuration = 30f,
                            ),
                            sampleRate = VoiceCapture.SAMPLE_RATE, numThreads = 1,
                        ),
                    )
                    val minBuf = AudioRecord.getMinBufferSize(
                        VoiceCapture.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION, VoiceCapture.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, VoiceCapture.SAMPLE_RATE * 2 * 2),
                    )
                    check(record.state == AudioRecord.STATE_INITIALIZED)
                    runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.enabled = true }
                    runCatching { NoiseSuppressor.create(record.audioSessionId)?.enabled = true }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)

                    val shorts = ShortArray(WINDOW)
                    val recent = ArrayDeque<FloatArray>()
                    var voiced = 0
                    var frames = 0
                    while (session == id) {
                        val n = record.read(shorts, 0, WINDOW)
                        if (n < 0) break
                        if (n == 0) continue
                        val frame = FloatArray(n) { shorts[it] / 32768f }
                        var energy = 0.0
                        for (v in frame) energy += v * v
                        // Solo una voce vicina e abbastanza forte conta: i rumori lontani e il brusio no.
                        val loud = kotlin.math.sqrt(energy / n) > MIN_RMS
                        recent.addLast(frame)
                        if (recent.size > KEEP_FRAMES) recent.removeFirst()
                        vad.acceptWaveform(frame)
                        while (!vad.empty()) vad.pop()
                        frames++
                        // I primi istanti servono alla cancellazione dell'eco per adattarsi alla voce di Memy.
                        voiced = if (frames > SETTLE_FRAMES && vad.isSpeechDetected() && loud) voiced + 1
                        else if (voiced > 0 && vad.isSpeechDetected()) voiced // brevi cali di volume dentro una parola
                        else 0
                        if (voiced >= TRIGGER_FRAMES) {
                            handedOff = true
                            onSpeech(Capture(record, VoiceCapture.join(recent.takeLast(voiced + LEAD_FRAMES))))
                            break
                        }
                    }
                } catch (_: Throwable) {
                    // Senza microfono o senza rilevatore, semplicemente non si può interrompere a voce.
                } finally {
                    vad?.release()
                    if (!handedOff) record?.let { r -> runCatching { r.stop() }; r.release() }
                }
            }
        }
    }

    fun stop() {
        session++
    }

    private companion object {
        const val WINDOW = 512 // 32 ms
        const val SETTLE_FRAMES = 12
        /** Circa 0,6 secondi di voce continua e forte: un colpo di tosse, un "eh" o un rumore non bastano. */
        const val TRIGGER_FRAMES = 18
        const val MIN_RMS = 0.012
        const val LEAD_FRAMES = 10
        const val KEEP_FRAMES = 64
    }
}

/** Riconosce quando il microfono ha sentito la voce di Memy invece della tua. */
object Echo {
    private fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

    /** true se quasi tutte le parole sentite sono tra quelle che Memy stava dicendo. */
    fun isEcho(heard: String, spoken: String): Boolean {
        val h = words(heard)
        if (h.size < 3) return false
        val said = words(spoken).toSet()
        return h.count { it in said } >= h.size * 0.7
    }
}


/** Distingue parole vere da rumori, versi e "parole" inventate dal riconoscimento quando non c'è voce. */
object SpokenWords {
    private val FILLERS = setOf(
        "eh", "ehm", "uhm", "um", "uh", "ah", "oh", "mh", "mm", "mmm", "hm", "hmm", "boh", "beh", "ehi", "ok", "okay", "si", "no",
    )
    /** Brevi risposte che hanno senso anche da sole. */
    private val SHORT = setOf(
        "si", "no", "ferma", "fermati", "stop", "basta", "aspetta", "annulla", "certo", "esatto", "giusto", "vai", "procedi",
        "conferma", "confermo", "perfetto", "ok", "okay", "grazie", "smetti", "zitta", "silenzio", "alt",
    )

    private fun words(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    fun isMeaningful(text: String): Boolean {
        val all = words(text)
        if (all.isEmpty()) return false
        if (all.size <= 2 && all.all { it in SHORT }) return true
        val real = all.filter { it !in FILLERS && it.length >= 2 }
        return real.size >= 2 && real.sumOf { it.length } >= 7
    }
}
