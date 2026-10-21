package dev.phonerecall.olympia

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.home.Fmt
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.concurrent.thread

/** Olympia: live first, then what's coming, his teams, and the latest results. */
@Composable
fun OlympiaScreen(wide: Boolean, gutter: Dp) {
    val ctx = LocalContext.current.applicationContext
    val s by OlympiaStore.state.collectAsStateWithLifecycle()
    var team by rememberSaveable { mutableStateOf<String?>(null) }
    var settings by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        thread(name = "olympia-open") {
            val now = System.currentTimeMillis()
            if (now - OlympiaStore.load(ctx).refreshedAt > 30 * MatchDay.MIN) { refreshing = true; runCatching { Sync(ctx).refresh() }; refreshing = false; Olympia.arm(ctx); LiveWidget.update(ctx) }
        }
    }
    val refresh = {
        if (!refreshing) {
            refreshing = true
            thread(name = "olympia-refresh") { runCatching { Sync(ctx).refresh() }; refreshing = false; Olympia.arm(ctx); LiveWidget.update(ctx) }
        }
    }
    when {
        settings -> { BackHandler { settings = false }; OlympiaSettingsPage(gutter, Place.SPORTS.title()) { settings = false } }
        team != null -> { BackHandler { team = null }; TeamContent(s, team!!, ZonedDateTime.now(), gutter) { team = null } }
        else -> OlympiaContent(s, ZonedDateTime.now(), gutter, wide, refreshing, onTeam = { team = it }, onSettings = { settings = true }, onRefresh = refresh)
    }
}

@Composable
fun OlympiaContent(
    s: OlympiaSnapshot, now: ZonedDateTime, gutter: Dp, wide: Boolean, refreshing: Boolean,
    onTeam: (String) -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit,
) {
    val c = Athena.colors
    val ms = now.toInstant().toEpochMilli()
    val matches = s.followed
    val live = MatchDay.liveNow(matches, ms).filter { it.phase == Phase.LIVE }.sortedByDescending { it.start }
    val ahead = matches.filter { it.phase == Phase.PRE && it.start > ms && it.start < ms + 10 * 86_400_000L }.sortedBy { it.start }
    val results = matches.filter { it.phase == Phase.DONE && it.start < ms }.sortedByDescending { it.start }.take(6)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.SPORTS.title(), gutter, emblem = Place.SPORTS, subtitle = Place.SPORTS.subtitle()) {
            TextAction(if (refreshing) "Refreshing" else "Refresh", onRefresh, enabled = !refreshing)
            TextAction("Teams", onSettings)
        }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            for (m in live) item(key = "live-${m.id}") {
                LiveBlock(s.live[m.id] ?: Live(m), Modifier.padding(bottom = Space.blockGap).widthIn(max = if (wide) 640.dp else Dp.Unspecified))
            }
            item { Zone("Coming up") }
            if (ahead.isEmpty()) item { Line(if (s.loaded && s.refreshedAt > 0) "Nothing in the next ten days." else "Fixtures arrive with the first refresh.", Type.body, c.ink2, Modifier.padding(vertical = Space.s)) }
            var lastDay = ""
            for (m in ahead) {
                val day = dayWord(m.start, now)
                if (day != lastDay) { item(key = "day-$day") { Kicker(day, Modifier.padding(top = Space.m, bottom = Space.xs)) }; lastDay = day }
                item(key = "up-${m.id}") { FixtureRow(m, now, lineups = s.live[m.id]?.lineups?.isNotEmpty() == true) }
            }
            item { Zone("Your teams") }
            val teams = Teams.all.filter { it.key in s.settings.followed }
            for (t in teams) item(key = "team-${t.key}") { TeamRow(t, s.team(t.key, ms), now) { onTeam(t.key) } }
            if (results.isNotEmpty()) {
                item { Zone("Results") }
                for (m in results) item(key = "res-${m.id}") { ResultRow(m, s, now) }
            }
            item {
                val ago = if (s.refreshedAt == 0L) "Not refreshed yet" else "Refreshed ${agoText(s.refreshedAt, ms)}"
                Line("$ago. Scores from ESPN, the F1 calendar from Jolpica.", Type.small, c.ink3, Modifier.padding(top = Space.xl))
            }
        }
    }
}

@Composable
private fun Zone(title: String) = Kicker(title, Modifier.padding(top = Space.xl, bottom = Space.xs))

