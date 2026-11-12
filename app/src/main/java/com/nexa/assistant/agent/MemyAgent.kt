package com.nexa.assistant.agent

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.OverloadedException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209
import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.tools.MemyTool
import com.nexa.assistant.tools.ToolInput
import com.nexa.assistant.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * L'agente: manda la richiesta a Claude in streaming, esegue gli strumenti che Claude sceglie,
 * rimanda i risultati e ripete finché Claude non ha finito. Il testo arriva frase per frase,
 * così la voce inizia a parlare quasi subito.
 */
class MemyAgent(
    private val tools: List<MemyTool>,
    private val hooks: Hooks,
    /** Solo per i test: indirizzo di un server finto al posto di api.anthropic.com. */
    private val baseUrl: String? = null,
) {
    interface Hooks {
        /** Chiede all'utente conferma per un'azione delicata. */
        suspend fun confirm(prompt: String): Boolean

        /** Memy sta usando uno strumento (o "web_search"). */
        fun onToolUse(toolName: String) {}

        /** Chiamata prima di ogni azione: sospende finché l'utente sta parlando, così può fermare o correggere Memy. */
        suspend fun beforeTool() {}

        /** Claude ha iniziato a usare uno strumento: da qui lavora in silenzio. */
        fun onWorkStarted() {}

        /** Una frase completa, pronta da leggere ad alta voce. */
        fun onSentence(sentence: String) {}

        /** Il testo visibile della risposta finora (per la UI). */
        fun onText(textSoFar: String) {}
    }

    data class Options(
        val apiKey: String,
        val model: ClaudeModel,
        val userName: String,
        val webSearch: Boolean,
        val memories: List<String> = emptyList(),
        /** Cosa c'è sullo schermo adesso (app e schermata), aggiunto alla richiesta. */
        val screenContext: String? = null,
        /** Città approssimativa, per la ricerca web. */
        val city: String? = null,
    )

    class AgentException(message: String) : Exception(message)

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** Cronologia della conversazione. Si aggiunge solo in coda (mai modificata a metà). */
    private val history = mutableListOf<MessageParam>()
    private var lastActivity = 0L
    /** Una richiesta alla volta: una nuova aspetta che quella annullata abbia rimesso in ordine la cronologia. */
    private val turn = Mutex()

    private val toolDefs: List<Tool> by lazy { (tools + EscalateTool).map(::toApiTool) }

    fun resetConversation() {
        history.clear()
        conversationMemories = null
    }

    /**
     * @param interrupting l'utente ha parlato mentre Memy stava ancora lavorando alla richiesta precedente
     */
    /** Qualcosa condiviso da un'altra app, allegato alla richiesta. */
    sealed interface Attachment {
        class Image(val jpeg: ByteArray) : Attachment
        class Pdf(val bytes: ByteArray) : Attachment
        class Text(val name: String, val text: String) : Attachment
    }

    /** Modello usato in questo momento: in automatico parte da Haiku e può salire a Sonnet durante la richiesta. */
    @Volatile private var turnModel = ClaudeModel.HAIKU
    private var conversationMemories: List<String>? = null

    private fun hasBigAttachment(m: MessageParam) =
        m.content().isContentBlockParams() && m.content().asContentBlockParams().any { it.isImage() || it.isDocument() }
    private var autoModel = false
    private var lastWarm = 0L

    /** Apre in anticipo la connessione con Claude (nessun costo in token), così la prima risposta arriva prima. */
    fun prewarm(apiKey: String) {
        val now = System.currentTimeMillis()
        if (now - lastWarm < 60_000) return
        lastWarm = now
        Thread({ runCatching { clientFor(apiKey).models().list() } }, "memy-warm-claude").start()
    }

    private fun escalate() {
        if (autoModel && turnModel == ClaudeModel.HAIKU) turnModel = ClaudeModel.SONNET
    }

    /**
     * @param interrupting l'utente ha parlato mentre Memy stava ancora lavorando alla richiesta precedente
     * @param attachment foto, PDF o testo condiviso da un'altra app
     */
    suspend fun run(
        userText: String, options: Options, interrupting: Boolean = false, attachment: Attachment? = null,
    ): String = withContext(Dispatchers.IO) {
        turn.withLock { runTurn(userText, options, interrupting, attachment) }
    }

    private suspend fun runTurn(userText: String, options: Options, interrupting: Boolean, attachment: Attachment?): String {
        autoModel = options.model == ClaudeModel.AUTO
        // Un solo allegato grande per conversazione: altrimenti la richiesta supera i limiti dell'API.
        if (attachment != null && history.any { !it.content().isString() && it.role() == MessageParam.Role.USER && hasBigAttachment(it) }) history.clear()
        turnModel = if (autoModel) ClaudeModel.HAIKU else options.model
        val now = System.currentTimeMillis()
        if (now - lastActivity > CONVERSATION_TIMEOUT_MS) history.clear() else trimHistory()
        lastActivity = now

        // I ricordi entrano nel prompt solo all'inizio della conversazione: cambiarlo a metà invaliderebbe il ragionamento.
        if (history.isEmpty()) conversationMemories = options.memories
        val api = clientFor(options.apiKey)
        val checkpoint = history.size
        val note = if (interrupting) " [Ti ho interrotta mentre lavoravi: quello che dico ora corregge o sostituisce la richiesta di prima.]" else ""
        val screen = options.screenContext?.let { " [Sullo schermo: $it]" }.orEmpty()
        val text = "${SystemPrompt.timeContext()}$screen$note $userText"
        history += if (attachment == null) MessageParam.ofUser(text)
        else MessageParam.ofUserOfContentBlockParams(listOf(attachmentBlock(attachment), ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())))
        // Se l'utente interrompe, si conserva tutto fino all'ultimo passaggio completato: così Memy sa cosa stava facendo.
        var safePoint = history.size
        val spoken = StringBuilder()
        val job = coroutineContext[Job]

        try {
            repeat(MAX_STEPS) {
                val response = streamOnce(api, buildParams(options), spoken, job)
                history += response.toParam()

                when (response.stopReason().orElse(StopReason.END_TURN)) {
                    StopReason.TOOL_USE -> {
                        val done = ArrayList<ContentBlockParam>()
                        try {
                            runTools(response, done)
                        } finally {
                            // Anche se l'utente interrompe, Claude deve sapere quali azioni sono già state fatte.
                            history += toolResults(response, done, "Annullato dall'utente: non eseguito.")
                            safePoint = history.size
                        }
                    }
                    StopReason.MAX_TOKENS -> {
                        // Risposta troppo lunga, tagliata mentre chiedeva uno strumento: la cronologia deve restare valida.
                        if (response.content().any { it.toolUse().isPresent }) {
                            history += toolResults(response, emptyList(), "Richiesta tagliata perché troppo lunga: rifalla più breve.")
                            safePoint = history.size
                        } else {
                            lastActivity = System.currentTimeMillis()
                            return spoken.toString().trim().ifEmpty { "Ecco fatto." }
                        }
                    }
                    StopReason.PAUSE_TURN -> Unit // ricerca web lunga: il server chiede di proseguire
                    StopReason.REFUSAL -> {
                        history.clear()
                        val msg = "Mi dispiace, su questo non posso aiutarti."
                        hooks.onSentence(msg)
                        return msg
                    }
                    else -> {
                        lastActivity = System.currentTimeMillis()
                        val text = spoken.toString().trim()
                        if (text.isEmpty()) {
                            val msg = "Ecco fatto."
                            hooks.onSentence(msg)
                            return msg
                        }
                        return text
                    }
                }
            }
            return "Ho fatto molti passaggi senza riuscire a finire. Dimmi come preferisci che continui.".also { hooks.onSentence(it) }
        } catch (e: AnthropicServiceException) {
            rollbackAfterError(checkpoint, safePoint)
            throw AgentException(describe(e))
        } catch (e: AnthropicIoException) {
            if (job?.isActive == false) {
                rollback(safePoint)
                throw CancellationException("annullato")
            }
            rollbackAfterError(checkpoint, safePoint)
            throw AgentException("Non riesco a collegarmi a internet. Controlla la connessione.")
        } catch (e: CancellationException) {
            rollback(safePoint)
            throw e
        } catch (e: Exception) {
            // Qualsiasi altro imprevisto: la cronologia torna valida, così la conversazione può continuare.
            if (job?.isActive == false) {
                rollback(safePoint)
                throw CancellationException("annullato")
            }
            rollbackAfterError(checkpoint, safePoint)
            throw AgentException("Si è verificato un errore imprevisto: ${e.message}")
        }
    }

    /** Una chiamata in streaming: consegna le frasi man mano e restituisce il messaggio completo. */
    /**
     * Una chiamata in streaming: restituisce il messaggio completo.
     *
     * Memy lavora in silenzio: il testo viene letto ad alta voce solo se NON accompagna l'uso di uno strumento.
     * Per questo le frasi restano in attesa finché non si capisce che tipo di risposta è: se arriva uno strumento
     * vengono scartate (erano solo "ora apro…"), se la risposta finisce o è ormai chiaramente un discorso vengono lette.
     */
    private fun streamOnce(api: AnthropicClient, params: MessageCreateParams, spoken: StringBuilder, job: Job?): Message {
        val accumulator = MessageAccumulator.create()
        val held = ArrayList<String>()
        var released = false
        var usedClientTool = false
        fun release() {
            released = true
            held.forEach { hooks.onSentence(it) }
            held.clear()
        }
        val splitter = SentenceSplitter { sentence ->
            if (released) hooks.onSentence(sentence) else held += sentence
        }
        fun discardNarration() {
            held.clear(); released = false
            if (spoken.isNotEmpty()) {
                spoken.setLength(0)
                hooks.onText("")
            }
        }
        api.messages().createStreaming(params).use { stream ->
            // Se l'utente interrompe, la connessione si chiude subito invece di aspettare il prossimo dato.
            @OptIn(InternalCoroutinesApi::class)
            val onCancel = job?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { runCatching { stream.close() } }
            try {
                val iterator = stream.stream().iterator()
                while (iterator.hasNext()) {
                    if (job?.isActive == false) throw CancellationException("annullato")
                    val event = iterator.next()
                    accumulator.accumulate(event)
                    event.contentBlockStart().ifPresent { start ->
                        start.contentBlock().serverToolUse().ifPresent {
                            discardNarration()
                            hooks.onToolUse("web_search")
                        }
                        start.contentBlock().toolUse().ifPresent {
                            usedClientTool = true
                            discardNarration()
                            hooks.onWorkStarted()
                        }
                    }
                    event.contentBlockDelta().ifPresent { d ->
                        d.delta().text().ifPresent { t ->
                            if (usedClientTool) return@ifPresent // testo dopo uno strumento: non si legge e non si mostra
                            // Altro testo dopo una frase completa: è una risposta, non un «apro WhatsApp» prima di uno strumento.
                            val afterSentence = held.isNotEmpty()
                            spoken.append(t.text())
                            splitter.feed(t.text())
                            if (afterSentence && !released) release()
                            hooks.onText(spoken.toString().trim())
                        }
                    }
                }
            } finally {
                onCancel?.dispose()
            }
        }
        if (job?.isActive == false) throw CancellationException("annullato")
        if (!usedClientTool) {
            splitter.flush()
            release()
        }
        return accumulator.message()
    }

    private fun attachmentBlock(a: Attachment): ContentBlockParam = when (a) {
        is Attachment.Image -> ContentBlockParam.ofImage(
            ImageBlockParam.builder().source(
                Base64ImageSource.builder().mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                    .data(Base64.getEncoder().encodeToString(a.jpeg)).build()
            ).build()
        )
        is Attachment.Pdf -> ContentBlockParam.ofDocument(
            DocumentBlockParam.builder().source(
                Base64PdfSource.builder().data(Base64.getEncoder().encodeToString(a.bytes)).build()
            ).build()
        )
        is Attachment.Text -> ContentBlockParam.ofText(
            TextBlockParam.builder().text("Contenuto condiviso (${a.name}):\n${a.text.take(40_000)}").build()
        )
    }

    /** In caso di errore si torna allo stato precedente, così la cronologia resta valida. */
    private fun rollback(checkpoint: Int) {
        while (history.size > checkpoint) history.removeAt(history.lastIndex)
    }

    /**
     * Dopo un errore: se erano già state fatte delle azioni, la cronologia le conserva (così un "riprova" non le
     * ripete, per esempio un SMS); altrimenti si torna a prima della richiesta.
     */
    private fun rollbackAfterError(checkpoint: Int, safePoint: Int) {
        rollback(if (safePoint > checkpoint + 1) safePoint else checkpoint)
    }

    /** Toglie i turni più vecchi (mai a metà di un turno) invece di dimenticare tutto di colpo. */
    /**
     * Con il ragionamento di Sonnet i messaggi precedenti non si possono modificare (l'API rifiuterebbe la richiesta):
     * quando la cronologia diventa troppo lunga si riparte con una conversazione nuova. I ricordi restano.
     */
    private fun trimHistory() {
        if (history.size > MAX_HISTORY) history.clear()
    }

    /** I risultati per tutti gli strumenti chiesti: quelli eseguiti e, per gli altri, [missing]. */
    private fun toolResults(response: Message, done: List<ContentBlockParam>, missing: String): MessageParam {
        val doneIds = done.mapNotNull { it.toolResult().orElse(null)?.toolUseId() }.toSet()
        val rest = response.content().mapNotNull { it.toolUse().orElse(null) }.filter { it.id() !in doneIds }.map {
            ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(it.id()).isError(true).content(missing).build())
        }
        // Tutti i risultati vanno in un unico messaggio utente.
        return MessageParam.ofUserOfContentBlockParams(done + rest)
    }

    private suspend fun runTools(response: Message, out: MutableList<ContentBlockParam>) {
        response.content().mapNotNull { it.toolUse().orElse(null) }.forEach { block ->
            val result = executeTool(block)
            val builder = ToolResultBlockParam.builder().toolUseId(block.id()).isError(result.isError)
            val image = result.imageJpeg
            if (image == null) builder.content(result.text)
            else builder.contentOfBlocks(
                listOf(
                    ToolResultBlockParam.Content.Block.ofText(result.text),
                    ToolResultBlockParam.Content.Block.ofImage(
                        Base64ImageSource.builder()
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .data(Base64.getEncoder().encodeToString(image))
                            .build()
                    ),
                )
            )
            out += ContentBlockParam.ofToolResult(builder.build())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeTool(block: ToolUseBlock): ToolResult {
        val tool = (tools + EscalateTool).firstOrNull { it.name == block.name() }
            ?: return ToolResult.error("Strumento sconosciuto: ${block.name()}")
        val input = try {
            ToolInput(block._input().convert(Map::class.java) as? Map<String, Any?> ?: emptyMap())
        } catch (e: Exception) {
            return ToolResult.error("Parametri non validi: ${e.message}")
        }
        if (tool.name == ESCALATE || tool.name in SCREEN_TOOLS) escalate()
        if (tool.name == ESCALATE) {
            return ToolResult.ok(if (autoModel) "Ora sei il modello più capace: continua tu la richiesta." else "Sei già il modello scelto: continua.")
        }
        hooks.onToolUse(tool.name)
        hooks.beforeTool()
        return try {
            val prompt = tool.confirmationPrompt(input)
            if (prompt != null && !hooks.confirm(prompt)) {
                return ToolResult.ok("L'utente NON ha confermato: azione annullata, non eseguita.")
            }
            tool.execute(input)
        } catch (e: IllegalArgumentException) {
            ToolResult.error(e.message ?: "Parametri non validi")
        } catch (e: SecurityException) {
            ToolResult.error("Permesso mancante: ${e.message}. L'utente deve concederlo nelle impostazioni di Memy.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Errore durante l'azione: ${e.message}")
        }
    }

    private fun buildParams(o: Options): MessageCreateParams {
        val model = turnModel
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(16000L)
            .system(SystemPrompt.build(o.userName, o.webSearch, conversationMemories ?: o.memories))
            .messages(history.toList())
            // Effort basso: risposte rapide, adatte alla conversazione a voce.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // Riutilizza la parte fissa (istruzioni + strumenti) tra una richiesta e l'altra: costa meno.
            .cacheControl(CacheControlEphemeral.builder().build())
        // Per la voce conta la rapidità: Sonnet e Haiku rispondono senza la fase di ragionamento iniziale
        // (Opus invece ragiona sempre). Sonnet 5.5 usa "between_tools", Haiku 5.5 "disabled".
        when (model) {
            ClaudeModel.SONNET -> builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "between_tools")))
            ClaudeModel.HAIKU -> builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "disabled")))
            else -> Unit
        }
        // Elenco degli strumenti sempre uguale (anche think_harder): cambiarlo farebbe perdere la cache.
        toolDefs.forEach { builder.addTool(it) }
        if (o.webSearch) {
            val location = UserLocation.builder()
                .country(Locale.getDefault().country.ifBlank { "IT" })
                .timezone(ZoneId.systemDefault().id)
                .apply { o.city?.let { city(it) } }
                .build()
            // Haiku usa la versione base della ricerca web; Opus e Sonnet quella con filtro dinamico dei risultati.
            if (model == ClaudeModel.HAIKU) {
                builder.addTool(WebSearchTool20250305.builder().maxUses(3L).userLocation(location).build())
            } else {
                builder.addTool(WebSearchTool20260209.builder().maxUses(3L).userLocation(location).build())
            }
        }
        if (model != ClaudeModel.HAIKU) {
            // Se Claude rifiuta per un falso allarme di sicurezza, il server riprova con un modello alternativo.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun clientFor(apiKey: String): AnthropicClient {
        if (client == null || clientKey != apiKey) {
            client?.close()
            client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .apply { baseUrl?.let { baseUrl(it) } }
                .maxRetries(1)
                .build()
            clientKey = apiKey
        }
        return client!!
    }

    private fun toApiTool(tool: MemyTool): Tool {
        val props = Tool.InputSchema.Properties.builder()
        tool.properties.forEach { (key, schema) -> props.putAdditionalProperty(key, JsonValue.from(schema)) }
        return Tool.builder()
            .name(tool.name)
            .description(tool.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(props.build())
                    .required(tool.required)
                    .build()
            )
            .build()
    }

    private fun describe(e: AnthropicServiceException): String = when (e) {
        is UnauthorizedException -> "La chiave API non è valida. Controllala nelle impostazioni."
        is PermissionDeniedException -> "Accesso negato da Anthropic. Se sei in un paese non supportato l'API non è disponibile."
        is RateLimitException -> "Troppe richieste in poco tempo. Riprova tra qualche secondo."
        is OverloadedException, is InternalServerException -> "I server di Claude sono sovraccarichi. Riprova tra poco."
        is BadRequestException ->
            if (e.message.orEmpty().contains("credit", ignoreCase = true)) {
                "Il credito API è esaurito. Ricaricalo su console.anthropic.com."
            } else "Richiesta non valida: ${e.message}"
        else -> "Errore dal servizio Claude (${e.statusCode()}): ${e.message}"
    }

    companion object {
        private const val MAX_STEPS = 40
        const val ESCALATE = "think_harder"
        /** Usare le app sullo schermo richiede il modello più capace. */
        val SCREEN_TOOLS = setOf("read_screen", "look_at_screen", "tap_element", "tap_position", "type_text", "scroll")
        const val MAX_HISTORY = 80
        const val CONVERSATION_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

/** Haiku chiede di passare il lavoro a Sonnet (solo in modalità automatica). */
private object EscalateTool : MemyTool {
    override val name = MemyAgent.ESCALATE
    override val description =
        "Passa la richiesta a un modello più capace. Chiamalo SUBITO, senza scrivere nulla, se la richiesta richiede di usare " +
            "app sullo schermo, più di due passaggi, un ragionamento attento, scrivere un documento o un testo lungo."
    override val properties = emptyMap<String, Map<String, Any>>()
    override val required = emptyList<String>()
    override suspend fun execute(input: ToolInput) = ToolResult.ok("ok")
}
