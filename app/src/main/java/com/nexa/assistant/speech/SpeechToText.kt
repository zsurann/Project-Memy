package com.nexa.assistant.speech

/** Ascolta una frase e restituisce il testo. Tutte le callback arrivano sul thread principale. */
interface SpeechToText {
    interface Listener {
        fun onReady() {}
        /** Volume della voce, da 0 a 1, per animare la sfera. */
        fun onLevel(level: Float) {}
        fun onPartial(text: String) {}
        /** L'utente ha smesso di parlare e il testo è in trascrizione (motori cloud). */
        fun onProcessing() {}
        fun onFinal(text: String)
        fun onError(message: String)
    }

    val name: String

    fun isReady(): Boolean
    fun start(listener: Listener)
    fun stop()
    fun release() = stop()
}
