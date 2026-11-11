package com.nexa.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.nexa.assistant.speech.SpeechToText

/**
 * Con questi tre servizi Android riconosce Memy come "app assistente digitale": si può scegliere al posto
 * dell'assistente del telefono e richiamare con il gesto o il tasto dedicato.
 */
class MemyVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // Il sistema avvia l'assistente predefinito da solo, anche dopo un riavvio: da qui riparte "Ehi Memy".
        MemyWakeService.sync(this)
    }
}

/** Gesto o tasto dell'assistente: apre Memy già in ascolto. */
class MemyAssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = object : VoiceInteractionSession(this) {
        override fun onPrepareShow(args: Bundle?, showFlags: Int) {
            super.onPrepareShow(args, showFlags)
            setUiEnabled(false) // nessuna finestra intermedia: si passa direttamente a Memy
        }

        override fun onShow(args: Bundle?, showFlags: Int) {
            super.onShow(args, showFlags)
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_ASSIST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startAssistantActivity(intent) }.onFailure { runCatching { context.startActivity(intent) } }
            hide()
        }
    }
}

/**
 * Servizio di riconoscimento vocale richiesto da Android a ogni assistente. Usa lo stesso ascolto di Memy
 * (Whisper o Parakeet) per le app che chiedono la dettatura al sistema.
 */
class MemyRecognitionService : RecognitionService() {

    private var engine: SpeechToText? = null
    private val controller get() = (application as MemyApp).controller

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        finish()
        val stt = if (controller.state.value.phase == Phase.IDLE && controller.hasMicPermission()) controller.newDictationEngine() else null
        if (stt == null) {
            runCatching {
                listener.error(if (controller.state.value.phase != Phase.IDLE) SpeechRecognizer.ERROR_RECOGNIZER_BUSY else SpeechRecognizer.ERROR_CLIENT)
            }
            return
        }
        engine = stt
        controller.dictating.value = true
        fun results(text: String) = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
            putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.9f))
        }
        stt.start(object : SpeechToText.Listener {
            var speaking = false
            override fun onReady() { runCatching { listener.readyForSpeech(Bundle()) } }
            override fun onLevel(level: Float) { runCatching { listener.rmsChanged(level * 12f - 2f) } }
            override fun onPartial(text: String) {
                runCatching {
                    if (!speaking) { speaking = true; listener.beginningOfSpeech() }
                    listener.partialResults(results(text))
                }
            }
            override fun onProcessing() { runCatching { listener.endOfSpeech() } }
            override fun onFinal(text: String) {
                finish()
                runCatching { if (text.isBlank()) listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT) else listener.results(results(text)) }
            }
            override fun onError(message: String) {
                finish()
                runCatching { listener.error(SpeechRecognizer.ERROR_SERVER) }
            }
        })
    }

    private fun finish() {
        engine?.stop()
        engine = null
        controller.dictating.value = false
    }

    override fun onStopListening(listener: Callback) = finish()
    override fun onCancel(listener: Callback) = finish()

    override fun onDestroy() {
        finish()
        super.onDestroy()
    }
}

/** Stato e impostazioni del ruolo di assistente predefinito. */
object AssistantRole {
    fun isHeld(context: Context): Boolean {
        val role = runCatching {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()
        if (role == true) return true
        val current = Settings.Secure.getString(context.contentResolver, "voice_interaction_service")
            ?: Settings.Secure.getString(context.contentResolver, "assistant")
        return current?.startsWith("${context.packageName}/") == true
    }

    /** Apre la schermata di sistema in cui scegliere l'assistente; false se il telefono non ne offre una. */
    fun openSettings(context: Context): Boolean = listOf(
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
    ).any { intent ->
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