/** A live match: the score big, the clock and the situation, the key moments, the lineups on request. */
@Composable
fun LiveBlock(l: Live, modifier: Modifier = Modifier) {
    val c = Athena.colors
    val m = l.match
    val shape = RoundedCornerShape(Radius.block)
    var showLineups by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(shape).background(c.block(Tint.SPORT, strong = true)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("${m.leagueName}${m.session?.let { " · $it" } ?: ""}", Modifier.weight(1f))
            StatusWord("Live", Tone.ACTIVE)
        }
        Spacer(Modifier.height(Space.s))
        if (m.sport == Sport.F1) {
            Line(m.title, Type.title, c.ink)
            Line(m.clock.ifEmpty { "Live" }, Type.body, c.ink2)
            Spacer(Modifier.height(Space.s))
            for ((i, d) in l.positions.take(5).withIndex()) FactRow("P${i + 1}", d)
        } else {
            val (a, b) = m.ordered()
            ScoreLine(a, b, m.sport)
            Line(listOfNotNull(m.clock.ifEmpty { null }, l.situation).joinToString(" · "), Type.body, c.ink2, Modifier.padding(top = Space.xs))
        }
        l.progress?.let { (done, of) -> Spacer(Modifier.height(Space.m)); Progress(done, of) }
        val keys = l.moments.filter { it.kind.key }.takeLast(4).reversed()
        if (keys.isNotEmpty()) {
            Spacer(Modifier.height(Space.m))
            for (k in keys) FactRow(k.clock.take(9), k.text.removeSuffix(" ${k.clock}"), labelWidth = 64.dp)
        }
        if (l.lineups.isNotEmpty()) {
            TextAction(if (showLineups) "Hide lineups" else "Lineups", { showLineups = !showLineups }, Modifier.padding(top = Space.xs))
            if (showLineups) Lineups(l.lineups)
        }
    }
}

@Composable
private fun ScoreLine(a: Side?, b: Side?, sport: Sport) {
    val c = Athena.colors
    if (a == null || b == null) return
    if (sport == Sport.CRICKET) {
        for (s in listOf(a, b)) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Line(s.name, Type.title, c.ink, Modifier.weight(1f), maxLines = 1)
            Line(s.score?.substringBefore(" (").orEmpty(), Type.headline, c.ink, maxLines = 1)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Line(a.name, Type.title, c.ink, Modifier.weight(1f), maxLines = 2)
        Line("${a.score ?: "–"}–${b.score ?: "–"}", Type.display, c.ink, Modifier.padding(horizontal = Space.m), maxLines = 1)
        Line(b.name, Type.title.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), c.ink, Modifier.weight(1f), maxLines = 2)
    }
}

/** A flat bar: laps or balls done. A hairline track, a saffron fill. */
@Composable
private fun Progress(done: Int, of: Int) {
    val c = Athena.colors
    val track = c.rule
    val fill = c.accent
    Box(Modifier.fillMaxWidth().height(4.dp).drawBehind {
        val y = size.height / 2
        drawLine(track, Offset(0f, y), Offset(size.width, y), size.height)
        if (of > 0) drawLine(fill, Offset(0f, y), Offset(size.width * done.coerceIn(0, of) / of, y), size.height)
    })
}

@Composable
private fun Lineups(lineups: List<Lineup>) {
    val c = Athena.colors
    for (lu in lineups) {
        Kicker(listOfNotNull(lu.team, lu.formation).joinToString(" · "), Modifier.padding(top = Space.m, bottom = Space.xxs))
        Line(lu.starters.joinToString(", "), Type.small, c.ink)
        if (lu.bench.isNotEmpty()) Line("Bench: " + lu.bench.joinToString(", "), Type.small, c.ink3, Modifier.padding(top = Space.xxs))
    }
}

@Composable
private fun FixtureRow(m: Match, now: ZonedDateTime, lineups: Boolean) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Line(Fmt.time(m.start, now), Type.small, c.ink2, Modifier.width(76.dp), maxLines = 1)
        Column(Modifier.weight(1f)) {
            Line(m.title, Type.body, c.ink, maxLines = 2)
            Line(m.leagueName, Type.small, c.ink3, maxLines = 1)
        }
        if (lineups) StatusWord("Lineups", Tone.ACTIVE)
    }
}

