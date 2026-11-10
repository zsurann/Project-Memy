package com.nexa.assistant.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Prova reale della qualità dell'ascolto: la voce Edge legge comandi in italiano, Parakeet li trascrive.
 * Si esegue solo con ASR_LIVE=1, ASR_MODEL_DIR (cartella del modello) e SHERPA_JNI_DIR (librerie native Linux).
 */
class ParakeetLiveTest {

    private val sentences = listOf(
        "it-IT-IsabellaNeural" to "Ricordami di chiamare la mamma domani alle sei del pomeriggio.",
        "it-IT-DiegoNeural" to "Scrivi a Marco su WhatsApp che arrivo tra dieci minuti.",
        "it-IT-ElsaNeural" to "Che tempo fa questo fine settimana a Napoli?",
        "it-IT-GiuseppeMultilingualNeural" to "Accendi la torcia e abbassa il volume della musica.",
        "it-IT-IsabellaNeural" to "Leggimi le ultime notifiche di Telegram, per favore.",
        "it-IT-DiegoNeural" to "Aggiungi un appuntamento dal dentista giovedì prossimo alle quindici e trenta.",
    )

    private fun words(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Word error rate: distanza di modifica sulle parole / parole attese. */
    private fun wer(ref: String, hyp: String): Double {
        val r = words(ref); val h = words(hyp)
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
        }
        return d[r.size][h.size].toDouble() / r.size
    }

    /** Converte l'MP3 di Edge in PCM 16 kHz mono con ffmpeg (necessario solo per questo test). */
    private fun mp3To16k(mp3: ByteArray): ByteArray {
        val input = File.createTempFile("edge", ".mp3").apply { writeBytes(mp3) }
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", input.path, "-f", "s16le", "-ac", "1", "-ar", "16000", "-")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val out = p.inputStream.readBytes()
        p.waitFor()
        input.delete()
        return out
    }

    private fun pcmToFloat(pcm: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(pcm.size / 2) { buf.short / 32768f }
    }

    @Test
    fun italianCommandsAreTranscribedAccurately() {
        assumeTrue(System.getenv("ASR_LIVE") == "1")
        val dir = File(System.getenv("ASR_MODEL_DIR"))
        val loadStart = System.currentTimeMillis()
        val recognizer = OfflineRecognizer(
            null,
            OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").path,
                        decoder = File(dir, "decoder.int8.onnx").path,
                        joiner = File(dir, "joiner.int8.onnx").path,
                    ),
                    tokens = File(dir, "tokens.txt").path,
                    modelType = "nemo_transducer",
                    numThreads = 4,
                ),
            ),
        )
        println("Modello caricato in ${System.currentTimeMillis() - loadStart} ms")

        val vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = File("src/main/assets/silero_vad.onnx").absolutePath,
                    minSilenceDuration = 1.0f, minSpeechDuration = 0.2f, windowSize = 512, maxSpeechDuration = 35f,
                ),
                sampleRate = 16000,
            ),
        )

        val tts = EdgeTts()
        var totalWer = 0.0
        for ((voice, text) in sentences) {
            val samples = pcmToFloat(mp3To16k(tts.synthesize(text, voice)))

            // Il VAD deve riconoscere la voce nell'audio.
            vad.reset()
            var detected = false
            var i = 0
            while (i + 512 <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(i, i + 512))
                if (vad.isSpeechDetected()) detected = true
                i += 512
            }
            assertTrue("VAD: nessuna voce in \"$text\"", detected)

            val t0 = System.currentTimeMillis()
            val stream = recognizer.createStream()
            stream.acceptWaveform(samples, 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream).text.trim()
            stream.release()
            val ms = System.currentTimeMillis() - t0
            val w = wer(text, result)
            totalWer += w
            println("[%s] audio %.1fs, decodifica %d ms, WER %.0f%%\n  atteso:   %s\n  ascoltato: %s"
                .format(voice.substringAfter("IT-"), samples.size / 16000.0, ms, w * 100, text, result))
        }
        val avg = totalWer / sentences.size
        println("WER medio: %.1f%%".format(avg * 100))
        vad.release()
        recognizer.release()
        assertTrue("WER troppo alto: $avg", avg < 0.15)
    }
}
