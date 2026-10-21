package dev.phonerecall.setup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.Dest
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.delay

/** What Telete's page shows, as plain data (so it renders in shots). */
data class TeleteView(
    val checks: List<Check> = emptyList(),
    val tests: List<Pair<String, String>> = emptyList(),
    val testWhat: Map<String, String> = emptyMap(),
    val results: Map<String, TestResult> = emptyMap(),
    val running: Set<String> = emptySet(),
    val checking: Boolean = false,
)

class TeleteCalls(
    val refresh: () -> Unit = {},
    val fix: (Check) -> Unit = {},
    val test: (String) -> Unit = {},
    val runAll: () -> Unit = {},
    val askAthena: () -> Unit = {},
    val shakedown: () -> Unit = {},
    val themis: () -> Unit = {},
)

@Composable
fun TeleteScreen(hub: BrainHub, gutter: Dp, wide: Boolean, go: (Dest) -> Unit) {
    val ctx = LocalContext.current
    val tele = remember { TeleteHub.get(ctx) }
    val s by tele.state.collectAsStateWithLifecycle()
    val tests = remember { tele.tests() }
    var shaking by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val fix = rememberTeleteFix(hub, go)
    val calls = TeleteCalls(
        refresh = { tele.refresh(force = true) },
        fix = fix,
        test = { tele.run(it) },
        runAll = { tele.runAll() },
        askAthena = { hub.send(Telete.context(s.checks) + "\n\n" + Telete.ASK); go(Dest.DELPHI) },
        themis = { go(Dest.SETTINGS) },
        shakedown = { shaking = true },
    )
    if (shaking) {
        val shake = remember { ShakedownHub.get(ctx) }
        val sh by shake.state.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(sh.running) { while (sh.running) { now = System.currentTimeMillis(); delay(1_000) } }
        val report = sh.history.firstOrNull { it.id == openId } ?: sh.history.firstOrNull()
        ShakedownContent(
            ShakeView(sh.running, sh.stage, now - sh.startedAt, sh.live, report, report?.let { r -> sh.history.getOrNull(sh.history.indexOf(r) + 1) }, sh.history, mic = sh.mic, micResult = sh.micResult),
            gutter,
            ShakeCalls(
                back = { shaking = false },
                run = { openId = null; shake.start() },
                stop = { shake.stop() },
                // The Telete check's own fix while it isn't ready; else its screen; else back to Telete.
                fix = { ch ->
                    val t = s.checks.firstOrNull { it.id == ch.telete }
                    when {
                        t != null && t.status != Status.OK && t.fix != null -> calls.fix(t)
                        ch.dest != null -> Dest.entries.firstOrNull { it.name == ch.dest }?.let(go)
                        else -> shaking = false
                    }
                },
                answer = { id, yes -> shake.answer(id, yes) },
                open = { openId = it },
                manual = { shake.micTest() },
            ),
        )
        return
    }
    TeleteContent(
        TeleteView(s.checks, tests.map { it.id to it.label }, tests.associate { it.id to it.what }, s.results, s.running, s.checking),
        gutter, wide, calls,
    )
}

/** What a check's fix button does: Android's sheet, the exact system screen, or a Zeus page. Shared with Genesis. */
@Composable
fun rememberTeleteFix(hub: BrainHub, go: (Dest) -> Unit): (Check) -> Unit {
    val ctx = LocalContext.current
    val tele = remember { TeleteHub.get(ctx) }
    val askPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        // Android won't show its sheet again after two refusals: the app's own page is where it changes then.
        if (r.values.any { !it }) TeleteHub.open(ctx, Screen.APP_DETAILS)
        tele.refresh()
    }
    val askGoogle = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { tele.refresh(force = true) }
    return { c ->
        when (val f = c.fix) {
            is Fix.Ask -> askPerms.launch(f.permissions.toTypedArray())
            is Fix.System -> TeleteHub.open(ctx, f.screen)
            is Fix.Open -> Dest.entries.firstOrNull { it.name == f.dest }?.let(go)
            is Fix.Themis -> { dev.phonerecall.settings.ThemisNav.page = f.page; go(Dest.SETTINGS) }
            Fix.GoogleCalendar -> dev.phonerecall.data.GoogleCalendarAuth.ask(ctx, { askGoogle.launch(IntentSenderRequest.Builder(it).build()) }) { tele.refresh(force = true) }
            Fix.IndexNow -> { hub.indexMemoryNow(); tele.refresh() }
            Fix.HeartbeatOn -> { hub.setHeartbeat(true); tele.refresh() }
            Fix.SyncMind -> dev.phonerecall.mind.MindWork.syncNow(ctx)
            null -> {}
        }
    }
}

