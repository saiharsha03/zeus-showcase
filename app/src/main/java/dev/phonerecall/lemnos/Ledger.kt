package dev.phonerecall.lemnos

import dev.phonerecall.mind.LongJob
import dev.phonerecall.ui.Tone

/**
 * The agent ledger: for Iris, Atlas and Athena's own long jobs, what was asked in the last week, what was
 * answered, and what is overdue and for how long. Built from the records Zeus already has (Lemnos's jobs
 * and chat rooms, the mind's job list), so silence shows as a status instead of nothing.
 */
data class Ledger(val asked: Int = 0, val answered: Int = 0, val overdue: Int = 0, val overdueFor: Long? = null) {
    val open: Int get() = asked - answered
}

object AgentLedger {
    const val WINDOW = 7 * 86_400_000L
    /** A worker job with no answer after this long is overdue. */
    const val WORKER_DUE = 4 * 3_600_000L
    /** A message to a worker with no reply after this long is overdue. */
    const val CHAT_DUE = 2 * 3_600_000L
    /** Athena's long jobs run for hours by design; a day without an answer is overdue. */
    const val ATHENA_DUE = 24 * 3_600_000L

    private val ANSWERED = setOf("done", "failed", "cancelled")

    /** A worker: its jobs from Lemnos plus his messages in its room (when that room was loaded). */
    fun worker(agent: String, jobs: List<WorkerJob>, chat: List<ChatMsg>, now: Long): Ledger {
        var asked = 0; var answered = 0; var overdue = 0; var oldest: Long? = null
        fun late(since: Long, due: Long) { if (now - since > due) { overdue++; oldest = maxOf(oldest ?: 0L, now - since) } }
        for (j in jobs) {
            val at = (j.created * 1000).toLong()
            if (j.to != agent || now - at > WINDOW) continue
            asked++
            if (j.status in ANSWERED || j.result != null) answered++ else late(at, WORKER_DUE)
        }
        // A message from him counts as answered once the agent wrote anything after it.
        val sorted = chat.filter { it.room == agent }.sortedBy { it.ts }
        sorted.forEachIndexed { i, m ->
            val at = (m.ts * 1000).toLong()
            if (m.sender != "owner" || m.kind == "job" || now - at > WINDOW) return@forEachIndexed
            asked++
            if (sorted.drop(i + 1).any { it.sender == agent }) answered++ else late(at, CHAT_DUE)
        }
        return Ledger(asked, answered, overdue, oldest)
    }

    fun athena(jobs: List<LongJob>, now: Long): Ledger {
        val recent = jobs.filter { now - it.startedAt <= WINDOW || it.status !in ANSWERED }
        val late = recent.filter { it.status !in ANSWERED && now - it.startedAt > ATHENA_DUE }
        return Ledger(recent.size, recent.count { it.status in ANSWERED }, late.size, late.maxOfOrNull { now - it.startedAt })
    }

    /** "5 h", "2 d". */
    fun span(ms: Long): String = when {
        ms < 3_600_000L -> "${(ms / 60_000L).coerceAtLeast(1)} min"
        ms < 2 * 86_400_000L -> "${ms / 3_600_000L} h"
        else -> "${ms / 86_400_000L} d"
    }

    /** The status word: overdue is loud, open is active, all answered is fine, nothing asked is quiet. */
    fun status(l: Ledger): Pair<String, Tone> = when {
        l.overdue > 0 -> "${l.overdue} overdue, ${span(l.overdueFor ?: 0)}" to Tone.ALERT
        l.open > 0 -> "${l.open} open" to Tone.ACTIVE
        l.asked > 0 -> "All answered" to Tone.OK
        else -> "Nothing asked" to Tone.QUIET
    }

    /** "Asked 6, answered 4 this week". */
    fun line(l: Ledger): String = if (l.asked == 0) "Nothing asked this week" else "Asked ${l.asked}, answered ${l.answered} this week"
}
