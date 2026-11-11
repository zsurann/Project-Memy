package com.nexa.assistant.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Riconoscimento vocale del sistema. Se è installato il servizio di Google lo sceglie esplicitamente
 * (anche se il produttore ne ha impostato un altro come predefinito).
 */
class SystemSpeechToText(private val context: Context, private val googleOnly: Boolean) : SpeechToText {

    private var recognizer: SpeechRecognizer? = null

    override val name: String get() = if (googleComponent() != null) "Google" else "Sistema"

    /** Servizi di riconoscimento installati (es. Google, vivo…). */
    fun services(): List<ComponentName> =
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            // Memy stessa offre un servizio di riconoscimento (serve per fare da assistente): qui non conta.
            .filter { it.packageName != context.packageName }

    fun googleComponent(): ComponentName? = services().firstOrNull { it.packageName in GOOGLE_PACKAGES }

    override fun isReady(): Boolean =
        if (googleOnly) googleComponent() != null else services().isNotEmpty()

    override fun start(listener: SpeechToText.Listener) {
        stop()
        val component = googleComponent() ?: services().firstOrNull().takeIf { !googleOnly }
        if (googleOnly && component == null) {
            listener.onError("Il riconoscimento vocale di Google non è installato su questo telefono.")
            return
        }
        val rec = if (component != null) SpeechRecognizer.createSpeechRecognizer(context, component)
        else SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = rec
        var lastPartial = ""
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // rmsdB va circa da -2 (silenzio) a 10 (voce forte)
                listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() = listener.onProcessing()
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                first(partialResults)?.takeIf { it.isNotBlank() }?.let {
                    lastPartial = it
                    listener.onPartial(it)
                }
            }

            override fun onResults(results: Bundle?) {
                listener.onFinal(first(results)?.takeIf { it.isNotBlank() } ?: lastPartial)
                destroy()
            }

            override fun onError(error: Int) {
                destroy()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listener.onFinal(lastPartial)
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        listener.onError("Il riconoscimento di Google non riesce a collegarsi a internet.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onError("Serve il permesso del microfono.")
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> listener.onError("Il microfono è occupato, riprova.")
                    else -> listener.onError("Errore del riconoscimento vocale ($error).")
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "it-IT")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Lascia più tempo per pause naturali, così non taglia a metà frase.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
        rec.startListening(intent)
    }

    private fun first(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    override fun stop() {
        recognizer?.let {
            it.cancel()
            it.destroy()
        }
        recognizer = null
    }

    companion object {
        val GOOGLE_PACKAGES = setOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.tts",
            "com.google.android.as",
        )
    }
}
