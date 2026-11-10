package com.nexa.assistant.speech

import com.nexa.assistant.data.VoiceModel
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Una voce disponibile nell'account ElevenLabs. */
data class ElevenVoice(
    val id: String,
    val name: String,
    /** "premade" = voce predefinita, utilizzabile anche con il piano gratuito. */
    val category: String,
    val gender: String,
    val accent: String,
    val age: String,
    val style: String,
    val previewUrl: String?,
) {
    val isFree: Boolean get() = category == "premade"
}

data class ElevenUsage(val tier: String, val used: Long, val limit: Long) {
    val remaining: Long get() = (limit - used).coerceAtLeast(0)
}

class ElevenLabsException(message: String, val code: Int = 0) : Exception(message)

/** Client minimale per l'API di ElevenLabs. Tutte le chiamate sono bloccanti: usarle fuori dal thread principale. */
class ElevenLabs(private val baseUrl: String = "https://api.elevenlabs.io") {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun request(path: String, key: String) = Request.Builder().url("$baseUrl$path").header("xi-api-key", key)

    private fun fail(resp: Response): Nothing {
        val body = resp.body?.string().orEmpty()
        val detail = runCatching {
            val d = JSONObject(body).opt("detail")
            (d as? JSONObject)?.optString("message") ?: d?.toString()
        }.getOrNull()
        val msg = when (resp.code) {
            401 -> "chiave ElevenLabs non valida o senza i permessi necessari"
            402 -> "questa voce o funzione richiede un piano a pagamento"
            429 -> "crediti esauriti o troppe richieste"
            else -> "errore ${resp.code}"
        }
        throw ElevenLabsException(if (detail.isNullOrBlank()) msg else "$msg ($detail)", resp.code)
    }

    /** Voci dell'account: le predefinite (gratuite) più quelle personali o salvate. */
    fun voices(key: String): List<ElevenVoice> {
        val out = mutableListOf<ElevenVoice>()
        var token: String? = null
        repeat(5) {
            val url = "$baseUrl/v2/voices".toHttpUrl().newBuilder()
                .addQueryParameter("page_size", "100")
                .addQueryParameter("include_total_count", "false")
                .apply { token?.let { addQueryParameter("next_page_token", it) } }
                .build()
            val json = http.newCall(Request.Builder().url(url).header("xi-api-key", key).build()).execute().use { resp ->
                if (!resp.isSuccessful) fail(resp)
                JSONObject(resp.body!!.string())
            }
            val arr = json.optJSONArray("voices")
            if (arr != null) for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                val labels = v.optJSONObject("labels")
                fun label(name: String) = labels?.optString(name).orEmpty().replace('_', ' ').replace('-', ' ')
                out += ElevenVoice(
                    id = v.getString("voice_id"),
                    name = v.optString("name").substringBefore(" - ").trim().ifBlank { "Voce" },
                    category = v.optString("category"),
                    gender = label("gender"),
                    accent = label("accent"),
                    age = label("age"),
                    style = label("descriptive").ifBlank { label("description") },
                    previewUrl = v.optString("preview_url").takeIf { it.startsWith("http") },
                )
            }
            token = json.optString("next_page_token").takeIf { json.optBoolean("has_more") && it.isNotBlank() && it != "null" }
            if (token == null) return out.distinctBy { it.id }
        }
        return out.distinctBy { it.id }
    }

    /** Sintetizza una frase e restituisce l'audio MP3. */
    fun speak(text: String, key: String, voiceId: String, model: VoiceModel, rate: Float): ByteArray {
        val body = JSONObject()
            .put("text", text)
            .put("model_id", model.id)
            .put(
                "voice_settings", JSONObject()
                    .put("stability", 0.5)
                    .put("similarity_boost", 0.8)
                    .put("speed", rate.coerceIn(0.7f, 1.2f).toDouble())
            )
        if (model.supportsLanguageCode) body.put("language_code", "it")
        val req = request("/v1/text-to-speech/$voiceId?output_format=mp3_44100_128", key)
            .header("Accept", "audio/mpeg")
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) fail(resp)
            return resp.body!!.bytes()
        }
    }

    /** Trascrive un audio WAV in italiano. */
    fun transcribe(wav: ByteArray, key: String, modelId: String = "scribe_v3"): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model_id", modelId)
            .addFormDataPart("language_code", "it")
            .addFormDataPart("tag_audio_events", "false")
            .addFormDataPart("file", "voce.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .build()
        http.newCall(request("/v1/speech-to-text", key).post(body).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                // Se il modello più recente non è disponibile per l'account, riprova con il precedente.
                if (modelId == "scribe_v3" && resp.code in listOf(400, 403, 404, 422)) {
                    resp.close()
                    return transcribe(wav, key, "scribe_v2")
                }
                fail(resp)
            }
            return JSONObject(resp.body!!.string()).optString("text").trim()
        }
    }

    fun usage(key: String): ElevenUsage =
        http.newCall(request("/v1/user/subscription", key).build()).execute().use { resp ->
            if (!resp.isSuccessful) fail(resp)
            val j = JSONObject(resp.body!!.string())
            ElevenUsage(j.optString("tier"), j.optLong("character_count"), j.optLong("character_limit"))
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}
