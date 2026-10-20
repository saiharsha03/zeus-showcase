package dev.phonerecall.brain

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import dev.phonerecall.data.Availability
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.ScoredCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** A fake model drives the real loop: no network, no API calls. */
class EngineTest {
    object EmptyReads : BrainReads {
        override fun now(): ZonedDateTime = ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, ZoneId.of("America/New_York"))
        override fun relevantEvents(from: Long, to: Long) = emptyList<CalItem>()
        override fun allEvents(from: Long, to: Long) = emptyList<CalItem>()
        override fun tasks() = TaskSnapshot(
            listOf(Task(7, "Return the green scarf", TaskList.NEXT, null, null, false, 0.0, false, null, false)), loaded = true,
        )
        override fun closet() = ClosetSnapshot(loaded = true)
        override fun homeCards() = emptyList<ScoredCard>()
        override fun availability() = emptyMap<LocalDate, Availability>()
        override fun writableCalendars() = emptyList<CalendarChoice>()
    }

    private val json = jsonMapper()

    private fun message(stop: String, content: String) = json.readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-5-5","content":$content,
           "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":1200,"output_tokens":80,"cache_read_input_tokens":0,"cache_creation_input_tokens":0}}""",
        Message::class.java,
    )

    @Test fun writeToolOnlyProposesAndTheLoopCompletes() {
        val store = MemoryStore()
        val exec = FakeExecutor()
        val fates = Fates(store, FakeState(), exec, { _, _, _ -> }, { EmptyReads.now().toInstant().toEpochMilli() })
        val replies = ArrayDeque(
            listOf(
                message(
                    "tool_use",
                    """[{"type":"thinking","thinking":"","signature":"sig-abc"},
                        {"type":"text","text":"Checking."},
                        {"type":"tool_use","id":"tu_1","name":"read_tasks","input":{}},
                        {"type":"tool_use","id":"tu_2","name":"add_task","input":{"title":"Book the oil change","list":"next"}}]""",
                ),
                message("end_turn", """[{"type":"text","text":"It's on a card for you to confirm."}]"""),
            ),
        )
        val sent = ArrayList<MessageCreateParams>()
        val spent = ArrayList<TokenUse>()
        val guard = object : SpendGuard {
            override fun check() = Unit
            override fun record(model: BrainModel, use: TokenUse) { spent += use }
        }
        val engine = AthenaEngine({ p, onText -> sent += p; onText("x"); replies.removeFirst() }, ToolBox(EmptyReads, fates), guard)
        val history = mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("Remind me to book the oil change").build())
        val proposals = ArrayList<ToolOutcome>()
        val texts = ArrayList<String>()

        engine.run(history, BrainModel.SONNET, 1L, object : TurnSink {
            override fun proposal(outcome: ToolOutcome) { proposals += outcome }
            override fun assistant(text: String) { texts += text }
        })

        // Two model calls, both with the cached system prompt and every tool, adaptive thinking (no thinking param).
        assertEquals(2, sent.size)
        // 2c: search_memory and hand_off joined the read side: 8 reads + 9 writes.
        assertEquals(32, sent[0].tools().get().size)   // alarms: read_alarms; music: read_music, play_music, music_control; vaults: calypso_deposit, aegis_list, aegis_read; Hestia: home_status, home_control; Horae: place_call, bookmark_for; Kairos: suggest_timers; Argo: note_trip, read_trips; Metron: read_usage
        assertFalse(sent[0].thinking().isPresent)
        assertFalse(sent[0].toolChoice().isPresent)
        // History: user, assistant (with its thinking block), tool results, assistant.
        assertEquals(4, history.size)
        val results = json.writeValueAsString(history[2])
        assertTrue(results.contains("\"tool_use_id\":\"tu_1\""))
        assertTrue(results.contains("Return the green scarf"))
        assertTrue(results.contains("awaiting his confirmation"))
        assertTrue(json.writeValueAsString(history[1]).contains("sig-abc"))
        // The write tool parked a proposal and ran nothing.
        assertEquals(1, proposals.size)
        assertEquals(ProposalStatus.PENDING, store.all().single().status)
        assertTrue(exec.ran.isEmpty())
        assertEquals(listOf("Checking.", "It's on a card for you to confirm."), texts)
        assertEquals(2, spent.size)

        // The stored history round-trips through JSON, as BrainDb keeps it.
        val back = history.map { json.readValue(json.writeValueAsString(it), MessageParam::class.java) }
        assertEquals(history.map { json.writeValueAsString(it) }, back.map { json.writeValueAsString(it) })
    }

    @Test fun cutOffToolCallsNeverRun() {
        val store = MemoryStore()
        val fates = Fates(store, FakeState(), FakeExecutor(), { _, _, _ -> })
        val guard = object : SpendGuard { override fun check() = Unit; override fun record(model: BrainModel, use: TokenUse) = Unit }
        val engine = AthenaEngine({ _, _ -> message("max_tokens", """[{"type":"tool_use","id":"tu_1","name":"add_task","input":{"title":"Bo"}}]""") }, ToolBox(EmptyReads, fates), guard)
        val e = runCatching { engine.run(mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("x").build()), BrainModel.SONNET, 1L, object : TurnSink {}) }.exceptionOrNull()
        assertTrue(e is TurnError)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun haikuSkipsTheServerFallbackSonnetAndOpusUseIt() {
        val engine = AthenaEngine({ _, _ -> error("unused") }, ToolBox(EmptyReads, Fates(MemoryStore(), FakeState(), FakeExecutor(), { _, _, _ -> })), object : SpendGuard {
            override fun check() = Unit; override fun record(model: BrainModel, use: TokenUse) = Unit
        })
        val history = listOf(MessageParam.builder().role(MessageParam.Role.USER).content("hi").build())
        assertTrue(engine.params(history, BrainModel.SONNET)._additionalBodyProperties().containsKey("fallbacks"))
        assertFalse(engine.params(history, BrainModel.HAIKU)._additionalBodyProperties().containsKey("fallbacks"))
        assertFalse(ATHENA_SYSTEM.contains('—'))
    }
}