@Composable
private fun ResultRow(m: Match, s: OlympiaSnapshot, now: ZonedDateTime) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(OlympiaCards.resultLine(m, s), Type.body, c.ink, maxLines = 2)
            Line("${m.leagueName} · ${dayWord(m.start, now)}", Type.small, c.ink3, maxLines = 1)
        }
        m.teams.firstNotNullOfOrNull { k -> outcome(m, k) }?.let { (word, tone) -> Spacer(Modifier.width(Space.m)); StatusWord(word, tone) }
    }
}

@Composable
private fun TeamRow(t: Team, info: TeamInfo, now: ZonedDateTime, onClick: () -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule).clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(t.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            val next = info.next?.let { if (it.phase == Phase.LIVE) "Live now: ${opponent(it, t)}" else "Next: ${opponent(it, t)}, ${LiveWidget.whenText(it, now)}" }
            Line(listOfNotNull(info.standing, next).joinToString(". ").ifEmpty { "No fixtures yet" }, Type.small, c.ink3, maxLines = 2)
        }
        info.last?.let { m -> outcome(m, t.key)?.let { (w, tone) -> Spacer(Modifier.width(Space.m)); StatusWord(w, tone) } }
    }
}

/** A followed team's page: standing, next, last, the run of fixtures and results. */
@Composable
fun TeamContent(s: OlympiaSnapshot, key: String, now: ZonedDateTime, gutter: Dp, back: () -> Unit) {
    val c = Athena.colors
    val t = Teams.byKey(key) ?: return
    val info = s.team(key, now.toInstant().toEpochMilli())
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(t.name, gutter, kicker = Place.SPORTS.title()) { TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Column(Modifier.padding(top = Space.s)) {
                    info.standing?.let { FactRow("Table", it) }
                    info.record?.let { FactRow("Record", it) }
                    info.next?.let { FactRow("Next", "${it.title}, ${LiveWidget.whenText(it, now)}") }
                    info.last?.let { FactRow("Last", OlympiaCards.resultLine(it, s), outcome(it, key)?.first, outcome(it, key)?.second ?: Tone.QUIET) }
                    if (t.sport == Sport.F1) s.podium?.let { (race, top) -> FactRow("Podium", "$race: ${top.joinToString(", ")}") }
                }
            }
            if (info.upcoming.isNotEmpty()) {
                item { Zone("Fixtures") }
                for (m in info.upcoming.take(12)) item(key = "u-${m.id}") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Line(LiveWidget.whenText(m, now).replaceFirstChar { it.uppercase() }, Type.small, c.ink2, Modifier.width(132.dp), maxLines = 2)
                        Column(Modifier.weight(1f)) {
                            Line(if (t.sport == Sport.F1) m.title else opponent(m, t), Type.body, c.ink, maxLines = 2)
                            Line(m.leagueName, Type.small, c.ink3, maxLines = 1)
                        }
                    }
                }
            }
            if (info.recent.isNotEmpty()) {
                item { Zone("Results") }
                for (m in info.recent.take(12)) item(key = "r-${m.id}") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Line(OlympiaCards.resultLine(m, s), Type.body, c.ink, maxLines = 2)
                            Line("${m.leagueName} · ${Fmt.shortDate(Instant.ofEpochMilli(m.start).atZone(now.zone))}", Type.small, c.ink3, maxLines = 1)
                        }
                        outcome(m, key)?.let { (w, tone) -> StatusWord(w, tone) }
                    }
                }
            }
        }
    }
}

/** Followed teams, competitions, alerts, F1 practice. Themis opens the same page. */
@Composable
fun OlympiaSettingsPage(gutter: Dp, kicker: String, back: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val s by OlympiaStore.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { thread { OlympiaStore.load(ctx) } }
    OlympiaSettingsContent(s.settings, gutter, kicker, back) { change ->
        thread(name = "olympia-settings") { OlympiaStore.settings(ctx, change); Olympia.settingsChanged(ctx) }
    }
}

