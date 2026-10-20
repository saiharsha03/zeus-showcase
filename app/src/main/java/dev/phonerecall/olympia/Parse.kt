package dev.phonerecall.olympia

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/*
 * Parsers for the exact JSON Olympia fetches (samples in app/src/test/resources/olympia). Pure: a
 * string in, model out. Anything missing or odd is skipped, never thrown, so one bad event can't
 * blank the screen.
 */

private fun JSONObject.obj(name: String): JSONObject? = optJSONObject(name)
private fun JSONObject.str(name: String): String? = if (has(name) && !isNull(name)) optString(name).takeIf { it.isNotEmpty() } else null
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** ESPN writes "2026-10-25T20:00Z"; Jolpica writes a date and "20:00:00Z". */
internal fun epoch(iso: String?): Long? = iso?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

internal fun epoch(date: String?, time: String?): Long? {
    val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val t = time?.removeSuffix("Z")?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.NOON
    return d.atTime(t).toInstant(ZoneOffset.UTC).toEpochMilli()
}

object Espn {

    // ---- shared event shape (schedules and scoreboards) ----

    private fun phase(status: JSONObject?): Phase {
        val type = status?.obj("type")
        val name = type?.str("name").orEmpty()
        return when {
            name.contains("POSTPONED") || name.contains("CANCELED") || name.contains("ABANDONED") || name.contains("SUSPENDED") -> Phase.OFF
            type?.str("state") == "in" -> Phase.LIVE
            type?.str("state") == "post" -> Phase.DONE
            else -> Phase.PRE
        }
    }

    /** A score is a string on scoreboards and an object on team schedules. */
    private fun score(c: JSONObject): String? {
        c.optJSONObject("score")?.let { return it.str("displayValue") }
        return c.str("score")
    }

    private fun sides(comp: JSONObject): Pair<Side?, Side?> {
        val list = comp.optJSONArray("competitors").objects().map { c ->
            val t = c.obj("team")
            Side(
                id = t?.str("id") ?: c.str("id").orEmpty(),
                name = t?.str("shortDisplayName") ?: t?.str("displayName").orEmpty(),
                abbr = t?.str("abbreviation").orEmpty(),
                score = score(c),
                home = c.str("homeAway") == "home",
            )
        }
        return list.firstOrNull { it.home } to list.firstOrNull { !it.home }
    }

    /** "67'", "HT", "Q3 4:12", "OT 2:10", "16.2 ov" (from the batting side's line). */
    private fun clock(sport: Sport, status: JSONObject?, phase: Phase): String {
        if (phase != Phase.LIVE) return ""
        val type = status?.obj("type")
        if (type?.str("name")?.contains("HALFTIME") == true) return "HT"
        val shown = status?.str("displayClock").orEmpty()
        return when (sport) {
            Sport.SOCCER -> shown
            Sport.NFL -> {
                val p = status?.optInt("period") ?: 0
                (if (p >= 5) "OT" else "Q$p") + if (shown.isNotEmpty()) " $shown" else ""
            }
            else -> type?.str("shortDetail").orEmpty()
        }
    }

    private fun event(e: JSONObject, sport: Sport, league: String?, leagueName: String?): Match? {
        val id = e.str("id") ?: return null
        val comp = e.optJSONArray("competitions").objects().firstOrNull() ?: return null
        val status = comp.obj("status") ?: e.obj("status")
        val ph = phase(status)
        val (home, away) = sides(comp)
        val lg = e.obj("league")
        val slug = lg?.str("slug") ?: league ?: return null
        val teams = listOfNotNull(home, away).mapNotNull { s -> Teams.byEspn(sport, s.id)?.key }.toSet()
        val title = when (sport) {
            Sport.SOCCER -> if (home != null && away != null) "${home.name} v ${away.name}" else e.str("name").orEmpty()
            else -> e.str("name").orEmpty()
        }
        var m = Match(
            id = id, sport = sport, league = slug,
            leagueName = lg?.str("abbreviation") ?: lg?.str("shortName") ?: leagueName ?: slug,
            start = epoch(comp.str("date") ?: e.str("date")) ?: return null,
            phase = ph, title = title, home = home, away = away, teams = teams,
            clock = clock(sport, status, ph),
            detail = status?.obj("type")?.str("shortDetail").orEmpty(),
            result = status?.str("summary"),
        )
        if (sport == Sport.CRICKET) m = m.copy(clock = if (ph == Phase.LIVE) cricketClock(comp) else "")
        return m
    }

