package dev.phonerecall.lemnos

import dev.phonerecall.mind.LongJob
import dev.phonerecall.ui.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerTest {
    private val now = 1_792_000_000_000L
    private val h = 3_600_000L
    private fun s(ms: Long) = ms / 1000.0

    private fun job(id: String, to: String, ageH: Long, status: String, result: String? = null) =
        WorkerJob(id, to, "ask", "Job $id", status, true, s(now - ageH * h), s(now - ageH * h), result)

    private fun msg(id: Long, room: String, sender: String, ageMin: Long, kind: String = "chat") =
        ChatMsg(id, room, sender, "m$id", s(now - ageMin * 60_000L), kind, null, null)

    @Test fun jobsAreAskedAnsweredOrOverdue() {
        val jobs = listOf(
            job("1", "iris", 30, "done"), job("2", "iris", 20, "failed"), job("3", "iris", 6, "delivered"),
            job("4", "iris", 1, "working"), job("5", "atlas", 9, "sent"), job("6", "iris", 24 * 9, "sent"),
        )
        val l = AgentLedger.worker("iris", jobs, emptyList(), now)
        assertEquals(Ledger(asked = 4, answered = 2, overdue = 1, overdueFor = 6 * h), l)   // job 6 is older than the week
        assertEquals("1 overdue, 6 h" to Tone.ALERT, AgentLedger.status(l))
        assertEquals("Asked 4, answered 2 this week", AgentLedger.line(l))
    }

    @Test fun aResultCountsAsAnAnswer() {
        val l = AgentLedger.worker("atlas", listOf(job("1", "atlas", 8, "delivered", result = "Disk is fine")), emptyList(), now)
        assertEquals(Ledger(1, 1, 0, null), l)
        assertEquals("All answered" to Tone.OK, AgentLedger.status(l))
    }

    @Test fun messagesWithoutAReplyBecomeOverdue() {
        val chat = listOf(
            msg(1, "iris", "owner", 300), msg(2, "iris", "iris", 290),      // answered
            msg(3, "iris", "owner", 180),                                    // 3 h, no reply: overdue
            msg(4, "iris", "owner", 30),                                     // 30 min: open, not overdue yet
            msg(5, "iris", "owner", 20, kind = "job"),                       // a job line is counted as a job, not here
        )
        val l = AgentLedger.worker("iris", emptyList(), chat, now)
        assertEquals(Ledger(asked = 3, answered = 1, overdue = 1, overdueFor = 3 * h), l)
    }

    @Test fun athenasLongJobsGetADay() {
        val jobs = listOf(
            LongJob("a", "Winter tires", startedAt = now - 30 * h, status = "progress"),
            LongJob("b", "Refund", startedAt = now - 5 * h, status = "started"),
            LongJob("c", "Lease", startedAt = now - 50 * h, status = "done"),
            LongJob("d", "Old", startedAt = now - 10 * 24 * h, status = "done"),
        )
        val l = AgentLedger.athena(jobs, now)
        assertEquals(Ledger(asked = 3, answered = 1, overdue = 1, overdueFor = 30 * h), l)
        assertEquals("1 overdue, 30 h", AgentLedger.status(l).first)
    }

    @Test fun nothingAskedIsQuiet() {
        val l = AgentLedger.worker("atlas", emptyList(), emptyList(), now)
        assertEquals("Nothing asked" to Tone.QUIET, AgentLedger.status(l))
        assertNull(l.overdueFor)
        assertEquals("2 d", AgentLedger.span(50 * h))
        assertEquals("5 min", AgentLedger.span(5 * 60_000L))
    }

    @Test fun theCardLeadsWithWhoOwesAnAnswer() {
        val agents = listOf(AgentStatus("iris", "Iris", "claude", "claude-auto", true, "connected", false, null), AgentStatus("atlas", "Atlas", "codex", "codex", true, "connected", false, null))
        val st = LemnosState(pairing = DeviceKeys.State.PAIRED, agents = agents, jobs = listOf(job("1", "iris", 6, "delivered"), job("2", "atlas", 2, "done")))
        val c = LemnosCards.agents(st, emptyList(), now)!!
        assertEquals("Iris owes you an answer", c.title)
        val iris = c.facts.last { it.label == "Iris" }
        assertEquals("0 of 1 answered", iris.value)
        assertEquals(Tone.ALERT, iris.tone)
        assertEquals("Both workers idle", LemnosCards.agents(st.copy(jobs = emptyList()), emptyList(), now)!!.title)
    }
}
