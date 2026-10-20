package dev.phonerecall.voice

import com.anthropic.core.jsonMapper
import dev.phonerecall.brain.EngineTest
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ToolBox
import dev.phonerecall.brain.ToolOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.Base64

class LiveProtocolTest {
    private val json = jsonMapper()
    private val fakeKey = "AIzaSyFAKE-test-key-0123456789abcdefghij"

    private fun box(exec: FakeExecutor = FakeExecutor(), auto: Set<ProposalKind> = emptySet()): Pair<ToolBox, Fates> {
        val fates = Fates(MemoryStore(), FakeState(), exec, { _, _, _ -> }, { EngineTest.EmptyReads.now().toInstant().toEpochMilli() }).apply { autoApprove = auto }
        return ToolBox(EngineTest.EmptyReads, fates) to fates
    }

    private fun callFrame(name: String, args: String) = """{"toolCall":{"functionCalls":[{"id":"fc_1","name":"$name","args":$args}]}}"""

    @Test fun setupMirrorsDelphisReadAndProposalToolsOnly() {
        val setup = json.readTree(LiveProtocol.setup("Monday, October 19, 3:00 PM"))
        assertEquals("models/gemini-3.8-live", setup.path("setup").path("model").asText())
        assertEquals("AUDIO", setup.path("setup").path("generationConfig").path("responseModalities")[0].asText())
        val names = setup.path("setup").path("tools")[0].path("functionDeclarations").map { it.path("name").asText() }
        assertEquals(LiveProtocol.READS + LiveProtocol.PROPOSALS, names)
        for (never in listOf("hand_off", "propose_plan", "calypso_deposit", "aegis_list", "aegis_read")) assertFalse(never, never in names)
        // Gemini's schema has no additionalProperties; tools with no inputs send no parameters.
        assertFalse(setup.toString().contains("additionalProperties"))
        val timer = setup.path("setup").path("tools")[0].path("functionDeclarations").first { it.path("name").asText() == "set_timer" }
        assertEquals("integer", timer.path("parameters").path("properties").path("seconds").path("type").asText())
        assertEquals("seconds", timer.path("parameters").path("required")[0].asText())
        val now = setup.path("setup").path("tools")[0].path("functionDeclarations").first { it.path("name").asText() == "now" }
        assertTrue(now.path("parameters").isMissingNode)
    }

    @Test fun aGeminiWriteBecomesAPendingProposalAndNothingRuns() {
        val exec = FakeExecutor()
        val (tools, _) = box(exec)
        val made = ArrayList<Proposal>()
        val bridge = LiveToolBridge(tools, 4L) { made += it }
        val calls = (LiveProtocol.parse(callFrame("set_timer", """{"seconds":600,"label":"Tea"}""")).single() as LiveEvent.Calls).calls
        val reply = json.readTree(bridge.answerAll(calls)).path("toolResponse").path("functionResponses")[0]
        assertEquals("fc_1", reply.path("id").asText())
        assertEquals("set_timer", reply.path("name").asText())
        assertTrue(reply.path("response").path("result").asText().contains("awaiting his confirmation"))
        assertEquals(ProposalStatus.PENDING, made.single().status)
        assertEquals(ProposalKind.SET_TIMER, made.single().steps.single().kind)
        assertTrue(exec.ran.isEmpty())
    }

    @Test fun autoApproveIsRespectedAndOnlyWhenHeListedTheKind() {
        val exec = FakeExecutor()
        val (tools, _) = box(exec, auto = setOf(ProposalKind.SET_TIMER))
        val bridge = LiveToolBridge(tools, null)
        val r = json.readTree(bridge.answerAll(listOf(LiveCall("a", "set_timer", mapOf("seconds" to 60)))))
        assertTrue(r.toString().contains("auto-approve"))
        assertEquals(1, exec.ran.size)
        // An alarm is not on his list: a card, nothing run.
        bridge.answerAll(listOf(LiveCall("b", "set_alarm", mapOf("hour" to 6))))
        assertEquals(1, exec.ran.size)
    }

    @Test fun toolsOutsideTheMirrorAreRefusedWhateverGeminiAsks() {
        var ran = 0
        val bridge = LiveToolBridge({ _, _ -> ran++; ToolOutcome("secret") })
        for (n in listOf("aegis_read", "aegis_list", "calypso_deposit", "hand_off", "propose_plan", "run_shell")) {
            val r = bridge.answer(LiveCall("x", n, emptyMap()))
            assertTrue(n, r.third)
        }
        assertEquals(0, ran)
        val bad = json.readTree(bridge.answerAll(listOf(LiveCall("x", "aegis_read", mapOf("name" to "bank")))))
        assertTrue(bad.path("toolResponse").path("functionResponses")[0].path("response").has("error"))
    }

    @Test fun invalidArgumentsComeBackAsErrorsNotProposals() {
        val (tools, _) = box()
        val made = ArrayList<Proposal>()
        val r = LiveToolBridge(tools, null) { made += it }.answer(LiveCall("x", "set_timer", mapOf("seconds" to 999_999)))
        assertTrue(r.third)
        assertTrue(made.isEmpty())
    }

