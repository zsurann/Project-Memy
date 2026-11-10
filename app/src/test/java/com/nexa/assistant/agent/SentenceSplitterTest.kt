package com.nexa.assistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceSplitterTest {
    private fun split(vararg parts: String): List<String> {
        val out = mutableListOf<String>()
        val s = SentenceSplitter { out += it }
        parts.forEach(s::feed)
        s.flush()
        return out
    }

    @Test
    fun firstSentenceComesEarly() {
        assertEquals(
            listOf("Ciao Luca!", "Sto benissimo, grazie per avermelo chiesto.", "E tu come stai oggi?"),
            split("Ciao Lu", "ca! Sto benissimo, grazie per avermelo chiesto. E tu come stai oggi?"),
        )
    }

    @Test
    fun decimalsAreNotSplit() {
        assertEquals(listOf("Fuori ci sono 12.5 gradi."), split("Fuori ci sono 12.5 gradi."))
    }

    @Test
    fun newlineEndsSentence() {
        assertEquals(listOf("Prima riga", "seconda riga"), split("Prima riga\nseconda riga"))
    }
}