@Composable
fun OlympiaSettingsContent(st: OlympiaSettings, gutter: Dp, kicker: String, back: () -> Unit, edit: ((OlympiaSettings) -> OlympiaSettings) -> Unit) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Teams and alerts", gutter, kicker = kicker) { TextAction("Done", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item { Zone("Alerts") }
            for (a in Alerts.entries) item(key = "al-${a.name}") {
                Toggle(a.label, a.about, a == st.alerts, onWord = "On", offWord = "") { edit { it.copy(alerts = a) } }
            }
            for ((group, teams) in Teams.all.groupBy { it.group }) {
                item(key = "g-$group") { Zone(group) }
                for (t in teams) item(key = "t-${t.key}") {
                    val on = t.key in st.followed
                    Toggle(t.name, null, on) { edit { s -> s.copy(followed = if (on) s.followed - t.key else s.followed + t.key) } }
                }
            }
            item { Zone("Competitions") }
            for (comp in Competition.entries) item(key = "c-${comp.name}") {
                val on = comp in st.competitions
                Toggle(comp.label, null, on, onWord = "Shown", offWord = "Hidden") { edit { s -> s.copy(competitions = if (on) s.competitions - comp else s.competitions + comp) } }
            }
            item { Zone("Big matches") }
            for (sport in listOf(Sport.SOCCER, Sport.NFL)) item(key = "b-${sport.name}") {
                val on = sport in st.big
                val about = if (sport == Sport.SOCCER) "Finals and the great derbies, when you follow a team in football" else "Playoffs and the Super Bowl, when you follow an NFL team"
                Toggle(sport.word, about, on, onWord = "Shown", offWord = "Hidden") { edit { s -> s.copy(big = if (on) s.big - sport else s.big + sport) } }
            }
            item {
                Toggle("Formula 1 practice", "Practice sessions in Olympia, Chronos and the live score", st.practice, onWord = "Shown", offWord = "Hidden") { edit { it.copy(practice = !it.practice) } }
            }
            item {
                Line(
                    "Nothing runs between matches. From five minutes before the start, Zeus checks the score every 45 seconds (cricket every two minutes, F1 every minute) and stops at full time. Quiet hours, 11 PM to 7 AM, are always silent.",
                    Type.small, c.ink3, Modifier.padding(top = Space.xl).widthIn(max = 560.dp),
                )
            }
        }
    }
}

@Composable
private fun Toggle(title: String, about: String?, on: Boolean, onWord: String = "Following", offWord: String = "Follow", onClick: () -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule).clickable(role = Role.Switch, onClick = onClick).padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), if (on) c.ink else c.ink2, maxLines = 1)
            if (about != null) Line(about, Type.small, c.ink3)
        }
        Spacer(Modifier.width(Space.m))
        val word = if (on) onWord else offWord
        if (word.isNotEmpty()) StatusWord(word, if (on) Tone.ACTIVE else Tone.QUIET)
    }
}

// ---- words ----

/** "Real Madrid (home)" or "at Atlético Madrid", from the followed team's side. */
fun opponent(m: Match, t: Team): String {
    if (t.sport == Sport.F1) return m.title
    val mine = listOfNotNull(m.home, m.away).firstOrNull { it.id == t.espnId }
    val other = listOfNotNull(m.home, m.away).firstOrNull { it.id != t.espnId } ?: return m.title
    return if (mine?.home == false) "at ${other.name}" else "v ${other.name}"
}

/** W, L or D for a followed team, as a status word. Null before the result or for F1. */
fun outcome(m: Match, key: String): Pair<String, Tone>? {
    if (m.phase != Phase.DONE) return null
    val t = Teams.byKey(key) ?: return null
    if (t.sport == Sport.CRICKET) {
        val r = m.result ?: return null
        return when { r.startsWith("${t.short} won") -> "Won" to Tone.OK; r.contains(" won") -> "Lost" to Tone.QUIET; else -> "No result" to Tone.QUIET }
    }
    val mine = listOfNotNull(m.home, m.away).firstOrNull { it.id == t.espnId } ?: return null
    val other = listOfNotNull(m.home, m.away).firstOrNull { it.id != t.espnId } ?: return null
    val a = mine.score?.toIntOrNull() ?: return null
    val b = other.score?.toIntOrNull() ?: return null
    return when { a > b -> "Won" to Tone.OK; a < b -> "Lost" to Tone.QUIET; else -> "Draw" to Tone.QUIET }
}

private fun dayWord(ms: Long, now: ZonedDateTime): String {
    val d = Instant.ofEpochMilli(ms).atZone(now.zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(now.toLocalDate(), d)) {
        0L -> "Today"; 1L -> "Tomorrow"; -1L -> "Yesterday"
        else -> Fmt.shortDate(Instant.ofEpochMilli(ms).atZone(now.zone))
    }
}

private fun agoText(at: Long, now: Long): String {
    val m = (now - at) / MatchDay.MIN
    return when { m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
}