    /** A team's schedule (soccer and NFL): its fixtures or results, plus "1st in Spanish LALIGA". */
    fun schedule(json: String, sport: Sport): Pair<TeamInfo?, List<Match>> {
        val d = JSONObject(json)
        val t = d.obj("team")
        val league = if (sport == Sport.NFL) "nfl" else null
        val matches = d.optJSONArray("events").objects().mapNotNull { event(it, sport, league, if (sport == Sport.NFL) "NFL" else null) }
        val key = t?.str("id")?.let { Teams.byEspn(sport, it)?.key }
        val info = key?.let { TeamInfo(it, standing = t.str("standingSummary"), record = t.str("recordSummary")) }
        return info to matches.sortedBy { it.start }
    }

    /** A league scoreboard (a soccer league's day, the NFL's day, the IPL's season). */
    fun scoreboard(json: String, sport: Sport): List<Match> {
        val d = JSONObject(json)
        val lg = d.optJSONArray("leagues").objects().firstOrNull()
        val slug = lg?.str("slug") ?: if (sport == Sport.NFL) "nfl" else null
        val name = lg?.str("abbreviation") ?: lg?.str("shortName")
        return d.optJSONArray("events").objects().mapNotNull { event(it, sport, slug, name) }.sortedBy { it.start }
    }

    private fun scoreboardEvent(json: String, eventId: String): JSONObject? =
        JSONObject(json).optJSONArray("events").objects().firstOrNull { it.str("id") == eventId }

    // ---- soccer live ----

    /**
     * One soccer match from its league's scoreboard: score, minute, and the moments ESPN lists in
     * `details` (goals, red cards, missed penalties). Yellow cards and substitutions are left out.
     */
    fun soccerLive(json: String, eventId: String, prev: Live?): Live? {
        val e = scoreboardEvent(json, eventId) ?: return null
        val d = JSONObject(json)
        val lg = d.optJSONArray("leagues").objects().firstOrNull()
        val m = event(e, Sport.SOCCER, lg?.str("slug") ?: prev?.match?.league, lg?.str("abbreviation")) ?: return null
        val comp = e.optJSONArray("competitions").objects().first()
        val abbr = listOfNotNull(m.home, m.away).associate { it.id to it.abbr }
        val moments = ArrayList<Moment>()
        for (x in comp.optJSONArray("details").objects()) {
            val type = x.obj("type")?.str("text").orEmpty()
            val clk = x.obj("clock")?.str("displayValue").orEmpty()
            val at = x.obj("clock")?.optDouble("value", 0.0)?.toLong() ?: 0L
            val who = x.optJSONArray("athletesInvolved").objects().firstOrNull()
            val name = who?.str("displayName") ?: who?.str("shortName") ?: ""
            val team = abbr[x.obj("team")?.str("id")].orEmpty()
            val id = "$type:$at:${who?.str("id").orEmpty()}"
            when {
                x.optBoolean("scoringPlay") -> {
                    val how = when { x.optBoolean("ownGoal") -> " (own goal)"; x.optBoolean("penaltyKick") -> " (penalty)"; else -> "" }
                    moments += Moment(id, MomentKind.GOAL, "Goal, $team: $name$how $clk".trim(), clk)
                }
                x.optBoolean("redCard") -> moments += Moment(id, MomentKind.RED_CARD, "Red card, $team: $name $clk".trim(), clk)
                type.contains("Penalty", true) && type.contains("Miss", true) -> moments += Moment(id, MomentKind.PENALTY_MISS, "Penalty missed, $team: $name $clk".trim(), clk)
            }
        }
        if (m.clock == "HT") moments += Moment("ht", MomentKind.HALF_TIME, "Half time, ${m.scoreLine()}", "HT")
        if (m.phase == Phase.DONE) moments += Moment("ft", MomentKind.FULL_TIME, "Full time, ${m.scoreLine()}", "FT")
        return Live(m.copy(teams = prev?.match?.teams ?: m.teams), moments, prev?.lineups.orEmpty(), updatedAt = System.currentTimeMillis())
    }

