package com.nexa.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Modelli Claude selezionabili. */
enum class ClaudeModel(val id: String, val label: String, val description: String) {
    OPUS("claude-opus-5-5", "Opus 5.5", "Il più intelligente. Ideale per compiti complessi."),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "Molto capace, più economico."),
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "Velocissimo ed economicissimo."),
}

/** Chi trascrive la tua voce. */
enum class SttEngine(val label: String, val description: String) {
    AUTO("Automatico", "Usa il migliore motore gratuito disponibile sul telefono."),
    GOOGLE_DEVICE("Google (sul telefono)", "Gratis, se è installata l'app Google."),
    ELEVENLABS("ElevenLabs Scribe", "Precisione altissima via internet, con la stessa chiave della voce."),
    VOSK("Offline (Vosk)", "Funziona senza internet. Meno preciso."),
}

/** Modello di sintesi vocale ElevenLabs. */
enum class VoiceModel(val id: String, val label: String, val description: String, val supportsLanguageCode: Boolean) {
    FLASH("eleven_flash_v2_5", "Flash 2.5", "Rapidissima e naturale. Consigliata.", true),
    V4_TURBO("eleven_v4_turbo", "v4 Turbo", "La più espressiva in tempo reale.", true),
    MULTILINGUAL("eleven_multilingual_v2", "Multilingual v2", "Molto realistica, un po' più lenta.", false),
}

data class SettingsState(
    val hasApiKey: Boolean,
    val hasElevenKey: Boolean,
    val model: ClaudeModel,
    val sttEngine: SttEngine,
    val voiceId: String,
    val voiceName: String,
    val voiceModel: VoiceModel,
    val speechRate: Float,
    val continuousConversation: Boolean,
    val webSearch: Boolean,
    val autoUpdate: Boolean,
    val userName: String,
)

class MemySettings(context: Context) {
    // Il nome del file resta quello storico ("nexa_settings") per non perdere le impostazioni dopo il cambio di nome.
    private val prefs: SharedPreferences = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)

    init {
        // Pulizia: chiavi e scelte di servizi che Memy non usa più (voci Google Cloud e OpenAI).
        if (prefs.contains("google_key_enc") || prefs.contains("openai_key_enc")) {
            prefs.edit().remove("google_key_enc").remove("openai_key_enc").remove("voice_engine")
                .remove("google_voice").remove("openai_voice").remove("eleven_voice").apply()
        }
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun read() = SettingsState(
        hasApiKey = prefs.contains(KEY_API),
        hasElevenKey = prefs.contains(KEY_ELEVEN),
        model = enumPref(KEY_MODEL, ClaudeModel.OPUS),
        sttEngine = enumPref(KEY_STT, SttEngine.AUTO),
        voiceId = prefs.getString(KEY_VOICE_ID, "") ?: "",
        voiceName = prefs.getString(KEY_VOICE_NAME, "") ?: "",
        voiceModel = enumPref(KEY_VOICE_MODEL, VoiceModel.FLASH),
        speechRate = prefs.getFloat(KEY_RATE, 1.0f),
        continuousConversation = prefs.getBoolean(KEY_CONTINUOUS, true),
        webSearch = prefs.getBoolean(KEY_WEB, true),
        autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        userName = prefs.getString(KEY_NAME, "") ?: "",
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    private fun secret(key: String): String? = prefs.getString(key, null)?.let { SecureStore.decrypt(it) }

    private fun setSecret(key: String, value: String) = edit {
        val v = value.trim()
        if (v.isEmpty()) remove(key) else putString(key, SecureStore.encrypt(v))
    }

    fun apiKey() = secret(KEY_API)
    fun elevenKey() = secret(KEY_ELEVEN)
    fun setApiKey(v: String) = setSecret(KEY_API, v)
    fun setElevenKey(v: String) = setSecret(KEY_ELEVEN, v)

    fun setModel(v: ClaudeModel) = edit { putString(KEY_MODEL, v.name) }
    fun setSttEngine(v: SttEngine) = edit { putString(KEY_STT, v.name) }
    fun setVoice(id: String, name: String) = edit { putString(KEY_VOICE_ID, id); putString(KEY_VOICE_NAME, name) }
    fun setVoiceModel(v: VoiceModel) = edit { putString(KEY_VOICE_MODEL, v.name) }
    fun setSpeechRate(v: Float) = edit { putFloat(KEY_RATE, v) }
    fun setContinuous(v: Boolean) = edit { putBoolean(KEY_CONTINUOUS, v) }
    fun setWebSearch(v: Boolean) = edit { putBoolean(KEY_WEB, v) }
    fun setAutoUpdate(v: Boolean) = edit { putBoolean(KEY_AUTO_UPDATE, v) }
    fun setUserName(v: String) = edit { putString(KEY_NAME, v.trim()) }

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    private companion object {
        const val KEY_API = "api_key_enc"
        const val KEY_ELEVEN = "eleven_key_enc"
        const val KEY_MODEL = "model"
        const val KEY_STT = "stt_engine"
        const val KEY_VOICE_ID = "eleven_voice_id_v2"
        const val KEY_VOICE_NAME = "eleven_voice_name"
        const val KEY_VOICE_MODEL = "eleven_voice_model"
        const val KEY_RATE = "speech_rate_v2"
        const val KEY_CONTINUOUS = "continuous"
        const val KEY_WEB = "web_search"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_NAME = "user_name"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
