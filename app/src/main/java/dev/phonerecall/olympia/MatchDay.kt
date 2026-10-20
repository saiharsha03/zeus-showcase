package dev.phonerecall.olympia

/**
 * When Olympia is awake. Pure, so the windows are tested without a phone.
 *
 * - Lineups: one look an hour before kickoff (soccer and cricket publish them then).
 * - Live: from five minutes before the start, polling at the sport's pace, until the match is over.
 *   An expected length is the backstop for a feed that never says "full time".
 * - Otherwise asleep, with one alarm set for the next of those moments.
 */
object MatchDay {
    const val MIN = 60_000L
    const val HOUR = 60 * MIN

    /** Soccer and the NFL every 45 s, cricket about twice an over, F1 about once a lap. */
    fun cadence(s: Sport): Long = when (s) {
        Sport.SOCCER, Sport.NFL -> 45_000L
        Sport.CRICKET -> 120_000L
        Sport.F1 -> 60_000L
    }

    /** How long a match can run, extra time and delays included. */
    fun length(m: Match): Long = when (m.sport) {
        Sport.SOCCER -> 2 * HOUR + 45 * MIN
        Sport.NFL -> 4 * HOUR
        Sport.CRICKET -> 4 * HOUR + 30 * MIN
        Sport.F1 -> if (m.session == "Race") 2 * HOUR + 30 * MIN else 90 * MIN
    }

    const val LEAD_IN = 5 * MIN
    const val LINEUPS_BEFORE = 60 * MIN

    /** Followed live: from the lead-in to the end, or for as long as the feed says it is on. */
    fun live(m: Match, now: Long): Boolean = when (m.phase) {
        Phase.LIVE -> now < m.start + length(m) + HOUR
        Phase.PRE -> now >= m.start - LEAD_IN && now < m.start + length(m)
        Phase.DONE, Phase.OFF -> false
    }

    fun hasLineups(m: Match): Boolean = m.sport == Sport.SOCCER || m.sport == Sport.CRICKET

    /** In the hour before the lead-in, when a lineup look is due. */
    fun lineupsDue(m: Match, now: Long): Boolean =
        hasLineups(m) && m.phase == Phase.PRE && now >= m.start - LINEUPS_BEFORE && now < m.start - LEAD_IN

    /** The next moment to wake: a lineup look or a lead-in, whichever is first. Null when nothing is ahead. */
    fun nextWake(matches: List<Match>, now: Long): Long? = matches.filter { it.phase == Phase.PRE }.flatMap { m ->
        listOfNotNull(if (hasLineups(m)) m.start - LINEUPS_BEFORE else null, m.start - LEAD_IN)
    }.filter { it > now }.minOrNull()

    /** The followed matches to keep polling now. */
    fun liveNow(matches: List<Match>, now: Long): List<Match> = matches.filter { live(it, now) }

    /** When each live match is next due, given when it was last polled. */
    fun due(m: Match, lastPoll: Long?, now: Long): Long = lastPoll?.let { it + cadence(m.sport) } ?: now

    /** The match the notification and the widget carry: live first (the latest start), else the next one. */
    fun headline(matches: List<Match>, now: Long): Match? =
        liveNow(matches, now).maxByOrNull { it.start }
            ?: matches.filter { it.phase == Phase.PRE && it.start > now }.minByOrNull { it.start }
}

/**
 * Key moments that are new since the last look. The first look at a match (in progress when Olympia
 * woke, or after a restart) marks everything seen and notifies nothing, so there is never a backlog.
 */
object Moments {
    fun fresh(now: List<Moment>, seen: Set<String>?): List<Moment> =
        if (seen == null) emptyList() else now.filter { it.kind.key && it.id !in seen }

    /** What may sound, given his setting: key moments only with KEY; the result with KEY or RESULTS. */
    fun alerting(fresh: List<Moment>, level: Alerts): List<Moment> = when (level) {
        Alerts.KEY -> fresh
        Alerts.RESULTS -> fresh.filter { it.final }
        Alerts.QUIET, Alerts.OFF -> emptyList()
    }
}
