package com.nexa.assistant.speech

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GroqException(message: String, val code: Int = 0) : Exception(message)

/**
 * Trascrizione con Whisper large-v3 su Groq: il modello più preciso per l'italiano, molto veloce.
 * Il piano gratuito di Groq basta per uso personale (20 richieste al minuto, 2.000 al giorno).
 * Documentazione: https://console.groq.com/docs/speech-to-text
 */
class GroqWhisper(private val baseUrl: String = "https://api.groq.com/openai/v1") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Bloccante. [prompt] aiuta Whisper con stile e parole insolite (nomi, app). */
    fun transcribe(samples: FloatArray, key: String, prompt: String = DEFAULT_PROMPT): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", "it")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "json")
            .addFormDataPart("prompt", prompt.take(600))
            .addFormDataPart("file", "voce.wav", VoiceCapture.wav(samples).toRequestBody("audio/wav".toMediaType()))
            .build()
        val req = Request.Builder()
            .url("$baseUrl/audio/transcriptions")
            .header("Authorization", "Bearer $key")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
                throw GroqException(
                    when (resp.code) {
                        401 -> "chiave Groq non valida"
                        429 -> "limite gratuito di Groq raggiunto per ora"
                        else -> "errore Groq ${resp.code}${msg?.let { ": $it" } ?: ""}"
                    },
                    resp.code,
                )
            }
            return clean(JSONObject(text).optString("text"))
        }
    }

    companion object {
        const val MODEL = "whisper-large-v3"

        /** Esempio di stile in italiano, con i nomi che Memy sente spesso. */
        const val DEFAULT_PROMPT =
            "Ehi Memy, apri WhatsApp e scrivi a Marco che arrivo tra dieci minuti. " +
                "Imposta una sveglia alle sette. Leggimi le notifiche di Telegram e Instagram."

        /** Frasi che Whisper a volte "inventa" quando l'audio è quasi muto. */
        private val HALLUCINATIONS = listOf(
            "sottotitoli", "amara.org", "grazie per la visione", "iscriviti al canale", "qtss", "sottotitolato",
            "grazie a tutti per la visione",
        )

        internal fun clean(raw: String): String {
            val text = raw.trim()
            val lower = text.lowercase()
            if (HALLUCINATIONS.any { lower.contains(it) } && text.length < 80) return ""
            return text
        }
    }
}
