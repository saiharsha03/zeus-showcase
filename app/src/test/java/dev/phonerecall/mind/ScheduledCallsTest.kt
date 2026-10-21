package dev.phonerecall.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scheduled runs that wait on a phone tool: answered on the next wake, bounded, stale ones refused. */
class ScheduledCallsTest {
    private val traces = ArrayList<String>()
    private val r = Rig()
    private val mind = Mind(r.api, r.kv, r.tools, r.sink, r.context, r.ledger, clock = { r.now }, driveMs = 10_000L, trace = { e, d -> traces += "$e: $d" })

    /** A daily-plan run whose session is idle on these tool calls. */
    private fun waitingRun(calls: List<Pair<String, Long>>, input: Map<String, Any?> = emptyMap()): String {
        mind.ensure()
        val d = mind.ids.deployment("Zeus · daily-plan")!!
        val sid = "sesn_run"
        r.api.sessions[sid] = SessionInfo(sid, "idle")
        r.api.events[sid] = calls.mapIndexed { i, (tool, at) -> MindEvent.CustomToolUse("sevt_c$i", at, tool, input) }.toMutableList<MindEvent>()
            .also { it += MindEvent.Idle("sevt_idle", r.now - 60_000, "requires_action") }
        r.api.runList += RunInfo("run_1", d, sid, r.now - 3_600_000L, null)
        return sid
    }

    @Test fun atMostTwentyAWakeAndTheRestNextWake() {
        waitingRun(List(25) { "read_cards" to r.now - 600_000L })
        assertEquals(20, mind.answerScheduled())
        assertEquals(20, r.api.toolResults)
        assertEquals(20, traces.size)
        assertTrue(traces.all { it == "answered scheduled tool call: read_cards" })
        // Still waiting: looked at again on the next wake, which answers the other five and settles it.
        assertFalse("run.sesn_run" in r.kv.strings("runs.done"))
        assertEquals(5, mind.answerScheduled())
        assertEquals(25, r.api.toolResults)
        assertTrue("run.sesn_run" in r.kv.strings("runs.done"))
        assertEquals(0, mind.answerScheduled())
    }

    /** A heartbeat or the dream may ask read_zeus; the phone answers it on its next wake like any read. */
    @Test fun aScheduledReadZeusIsAnswered() {
        waitingRun(listOf(ZeusRead.NAME to r.now - 600_000L), mapOf("area" to "index"))
        assertEquals(1, mind.answerScheduled())
        assertTrue(r.api.lastToolResult.orEmpty(), r.api.lastToolResult.orEmpty().contains("\"area\":\"places\""))
        assertEquals(listOf("answered scheduled tool call: read_zeus"), traces)
        assertTrue(r.api.agents.getValue(MindPrompts.ATHENA_NAME).tools.any { it.name == ZeusRead.NAME })
    }

    @Test fun aCallOlderThanTwelveHoursGetsTheOfflineError() {
        waitingRun(listOf("add_task" to r.now - 13 * 3_600_000L))
        assertEquals(1, mind.answerScheduled())
        assertEquals(Mind.OFFLINE, r.api.lastToolResult)
        // Not run: no proposal was parked.
        assertTrue(r.store.all().isEmpty())
        assertEquals(listOf("answered scheduled tool call: add_task"), traces)
    }

    @Test fun nothingIsAnsweredAtTheCap() {
        waitingRun(listOf("read_cards" to r.now - 600_000L))
        r.ledger.spent = r.ledger.capCents
        assertEquals(0, mind.answerScheduled())
        assertEquals(0, r.api.toolResults)
    }

    @Test fun scheduledRunsMayCallPhoneToolsAndTheDreamIsADeployment() {
        val spec = Schedules.specs(java.time.ZoneId.of("America/New_York"))
        assertTrue(spec.all { "You may call phone tools; the phone answers when it next wakes (up to 30 min). Ask for what you need, don't guess." in it.message })
        assertTrue(spec.none { "Do not call phone tools" in it.message })
        val dream = spec.single { it.name == Schedules.DREAM }
        assertEquals("0 3 * * *", dream.cron)
        assertTrue("read_facts" in dream.message && "propose_fact_changes" in dream.message)
        mind.ensure()
        assertEquals(10L, r.api.deploymentBudgets[mind.ids.deployment(Schedules.DREAM)])
        // Athena gets both fact tools; her circle gets neither.
        assertTrue(r.api.agents.getValue(MindPrompts.ATHENA_NAME).tools.map { it.name }.containsAll(MindTools.FACTS))
        assertTrue(r.api.agents.getValue(Protege.ANNABETH.agentName).tools.none { it.name in MindTools.FACTS })
        assertTrue(r.tools.run("propose_fact_changes", emptyMap(), "thread_1", asCircle = true).isError)
    }
}
