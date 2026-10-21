package dev.phonerecall.olympia

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** His Olympia settings. Edited in Themis > Olympia (and from the Olympia screen). */
@Immutable
data class OlympiaSettings(
    val followed: Set<String> = Teams.defaults,
    val competitions: Set<Competition> = Competition.defaults,
    val alerts: Alerts = Alerts.KEY,
    /** F1 practice is hidden by default, like the calendar's seeded rule. */
    val practice: Boolean = false,
    /** Sports where big matches he doesn't follow (finals, derbies, playoffs) get a quiet card. */
    val big: Set<Sport> = setOf(Sport.SOCCER, Sport.NFL),
) {
    fun follows(m: Match): Boolean {
        if (m.teams.none { it in followed }) return false
        if (m.sport == Sport.F1 && m.isPractice && !practice) return false
        val comp = Competition.of(m.league) ?: return true
        return comp in competitions
    }
}

/** Everything Olympia knows, as the screens, the widget and the service read it. */
@Immutable
data class OlympiaSnapshot(
    val settings: OlympiaSettings = OlympiaSettings(),
    /** Fixtures and results of everything he can follow, from the last refresh. */
    val matches: List<Match> = emptyList(),
    val teams: Map<String, TeamInfo> = emptyMap(),
    val live: Map<String, Live> = emptyMap(),
    /** The last F1 podium ("Singapore Grand Prix" to three names). */
    val podium: Pair<String, List<String>>? = null,
    /** Big matches today and tomorrow that he doesn't follow ([BigMatch]). */
    val big: List<Match> = emptyList(),
    val refreshedAt: Long = 0L,
    val loaded: Boolean = false,
) {
    /** Followed only, with live scores laid over the fixture list. */
    val followed: List<Match> get() = matches.map { live[it.id]?.match ?: it }.filter { settings.follows(it) }

    fun team(key: String, now: Long): TeamInfo {
        val base = teams[key] ?: TeamInfo(key)
        val mine = matches.map { live[it.id]?.match ?: it }.filter { key in it.teams && (it.sport != Sport.F1 || !it.isPractice || settings.practice) }
        return base.copy(
            upcoming = mine.filter { it.phase == Phase.PRE || it.phase == Phase.LIVE }.filter { it.start + MatchDay.length(it) > now }.sortedBy { it.start },
            recent = mine.filter { it.phase == Phase.DONE }.sortedByDescending { it.start },
        )
    }
}

/**
 * Olympia's state on the phone: SharedPreferences, JSON. One process-wide flow, so the app, the
 * service, the alarm receiver and the widget all see the same thing.
 */
object OlympiaStore {
    private const val FILE = "olympia"
    private val _state = MutableStateFlow(OlympiaSnapshot())
    val state: StateFlow<OlympiaSnapshot> = _state.asStateFlow()
    private val lock = Any()

    fun load(c: Context): OlympiaSnapshot = synchronized(lock) {
        if (_state.value.loaded) return _state.value
        val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val s = runCatching { Codec.snapshot(sp.getString("snapshot", null)) }.getOrNull() ?: OlympiaSnapshot()
        val loaded = s.copy(loaded = true)
        _state.value = loaded
        loaded
    }

    fun update(c: Context, change: (OlympiaSnapshot) -> OlympiaSnapshot): OlympiaSnapshot = synchronized(lock) {
        val next = change(load(c)).copy(loaded = true)
        _state.value = next
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("snapshot", Codec.json(next)).apply()
        next
    }

    fun settings(c: Context, change: (OlympiaSettings) -> OlympiaSettings) = update(c) { it.copy(settings = change(it.settings)) }

    /** Seen moment ids per match, for [Moments.fresh]. Kept apart from the snapshot; small. */
    fun seen(c: Context, matchId: String): Set<String>? =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("seen:$matchId", null)?.split('\n')?.filter { it.isNotEmpty() }?.toSet()

    fun markSeen(c: Context, matchId: String, ids: Collection<String>) {
        val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        // Only the last few days of matches are kept.
        val keys = sp.all.keys.filter { it.startsWith("seen:") }
        sp.edit().apply {
            if (keys.size > 40) keys.take(keys.size - 40).forEach { remove(it) }
            putString("seen:$matchId", ids.joinToString("\n"))
        }.apply()
    }

    /** For previews and tests. */
    fun set(s: OlympiaSnapshot) { _state.value = s }
}

/** JSON for the snapshot. Field names are short; unknown or broken entries are dropped. */
internal object Codec {
    private fun side(s: Side?) = s?.let { JSONObject().put("id", it.id).put("n", it.name).put("a", it.abbr).put("s", it.score).put("h", it.home) }
    private fun side(o: JSONObject?) = o?.let { Side(it.optString("id"), it.optString("n"), it.optString("a"), if (it.isNull("s")) null else it.optString("s"), it.optBoolean("h")) }
    private fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    fun match(m: Match): JSONObject = JSONObject()
        .put("id", m.id).put("sp", m.sport.name).put("lg", m.league).put("ln", m.leagueName).put("t0", m.start)
        .put("ph", m.phase.name).put("ti", m.title).put("ho", side(m.home)).put("aw", side(m.away))
        .put("tm", JSONArray(m.teams.toList())).put("ck", m.clock).put("dt", m.detail)
        .put("se", m.session).put("rs", m.result).put("ev", m.espnEvent).put("ci", m.circuit)
        .put("bc", JSONArray(m.broadcasts)).put("sg", m.stage)

