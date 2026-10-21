package dev.phonerecall.olympia

import android.content.Context
import dev.phonerecall.brain.NetGate
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The exact URLs Olympia calls. Two hosts, both in NetGate and network_security_config.xml. */
object Urls {
    const val ESPN = "https://site.api.espn.com/apis/site/v2/sports"
    const val JOLPICA = "https://api.jolpi.ca/ergast/f1"

    /** ESPN's scoreboard day is the US Eastern day (Monday night's 8:15 PM game is on Monday's board). */
    private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.of("America/New_York"))
    fun day(ms: Long): String = DAY.format(Instant.ofEpochMilli(ms))

    fun soccerFixtures(id: String) = "$ESPN/soccer/all/teams/$id/schedule?fixture=true"
    fun soccerResults(id: String) = "$ESPN/soccer/all/teams/$id/schedule"
    fun nflSchedule(id: String) = "$ESPN/football/nfl/teams/$id/schedule"
    fun iplSeason(year: Int) = "$ESPN/cricket/${Teams.IPL}/scoreboard?dates=$year"
    fun f1Schedule() = "$JOLPICA/current.json"
    fun f1LastResults() = "$JOLPICA/current/last/results.json"
    fun f1Laps(year: Int, circuit: String) = "$JOLPICA/$year/circuits/$circuit/results/1.json"
    fun f1Timing() = "$ESPN/racing/f1/scoreboard"

    /** A league's day, for big matches he doesn't follow ([BigMatch.LEAGUES]). */
    fun leagueDay(league: String, ms: Long) =
        if (league == "nfl") "$ESPN/football/nfl/scoreboard?dates=${day(ms)}" else "$ESPN/soccer/$league/scoreboard?dates=${day(ms)}"

    /** The live board for a match: its league's scoreboard for the match day. */
    fun board(m: Match) = when (m.sport) {
        Sport.SOCCER -> "$ESPN/soccer/${m.league}/scoreboard?dates=${day(m.start)}"
        Sport.NFL -> "$ESPN/football/nfl/scoreboard?dates=${day(m.start)}"
        Sport.CRICKET -> "$ESPN/cricket/${Teams.IPL}/scoreboard?dates=${day(m.start)}"
        Sport.F1 -> f1Timing()
    }

    fun summary(m: Match) = when (m.sport) {
        Sport.SOCCER -> "$ESPN/soccer/${m.league}/summary?event=${m.id}"
        Sport.NFL -> "$ESPN/football/nfl/summary?event=${m.id}"
        Sport.CRICKET -> "$ESPN/cricket/${Teams.IPL}/summary?event=${m.id}"
        Sport.F1 -> null
    }
}

/** GET through NetGate. Throws on a block or an HTTP error; callers keep what they had. */
object Fetch {
    fun get(url: String): String {
        NetGate.check(url)
        val t0 = System.currentTimeMillis()
        val c = dev.phonerecall.brain.TlsGuard.open(url)
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "Zeus/olympia")
            val code = c.responseCode
            NetGate.logRequest("GET", url, code, System.currentTimeMillis() - t0)
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }
}

/**
 * Fixtures, results, standings (a few calls per followed team, a couple of times a day) and one live
 * poll per match. All network; call off the main thread.
 */
class Sync(private val c: Context, private val get: (String) -> String = Fetch::get) {

    /** Re-reads every followed team's fixtures and results. Keeps what it had for anything that fails. */
    fun refresh(now: Long = System.currentTimeMillis()): OlympiaSnapshot {
        val s = OlympiaStore.load(c)
        val followed = s.settings.followed.mapNotNull(Teams::byKey)
        val fresh = HashMap<String, Match>()
        val teams = HashMap(s.teams)
        var podium = s.podium
        var failed = 0
        for (t in followed) runCatching {
            when (t.sport) {
                Sport.SOCCER -> {
                    val (info, fixtures) = Espn.schedule(get(Urls.soccerFixtures(t.espnId)), Sport.SOCCER)
                    val (_, results) = Espn.schedule(get(Urls.soccerResults(t.espnId)), Sport.SOCCER)
                    (fixtures + results).forEach { fresh[it.id] = merge(fresh[it.id], it) }
                    info?.let { teams[t.key] = it }
                }
                Sport.NFL -> {
                    val (info, games) = Espn.schedule(get(Urls.nflSchedule(t.espnId)), Sport.NFL)
                    games.forEach { fresh[it.id] = merge(fresh[it.id], it) }
                    info?.let { teams[t.key] = it }
                }
                Sport.CRICKET -> {
                    val year = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Kolkata")).year
                    val season = Espn.scoreboard(get(Urls.iplSeason(year)), Sport.CRICKET).filter { t.key in it.teams }
                    season.forEach { fresh[it.id] = it }
                    val done = season.filter { it.phase == Phase.DONE }
                    val won = done.count { it.result?.startsWith(t.short + " won") == true }
                    val lost = done.count { val r = it.result.orEmpty(); r.contains(" won") && !r.startsWith(t.short + " won") }
                    teams[t.key] = TeamInfo(t.key, standing = if (done.isEmpty()) null else "IPL $year", record = if (done.isEmpty()) null else "$won-$lost")
                }
                Sport.F1 -> {
                    Jolpica.schedule(get(Urls.f1Schedule()), now).forEach { fresh[it.id] = it }
                    runCatching { Jolpica.podium(get(Urls.f1LastResults())) }.getOrNull()?.let { podium = it }
                }
            }
        }.onFailure { failed++ }
        val big = runCatching { bigMatches(s.settings, now) }.getOrNull()
        // Results are kept for half a year, so a team between seasons (the IPL, the NFL in spring) still has a page.
        val keepFrom = now - 183 * 86_400_000L
        return OlympiaStore.update(c) { cur ->
            // A failed team keeps its old rows; a refreshed one is replaced. Live overlays stay.
            val refreshed = fresh.values.flatMap { it.teams }.toSet()
            val kept = cur.matches.filter { m -> m.teams.none { it in refreshed } && fresh[m.id] == null }
            cur.copy(
                matches = (kept + fresh.values)
                    .filter { it.start >= keepFrom }.sortedBy { it.start },
                teams = teams, podium = podium, big = big ?: cur.big.filter { it.start > now },
                refreshedAt = if (failed < followed.size) now else cur.refreshedAt,
                live = cur.live.filterValues { it.updatedAt >= now - 2 * 86_400_000L },
            )
        }
    }

