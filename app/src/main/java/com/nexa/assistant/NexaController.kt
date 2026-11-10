package com.nexa.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nexa.assistant.agent.NexaAgent
import com.nexa.assistant.data.SpeechEngine
import com.nexa.assistant.speech.Speaker
import com.nexa.assistant.speech.SpeechToText
import com.nexa.assistant.speech.SystemSpeechToText
import com.nexa.assistant.speech.VoskModelManager
import com.nexa.assistant.speech.VoskSpeechToText
import com.nexa.assistant.tools.phoneTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING, CONFIRMING }

data class ChatLine(val fromUser: Boolean, val text: String, val isNotice: Boolean = false)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val lines: List<ChatLine> = emptyList(),
    val toolInUse: String? = null,
    val pendingConfirmation: String? = null,
    val modelDownloadProgress: Float? = null,
    val voiceModelInstalled: Boolean = false,
    val speakerStatus: Speaker.Status = Speaker.Status.LOADING,
)

/** Coordina voce → Claude → azioni → voce. Vive a livello di app (vedi [NexaApp]). */
class NexaController(private val app: NexaApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = app.settings

    private val voskModels = VoskModelManager(app)
    private val vosk = VoskSpeechToText(voskModels)
    private val system = SystemSpeechToText(app)
    private val speaker = Speaker(app)

    private val _state = MutableStateFlow(UiState(voiceModelInstalled = voskModels.isInstalled()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null

    private val agent = NexaAgent(phoneTools(app), object : NexaAgent.Hooks {
        override suspend fun confirm(prompt: String): Boolean =
            withContext(Dispatchers.Main) { askConfirmation(prompt) }
        override fun onToolUse(toolName: String) {
            _state.update { it.copy(toolInUse = toolName) }
        }
    })

    init {
        scope.launch {
            val st = speaker.status()
            _state.update { it.copy(speakerStatus = st) }
        }
    }

    private fun stt(): SpeechToText =
        if (settings.state.value.speechEngine == SpeechEngine.SYSTEM && system.isReady()) system else vosk

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ ascolto

    /** Tocco sul microfono: inizia ad ascoltare, oppure interrompe quello che Nexa sta facendo. */
    fun onMicPressed() {
        when (_state.value.phase) {
            Phase.IDLE -> startListening()
            Phase.CONFIRMING -> Unit
            else -> stopAll()
        }
    }

    fun startListening() {
        if (!readyOrExplain()) return
        speaker.stop()
        _state.update { it.copy(phase = Phase.LISTENING, partial = "") }
        stt().start(object : SpeechToText.Listener {
            override fun onPartial(text: String) {
                _state.update { it.copy(partial = text) }
            }

            override fun onFinal(text: String) {
                _state.update { it.copy(partial = "") }
                if (text.isBlank()) {
                    _state.update { it.copy(phase = Phase.IDLE) }
                } else submit(text)
            }

            override fun onError(message: String) {
                notice(message)
                _state.update { it.copy(phase = Phase.IDLE, partial = "") }
            }
        })
    }

    private fun readyOrExplain(): Boolean {
        if (!hasMicPermission()) {
            notice("Serve il permesso del microfono.")
            return false
        }
        if (!settings.state.value.hasApiKey) {
            notice("Inserisci prima la tua chiave API di Claude nelle impostazioni (icona ingranaggio).")
            return false
        }
        if (!stt().isReady()) {
            notice("Scarica prima la voce offline italiana nelle impostazioni (circa 50 MB, una volta sola).")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ elaborazione

    /** Invia un comando (detto a voce o scritto). */
    fun submit(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val key = settings.apiKey()
        if (key.isNullOrBlank()) {
            notice("Inserisci prima la tua chiave API di Claude nelle impostazioni.")
            return
        }
        addLine(ChatLine(fromUser = true, text = clean))
        job?.cancel()
        job = scope.launch {
            _state.update { it.copy(phase = Phase.THINKING, toolInUse = null) }
            val s = settings.state.value
            val reply = try {
                agent.run(clean, key, s.model, s.userName)
            } catch (e: NexaAgent.AgentException) {
                e.message ?: "Si è verificato un errore."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "Si è verificato un errore imprevisto: ${e.message}"
            }
            addLine(ChatLine(fromUser = false, text = reply))
            say(reply)
            _state.update { it.copy(phase = Phase.IDLE, toolInUse = null) }
            if (settings.state.value.autoListenAfterReply && !endsConversation(reply)) startListening()
        }
    }

    private suspend fun say(text: String) {
        _state.update { it.copy(phase = Phase.SPEAKING) }
        speaker.rate = settings.state.value.speechRate
        speaker.speak(text)
    }

    private fun endsConversation(reply: String) = !reply.trimEnd().endsWith("?")

    // ------------------------------------------------------------------ conferme

    private suspend fun askConfirmation(prompt: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmation = deferred
        addLine(ChatLine(fromUser = false, text = prompt))
        _state.update { it.copy(phase = Phase.CONFIRMING, pendingConfirmation = prompt) }
        speaker.rate = settings.state.value.speechRate
        speaker.speak(prompt)

        // Dopo aver parlato, ascolta un "sì" o un "no" (i pulsanti a schermo funzionano comunque).
        if (!deferred.isCompleted && hasMicPermission() && stt().isReady()) {
            stt().start(object : SpeechToText.Listener {
                override fun onPartial(text: String) {
                    _state.update { it.copy(partial = text) }
                }

                override fun onFinal(text: String) {
                    _state.update { it.copy(partial = "") }
                    if (text.isNotBlank()) addLine(ChatLine(fromUser = true, text = text))
                    when (parseYesNo(text)) {
                        true -> deferred.complete(true)
                        false -> deferred.complete(false)
                        null -> Unit // risposta non chiara: resta in attesa dei pulsanti
                    }
                }

                override fun onError(message: String) {}
            })
        }
        val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { deferred.await() } ?: false
        stt().stop()
        confirmation = null
        _state.update { it.copy(phase = Phase.THINKING, pendingConfirmation = null, partial = "") }
        return answer
    }

    /** Pulsanti "Sì" / "No" a schermo. */
    fun answerConfirmation(yes: Boolean) {
        confirmation?.complete(yes)
    }

    private fun parseYesNo(text: String): Boolean? {
        val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val words = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
        val no = setOf("no", "annulla", "stop", "aspetta", "fermo", "lascia", "niente", "negativo")
        val yes = setOf("si", "certo", "conferma", "confermo", "ok", "okay", "vai", "procedi", "esatto", "invia", "chiama", "sicuro", "giusto")
        return when {
            words.any { it in no } -> false
            words.any { it in yes } -> true
            else -> null
        }
    }

    // ------------------------------------------------------------------ varie

    fun stopAll() {
        job?.cancel()
        confirmation?.complete(false)
        vosk.stop()
        system.stop()
        speaker.stop()
        _state.update { it.copy(phase = Phase.IDLE, partial = "", toolInUse = null, pendingConfirmation = null) }
    }

    fun newConversation() {
        stopAll()
        agent.resetConversation()
        _state.update { it.copy(lines = emptyList()) }
    }

    fun downloadVoiceModel() {
        if (_state.value.modelDownloadProgress != null) return
        scope.launch {
            _state.update { it.copy(modelDownloadProgress = 0f) }
            try {
                voskModels.download { p -> _state.update { it.copy(modelDownloadProgress = p) } }
                notice("Voce offline italiana installata. Ora puoi parlare con Nexa!")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Download della voce non riuscito: ${e.message}. Controlla la connessione e riprova.")
            }
            _state.update {
                it.copy(modelDownloadProgress = null, voiceModelInstalled = voskModels.isInstalled())
            }
        }
    }

    fun testVoice() {
        scope.launch {
            speaker.rate = settings.state.value.speechRate
            speaker.speak("Ciao, sono Nexa. Così suona la mia voce.")
        }
    }

    private fun notice(text: String) = addLine(ChatLine(fromUser = false, text = text, isNotice = true))

    private fun addLine(line: ChatLine) {
        _state.update { it.copy(lines = (it.lines + line).takeLast(MAX_LINES)) }
    }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 30_000L
        const val MAX_LINES = 200
    }
}
