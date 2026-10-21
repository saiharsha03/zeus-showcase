package dev.phonerecall.mind

import dev.phonerecall.brain.EngineTest
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.SpendCapReached
import dev.phonerecall.brain.ToolBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class FakeExtras : MindExtras {
    val reports = ArrayList<Map<String, Any?>>()
    lateinit var mind: Mind
    override fun cards() = listOf(mapOf<String, Any?>("title" to "Amma called twice", "body" to "Last call 9:41 PM"))
    override fun peopleLabels() = listOf(mapOf<String, Any?>("label" to "family (mother)", "tier" to "Reply now", "count" to 1))
    override fun sections() = listOf(mapOf<String, Any?>("name" to "Groceries", "open_items" to listOf("tomatoes")))
    override fun searchMemory(query: String, k: Int) = """{"results":[]}"""
    override fun reportJob(input: Map<String, Any?>, thread: String?): String { reports += input; return mind.reportJob(input, thread) }
}

/** Builds a Mind on fakes: no network, no API calls. */
class Rig(var now: Long = 1_792_000_000_000L) {
    val api = FakeMindApi()
    val kv = MapKv()
    val sink = FakeSink()
    val context = FakeContext()
    val ledger = FakeLedger()
    val store = MemoryStore()
    val exec = FakeExecutor()
    val fates = Fates(store, FakeState(), exec, { _, _, _ -> }, { now })
    val extras = FakeExtras()
    val tools = MindTools(ToolBox(EngineTest.EmptyReads, fates), extras) { Sanitizer(context.names(), context.sealed()) }
    /** What the mind told Clio. */
    val traces = ArrayList<String>()
    val mind = Mind(api, kv, tools, sink, context, ledger, clock = { now }, driveMs = 10_000L, trace = { e, d -> traces += "$e: $d" }).also { extras.mind = it }
}

class SetupTest {
    @Test fun setupCreatesEverythingOnceAndReusesTheIds() {
        val r = Rig()
        val first = r.mind.ensure()
        // One environment, one memory store, six protégés + Athena, five schedules.
        assertEquals(1, r.api.environments.size)
        assertEquals(1, r.api.stores.size)
        assertEquals(7, r.api.agents.size)
        assertEquals(5, r.api.deployments.size)
        val creates = r.api.creates
        assertEquals(14, creates)
        // Every later sync: nothing is created or updated again.
        repeat(3) { assertEquals(first, r.mind.ensure()) }
        assertEquals(creates, r.api.creates)
        assertEquals(0, r.api.updates)
        // Athena's roster: the six protégés, a copy of herself, and Metis as the advisor.
        val athena = r.api.agents.getValue(MindPrompts.ATHENA_NAME)
        assertEquals(6, athena.roster.size)
        assertTrue(athena.self)
        assertEquals(Metis.MODEL, athena.advisor)
        assertEquals("claude-sonnet-5-5", athena.model)
        // Annabeth on Opus 5.5 at high effort (Opus defaults to medium).
        val annabeth = r.api.agents.getValue(Protege.ANNABETH.agentName)
        assertEquals("claude-opus-5-5", annabeth.model)
        assertEquals("high", annabeth.effort)
        // Children get no proposal tools.
        assertTrue(annabeth.tools.none { it.name in MindTools.WRITES })
        assertTrue(athena.tools.any { it.name == "add_task" })
        // Agora: Athena posts; her circle doesn't. Seven more than the circle (post_agora, the two schedule tools, report_check, propose_trips, spawn_task, ask_prometheus) plus the dream's two fact tools.
        assertTrue(athena.tools.any { it.name == MindTools.AGORA })
        assertTrue(annabeth.tools.none { it.name == MindTools.AGORA })
        assertEquals(r.tools.specs(circle = true).size + MindTools.WRITES.size + 7 + MindTools.FACTS.size + MindTools.HABITS.size, r.tools.specs(circle = false).size)   // post_agora, read_schedules, propose_schedule, report_check, propose_trips, spawn_task, ask_prometheus; read_habits, propose_shortcuts, propose_ideas
        // The chat is her now: she reads what the old phone chat read (closet, music, alarms), and only she spawns tasks.
        assertTrue(MindTools.PHONE_READS.all { n -> athena.tools.any { it.name == n } })
        assertTrue(athena.tools.any { it.name == MindTools.SPAWN })
        assertTrue(annabeth.tools.none { it.name == MindTools.SPAWN })
        // read_zeus is a read: Athena and every protégé get it (one more tool each).
        assertTrue(athena.tools.any { it.name == ZeusRead.NAME } && annabeth.tools.any { it.name == ZeusRead.NAME })
        assertTrue(athena.tools.any { it.name == MindTools.TRIPS })
        assertTrue(annabeth.tools.none { it.name == MindTools.TRIPS })
        assertTrue(annabeth.tools.none { it.name in MindTools.SCHEDULE })
    }

