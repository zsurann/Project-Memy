package com.nexa.assistant.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Fa parlare Nexa con il motore di sintesi vocale installato sul telefono. */
class Speaker(context: Context) {

    enum class Status { LOADING, READY, NO_ITALIAN, UNAVAILABLE }

    private val ready = CompletableDeferred<Status>()
    private var tts: TextToSpeech? = null
    var rate: Float = 1.0f

    init {
        tts = TextToSpeech(context.applicationContext) { code ->
            val engine = tts
            if (code != TextToSpeech.SUCCESS || engine == null) {
                ready.complete(Status.UNAVAILABLE)
                return@TextToSpeech
            }
            val result = engine.setLanguage(Locale.ITALIAN)
            ready.complete(
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Status.NO_ITALIAN
                } else Status.READY
            )
        }
    }

    suspend fun status(): Status = ready.await()

    /** Parla e ritorna solo quando ha finito (o se viene interrotto). */
    suspend fun speak(text: String) {
        val st = ready.await()
        val engine = tts ?: return
        if (st == Status.UNAVAILABLE || text.isBlank()) return
        engine.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })
            cont.invokeOnCancellation { engine.stop() }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
