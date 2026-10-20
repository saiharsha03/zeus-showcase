package dev.phonerecall.setup

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Shakedown page shows, as plain data (so it renders in shots). [report] is the one open; [previous] the run before it. */
data class ShakeView(
    val running: Boolean = false,
    val stage: String = "",
    val elapsedMs: Long = 0,
    val live: List<ShakeResult> = emptyList(),
    val report: ShakeReport? = null,
    val previous: ShakeReport? = null,
    val history: List<ShakeReport> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
    /** The tap-only mic test: running now, and its last outcome when the open report has none. */
    val mic: Boolean = false,
    val micResult: ShakeResult? = null,
)

class ShakeCalls(
    val back: () -> Unit = {},
    val run: () -> Unit = {},
    val stop: () -> Unit = {},
    val fix: (ShakeCheck) -> Unit = {},
    val answer: (String, Boolean) -> Unit = { _, _ -> },
    val open: (String) -> Unit = {},
    val manual: (String) -> Unit = {},
)

@Composable
fun ShakedownContent(view: ShakeView, gutter: Dp, calls: ShakeCalls) {
    val c = Athena.colors
    val results = if (view.running) view.live else view.report?.results.orEmpty()
    val byId = results.associateBy { it.id }.let { m -> view.micResult?.takeIf { it.id !in m && !view.running }?.let { m + (it.id to it) } ?: m }
    val changes = view.report?.let { Shakedown.compare(it, view.previous) }.orEmpty()
    val rank = mapOf(Verdict.FAIL to 0, Verdict.SKIP to 1, Verdict.PASS to 2)
    val checks = Shakedown.CHECKS.filter { !it.notify }
    val groups = checks.filter { if (it.manual) !view.running else it.id in byId || view.running }.groupBy { it.feature }
        .mapValues { (_, l) -> l.sortedBy { byId[it.id]?.let { r -> rank.getValue(r.verdict) } ?: 3 } }
        .entries.sortedBy { (_, l) -> l.minOf { byId[it.id]?.let { r -> rank.getValue(r.verdict) } ?: 3 } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Shakedown", gutter, kicker = Place.SETUP.title(), subtitle = "Everything Zeus does, end to end") {
            TextAction("Back", calls.back)
            if (view.running) TextAction("Stop", calls.stop) else TextAction(if (view.report == null) "Run" else "Run again", calls.run, emphasis = true)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item(key = "summary") { Summary(view, results) }
            if (!view.running && view.report != null) {
                item(key = "k-notify") { Kicker("Did they appear?", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
                for (ch in TestNotify.CHANNELS) item(key = "n-${ch.check}") { NotifyRow(ch, byId[ch.check], view.report.seen[ch.check], calls) }
            } else if (view.running) {
                item(key = "k-notify") { Kicker("Notifications", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
                for (ch in TestNotify.CHANNELS) item(key = "n-${ch.check}") { NotifyRow(ch, byId[ch.check], null, calls) }
            }
            for ((feature, rows) in groups) {
                item(key = "g-$feature") {
                    Row(Modifier.padding(top = Space.xl, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                        val failed = rows.count { byId[it.id]?.verdict == Verdict.FAIL }
                        Kicker(feature, Modifier.weight(1f), color = if (failed > 0) c.accentInk else c.ink3)
                        if (failed > 0) Line("$failed failed", Type.caption, c.ink3)
                    }
                }
                for (ch in rows) item(key = "c-${ch.id}") { CheckRow(ch, byId[ch.id], changes[ch.id], view.running, view.mic, calls) }
            }
            if (!view.running && view.history.size > 1) {
                item(key = "k-history") { Kicker("Earlier runs", Modifier.padding(top = Space.xxl, bottom = Space.xs)) }
                for (r in view.history) item(key = "h-${r.id}") { HistoryRow(r, r.id == view.report?.id, view.zone, calls) }
            }
        }
    }
}


@Composable
private fun Summary(view: ShakeView, results: List<ShakeResult>) {
    val c = Athena.colors
    Column(Modifier.widthIn(max = 640.dp).padding(bottom = Space.s)) {
        val report = view.report
        when {
            view.running -> {
                Line("Running · ${results.size} of ${Shakedown.CHECKS.size} · ${clock(view.elapsedMs)}", Type.title, c.ink)
                Line(if (view.stage.isEmpty()) "Athena has the list; the phone runs its own checks" else "Just did: ${view.stage}", Type.small, c.ink3, Modifier.padding(top = Space.xs))
                Line("Test artifacts are removed at the end, even if you stop it. At most ${ShakeRunner.cents(Shakedown.CAP_CENTS)} and ${Shakedown.DEADLINE_MS / 60_000} minutes.", Type.small, c.ink3, Modifier.padding(top = Space.xs))
            }
            report == null -> {
                Line("One run through every feature.", Type.title, c.ink)
                Line("Athena takes the reading and agent checks, the phone does the rest, and four test notifications ask whether you saw them. Nothing is sent to anyone, booked or bought; at most ${ShakeRunner.cents(Shakedown.CAP_CENTS)}.", Type.small, c.ink3, Modifier.padding(top = Space.xs))
            }
            else -> {
                Line(report.summary, Type.title, c.ink)
                val p = report.results.count { it.verdict == Verdict.PASS }
                val f = report.results.count { it.verdict == Verdict.FAIL }
                val s = report.results.count { it.verdict == Verdict.SKIP }
                Line("$p passed · $f failed · $s skipped · ${ShakeRunner.cents(report.spentCents)} · ${clock(report.ms)}", Type.small, c.ink3, Modifier.padding(top = Space.xs))
                report.aborted?.let { Line(it, Type.small, c.alert, Modifier.padding(top = 2.dp)) }
                report.cleanup.forEach { Line("Not cleaned up: $it", Type.small, c.alert, Modifier.padding(top = 2.dp)) }
            }
        }
    }
}

@Composable
private fun CheckRow(ch: ShakeCheck, r: ShakeResult?, change: Shakedown.Change?, running: Boolean, mic: Boolean, calls: ShakeCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).widthIn(max = 560.dp)) {
                Line(ch.label, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                if (r != null && r.detail.isNotBlank()) Line(r.detail, Type.small, if (r.verdict == Verdict.FAIL) c.alert else c.ink2, maxLines = 3)
                r?.claim?.let { Line("Athena: $it", Type.small, c.ink3, Modifier.padding(top = 2.dp), maxLines = 2) }
                change?.let { Line(if (it == Shakedown.Change.FIXED) "Fixed since the last run" else "Worked in the last run", Type.caption, if (it == Shakedown.Change.FIXED) c.ok else c.alert, Modifier.padding(top = 2.dp)) }
            }
            Spacer(Modifier.width(Space.m))
            when {
                r != null -> StatusWord(word(r.verdict), tone(r.verdict), Modifier.padding(top = 4.dp))
                running -> StatusWord("Waiting", Tone.QUIET, Modifier.padding(top = 4.dp))
            }
        }
        val fixable = r != null && r.verdict != Verdict.PASS && (ch.telete != null || ch.dest != null)
        if (ch.manual || fixable) {
            Row(Modifier.padding(top = Space.xs).offset(x = -Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (ch.manual) TextAction(if (mic) "Listening… say something" else if (r == null) "Start mic test" else "Test again", { calls.manual(ch.id) }, emphasis = !fixable, enabled = !mic)
                if (fixable) TextAction("Fix", { calls.fix(ch) }, emphasis = true)
            }
        }
    }
}

@Composable
private fun NotifyRow(ch: TestChannel, r: ShakeResult?, seen: Boolean?, calls: ShakeCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).widthIn(max = 560.dp)) {
                Line(ch.title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Line(if (r?.verdict == Verdict.FAIL) r.detail else "On ${ch.name}", Type.small, if (r?.verdict == Verdict.FAIL) c.alert else c.ink3, maxLines = 2)
            }
            Spacer(Modifier.width(Space.m))
            when {
                r?.verdict == Verdict.FAIL -> StatusWord("Fail", Tone.ALERT, Modifier.padding(top = 4.dp))
                seen == true -> StatusWord("Seen", Tone.OK, Modifier.padding(top = 4.dp))
                seen == false -> StatusWord("Not seen", Tone.ALERT, Modifier.padding(top = 4.dp))
                r != null -> StatusWord("Posted", Tone.ACTIVE, Modifier.padding(top = 4.dp))
                else -> StatusWord("Waiting", Tone.QUIET, Modifier.padding(top = 4.dp))
            }
        }
        if (r?.verdict == Verdict.PASS) {
            Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Chip("Yes", seen == true) { calls.answer(ch.check, true) }
                Chip("No", seen == false) { calls.answer(ch.check, false) }
                if (seen == false) TextAction("Fix", { Shakedown.check(ch.check)?.let(calls.fix) }, emphasis = true)
            }
        }
    }
}