    /** Lineups from a match summary, once they are published (starters and the bench, by side). */
    fun lineups(json: String): List<Lineup> = JSONObject(json).optJSONArray("rosters").objects().mapNotNull { r ->
        val players = r.optJSONArray("roster").objects()
        if (players.isEmpty()) return@mapNotNull null
        val name = { p: JSONObject -> p.obj("athlete")?.str("displayName") ?: p.str("playerName") }
        val starters = players.filter { it.optBoolean("starter") }.mapNotNull(name)
        if (starters.isEmpty()) return@mapNotNull null
        Lineup(
            team = r.obj("team")?.str("abbreviation") ?: r.obj("team")?.str("displayName").orEmpty(),
            formation = r.str("formation"),
            starters = starters,
            bench = players.filter { !it.optBoolean("starter") }.mapNotNull(name),
        )
    }

    // ---- NFL live ----

    /** One NFL game from the day's scoreboard: score, quarter and clock, down and distance. */
    fun nflLive(json: String, eventId: String, prev: Live?): Live? {
        val e = scoreboardEvent(json, eventId) ?: return null
        val m = event(e, Sport.NFL, "nfl", "NFL") ?: return null
        val sit = e.optJSONArray("competitions").objects().first().obj("situation")
        val down = sit?.str("downDistanceText")?.trim()?.takeIf { !it.startsWith("&") }
        val moments = prev?.moments.orEmpty().filter { it.kind != MomentKind.FULL_TIME }.toMutableList()
        if (m.phase == Phase.DONE) moments += Moment("ft", MomentKind.FULL_TIME, "Final, ${m.scoreLine()}", "Final")
        return Live(
            m.copy(teams = prev?.match?.teams ?: m.teams), moments, situation = if (m.phase == Phase.LIVE) down else null,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** True when the score moved since the last poll, so the summary (with the play text) is worth fetching. */
    fun scoreMoved(prev: Live?, now: Live): Boolean = prev != null && prev.match.score != now.match.score

    /** NFL scoring plays from the game summary, merged into `live`'s moments. */
    fun nflScoring(json: String, live: Live): Live {
        val have = live.moments.map { it.id }.toSet()
        val plays = JSONObject(json).optJSONArray("scoringPlays").objects().mapNotNull { p ->
            val id = "sp:" + (p.str("id") ?: return@mapNotNull null)
            if (id in have) return@mapNotNull null
            val abbr = p.obj("type")?.str("abbreviation").orEmpty()
            val text = p.str("text").orEmpty().trim()
            val kind = when {
                abbr == "TD" || text.contains("touchdown", true) || p.obj("type")?.str("text")?.contains("Touchdown") == true -> MomentKind.TOUCHDOWN
                abbr == "SF" || text.contains("safety", true) -> MomentKind.SAFETY
                else -> MomentKind.FIELD_GOAL
            }
            val q = p.obj("period")?.optInt("number") ?: 0
            val clk = (if (q >= 5) "OT" else "Q$q") + " " + p.obj("clock")?.str("displayValue").orEmpty()
            val team = p.obj("team")?.str("abbreviation").orEmpty()
            val (a, h) = p.optInt("awayScore") to p.optInt("homeScore")
            val word = when (kind) { MomentKind.TOUCHDOWN -> "Touchdown"; MomentKind.SAFETY -> "Safety"; else -> "Field goal" }
            Moment(id, kind, "$word $team: $text ($a–$h)", clk.trim())
        }
        val ft = live.moments.filter { it.kind == MomentKind.FULL_TIME }
        return live.copy(moments = live.moments.filter { it.kind != MomentKind.FULL_TIME } + plays + ft)
    }

    // ---- cricket ----

    private fun balls(overs: Double): Int { val o = overs.toInt(); return o * 6 + ((overs - o) * 10 + 0.5).toInt() }

    private class Innings(val n: Int, val team: String, val runs: Int, val wickets: Int, val overs: Double, val batting: Boolean)

    private fun innings(comp: JSONObject): List<Innings> = comp.optJSONArray("competitors").objects().flatMap { c ->
        val abbr = c.obj("team")?.str("abbreviation").orEmpty()
        c.optJSONArray("linescores").objects().filter { it.optInt("runs") > 0 || it.optBoolean("isBatting") }.map {
            Innings(it.optInt("period"), abbr, it.optInt("runs"), it.optInt("wickets"), it.optDouble("overs", 0.0), it.optBoolean("isBatting"))
        }
    }.sortedBy { it.n }

    private fun cricketClock(comp: JSONObject): String =
        innings(comp).lastOrNull()?.let { "${it.overs.toString().removeSuffix(".0")} ov" }.orEmpty()

    /**
     * One IPL match from the league scoreboard. Wickets and team milestones come from the innings lines;
     * a chase gets "need 38 off 22" and a balls bar. Batters' fifties come from [cricketCards].
     */
    fun cricketLive(json: String, eventId: String, prev: Live?, ballsPerInnings: Int = 120): Live? {
        val e = scoreboardEvent(json, eventId) ?: return null
        val d = JSONObject(json)
        val lg = d.optJSONArray("leagues").objects().firstOrNull()
        val m = event(e, Sport.CRICKET, lg?.str("slug") ?: Teams.IPL, lg?.str("abbreviation") ?: "IPL") ?: return null
        val comp = e.optJSONArray("competitions").objects().first()
        val inns = innings(comp)
        val moments = prev?.moments.orEmpty().filter { it.kind == MomentKind.FIFTY || it.kind == MomentKind.HUNDRED }.toMutableList()
        for (i in inns) {
            val line = "${i.team} ${i.runs}/${i.wickets} (${i.overs.toString().removeSuffix(".0")} ov)"
            for (w in 1..i.wickets) moments += Moment("wkt:${i.n}:$w", MomentKind.WICKET, "Wicket, $line", "${i.overs}")
            for (r in listOf(100, 150, 200, 250)) if (i.runs >= r) moments += Moment("runs:${i.n}:$r", MomentKind.TEAM_MILESTONE, "${i.team} pass $r", "")
            if (i.n == 1 && inns.size > 1) moments += Moment("inn:1", MomentKind.INNINGS, "Innings break, $line", "")
        }
        if (m.phase == Phase.DONE) moments += Moment("ft", MomentKind.FULL_TIME, m.result ?: "Result, ${m.scoreLine()}", "")
        val cur = inns.lastOrNull()
        var situation: String? = null
        var progress: Pair<Int, Int>? = null
        if (cur != null && m.phase == Phase.LIVE) {
            val done = balls(cur.overs).coerceAtMost(ballsPerInnings)
            progress = done to ballsPerInnings
            val first = inns.firstOrNull { it.n == 1 }
            if (cur.n == 2 && first != null) {
                val target = Regex("target (\\d+)").find(m.home?.score.orEmpty() + " " + m.away?.score.orEmpty())?.groupValues?.get(1)?.toInt() ?: (first.runs + 1)
                val need = target - cur.runs
                situation = if (need > 0) "need $need off ${ballsPerInnings - done}" else null
            }
        }
        return Live(m.copy(teams = prev?.match?.teams ?: m.teams), moments.sortedBy { order(it) }, situation = situation, progress = progress, updatedAt = System.currentTimeMillis())
    }

    private fun order(m: Moment): Int = if (m.kind == MomentKind.FULL_TIME) 1 else 0

    /** Batters reaching 50 and 100, from the summary's batting cards, merged into `live`. */
    fun cricketCards(json: String, live: Live): Live {
        val have = live.moments.map { it.id }.toSet()
        val add = JSONObject(json).optJSONArray("matchcards").objects().filter { it.str("headline") == "Batting" }.flatMap { card ->
            val inn = card.str("inningsNumber").orEmpty()
            card.optJSONArray("playerDetails").objects().flatMap { p ->
                val runs = p.str("runs")?.toIntOrNull() ?: 0
                val who = p.str("playerName").orEmpty()
                val id = p.str("playerID").orEmpty()
                val line = "$who $runs (${p.str("ballsFaced") ?: "?"})"
                listOfNotNull(
                    if (runs >= 50) Moment("50:$inn:$id", MomentKind.FIFTY, "Fifty, $line", "") else null,
                    if (runs >= 100) Moment("100:$inn:$id", MomentKind.HUNDRED, "Hundred, $line", "") else null,
                )
            }
        }.filter { it.id !in have }
        return live.copy(moments = (live.moments + add).sortedBy { order(it) })
    }

    // ---- F1 live timing (ESPN) ----

    private val SESSION = mapOf("FP1" to "Practice 1", "FP2" to "Practice 2", "FP3" to "Practice 3", "SS" to "Sprint Qualifying", "SR" to "Sprint", "Qual" to "Qualifying", "Race" to "Race")

    private fun lastName(c: JSONObject): String {
        val a = c.obj("athlete")
        return a?.str("shortName")?.substringAfter(". ")?.trim() ?: a?.str("displayName")?.substringAfterLast(' ').orEmpty()
    }

    /** The race weekend's sessions on ESPN, keyed "Race", "Qualifying", ... with ESPN's weekend id. */
    fun f1Sessions(json: String): List<Match> = JSONObject(json).optJSONArray("events").objects().flatMap { e ->
        val gp = e.str("name").orEmpty()
        e.optJSONArray("competitions").objects().mapNotNull { c ->
            val code = c.obj("type")?.str("abbreviation") ?: return@mapNotNull null
            val name = SESSION[code] ?: return@mapNotNull null
            val ph = phase(c.obj("status"))
            val order = c.optJSONArray("competitors").objects().sortedBy { it.optInt("order", 99) }
            Match(
                id = "espn:${c.str("id")}", sport = Sport.F1, league = "f1", leagueName = "F1",
                start = epoch(c.str("date")) ?: return@mapNotNull null, phase = ph, title = gp, teams = setOf(Teams.F1),
                session = name, espnEvent = e.str("id"),
                clock = if (ph == Phase.LIVE) "Lap ${c.obj("status")?.optInt("period") ?: 0}" else "",
                result = if (ph == Phase.DONE) order.firstOrNull()?.let(::lastName) else null,
            )
        }
    }

    /**
     * One F1 session from ESPN's scoreboard: the running order and the lap. A new leader since the last
     * poll is a moment; the finish is a moment (the winner, or pole after qualifying).
     */
    fun f1Live(json: String, session: String, prev: Live?, laps: Int?, near: Long? = null): Live? {
        val d = JSONObject(json)
        for (e in d.optJSONArray("events").objects()) for (c in e.optJSONArray("competitions").objects()) {
            if (SESSION[c.obj("type")?.str("abbreviation")] != session) continue
            // The board shows one weekend; a session more than 12 h from ours belongs to another.
            if (near != null && kotlin.math.abs((epoch(c.str("date")) ?: 0L) - near) > 12 * 3_600_000L) continue
            val status = c.obj("status")
            val ph = phase(status)
            val order = c.optJSONArray("competitors").objects().sortedBy { it.optInt("order", 99) }.map(::lastName).filter { it.isNotEmpty() }
            val lap = status?.optInt("period") ?: 0
            val base = prev?.match ?: Match("espn:${c.str("id")}", Sport.F1, "f1", "F1", epoch(c.str("date")) ?: 0L, ph, e.str("name").orEmpty(), teams = setOf(Teams.F1), session = session)
            val racing = session == "Race" || session == "Sprint"
            val m = base.copy(
                phase = ph,
                clock = if (ph == Phase.LIVE && racing) "Lap $lap" + (laps?.let { "/$it" } ?: "") else if (ph == Phase.LIVE) "Live" else "",
                result = if (ph == Phase.DONE) order.firstOrNull() else null,
            )
            val moments = prev?.moments.orEmpty().toMutableList()
            val leader = order.firstOrNull()
            val was = prev?.positions?.firstOrNull()
            if (racing && ph == Phase.LIVE && leader != null && was != null && leader != was && lap > 1) {
                moments += Moment("lead:$lap:$leader", MomentKind.LEAD, "$leader takes the lead from $was, lap $lap", "Lap $lap")
            }
            if (ph == Phase.DONE && leader != null && moments.none { it.id == "finish" }) {
                moments += if (racing) Moment("finish", MomentKind.FINISH, "$leader wins the ${m.title}" + if (session == "Sprint") " sprint" else "", "")
                else Moment("finish", MomentKind.POLE, "$leader on pole" + if (session == "Sprint Qualifying") " for the sprint" else "", "")
            }
            return Live(
                m, moments, positions = order.take(10),
                progress = if (racing && laps != null && lap > 0) lap.coerceAtMost(laps) to laps else null,
                updatedAt = System.currentTimeMillis(),
            )
        }
        return null
    }
}

object Jolpica {
    private val SESSIONS = listOf(
        "FirstPractice" to "Practice 1", "SecondPractice" to "Practice 2", "ThirdPractice" to "Practice 3",
        "SprintQualifying" to "Sprint Qualifying", "SprintShootout" to "Sprint Qualifying", "Sprint" to "Sprint", "Qualifying" to "Qualifying",
    )

    /** The season's sessions. Titles read as calendar entries do ("United States Grand Prix qualifying"). */
    fun schedule(json: String, now: Long): List<Match> {
        val races = JSONObject(json).obj("MRData")?.obj("RaceTable")?.optJSONArray("Races").objects()
        return races.flatMap { r ->
            val season = r.str("season").orEmpty()
            val round = r.str("round").orEmpty()
            val gp = r.str("raceName").orEmpty()
            val circuit = r.obj("Circuit")?.str("circuitId")
            val sessions = SESSIONS.mapNotNull { (k, name) -> r.obj(k)?.let { s -> name to epoch(s.str("date"), s.str("time")) } } +
                ("Race" to epoch(r.str("date"), r.str("time")))
            sessions.mapNotNull { (name, at) ->
                at ?: return@mapNotNull null
                Match(
                    id = "f1:$season:$round:${name.lowercase().replace(' ', '-')}", sport = Sport.F1, league = "f1", leagueName = "F1",
                    start = at, phase = if (at + 3 * 3_600_000L < now) Phase.DONE else Phase.PRE,
                    title = if (name == "Race") gp else "$gp ${name.lowercase()}", teams = setOf(Teams.F1), session = name, circuit = circuit,
                )
            }
        }.distinctBy { it.id }.sortedBy { it.start }
    }

    /** The last race's podium: "Verstappen, Leclerc, Antonelli". */
    fun podium(json: String): Pair<String, List<String>>? {
        val r = JSONObject(json).obj("MRData")?.obj("RaceTable")?.optJSONArray("Races").objects().firstOrNull() ?: return null
        val top = r.optJSONArray("Results").objects().sortedBy { it.str("position")?.toIntOrNull() ?: 99 }.take(3)
            .mapNotNull { it.obj("Driver")?.str("familyName") }
        return r.str("raceName").orEmpty() to top
    }

    /** Race distance in laps, from the winner of the last race at this circuit. */
    fun laps(json: String): Int? = JSONObject(json).obj("MRData")?.obj("RaceTable")?.optJSONArray("Races").objects()
        .firstOrNull()?.optJSONArray("Results").objects().firstOrNull()?.str("laps")?.toIntOrNull()
}
