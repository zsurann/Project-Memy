package com.nexa.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.nexa.assistant.data.MemySettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/** Elenco delle voci mostrato nelle impostazioni. */
data class VoiceCatalog(
    val loading: Boolean = false,
    val voices: List<EdgeVoice> = VoicePlayer.ITALIAN_VOICES,
    val error: String? = null,
)

/**
 * La voce di Memy: voci neurali di Microsoft Edge, gratuite.
 *
 * Le frasi arrivano una alla volta (mentre Claude scrive) e vengono lette in ordine;
 * la frase successiva viene preparata mentre si legge quella attuale, così non ci sono pause.
 * Solo se il servizio Edge non risponde (es. niente internet) si usa la voce del telefono, per non restare muta.
 */
class VoicePlayer(
    private val context: Context,
    private val settings: MemySettings,
    private val edge: EdgeTts = EdgeTts(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _catalog = MutableStateFlow(VoiceCatalog())
    val catalog: StateFlow<VoiceCatalog> = _catalog.asStateFlow()

    /** Voce di cui si sta ascoltando l'anteprima, e se l'audio è ancora in preparazione. */
    data class Preview(val voiceId: String, val loading: Boolean)

    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    /** Avviso (al massimo uno al minuto) quando il servizio vocale non risponde. */
    var onFallback: ((String) -> Unit)? = null

    private data class Item(val gen: Int, val text: String, val audio: Deferred<Result<File>>)

    private val incoming = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private val prepared = Channel<Item>(capacity = 2)
    private val pending = MutableStateFlow(0)
    private val counter = AtomicInteger()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    private var interruptPlayback: (() -> Unit)? = null
    private var previewPlayer: MediaPlayer? = null
    private var previewJob: Job? = null
    private val previewCache = mutableMapOf<String, File>()
    private var focus: AudioFocusRequest? = null
    /** Volume della voce: si abbassa mentre Memy ascolta se stai parlando sopra di lei. */
    @Volatile private var volume = 1f

    /** Abbassa (o riporta al normale) la voce mentre Memy ascolta cosa le stai dicendo. */
    fun duck(on: Boolean) {
        volume = if (on) 0.22f else 1f
        scope.launch { runCatching { player?.setVolume(volume, volume) } }
    }
    private var lastFallbackNotice = 0L
    @Volatile private var edgeDownUntil = 0L

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()

    init {
        // Voce di riserva del telefono: si prepara subito, ma si usa solo se Edge non risponde.
        tts = TextToSpeech(context.applicationContext) { code ->
            val engine = tts
            val ok = code == TextToSpeech.SUCCESS && engine != null && run {
                val res = engine.setLanguage(Locale.ITALIAN)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) false
                else {
                    bestItalianVoice(engine)?.let { engine.voice = it }
                    true
                }
            }
            ttsReady.complete(ok)
        }
        scope.launch { prepareLoop() }
        scope.launch { playLoop() }
        cleanOldFiles()
    }

    private fun bestItalianVoice(engine: TextToSpeech): Voice? = runCatching {
        engine.voices.orEmpty()
            .filter { it.locale.language == "it" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .maxWithOrNull(compareBy<Voice> { it.quality }.thenBy { if (it.locale.country == "IT") 1 else 0 })
    }.getOrNull()

    /** File audio rimasti da una chiusura improvvisa dell'app. */
    private fun cleanOldFiles() {
        context.cacheDir.listFiles { f -> f.name.startsWith("voce-") && f.name.endsWith(".mp3") }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ elenco voci e anteprime

    /** Scarica l'elenco delle voci: tutte le italiane più le "multilingue", che parlano anche italiano. */
    fun refreshCatalog() {
        if (_catalog.value.loading) return
        _catalog.update { it.copy(loading = true, error = null) }
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { edge.voices() } }
            _catalog.value = result.fold(
                onSuccess = { all ->
                    val italian = all.filter { it.isItalian }.sortedWith(compareBy({ it.gender }, { it.name }))
                    val multi = all.filter { it.isMultilingual && !it.isItalian }.sortedWith(compareBy({ it.gender }, { it.name }))
                    VoiceCatalog(voices = (italian + multi).ifEmpty { ITALIAN_VOICES })
                },
                onFailure = { e -> VoiceCatalog(error = "Non riesco a scaricare l'elenco completo: ${e.message}") },
            )
        }
    }

    /** Ascolta (o ferma) un'anteprima in italiano della voce: è gratuita. */
    fun togglePreview(voice: EdgeVoice) {
        val current = _preview.value
        stopPreview()
        if (current?.voiceId == voice.id) return
        stop()
        _preview.value = Preview(voice.id, loading = true)
        previewJob = scope.launch {
            val s = settings.state.value
            val file = previewCache[previewKey(voice.id, s.voiceRate, s.voicePitch)]?.takeIf { it.exists() }
                ?: withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = edge.synthesize(previewText(voice), voice.id, s.voiceRate, s.voicePitch)
                        File(context.cacheDir, "anteprima-${voice.id}-${s.voiceRate}-${s.voicePitch}.mp3").apply { writeBytes(bytes) }
                    }.getOrNull()
                }?.also { previewCache[previewKey(voice.id, s.voiceRate, s.voicePitch)] = it }
            if (file == null) {
                _preview.value = null
                onFallback?.invoke("Anteprima non disponibile: controlla la connessione a internet.")
                return@launch
            }
            if (_preview.value?.voiceId != voice.id) return@launch
            val mp = MediaPlayer()
            previewPlayer = mp
            _preview.value = Preview(voice.id, loading = false)
            runCatching {
                mp.setAudioAttributes(attributes)
                mp.setDataSource(file.absolutePath)
                mp.setOnPreparedListener { if (previewPlayer === mp) it.start() }
                mp.setOnCompletionListener { if (previewPlayer === mp) stopPreview() }
                mp.setOnErrorListener { _, _, _ -> if (previewPlayer === mp) stopPreview(); true }
                mp.prepareAsync()
            }.onFailure { stopPreview() }
        }
    }

    private fun previewKey(id: String, rate: Int, pitch: Int) = "$id|$rate|$pitch"

    private fun previewText(v: EdgeVoice): String {
        val name = settings.state.value.userName
        return "Ciao${if (name.isNotBlank()) " $name" else ""}, sono Memy e questa è la voce di ${v.name}. " +
            "Posso leggerti le notifiche, impostare promemoria e usare le app al posto tuo."
    }

    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewPlayer?.runCatching { release() }
        previewPlayer = null
        _preview.value = null
    }

    // ------------------------------------------------------------------ parlare

    /** Apre in anticipo la connessione al servizio vocale: la prima frase della risposta partirà prima. */
    fun prewarm() {
        scope.launch(Dispatchers.IO) { runCatching { edge.prewarm() } }
    }

    /** Mette in coda una frase. Si può chiamare da qualsiasi thread: il conteggio avviene sul thread principale, come [stop]. */
    fun enqueue(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        scope.launch {
            if (_preview.value != null) stopPreview()
            pending.update { it + 1 }
            incoming.trySend(generation to clean)
        }
    }

    /** Aspetta che tutte le frasi in coda siano state lette. */
    suspend fun awaitIdle() {
        pending.first { it <= 0 }
    }

    suspend fun speakNow(text: String) {
        enqueue(text)
        awaitIdle()
    }

    fun stop() {
        generation++
        pending.value = 0
        interruptPlayback?.invoke()
        interruptPlayback = null
        player?.runCatching { stop() }
        tts?.stop()
        setSpeaking(false)
    }

    private suspend fun prepareLoop() {
        for ((gen, text) in incoming) {
            if (gen != generation) continue
            val s = settings.state.value
            val job = scope.async(Dispatchers.IO) {
                runCatching {
                    // Servizio appena fallito: per un minuto si usa subito la voce del telefono, senza lunghe attese.
                    if (System.currentTimeMillis() < edgeDownUntil) throw EdgeTtsException("servizio vocale non raggiungibile")
                    val bytes = try {
                        edge.synthesize(text, s.voiceId, s.voiceRate, s.voicePitch)
                    } catch (e: EdgeTtsException) {
                        // Un secondo tentativo risolve quasi sempre gli intoppi di rete momentanei.
                        edge.synthesize(text, s.voiceId, s.voiceRate, s.voicePitch)
                    }
                    File(context.cacheDir, "voce-${counter.incrementAndGet()}.mp3").apply { writeBytes(bytes) }
                }
            }
            prepared.send(Item(gen, text, job))
        }
    }

    private suspend fun playLoop() {
        for (item in prepared) {
            try {
                if (item.gen != generation) {
                    scope.launch { item.audio.await().getOrNull()?.delete() }
                    continue
                }
                setSpeaking(true)
                val result = item.audio.await()
                if (item.gen != generation) {
                    result.getOrNull()?.delete()
                    continue
                }
                result.fold(
                    onSuccess = { file -> playFile(file); file.delete() },
                    onFailure = { e ->
                        val now = System.currentTimeMillis()
                        edgeDownUntil = now + 60_000
                        if (now - lastFallbackNotice > 60_000) {
                            lastFallbackNotice = now
                            onFallback?.invoke("Voce non disponibile (${e.message}). Uso per ora la voce del telefono.")
                        }
                        playSystem(item.text)
                    },
                )
            } finally {
                if (item.gen == generation) {
                    pending.update { (it - 1).coerceAtLeast(0) }
                    if (pending.value == 0) setSpeaking(false)
                }
            }
        }
    }

    private suspend fun playFile(file: File) {
        val mp = MediaPlayer()
        player = mp
        try {
            suspendCancellableCoroutine { cont ->
                mp.setAudioAttributes(attributes)
                mp.setDataSource(file.absolutePath)
                mp.setVolume(volume, volume)
                mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                mp.setOnPreparedListener { it.start() }
                cont.invokeOnCancellation { runCatching { mp.stop() } }
                interruptPlayback = { if (cont.isActive) cont.resume(Unit) }
                mp.prepareAsync()
            }
        } catch (e: java.io.IOException) {
            // file audio non leggibile: si passa alla frase successiva
        } finally {
            interruptPlayback = null
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    private suspend fun playSystem(text: String) {
        if (!ttsReady.await()) return
        val engine = tts ?: return
        engine.setSpeechRate(1f + settings.state.value.voiceRate / 100f)
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
            })
            cont.invokeOnCancellation { engine.stop() }
            interruptPlayback = { if (cont.isActive) cont.resume(Unit) }
            if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
        }
        interruptPlayback = null
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun setSpeaking(value: Boolean) {
        if (_speaking.value == value) return
        _speaking.value = value
        // Abbassa la musica mentre Memy parla.
        if (value) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).build().also { audio.requestAudioFocus(it) }
        } else {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    /** Solo per gli screenshot di prova. */
    @androidx.annotation.VisibleForTesting
    internal fun setPreviewCatalog(catalog: VoiceCatalog, previewingId: String?) {
        _catalog.value = catalog
        _preview.value = previewingId?.let { Preview(it, loading = false) }
    }

    companion object {
        /** Le voci italiane di Edge: usate finché non arriva l'elenco completo dal servizio. */
        val ITALIAN_VOICES = listOf(
            EdgeVoice("it-IT-IsabellaNeural", "Isabella", "it-IT", "Female", listOf("Friendly", "Positive")),
            EdgeVoice("it-IT-ElsaNeural", "Elsa", "it-IT", "Female", listOf("Friendly", "Positive")),
            EdgeVoice("it-IT-DiegoNeural", "Diego", "it-IT", "Male", listOf("Friendly", "Positive")),
            EdgeVoice("it-IT-GiuseppeMultilingualNeural", "Giuseppe", "it-IT", "Male", listOf("Friendly", "Positive")),
        )

        /** Toglie simboli che suonano male letti ad alta voce. */
        fun cleanForSpeech(text: String): String = text
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`>|~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
