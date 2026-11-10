package com.nexa.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.nexa.assistant.data.NexaSettings
import com.nexa.assistant.data.VoiceEngine
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

/**
 * La voce di Nexa. Le frasi arrivano una alla volta (mentre Claude scrive) e vengono lette in ordine.
 * Con le voci cloud la frase successiva viene preparata mentre si sta leggendo quella attuale.
 */
class VoicePlayer(private val context: Context, private val settings: NexaSettings) {

    enum class SystemStatus { LOADING, READY, NO_ITALIAN, UNAVAILABLE }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    private val _systemStatus = MutableStateFlow(SystemStatus.LOADING)
    val systemStatus: StateFlow<SystemStatus> = _systemStatus.asStateFlow()

    /** Notifica (una volta) quando la voce cloud fallisce e si passa a quella del telefono. */
    var onFallback: ((String) -> Unit)? = null

    private sealed interface Item {
        val gen: Int
        data class Cloud(override val gen: Int, val text: String, val audio: Deferred<Result<File>>) : Item
        data class System(override val gen: Int, val text: String) : Item
    }

    private val incoming = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private val prepared = Channel<Item>(capacity = 2)
    private val pending = MutableStateFlow(0)
    private val counter = AtomicInteger()
    @Volatile private var generation = 0
    private var player: MediaPlayer? = null
    private var interruptPlayback: (() -> Unit)? = null
    private var focus: AudioFocusRequest? = null
    private var lastFallbackNotice = 0L

    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Unit>()

    init {
        tts = TextToSpeech(context.applicationContext) { code ->
            val engine = tts
            if (code != TextToSpeech.SUCCESS || engine == null) {
                _systemStatus.value = SystemStatus.UNAVAILABLE
            } else {
                val res = engine.setLanguage(Locale.ITALIAN)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    _systemStatus.value = SystemStatus.NO_ITALIAN
                } else {
                    bestItalianVoice(engine)?.let { engine.voice = it }
                    _systemStatus.value = SystemStatus.READY
                }
            }
            ttsReady.complete(Unit)
        }
        scope.launch { prepareLoop() }
        scope.launch { playLoop() }
    }

    /** Sceglie la voce italiana di qualità più alta installata (le voci "rete" di Google sono neurali). */
    private fun bestItalianVoice(engine: TextToSpeech): Voice? = runCatching {
        engine.voices.orEmpty()
            .filter { it.locale.language == "it" && !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .maxWithOrNull(compareBy<Voice> { it.quality }.thenBy { if (it.locale.country == "IT") 1 else 0 })
    }.getOrNull()

    fun enqueue(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
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
            val synth: ((String) -> ByteArray)? = when (s.voiceEngine) {
                VoiceEngine.GOOGLE_CLOUD -> settings.googleKey()?.let { k -> { t -> CloudVoices.google(t, k, s.googleVoice, s.speechRate) } }
                VoiceEngine.OPENAI -> settings.openAiKey()?.let { k -> { t -> CloudVoices.openAi(t, k, s.openAiVoice, s.speechRate) } }
                VoiceEngine.ELEVENLABS -> settings.elevenKey()?.let { k -> { t -> CloudVoices.elevenLabs(t, k, s.elevenVoiceId, s.speechRate) } }
                VoiceEngine.SYSTEM -> null
            }
            val item = if (synth == null) Item.System(gen, text) else {
                val job = scope.async(Dispatchers.IO) {
                    runCatching {
                        val file = File(context.cacheDir, "voce-${counter.incrementAndGet()}.mp3")
                        file.writeBytes(synth(text))
                        file
                    }
                }
                Item.Cloud(gen, text, job)
            }
            prepared.send(item)
        }
    }

    private suspend fun playLoop() {
        for (item in prepared) {
            try {
                if (item.gen != generation) continue
                setSpeaking(true)
                when (item) {
                    is Item.System -> playSystem(item.text)
                    is Item.Cloud -> {
                        val result = item.audio.await()
                        if (item.gen != generation) continue
                        result.fold(
                            onSuccess = { file -> playFile(file); file.delete() },
                            onFailure = { e ->
                                val now = System.currentTimeMillis()
                                if (now - lastFallbackNotice > 60_000) {
                                    lastFallbackNotice = now
                                    onFallback?.invoke("Voce cloud non disponibile (${e.message}). Uso la voce del telefono.")
                                }
                                playSystem(item.text)
                            },
                        )
                    }
                }
            } finally {
                if (item.gen == generation) {
                    pending.value = (pending.value - 1).coerceAtLeast(0)
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
        } finally {
            interruptPlayback = null
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    private suspend fun playSystem(text: String) {
        ttsReady.await()
        val engine = tts ?: return
        if (_systemStatus.value == SystemStatus.UNAVAILABLE) return
        engine.setSpeechRate(settings.state.value.speechRate)
        val id = UUID.randomUUID().toString()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                })
                cont.invokeOnCancellation { engine.stop() }
                if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
            }
        }
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun setSpeaking(value: Boolean) {
        if (_speaking.value == value) return
        _speaking.value = value
        // Abbassa la musica mentre Nexa parla.
        if (value) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).build().also { audio.requestAudioFocus(it) }
        } else {
            focus?.let { audio.abandonAudioFocusRequest(it) }
            focus = null
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
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
