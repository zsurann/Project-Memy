package com.nexa.assistant.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Ascolto con [VoiceCapture]: mentre parli mostra il testo parziale (calcolato sul telefono con Parakeet,
 * se è installato), poi trascrive la frase completa con [finalTranscriber]. Se questo fallisce e c'è
 * Parakeet, usa la trascrizione locale: così Memy ti capisce anche senza internet.
 */
class CaptureSpeechToText(
    private val context: Context,
    override val name: String,
    private val parakeet: ParakeetModel,
    private val ready: () -> Boolean,
    /** null = usa solo Parakeet. */
    private val finalTranscriber: ((FloatArray) -> String)?,
) : SpeechToText {

    private val main = Handler(Looper.getMainLooper())
    private val capture = VoiceCapture(context)
    @Volatile private var session = 0
    /** I testi parziali si calcolano su un thread a parte, così la registrazione non perde audio. */
    private val partialExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "memy-asr-partial") }
    private val partialBusy = AtomicBoolean(false)

    override fun isReady(): Boolean = ready()

    override fun start(listener: SpeechToText.Listener) = start(listener, null)

    /** @param handoff registratore già attivo da cui continuare (vedi [WakeWordDetector]); viene rilasciato qui. */
    fun start(listener: SpeechToText.Listener, handoff: android.media.AudioRecord?) {
        stop()
        val id = ++session
        fun post(block: () -> Unit) = main.post { if (session == id) block() }
        val localPartials = parakeet.isInstalled()

        thread(name = "memy-asr") {
            if (localPartials) runCatching { parakeet.recognizer() } // di solito è già in memoria
            val result = capture.record(
                isActive = { session == id },
                onReady = { post { listener.onReady() } },
                onLevel = { l -> post { listener.onLevel(l) } },
                onSpeech = { audio ->
                    if (localPartials && partialBusy.compareAndSet(false, true)) {
                        partialExecutor.execute {
                            try {
                                val text = runCatching { parakeet.transcribe(audio) }.getOrDefault("")
                                if (text.isNotBlank()) post { listener.onPartial(text) }
                            } finally {
                                partialBusy.set(false)
                            }
                        }
                    }
                },
                handoff = handoff,
            )
            when (result) {
                is VoiceCapture.Result.Speech -> {
                    post { listener.onProcessing() }
                    val text = try {
                        finalTranscriber?.invoke(result.samples) ?: parakeet.transcribe(result.samples)
                    } catch (e: Exception) {
                        // Senza internet (o oltre il limite gratuito) si usa la trascrizione sul telefono, se c'è.
                        val local = if (localPartials) runCatching { parakeet.transcribe(result.samples) }.getOrNull() else null
                        if (local == null) {
                            post { listener.onError("Trascrizione non riuscita: ${e.message}") }
                            return@thread
                        }
                        local
                    }
                    post { listener.onFinal(text) }
                }
                VoiceCapture.Result.Silence -> post { listener.onFinal("") }
                VoiceCapture.Result.Cancelled -> Unit
                is VoiceCapture.Result.Failed -> post { listener.onError(result.message.replaceFirstChar { it.uppercase() } + ".") }
            }
        }
    }

    override fun stop() {
        session++
    }
}