    fun match(o: JSONObject): Match? = runCatching {
        Match(
            id = o.getString("id"), sport = Sport.valueOf(o.getString("sp")), league = o.getString("lg"), leagueName = o.optString("ln"),
            start = o.getLong("t0"), phase = Phase.valueOf(o.getString("ph")), title = o.optString("ti"),
            home = side(o.optJSONObject("ho")), away = side(o.optJSONObject("aw")), teams = strs(o.optJSONArray("tm")).toSet(),
            clock = o.optString("ck"), detail = o.optString("dt"),
            session = o.optString("se").ifEmpty { null }, result = o.optString("rs").ifEmpty { null },
            espnEvent = o.optString("ev").ifEmpty { null }, circuit = o.optString("ci").ifEmpty { null },
            broadcasts = strs(o.optJSONArray("bc")), stage = o.optString("sg").ifEmpty { null },
        )
    }.getOrNull()

    private fun live(l: Live): JSONObject = JSONObject()
        .put("m", match(l.match))
        .put("mo", JSONArray(l.moments.map { JSONObject().put("id", it.id).put("k", it.kind.name).put("t", it.text).put("c", it.clock) }))
        .put("lu", JSONArray(l.lineups.map { JSONObject().put("t", it.team).put("f", it.formation).put("s", JSONArray(it.starters)).put("b", JSONArray(it.bench)) }))
        .put("si", l.situation).put("po", JSONArray(l.positions))
        .put("pr", l.progress?.let { JSONArray(listOf(it.first, it.second)) }).put("at", l.updatedAt)

    private fun live(o: JSONObject): Live? = runCatching {
        val mo = o.optJSONArray("mo")
        val lu = o.optJSONArray("lu")
        val pr = o.optJSONArray("pr")
        Live(
            match = match(o.getJSONObject("m")) ?: return null,
            moments = (0 until (mo?.length() ?: 0)).mapNotNull { i ->
                mo!!.optJSONObject(i)?.let { runCatching { Moment(it.getString("id"), MomentKind.valueOf(it.getString("k")), it.optString("t"), it.optString("c")) }.getOrNull() }
            },
            lineups = (0 until (lu?.length() ?: 0)).mapNotNull { i ->
                lu!!.optJSONObject(i)?.let { Lineup(it.optString("t"), it.optString("f").ifEmpty { null }, strs(it.optJSONArray("s")), strs(it.optJSONArray("b"))) }
            },
            situation = o.optString("si").ifEmpty { null }, positions = strs(o.optJSONArray("po")),
            progress = pr?.let { it.optInt(0) to it.optInt(1) }, updatedAt = o.optLong("at"),
        )
    }.getOrNull()

    fun json(s: OlympiaSnapshot): String = JSONObject()
        .put("fo", JSONArray(s.settings.followed.toList()))
        .put("co", JSONArray(s.settings.competitions.map { it.name }))
        .put("al", s.settings.alerts.name).put("pq", s.settings.practice)
        .put("bs", JSONArray(s.settings.big.map { it.name }))
        .put("bg", JSONArray(s.big.map(::match)))
        .put("ma", JSONArray(s.matches.map(::match)))
        .put("te", JSONArray(s.teams.values.map { JSONObject().put("k", it.key).put("st", it.standing).put("rc", it.record) }))
        .put("li", JSONArray(s.live.values.map(::live)))
        .put("pd", s.podium?.let { JSONObject().put("r", it.first).put("d", JSONArray(it.second)) })
        .put("at", s.refreshedAt)
        .toString()

    fun snapshot(json: String?): OlympiaSnapshot? {
        if (json.isNullOrEmpty()) return null
        val o = JSONObject(json)
        val settings = OlympiaSettings(
            followed = strs(o.optJSONArray("fo")).toSet(),
            competitions = strs(o.optJSONArray("co")).mapNotNull { n -> Competition.entries.firstOrNull { it.name == n } }.toSet(),
            alerts = Alerts.entries.firstOrNull { it.name == o.optString("al") } ?: Alerts.KEY,
            practice = o.optBoolean("pq"),
            big = o.optJSONArray("bs")?.let { a -> strs(a).mapNotNull { n -> Sport.entries.firstOrNull { it.name == n } }.toSet() } ?: OlympiaSettings().big,
        )
        val bg = o.optJSONArray("bg")
        val ma = o.optJSONArray("ma")
        val te = o.optJSONArray("te")
        val li = o.optJSONArray("li")
        return OlympiaSnapshot(
            settings = settings,
            matches = (0 until (ma?.length() ?: 0)).mapNotNull { ma!!.optJSONObject(it)?.let(::match) },
            teams = (0 until (te?.length() ?: 0)).mapNotNull { te!!.optJSONObject(it) }.associate {
                it.optString("k") to TeamInfo(it.optString("k"), it.optString("st").ifEmpty { null }, it.optString("rc").ifEmpty { null })
            },
            live = (0 until (li?.length() ?: 0)).mapNotNull { li!!.optJSONObject(it)?.let(::live) }.associateBy { it.match.id },
            podium = o.optJSONObject("pd")?.let { it.optString("r") to strs(it.optJSONArray("d")) },
            big = (0 until (bg?.length() ?: 0)).mapNotNull { bg!!.optJSONObject(it)?.let(::match) },
            refreshedAt = o.optLong("at"),
        )
    }
}
