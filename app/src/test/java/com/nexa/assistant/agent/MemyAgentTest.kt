package com.nexa.assistant.agent

import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.tools.MemyTool
import com.nexa.assistant.tools.ToolInput
import com.nexa.assistant.tools.ToolResult
import com.nexa.assistant.tools.prop
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Verifica il ciclo completo agente → strumento → risposta in streaming, con un server finto al posto di Claude. */
class MemyAgentTest {

    private lateinit var server: MockWebServer

    private class FakeTorch(private val needsConfirm: Boolean = false) : MemyTool {
        val calls = mutableListOf<ToolInput>()
        override val name = "set_flashlight"
        override val description = "Accende o spegne la torcia."
        override val properties = mapOf("on" to prop("boolean", "accesa?"))
        override val required = listOf("on")
        override suspend fun confirmationPrompt(input: ToolInput) = if (needsConfirm) "Confermi?" else null
        override suspend fun execute(input: ToolInput): ToolResult {
            calls += input
            return ToolResult.ok("Torcia accesa.")
        }
    }

    private class Hooks(private val answer: Boolean) : MemyAgent.Hooks {
        val prompts = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        val tools = mutableListOf<String>()
        override suspend fun confirm(prompt: String): Boolean { prompts += prompt; return answer }
        override fun onSentence(sentence: String) { sentences += sentence }
        override fun onToolUse(toolName: String) { tools += toolName }
    }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() = server.shutdown()

