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
import com.nexa.assistant.speech.CaptureSpeechToText
import com.nexa.assistant.speech.BargeInDetector
import com.nexa.assistant.speech.Echo
import com.nexa.assistant.speech.SpokenWords
import com.nexa.assistant.speech.GroqWhisper
import com.nexa.assistant.speech.WakeWord
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
import kotlinx.coroutines.flow.first
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
    private val groq = GroqWhisper()
    /** Whisper su Groq per la frase finale; testo parziale e riserva senza internet con Parakeet (se scaricato). */
    private val whisper = CaptureSpeechToText(
        app, "Whisper", asrModel, ready = { !settings.groqKey().isNullOrBlank() },
    ) { samples -> groq.transcribe(samples, settings.groqKey().orEmpty(), whisperPrompt()) }
    private val parakeet = CaptureSpeechToText(app, "Parakeet", asrModel, ready = { asrModel.isInstalled() }, finalTranscriber = null)

    /** Il microfono è usato dal servizio di dettatura ([MemyRecognitionService]): la parola di attivazione aspetta. */
    val dictating = MutableStateFlow(false)

    /** Un motore di ascolto a parte (dettatura nelle altre app, ascolto mentre Memy lavora), o null se non ce n'è uno pronto. */
    fun newDictationEngine(ctx: android.content.Context = app): CaptureSpeechToText? = when {
        whisper.isReady() -> CaptureSpeechToText(ctx, "Whisper", asrModel, ready = { true }) { samples ->
            groq.transcribe(samples, settings.groqKey().orEmpty(), GroqWhisper.DEFAULT_PROMPT)
        }
        parakeet.isReady() -> CaptureSpeechToText(ctx, "Parakeet", asrModel, ready = { true }, finalTranscriber = null)
        else -> null
    }
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
    /** Numero della richiesta in corso: una richiesta annullata non deve toccare lo stato di quella nuova. */
    private var runSeq = 0
    /** Claude sta ancora lavorando (a differenza di [job], che resta attivo finché Memy finisce di parlare). */
    private var agentBusy = false
    private var confirmation: CompletableDeferred<Boolean>? = null
    private var sessionEndJob: Job? = null

    // Interruzione a voce: mentre Memy parla un rilevatore ascolta se inizi a parlare tu.
    private val barge = BargeInDetector(app)
    private var bargeRunning = false
    /** Ascolto di ciò che dici mentre Memy lavora o parla (passa da qui prima di interromperla). */
    private var workStt: CaptureSpeechToText? = null
    /** Ciò che hai detto sopra la domanda di conferma di Memy: vale come risposta. */
    private var overHeard: String? = null
    private val userSpeaking = MutableStateFlow(false)
    /** Interruzioni in cui poi non hai detto nulla: se si ripetono, il microfono sta sentendo la voce di Memy. */
    private var falseBarges = 0
    /** Ultime parole dette da Memy, per riconoscere l'eco della sua voce. */
    @Volatile private var lastSpoken = ""

    private fun spoke(text: String) {
        lastSpoken = (lastSpoken + " " + text).takeLast(900)
    }

    private val agent = MemyAgent(
        basicTools(app) + screenTools() + notificationTools() + dataTools(app, app.memory, app.reminders) + systemTools(app) +
            com.nexa.assistant.tools.documentTools(app) + com.nexa.assistant.tools.zipTools(app),
        object : MemyAgent.Hooks {
            override suspend fun confirm(prompt: String): Boolean =
                withContext(Dispatchers.Main) { askConfirmation(prompt) }

            override suspend fun beforeTool() {
                // Se stai parlando, Memy aspetta di sentire cosa dici prima di fare il passo successivo.
                userSpeaking.first { !it }
            }

            override fun onWorkStarted() {
                // Da qui Memy lavora in silenzio: eventuali frasi di circostanza non vengono lette.
                scope.launch {
                    if (!agentBusy) return@launch
                    voice.stop()
                    _state.update { if (it.phase == Phase.SPEAKING) it.copy(phase = Phase.THINKING) else it }
                }
            }

            override fun onToolUse(toolName: String) {
                updateStreamingLine { it.copy(tool = toolName) }
            }

            override fun onSentence(sentence: String) {
                spoke(sentence)
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
                updateBarge()
                duckMusic(phase == Phase.LISTENING || phase == Phase.CONFIRMING)
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
        scope.launch { voice.speaking.collect { updateBarge() } }
        voice.refreshCatalog()
        // Carica in anticipo il modello di ascolto e libera lo spazio del vecchio modello Vosk (non più usato).
        asrModel.warmUp()
        scope.launch(Dispatchers.IO) { java.io.File(app.filesDir, "vosk-model-it").deleteRecursively() }
    }

    private var listenFocus: android.media.AudioFocusRequest? = null

    /** Mentre Memy ti ascolta, la musica delle altre app si abbassa: così ti capisce meglio. */
    private fun duckMusic(on: Boolean) {
        val audio = app.getSystemService(android.media.AudioManager::class.java) ?: return
        if (on == (listenFocus != null)) return
        if (on) {
            listenFocus = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build()
                ).build().also { runCatching { audio.requestAudioFocus(it) } }
        } else {
            listenFocus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
            listenFocus = null
        }
    }

    /** Avvia o ferma l'ascolto "di interruzione" in base a cosa sta facendo Memy. */
    private fun updateBarge() {
        val phase = _state.value.phase
        val whileSpeaking = voice.speaking.value && (phase == Phase.CONFIRMING || (phase == Phase.SPEAKING && !agentBusy))
        val whileWorking = agentBusy && phase != Phase.CONFIRMING && phase != Phase.LISTENING
        val wanted = settings.state.value.bargeIn && activeStt == null && workStt == null &&
            (whileSpeaking || whileWorking) && canListen()
        if (wanted == bargeRunning) return
        bargeRunning = wanted
        if (!wanted) {
            barge.stop()
            return
        }
        barge.start { capture ->
            scope.launch {
                bargeRunning = false
                val p = _state.value.phase
                val busy = activeStt != null || workStt != null || p == Phase.LISTENING
                when {
                    busy -> capture.release()
                    voice.speaking.value -> listenAside(capture, speaking = true)
                    agentBusy && p != Phase.CONFIRMING -> listenAside(capture, speaking = false)
                    else -> capture.release()
                }
            }
        }
    }

    /**
     * Hai iniziato a parlare mentre Memy lavorava o parlava. Lei NON si interrompe subito: abbassa la voce,
     * ascolta tutta la frase e solo se ha capito parole vere decide cosa fare. Un rumore, un colpo di tosse o
     * la sua stessa voce non la fermano più.
     * - Mentre lavora: sospende il passo successivo; "ferma" la ferma, una nuova indicazione la sostituisce.
     * - Mentre parla: si zittisce e risponde a ciò che hai detto.
     * - Mentre chiede conferma: quello che hai detto vale come risposta.
     */
    private fun listenAside(capture: BargeInDetector.Capture, speaking: Boolean) {
        val engine = newDictationEngine() ?: run {
            capture.release()
            return
        }
        workStt = engine
        if (!speaking) userSpeaking.value = true
        if (speaking) voice.duck(true)
        fun done() {
            if (workStt === engine) workStt = null
            engine.release()
            userSpeaking.value = false
            voice.duck(false)
            _state.update { it.copy(partial = "") }
        }
        engine.start(object : SpeechToText.Listener {
            override fun onPartial(text: String) { _state.update { it.copy(partial = text) } }
            override fun onFinal(text: String) {
                if (workStt !== engine) return
                done()
                val command = WakeWord.strip(text)
                val echo = Echo.isEcho(text, lastSpoken)
                if (echo) bargeOutcome(real = false)
                // Mentre lavora: solo "ferma" o frasi rivolte a lei ("Memy, …"); una conversazione nella stanza non conta.
                val addressed = command.length < text.trim().length
                val valid = when {
                    isStopWord(command) -> true
                    confirmation != null -> parseYesNo(command) != null
                    !speaking -> addressed && command.isNotBlank()
                    else -> SpokenWords.isMeaningful(command)
                }
                if (echo || !valid) {
                    // Niente di concreto: Memy continua come se nulla fosse.
                    afterIgnoredSpeech()
                    return
                }
                bargeOutcome(real = true)
                when {
                    isStopWord(command) -> stopAll()
                    confirmation != null -> {
                        overHeard = command
                        voice.stop()
                    }
                    else -> submit(command, interrupting = agentBusy)
                }
            }
            override fun onError(message: String) {
                if (workStt !== engine) return
                done()
                afterIgnoredSpeech()
            }
        }, capture.record, capture.samples)
    }

    /** Dopo una frase scartata: si riprende ad ascoltare le interruzioni, o la conversazione se Memy ha finito. */
    private fun afterIgnoredSpeech() {
        val idle = _state.value.phase == Phase.IDLE && !agentBusy && confirmation == null && !voice.speaking.value
        if (idle && settings.state.value.continuousConversation && canListen()) startListening() else updateBarge()
    }

    private fun stopWorkListening() {
        workStt?.release()
        workStt = null
        userSpeaking.value = false
        voice.duck(false)
    }

    /** Dopo un'interruzione: hai parlato davvero, o il microfono ha sentito la voce di Memy? */
    private fun bargeOutcome(real: Boolean) {
        if (real) {
            falseBarges = 0
            return
        }
        if (++falseBarges >= 3 && settings.state.value.bargeIn) {
            falseBarges = 0
            settings.setBargeIn(false)
            notice("Ho disattivato \"Interrompila parlando\": su questo telefono il microfono sente la mia voce. Puoi riattivarla in Impostazioni → Conversazione e memoria (funziona bene con le cuffie).")
        }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Sceglie il motore di ascolto in base alle impostazioni e a cosa è disponibile. */
    fun resolveStt(): SpeechToText? = when (settings.state.value.sttEngine) {
        SttEngine.AUTO -> listOf(whisper, parakeet, googleDevice, anySystem).firstOrNull { it.isReady() }
        SttEngine.WHISPER -> whisper.takeIf { it.isReady() }
        SttEngine.PARAKEET -> parakeet.takeIf { it.isReady() }
        SttEngine.GOOGLE_DEVICE -> googleDevice.takeIf { it.isReady() }
    }

    /** Esempio di stile per Whisper, con il nome dell'utente e i nomi che Memy sente spesso. */
    private fun whisperPrompt(): String {
        val name = settings.state.value.userName
        return (if (name.isNotBlank()) "Ciao Memy, sono $name. " else "") + GroqWhisper.DEFAULT_PROMPT
    }

    /**
     * Memy può usare il microfono: con l'app aperta sempre; fuori dall'app solo durante una conversazione,
     * grazie al servizio in primo piano avviato quando l'app era visibile.
     */
    private fun canListen() =
        hasMicPermission() && (inForeground || MemySessionService.running || MemyWakeService.running)

    /** Avvia la "sessione" che tiene vivo il microfono quando Memy lavora in un'altra app. */
    private fun ensureSession() {
        sessionEndJob?.cancel()
        // Con "Ehi Memy" attiva il microfono in background è già garantito dal suo servizio.
        if (!MemySessionService.running && !MemyWakeService.running && inForeground) {
            MemySessionService.start(app, sessionText(_state.value.phase))
        }
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
                job?.cancel(); stopWorkListening(); voice.stop(); agentBusy = false; finishStreamingLine()
                _state.update { it.copy(phase = Phase.IDLE) }
                startListening()
            }
            Phase.CONFIRMING -> Unit
            else -> stopAll()
        }
    }

    /**
     * Qualcuno ha detto "Ehi Memy" (vedi [MemyWakeService]). [recorder] è il registratore ancora attivo:
     * Memy continua ad ascoltare da lì, così capisce anche "Ehi Memy, che ore sono?" detto tutto d'un fiato.
     * @return false se non può ascoltare adesso
     */
    fun onWakeWord(recorder: android.media.AudioRecord): Boolean {
        if (_state.value.phase != Phase.IDLE || dictating.value) {
            recorder.release()
            return false
        }
        chime()
        return startListening(recorder)
    }

    /** Breve segnale: Memy ti ha sentito e sta ascoltando. */
    private fun chime() {
        scope.launch(Dispatchers.Default) {
            runCatching {
                val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 55)
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                delay(300)
                tone.release()
            }
        }
        runCatching {
            app.getSystemService(android.os.Vibrator::class.java)
                ?.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun startListening(handoff: android.media.AudioRecord? = null): Boolean {
        val stt = readyOrExplain() ?: run {
            handoff?.release()
            return false
        }
        // Mentre parli si preparano già le connessioni con Groq e Claude: la risposta arriva prima.
        settings.groqKey()?.takeIf { it.isNotBlank() && stt === whisper }?.let { key ->
            scope.launch(Dispatchers.IO) { groq.prewarm(key) }
        }
        settings.apiKey()?.let { agent.prewarm(it) }
        ensureSession()
        voice.stop()
        voice.stopPreview()
        stopStt()
        activeStt = stt
        _state.update { it.copy(phase = Phase.LISTENING, partial = "", listenerName = stt.name) }
        val listener = object : SpeechToText.Listener {
            override fun onLevel(level: Float) {
                _level.value = _level.value * 0.4f + level * 0.6f
            }

            override fun onPartial(text: String) {
                _state.update { it.copy(partial = text) }
            }

            override fun onProcessing() {
                // Mentre si trascrive, si prepara già la connessione per la voce della risposta.
                voice.prewarm()
                _state.update { if (it.phase == Phase.LISTENING) it.copy(phase = Phase.PROCESSING) else it }
            }

            override fun onFinal(text: String) {
                if (activeStt !== stt) return
                activeStt = null
                _state.update { it.copy(partial = "") }
                val command = WakeWord.strip(text)
                when {
                    text.isBlank() -> _state.update { it.copy(phase = Phase.IDLE) }
                    // Hai detto solo "Ehi Memy": resta in ascolto del comando.
                    command.isBlank() -> if (!startListening()) _state.update { it.copy(phase = Phase.IDLE) }
                    else -> submit(command)
                }
            }

            override fun onError(message: String) {
                if (activeStt !== stt) return
                activeStt = null
                notice(message)
                _state.update { it.copy(phase = Phase.IDLE, partial = "") }
            }
        }
        if (stt is CaptureSpeechToText) {
            stt.start(listener, handoff)
        } else {
            handoff?.release()
            stt.start(listener)
        }
        return true
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
                    "Per ascoltarti aggiungi la chiave gratuita di Groq oppure scarica Parakeet, in Impostazioni → Ascolto."
                }
            )
            null
        }
    }

    // ------------------------------------------------------------------ elaborazione

    /** Invia un comando (detto a voce o scritto). */
    fun submit(text: String, interrupting: Boolean = false) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val key = settings.apiKey()
        if (key.isNullOrBlank()) {
            notice("Inserisci prima la tua chiave API di Claude nelle impostazioni."); return
        }
        ensureSession()
        voice.prewarm()
        job?.cancel()
        confirmation?.complete(false)
        stopStt()
        stopWorkListening()
        voice.stop()
        finishStreamingLine()
        addLine(ChatLine(ids.incrementAndGet(), fromUser = true, text = clean))
        addLine(ChatLine(ids.incrementAndGet(), fromUser = false, text = "", streaming = true))
        // L'allegato vale solo per la risposta alla domanda «cosa ne faccio?», non per una richiesta di ore dopo.
        val attachment = pendingAttachment?.takeIf { System.currentTimeMillis() - pendingAttachmentAt < 5 * 60_000L }
        pendingAttachment = null
        agentBusy = true
        updateBarge()
        val runId = ++runSeq
        job = scope.launch {
            _state.update { it.copy(phase = Phase.THINKING, partial = "") }
            val s = settings.state.value
            val reply = try {
                // Contesto automatico: l'app che l'utente ha davanti (se Memy non è in primo piano) e la città.
                val screen = if (inForeground) null else MemyAccessibilityService.instance?.screenSummary()
                val options = MemyAgent.Options(key, s.model, s.userName, s.webSearch, app.memory.items.value, screen, city())
                agent.run(clean, options, interrupting, attachment)
            } catch (e: MemyAgent.AgentException) {
                (e.message ?: "Si è verificato un errore.").also { voice.enqueue(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (runSeq == runId) {
                    agentBusy = false; finishStreamingLine(); updateOverlay()
                }
                throw e
            } catch (e: Exception) {
                "Si è verificato un errore imprevisto: ${e.message}".also { voice.enqueue(it) }
            }
            replaceStreamingLine(reply)
            agentBusy = false
            _state.update { it.copy(phase = if (voice.speaking.value) Phase.SPEAKING else it.phase) }
            updateOverlay()
            updateBarge()
            voice.awaitIdle()
            _state.update { it.copy(phase = Phase.IDLE) }
            when {
                // Stai già parlando (hai iniziato mentre finiva di lavorare): quella frase arriva da sola.
                workStt != null -> Unit
                // Conversazione continua: Memy torna ad ascoltarti, anche se sei in un'altra app.
                settings.state.value.continuousConversation && canListen() -> startListening()
            }
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

        spoke(prompt)
        updateBarge()
        voice.speakNow(prompt)

        // Poi Memy ascolta la risposta a voce, dentro o fuori dall'app. Se la risposta non è chiara, riprova.
        var attempts = 0
        val answer = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) {
            while (!deferred.isCompleted && attempts < 3 && canListen()) {
                // Se stai già rispondendo mentre Memy finisce la domanda, si aspetta la tua frase invece di buttarla.
                while (workStt != null && overHeard == null && !deferred.isCompleted) delay(100)
                val early = overHeard.also { overHeard = null }
                val text = if (early != null) early else {
                    val stt = resolveStt() ?: break
                    attempts++
                    val heard = CompletableDeferred<String>()
                    activeStt = stt
                    stt.start(object : SpeechToText.Listener {
                        override fun onLevel(level: Float) { _level.value = level }
                        override fun onPartial(text: String) { _state.update { it.copy(partial = text) } }
                        override fun onFinal(text: String) { heard.complete(if (Echo.isEcho(text, lastSpoken)) "" else text) }
                        override fun onError(message: String) { heard.complete("") }
                    })
                    // Il primo che arriva vince: la voce oppure un tocco sui pulsanti.
                    kotlinx.coroutines.selects.select {
                        heard.onAwait { it }
                        deferred.onAwait { "" }
                    }
                }
                stopStt()
                _state.update { it.copy(partial = "") }
                if (deferred.isCompleted) break
                val parsed = parseYesNo(text)
                if (parsed != null) deferred.complete(parsed)
                else if (text.isNotBlank()) {
                    val again = "Scusa, non ho capito. Rispondi sì oppure no."
                    spoke(again)
                    voice.speakNow(again)
                }
            }
            if (!deferred.isCompleted && attempts >= 3) {
                val bye = "Va bene, lascio stare."
                spoke(bye)
                voice.speakNow(bye)
                deferred.complete(false)
            }
            deferred.await()
        } ?: false
        deferred.complete(answer)
        overlay?.cancel()
        stopStt()
        stopWorkListening()
        overHeard = null
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
        stopWorkListening()
        voice.stop()
        finishStreamingLine()
        _state.update { it.copy(phase = Phase.IDLE, partial = "", pendingConfirmation = null) }
        updateOverlay()
    }

    @Volatile private var cachedCity: String? = null
    private var cityCheckedAt = 0L

    /** Città approssimativa per la ricerca web, dall'ultima posizione nota (al massimo ogni 30 minuti, senza GPS). */
    private fun city(): String? {
        val now = System.currentTimeMillis()
        if (now - cityCheckedAt > 30 * 60_000L) {
            cityCheckedAt = now
            scope.launch(Dispatchers.IO) {
                cachedCity = runCatching {
                    if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return@runCatching null
                    val lm = app.getSystemService(android.location.LocationManager::class.java)
                    val loc = lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
                        ?: return@runCatching null
                    @Suppress("DEPRECATION")
                    android.location.Geocoder(app, java.util.Locale.ITALIAN).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.locality
                }.getOrNull() ?: cachedCity
            }
        }
        return cachedCity
    }

    /** Foto, PDF o link condiviso da un'altra app, in attesa della richiesta su cosa farne. */
    private var pendingAttachment: MemyAgent.Attachment? = null
    private var pendingAttachmentAt = 0L

    /**
     * «Condividi con Memy»: tiene da parte ciò che è arrivato e chiede a voce cosa farne.
     * @param what come chiamarlo a voce, es. "questo PDF"
     */
    fun onShared(attachment: MemyAgent.Attachment?, what: String, name: String) {
        if (attachment == null) {
            notice(name.replaceFirstChar { it.uppercase() } + ".")
            return
        }
        stopAll()
        pendingAttachment = attachment
        pendingAttachmentAt = System.currentTimeMillis()
        addLine(ChatLine(ids.incrementAndGet(), fromUser = true, text = "📎 $name"))
        val question = "Cosa vuoi che faccia con $what?"
        addLine(ChatLine(ids.incrementAndGet(), fromUser = false, text = question))
        scope.launch {
            spoke(question)
            voice.speakNow(question)
            if (_state.value.phase == Phase.IDLE) startListening()
        }
    }

    fun newConversation() {
        pendingAttachment = null
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
                asrModel.warmUp()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                notice("Download del modello di ascolto non riuscito: ${e.message}. Controlla la connessione e riprova.")
            }
            _state.update { it.copy(modelDownloadProgress = null, asrModelInstalled = asrModel.isInstalled()) }
        }
    }

    fun deleteAsrModel() {
        // Fuori dal thread principale: se Parakeet sta trascrivendo, l'eliminazione aspetta senza bloccare lo schermo.
        scope.launch(Dispatchers.IO) {
            asrModel.delete()
            _state.update { it.copy(asrModelInstalled = false) }
        }
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

        /** "Ferma", "basta", "lascia stare": l'utente vuole solo che Memy smetta. */
        internal fun isStopWord(text: String): Boolean {
            val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .replace(Regex("[^a-z ]"), " ").trim().replace(Regex("\\s+"), " ")
            return t in setOf(
                "ferma", "fermati", "ferma tutto", "stop", "basta", "basta cosi", "annulla", "lascia stare", "lascia perdere",
                "aspetta", "smetti", "smettila", "no ferma", "no fermati", "alt", "zitta", "stai zitta", "silenzio",
                "grazie basta", "basta grazie", "ok basta",
            )
        }

        /** Capisce un "sì" o un "no" detto a voce; null se la risposta non è chiara. */
        internal fun parseYesNo(text: String): Boolean? {
            val t = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            val words = t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.toSet()
            val no = setOf("no", "non", "annulla", "stop", "aspetta", "fermo", "ferma", "lascia", "niente", "negativo", "nope")
            val yes = setOf(
                "si", "certo", "conferma", "confermo", "ok", "okay", "vai", "procedi", "esatto", "invia", "chiama",
                "sicuro", "giusto", "perfetto", "fallo", "certamente", "assolutamente", "yes",
            )
            return when {
                // «Sì, non c'è problema»: c'è un sì e un "non" che non è un rifiuto. Meglio chiedere di nuovo che annullare.
                words.firstOrNull() == "si" && "non" in words && words.none { it in no - "non" } -> null
                words.any { it in no } -> false
                words.any { it in yes } -> true
                else -> null
            }
        }
    }
}
