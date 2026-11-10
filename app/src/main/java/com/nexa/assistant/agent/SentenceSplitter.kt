package com.nexa.assistant.agent

/**
 * Riceve il testo a pezzetti (mentre Claude lo scrive) e consegna frasi complete,
 * così la voce può iniziare a parlare subito invece di aspettare tutta la risposta.
 */
class SentenceSplitter(private val onSentence: (String) -> Unit) {
    private val buffer = StringBuilder()
    private var emittedAny = false

    fun feed(delta: String) {
        buffer.append(delta)
        while (true) {
            val cut = findCut() ?: break
            val sentence = buffer.substring(0, cut).trim()
            buffer.delete(0, cut)
            if (sentence.isNotEmpty()) emit(sentence)
        }
    }

    fun flush() {
        val rest = buffer.toString().trim()
        buffer.clear()
        if (rest.isNotEmpty()) emit(rest)
    }

    private fun emit(s: String) {
        emittedAny = true
        onSentence(s)
    }

    /** Trova la fine di una frase: punteggiatura forte seguita da spazio o a capo. */
    private fun findCut(): Int? {
        // La prima frase può essere corta (risposta più rapida); le successive le raggruppiamo un po'.
        val minLen = if (emittedAny) 40 else 6
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n' && i >= 1) return i + 1
            if ((c == '.' || c == '!' || c == '?' || c == ';' || c == ':') && i + 1 < buffer.length && buffer[i + 1].isWhitespace()) {
                val prev = buffer.getOrNull(i - 1)
                val isNumber = c == '.' && prev?.isDigit() == true && buffer.getOrNull(i + 2)?.isDigit() == true
                if (!isNumber && i + 1 >= minLen) return i + 1
            }
            i++
        }
        return null
    }
}
