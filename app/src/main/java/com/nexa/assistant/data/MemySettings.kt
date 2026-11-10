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
    AUTO("Automatico", "Usa Google se è installato, altrimenti il riconoscimento offline."),
    GOOGLE_DEVICE("Google (sul telefono)", "Gratis e preciso, se è installata l'app Google."),
    VOSK("Offline (Vosk)", "Funziona senza internet e senza Google."),
}

/** La voce predefinita di Memy (Microsoft Edge, gratuita). */
object DefaultVoice {
    const val ID = "it-IT-IsabellaNeural"
    const val NAME = "Isabella"
}

data class SettingsState(
    val hasApiKey: Boolean,
    val model: ClaudeModel,
    val sttEngine: SttEngine,
    val voiceId: String,
    val voiceName: String,
    /** Velocità della voce in percentuale rispetto al normale (-30..+40). */
    val voiceRate: Int,
    /** Tono della voce in Hz rispetto al normale (-20..+20). */
    val voicePitch: Int,
    val continuousConversation: Boolean,
    val webSearch: Boolean,
    val autoUpdate: Boolean,
    val userName: String,
)

class MemySettings(context: Context) {
    // Il nome del file resta quello storico ("nexa_settings") per non perdere le impostazioni dopo il cambio di nome.
    private val prefs: SharedPreferences = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)

    init {
        // Pulizia: chiavi e scelte di servizi vocali che Memy non usa più (Google Cloud, OpenAI, ElevenLabs).
        val obsolete = listOf(
            "google_key_enc", "openai_key_enc", "eleven_key_enc", "voice_engine", "google_voice", "openai_voice",
            "eleven_voice", "eleven_voice_id_v2", "eleven_voice_name", "eleven_voice_model", "speech_rate_v2",
        )
        if (obsolete.any(prefs::contains)) prefs.edit().apply { obsolete.forEach { remove(it) } }.apply()
        if (prefs.getString(KEY_STT, null) == "ELEVENLABS") prefs.edit().remove(KEY_STT).apply()
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun read() = SettingsState(
        hasApiKey = prefs.contains(KEY_API),
        model = enumPref(KEY_MODEL, ClaudeModel.OPUS),
        sttEngine = enumPref(KEY_STT, SttEngine.AUTO),
        voiceId = prefs.getString(KEY_VOICE_ID, null) ?: DefaultVoice.ID,
        voiceName = prefs.getString(KEY_VOICE_NAME, null) ?: DefaultVoice.NAME,
        voiceRate = prefs.getInt(KEY_RATE, 0),
        voicePitch = prefs.getInt(KEY_PITCH, 0),
        continuousConversation = prefs.getBoolean(KEY_CONTINUOUS, true),
        webSearch = prefs.getBoolean(KEY_WEB, true),
        autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, true),
        userName = prefs.getString(KEY_NAME, "") ?: "",
    )

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun apiKey(): String? = prefs.getString(KEY_API, null)?.let { SecureStore.decrypt(it) }

    fun setApiKey(value: String) = edit {
        val v = value.trim()
        if (v.isEmpty()) remove(KEY_API) else putString(KEY_API, SecureStore.encrypt(v))
    }

    fun setModel(v: ClaudeModel) = edit { putString(KEY_MODEL, v.name) }
    fun setSttEngine(v: SttEngine) = edit { putString(KEY_STT, v.name) }
    fun setVoice(id: String, name: String) = edit { putString(KEY_VOICE_ID, id); putString(KEY_VOICE_NAME, name) }
    fun setVoiceRate(v: Int) = edit { putInt(KEY_RATE, v.coerceIn(-30, 40)) }
    fun setVoicePitch(v: Int) = edit { putInt(KEY_PITCH, v.coerceIn(-20, 20)) }
    fun setContinuous(v: Boolean) = edit { putBoolean(KEY_CONTINUOUS, v) }
    fun setWebSearch(v: Boolean) = edit { putBoolean(KEY_WEB, v) }
    fun setAutoUpdate(v: Boolean) = edit { putBoolean(KEY_AUTO_UPDATE, v) }
    fun setUserName(v: String) = edit { putString(KEY_NAME, v.trim()) }

    var lastUpdateCheck: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(v) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, v).apply()

    private companion object {
        const val KEY_API = "api_key_enc"
        const val KEY_MODEL = "model"
        const val KEY_STT = "stt_engine"
        const val KEY_VOICE_ID = "edge_voice_id"
        const val KEY_VOICE_NAME = "edge_voice_name"
        const val KEY_RATE = "edge_voice_rate"
        const val KEY_PITCH = "edge_voice_pitch"
        const val KEY_CONTINUOUS = "continuous"
        const val KEY_WEB = "web_search"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_NAME = "user_name"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
