package com.nexa.assistant.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** I tocchi con effetti reali devono sempre far scattare la richiesta di conferma. */
class SafetyTest {
    @Test
    fun sensitiveActionsAreDetected() {
        listOf("ImageButton \"Invia\"", "Button \"Paga ora\"", "Button \"Send\"", "Elimina conversazione", "发送", "Conferma ordine", "TextView send_button")
            .forEach { assertTrue(it, looksSensitive(it)) }
    }

    @Test
    fun ordinaryElementsAreNot() {
        listOf("Button \"Cerca\"", "EditText \"Messaggio\"", "Chat con Marco", "Impostazioni", "Postazione di lavoro", "Calendario")
            .forEach { assertFalse(it, looksSensitive(it)) }
    }
}
