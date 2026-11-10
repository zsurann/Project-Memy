package com.nexa.assistant.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Modelli Claude selezionabili. */
enum class ClaudeModel(val id: String, val label: String, val description: String, val supportsWebSearch: Boolean) {
    OPUS("claude-opus-5-5", "Opus 5.5", "Il più intelligente. Consigliato.", true),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "Molto capace e più economico.", true),
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "Velocissimo ed economicissimo, meno profondo.", false),
}

/** Chi trascrive la tua voce. */
enum class SttEngine(val label: String, val description: String) {
    AUTO("Automatico", "Sceglie il migliore disponibile: Google sul telefono, poi Google Cloud, OpenAI, offline."),
    GOOGLE_DEVICE("Google (sul telefono)", "Usa il riconoscimento di Google se è installata l'app Google. Gratis."),
    GOOGLE_CLOUD("Google Cloud", "Precisione altissima via internet. Serve una chiave Google Cloud."),
    OPENAI("OpenAI", "Trascrizione di alta qualità via internet. Serve una chiave OpenAI."),
    VOSK("Offline (Vosk)", "Funziona senza internet e senza Google. Meno preciso."),
}

/** Chi dà la voce a Nexa. */
enum class VoiceEngine(val label: String, val description: String) {
    GOOGLE_CLOUD("Google Cloud — Chirp 3 HD", "Voci italiane neurali di altissimo livello. 1 milione di caratteri gratis al mese."),
    OPENAI("OpenAI", "Voce molto naturale ed espressiva. Serve una chiave OpenAI."),
    ELEVENLABS("ElevenLabs", "La voce più realistica in assoluto. Serve una chiave ElevenLabs."),
    SYSTEM("Voce del telefono", "Gratis e offline, ma più robotica."),
}

data class VoiceOption(val id: String, val label: String)

object Voices {
    val google = listOf(
        VoiceOption("it-IT-Chirp3-HD-Aoede", "Aoede — donna, calda"),
        VoiceOption("it-IT-Chirp3-HD-Kore", "Kore — donna, decisa"),
        VoiceOption("it-IT-Chirp3-HD-Leda", "Leda — donna, giovane"),
        VoiceOption("it-IT-Chirp3-HD-Zephyr", "Zephyr — donna, luminosa"),
        VoiceOption("it-IT-Chirp3-HD-Charon", "Charon — uomo, profonda"),
        VoiceOption("it-IT-Chirp3-HD-Fenrir", "Fenrir — uomo, energica"),
        VoiceOption("it-IT-Chirp3-HD-Orus", "Orus — uomo, pacata"),
        VoiceOption("it-IT-Chirp3-HD-Puck", "Puck — uomo, vivace"),
    )
    val openai = listOf(
        VoiceOption("coral", "Coral — donna, calorosa"),
        VoiceOption("nova", "Nova — donna, brillante"),
        VoiceOption("shimmer", "Shimmer — donna, morbida"),
        VoiceOption("sage", "Sage — donna, pacata"),
        VoiceOption("marin", "Marin — donna, naturale"),
        VoiceOption("ash", "Ash — uomo, chiara"),
        VoiceOption("onyx", "Onyx — uomo, profonda"),
        VoiceOption("cedar", "Cedar — uomo, naturale"),
    )
    /** Voce predefinita ElevenLabs (multilingue); se ne può incollare un'altra dalla Voice Library. */
    const val ELEVEN_DEFAULT = "21m00Tcm4TlvDq8ikWAM"
}

data class SettingsState(
    val hasApiKey: Boolean,
    val hasGoogleKey: Boolean,
    val hasOpenAiKey: Boolean,
    val hasElevenKey: Boolean,
    val model: ClaudeModel,
    val sttEngine: SttEngine,
    val voiceEngine: VoiceEngine,
    val googleVoice: String,
    val openAiVoice: String,
    val elevenVoiceId: String,
    val speechRate: Float,
    val continuousConversation: Boolean,
    val webSearch: Boolean,
    val autoUpdate: Boolean,
    val userName: String,
)

class NexaSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun read() = SettingsState(
        hasApiKey = prefs.contains(KEY_API),
        hasGoogleKey = prefs.contains(KEY_GOOGLE),
        hasOpenAiKey = prefs.contains(KEY_OPENAI),
        hasElevenKey = prefs.contains(KEY_ELEVEN),
        model = enumPref(KEY_MODEL, ClaudeModel.OPUS),
        sttEngine = enumPref(KEY_STT, SttEngine.AUTO),
        voiceEngine = enumPref(KEY_VOICE, VoiceEngine.SYSTEM),
        googleVoice = prefs.getString(KEY_GOOGLE_VOICE, null) ?: Voices.google.first().id,
        openAiVoice = prefs.getString(KEY_OPENAI_VOICE, null) ?: Voices.openai.first().id,
        elevenVoiceId = prefs.getString(KEY_ELEVEN_VOICE, null) ?: Voices.ELEVEN_DEFAULT,
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
    fun googleKey() = secret(KEY_GOOGLE)
    fun openAiKey() = secret(KEY_OPENAI)
    fun elevenKey() = secret(KEY_ELEVEN)

    fun setApiKey(v: String) = setSecret(KEY_API, v)
    fun setGoogleKey(v: String) = setSecret(KEY_GOOGLE, v)
    fun setOpenAiKey(v: String) = setSecret(KEY_OPENAI, v)
    fun setElevenKey(v: String) = setSecret(KEY_ELEVEN, v)

    fun setModel(v: ClaudeModel) = edit { putString(KEY_MODEL, v.name) }
    fun setSttEngine(v: SttEngine) = edit { putString(KEY_STT, v.name) }
    fun setVoiceEngine(v: VoiceEngine) = edit { putString(KEY_VOICE, v.name) }
    fun setGoogleVoice(v: String) = edit { putString(KEY_GOOGLE_VOICE, v) }
    fun setOpenAiVoice(v: String) = edit { putString(KEY_OPENAI_VOICE, v) }
    fun setElevenVoiceId(v: String) = edit { putString(KEY_ELEVEN_VOICE, v.trim().ifEmpty { Voices.ELEVEN_DEFAULT }) }
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
        const val KEY_GOOGLE = "google_key_enc"
        const val KEY_OPENAI = "openai_key_enc"
        const val KEY_ELEVEN = "eleven_key_enc"
        const val KEY_MODEL = "model"
        const val KEY_STT = "stt_engine"
        const val KEY_VOICE = "voice_engine"
        const val KEY_GOOGLE_VOICE = "google_voice"
        const val KEY_OPENAI_VOICE = "openai_voice"
        const val KEY_ELEVEN_VOICE = "eleven_voice"
        const val KEY_RATE = "speech_rate_v2"
        const val KEY_CONTINUOUS = "continuous"
        const val KEY_WEB = "web_search"
        const val KEY_AUTO_UPDATE = "auto_update"
        const val KEY_NAME = "user_name"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
    }
}
