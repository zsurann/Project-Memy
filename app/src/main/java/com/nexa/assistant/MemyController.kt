package com.nexa.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.nexa.assistant.access.MemyAccessibilityService
import com.nexa.assistant.agent.MemyAgent
import com.nexa.assistant.data.SttEngine
import com.nexa.assistant.speech.SpeechToText
import com.nexa.assistant.speech.SystemSpeechToText
import com.nexa.assistant.speech.VoicePlayer
import com.nexa.assistant.speech.ParakeetModel
import com.nexa.assistant.speech.ParakeetSpeechToText
import com.nexa.assistant.tools.basicTools
import com.nexa.assistant.tools.dataTools
import com.nexa.assistant.tools.notificationTools
import com.nexa.assistant.tools.screenTools
import com.nexa.assistant.tools.systemTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sin

enum class Phase { IDLE, LISTENING, PROCESSING, THINKING, SPEAKING, CONFIRMING }

data class ChatLine(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val isNotice: Boolean = false,
    /** La risposta è ancora in arrivo. */
    val streaming: Boolean = false,
    /** Strumento in uso mentre la risposta è in arrivo (es. "set_alarm"). */
    val tool: String? = null,
)

data class UiState(
    val phase: Phase = Phase.IDLE,
    val partial: String = "",
    val lines: List<ChatLine> = emptyList(),
    val pendingConfirmation: String? = null,
    val modelDownloadProgress: Float? = null,
    val asrModelInstalled: Boolean = false,
    val listenerName: String = "",
)

