package com.nexa.assistant.speech

import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/** Riconoscimento vocale completamente offline, ideale per telefoni senza servizi Google. */
class VoskSpeechToText(private val models: VoskModelManager) : SpeechToText {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null

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
            var delivered = false
            svc.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    val text = parse(hypothesis, "partial")
                    if (text.isNotBlank()) listener.onPartial(text)
                }

                override fun onResult(hypothesis: String?) {
                    // Vosk chiama onResult quando rileva una pausa: la frase è finita.
                    val text = parse(hypothesis, "text")
                    if (text.isNotBlank() && !delivered) {
                        delivered = true
                        stop()
                        listener.onFinal(text)
                    }
                }

                override fun onFinalResult(hypothesis: String?) {
                    if (delivered) return
                    delivered = true
                    listener.onFinal(parse(hypothesis, "text"))
                }

                override fun onError(exception: Exception?) {
                    if (delivered) return
                    delivered = true
                    stop()
                    listener.onError(exception?.message ?: "Errore del microfono")
                }

                override fun onTimeout() {
                    if (delivered) return
                    delivered = true
                    stop()
                    listener.onFinal("")
                }
            }, LISTEN_TIMEOUT_MS)
        } catch (e: Exception) {
            listener.onError("Impossibile avviare il riconoscimento vocale: ${e.message}")
        }
    }

    override fun stop() {
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
        const val LISTEN_TIMEOUT_MS = 12_000
    }
}
