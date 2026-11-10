package com.nexa.assistant.speech

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Trascrizione via internet: registra con [VadRecorder], poi invia l'audio al servizio scelto. */
class CloudSpeechToText(
    override val name: String,
    private val keyProvider: () -> String?,
    private val transcriber: (pcm: ByteArray, key: String) -> String,
) : SpeechToText {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var recorder: VadRecorder? = null
    @Volatile private var session = 0

    override fun isReady(): Boolean = !keyProvider().isNullOrBlank()

    override fun start(listener: SpeechToText.Listener) {
        stop()
        val key = keyProvider()
        if (key.isNullOrBlank()) {
            listener.onError("Manca la chiave per $name nelle impostazioni.")
            return
        }
        val id = ++session
        val rec = VadRecorder().also { recorder = it }
        fun post(block: () -> Unit) = main.post { if (session == id) block() }
        thread(name = "nexa-stt") {
            post { listener.onReady() }
            when (val outcome = rec.record(onLevel = { l -> post { listener.onLevel(l) } }, onSpeechStart = {})) {
                is VadRecorder.Outcome.Speech -> {
                    post { listener.onProcessing() }
                    try {
                        val text = transcriber(outcome.pcm, key).trim()
                        post { listener.onFinal(text) }
                    } catch (e: Exception) {
                        post { listener.onError("Trascrizione $name non riuscita: ${e.message}") }
                    }
                }
                VadRecorder.Outcome.NoSpeech -> post { listener.onFinal("") }
                VadRecorder.Outcome.Cancelled -> Unit
                is VadRecorder.Outcome.Failed -> post { listener.onError(outcome.message) }
            }
        }
    }

    override fun stop() {
        session++
        recorder?.cancel()
        recorder = null
    }

    companion object {
        private val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun googleCloud(keyProvider: () -> String?) = CloudSpeechToText("Google Cloud", keyProvider) { pcm, key ->
            val body = JSONObject()
                .put(
                    "config", JSONObject()
                        .put("encoding", "LINEAR16")
                        .put("sampleRateHertz", VadRecorder.SAMPLE_RATE)
                        .put("languageCode", "it-IT")
                        .put("model", "latest_long")
                        .put("enableAutomaticPunctuation", true)
                )
                .put("audio", JSONObject().put("content", Base64.encodeToString(pcm, Base64.NO_WRAP)))
            val req = Request.Builder()
                .url("https://speech.googleapis.com/v1/speech:recognize?key=$key")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error(googleError(resp.code, text))
                val results = JSONObject(text).optJSONArray("results") ?: return@use ""
                (0 until results.length()).joinToString(" ") { i ->
                    results.getJSONObject(i).optJSONArray("alternatives")?.optJSONObject(0)?.optString("transcript").orEmpty()
                }
            }
        }

        fun openAi(keyProvider: () -> String?) = CloudSpeechToText("OpenAI", keyProvider) { pcm, key ->
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", "gpt-4o-transcribe")
                .addFormDataPart("language", "it")
                .addFormDataPart("file", "voce.wav", VadRecorder.wav(pcm).toRequestBody("audio/wav".toMediaType()))
                .build()
            val req = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) error("HTTP ${resp.code}: ${JSONObject(text.ifBlank { "{}" }).optJSONObject("error")?.optString("message") ?: text.take(200)}")
                JSONObject(text).optString("text")
            }
        }

        internal fun googleError(code: Int, body: String): String {
            val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
            return when (code) {
                400 -> "richiesta non valida${msg?.let { ": $it" } ?: ""}"
                403 -> "chiave non autorizzata. Attiva l'API giusta nel progetto Google Cloud${msg?.let { " ($it)" } ?: ""}"
                else -> "HTTP $code${msg?.let { ": $it" } ?: ""}"
            }
        }
    }
}
