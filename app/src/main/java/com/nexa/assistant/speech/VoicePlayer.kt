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
import com.nexa.assistant.data.VoiceModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
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

/** Stato dell'elenco voci mostrato nelle impostazioni. */
data class VoiceCatalog(
    val loading: Boolean = false,
    val voices: List<ElevenVoice> = emptyList(),
    val usage: ElevenUsage? = null,
    val error: String? = null,
)

/**
 * La voce di Memy, con ElevenLabs. Le frasi arrivano una alla volta (mentre Claude scrive) e vengono
 * lette in ordine; la frase successiva viene preparata mentre si sta leggendo quella attuale.
 *
 * Se manca la chiave o ElevenLabs non risponde, per non restare muta usa la sintesi del telefono.
 */
class VoicePlayer(
    private val context: Context,
    private val settings: MemySettings,
    private val api: ElevenLabs = ElevenLabs(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _catalog = MutableStateFlow(VoiceCatalog())
    val catalog: StateFlow<VoiceCatalog> = _catalog.asStateFlow()

    /** Solo per gli screenshot di prova. */
    @androidx.annotation.VisibleForTesting
    internal fun setPreviewCatalog(catalog: VoiceCatalog, previewingId: String?) {
        _catalog.value = catalog
        _previewing.value = previewingId
    }

    /** ID della voce di cui si sta ascoltando l'anteprima (per animare il pulsante). */
    private val _previewing = MutableStateFlow<String?>(null)
    val previewing: StateFlow<String?> = _previewing.asStateFlow()

    /** Avviso (al massimo uno al minuto) quando ElevenLabs fallisce e si usa la voce del telefono. */
    var onFallback: ((String) -> Unit)? = null

    private sealed interface Item {
        val gen: Int
        val text: String
        data class Cloud(override val gen: Int, override val text: String, val audio: Deferred<Result<File>>) : Item
        data class Local(override val gen: Int, override val text: String) : Item
    }

    private val incoming = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private val prepared = Channel<Item>(capacity = 2)
    private val pending = MutableStateFlow(0)
    private val counter = AtomicInteger()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    private var interruptPlayback: (() -> Unit)? = null
    private var previewPlayer: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var lastFallbackNotice = 0L

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()

    init {
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
    }

    private fun bestItalianVoice(engine: TextToSpeech): Voice? = runCatching {
        engine.voices.orEmpty()
            .filter { it.locale.language == "it" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .maxWithOrNull(compareBy<Voice> { it.quality }.thenBy { if (it.locale.country == "IT") 1 else 0 })
    }.getOrNull()

    // ------------------------------------------------------------------ elenco voci

    /** Scarica l'elenco delle voci e i crediti residui. Se non c'è una voce scelta, seleziona la prima gratuita. */
    fun refreshCatalog() {
        val key = settings.elevenKey()
        if (key.isNullOrBlank()) {
            _catalog.value = VoiceCatalog()
            return
        }
        if (_catalog.value.loading) return
        _catalog.update { it.copy(loading = true, error = null) }
        scope.launch {
            val voices = withContext(Dispatchers.IO) { runCatching { api.voices(key) } }
            val usage = withContext(Dispatchers.IO) { runCatching { api.usage(key) }.getOrNull() }
            voices.fold(
                onSuccess = { list ->
                    val sorted = list.sortedWith(compareBy<ElevenVoice> { !it.isFree }.thenBy { it.name })
                    _catalog.value = VoiceCatalog(voices = sorted, usage = usage)
                    val current = settings.state.value.voiceId
                    if (sorted.isNotEmpty() && sorted.none { it.id == current }) {
                        val first = sorted.first()
                        settings.setVoice(first.id, first.name)
                    }
                },
                onFailure = { e ->
                    _catalog.update { it.copy(loading = false, usage = usage, error = "Non riesco a scaricare le voci: ${e.message}") }
                },
            )
        }
    }

    /** Riproduce (o ferma) l'anteprima gratuita di una voce: non consuma crediti. */
    fun togglePreview(voice: ElevenVoice) {
        val wasPlaying = _previewing.value == voice.id
        stopPreview()
        if (wasPlaying) return
        val url = voice.previewUrl ?: return
        stop()
        val mp = MediaPlayer()
        previewPlayer = mp
        _previewing.value = voice.id
        runCatching {
            mp.setAudioAttributes(attributes)
            mp.setDataSource(url)
            mp.setOnPreparedListener { if (previewPlayer === mp) it.start() }
            mp.setOnCompletionListener { if (previewPlayer === mp) stopPreview() }
            mp.setOnErrorListener { _, _, _ -> if (previewPlayer === mp) stopPreview(); true }
            mp.prepareAsync()
        }.onFailure { stopPreview() }
    }

    fun stopPreview() {
        previewPlayer?.runCatching { release() }
        previewPlayer = null
        _previewing.value = null
    }

    // ------------------------------------------------------------------ parlare

    fun enqueue(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        if (_previewing.value != null) scope.launch { stopPreview() }
        pending.update { it + 1 }
        incoming.trySend(generation to clean)
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
            val key = settings.elevenKey()
            val item = if (key.isNullOrBlank() || s.voiceId.isBlank()) Item.Local(gen, text) else {
                Item.Cloud(gen, text, scope.async(Dispatchers.IO) {
                    runCatching {
                        val bytes = try {
                            api.speak(text, key, s.voiceId, s.voiceModel, s.speechRate)
                        } catch (e: ElevenLabsException) {
                            // Un modello non incluso nel piano: riprova con quello base prima di arrendersi.
                            if (s.voiceModel == VoiceModel.FLASH || e.code == 401 || e.code == 429) throw e
                            api.speak(text, key, s.voiceId, VoiceModel.FLASH, s.speechRate)
                        }
                        File(context.cacheDir, "voce-${counter.incrementAndGet()}.mp3").apply { writeBytes(bytes) }
                    }
                })
            }
            prepared.send(item)
        }
    }

    private suspend fun playLoop() {
        for (item in prepared) {
            try {
                if (item.gen != generation) {
                    (item as? Item.Cloud)?.audio?.let { d -> scope.launch { d.await().getOrNull()?.delete() } }
                    continue
                }
                setSpeaking(true)
                when (item) {
                    is Item.Local -> playSystem(item.text)
                    is Item.Cloud -> {
                        val result = item.audio.await()
                        if (item.gen != generation) {
                            result.getOrNull()?.delete()
                            continue
                        }
                        result.fold(
                            onSuccess = { file -> playFile(file); file.delete() },
                            onFailure = { e ->
                                val now = System.currentTimeMillis()
                                if (now - lastFallbackNotice > 60_000) {
                                    lastFallbackNotice = now
                                    onFallback?.invoke("Voce ElevenLabs non disponibile: ${e.message}. Uso la voce del telefono.")
                                }
                                playSystem(item.text)
                            },
                        )
                    }
                }
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
        engine.setSpeechRate(settings.state.value.speechRate)
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                @Deprecated("Deprecated in Java")
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

    companion object {
        /** Toglie simboli che suonano male letti ad alta voce. */
        fun cleanForSpeech(text: String): String = text
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`>|~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