    @Test fun aLostReplyIsFoundAgainNotCreatedTwice() {
        for (kind in listOf("env", "store", "agent")) {
            val r = Rig()
            r.api.loseReplyOf = kind
            r.mind.ensure()
            assertEquals("no duplicate after a lost $kind reply", 1, if (kind == "env") r.api.environments.size else if (kind == "store") r.api.stores.size else r.api.agents.count { it.key == Protege.ODYSSEUS.agentName })
            assertEquals(7, r.api.agents.size)
        }
    }

    @Test fun anExistingAgentFromAnEarlierInstallIsAdoptedNotDuplicated() {
        val r = Rig()
        r.mind.ensure()
        val again = Rig().also { it.api.agents.putAll(r.api.agents); it.api.environments.putAll(r.api.environments); it.api.stores.putAll(r.api.stores); it.api.deployments.putAll(r.api.deployments) }
        again.mind.ensure()
        assertEquals(7, again.api.agents.size)
        assertEquals(0, again.api.creates)
    }

    @Test fun aChangedPromptUpdatesTheAgentInPlace() {
        val r = Rig()
        r.mind.ensure()
        r.kv.put("hash.agent.${MindPrompts.ATHENA_NAME}", "stale")
        r.mind.ensure()
        assertEquals(7, r.api.agents.size)
        assertEquals(2, r.api.versions[MindPrompts.ATHENA_NAME])
    }

    @Test fun metisUnavailableFallsBackToNoAdvisorAndSaysSo() {
        val r = Rig()
        r.api.unavailable += Metis.MODEL
        r.mind.ensure()
        assertNull(r.api.agents.getValue(MindPrompts.ATHENA_NAME).advisor)
        assertTrue(r.kv.get("metis.unavailable")!!.startsWith("Metis isn't available on this account"))
    }

    @Test fun theMonthlySessionIsCreatedOnceEvenWhenTheReplyIsLost() {
        val r = Rig()
        val athena = r.mind.ensure()
        r.api.loseReplyOf = "session"
        val id = r.mind.session(athena)
        assertNotNull(id)
        assertEquals(id, r.mind.session(athena))
        assertEquals(1, r.api.sessions.size)
    }
}

class BatchingTest {
    @Test fun routineNotesWaitForTheFifteenMinuteBatch() {
        val r = Rig()
        val b = r.mind.batcher
        b.sent(r.now)
        assertFalse(b.offer(MindNote(r.now, "Message from manager (medium).", key = "a")))
        r.now += 5 * 60_000L
        assertFalse(b.offer(MindNote(r.now, "Delivery update from Amazon.", key = "b")))
        assertEquals(10 * 60_000L, b.delay(r.now))
        r.now += 10 * 60_000L
        assertTrue(b.due(r.now))
        val (_, text) = b.compose(r.mind.sanitizer(), ZoneId.of("America/New_York"))!!
        assertTrue(text.contains("manager") && text.contains("Delivery"))
    }

    @Test fun urgentNotesGoAtOnce() {
        val r = Rig()
        r.mind.batcher.sent(r.now)
        assertTrue(r.mind.batcher.offer(MindNote(r.now + 1, "Security alert from Google (details withheld).", high = true, key = "sec")))
    }

