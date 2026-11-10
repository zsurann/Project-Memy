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
import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.tools.NexaTool
import com.nexa.assistant.tools.ToolInput
import com.nexa.assistant.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * L'agente: manda la richiesta a Claude, esegue gli strumenti che Claude sceglie,
 * rimanda i risultati e ripete finché Claude non dà la risposta finale.
 */
class NexaAgent(
    private val tools: List<NexaTool>,
    private val hooks: Hooks,
    /** Solo per i test: indirizzo di un server finto al posto di api.anthropic.com. */
    private val baseUrl: String? = null,
) {
    interface Hooks {
        /** Chiede all'utente conferma per un'azione delicata. */
        suspend fun confirm(prompt: String): Boolean

        /** Notifica (per la UI) che Nexa sta usando uno strumento. */
        fun onToolUse(toolName: String) {}
    }

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

    suspend fun run(
        userText: String,
        apiKey: String,
        model: ClaudeModel,
        userName: String,
    ): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (now - lastActivity > CONVERSATION_TIMEOUT_MS || history.size > MAX_HISTORY) history.clear()
        lastActivity = now

        val api = clientFor(apiKey)
        val checkpoint = history.size
        history += MessageParam.ofUser(userText)

        try {
            repeat(MAX_STEPS) {
                val response = api.messages().create(buildParams(model, userName))
                history += response.toParam()

                when (response.stopReason().orElse(StopReason.END_TURN)) {
                    StopReason.TOOL_USE -> history += runTools(response)
                    StopReason.PAUSE_TURN -> Unit // il server chiede di proseguire: rimandiamo la richiesta
                    StopReason.REFUSAL -> {
                        // Una richiesta rifiutata non deve restare nella memoria della conversazione.
                        history.clear()
                        return@withContext "Mi dispiace, non posso aiutarti con questa richiesta."
                    }
                    else -> {
                        lastActivity = System.currentTimeMillis()
                        return@withContext textOf(response).ifBlank { "Fatto." }
                    }
                }
            }
            "Ho fatto troppi passaggi senza finire. Prova a chiedermelo in modo più semplice."
        } catch (e: AnthropicServiceException) {
            rollback(checkpoint)
            throw AgentException(describe(e))
        } catch (e: AnthropicIoException) {
            rollback(checkpoint)
            throw AgentException("Non riesco a collegarmi a internet. Controlla la connessione.")
        }
    }

    /** In caso di errore si torna allo stato precedente, così la cronologia resta valida. */
    private fun rollback(checkpoint: Int) {
        while (history.size > checkpoint) history.removeAt(history.lastIndex)
    }

    private suspend fun runTools(response: Message): MessageParam {
        val results = response.content().mapNotNull { it.toolUse().orElse(null) }.map { block ->
            val result = executeTool(block)
            ContentBlockParam.ofToolResult(
                ToolResultBlockParam.builder()
                    .toolUseId(block.id())
                    .content(result.text)
                    .isError(result.isError)
                    .build()
            )
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
            ToolResult.error("Permesso mancante: ${e.message}. L'utente deve concederlo nelle impostazioni di Nexa.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.error("Errore durante l'azione: ${e.message}")
        }
    }

    private fun buildParams(model: ClaudeModel, userName: String): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(8000L)
            .system(SystemPrompt.build(userName))
            .messages(history.toList())
            // Effort basso: risposte veloci, ideali per la voce, e meno token spesi.
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            // Riutilizza la parte fissa (istruzioni + strumenti) tra una richiesta e l'altra: costa meno.
            .cacheControl(CacheControlEphemeral.builder().build())
        toolDefs.forEach { builder.addTool(it) }

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

    private fun textOf(message: Message): String =
        message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString(" ").trim()

    private fun toApiTool(tool: NexaTool): Tool {
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
        const val MAX_STEPS = 10
        const val MAX_HISTORY = 60
        const val CONVERSATION_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
