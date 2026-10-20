package dev.phonerecall.olympia

import androidx.compose.runtime.Immutable

/*
 * Olympia: live sports. His teams' fixtures, results, lineups and live scores,
 * from ESPN's public JSON (soccer, NFL, cricket, F1 timing) and Jolpica (the F1 calendar). No keys.
 * Nothing runs outside his matches: MatchDay decides when to wake, LiveService polls only while one is on.
 */

enum class Sport(val word: String) { SOCCER("Football"), NFL("NFL"), CRICKET("Cricket"), F1("Formula 1") }

/**
 * A team (or, for F1, the series) he can follow. `espnId` is the id in ESPN's paths; `group` is the
 * heading it sits under in the settings list.
 */
@Immutable
data class Team(val key: String, val sport: Sport, val espnId: String, val name: String, val short: String, val group: String)

object Teams {
    const val F1 = "f1"

    /** Everything he can follow. The default set is his: Barça, Inter Miami, the big six, the Bills, PBKS, F1. */
    val all: List<Team> = listOf(
        Team("bar", Sport.SOCCER, "83", "Barcelona", "BAR", "Football"),
        Team("mia", Sport.SOCCER, "20232", "Inter Miami", "MIA", "Football"),
        Team("ars", Sport.SOCCER, "359", "Arsenal", "ARS", "Premier League"),
        Team("che", Sport.SOCCER, "363", "Chelsea", "CHE", "Premier League"),
        Team("liv", Sport.SOCCER, "364", "Liverpool", "LIV", "Premier League"),
        Team("mci", Sport.SOCCER, "382", "Manchester City", "MNC", "Premier League"),
        Team("mun", Sport.SOCCER, "360", "Manchester United", "MAN", "Premier League"),
        Team("tot", Sport.SOCCER, "367", "Tottenham", "TOT", "Premier League"),
        Team("new", Sport.SOCCER, "361", "Newcastle", "NEW", "Premier League"),
        Team("avl", Sport.SOCCER, "362", "Aston Villa", "AVL", "Premier League"),
        Team("rma", Sport.SOCCER, "86", "Real Madrid", "RMA", "Football"),
        Team("buf", Sport.NFL, "2", "Buffalo Bills", "BUF", "NFL"),
        Team("pbks", Sport.CRICKET, "335973", "Punjab Kings", "PBKS", "IPL"),
        Team(F1, Sport.F1, "f1", "Formula 1", "F1", "Formula 1"),
    )

    val defaults: Set<String> = setOf("bar", "mia", "ars", "che", "liv", "mci", "mun", "tot", "buf", "pbks", F1)

    fun byKey(key: String): Team? = all.firstOrNull { it.key == key }
    fun byEspn(sport: Sport, id: String): Team? = all.firstOrNull { it.sport == sport && it.espnId == id }

    /** The IPL in ESPN's cricket paths. */
    const val IPL = "8048"
}

/** Competitions he can switch off as a whole. A match in an unknown competition follows its team. */
enum class Competition(val label: String, val slugs: Set<String>) {
    LALIGA("LaLiga", setOf("esp.1")),
    PREMIER("Premier League", setOf("eng.1")),
    CHAMPIONS("Champions League", setOf("uefa.champions")),
    MLS("MLS", setOf("usa.1")),
    CUPS("Domestic cups", setOf("esp.copa_del_rey", "esp.super_cup", "eng.fa", "eng.league_cup", "usa.open", "concacaf.leagues.cup", "concacaf.champions")),
    FRIENDLIES("Friendlies", setOf("club.friendly", "esp.joan_gamper")),
    NFL("NFL", setOf("nfl")),
    IPL("IPL", setOf(Teams.IPL)),
    F1("Formula 1", setOf("f1"));

    companion object {
        fun of(slug: String): Competition? = entries.firstOrNull { slug in it.slugs }
        val defaults: Set<Competition> = entries.toSet() - FRIENDLIES
    }
}

/** How much Olympia may say. */
enum class Alerts(val label: String, val about: String) {
    KEY("Key moments", "Live score in place, a sound only for goals, red cards, touchdowns, wickets and F1 lead changes"),
    QUIET("Silent live", "Live score in place, never a sound"),
    RESULTS("Results only", "One notification at full time"),
    OFF("Off", "Nothing; scores stay in Olympia"),
}