    /**
     * Today's and tomorrow's boards of the big leagues, in the sports he follows something in: two
     * small reads per league, twice a day. Null when every read failed, so the old list stays.
     */
    private fun bigMatches(st: OlympiaSettings, now: Long): List<Match>? {
        val sports = BigMatch.sports(st)
        val leagues = BigMatch.LEAGUES.filterKeys { it in sports }.flatMap { (sport, ls) -> ls.map { sport to it } }
            .filter { (_, l) -> Competition.of(l)?.let { it in st.competitions } ?: true }
        if (leagues.isEmpty()) return emptyList()
        var ok = false
        val out = leagues.flatMap { (sport, l) ->
            listOf(now, now + 86_400_000L).flatMap { day ->
                runCatching { Espn.scoreboard(get(Urls.leagueDay(l, day)), sport) }.onSuccess { ok = true }.getOrDefault(emptyList())
            }
        }
        return if (ok) BigMatch.pick(out.distinctBy { it.id }, st) else null
    }

    /** Two followed teams in one match: one row, both keys. */
    private fun merge(a: Match?, b: Match): Match = if (a == null) b else b.copy(teams = a.teams + b.teams)

    /**
     * One live poll. Returns the new state and the key moments that are new since the last look
     * (empty on the first look). Lineups are read once they are out; NFL and cricket summaries only
     * when the score moved or every few polls.
     */
    fun poll(m: Match, polls: Int): Pair<Live, List<Moment>>? {
        val s = OlympiaStore.load(c)
        val prev = s.live[m.id]
        val board = get(Urls.board(m))
        var live = when (m.sport) {
            Sport.SOCCER -> Espn.soccerLive(board, m.id, prev)
            Sport.NFL -> Espn.nflLive(board, m.id, prev)?.let { l ->
                if (Espn.scoreMoved(prev, l) || polls % 10 == 0 || l.match.phase == Phase.DONE) runCatching { Espn.nflScoring(get(Urls.summary(m)!!), l) }.getOrDefault(l) else l
            }
            Sport.CRICKET -> Espn.cricketLive(board, m.id, prev)?.let { l ->
                if (polls % 4 == 0 || l.match.phase == Phase.DONE) runCatching { Espn.cricketCards(get(Urls.summary(m)!!), l) }.getOrDefault(l) else l
            }
            Sport.F1 -> Espn.f1Live(board, m.session ?: "Race", prev, laps(m), near = m.start)?.let { l ->
                // ESPN's F1 board carries the current weekend only; our row keeps its Jolpica id and title.
                l.copy(match = l.match.copy(id = m.id, title = m.title, start = m.start, teams = m.teams, circuit = m.circuit))
            }
        } ?: return null
        live = live.copy(match = live.match.copy(teams = m.teams, broadcasts = live.match.broadcasts.ifEmpty { m.broadcasts }), lineups = live.lineups.ifEmpty { prev?.lineups.orEmpty() })
        if (live.lineups.isEmpty() && MatchDay.hasLineups(m) && polls % 5 == 0) live = withLineups(live)
        val seen = OlympiaStore.seen(c, m.id)
        val fresh = Moments.fresh(live.moments, seen)
        OlympiaStore.markSeen(c, m.id, live.moments.map { it.id } + seen.orEmpty())
        val final = live
        OlympiaStore.update(c) { it.copy(live = it.live + (m.id to final)) }
        return final to fresh
    }

    /** The lineup look an hour before kickoff, and again at kickoff if they were not out yet. */
    fun lineups(m: Match): Live? {
        val prev = OlympiaStore.load(c).live[m.id] ?: Live(m)
        if (prev.lineups.isNotEmpty()) return prev
        val l = withLineups(prev.copy(updatedAt = System.currentTimeMillis()))
        if (l.lineups.isEmpty()) return null
        OlympiaStore.update(c) { it.copy(live = it.live + (m.id to l)) }
        return l
    }

    private fun withLineups(l: Live): Live {
        val url = Urls.summary(l.match) ?: return l
        val lu = runCatching { Espn.lineups(get(url)) }.getOrDefault(emptyList())
        return if (lu.isEmpty()) l else l.copy(lineups = lu)
    }

    private val lapsCache = HashMap<String, Int?>()

    /** Race laps from last year's race at the circuit (Jolpica). Null if unknown. */
    private fun laps(m: Match): Int? {
        val circuit = m.circuit ?: return null
        if (m.session != "Race") return null
        return lapsCache.getOrPut(circuit) {
            val year = Instant.ofEpochMilli(m.start).atZone(ZoneId.of("UTC")).year - 1
            runCatching { Jolpica.laps(get(Urls.f1Laps(year, circuit))) }.getOrNull()
        }
    }
}
