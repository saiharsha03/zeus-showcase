package dev.phonerecall.oneiroi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val WHEN = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)
private fun whenText(ms: Long) = WHEN.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun cents(micros: Long) = "$" + String.format(Locale.US, "%.2f", micros / 1_000_000.0)

private fun statusTone(s: String) = if (FactStatus.of(s) == FactStatus.CONFIRMED) Tone.OK else Tone.QUIET

/** His taps on Memory care. */
class MemoryCareCalls(
    val forget: (Int) -> Unit = {},
    val confirm: (Int) -> Unit = {},
    val decide: (String, Boolean) -> Unit = { _, _ -> },
    val removeRef: (String) -> Unit = {},
    val back: () -> Unit = {},
)

@Composable
fun MemoryCarePage(hub: BrainHub, gutter: Dp, back: () -> Unit) {
    val s by hub.oneiroi.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { hub.loadMemoryCare() }
    MemoryCareContent(
        s, gutter, wide = false,
        MemoryCareCalls(forget = hub::forgetFact, confirm = hub::confirmFact, decide = hub::decideNudge, removeRef = hub::removeRef, back = back),
    )
}

/** One fact: its number, the words, where it came from, how sure. */
@Composable
private fun FactItem(id: Int, text: String, status: String, source: String, actions: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
        Line("#$id", Type.caption, c.ink3, Modifier.width(44.dp).padding(top = 3.dp))
        Column(Modifier.weight(1f)) {
            Line(text, Type.body, c.ink)
            Line(source, Type.caption, c.ink3, Modifier.padding(top = 2.dp))
            actions()
        }
        Spacer(Modifier.width(Space.m))
        StatusWord(status, statusTone(status), Modifier.padding(top = 4.dp))
    }
}

/**
 * Themis → Memory care (Oneiroi). Cover: one column. Inner: what she knows on the left, the night's
 * work, the proposals and the names on the right.
 */
@Composable
fun MemoryCareContent(s: MemoryCare, gutter: Dp, wide: Boolean, calls: MemoryCareCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.DREAMS.title(), gutter, kicker = Place.SETTINGS.title(), subtitle = Place.DREAMS.subtitle()) { TextAction("Back", calls.back) }
        val intro: @Composable () -> Unit = {
            Line(
                "What Athena knows, one fact per number. To fix one, tell her in ${Place.ORACLE.title()}: \"#12 is wrong, it's ...\" or \"forget #12\". A fix replaces the old fact; the old one stays in the history.",
                Type.small, c.ink2, Modifier.padding(bottom = Space.m).widthIn(max = 560.dp),
            )
        }
        val knows: @Composable () -> Unit = { Knows(s, calls) }
        val side: @Composable () -> Unit = { Column { Night(s); Proposals(s, calls); Names(s); Runs(s) } }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                LazyColumn(Modifier.weight(1.2f), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { intro() }; item { knows() } }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { side() } }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
                item { intro() }
                item { Night(s) }
                item { Proposals(s, calls) }
                item { knows() }
                item { Names(s) }
                item { Runs(s) }
            }
        }
    }
}

@Composable
private fun Section(text: String) = Kicker(text, Modifier.padding(top = Space.xl, bottom = Space.xs))

@Composable
private fun Knows(s: MemoryCare, calls: MemoryCareCalls) {
    val c = Athena.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(top = Space.l, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Kicker("What she knows", Modifier.weight(1f))
            Line("${s.live.size} live, ${s.replaced} replaced", Type.caption, c.ink3)
        }
        if (s.loaded && s.live.isEmpty()) Line("Nothing yet. She starts keeping facts after your next chat.", Type.body, c.ink2, Modifier.padding(vertical = Space.m))
        for ((topic, group) in s.live.groupBy { it.topic }) {
            Line(topic.replaceFirstChar { it.uppercase() }, Type.title, c.ink, Modifier.padding(top = Space.l, bottom = Space.xs))
            for (f in group) FactItem(f.id, f.text, f.status, f.source) {
                Row(Modifier.padding(top = Space.xs)) {
                    if (f.statusOf != FactStatus.CONFIRMED) TextAction("It's right", { calls.confirm(f.id) })
                    TextAction("Forget", { calls.forget(f.id) })
                }
            }
        }
    }
}