@Composable
fun TeleteContent(view: TeleteView, gutter: Dp, wide: Boolean, calls: TeleteCalls) {
    val c = Athena.colors
    val left = Telete.left(view.checks)
    val rank = mapOf(Status.MISSING to 0, Status.UNKNOWN to 1, Status.OPTIONAL to 2, Status.OK to 3)
    val groups = view.checks.groupBy { it.group }.mapValues { (_, l) -> l.sortedBy { rank.getValue(it.status) } }
    val labels = view.tests.toMap()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.SETUP.title(), gutter, emblem = Place.SETUP, subtitle = Place.SETUP.subtitle()) {
            TextAction("Refresh", calls.refresh)
            if (left == 0) TextAction(Place.SETTINGS.title(), calls.themis)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item(key = "summary") {
                Column(Modifier.widthIn(max = 640.dp).padding(bottom = Space.s)) {
                    Line(
                        when { view.checks.isEmpty() -> "Looking at the phone…"; left == 0 -> "Everything Zeus needs is in place."; left == 1 -> "One thing left."; else -> "$left things left." },
                        Type.title, c.ink,
                    )
                    Line(
                        if (view.checking) "Asking Lemnos and Google…" else "Each fix opens the exact screen. Tests only read, or undo what they add.",
                        Type.small, c.ink3, Modifier.padding(top = Space.xs),
                    )
                    Spacer(Modifier.height(Space.m))
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                        PrimaryAction("Ask Athena to help", calls.askAthena)
                        TextAction("Run the full shakedown", calls.shakedown, emphasis = true)
                    }
                }
            }
            for ((group, rows) in groups) {
                item(key = "g-$group") {
                    Row(Modifier.padding(top = Space.xl, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        Kicker(group, Modifier.weight(1f), color = if (rows.any { it.status == Status.MISSING }) c.accentInk else c.ink3)
                        val n = rows.count { it.status == Status.MISSING }
                        if (n > 0) Line("$n left", Type.caption, c.ink3)
                    }
                }
                for (ch in rows) item(key = "c-${ch.id}") { CheckRow(ch, view, wide, calls) }
            }
            item(key = "tests") {
                Row(Modifier.padding(top = Space.xxl, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Self-tests", Modifier.weight(1f))
                    TextAction("Run all", calls.runAll, enabled = view.running.isEmpty())
                }
            }
            for ((id, _) in view.tests) item(key = "t-$id") { TestRow(id, labels[id].orEmpty(), view.testWhat[id].orEmpty(), view, calls) }
        }
    }
}

@Composable
private fun CheckRow(ch: Check, view: TeleteView, wide: Boolean, calls: TeleteCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).widthIn(max = 560.dp)) {
                Line(ch.label, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Line(ch.why, Type.small, c.ink3)
                if (ch.detail.isNotBlank()) Line(ch.detail, Type.small, if (ch.status == Status.MISSING) c.alert else c.ink2, Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(Space.m))
            StatusWord(word(ch.status), tone(ch.status), Modifier.padding(top = 4.dp))
        }
        val fixable = ch.status != Status.OK && ch.fix != null
        if (fixable || ch.test != null) {
            Row(Modifier.padding(top = Space.xs).offset(x = -Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                if (fixable) TextAction(ch.fixLabel, { calls.fix(ch) }, Modifier.padding(start = 0.dp), emphasis = true)
                ch.test?.let { t -> TextAction(if (t in view.running) "Testing…" else "Test", { calls.test(t) }, enabled = t !in view.running) }
                ch.test?.let { t -> view.results[t]?.let { r -> ResultLine(r, Modifier.weight(1f)) } }
            }
        }
    }
}

@Composable
private fun TestRow(id: String, label: String, what: String, view: TeleteView, calls: TeleteCalls) {
    val c = Athena.colors
    val r = view.results[id]
    val running = id in view.running
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line(label, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Line(what, Type.small, c.ink3)
            }
            Spacer(Modifier.width(Space.s))
            when {
                running -> StatusWord("Running", Tone.ACTIVE)
                r != null -> StatusWord(if (r.pass) "Pass" else "Fail", if (r.pass) Tone.OK else Tone.ALERT)
            }
            TextAction("Test", { calls.test(id) }, enabled = !running)
        }
        r?.let { ResultLine(it) }
    }
}

@Composable
private fun ResultLine(r: TestResult, modifier: Modifier = Modifier) {
    val c = Athena.colors
    val secs = if (r.ms >= 1000) " · ${"%.1f".format(r.ms / 1000.0)} s" else ""
    Line(r.reason + secs, Type.small, if (r.pass) c.ink2 else c.alert, modifier, maxLines = 2)
}

private fun word(s: Status) = when (s) { Status.OK -> "Ready"; Status.MISSING -> "Missing"; Status.OPTIONAL -> "Optional"; Status.UNKNOWN -> "Unknown" }
private fun tone(s: Status) = when (s) { Status.OK -> Tone.OK; Status.MISSING -> Tone.ALERT; Status.OPTIONAL -> Tone.QUIET; Status.UNKNOWN -> Tone.QUIET }