@Composable
private fun HistoryRow(r: ShakeReport, open: Boolean, zone: ZoneId, calls: ShakeCalls) {
    val c = Athena.colors
    val p = r.results.count { it.verdict == Verdict.PASS }
    Row(
        Modifier.fillMaxWidth().topRule(c.rule).clickable(role = Role.Button) { calls.open(r.id) }.padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(WHEN.format(Instant.ofEpochMilli(r.at).atZone(zone)), Type.body, c.ink, maxLines = 1)
            Line("$p of ${r.results.size} passed" + (r.aborted?.let { " · $it" } ?: ""), Type.small, c.ink3, maxLines = 1)
        }
        if (open) StatusWord("Open", Tone.ACTIVE)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (on) c.accent else c.surface)
            .border(1.dp, if (on) c.accent else c.edge, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) { Line(label, Type.action, if (on) c.onAccent else c.ink, maxLines = 1) }
}

private val WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

private fun clock(ms: Long): String { val s = ms / 1000; return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s" }
private fun word(v: Verdict) = when (v) { Verdict.PASS -> "Pass"; Verdict.FAIL -> "Fail"; Verdict.SKIP -> "Skip" }
private fun tone(v: Verdict) = when (v) { Verdict.PASS -> Tone.OK; Verdict.FAIL -> Tone.ALERT; Verdict.SKIP -> Tone.QUIET }
