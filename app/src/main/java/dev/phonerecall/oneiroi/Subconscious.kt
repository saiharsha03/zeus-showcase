package dev.phonerecall.oneiroi

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.LongJob
import dev.phonerecall.mind.list
import dev.phonerecall.mind.long
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.putLong
import dev.phonerecall.mind.sha

/** A proposal from the subconscious sweep. Nothing happens until he taps it. */
data class Nudge(
    val id: String = "",
    /** dropped: something Athena said she'd do; backfill: something he said that never reached memory. */
    val kind: String = DROPPED,
    val text: String = "",
    val evidence: String = "",
    val at: Long = 0,
    val topic: String = "",
    /** open, accepted, dismissed. */
    val state: String = OPEN,
) {
    companion object {
        const val DROPPED = "dropped"
        const val BACKFILL = "backfill"
        const val OPEN = "open"
    }
}

/**
 * The subconscious (Iris's every-two-days sweep): recent transcripts and long jobs, looking for
 * dropped balls. Proposals only: it reads, writes its own list of nudges, and never touches the
 * facts, Fates or anything else. Plain rules, no model.
 */
class Subconscious(private val kv: Kv, private val facts: FactStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val type = object : TypeReference<List<Nudge>>() {}

    fun nudges(): List<Nudge> = kv.list(KEY, type)
    fun open(): List<Nudge> = nudges().filter { it.state == Nudge.OPEN }
    fun lastRun(): Long? = kv.long(LAST)
    fun due(): Boolean = clock() - (lastRun() ?: 0L) >= EVERY_MS

    fun sweep(said: List<Said>, jobs: List<LongJob>): List<Nudge> {
        val now = clock()
        val found = ArrayList<Nudge>()
        // Promises: Athena said she'd do something, and nothing in that chat came of it (no proposal or job after).
        for (s in said.filter { it.who == Said.ATHENA }) {
            val m = PROMISE.find(s.text) ?: continue
            val after = said.any { it.thread == s.thread && it.at > s.at && it.who == Said.ZEUS }
            if (!after) found += Nudge(id("p", s.text), Nudge.DROPPED, "Athena said she'd ${m.groupValues[2].trim().trimEnd('.', ',')}. Nothing came of it.", s.text.take(160), s.at)
        }
        // Long jobs that went quiet for two days.
        for (j in jobs.filter { it.status in setOf("queued", "started", "progress") && now - it.updatedAt >= EVERY_MS }) {
            found += Nudge(id("j", j.id), Nudge.DROPPED, "The long job \"${j.title}\" has gone quiet.", "Last update ${(now - j.updatedAt) / 86_400_000L} days ago", j.updatedAt)
        }
        // Things he told her that no live fact holds.
        val live = facts.live().map { words(it.text) }
        for (s in said.filter { it.who == Said.OWNER }) {
            val m = FACTISH.find(s.text) ?: continue
            val sentence = s.text.substring(m.range.first).substringBefore('\n').split(Regex("(?<=[.!?])\\s")).first().trim().take(160)
            val w = words(sentence)
            if (w.size < 2 || live.any { f -> w.count { it in f } >= (w.size + 1) / 2 }) continue
            found += Nudge(id("f", sentence), Nudge.BACKFILL, third(sentence), s.text.take(160), s.at, topic = "general")
        }
        val known = nudges()
        val fresh = found.distinctBy { it.id }.filter { n -> known.none { it.id == n.id } }
        kv.putList(KEY, (known + fresh).takeLast(MAX))
        kv.putLong(LAST, now)
        return fresh
    }

    /** His tap. Returns the nudge as it was, so the caller does what he chose (add the fact, propose a task). */
    fun decide(id: String, accept: Boolean): Nudge? {
        val all = nudges()
        val n = all.firstOrNull { it.id == id && it.state == Nudge.OPEN } ?: return null
        kv.putList(KEY, all.map { if (it.id == id) it.copy(state = if (accept) "accepted" else "dismissed") else it })
        return n
    }

    private fun id(p: String, s: String) = p + sha(Facts.norm(s)).take(10)

    private fun words(s: String): Set<String> = Facts.norm(s).split(' ').filter { it.length > 2 && it !in STOP }.toSet()

    /** "my car is ..." → "His car is ...", so it reads as a fact about him. */
    private fun third(s: String): String = s.replace(Regex("\\bI'm\\b"), "He's").replace(Regex("\\bI am\\b"), "He is").replace(Regex("\\bI\\b"), "He")
        .replace(Regex("\\b[Mm]y\\b"), "his").replace(Regex("\\bme\\b"), "him").replaceFirstChar { it.uppercase() }

    companion object {
        const val KEY = "oneiroi.nudges"
        private const val LAST = "oneiroi.sub.last"
        const val EVERY_MS = 2 * 86_400_000L
        const val MAX = 80
        private val PROMISE = Regex("(?i)\\b(I'll|I will|let me|I can)\\s+((remind you|check|look into|get back to you|follow up|let you know|keep an eye on|send|draft|find out|set up)[^.!?\\n]{0,80})")
        private val FACTISH = Regex("(?i)\\b(my [\\p{L} ]{2,24} (is|are|was) |i moved|i live|i('m| am) moving|i prefer|i don't like|i do not like|i hate|i love|remember that|from now on|we're (now )?(using|going))")
        private val STOP = setOf("the", "and", "his", "her", "that", "this", "with", "for", "from", "now", "remember", "are", "was", "has", "have", "you", "she", "him")
    }
}
