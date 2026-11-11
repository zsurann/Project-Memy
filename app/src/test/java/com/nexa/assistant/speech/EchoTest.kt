package com.nexa.assistant.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTest {
    private val spoken = "Domani a Napoli ci sarà il sole, con massime intorno ai ventidue gradi."

    @Test fun recognisesMemysOwnVoice() = assertTrue(Echo.isEcho("a Napoli ci sarà il sole con massime", spoken))
    @Test fun realAnswersAreNotEcho() {
        assertFalse(Echo.isEcho("e invece dopodomani che tempo fa?", spoken))
        assertFalse(Echo.isEcho("sì", spoken))
        assertFalse(Echo.isEcho("ok grazie", spoken))
    }
}