/** Coordina voce → Claude → azioni → voce. Vive a livello di app (vedi [MemyApp]). */
class MemyController(private val app: MemyApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = app.settings
    private val ids = AtomicLong()

    private val asrModel = ParakeetModel(app)
    private val parakeet = ParakeetSpeechToText(app, asrModel)
    val googleDevice = SystemSpeechToText(app, googleOnly = true)
    private val anySystem = SystemSpeechToText(app, googleOnly = false)
    private var activeStt: SpeechToText? = null

    val voice = VoicePlayer(app, settings).also { v ->
        v.onFallback = { msg -> scope.launch { notice(msg) } }
    }

    private val _state = MutableStateFlow(UiState(asrModelInstalled = asrModel.isInstalled()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Volume della voce (0..1), separato dallo stato per non ridisegnare tutta l'interfaccia. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** L'app è visibile sullo schermo? */
    var inForeground = false
        set(value) {
            field = value
            updateOverlay()
        }

    private var job: Job? = null
    /** Claude sta ancora lavorando (a differenza di [job], che resta attivo finché Memy finisce di parlare). */
    private var agentBusy = false
    private var confirmation: CompletableDeferred<Boolean>? = null
    private var sessionEndJob: Job? = null

    private val agent = MemyAgent(
        basicTools(app) + screenTools() + notificationTools() + dataTools(app, app.memory, app.reminders) + systemTools(app),
        object : MemyAgent.Hooks {
            override suspend fun confirm(prompt: String): Boolean =
                withContext(Dispatchers.Main) { askConfirmation(prompt) }

            override fun onToolUse(toolName: String) {
                updateStreamingLine { it.copy(tool = toolName) }
            }

            override fun onSentence(sentence: String) {
                voice.enqueue(sentence)
                _state.update { if (it.phase == Phase.THINKING) it.copy(phase = Phase.SPEAKING) else it }
            }

            override fun onText(textSoFar: String) = updateStreamingLine { it.copy(text = textSoFar, tool = null) }
        },
    )

    init {
        // Animazione della sfera mentre Memy parla.
        scope.launch {
            var t = 0f
            while (isActive) {
                val phase = _state.value.phase
                if (voice.speaking.value && phase != Phase.LISTENING) {
                    t += 0.18f
                    _level.value = 0.35f + 0.3f * abs(sin(t)) + 0.15f * abs(sin(t * 2.3f))
                } else if (phase != Phase.LISTENING && _level.value > 0.005f) {
                    _level.value *= 0.85f
                }
                delay(50)
            }
        }
        // A ogni cambio di stato: aggiorna l'indicatore sopra le altre app e la sessione di ascolto.
        scope.launch {
            _state.map { it.phase }.distinctUntilChanged().collect { phase ->
                updateOverlay()
                MemySessionService.update(app, sessionText(phase))
                sessionEndJob?.cancel()
                if (phase == Phase.IDLE && !agentBusy) {
                    // Conversazione finita: dopo qualche secondo di calma si spegne il microfono in background.
                    sessionEndJob = scope.launch {
                        delay(SESSION_GRACE_MS)
                        if (_state.value.phase == Phase.IDLE && !agentBusy) MemySessionService.stop(app)
                    }
                }
            }
        }
        voice.refreshCatalog()
        // Carica in anticipo il modello di ascolto e libera lo spazio del vecchio modello Vosk (non più usato).
        parakeet.warmUp()
        scope.launch(Dispatchers.IO) { java.io.File(app.filesDir, "vosk-model-it").deleteRecursively() }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Sceglie il motore di ascolto in base alle impostazioni e a cosa è disponibile. */
    fun resolveStt(): SpeechToText? = when (settings.state.value.sttEngine) {
        SttEngine.AUTO -> listOf(parakeet, googleDevice, anySystem).firstOrNull { it.isReady() }
        SttEngine.PARAKEET -> parakeet.takeIf { it.isReady() }
        SttEngine.GOOGLE_DEVICE -> googleDevice.takeIf { it.isReady() }
    }

    /**
     * Memy può usare il microfono: con l'app aperta sempre; fuori dall'app solo durante una conversazione,
     * grazie al servizio in primo piano avviato quando l'app era visibile.
     */
    private fun canListen() = hasMicPermission() && (inForeground || MemySessionService.running)

    /** Avvia la "sessione" che tiene vivo il microfono quando Memy lavora in un'altra app. */
    private fun ensureSession() {
        sessionEndJob?.cancel()
        if (!MemySessionService.running && inForeground) MemySessionService.start(app, sessionText(_state.value.phase))
    }

    private fun sessionText(phase: Phase) = when (phase) {
        Phase.LISTENING, Phase.CONFIRMING -> "Ti ascolto…"
        Phase.PROCESSING, Phase.THINKING -> "Al lavoro per te"
        Phase.SPEAKING -> "Sto rispondendo"
        Phase.IDLE -> "Conversazione in corso"
    }

    // ------------------------------------------------------------------ ascolto

    /** Tocco sulla sfera: ascolta, interrompe la voce per parlare subito, oppure annulla. */
    fun onOrbPressed() {
        when (_state.value.phase) {
            Phase.IDLE -> startListening()
            Phase.SPEAKING -> {
                job?.cancel(); voice.stop(); agentBusy = false; finishStreamingLine()
                _state.update { it.copy(phase = Phase.IDLE) }
                startListening()
            }
            Phase.CONFIRMING -> Unit
            else -> stopAll()
        }
    }

    fun startListening() {
        val stt = readyOrExplain() ?: return
        ensureSession()
        voice.stop()
        voice.stopPreview()
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
                if (activeStt !== stt) return
                activeStt = null
                _state.update { it.copy(partial = "") }
                if (text.isBlank()) _state.update { it.copy(phase = Phase.IDLE) } else submit(text)
            }

            override fun onError(message: String) {
                if (activeStt !== stt) return
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
        if (!canListen()) return null
        return resolveStt() ?: run {
            notice(
                if (settings.state.value.sttEngine == SttEngine.GOOGLE_DEVICE) {
                    "Il riconoscimento di Google non è disponibile su questo telefono. Scegli un altro motore nelle impostazioni."
                } else {
                    "Per ascoltarti scarica il modello Parakeet in Impostazioni → Ascolto (circa 490 MB, una volta sola, meglio con il Wi-Fi)."
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
        ensureSession()
        job?.cancel()
        confirmation?.complete(false)
        stopStt()
        voice.stop()
        finishStreamingLine()
        addLine(ChatLine(ids.incrementAndGet(), fromUser = true, text = clean))
        addLine(ChatLine(ids.incrementAndGet(), fromUser = false, text = "", streaming = true))
        agentBusy = true
        job = scope.launch {
            _state.update { it.copy(phase = Phase.THINKING, partial = "") }
            val s = settings.state.value
            val reply = try {
                agent.run(clean, MemyAgent.Options(key, s.model, s.userName, s.webSearch, app.memory.items.value))
            } catch (e: MemyAgent.AgentException) {
                (e.message ?: "Si è verificato un errore.").also { voice.enqueue(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                agentBusy = false; finishStreamingLine(); updateOverlay(); throw e
            } catch (e: Exception) {
                "Si è verificato un errore imprevisto: ${e.message}".also { voice.enqueue(it) }
            }
            replaceStreamingLine(reply)
            agentBusy = false
            _state.update { it.copy(phase = if (voice.speaking.value) Phase.SPEAKING else it.phase) }
            updateOverlay()
            voice.awaitIdle()
            _state.update { it.copy(phase = Phase.IDLE) }
            // Conversazione continua: Memy torna ad ascoltarti, anche se sei in un'altra app.
            if (settings.state.value.continuousConversation && canListen()) startListening()
        }
    }

    private inline fun editStreaming(s: UiState, change: (ChatLine) -> ChatLine?): UiState? {
        val i = s.lines.indexOfLast { it.streaming }
        if (i < 0) return null
        val lines = s.lines.toMutableList()
        val updated = change(lines[i])
        if (updated == null) lines.removeAt(i) else lines[i] = updated
        return s.copy(lines = lines)
    }

    private fun updateStreamingLine(change: (ChatLine) -> ChatLine) =
        _state.update { s -> editStreaming(s, change) ?: s }

    private fun replaceStreamingLine(text: String) = _state.update { s ->
        editStreaming(s) { it.copy(text = text, streaming = false, tool = null) }
            ?: s.copy(lines = s.lines + ChatLine(ids.incrementAndGet(), false, text))
    }

    private fun finishStreamingLine() = _state.update { s ->
        editStreaming(s) { if (it.text.isBlank()) null else it.copy(streaming = false, tool = null) } ?: s
    }

    /** Quando Memy è fuori dalla sua app, mostra sopra le altre app cosa sta facendo, con un tocco per fermarla. */
    private fun updateOverlay() {
        val svc = MemyAccessibilityService.instance ?: return
        val phase = _state.value.phase
        if (inForeground || (phase == Phase.IDLE && !agentBusy)) {
            svc.hideStatus()
            return
        }
        val text = when (phase) {
            Phase.LISTENING -> "🎙 Ti ascolto… · tocca per fermare"
            Phase.CONFIRMING -> "🎙 Rispondi sì o no"
            Phase.SPEAKING -> "✦ Memy sta parlando · tocca per fermare"
            else -> "✦ Memy al lavoro · tocca per fermare"
        }
        svc.showStatus(text) { stopAll() }
    }

    // ------------------------------------------------------------------ conferme

    private suspend fun askConfirmation(prompt: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        confirmation = deferred
        voice.awaitIdle()
        _state.update { it.copy(phase = Phase.CONFIRMING, pendingConfirmation = prompt) }

        // Fuori dall'app la domanda compare anche sopra l'app in uso, con i pulsanti.
        val svc = MemyAccessibilityService.instance
        val overlay = if (!inForeground && svc != null) scope.launch { svc.confirm(prompt, deferred) } else null

        voice.speakNow(prompt)

        // Poi Memy ascolta la risposta a voce, dentro o fuori dall'app. Se la risposta non è chiara, riprova.
        var attempts = 0
        val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            while (!deferred.isCompleted && attempts < 3 && canListen()) {
                val stt = resolveStt() ?: break
                attempts++
                val heard = CompletableDeferred<String>()
                activeStt = stt
                stt.start(object : SpeechToText.Listener {
                    override fun onLevel(level: Float) { _level.value = level }
                    override fun onPartial(text: String) { _state.update { it.copy(partial = text) } }
                    override fun onFinal(text: String) { heard.complete(text) }
                    override fun onError(message: String) { heard.complete("") }
                })
                // Il primo che arriva vince: la voce oppure un tocco sui pulsanti.
                val text = kotlinx.coroutines.selects.select {
                    heard.onAwait { it }
                    deferred.onAwait { "" }
                }
                stopStt()
                _state.update { it.copy(partial = "") }
                if (deferred.isCompleted) break
                val parsed = parseYesNo(text)
                if (parsed != null) deferred.complete(parsed)
                else if (text.isNotBlank()) voice.speakNow("Scusa, non ho capito. Rispondi sì oppure no.")
            }
            deferred.await()
        } ?: false
        deferred.complete(answer)
        overlay?.cancel()
        stopStt()
        voice.stop()
        confirmation = null
        _state.update { it.copy(phase = Phase.THINKING, pendingConfirmation = null, partial = "") }
        return answer
    }

    /** Pulsanti "Sì" / "No" a schermo. */
    fun answerConfirmation(yes: Boolean) {
        confirmation?.complete(yes)
    }

    private fun parseYesNo(text: String): Boolean? = Companion.parseYesNo(text)

    // ------------------------------------------------------------------ varie

    fun stopAll() {
        job?.cancel()
        job = null
        agentBusy = false
        confirmation?.complete(false)
        stopStt()
        voice.stop()
        finishStreamingLine()
        _state.update { it.copy(phase = Phase.IDLE, partial = "", pendingConfirmation = null) }
        updateOverlay()
    }

    fun newConversation() {
        stopAll()
        agent.resetConversation()
        _state.update { it.copy(lines = emptyList()) }
    }

    /** Scarica il modello di ascolto Parakeet (circa 490 MB). */
    fun downloadAsrModel() {
        if (_state.value.modelDownloadProgress != null) return
        scope.launch {
            _state.update { it.copy(modelDownloadProgress = 0f) }
            try {
                asrModel.download { p -> _state.update { it.copy(modelDownloadProgress = p) } }
                parakeet.warmUp()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Download del modello di ascolto non riuscito: ${e.message}. Controlla la connessione e riprova.")
            }
            _state.update { it.copy(modelDownloadProgress = null, asrModelInstalled = asrModel.isInstalled()) }
        }
    }

    fun deleteAsrModel() {
        asrModel.delete()
        _state.update { it.copy(asrModelInstalled = false) }
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

    companion object {
        private const val CONFIRM_TIMEOUT_MS = 45_000L
        private const val SESSION_GRACE_MS = 8_000L
        private const val MAX_LINES = 200

        /** Capisce un "sì" o un "no" detto a voce; null se la risposta non è chiara. */
        internal fun parseYesNo(text: String): Boolean? {
            val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            val words = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
            val no = setOf("no", "annulla", "stop", "aspetta", "fermo", "ferma", "lascia", "niente", "negativo", "nope")
            val yes = setOf(
                "si", "certo", "conferma", "confermo", "ok", "okay", "vai", "procedi", "esatto", "invia", "chiama",
                "sicuro", "giusto", "perfetto", "fallo", "certamente", "assolutamente", "yes",
            )
            return when {
                words.any { it in no } -> false
                words.any { it in yes } -> true
                else -> null
            }
        }
    }
}
