package com.nexa.assistant.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Prova reale della parola di attivazione: voci Edge diverse dicono "Ehi Memy" e frasi qualsiasi.
 * Si esegue solo con KWS_LIVE=1 e SHERPA_JNI_DIR (librerie native Linux); servono internet e ffmpeg.
 * Variabili facoltative per provare altre configurazioni: KWS_DIR, KWS_KEYWORDS, KWS_THRESHOLD, KWS_SCORE.
 */
class WakeWordLiveTest {

    private val voices = listOf(
        "it-IT-IsabellaNeural", "it-IT-ElsaNeural", "it-IT-DiegoNeural", "it-IT-GiuseppeMultilingualNeural",
        "en-US-AvaMultilingualNeural", "en-US-AndrewMultilingualNeural", "en-US-EmmaMultilingualNeural",
        "en-US-BrianMultilingualNeural", "fr-FR-VivienneMultilingualNeural", "de-DE-FlorianMultilingualNeural",
    )

    private val positives = listOf("Ehi Memi.", "Ehi Memi, che ore sono?", "Ehi, Memi! Accendi la torcia.")

    private val negatives = listOf(
        "Ricordami di chiamare la mamma domani alle sei del pomeriggio.",
        "Scrivi a Marco su WhatsApp che arrivo tra dieci minuti.",
        "Ehi Mario, mi passi il sale per favore?",
        "Mamma mia, che giornata! Dammi un momento e te lo dico.",
        "A memoria non me lo ricordo, ma domani mi informo meglio.",
        "Ehi, amici, ci vediamo stasera in piazza verso le nove?",
        "Mi hai detto che Emma e Mimmo arrivano lunedì mattina.",
        "Il telegiornale di oggi parla di economia, meteo e sport.",
    )

    private fun mp3To16k(mp3: ByteArray): FloatArray {
        val input = File.createTempFile("edge", ".mp3").apply { writeBytes(mp3) }
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", input.path, "-f", "s16le", "-ac", "1", "-ar", "16000", "-")
            .redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val pcm = p.inputStream.readBytes()
        p.waitFor()
        input.delete()
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(pcm.size / 2) { buf.short / 32768f }
    }

    private val cacheDir = File(System.getenv("KWS_CACHE") ?: "build/kws-cache").apply { mkdirs() }
    private val tts by lazy { EdgeTts() }

    private fun audio(voice: String, text: String): FloatArray {
        val f = File(cacheDir, "${voice}_${text.hashCode()}.mp3")
        if (!f.exists()) {
            // Il servizio rifiuta le richieste troppo ravvicinate: qualche tentativo con una pausa.
            var last: Exception? = null
            for (attempt in 1..5) {
                try { f.writeBytes(tts.synthesize(text, voice)); last = null; break } catch (e: Exception) { last = e; Thread.sleep(4000L * attempt) }
            }
            last?.let { throw it }
            Thread.sleep(400)
        }
        return mp3To16k(f.readBytes())
    }

    /** Quante volte scatta la parola di attivazione nell'audio. */
    private fun detections(spotter: KeywordSpotter, samples: FloatArray): Int {
        val stream = spotter.createStream()
        var hits = 0
        val padded = FloatArray(8000) + samples + FloatArray(16000)
        var i = 0
        while (i < padded.size) {
            val end = minOf(i + 1600, padded.size)
            stream.acceptWaveform(padded.copyOfRange(i, end), 16000)
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotEmpty()) {
                    hits++
                    spotter.reset(stream)
                }
            }
            i = end
        }
        stream.release()
        return hits
    }

    @Test
    fun wakeWordIsHeardAndOtherSpeechIsIgnored() {
        assumeTrue(System.getenv("KWS_LIVE") == "1")
        val dir = File(System.getenv("KWS_DIR") ?: "src/main/assets/${WakeWord.ASSET_DIR}")
        fun find(prefix: String) = dir.listFiles()!!.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            .let { l -> l.firstOrNull { "int8" in it.name && "chunk-16" in it.name } ?: l.firstOrNull { "chunk-16" in it.name } ?: l.first() }.path
        val spotter = KeywordSpotter(
            null,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = find("encoder"), decoder = find("decoder"), joiner = find("joiner")),
                    tokens = File(dir, "tokens.txt").path,
                    numThreads = 1,
                ),
                keywordsFile = System.getenv("KWS_KEYWORDS") ?: File(dir, "keywords.txt").path,
                keywordsThreshold = System.getenv("KWS_THRESHOLD")?.toFloat() ?: WakeWord.THRESHOLD,
                keywordsScore = System.getenv("KWS_SCORE")?.toFloat() ?: WakeWord.SCORE,
            ),
        )

        var heard = 0; var total = 0; var falseAlarms = 0; var seconds = 0.0; var cpuMs = 0L
        val missed = ArrayList<String>()
        for (voice in voices) {
            for (text in positives) {
                val a = audio(voice, text)
                val t0 = System.currentTimeMillis()
                val n = detections(spotter, a)
                cpuMs += System.currentTimeMillis() - t0; seconds += a.size / 16000.0 + 1.5
                total++
                if (n > 0) heard++ else missed += "${voice.substringBefore("Neural")}: $text"
            }
            for (text in negatives) {
                val a = audio(voice, text)
                val t0 = System.currentTimeMillis()
                val n = detections(spotter, a)
                cpuMs += System.currentTimeMillis() - t0; seconds += a.size / 16000.0 + 1.5
                if (n > 0) { falseAlarms += n; println("FALSO: ${voice.substringBefore("Neural")}: $text") }
            }
        }
        spotter.release()
        missed.forEach { println("PERSO: $it") }
        println("RISULTATO sentita %d/%d, falsi allarmi %d su %d frasi, carico %.1f%% di un core"
            .format(heard, total, falseAlarms, voices.size * negatives.size, cpuMs / (seconds * 10)))
        assertTrue("parola di attivazione sentita troppo poco: $heard/$total", heard >= total * 0.85)
        assertTrue("troppi falsi allarmi: $falseAlarms", falseAlarms <= 2)
    }
}
