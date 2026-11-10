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

    private val toolDefs: List<Tool> by lazy { tools.map(::toApiTool) }

    fun resetConversation() {
        history.clear()
    }

    suspend fun run(userText: String, options: Options): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastActivity > CONVERSATION_TIMEOUT_MS || history.size > MAX_HISTORY) history.clear()
        lastActivity = now

        val api = clientFor(options.apiKey)
        val checkpoint = history.size
        history += MessageParam.ofUser("${SystemPrompt.timeContext()} $userText")
        val spoken = StringBuilder()
        val job = coroutineContext[Job]

        try {
            repeat(MAX_STEPS) {
                val response = streamOnce(api, buildParams(options), spoken, job)
                history += response.toParam()

                when (response.stopReason().orElse(StopReason.END_TURN)) {
                    StopReason.TOOL_USE -> history += runTools(response)
                    StopReason.PAUSE_TURN -> Unit // ricerca web lunga: il server chiede di proseguire
                    StopReason.REFUSAL -> {
                        history.clear()
                        val msg = "Mi dispiace, su questo non posso aiutarti."
                        hooks.onSentence(msg)
                        return@withContext msg
                    }
                    else -> {
                        lastActivity = System.currentTimeMillis()
                        val text = spoken.toString().trim()
                        if (text.isEmpty()) {
                            val msg = "Ecco fatto."
                            hooks.onSentence(msg)
                            return@withContext msg
                        }
                        return@withContext text
                    }
                }
            }
            "Ho fatto molti passaggi senza riuscire a finire. Dimmi come preferisci che continui.".also { hooks.onSentence(it) }
        } catch (e: AnthropicServiceException) {
            rollback(checkpoint)
            throw AgentException(describe(e))
        } catch (e: AnthropicIoException) {
            rollback(checkpoint)
            if (job?.isActive == false) throw CancellationException("annullato")
            throw AgentException("Non riesco a collegarmi a internet. Controlla la connessione.")
        } catch (e: CancellationException) {
            rollback(checkpoint)
            throw e
        }
    }

    /** Una chiamata in streaming: consegna le frasi man mano e restituisce il messaggio completo. */
    private fun streamOnce(api: AnthropicClient, params: MessageCreateParams, spoken: StringBuilder, job: Job?): Message {
        val accumulator = MessageAccumulator.create()
        val splitter = SentenceSplitter { hooks.onSentence(it) }
        if (spoken.isNotEmpty()) spoken.append(' ')
        api.messages().createStreaming(params).use { stream ->
            val iterator = stream.stream().iterator()
            while (iterator.hasNext()) {
                if (job?.isActive == false) throw CancellationException("annullato")
                val event = iterator.next()
                accumulator.accumulate(event)
                event.contentBlockStart().ifPresent { start ->
                    start.contentBlock().serverToolUse().ifPresent { hooks.onToolUse("web_search") }
                }
                event.contentBlockDelta().ifPresent { d ->
                    d.delta().text().ifPresent { t ->
                        spoken.append(t.text())
                        splitter.feed(t.text())
                        hooks.onText(spoken.toString().trim())
                    }
                }
            }
        }
        splitter.flush()
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
            // Effort contenuto: risposte rapide, adatte alla conversazione a voce.
            .outputConfig(
                OutputConfig.builder()
                    .effort(if (o.model == ClaudeModel.HAIKU) OutputConfig.Effort.MEDIUM else OutputConfig.Effort.LOW)
                    .build()
            )
            // Riutilizza la parte fissa (istruzioni + strumenti) tra una richiesta e l'altra: costa meno.
            .cacheControl(CacheControlEphemeral.builder().build())
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
        const val MAX_HISTORY = 80
        const val CONVERSATION_TIMEOUT_MS = 15 * 60 * 1000L
    }
}
