package com.nexa.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class WakeWordTest {
    @Test
    fun stripsTheWakePhraseFromTheCommand() {
        assertEquals("Che ore sono?", WakeWord.strip("Ehi Memy, che ore sono?"))
        assertEquals("Accendi la torcia", WakeWord.strip("Ei, Memi! accendi la torcia"))
        assertEquals("Chiama Marco", WakeWord.strip("Memy chiama Marco"))
        assertEquals("", WakeWord.strip("Ehi Memy."))
        assertEquals("", WakeWord.strip(" Hey Memi "))
    }

    @Test
    fun leavesOtherSentencesAlone() {
        assertEquals("Ehi, che ore sono?", WakeWord.strip("Ehi, che ore sono?"))
        assertEquals("Memoria piena, cosa faccio?", WakeWord.strip("Memoria piena, cosa faccio?"))
        assertEquals("Scrivi a Memy che arrivo", WakeWord.strip("Scrivi a Memy che arrivo"))
    }
}