enum class Phase { PRE, LIVE, DONE, OFF }

@Immutable
data class Side(val id: String, val name: String, val abbr: String, val score: String?, val home: Boolean)

/**
 * One fixture or F1 session. `league` is ESPN's slug ("esp.1", "nfl", "8048", "f1"). `teams` are the
 * followed teams in it. `clock` is the live clock in its sport's words ("67'", "Q3 4:12", "16.2 ov", "Lap 34").
 */
@Immutable
data class Match(
    val id: String,
    val sport: Sport,
    val league: String,
    val leagueName: String,
    val start: Long,
    val phase: Phase,
    val title: String,
    val home: Side? = null,
    val away: Side? = null,
    val teams: Set<String> = emptySet(),
    val clock: String = "",
    val detail: String = "",
    /** F1: "Race", "Qualifying", "Sprint", "Sprint Qualifying", "Practice 1". */
    val session: String? = null,
    /** Cricket's result line ("PBKS won by 3 wkts") or F1's winner. */
    val result: String? = null,
    /** F1: ESPN's race weekend id and the Jolpica circuit, for live timing and the lap count. */
    val espnEvent: String? = null,
    val circuit: String? = null,
) {
    val isPractice: Boolean get() = session?.startsWith("Practice") == true
    val score: String? get() = if (home?.score != null && away?.score != null) "${home.score}–${away.score}" else null

    /** "BAR 2–1 SEV", home side first as the leagues write it (NFL: away first, "BUF 24–17 KC"). */
    fun scoreLine(): String {
        if (sport == Sport.F1) return listOfNotNull(session, clock.ifEmpty { null }).joinToString(" · ")
        val (a, b) = ordered()
        if (a == null || b == null) return title
        if (sport == Sport.CRICKET) return listOf(a, b).joinToString(" · ") { s -> s.abbr + (s.score?.let { " $it" } ?: "") }
        return if (a.score != null && b.score != null) "${a.abbr} ${a.score}–${b.score} ${b.abbr}" else "${a.abbr} v ${b.abbr}"
    }

    /** Soccer and cricket put the home side first; American football puts the away side first. */
    fun ordered(): Pair<Side?, Side?> = if (sport == Sport.NFL) away to home else home to away
}

enum class MomentKind(val key: Boolean) {
    GOAL(true), RED_CARD(true), PENALTY_MISS(true), TOUCHDOWN(true), FIELD_GOAL(false), SAFETY(true),
    WICKET(true), FIFTY(true), HUNDRED(true), TEAM_MILESTONE(false), INNINGS(false),
    LEAD(true), FINISH(true), POLE(true), HALF_TIME(false), FULL_TIME(true),
}

/** One thing that happened. `id` is stable across polls, so a moment notifies once. */
@Immutable
data class Moment(val id: String, val kind: MomentKind, val text: String, val clock: String = "") {
    /** The result: full time, the chequered flag, pole. */
    val final: Boolean get() = kind == MomentKind.FULL_TIME || kind == MomentKind.FINISH || kind == MomentKind.POLE
}

@Immutable
data class Lineup(val team: String, val formation: String?, val starters: List<String>, val bench: List<String>)

/**
 * A match while it is being followed live. `situation` is the line under the score ("2nd & 7 at KC 35",
 * "need 38 off 22"); `positions` is F1's running order; `progress` is laps or balls done of the total.
 */
@Immutable
data class Live(
    val match: Match,
    val moments: List<Moment> = emptyList(),
    val lineups: List<Lineup> = emptyList(),
    val situation: String? = null,
    val positions: List<String> = emptyList(),
    val progress: Pair<Int, Int>? = null,
    val updatedAt: Long = 0L,
) {
    val lastKey: Moment? get() = moments.lastOrNull { it.kind.key }
}

/** A followed team's page: where it stands, what's next, what just happened. */
@Immutable
data class TeamInfo(
    val key: String,
    val standing: String? = null,
    val record: String? = null,
    val upcoming: List<Match> = emptyList(),
    val recent: List<Match> = emptyList(),
) {
    val next: Match? get() = upcoming.firstOrNull()
    val last: Match? get() = recent.firstOrNull()
}
