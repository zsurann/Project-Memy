package com.nexa.assistant.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Il modello di riconoscimento vocale NVIDIA Parakeet TDT 0.6B v3 (25 lingue europee, italiano compreso),
 * in versione int8 per sherpa-onnx. Si scarica una volta (circa 490 MB) e poi funziona senza internet.
 */
class ParakeetModel(private val context: Context) {

    val dir: File get() = File(context.filesDir, "parakeet-tdt-0.6b-v3")

    private val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    fun isInstalled(): Boolean = files.all { File(dir, it).let { f -> f.isFile && f.length() > 0 } }

    @Volatile private var recognizer: OfflineRecognizer? = null
    private val lock = Any()

    /** Carica il modello in memoria (qualche secondo la prima volta). Thread-safe, bloccante. */
    fun recognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        synchronized(lock) {
            recognizer?.let { return it }
            check(isInstalled()) { "Modello di ascolto non installato" }
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = File(dir, "encoder.int8.onnx").absolutePath,
                        decoder = File(dir, "decoder.int8.onnx").absolutePath,
                        joiner = File(dir, "joiner.int8.onnx").absolutePath,
                    ),
                    tokens = File(dir, "tokens.txt").absolutePath,
                    modelType = "nemo_transducer",
                    numThreads = THREADS,
                ),
            )
            return OfflineRecognizer(null, config).also { recognizer = it }
        }
    }

    /** Trascrive un audio (16 kHz, valori tra -1 e 1). Bloccante. */
    fun transcribe(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val rec = recognizer()
        synchronized(lock) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                return rec.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    /**
     * Scarica ed estrae il modello mentre arriva (non serve spazio per l'archivio).
     * @param onProgress valore da 0 a 1
     */
    suspend fun download(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val staging = File(context.filesDir, "parakeet-staging").apply { deleteRecursively(); mkdirs() }
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("download non riuscito (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: APPROX_SIZE
            val counting = object : FilterInputStream(conn.inputStream.buffered(256 * 1024)) {
                var read = 0L
                private var lastReport = 0L
                private fun count(n: Int) {
                    if (n > 0) read += n
                    if (read - lastReport > 512 * 1024) {
                        lastReport = read
                        onProgress((read.toFloat() / total).coerceIn(0f, 0.99f))
                    }
                }
                override fun read(): Int = super.read().also { if (it >= 0) count(1) }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { count(it) }
            }
            TarArchiveInputStream(BZip2CompressorInputStream(counting as InputStream)).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name in files) {
                        File(staging, name).outputStream().use { tar.copyTo(it, 256 * 1024) }
                    }
                    entry = tar.nextEntry
                }
            }
        } finally {
            conn.disconnect()
        }
        check(files.all { File(staging, it).length() > 0 }) { "archivio del modello incompleto" }
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            dir.deleteRecursively()
            if (!staging.renameTo(dir)) error("impossibile installare il modello")
        }
        onProgress(1f)
    }

    fun delete() = synchronized(lock) {
        recognizer?.release()
        recognizer = null
        dir.deleteRecursively()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private val THREADS = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
        private const val APPROX_SIZE = 487_211_443L
    }
}
