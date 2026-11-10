package com.nexa.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

/** Le risposte a voce alle richieste di conferma. */
class ConfirmationTest {
    @Test
    fun yesAnswers() {
        listOf("sì", "Si, vai", "certo che sì", "ok procedi", "Sì, invialo pure", "fallo", "assolutamente").forEach {
            assertEquals(it, true, MemyController.parseYesNo(it))
        }
    }

    @Test
    fun noAnswers() {
        listOf("no", "No grazie", "annulla", "aspetta un attimo", "ferma tutto", "sì, anzi no").forEach {
            assertEquals(it, false, MemyController.parseYesNo(it))
        }
    }

    @Test
    fun unclearAnswers() {
        listOf("", "che cosa?", "boh", "mmm").forEach { assertEquals(it, null, MemyController.parseYesNo(it)) }
    }
}
