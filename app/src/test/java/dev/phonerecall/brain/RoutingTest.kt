package dev.phonerecall.brain

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delphi on Auto: Haiku first, Sonnet when the turn needs it, Metis (the advisor tool) only on Sonnet turns. */
class RoutingTest {
    private val json = jsonMapper()

    private fun message(model: String, stop: String, content: String, usage: String = """"input_tokens":100,"output_tokens":20""") = json.readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"$model","content":$content,"stop_reason":"$stop","stop_sequence":null,"usage":{$usage}}""",
        Message::class.java,
    )

    private fun tools(n: Int) = (1..n).joinToString(",", "[", "]") { """{"type":"tool_use","id":"tu_$it","name":"read_tasks","input":{}}""" }

    private class Guard(val metis: Boolean) : SpendGuard {
        var advisor = 0L
        override fun check() = Unit
        override fun record(model: BrainModel, use: TokenUse) = Unit
        override fun recordAdvisor(micros: Long) { advisor += micros }
        override fun advisorAllowed() = metis
    }

    private fun fates() = Fates(MemoryStore(), FakeState(), FakeExecutor(), { _, _, _ -> })

    @Test fun quickTurnsStayOnHaiku() {
        val sent = ArrayList<MessageCreateParams>()
        val routes = ArrayList<String>()
        val engine = AthenaEngine({ p, _ -> sent += p; message("claude-haiku-5-5", "end_turn", """[{"type":"text","text":"Tomorrow is a remote day."}]""") }, ToolBox(EngineTest.EmptyReads, fates()), Guard(true))
        engine.run(mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("is tomorrow an office day").build()), BrainModel.SONNET, 1L, object : TurnSink { override fun route(line: String) { routes += line } }, auto = true)
        assertEquals("claude-haiku-5-5", sent.single().model().asString())
        assertEquals(listOf("Quick, Haiku 5.5"), routes)
        // No advisor on a Haiku turn.
        assertFalse(sent.single()._additionalBodyProperties().containsKey("tools"))
    }

    @Test fun moreThanTwoToolCallsMovesTheRestOfTheTurnToSonnetWithMetis() {
        val sent = ArrayList<MessageCreateParams>()
        val replies = ArrayDeque(listOf(
            message("claude-haiku-5-5", "tool_use", tools(3)),
            message(
                "claude-sonnet-5-5", "end_turn",
                """[{"type":"server_tool_use","id":"srvtoolu_1","name":"advisor","input":{}},
                    {"type":"advisor_tool_result","tool_use_id":"srvtoolu_1","content":{"type":"advisor_redacted_result","encrypted_content":"ZXhh","stop_reason":"end_turn"}},
                    {"type":"text","text":"Here is the plan."}]""",
                """"input_tokens":2000,"output_tokens":300,"iterations":[{"type":"message","input_tokens":2000,"output_tokens":300},{"type":"advisor_message","model":"claude-fable-5-1","input_tokens":1000,"output_tokens":200}]""",
            ),
        ))
        val routes = ArrayList<String>()
        val guard = Guard(true)
        var created = 0
        val gateway = object : ModelGateway {
            override fun send(params: MessageCreateParams, onText: (String) -> Unit): Message { sent += params; return replies.removeFirst() }
            override fun create(params: MessageCreateParams): Message { created++; sent += params; return replies.removeFirst() }
        }
        val history = mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("what's on for this week").build())
        AthenaEngine(gateway, ToolBox(EngineTest.EmptyReads, fates()), guard).run(history, BrainModel.SONNET, 1L, object : TurnSink { override fun route(line: String) { routes += line } }, auto = true)
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), sent.map { it.model().asString() })
        // The Sonnet turn carries the documented advisor tool, non-streamed, behind its beta.
        assertEquals(1, created)
        val body = json.writeValueAsString(sent[1]._additionalBodyProperties()["tools"])
        assertTrue(body.contains("\"type\":\"advisor_20260301\"") && body.contains("\"model\":\"claude-fable-5-1\"") && body.contains("\"max_uses\":2") && body.contains("\"caching\""))
        assertTrue(sent[1]._headers().values("anthropic-beta").joinToString().contains("advisor-tool-2026-03-01"))
        assertEquals(listOf("Sonnet 5.5, Athena consulted Metis"), routes)
        // Fable prices for the advisor's tokens: 1000 x $10 + 200 x $50 per million.
        assertEquals(20_000L, guard.advisor)
        // The encrypted advice is kept for replay, so the tool must stay on in this chat.
        assertTrue(AthenaEngine.hasAdvisor(history))
        assertTrue(json.writeValueAsString(history.last()).contains("advisor_redacted_result"))
    }

    @Test fun planningStartsOnSonnetAndMetisAtHerCapIsNotOffered() {
        val sent = ArrayList<MessageCreateParams>()
        val engine = AthenaEngine({ p, _ -> sent += p; message("claude-sonnet-5-5", "end_turn", """[{"type":"text","text":"ok"}]""") }, ToolBox(EngineTest.EmptyReads, fates()), Guard(false))
        engine.run(mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("help me plan the trip to Denver").build()), BrainModel.HAIKU, 1L, object : TurnSink {}, auto = true)
        assertEquals("claude-sonnet-5-5", sent.single().model().asString())
        assertFalse(sent.single()._additionalBodyProperties().containsKey("tools"))
        assertEquals(32, sent.single().tools().get().size)
    }

    @Test fun thePickerOverridesAuto() {
        val sent = ArrayList<MessageCreateParams>()
        val engine = AthenaEngine({ p, _ -> sent += p; message("claude-opus-5-5", "end_turn", """[{"type":"text","text":"ok"}]""") }, ToolBox(EngineTest.EmptyReads, fates()), Guard(true))
        engine.run(mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("hi").build()), BrainModel.OPUS, 1L, object : TurnSink {}, auto = false)
        assertEquals("claude-opus-5-5", sent.single().model().asString())
    }

    @Test fun advisorErrorsAreReadNotShown() {
        val m = message(
            "claude-sonnet-5-5", "end_turn",
            """[{"type":"server_tool_use","id":"s1","name":"advisor","input":{}},{"type":"advisor_tool_result","tool_use_id":"s1","content":{"type":"advisor_tool_result_error","error_code":"overloaded"}},{"type":"text","text":"ok"}]""",
        )
        assertEquals("overloaded", AthenaEngine.advisorUse(m)!!.third)
    }

    @Test fun handOffIsTierOneAndSearchMemoryReturnsCitations() {
        var started: String? = null
        val extras = object : DelphiExtras {
            override fun searchMemory(query: String, k: Int) = """{"results":[{"cite":"[1]","title":"Lease agreement"}]}"""
            override fun handOff(title: String, brief: String, protege: String?): String { started = "$title/$protege"; return "Started long job job-1." }
        }
        val store = MemoryStore()
        val box = ToolBox(EngineTest.EmptyReads, Fates(store, FakeState(), FakeExecutor(), { _, _, _ -> }), extras)
        assertTrue(box.run("search_memory", mapOf("query" to "lease end"), 1L).text.contains("[1]"))
        assertEquals("Started long job job-1.", box.run("hand_off", mapOf("title" to "Winter tires", "brief" to "research", "protege" to "Perseus"), 1L).text)
        assertEquals("Winter tires/Perseus", started)
        assertTrue("no proposal for a hand-off", store.rows.isEmpty())
    }
}
