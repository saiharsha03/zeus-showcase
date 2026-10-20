package dev.phonerecall.brain

/*
 * The proposal fatigue meter. Per kind, what he did with Athena's proposals: confirmed, rejected,
 * edited, ignored (expired), plus the sparing "Was this useful?". Two rules come out of it:
 * - a steady yes (15 confirms, 0 rejects in 30 days) on an auto-eligible tier 2 kind earns ONE
 *   meta-proposal "Add <kind> to auto-approve?", at most one a week, never for a forbidden kind;
 * - a steady no (3 rejects in 14 days) quiets that kind for 30 days. Quiet means Athena stops
 *   offering it on her own; he can still ask for one in Delphi.
 */

enum class Signal { CONFIRM, REJECT, EDIT, IGNORE, USEFUL, NOT_USEFUL, OFFERED }

data class KindEvent(val kind: ProposalKind, val signal: Signal, val at: Long)

data class KindStats(val confirms: Int = 0, val rejects: Int = 0, val edits: Int = 0, val ignores: Int = 0, val useful: Int = 0, val notUseful: Int = 0) {
    val total: Int get() = confirms + rejects + edits + ignores
}

/** Where the meter's events live (brain.db). */
interface FatesMeter {
    fun record(e: KindEvent)
    fun events(since: Long): List<KindEvent>
}

/** For tests and previews. */
class MemoryMeter : FatesMeter {
    val all = ArrayList<KindEvent>()
    override fun record(e: KindEvent) { synchronized(all) { all += e } }
    override fun events(since: Long): List<KindEvent> = synchronized(all) { all.filter { it.at >= since } }
}

object Fatigue {
    const val DAY = 86_400_000L
    const val AUTO_CONFIRMS = 15
    const val AUTO_WINDOW = 30 * DAY
    const val QUIET_REJECTS = 3
    const val QUIET_WINDOW = 14 * DAY
    const val QUIET_FOR = 30 * DAY
    const val OFFER_EVERY = 7 * DAY
    /** A kind he was offered (and didn't take) is not offered again for this long. */
    const val REOFFER_AFTER = 30 * DAY
    /** How far back anything here looks. */
    const val HORIZON = QUIET_WINDOW + QUIET_FOR

    /** The meta-proposal itself is never measured. */
    fun tracked(kind: ProposalKind) = kind != ProposalKind.ADD_AUTO_APPROVE

    fun stats(events: List<KindEvent>, kind: ProposalKind, now: Long, window: Long = AUTO_WINDOW): KindStats {
        val e = events.filter { it.kind == kind && it.at in (now - window)..now }
        fun n(s: Signal) = e.count { it.signal == s }
        return KindStats(n(Signal.CONFIRM), n(Signal.REJECT), n(Signal.EDIT), n(Signal.IGNORE), n(Signal.USEFUL), n(Signal.NOT_USEFUL))
    }

    /** A "no": a rejection, or "not useful" on the ask. */
    private fun negative(s: Signal) = s == Signal.REJECT || s == Signal.NOT_USEFUL

    /** When this kind's quiet ends, or null when it isn't quiet now. */
    fun quietUntil(events: List<KindEvent>, kind: ProposalKind, now: Long): Long? {
        val no = events.filter { it.kind == kind && negative(it.signal) && it.at <= now }.map { it.at }.sorted()
        var until: Long? = null
        for (i in QUIET_REJECTS - 1 until no.size) {
            if (no[i] - no[i - QUIET_REJECTS + 1] <= QUIET_WINDOW) until = maxOf(until ?: 0L, no[i] + QUIET_FOR)
        }
        return until?.takeIf { it > now }
    }

    /**
     * The one kind to offer for auto-approve now, or null. Only auto-eligible tier 2 kinds not on the list
     * already; 15 or more confirms and not a single "no" in 30 days; no offer of any kind in the last week;
     * not this kind in the last 30 days. The steadiest kind wins.
     */
    fun offer(events: List<KindEvent>, autoList: Set<ProposalKind>, now: Long): ProposalKind? {
        val offers = events.filter { it.signal == Signal.OFFERED }
        if (offers.any { now - it.at < OFFER_EVERY }) return null
        return ProposalKind.autoCandidates
            .filter { it !in autoList && offers.none { o -> o.kind == it && now - o.at < REOFFER_AFTER } }
            .map { it to stats(events, it, now) }
            .filter { (_, s) -> s.confirms >= AUTO_CONFIRMS && s.rejects == 0 && s.notUseful == 0 }
            .maxByOrNull { (_, s) -> s.confirms }?.first
    }
}
