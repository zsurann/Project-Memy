package com.nexa.assistant.speech

/** Ascolta una singola frase e restituisce il testo riconosciuto. */
interface SpeechToText {
    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    /** true se il motore è pronto all'uso (es. modello vocale già scaricato). */
    fun isReady(): Boolean

    fun start(listener: Listener)
    fun stop()
    fun release()
}
