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
    )

    class AgentException(message: String) : Exception(message)

    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    /** Cronologia della conversazione. Si aggiunge solo in coda (mai modificata a metà). */
    private val history = mutableListOf<MessageParam>()
    private var lastActivity = 0L
    /** Una richiesta alla volta: una nuova aspetta che quella annullata abbia rimesso in ordine la cronologia. */
    private val turn = Mutex()

    private val toolDefs: List<Tool> by lazy { tools.map(::toApiTool) }

    fun resetConversation() {
        history.clear()
    }

    /**
     * @param interrupting l'utente ha parlato mentre Memy stava ancora lavorando alla richiesta precedente
     */
    suspend fun run(userText: String, options: Options, interrupting: Boolean = false): String = withContext(Dispatchers.IO) {
        turn.withLock { runTurn(userText, options, interrupting) }
    }

    private suspend fun runTurn(userText: String, options: Options, interrupting: Boolean): String {
        val now = System.currentTimeMillis()
        if (now - lastActivity > CONVERSATION_TIMEOUT_MS || history.size > MAX_HISTORY) history.clear()
        lastActivity = now

        val api = clientFor(options.apiKey)
        val checkpoint = history.size
        val note = if (interrupting) " [Ti ho interrotta mentre lavoravi: quello che dico ora corregge o sostituisce la richiesta di prima.]" else ""
        history += MessageParam.ofUser("${SystemPrompt.timeContext()}$note $userText")
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
                        history += runTools(response)
                        safePoint = history.size
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
            rollback(checkpoint)
            throw AgentException(describe(e))
        } catch (e: AnthropicIoException) {
            if (job?.isActive == false) {
                rollback(safePoint)
                throw CancellationException("annullato")
            }
            rollback(checkpoint)
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
            rollback(checkpoint)
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
        var heldChars = 0
        var released = false
        var usedClientTool = false
        fun release() {
            released = true
            held.forEach { hooks.onSentence(it) }
            held.clear()
        }
        val splitter = SentenceSplitter { sentence ->
            if (released) hooks.onSentence(sentence)
            else {
                held += sentence
                heldChars += sentence.length
                if (heldChars >= SPEAK_AFTER_CHARS) release()
            }
        }
        fun discardNarration() {
            held.clear(); heldChars = 0; released = false
            if (spoken.isNotEmpty()) {
                spoken.setLength(0)
                hooks.onText("")
            }
        }
        api.messages().createStreaming(params).use { stream ->
            // Se l'utente interrompe, la connessione si chiude subito invece di aspettare il prossimo dato.
            val onCancel = job?.invokeOnCompletion { runCatching { stream.close() } }
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
                            spoken.append(t.text())
                            splitter.feed(t.text())
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

    /** In caso di errore si torna allo stato precedente, così la cronologia resta valida. */
    private fun rollback(checkpoint: Int) {
        while (history.size > checkpoint) history.removeAt(history.lastIndex)
    }

    private suspend fun runTools(response: Message): MessageParam {
        val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { block ->
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
            ContentBlockParam.ofToolResult(builder.build())
        }
        // Tutti i risultati vanno in un unico messaggio utente.
        return MessageParam.ofUserOfContentBlockParams(results)
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun executeTool(block: ToolUseBlock): ToolResult {
        val tool = tools.firstOrNull { it.name == block.name() }
            ?: return ToolResult.error("Strumento sconosciuto: ${block.name()}")
        val input = try {
            ToolInput(block._input().convert(Map::class.java) as? Map<String, Any?> ?: emptyMap())
        } catch (e: Exception) {
            return ToolResult.error("Parametri non validi: ${e.message}")
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
        val builder = MessageCreateParams.builder()
            .model(o.model.id)
            .maxTokens(16000L)
            .system(SystemPrompt.build(o.userName, o.webSearch, o.memories))
            .messages(history.toList())
            // Effort basso: risposte rapide, adatte alla conversazione a voce.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // Riutilizza la parte fissa (istruzioni + strumenti) tra una richiesta e l'altra: costa meno.
            .cacheControl(CacheControlEphemeral.builder().build())
        // Per la voce conta la rapidità: Sonnet e Haiku rispondono senza la fase di ragionamento iniziale
        // (Opus invece ragiona sempre). Sonnet 5.5 usa "between_tools", Haiku 5.5 "disabled".
        when (o.model) {
            ClaudeModel.SONNET -> builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "between_tools")))
            ClaudeModel.HAIKU -> builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "disabled")))
            ClaudeModel.OPUS -> Unit
        }
        toolDefs.forEach { builder.addTool(it) }
        if (o.webSearch) {
            val location = UserLocation.builder()
                .country(Locale.getDefault().country.ifBlank { "IT" })
                .timezone(ZoneId.systemDefault().id)
                .build()
            // Haiku usa la versione base della ricerca web; Opus e Sonnet quella con filtro dinamico dei risultati.
            if (o.model == ClaudeModel.HAIKU) {
                builder.addTool(WebSearchTool20250305.builder().maxUses(3L).userLocation(location).build())
            } else {
                builder.addTool(WebSearchTool20260209.builder().maxUses(3L).userLocation(location).build())
            }
        }
        if (o.model != ClaudeModel.HAIKU) {
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

    private companion object {
        const val MAX_STEPS = 40
        /** Oltre questa lunghezza il testo è chiaramente una risposta, non una frase di circostanza: si inizia a leggerlo. */
        const val SPEAK_AFTER_CHARS = 220
        const val MAX_HISTORY = 80
        const val CONVERSATION_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
