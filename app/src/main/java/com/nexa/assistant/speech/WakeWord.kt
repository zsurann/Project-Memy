package com.nexa.assistant.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlin.concurrent.thread

/**
 * Parola di attivazione "Ehi Memy", riconosciuta sul telefono da un piccolo modello di sherpa-onnx
 * (circa 5 MB, incluso nell'app). Il modello lavora sui fonemi: le pronunce accettate sono in
 * `assets/kws/keywords.txt`.
 */
object WakeWord {
    const val ASSET_DIR = "kws"
    /** Valori predefiniti (sensibilità normale), verificati da WakeWordLiveTest. */
    const val THRESHOLD = 0.1f
    const val SCORE = 2.0f

    private val INTRO = Regex(
        "^\\s*(?:(?:e+h+i*|hey|ei|ehy|hei)[\\s,!.]*)?(?:memy|memi|mémi|memmy|memmi|mami)\\b[\\s,!.?:;-]*",
        RegexOption.IGNORE_CASE,
    )

    /** Toglie "Ehi Memy" dall'inizio di una frase trascritta: resta solo il comando. */
    fun strip(text: String): String = INTRO.replaceFirst(text, "").trim().replaceFirstChar { it.uppercase() }
}

/** Quanto facilmente scatta la parola di attivazione. */
enum class WakeSensitivity(val label: String, val threshold: Float, val score: Float) {
    LOW("Bassa", 0.2f, 1.5f),
    NORMAL("Normale", WakeWord.THRESHOLD, WakeWord.SCORE),
    HIGH("Alta", 0.05f, 3.0f),
}

/** Ascolta il microfono in continuazione e avvisa quando sente "Ehi Memy". */
class WakeWordDetector(private val context: Context) {

    @Volatile private var session = 0
    private val lock = Any()
    private var spotter: KeywordSpotter? = null
    private var spotterSensitivity: WakeSensitivity? = null

    private fun spotter(sensitivity: WakeSensitivity): KeywordSpotter {
        spotter?.let { if (spotterSensitivity == sensitivity) return it else it.release() }
        return KeywordSpotter(
            context.assets,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = VoiceCapture.SAMPLE_RATE, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "${WakeWord.ASSET_DIR}/encoder.int8.onnx",
                        decoder = "${WakeWord.ASSET_DIR}/decoder.onnx",
                        joiner = "${WakeWord.ASSET_DIR}/joiner.int8.onnx",
                    ),
                    tokens = "${WakeWord.ASSET_DIR}/tokens.txt",
                    numThreads = 1,
                ),
                keywordsFile = "${WakeWord.ASSET_DIR}/keywords.txt",
                keywordsThreshold = sensitivity.threshold,
                keywordsScore = sensitivity.score,
            ),
        ).also { spotter = it; spotterSensitivity = sensitivity }
    }

    /**
     * Inizia ad ascoltare. Le callback arrivano dal thread di ascolto.
     * @param onDetected riceve il registratore ancora attivo: chi lo riceve continua a leggere da lì (così non
     *   si perde ciò che dici subito dopo "Ehi Memy") e deve poi rilasciarlo.
     */
    @SuppressLint("MissingPermission")
    fun start(sensitivity: WakeSensitivity, onDetected: (AudioRecord) -> Unit, onError: (String) -> Unit) {
        val id = ++session
        thread(name = "memy-wake") {
            // Un solo ascolto alla volta: quello precedente si chiude entro una lettura (un decimo di secondo).
            synchronized(lock) {
                if (session != id) return@thread
                var record: AudioRecord? = null
                var handedOff = false
                try {
                    val kws = spotter(sensitivity)
                    val minBuf = AudioRecord.getMinBufferSize(
                        VoiceCapture.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    )
                    record = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceCapture.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                        // Due secondi di margine: l'audio non si perde nel passaggio alla trascrizione.
                        maxOf(minBuf, VoiceCapture.SAMPLE_RATE * 2 * 2),
                    )
                    check(record.state == AudioRecord.STATE_INITIALIZED) { "microfono non disponibile" }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "microfono occupato" }
                    val stream = kws.createStream()
                    try {
                        val shorts = ShortArray(CHUNK)
                        var detected = false
                        while (session == id && !detected) {
                            val n = record.read(shorts, 0, CHUNK)
                            if (n < 0) error("lettura del microfono non riuscita ($n)")
                            if (n == 0) continue
                            stream.acceptWaveform(FloatArray(n) { shorts[it] / 32768f }, VoiceCapture.SAMPLE_RATE)
                            while (kws.isReady(stream)) {
                                kws.decode(stream)
                                if (kws.getResult(stream).keyword.isNotEmpty()) detected = true
                            }
                        }
                        if (detected && session == id) {
                            handedOff = true
                            onDetected(record)
                        }
                    } finally {
                        stream.release()
                    }
                } catch (e: Throwable) {
                    if (session == id) onError(e.message ?: e.javaClass.simpleName)
                } finally {
                    if (!handedOff) record?.let { r -> runCatching { r.stop() }; r.release() }
                }
            }
        }
    }

    fun stop() {
        session++
    }

    /** Ferma l'ascolto e libera il modello dalla memoria. */
    fun release() {
        stop()
        thread(name = "memy-wake-release") {
            synchronized(lock) {
                spotter?.release()
                spotter = null
                spotterSensitivity = null
            }
        }
    }

    private companion object {
        /** Un decimo di secondo di audio per lettura. */
        const val CHUNK = 1600
    }
}
