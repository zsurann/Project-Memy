package com.nexa.assistant.speech

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Scarica e installa il modello vocale italiano di Vosk (~48 MB) nella memoria dell'app.
 * Serve una sola volta; poi il riconoscimento funziona anche senza internet.
 */
class VoskModelManager(private val context: Context) {

    val modelDir: File get() = File(context.filesDir, "vosk-model-it")

    fun isInstalled(): Boolean = File(modelDir, "am").isDirectory || File(modelDir, "conf").isDirectory

    /** @param onProgress valore da 0 a 1 */
    suspend fun download(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val tmpZip = File(context.cacheDir, "vosk-it.zip")
        val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) error("Download non riuscito (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: APPROX_SIZE
            conn.inputStream.use { input ->
                FileOutputStream(tmpZip).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        done += read
                        onProgress((done.toFloat() / total).coerceIn(0f, 0.95f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        val staging = File(context.filesDir, "vosk-staging").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(tmpZip.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                // Lo zip contiene una cartella radice ("vosk-model-small-it-0.22/"): la togliamo.
                val relative = entry.name.substringAfter('/', "")
                if (relative.isNotEmpty() && !relative.contains("..")) {
                    val target = File(staging, relative)
                    if (entry.isDirectory) target.mkdirs()
                    else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zip.copyTo(it) }
                    }
                }
                entry = zip.nextEntry
            }
        }
        tmpZip.delete()
        modelDir.deleteRecursively()
        if (!staging.renameTo(modelDir)) error("Impossibile installare il modello vocale")
        onProgress(1f)
    }

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"
        private const val APPROX_SIZE = 48L * 1024 * 1024
    }
}