    @Test fun theSameKeyReplacesInsteadOfPilingUp() {
        val r = Rig()
        repeat(5) { r.mind.batcher.offer(MindNote(r.now + it, "Near Walmart ($it items)", key = "near")) }
        assertEquals(1, r.mind.batcher.queued().size)
    }

    @Test fun aFlushSendsOneMessageAndNeverTwiceForTheSameBatch() {
        val r = Rig()
        r.mind.batcher.offer(MindNote(r.now, "Parnassus: \"need tomatoes\" went to Groceries.", high = true, key = "c1"))
        assertTrue(r.mind.flush())
        assertEquals(1, r.api.messagesSent)
        // A retry of the same batch (say the worker re-ran after the send) finds it in the history.
        val sid = r.mind.currentSession()!!
        r.mind.drive(sid, r.api.events.getValue(sid).filterIsInstance<MindEvent.UserMessage>().first().text, Regex("\\[(b[0-9a-f]+)]").find(r.api.events.getValue(sid).filterIsInstance<MindEvent.UserMessage>().first().text)!!.groupValues[1])
        assertEquals(1, r.api.messagesSent)
        assertTrue(r.mind.batcher.queued().isEmpty())
    }
}

class MindToolsTest {
    @Test fun aWriteToolOnlyParksAProposal() {
        val r = Rig()
        val out = r.tools.run("add_task", mapOf("title" to "Book the oil change", "list" to "next"), null, asCircle = false)
        assertFalse(out.isError)
        assertNotNull(out.proposal)
        assertEquals(ProposalStatus.PENDING, r.store.all().single().status)
        assertTrue("nothing executed", r.exec.ran.isEmpty())
    }

    @Test fun theCircleCannotProposeAndNobodyCanCallOtherTools() {
        val r = Rig()
        assertTrue(r.tools.run("add_task", mapOf("title" to "x"), "thread_1", asCircle = true).isError)
        assertTrue(r.store.all().isEmpty())
        assertTrue(r.tools.run("bash", mapOf("cmd" to "rm -rf /"), null, asCircle = false).isError)
        assertTrue(r.tools.run("execute_proposal", mapOf("id" to "1"), null, asCircle = false).isError)
        assertTrue(r.tools.run(MindTools.AGORA, mapOf("text" to "He is working from home today."), "thread_1", asCircle = true).isError)
    }

    @Test fun aCustomToolCallFromTheSessionMakesOneProposalEvenWhenRetried() {
        val r = Rig()
        r.api.script = { "add_task" to mapOf("title" to "Pick up the dry cleaning") }
        r.mind.batcher.offer(MindNote(r.now, "Parnassus: \"dry cleaning\" kept.", high = true))
        r.mind.flush()
        assertEquals(1, r.store.all().size)
        assertEquals(1, r.api.toolResults)
        // Reconnect: the history is replayed, the call already has a result, nothing runs again.
        r.mind.drive(r.mind.currentSession()!!)
        assertEquals(1, r.store.all().size)
        assertEquals(1, r.api.toolResults)
        assertTrue(r.exec.ran.isEmpty())
    }

    @Test fun anUnansweredCallFromADroppedStreamIsAnsweredOnReconnect() {
        val r = Rig()
        val athena = r.mind.ensure()
        val sid = r.mind.session(athena)!!
        r.api.events.getValue(sid) += MindEvent.CustomToolUse("sevt_x", 5, "read_people_labels", emptyMap())
        r.api.events.getValue(sid) += MindEvent.Idle("sevt_y", 6, "requires_action")
        r.mind.drive(sid)
        assertEquals(1, r.api.toolResults)
        assertTrue(r.api.lastToolResult!!.contains("family (mother)"))
    }

    @Test fun readToolsReturnLabelsAndNoPlaces() {
        val r = Rig()
        val people = r.tools.run("read_people_labels", emptyMap(), null, asCircle = true).text
        assertTrue(people.contains("family (mother)"))
        assertFalse(people.contains("Amma"))
    }
}

class BudgetTest {
    @Test fun atTheCapNothingIsSent() {
        val r = Rig()
        r.ledger.spent = r.ledger.capCents
        r.mind.batcher.offer(MindNote(r.now, "Message from manager (medium).", high = true))
        val e = runCatching { r.mind.flush() }.exceptionOrNull()
        assertTrue(e is SpendCapReached)
        assertEquals(0, r.api.messagesSent)
        assertEquals(0, r.api.sessions.size)
        assertEquals(1, r.mind.batcher.queued().size)
    }

