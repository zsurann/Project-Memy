package com.nexa.assistant.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class EdgeTtsTest {

    @Test
    fun tokenHasTheV2Format() {
        val token = Drm.secMsGec(1_762_800_000)
        assertTrue(token, Regex("^[0-9A-F]{16}-[0-9A-F]{64}$").matches(token))
    }

    @Test
    fun tokenIsStableWithinFiveMinutesAndChangesAfter() {
        // I "tick" vengono arrotondati a multipli di 300 secondi (calcolati dall'epoca di Windows).
        val start = 1_762_800_000L - ((1_762_800_000L + 11_644_473_600L) % 300)
        assertEquals(Drm.secMsGec(start), Drm.secMsGec(start + 299))
        assertNotEquals(Drm.secMsGec(start), Drm.secMsGec(start + 300))
    }

    @Test
    fun ssmlIsEscapedAndHasProsody() {
        val ssml = EdgeTts.mkSsml("it-IT-IsabellaNeural", -10, 5, EdgeTts.escapeXml("Tom & Jerry <3"))
        assertTrue(ssml.contains("<voice name='it-IT-IsabellaNeural'>"))
        assertTrue(ssml.contains("pitch='+5Hz' rate='-10%' volume='+0%'"))
        assertTrue(ssml.contains("Tom &amp; Jerry &lt;3"))
    }

    @Test
    fun controlCharactersAreRemoved() {
        assertEquals("a b c", EdgeTts.removeIncompatible("a\u000Bb\u0001c"))
    }

    @Test
    fun shortNames() {
        assertEquals("Isabella", EdgeTts.shortName("it-IT-IsabellaNeural"))
        assertEquals("Giuseppe", EdgeTts.shortName("it-IT-GiuseppeMultilingualNeural"))
    }

    /** Prova reale contro il servizio Microsoft: si esegue solo con EDGE_LIVE=1 (serve internet). */
    @Test
    fun liveSynthesisAndVoices() {
        assumeTrue(System.getenv("EDGE_LIVE") == "1")
        val tts = EdgeTts()
        val voices = tts.voices()
        val italian = voices.filter { it.isItalian }
        println("Voci totali: ${voices.size}; italiane: ${italian.map { "${it.id} (${it.gender}) ${it.personalities}" }}")
        println("Multilingue: ${voices.filter { it.isMultilingual }.map { it.id }}")
        assertTrue(italian.isNotEmpty())
        val audio = tts.synthesize("Ciao, sono Memy. Questa è una prova della voce gratuita.", italian.first().id)
        println("Audio MP3: ${audio.size} byte")
        assertTrue(audio.size > 5_000)
        // Intestazione MP3: "ID3" oppure frame sync 0xFFE
        assertTrue((audio[0] == 'I'.code.toByte()) || (audio[0] == 0xFF.toByte() && (audio[1].toInt() and 0xE0) == 0xE0))
        java.io.File("build/edge-sample.mp3").writeBytes(audio)
    }
}
