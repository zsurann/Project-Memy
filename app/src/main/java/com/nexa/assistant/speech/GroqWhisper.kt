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

    @Volatile private var lastWarm = 0L

    /** Apre in anticipo la connessione con Groq (nessun costo), così la trascrizione arriva prima. Bloccante. */
    fun prewarm(key: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 60_000) return
        lastWarm = now
        runCatching {
            http.newCall(Request.Builder().url("$baseUrl/models").header("Authorization", "Bearer $key").build()).execute().close()
        }
    }

    /** Bloccante. [prompt] aiuta Whisper con stile e parole insolite (nomi, app). */
    fun transcribe(samples: FloatArray, key: String, prompt: String = DEFAULT_PROMPT): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("language", "it")
            .addFormDataPart("temperature", "0")
            .addFormDataPart("response_format", "verbose_json")
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
            val heard = clean(spokenText(JSONObject(text)))
            // Sull'audio quasi muto Whisper a volte ripete l'esempio di stile: non è qualcosa che l'utente ha detto.
            return if (echoesPrompt(heard, prompt)) "" else heard
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

        /**
         * Il testo, senza i pezzi in cui Whisper stesso dice che probabilmente non c'era voce
         * (è lì che, sui rumori, inventa parole).
         */
        internal fun spokenText(json: JSONObject): String {
            val segments = json.optJSONArray("segments") ?: return json.optString("text")
            return (0 until segments.length()).map { segments.getJSONObject(it) }
                .filter { s ->
                    val noSpeech = s.optDouble("no_speech_prob", 0.0)
                    val logProb = s.optDouble("avg_logprob", 0.0)
                    val compression = s.optDouble("compression_ratio", 1.0)
                    !(noSpeech > 0.5 && logProb < -0.6) && logProb > -1.3 && compression < 2.6
                }
                .joinToString(" ") { it.optString("text").trim() }
                .trim()
        }

        private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

        /** Il testo è un pezzo lungo (9+ parole) copiato dall'esempio di stile: frasi così lunghe e identiche non si dicono per caso. */
        internal fun echoesPrompt(text: String, prompt: String): Boolean {
            val t = norm(text)
            return t.split(' ').size >= 9 && norm(prompt).contains(t)
        }

        internal fun clean(raw: String): String {
            val text = raw.trim()
            val lower = text.lowercase()
            if (HALLUCINATIONS.any { lower.contains(it) } && text.length < 80) return ""
            return text
        }
    }
}
