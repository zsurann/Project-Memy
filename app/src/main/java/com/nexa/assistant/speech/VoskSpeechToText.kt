package com.nexa.assistant.speech

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Riconoscimento offline. A differenza di prima non si ferma alla prima pausa:
 * accumula le frasi e chiude solo dopo un silenzio più lungo.
 */
class VoskSpeechToText(private val models: VoskModelManager) : SpeechToText {

    override val name = "Offline (Vosk)"

    private var model: Model? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    private val main = Handler(Looper.getMainLooper())
    private var finalize: Runnable? = null

    override fun isReady(): Boolean = models.isInstalled()

    private fun loadModel(): Model {
        model?.let { return it }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(models.modelDir.absolutePath).also { model = it }
    }

    override fun start(listener: SpeechToText.Listener) {
        stop()
        try {
            val rec = Recognizer(loadModel(), SAMPLE_RATE).also { recognizer = it }
            val svc = SpeechService(rec, SAMPLE_RATE)
            service = svc
            val collected = StringBuilder()
            var delivered = false
            var lastPartial = ""

            fun deliver() {
                if (delivered) return
                delivered = true
                cancelFinalize()
                stop()
                listener.onFinal((collected.toString() + " " + lastPartial).trim())
            }

            fun scheduleFinalize(delayMs: Long) {
                cancelFinalize()
                finalize = Runnable { deliver() }.also { main.postDelayed(it, delayMs) }
            }

            listener.onReady()
            // Se non dici niente entro qualche secondo, smette di ascoltare (non tiene il microfono aperto a vuoto).
            scheduleFinalize(NO_SPEECH_MS)
            svc.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parse(hypothesis, "partial")
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        cancelFinalize()
                        listener.onLevel(0.55f + (text.length % 5) * 0.08f)
                        listener.onPartial((collected.toString() + " " + text).trim())
                    } else {
                        listener.onLevel(0.08f)
                    }
                }

                override fun onResult(hypothesis: String?) {
                    val text = parse(hypothesis, "text")
                    lastPartial = ""
                    if (text.isNotBlank()) {
                        if (collected.isNotEmpty()) collected.append(' ')
                        collected.append(text)
                        listener.onPartial(collected.toString())
                        // Aspetta un po': se l'utente continua a parlare, la frase prosegue.
                        scheduleFinalize(END_SILENCE_MS)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    val text = parse(hypothesis, "text")
                    if (text.isNotBlank() && !collected.endsWith(text)) {
                        if (collected.isNotEmpty()) collected.append(' ')
                        collected.append(text)
                    }
                    deliver()
                }

                override fun onError(exception: Exception?) {
                    if (delivered) return
                    delivered = true
                    stop()
                    listener.onError(exception?.message ?: "Errore del microfono")
                }

                override fun onTimeout() = deliver()
            }, LISTEN_TIMEOUT_MS)
        } catch (e: Exception) {
            listener.onError("Impossibile avviare il riconoscimento offline: ${e.message}")
        }
    }

    private fun cancelFinalize() {
        finalize?.let { main.removeCallbacks(it) }
        finalize = null
    }

    override fun stop() {
        cancelFinalize()
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
        recognizer?.close()
        recognizer = null
    }

    override fun release() {
        stop()
        model?.close()
        model = null
    }

    private fun parse(json: String?, field: String): String =
        runCatching { JSONObject(json ?: "{}").optString(field) }.getOrDefault("").trim()

    private companion object {
        const val SAMPLE_RATE = 16000f
        const val LISTEN_TIMEOUT_MS = 30_000
        const val END_SILENCE_MS = 1_100L
        const val NO_SPEECH_MS = 8_000L
    }
}
