package com.nexa.assistant.speech

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Richieste a Groq secondo la documentazione ufficiale (https://console.groq.com/docs/speech-to-text). */
class GroqWhisperTest {
    private lateinit var server: MockWebServer
    private lateinit var groq: GroqWhisper

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        groq = GroqWhisper(server.url("/openai/v1").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun sendsItalianWhisperRequestWithWav() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"text":" Scrivi a Marco che arrivo. "}"""))
        val text = groq.transcribe(FloatArray(16000) { 0.1f }, "gsk_test", "Ciao Memy.")
        assertEquals("Scrivi a Marco che arrivo.", text)

        val req = server.takeRequest()
        assertEquals("/openai/v1/audio/transcriptions", req.path)
        assertEquals("Bearer gsk_test", req.getHeader("Authorization"))
        val form = req.body.readUtf8()
        for (field in listOf("model" to "whisper-large-v3", "language" to "it", "temperature" to "0", "prompt" to "Ciao Memy.", "response_format" to "verbose_json")) {
            assertTrue(field.toString(), form.contains("name=\"${field.first}\"\r\nContent-Length: ${field.second.length}\r\n\r\n${field.second}"))
        }
        assertTrue(form.contains("filename=\"voce.wav\""))
        assertTrue(form.contains("RIFF") && form.contains("WAVE"))
    }

    @Test
    fun invalidKeyGivesItalianMessage() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API Key"}}"""))
        try {
            groq.transcribe(FloatArray(1600), "bad")
            fail("doveva fallire")
        } catch (e: GroqException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("chiave Groq non valida"))
        }
    }

    @Test
    fun hallucinationsOnSilenceAreDropped() {
        assertEquals("", GroqWhisper.clean("Sottotitoli a cura di QTSS"))
        assertEquals("", GroqWhisper.clean(" Grazie per la visione! "))
        assertEquals("Accendi la torcia.", GroqWhisper.clean(" Accendi la torcia. "))
    }

    @Test
    fun segmentsWithoutSpeechAreDropped() {
        val json = org.json.JSONObject(
            """{"text":" Ferma. Grazie a tutti.","segments":[
              {"text":" Ferma.","avg_logprob":-0.25,"no_speech_prob":0.02,"compression_ratio":0.9},
              {"text":" Grazie a tutti.","avg_logprob":-0.95,"no_speech_prob":0.81,"compression_ratio":1.0}]}"""
        )
        assertEquals("Ferma.", GroqWhisper.spokenText(json))
        // Senza segmenti (formato semplice) si usa il testo così com'è.
        assertEquals("Ciao", GroqWhisper.spokenText(org.json.JSONObject("""{"text":"Ciao"}""")))
    }

    @Test
    fun onlyRealWordsInterruptMemy() {
        for (real in listOf("Ferma!", "sì", "No.", "Scrivi a Giulia", "aspetta un attimo", "che ore sono?")) {
            assertTrue(real, SpokenWords.isMeaningful(real))
        }
        for (noise in listOf("", "Eh.", "Mmm", "ehm, ah", "Oh!", "a", "uh e", "ah oh", "mh, boh")) {
            assertTrue(noise, !SpokenWords.isMeaningful(noise))
        }
    }

    @Test
    fun wavHeaderIsCorrect() {
        val wav = VoiceCapture.wav(FloatArray(100) { 0.5f })
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + 200, wav.size)
    }
}
