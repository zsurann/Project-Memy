package com.nexa.assistant.agent

import com.nexa.assistant.data.ClaudeModel
import com.nexa.assistant.tools.NexaTool
import com.nexa.assistant.tools.ToolInput
import com.nexa.assistant.tools.ToolResult
import com.nexa.assistant.tools.prop
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Verifica il ciclo completo agente → strumento → risposta, con un server finto al posto di Claude. */
class NexaAgentTest {

    private lateinit var server: MockWebServer

    private class FakeTorch(private val needsConfirm: Boolean = false) : NexaTool {
        var calls = mutableListOf<ToolInput>()
        override val name = "set_flashlight"
        override val description = "Accende o spegne la torcia."
        override val properties = mapOf("on" to prop("boolean", "accesa?"))
        override val required = listOf("on")
        override fun confirmationPrompt(input: ToolInput) = if (needsConfirm) "Confermi?" else null
        override suspend fun execute(input: ToolInput): ToolResult {
            calls += input
            return ToolResult.ok("Torcia accesa.")
        }
    }

    private class Hooks(private val answer: Boolean) : NexaAgent.Hooks {
        val prompts = mutableListOf<String>()
        override suspend fun confirm(prompt: String): Boolean {
            prompts += prompt; return answer
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun json(body: String) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json").setBody(body)

    private val toolUseResponse = """
        {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"thinking","thinking":"","signature":"sig123"},
                    {"type":"tool_use","id":"toolu_1","name":"set_flashlight","input":{"on":true}}],
         "stop_reason":"tool_use","stop_sequence":null,
         "usage":{"input_tokens":10,"output_tokens":5}}
    """.trimIndent()

    private val finalResponse = """
        {"id":"msg_2","type":"message","role":"assistant","model":"claude-haiku-5-5",
         "content":[{"type":"text","text":"Ecco, ho acceso la torcia."}],
         "stop_reason":"end_turn","stop_sequence":null,
         "usage":{"input_tokens":20,"output_tokens":8}}
    """.trimIndent()

    private fun agent(tool: NexaTool, hooks: NexaAgent.Hooks) =
        NexaAgent(listOf(tool), hooks, baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun runsToolAndReturnsFinalText() = runBlocking {
        server.enqueue(json(toolUseResponse))
        server.enqueue(json(finalResponse))
        val torch = FakeTorch()

        val reply = agent(torch, Hooks(true)).run("accendi la torcia", "sk-test", ClaudeModel.HAIKU, "Luca")

        assertEquals("Ecco, ho acceso la torcia.", reply)
        assertEquals(1, torch.calls.size)
        assertEquals(true, torch.calls[0].bool("on"))

        val first = server.takeRequest()
        assertEquals("/v1/messages", first.path)
        assertEquals("Bearer sk-test", first.getHeader("Authorization"))
        val body1 = JSONObject(first.body.readUtf8())
        assertEquals("claude-haiku-5-5", body1.getString("model"))
        assertEquals("low", body1.getJSONObject("output_config").getString("effort"))
        assertEquals("ephemeral", body1.getJSONObject("cache_control").getString("type"))
        assertEquals("set_flashlight", body1.getJSONArray("tools").getJSONObject(0).getString("name"))
        assertTrue(body1.getString("system").contains("Luca"))
        assertFalse("Haiku non usa i fallback", body1.has("fallbacks"))

        val body2 = JSONObject(server.takeRequest().body.readUtf8())
        val msgs = body2.getJSONArray("messages")
        assertEquals(3, msgs.length())
        // Il blocco di ragionamento viene rimandato intatto.
        val assistant = msgs.getJSONObject(1).getJSONArray("content")
        assertEquals("thinking", assistant.getJSONObject(0).getString("type"))
        assertEquals("sig123", assistant.getJSONObject(0).getString("signature"))
        val result = msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("toolu_1", result.getString("tool_use_id"))
        assertEquals("Torcia accesa.", result.getString("content"))
    }

    @Test
    fun deniedConfirmationDoesNotRunTool() = runBlocking {
        server.enqueue(json(toolUseResponse))
        server.enqueue(json(finalResponse))
        val torch = FakeTorch(needsConfirm = true)
        val hooks = Hooks(answer = false)

        agent(torch, hooks).run("accendi la torcia", "sk-test", ClaudeModel.HAIKU, "")

        assertEquals(listOf("Confermi?"), hooks.prompts)
        assertTrue(torch.calls.isEmpty())
        server.takeRequest()
        val body2 = server.takeRequest().body.readUtf8()
        assertTrue(body2.contains("NON ha confermato"))
    }

    @Test
    fun sonnetSendsServerSideFallback() = runBlocking {
        server.enqueue(json(finalResponse))
        agent(FakeTorch(), Hooks(true)).run("ciao", "sk-test", ClaudeModel.SONNET, "")
        val req = server.takeRequest()
        assertTrue(req.getHeader("anthropic-beta").orEmpty().contains("server-side-fallback-2026-07-01"))
        assertEquals("default", JSONObject(req.body.readUtf8()).getString("fallbacks"))
    }

    @Test
    fun invalidKeyGivesItalianMessage() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
                .setBody("""{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""")
        )
        try {
            agent(FakeTorch(), Hooks(true)).run("ciao", "sk-bad", ClaudeModel.HAIKU, "")
            fail("doveva fallire")
        } catch (e: NexaAgent.AgentException) {
            assertTrue(e.message!!.contains("chiave API non è valida"))
        }
    }
}
