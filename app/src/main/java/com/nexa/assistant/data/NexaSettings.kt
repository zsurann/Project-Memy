package com.nexa.assistant.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Modelli Claude selezionabili. Haiku è il predefinito: veloce ed economico. */
enum class ClaudeModel(val id: String, val label: String, val description: String) {
    HAIKU("claude-haiku-5-5", "Haiku 5.5", "Veloce ed economico (consigliato)"),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", "Più capace, costo medio"),
    OPUS("claude-opus-5-5", "Opus 5.5", "Il più intelligente, costo più alto"),
}

/** Motore per capire la voce. */
enum class SpeechEngine(val label: String, val description: String) {
    VOSK("Offline (Vosk)", "Funziona senza Google e senza internet. Scarica ~50 MB una volta."),
    SYSTEM("Sistema", "Usa il riconoscimento vocale del telefono, se presente."),
}

data class SettingsState(
    val hasApiKey: Boolean,
    val model: ClaudeModel,
    val speechEngine: SpeechEngine,
    val speechRate: Float,
    val autoListenAfterReply: Boolean,
    val userName: String,
)

class NexaSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nexa_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun read() = SettingsState(
        hasApiKey = prefs.contains(KEY_API),
        model = runCatching { ClaudeModel.valueOf(prefs.getString(KEY_MODEL, null)!!) }
            .getOrDefault(ClaudeModel.HAIKU),
        speechEngine = runCatching { SpeechEngine.valueOf(prefs.getString(KEY_ENGINE, null)!!) }
            .getOrDefault(SpeechEngine.VOSK),
        speechRate = prefs.getFloat(KEY_RATE, 1.05f),
        autoListenAfterReply = prefs.getBoolean(KEY_AUTO_LISTEN, false),
        userName = prefs.getString(KEY_NAME, "") ?: "",
    )

    private fun refresh() {
        _state.value = read()
    }

    fun apiKey(): String? = prefs.getString(KEY_API, null)?.let { SecureStore.decrypt(it) }

    fun setApiKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) prefs.edit().remove(KEY_API).apply()
        else prefs.edit().putString(KEY_API, SecureStore.encrypt(trimmed)).apply()
        refresh()
    }

    fun setModel(model: ClaudeModel) {
        prefs.edit().putString(KEY_MODEL, model.name).apply(); refresh()
    }

    fun setSpeechEngine(engine: SpeechEngine) {
        prefs.edit().putString(KEY_ENGINE, engine.name).apply(); refresh()
    }

    fun setSpeechRate(rate: Float) {
        prefs.edit().putFloat(KEY_RATE, rate).apply(); refresh()
    }

    fun setAutoListen(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_LISTEN, enabled).apply(); refresh()
    }

    fun setUserName(name: String) {
        prefs.edit().putString(KEY_NAME, name.trim()).apply(); refresh()
    }

    private companion object {
        const val KEY_API = "api_key_enc"
        const val KEY_MODEL = "model"
        const val KEY_ENGINE = "speech_engine"
        const val KEY_RATE = "speech_rate"
        const val KEY_AUTO_LISTEN = "auto_listen"
        const val KEY_NAME = "user_name"
    }
}