@Composable
private fun Night(s: MemoryCare) {
    val c = Athena.colors
    Column {
        Section("Last night")
        val n = s.dreams.firstOrNull()
        when {
            n == null -> Line("The first dream runs at 3 AM, as Athena's scheduled run.", Type.body, c.ink2)
            n.lines.isEmpty() -> Line("Nothing to tidy" + (n.skipped?.let { ": $it." } ?: "."), Type.body, c.ink2)
            else -> Column { for (l in n.lines.take(8)) Line(l, Type.body, c.ink, Modifier.topRule(c.rule).fillMaxWidth().padding(vertical = Space.s)) }
        }
        n?.let { Line(whenText(it.at) + ", " + cents(it.micros) + " of " + cents(Dreamer.CAP_MICROS), Type.caption, c.ink3, Modifier.padding(top = Space.xs)) }
    }
}

@Composable
private fun Proposals(s: MemoryCare, calls: MemoryCareCalls) {
    val c = Athena.colors
    if (s.nudges.isEmpty()) return
    Column {
        Section("Dropped balls")
        for (n in s.nudges.take(10)) Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
            Row(verticalAlignment = Alignment.Top) {
                Line(n.text, Type.body, c.ink, Modifier.weight(1f))
                Spacer(Modifier.width(Space.m))
                StatusWord(if (n.kind == Nudge.BACKFILL) "Not saved" else "Dropped", Tone.ACTIVE, Modifier.padding(top = 4.dp))
            }
            Line("\"${n.evidence}\"", Type.caption, c.ink3, Modifier.padding(top = 2.dp), maxLines = 2)
            Row(Modifier.padding(top = Space.xs)) {
                TextAction(if (n.kind == Nudge.BACKFILL) "Keep it" else "Make a task", { calls.decide(n.id, true) }, emphasis = true)
                TextAction("Dismiss", { calls.decide(n.id, false) })
            }
        }
    }
}

@Composable
private fun Names(s: MemoryCare) {
    val c = Athena.colors
    Column {
        Section("Names she knows")
        if (s.refs.isEmpty()) Line("When you call something by a nickname, she files it here.", Type.small, c.ink2)
        for (r in s.refs.take(40)) FactRow(r.term, r.meaning, labelWidth = 104.dp)
    }
}

@Composable
private fun Runs(s: MemoryCare) {
    Column {
        Section("How it runs")
        FactRow("Reflect", "After every chat and long job. ${s.reflected} so far.", labelWidth = 104.dp)
        FactRow("Dream", "Athena's 3 AM run, at most ${cents(Dreamer.CAP_MICROS)} a night. ${cents(s.spentTonightMicros)} tonight.", labelWidth = 104.dp)
        FactRow("Sweep", "Every two days. Proposals only, never acts." + (s.lastSweep?.let { " Last ${whenText(it)}." } ?: ""), labelWidth = 104.dp)
        FactRow("Iris", if (s.irisQueued == 0) "Nothing waiting for her." else "${s.irisQueued} notes waiting until her link is set up.", labelWidth = 104.dp)
    }
}

/** The /memory answer in Delphi: a title, the live facts with their numbers, one line on what to do. */
@Composable
fun MemoryAnswerCard(a: MemoryAnswer, modifier: Modifier = Modifier) {
    val c = Athena.colors
    Column(modifier.padding(start = 40.dp)) {
        Kicker(Place.DREAMS.title())
        Line(a.title, Type.title, c.ink, Modifier.padding(top = 2.dp, bottom = Space.xs))
        for (f in a.facts) FactItem(f.id, f.text, f.status, f.source)
        a.note?.let { Line(it, Type.small, c.ink2, Modifier.topRule(c.rule).fillMaxWidth().padding(top = Space.s)) }
    }
}