    @Test fun theKeyIsNeverInAnythingAModelSees() {
        val (tools, _) = box()
        val setup = LiveProtocol.setup("Monday")
        val reply = LiveToolBridge(tools, null).answerAll(listOf(LiveCall("x", "read_tasks", emptyMap()), LiveCall("y", "now", emptyMap())))
        val audio = LiveProtocol.audio(ByteArray(Pcm.FRAME_BYTES))
        for (frame in listOf(setup, reply, audio, LiveProtocol.audioEnd(), LiveProtocol.systemInstruction("Monday"))) assertFalse(fakeKey in frame)
        // The handshake carries it in one header, to the one Live host; the URL (and so any log line) never does.
        val req = LiveApi.handshake(fakeKey)
        assertEquals(fakeKey, req.header(LiveApi.KEY_HEADER))
        assertEquals(NetGate.LIVE_HOST, req.url.host)
        assertFalse(fakeKey in req.url.toString())
        assertFalse(fakeKey in NetGate.redact(req.url.toString()))
        // And the last check before any frame leaves: a frame with a key in it is refused.
        val guard = KeyGuard { listOf(fakeKey, "sk-ant-api03-fake") }
        assertTrue(guard.clean(setup))
        assertFalse(guard.clean(LiveProtocol.toolResponse(listOf(Triple(LiveCall("x", "now", emptyMap()), "here: $fakeKey", false)))))
        assertFalse(guard.clean("""{"x":"sk-ant-api03-fake"}"""))
    }

    @Test fun parsesAudioTranscriptsInterruptsAndGoAway() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val b64 = Base64.getEncoder().encodeToString(pcm)
        val events = LiveProtocol.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"$b64"}}]},"outputTranscription":{"text":"Hi"},"inputTranscription":{"text":"hello"},"turnComplete":true}}""",
        )
        assertArrayEquals(pcm, (events.filterIsInstance<LiveEvent.Audio>().single()).pcm)
        assertEquals(LiveEvent.Said("Hi"), events.filterIsInstance<LiveEvent.Said>().single())
        assertEquals(LiveEvent.Heard("hello"), events.filterIsInstance<LiveEvent.Heard>().single())
        assertTrue(LiveEvent.TurnComplete in events)
        assertEquals(listOf(LiveEvent.Interrupted), LiveProtocol.parse("""{"serverContent":{"interrupted":true}}"""))
        assertEquals(listOf(LiveEvent.SetupComplete), LiveProtocol.parse("""{"setupComplete":{}}"""))
        assertEquals(listOf(LiveEvent.GoAway("10s")), LiveProtocol.parse("""{"goAway":{"timeLeft":"10s"}}"""))
        assertEquals(listOf(LiveEvent.Cancelled(listOf("fc_1"))), LiveProtocol.parse("""{"toolCallCancellation":{"ids":["fc_1"]}}"""))
        assertTrue(LiveProtocol.parse("not json").isEmpty())
    }

    @Test fun audioFrames() {
        assertEquals(1280, Pcm.FRAME_BYTES)
        val s = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 0x1234)
        val b = Pcm.shortsToBytes(s)
        assertArrayEquals(byteArrayOf(0, 0, 1, 0, -1, -1, -1, 0x7F, 0, -128, 0x34, 0x12), b)
        assertArrayEquals(s, Pcm.bytesToShorts(b))
        val (frames, rest) = Pcm.frames(ByteArray(1280 * 2 + 100))
        assertEquals(2, frames.size); assertEquals(1280, frames[0].size); assertEquals(100, rest.size)
        assertEquals(1.0, Pcm.seconds(48_000, LiveApi.OUT_RATE), 1e-9)
        assertEquals(0f, Pcm.level(ShortArray(160)), 0f)
        assertEquals(1f, Pcm.level(ShortArray(160) { Short.MIN_VALUE }), 1e-4f)
        val frame = json.readTree(LiveProtocol.audio(byteArrayOf(9, 8)))
        assertEquals("audio/pcm;rate=16000", frame.path("realtimeInput").path("audio").path("mimeType").asText())
        assertArrayEquals(byteArrayOf(9, 8), Base64.getDecoder().decode(frame.path("realtimeInput").path("audio").path("data").asText()))
    }

    @Test fun whatsNextIsSpokenFromTheCalendar() {
        val now = LocalDateTime.of(2026, 10, 19, 15, 0)
        val cal = """{"events":[{"title":"Standup","start":"2026-10-19T09:00","all_day":false},{"title":"Holiday","start":"2026-10-19","all_day":true},
            {"title":"Dentist","start":"2026-10-19T16:30","all_day":false,"place":"Main St"},{"title":"Gym","start":"2026-10-20T07:00","all_day":false}]}"""
        assertEquals("Next: Dentist at 4:30 PM, at Main St.", VoiceController.nextEvent(cal, now))
        assertEquals("Next: Gym tomorrow at 7:00 AM.", VoiceController.nextEvent(cal, now.withHour(18)))
        assertEquals("Nothing else on your calendar today or tomorrow.", VoiceController.nextEvent("""{"events":[]}""", now))
    }
}