    private fun sse(vararg events: JSONObject) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "event: ${it.getString("type")}\ndata: $it\n\n" })

    private fun start(model: String) = JSONObject().put("type", "message_start").put(
        "message", JSONObject("""{"id":"msg","type":"message","role":"assistant","model":"$model","content":[],
            "stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}""")
    )
    private fun blockStart(i: Int, block: String) = JSONObject("""{"type":"content_block_start","index":$i,"content_block":$block}""")
    private fun delta(i: Int, delta: JSONObject) = JSONObject().put("type", "content_block_delta").put("index", i).put("delta", delta)
    private fun textDelta(i: Int, t: String) = delta(i, JSONObject().put("type", "text_delta").put("text", t))
    private fun blockStop(i: Int) = JSONObject("""{"type":"content_block_stop","index":$i}""")
    private fun end(reason: String) = JSONObject("""{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null},"usage":{"output_tokens":20}}""")
    private val stop = JSONObject("""{"type":"message_stop"}""")

    private fun toolUseStream() = sse(
        start("claude-opus-5-5"),
        blockStart(0, """{"type":"thinking","thinking":"","signature":""}"""),
        delta(0, JSONObject().put("type", "signature_delta").put("signature", "sig123")),
        blockStop(0),
        blockStart(1, """{"type":"text","text":""}"""),
        textDelta(1, "Certo, accendo "), textDelta(1, "la torcia. "),
        blockStop(1),
        blockStart(2, """{"type":"tool_use","id":"toolu_1","name":"set_flashlight","input":{}}"""),
        delta(2, JSONObject().put("type", "input_json_delta").put("partial_json", "{\"on\": true}")),
        blockStop(2),
        end("tool_use"), stop,
    )

    private fun finalStream() = sse(
        start("claude-opus-5-5"),
        blockStart(0, """{"type":"text","text":""}"""),
        textDelta(0, "Ecco fatto, la torcia "), textDelta(0, "è accesa! Serve altro?"),
        blockStop(0),
        end("end_turn"), stop,
    )

    private fun agent(tool: MemyTool, hooks: MemyAgent.Hooks) =
        MemyAgent(listOf(tool), hooks, baseUrl = server.url("/").toString().trimEnd('/'))

    private fun opts(model: ClaudeModel = ClaudeModel.OPUS, web: Boolean = true) =
        MemyAgent.Options("sk-test", model, "Luca", web)

    @Test
    fun streamsSentencesRunsToolAndKeepsThinking() = runBlocking {
        server.enqueue(toolUseStream())
        server.enqueue(finalStream())
        val torch = FakeTorch()
        val hooks = Hooks(true)

        val reply = agent(torch, hooks).run("accendi la torcia", opts())

        // La prima frase si legge subito (velocità); quando parte lo strumento è la voce a fermarsi.
        assertEquals(listOf("Certo, accendo la torcia.", "Ecco fatto, la torcia è accesa!", "Serve altro?"), hooks.sentences)
        assertEquals("Ecco fatto, la torcia è accesa! Serve altro?", reply)
        assertEquals(1, torch.calls.size)
        assertEquals(true, torch.calls[0].bool("on"))

        val first = server.takeRequest()
        assertEquals("/v1/messages", first.path)
        assertEquals("Bearer sk-test", first.getHeader("Authorization"))
        val body1 = JSONObject(first.body.readUtf8())
        assertTrue(body1.getBoolean("stream"))
        assertEquals("claude-opus-5-5", body1.getString("model"))
        assertEquals("low", body1.getJSONObject("output_config").getString("effort"))
        assertEquals("ephemeral", body1.getJSONObject("cache_control").getString("type"))
        assertEquals("default", body1.getString("fallbacks"))
        assertTrue(first.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        val toolTypes = toolNames(body1.getJSONArray("tools"))
        assertTrue(toolTypes.contains("set_flashlight"))
        assertTrue("ricerca web attiva", toolTypes.contains("web_search"))
        assertTrue(body1.getString("system").contains("Luca"))
        val userText = body1.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue("contesto data/ora", userText.startsWith("[") && userText.endsWith("accendi la torcia"))

        val body2 = JSONObject(server.takeRequest().body.readUtf8())
        val msgs = body2.getJSONArray("messages")
        assertEquals(3, msgs.length())
        val assistant = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", assistant.getJSONObject(0).getString("type"))
        assertEquals("sig123", assistant.getJSONObject(0).getString("signature"))
        assertEquals("tool_use", assistant.getJSONObject(2).getString("type"))
        assertEquals(true, assistant.getJSONObject(2).getJSONObject("input").getBoolean("on"))
        val result = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("toolu_1", result.getString("tool_use_id"))
    }

    private fun toolNames(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }

    @Test
    fun deniedConfirmationDoesNotRunTool() = runBlocking {
        server.enqueue(toolUseStream())
        server.enqueue(finalStream())
        val torch = FakeTorch(needsConfirm = true)
        val hooks = Hooks(answer = false)

        agent(torch, hooks).run("accendi la torcia", opts())

        assertEquals(listOf("Confermi?"), hooks.prompts)
        assertTrue(torch.calls.isEmpty())
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("NON ha confermato"))
    }

    @Test
    fun haikuUsesBasicWebSearchAndNoFallback() = runBlocking {
        server.enqueue(finalStream())
        agent(FakeTorch(), Hooks(true)).run("ciao", opts(ClaudeModel.HAIKU))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("fallbacks"))
        val tools = body.getJSONArray("tools")
        val web = (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == "web_search" }
        assertEquals("web_search_20250305", web.getString("type"))
        assertEquals("low", body.getJSONObject("output_config").getString("effort"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
    }

    @Test
    fun sonnetAnswersWithoutInitialThinkingAndOpusKeepsIt() = runBlocking {
        server.enqueue(finalStream())
        server.enqueue(finalStream())
        agent(FakeTorch(), Hooks(true)).run("ciao", opts(ClaudeModel.SONNET))
        val sonnet = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("between_tools", sonnet.getJSONObject("thinking").getString("type"))
        agent(FakeTorch(), Hooks(true)).run("ciao", opts(ClaudeModel.OPUS))
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("thinking"))
    }

    @Test
    fun memoriesReachTheSystemPrompt() = runBlocking {
        server.enqueue(finalStream())
        agent(FakeTorch(), Hooks(true)).run(
            "ciao", MemyAgent.Options("sk-test", ClaudeModel.OPUS, "", webSearch = false, memories = listOf("La sorella si chiama Giulia")),
        )
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getString("system").contains("La sorella si chiama Giulia"))
        assertFalse(toolNames(body.getJSONArray("tools")).contains("web_search"))
    }

    @Test
    fun imageResultsAreSentAsImageBlocks() = runBlocking {
        server.enqueue(toolUseStream())
        server.enqueue(finalStream())
        val camera = object : MemyTool {
            override val name = "set_flashlight"
            override val description = "finto"
            override val properties = mapOf("on" to prop("boolean", "x"))
            override val required = listOf("on")
            override suspend fun execute(input: ToolInput) = ToolResult("Ecco lo schermo", imageJpeg = byteArrayOf(1, 2, 3))
        }
        agent(camera, Hooks(true)).run("guarda", opts())
        server.takeRequest()
        val msgs = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
        val content = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image", content.getJSONObject(1).getString("type"))
        assertEquals("image/jpeg", content.getJSONObject(1).getJSONObject("source").getString("media_type"))
    }

    @Test
    fun invalidKeyGivesItalianMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        try {
            agent(FakeTorch(), Hooks(true)).run("ciao", opts())
            fail("doveva fallire")
        } catch (e: MemyAgent.AgentException) {
            assertTrue(e.message!!.contains("chiave API non è valida"))
        }
    }

    @Test
    fun autoModeStartsWithHaikuAndEscalatesToSonnet() = runBlocking {
        server.enqueue(sse(
            start("claude-haiku-5-5"),
            blockStart(0, """{"type":"tool_use","id":"toolu_up","name":"think_harder","input":{}}"""),
            delta(0, JSONObject().put("type", "input_json_delta").put("partial_json", "{}")),
            blockStop(0),
            end("tool_use"), stop,
        ))
        server.enqueue(finalStream())
        val reply = agent(FakeTorch(), Hooks(true)).run("scrivimi una lettera di dimissioni", opts(model = ClaudeModel.AUTO))
        assertTrue(reply.contains("Serve altro?"))
        val first = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("claude-haiku-5-5", first.getString("model"))
        assertTrue("Haiku può salire", toolNames(first.getJSONArray("tools")).contains("think_harder"))
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("claude-sonnet-5-5", second.getString("model"))
        assertTrue("Sonnet non sale oltre", !toolNames(second.getJSONArray("tools")).contains("think_harder"))
    }

    @Test
    fun sharedPdfIsSentAsDocument() = runBlocking {
        server.enqueue(finalStream())
        agent(FakeTorch(), Hooks(true)).run("riassumilo", opts(), attachment = MemyAgent.Attachment.Pdf("%PDF-1.4".toByteArray()))
        val content = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("document", content.getJSONObject(0).getString("type"))
        assertEquals("application/pdf", content.getJSONObject(0).getJSONObject("source").getString("media_type"))
        assertTrue(content.getJSONObject(1).getString("text").endsWith("riassumilo"))
    }
}
