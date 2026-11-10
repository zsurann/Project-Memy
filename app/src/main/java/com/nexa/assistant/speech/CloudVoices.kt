package com.nexa.assistant.speech

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Sintesi vocale neurale via internet. Ogni funzione restituisce l'audio MP3 di una frase. */
object CloudVoices {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val JSON = "application/json".toMediaType()

    fun google(text: String, key: String, voice: String, rate: Float): ByteArray {
        val audioConfig = JSONObject().put("audioEncoding", "MP3")
        if (rate !in 0.98f..1.02f) audioConfig.put("speakingRate", rate.toDouble())
        val body = JSONObject()
            .put("input", JSONObject().put("text", text))
            .put("voice", JSONObject().put("languageCode", "it-IT").put("name", voice))
            .put("audioConfig", audioConfig)
        val req = Request.Builder()
            .url("https://texttospeech.googleapis.com/v1/text:synthesize?key=$key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("Google: ${CloudSpeechToText.googleError(resp.code, txt)}")
            return Base64.decode(JSONObject(txt).getString("audioContent"), Base64.DEFAULT)
        }
    }

    fun openAi(text: String, key: String, voice: String, rate: Float): ByteArray {
        val body = JSONObject()
            .put("model", "gpt-4o-mini-tts")
            .put("voice", voice)
            .put("input", text)
            .put(
                "instructions",
                "Parla in italiano con accento italiano naturale, tono caldo, cordiale e sicuro, " +
                    "come un'assistente personale gentile. Ritmo scorrevole, intonazione espressiva.",
            )
            .put("response_format", "mp3")
            .put("speed", rate.coerceIn(0.5f, 2f).toDouble())
        val req = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("OpenAI: HTTP ${resp.code} ${resp.body?.string()?.take(200)}")
            return resp.body!!.bytes()
        }
    }

    fun elevenLabs(text: String, key: String, voiceId: String, rate: Float): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", "eleven_multilingual_v2")
            .put(
                "voice_settings", JSONObject()
                    .put("stability", 0.45)
                    .put("similarity_boost", 0.8)
                    .put("style", 0.15)
                    .put("use_speaker_boost", true)
                    .put("speed", rate.coerceIn(0.7f, 1.2f).toDouble())
            )
        val req = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
            .header("xi-api-key", key)
            .header("Accept", "audio/mpeg")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("ElevenLabs: HTTP ${resp.code} ${resp.body?.string()?.take(200)}")
            return resp.body!!.bytes()
        }
    }
}
