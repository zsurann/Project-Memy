package com.nexa.assistant.speech

import com.nexa.assistant.data.VoiceModel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Verifica le chiamate a ElevenLabs con un server finto, seguendo i formati della documentazione ufficiale. */
class ElevenLabsTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ElevenLabs

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        api = ElevenLabs(server.url("/").toString().trimEnd('/'))
    }

    @After fun tearDown() = server.shutdown()

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun listsVoicesAcrossPages() {
        server.enqueue(json("""{"voices":[
            {"voice_id":"v1","name":"Sarah - Mature, Reassuring","category":"premade","preview_url":"https://x/s.mp3",
             "labels":{"gender":"female","accent":"american","age":"young","descriptive":"professional"}},
            {"voice_id":"v2","name":"Mia","category":"cloned","preview_url":null,"labels":{}}],
            "has_more":true,"total_count":3,"next_page_token":"abc"}"""))
        server.enqueue(json("""{"voices":[{"voice_id":"v3","name":"George","category":"premade","labels":{"gender":"male"}}],
            "has_more":false,"total_count":3,"next_page_token":null}"""))

        val voices = api.voices("key")

        assertEquals(listOf("v1", "v2", "v3"), voices.map { it.id })
        assertEquals("Sarah", voices[0].name)
        assertTrue(voices[0].isFree)
        assertEquals("professional", voices[0].style)
        assertEquals("https://x/s.mp3", voices[0].previewUrl)
        assertFalse(voices[1].isFree)
        assertEquals(null, voices[1].previewUrl)

        val first = server.takeRequest()
        assertEquals("key", first.getHeader("xi-api-key"))
        assertTrue(first.path!!.startsWith("/v2/voices?page_size=100"))
        assertTrue(server.takeRequest().path!!.contains("next_page_token=abc"))
    }

    @Test
    fun speakSendsModelLanguageAndSpeed() {
        server.enqueue(MockResponse().setBody("MP3DATA"))
        val audio = api.speak("Ciao", "key", "voice123", VoiceModel.FLASH, 1.5f)
        assertEquals("MP3DATA", String(audio))

        val req = server.takeRequest()
        assertEquals("/v1/text-to-speech/voice123?output_format=mp3_44100_128", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("eleven_flash_v2_5", body.getString("model_id"))
        assertEquals("it", body.getString("language_code"))
        assertEquals(1.2, body.getJSONObject("voice_settings").getDouble("speed"), 0.001)
    }

    @Test
    fun multilingualOmitsLanguageCode() {
        server.enqueue(MockResponse().setBody("x"))
        api.speak("Ciao", "key", "v", VoiceModel.MULTILINGUAL, 1f)
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("language_code"))
    }

    @Test
    fun transcribeFallsBackToPreviousModel() {
        server.enqueue(json("""{"detail":{"message":"model not available"}}""", 422))
        server.enqueue(json("""{"language_code":"ita","language_probability":0.99,"text":" Accendi la torcia. "}"""))

        assertEquals("Accendi la torcia.", api.transcribe(byteArrayOf(1, 2), "key"))

        assertTrue(server.takeRequest().body.readUtf8().contains("scribe_v3"))
        val second = server.takeRequest()
        assertEquals("/v1/speech-to-text", second.path)
        val form = second.body.readUtf8()
        assertTrue(form.contains("scribe_v2"))
        assertTrue(form.contains("name=\"language_code\""))
    }

    @Test
    fun paidVoiceGivesClearError() {
        server.enqueue(json("""{"detail":{"status":"payment_required","message":"Free users cannot use library voices via the API."}}""", 402))
        try {
            api.speak("Ciao", "key", "v", VoiceModel.FLASH, 1f)
            fail("doveva fallire")
        } catch (e: ElevenLabsException) {
            assertEquals(402, e.code)
            assertTrue(e.message!!.contains("piano a pagamento"))
        }
    }

    @Test
    fun usageReadsCredits() {
        server.enqueue(json("""{"tier":"free","character_count":2500,"character_limit":10000,"status":"active"}"""))
        val u = api.usage("key")
        assertEquals(7500, u.remaining)
        assertEquals("free", u.tier)
    }
}