    @Test fun theSessionBudgetIsWhatIsLeftOfTheCapAndSpendIsCharged() {
        val r = Rig()
        r.ledger.spent = 6_000
        r.mind.batcher.offer(MindNote(r.now, "Delivery update.", high = true))
        r.mind.flush()
        val s = r.api.sessions.values.single()
        assertEquals(4_000L, s.budgetCents)
        assertEquals(12L, r.ledger.mindCents)
        assertEquals(30L, r.ledger.seconds)
        // Charged once: a second look at the same usage adds nothing.
        r.mind.account(s.id)
        assertEquals(12L, r.ledger.mindCents)
    }

    @Test fun syncPausesTheSchedulesAtTheCapAndResumesAfter() {
        val r = Rig()
        r.mind.sync()
        r.ledger.spent = r.ledger.capCents
        r.mind.sync()
        assertEquals(5, r.api.paused.size)
        r.ledger.capCents = 15_000
        r.mind.sync()
        assertEquals(0, r.api.paused.size)
    }

    @Test fun metisAndAnnabethHaveTheirOwnShares() {
        assertTrue(TopTierCaps.allowed(0, 0).metis)
        assertFalse(TopTierCaps.allowed(1_500, 0).metis)
        assertTrue(TopTierCaps.allowed(1_500, 0).annabeth)
        assertFalse(TopTierCaps.allowed(1_000, 2_000).annabeth)
        assertFalse(TopTierCaps.allowed(1_000, 2_000).metis)
    }

    @Test fun anAnnabethThreadOverHerShareIsStoppedOnSight() {
        val r = Rig()
        val athena = r.mind.ensure()
        val sid = r.mind.session(athena)!!
        r.mind.addCircle(Protege.ANNABETH.agentName, 3_000)
        r.api.events.getValue(sid) += MindEvent.ThreadCreated("sevt_t", 7, "thread_9", Protege.ANNABETH.agentName)
        r.api.events.getValue(sid) += MindEvent.Idle("sevt_i", 8, "end_turn")
        r.mind.drive(sid)
        assertEquals(listOf<String?>("thread_9"), r.api.interrupts)
    }

    @Test fun threadCostsGoToEachMembersShare() {
        val r = Rig()
        val athena = r.mind.ensure()
        val sid = r.mind.session(athena)!!
        r.api.threads[sid] = listOf(ThreadInfo("t0", MindPrompts.ATHENA_NAME, "idle", 40, true), ThreadInfo("t1", Metis.THREAD, "terminated", 90, false), ThreadInfo("t2", Protege.PERSEUS.agentName, "idle", 25, false))
        r.mind.account(sid)
        assertEquals(90L, r.mind.circleCents(Metis.THREAD))
        assertEquals(25L, r.mind.circleCents(Protege.PERSEUS.agentName))
    }
}

class LongJobTest {
    @Test fun aLongJobIsTierOneAndItsResultComesBackAsACard() {
        val r = Rig()
        val job = r.mind.startJob("Winter tires", "Research winter tires for a Camry and come back tomorrow", asked = null)
        assertTrue(r.mind.batcher.due())
        assertTrue(r.store.all().isEmpty())
        r.mind.reportJob(mapOf("job_id" to job.id, "status" to "started", "protege" to "Perseus", "why" to "Research"), "thread_2")
        assertEquals("Perseus", r.mind.jobs().single().protege)
        r.mind.reportJob(mapOf("job_id" to job.id, "status" to "done", "result_title" to "Winter tires", "result_body" to "Three good picks under the cap."), "thread_2")
        assertEquals("done", r.mind.jobs().single().status)
        assertEquals("Winter tires", r.sink.cards.single().first)
    }

