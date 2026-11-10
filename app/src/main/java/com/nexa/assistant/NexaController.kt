package com.nexa.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nexa.assistant.agent.NexaAgent
import com.nexa.assistant.data.SttEngine
import com.nexa.assistant.speech.CloudSpeechToText
import com.nexa.assistant.speech.SpeechToText
import com.nexa.assistant.speech.SystemSpeechToText
import com.nexa.assistant.speech.VoicePlayer
import com.nexa.assistant.speech.VoskModelManager
import com.nexa.assistant.speech.VoskSpeechToText
import com.nexa.assistant.tools.phoneTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong

enum class Phase { IDLE, LISTENING, PROCESSING, THINKING, SPEAKING, CONFIRMING }

data class ChatLine(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val isNotice: Boolean = false,
    val streaming: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val lines: List<ChatLine> = emptyList(),
    val toolInUse: String? = null,
    val pendingConfirmation: String? = null,
    val modelDownloadProgress: Float? = null,
    val voiceModelInstalled: Boolean = false,
    val listenerName: String = "",
)

/** Coordina voce → Claude → azioni → voce. Vive a livello di app (vedi [NexaApp]). */
class NexaController(private val app: NexaApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = app.settings
    private val ids = AtomicLong()

    private val voskModels = VoskModelManager(app)
    private val vosk = VoskSpeechToText(voskModels)
    val googleDevice = SystemSpeechToText(app, googleOnly = true)
    private val anySystem = SystemSpeechToText(app, googleOnly = false)
    private val googleCloud = CloudSpeechToText.googleCloud { settings.googleKey() }
    private val openAi = CloudSpeechToText.openAi { settings.openAiKey() }
    private var activeStt: SpeechToText? = null

    val voice = VoicePlayer(app, settings).also { v ->
        v.onFallback = { msg -> scope.launch { notice(msg) } }
    }

    private val _state = MutableStateFlow(UiState(voiceModelInstalled = voskModels.isInstalled()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Volume della voce (0..1), separato dallo stato per non ridisegnare tutta l'interfaccia. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** L'app è visibile? Il microfono si può usare solo in primo piano. */
    var inForeground = false

    private var job: Job? = null
    private var confirmation: CompletableDeferred<Boolean>? = null

    private val agent = NexaAgent(phoneTools(app), object : NexaAgent.Hooks {
        override suspend fun confirm(prompt: String): Boolean =
            withContext(Dispatchers.Main) { askConfirmation(prompt) }

        override fun onToolUse(toolName: String) {
            _state.update { it.copy(toolInUse = toolName) }
        }

        override fun onSentence(sentence: String) {
            voice.enqueue(sentence)
            _state.update { if (it.phase == Phase.THINKING) it.copy(phase = Phase.SPEAKING) else it }
        }

        override fun onText(textSoFar: String) {
            _state.update { s ->
                val last = s.lines.lastOrNull()
                if (last != null && last.streaming) s.copy(lines = s.lines.dropLast(1) + last.copy(text = textSoFar)) else s
            }
        }
    })

    init {
        // Animazione della sfera mentre Nexa parla.
        scope.launch {
            var t = 0f
            while (isActive) {
                if (voice.speaking.value && _state.value.phase != Phase.LISTENING) {
                    t += 0.18f
                    _level.value = 0.35f + 0.3f * kotlin.math.abs(kotlin.math.sin(t)) + 0.15f * kotlin.math.abs(kotlin.math.sin(t * 2.3f))
                } else if (_state.value.phase != Phase.LISTENING) {
                    _level.value = (_level.value * 0.85f)
                }
                delay(50)
            }
        }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Sceglie il motore di ascolto in base alle impostazioni e a cosa è disponibile. */
    fun resolveStt(): SpeechToText? = when (settings.state.value.sttEngine) {
        SttEngine.AUTO -> listOf(googleDevice, googleCloud, openAi, vosk, anySystem).firstOrNull { it.isReady() }
        SttEngine.GOOGLE_DEVICE -> googleDevice.takeIf { it.isReady() }
        SttEngine.GOOGLE_CLOUD -> googleCloud.takeIf { it.isReady() }
        SttEngine.OPENAI -> openAi.takeIf { it.isReady() }
        SttEngine.VOSK -> vosk.takeIf { it.isReady() }
    }

    // ------------------------------------------------------------------ ascolto

    /** Tocco sulla sfera: ascolta, interrompe la voce per parlare subito, oppure annulla. */
    fun onOrbPressed() {
        when (_state.value.phase) {
            Phase.IDLE -> startListening()
            Phase.SPEAKING -> {
                job?.cancel(); voice.stop(); finishStreamingLine()
                _state.update { it.copy(phase = Phase.IDLE, toolInUse = null) }
                startListening()
            }
            Phase.CONFIRMING -> Unit
            else -> stopAll()
        }
    }

    fun startListening() {
        val stt = readyOrExplain() ?: return
        voice.stop()
        stopStt()
        activeStt = stt
        _state.update { it.copy(phase = Phase.LISTENING, partial = "", listenerName = stt.name) }
        stt.start(object : SpeechToText.Listener {
            override fun onLevel(level: Float) {
                _level.value = _level.value * 0.4f + level * 0.6f
            }

            override fun onPartial(text: String) {
                _state.update { it.copy(partial = text) }
            }

            override fun onProcessing() {
                _state.update { if (it.phase == Phase.LISTENING) it.copy(phase = Phase.PROCESSING) else it }
            }

            override fun onFinal(text: String) {
                activeStt = null
                _state.update { it.copy(partial = "") }
                if (text.isBlank()) _state.update { it.copy(phase = Phase.IDLE) } else submit(text)
            }

            override fun onError(message: String) {
                activeStt = null
                notice(message)
                _state.update { it.copy(phase = Phase.IDLE, partial = "") }
            }
        })
    }

    private fun stopStt() {
        activeStt?.stop()
        activeStt = null
    }

    private fun readyOrExplain(): SpeechToText? {
        if (!hasMicPermission()) {
            notice("Serve il permesso del microfono."); return null
        }
        if (!settings.state.value.hasApiKey) {
            notice("Inserisci prima la tua chiave API di Claude nelle impostazioni."); return null
        }
        return resolveStt() ?: run {
            notice(
                when (settings.state.value.sttEngine) {
                    SttEngine.GOOGLE_DEVICE -> "Il riconoscimento di Google non è disponibile su questo telefono. Scegli un altro motore nelle impostazioni."
                    SttEngine.GOOGLE_CLOUD -> "Inserisci la chiave Google Cloud nelle impostazioni."
                    SttEngine.OPENAI -> "Inserisci la chiave OpenAI nelle impostazioni."
                    else -> "Configura il riconoscimento vocale nelle impostazioni (ad esempio scarica la voce offline)."
                }
            )
            null
        }
    }

    // ------------------------------------------------------------------ elaborazione

    /** Invia un comando (detto a voce o scritto). */
    fun submit(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val key = settings.apiKey()
        if (key.isNullOrBlank()) {
            notice("Inserisci prima la tua chiave API di Claude nelle impostazioni."); return
        }
        job?.cancel()
        voice.stop()
        addLine(ChatLine(ids.incrementAndGet(), fromUser = true, text = clean))
        addLine(ChatLine(ids.incrementAndGet(), fromUser = false, text = "", streaming = true))
        job = scope.launch {
            _state.update { it.copy(phase = Phase.THINKING, toolInUse = null) }
            val s = settings.state.value
            val reply = try {
                agent.run(clean, NexaAgent.Options(key, s.model, s.userName, s.webSearch))
            } catch (e: NexaAgent.AgentException) {
                (e.message ?: "Si è verificato un errore.").also { voice.enqueue(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                finishStreamingLine(); throw e
            } catch (e: Exception) {
                "Si è verificato un errore imprevisto: ${e.message}".also { voice.enqueue(it) }
            }
            replaceStreamingLine(reply)
            _state.update { it.copy(toolInUse = null, phase = if (voice.speaking.value) Phase.SPEAKING else it.phase) }
            voice.awaitIdle()
            _state.update { it.copy(phase = Phase.IDLE) }
            if (settings.state.value.continuousConversation && inForeground) startListening()
        }
    }

    private fun replaceStreamingLine(text: String) = _state.update { s ->
        val last = s.lines.lastOrNull()
        if (last != null && last.streaming) s.copy(lines = s.lines.dropLast(1) + last.copy(text = text, streaming = false))
        else s.copy(lines = s.lines + ChatLine(ids.incrementAndGet(), false, text))
    }

    private fun finishStreamingLine() = _state.update { s ->
        val last = s.lines.lastOrNull()
        when {
            last == null || !last.streaming -> s
            last.text.isBlank() -> s.copy(lines = s.lines.dropLast(1))
            else -> s.copy(lines = s.lines.dropLast(1) + last.copy(streaming = false))
        }
    }

    // ------------------------------------------------------------------ conferme

    private suspend fun askConfirmation(prompt: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmation = deferred
        voice.awaitIdle()
        _state.update { it.copy(phase = Phase.CONFIRMING, pendingConfirmation = prompt) }
        voice.speakNow(prompt)

        // Dopo aver parlato, ascolta un "sì" o un "no" (i pulsanti funzionano comunque).
        val stt = resolveStt()
        if (!deferred.isCompleted && hasMicPermission() && stt != null) {
            activeStt = stt
            stt.start(object : SpeechToText.Listener {
                override fun onLevel(level: Float) { _level.value = level }
                override fun onPartial(text: String) { _state.update { it.copy(partial = text) } }
                override fun onFinal(text: String) {
                    _state.update { it.copy(partial = "") }
                    parseYesNo(text)?.let { deferred.complete(it) }
                }
                override fun onError(message: String) {}
            })
        }
        val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { deferred.await() } ?: false
        stopStt()
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
        val yes = setOf("si", "certo", "conferma", "confermo", "ok", "okay", "vai", "procedi", "esatto", "invia", "chiama", "sicuro", "giusto", "perfetto")
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
        stopStt()
        voice.stop()
        finishStreamingLine()
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
                notice("Riconoscimento offline italiano installato.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Download non riuscito: ${e.message}. Controlla la connessione e riprova.")
            }
            _state.update { it.copy(modelDownloadProgress = null, voiceModelInstalled = voskModels.isInstalled()) }
        }
    }

    fun testVoice() {
        voice.stop()
        val name = settings.state.value.userName
        voice.enqueue("Ciao${if (name.isNotBlank()) " $name" else ""}, sono Nexa. È così che suona la mia voce: ti piace?")
    }

    private fun notice(text: String) = addLine(ChatLine(ids.incrementAndGet(), fromUser = false, text = text, isNotice = true))

    private fun addLine(line: ChatLine) {
        _state.update { it.copy(lines = (it.lines + line).takeLast(MAX_LINES)) }
    }

    /** Solo per gli screenshot di prova. */
    @androidx.annotation.VisibleForTesting
    internal fun setPreviewState(state: UiState, level: Float) {
        _state.value = state
        _level.value = level
    }

    private companion object {
        const val CONFIRM_TIMEOUT_MS = 30_000L
        const val MAX_LINES = 200
    }
}
