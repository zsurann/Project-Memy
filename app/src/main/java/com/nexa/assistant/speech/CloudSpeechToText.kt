package com.nexa.assistant.speech

import android.os.Handler
import android.os.Looper
import kotlin.concurrent.thread

/** Trascrizione via internet: registra con [VadRecorder], poi invia l'audio a ElevenLabs Scribe. */
class CloudSpeechToText(
    override val name: String,
    private val keyProvider: () -> String?,
    private val transcriber: (wav: ByteArray, key: String) -> String,
) : SpeechToText {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: VadRecorder? = null
    @Volatile private var session = 0

    override fun isReady(): Boolean = !keyProvider().isNullOrBlank()

    override fun start(listener: SpeechToText.Listener) {
        stop()
        val key = keyProvider()
        if (key.isNullOrBlank()) {
            listener.onError("Manca la chiave ElevenLabs nelle impostazioni.")
            return
        }
        val id = ++session
        val rec = VadRecorder().also { recorder = it }
        fun post(block: () -> Unit) = main.post { if (session == id) block() }
        thread(name = "memy-stt") {
            post { listener.onReady() }
            when (val outcome = rec.record(onLevel = { l -> post { listener.onLevel(l) } }, onSpeechStart = {})) {
                is VadRecorder.Outcome.Speech -> {
                    post { listener.onProcessing() }
                    try {
                        val text = transcriber(VadRecorder.wav(outcome.pcm), key)
                        post { listener.onFinal(text) }
                    } catch (e: Exception) {
                        post { listener.onError("Trascrizione non riuscita: ${e.message}") }
                    }
                }
                VadRecorder.Outcome.NoSpeech -> post { listener.onFinal("") }
                VadRecorder.Outcome.Cancelled -> Unit
                is VadRecorder.Outcome.Failed -> post { listener.onError(outcome.message) }
            }
        }
    }

    override fun stop() {
        session++
        recorder?.cancel()
        recorder = null
    }
}
