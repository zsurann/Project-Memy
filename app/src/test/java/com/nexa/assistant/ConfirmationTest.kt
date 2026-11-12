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
        listOf("no", "No grazie", "annulla", "aspetta un attimo", "ferma tutto", "sì, anzi no", "non confermo", "non procedere", "non inviarlo").forEach {
            assertEquals(it, false, MemyController.parseYesNo(it))
        }
    }

    @Test
    fun unclearAnswers() {
        listOf("", "che cosa?", "boh", "mmm", "Sì, non c'è problema", "sì non vedo l'ora").forEach { assertEquals(it, null, MemyController.parseYesNo(it)) }
    }
}

class StopWordTest {
    @org.junit.Test
    fun recognisesStopRequests() {
        org.junit.Assert.assertTrue(MemyController.isStopWord("Ferma!"))
        org.junit.Assert.assertTrue(MemyController.isStopWord("Basta così."))
        org.junit.Assert.assertTrue(MemyController.isStopWord("lascia stare"))
        org.junit.Assert.assertTrue(MemyController.isStopWord("Stai zitta!"))
        org.junit.Assert.assertTrue(MemyController.isStopWord("Silenzio."))
        org.junit.Assert.assertFalse(MemyController.isStopWord("Ferma la musica"))
        org.junit.Assert.assertFalse(MemyController.isStopWord("No, scrivi a Giulia invece"))
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class MemoryReplaceTest {
    @org.junit.Test
    fun replacesOutdatedFact() {
        val store = com.nexa.assistant.data.MemoryStore(androidx.test.core.app.ApplicationProvider.getApplicationContext())
        store.add("La sorella vive a Milano")
        store.replace("sorella", "La sorella vive a Torino")
        org.junit.Assert.assertEquals(listOf("La sorella vive a Torino"), store.items.value.filter { "sorella" in it })
    }
}