    @Test fun cancelStopsTheThreadAndTellsAthena() {
        val r = Rig()
        r.mind.ensure()
        r.mind.session(r.mind.ensure())
        val job = r.mind.startJob("Watch flight prices", "Watch BUF to EWR fares")
        r.mind.reportJob(mapOf("job_id" to job.id, "status" to "started"), "thread_5")
        r.mind.cancelJob(job.id)
        assertEquals("cancelled", r.mind.jobs().single().status)
        assertEquals(listOf<String?>("thread_5"), r.api.interrupts)
        assertTrue(r.mind.reportJob(mapOf("job_id" to job.id, "status" to "progress"), null).contains("cancelled"))
    }
}

class MemoryStoreTest {
    @Test fun thePhoneWritesItsFilesByPathOnlyWhenTheyChange() {
        val r = Rig()
        r.mind.ensure()
        r.mind.syncMemory()
        val paths = r.api.memories.keys
        assertTrue(paths.containsAll(listOf("/snapshot/today.md", "/decisions/recent.md", "/people/labels.md", "/routines/days.md", "/preferences/phone.md", Mind.PROFILE_PATH)))
        val before = r.api.memories.getValue("/snapshot/today.md")
        r.mind.syncMemory()
        assertEquals(before, r.api.memories.getValue("/snapshot/today.md"))
        r.context.snapshotText = "# Snapshot\n- Groceries: milk"
        r.mind.syncMemory()
        assertTrue(r.api.memories.getValue("/snapshot/today.md").content!!.contains("milk"))
    }

    @Test fun theOutboxBecomesCardsAndProposalsOnce() {
        val r = Rig()
        r.mind.ensure()
        val store = r.mind.ids.store!!
        r.api.createMemory(store, "/outbox/daily-plan-2026-10-20.json", """{"cards":[{"title":"Office day, rain at 5","body":"Take the umbrella.","hours":12}],"proposals":[{"title":"Leave by 8:05","steps":[{"tool":"add_reminder","input":{"at":"2026-10-20T08:05","text":"Leave for the office"}}]}],"note":"planned"}""")
        assertEquals(2, r.mind.pullOutbox())
        assertEquals("Office day, rain at 5", r.sink.cards.single().first)
        assertEquals(1, r.sink.proposals.size)
        assertEquals(0, r.mind.pullOutbox())
        assertTrue(r.api.memories.keys.none { it.startsWith("/outbox/") })
    }

    @Test fun forgetDeletesTheFile() {
        val r = Rig()
        r.mind.ensure()
        r.mind.syncMemory()
        val f = r.mind.remembered().first { it.path == "/people/labels.md" }
        r.mind.forget(f)
        assertTrue(r.mind.remembered().none { it.path == "/people/labels.md" })
        assertTrue(r.sink.logs.any { it.startsWith("The owner forgot") })
    }
}

class ScheduleTest {
    @Test fun fiveSchedulesInHisZoneOutsideTheDstWindow() {
        val specs = Schedules.specs(ZoneId.of("America/New_York"))
        assertEquals(listOf("0 6 * * *", "0 20 * * *", "0 9 * * 0", "0 3 * * *", "0 10 * * 0"), specs.map { it.cron })
        assertTrue(specs.all { it.zone == "America/New_York" })
        // US Eastern skips 2:00-3:00 in spring and repeats 1:00-2:00 in fall: 3:00 AM (the dream) always happens once.
        assertTrue(specs.all { it.cron.split(' ')[1].toInt() !in 1..2 })
        assertTrue(specs.all { it.message.contains("/outbox/") && it.message.contains("/snapshot/") })
    }

    @Test fun aZoneWithoutAnIanaIdFallsBackToBuffalo() {
        assertEquals("America/New_York", Schedules.zone(ZoneId.of("UTC")))
        assertEquals("Asia/Kolkata", Schedules.zone(ZoneId.of("Asia/Kolkata")))
    }

    @Test fun travellingUpdatesTheSchedulesInPlace() {
        val r = Rig()
        r.mind.ensure()
        val creates = r.api.creates
        r.context.zoneId = ZoneId.of("America/Chicago")
        r.mind.ensure()
        assertEquals(creates, r.api.creates)
        assertTrue(r.api.deploymentSpecs.values.all { it.zone == "America/Chicago" })
    }
}
