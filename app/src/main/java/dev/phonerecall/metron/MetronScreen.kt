package dev.phonerecall.metron

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.LemnosHub
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
import dev.phonerecall.ui.Workers
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import dev.phonerecall.voice.LiveLedgerStore
import java.time.ZonedDateTime

private val Tabular = Type.small.copy(fontFeatureSettings = "tnum")

@Composable
private fun levelColor(pct: Double?): Color = when (Level.of(pct)) {
    Level.HOT -> Athena.colors.alert
    Level.WARN -> Athena.colors.accentInk
    Level.OK -> Athena.colors.ink2
}

/** A flat bar: the track is the hairline colour, the fill ink, saffron from 80%, alert red from 95%. Square ends. */
@Composable
fun UsageBar(pct: Double?, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val c = Athena.colors
    Box(modifier.height(height).background(c.rule)) {
        val f = ((pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
        if (f > 0f) Box(Modifier.fillMaxWidth(f).height(height).background(levelColor(pct)))
    }
}

private fun resetText(w: UsageWindow, now: ZonedDateTime): String? = w.resetsAt?.let { "resets " + MetronWords.reset(it, now).removePrefix("at ") }

/** Pantheon's compact bars: one line per window (5h, 7d), the share used and the reset time. */
@Composable
fun CompactUsage(a: AgentUsage?, now: ZonedDateTime = ZonedDateTime.now(), modifier: Modifier = Modifier) {
    val c = Athena.colors
    val windows = a?.windows.orEmpty().filter { it.label == "5h" || it.label == "7d" }.ifEmpty { a?.windows.orEmpty().take(2) }
    if (windows.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(bottom = Space.s)) {
        for (w in windows) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Kicker(w.label, Modifier.width(30.dp))
            UsageBar(w.usedPct, Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            Line(w.usedPct?.let { "${it.toInt()}%" } ?: "reset", Tabular, levelColor(w.usedPct), Modifier.width(40.dp), maxLines = 1)
            Line(w.resetsAt?.let { MetronWords.reset(it, now).removePrefix("at ") }.orEmpty(), Type.caption, c.ink3, Modifier.width(84.dp), maxLines = 1)
        }
    }
}

/** One worker on Metron: name, model, a status word, then each window full width. */
@Composable
private fun WorkerBlock(name: String, a: AgentUsage?, now: ZonedDateTime) {
    val c = Athena.colors
    val worst = a?.worst?.usedPct
    val held = UsageRouting.blocking(a, now.toEpochSecond()).isNotEmpty()
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line(name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                Line(a?.model ?: a?.note ?: "No reading yet", Type.small, c.ink3, maxLines = 1)
            }
            StatusWord(
                when { a == null || a.windows.isEmpty() -> "Unknown"; held -> "Jobs held"; Level.of(worst) != Level.OK -> "Near limit"; else -> "Room" },
                when { a == null || a.windows.isEmpty() -> Tone.QUIET; held || Level.of(worst) == Level.HOT -> Tone.ALERT; Level.of(worst) == Level.WARN -> Tone.ACTIVE; else -> Tone.OK },
            )
        }
        for (w in a?.windows.orEmpty()) {
            Spacer(Modifier.height(Space.s))
            Row(verticalAlignment = Alignment.Bottom) {
                Line(w.words.replaceFirstChar { it.uppercase() } + " window", Type.small, c.ink2, Modifier.weight(1f), maxLines = 1)
                Line(resetText(w, now).orEmpty(), Type.caption, c.ink3, maxLines = 1)
            }
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                UsageBar(w.usedPct, Modifier.weight(1f), height = 6.dp)
                Spacer(Modifier.width(Space.m))
                Line(w.usedPct?.let { "${it.toInt()}%" } ?: "reset", Type.title.copy(fontFeatureSettings = "tnum"), levelColor(w.usedPct), Modifier.width(52.dp), maxLines = 1)
            }
        }
        a?.updated?.let { u ->
            val mins = ((now.toEpochSecond() - u) / 60).coerceAtLeast(0)
            Line(if (mins < 1) "Read just now" else if (mins < 120) "Read $mins min ago" else "Read ${mins / 60} h ago", Type.caption, c.ink3, Modifier.padding(top = Space.s))
        }
    }
}

@Composable
private fun BudgetBlock(b: Budget) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Line(MetronWords.usd(b.spentMicros), Type.display.copy(fontFeatureSettings = "tnum"), levelColor(b.pct))
            Spacer(Modifier.width(Space.s))
            Line("of $${b.capUsd}", Type.body, c.ink3, Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Line("${b.pct.toInt()}%", Type.title.copy(fontFeatureSettings = "tnum"), levelColor(b.pct), Modifier.padding(bottom = 8.dp))
        }
        UsageBar(b.pct, Modifier.fillMaxWidth().padding(top = Space.s), height = 6.dp)
        Spacer(Modifier.height(Space.m))
        FactRow("Month end", "${MetronWords.usd(b.projectedMicros)} at this pace", if (b.projectedPct >= 100) "Over cap" else null, Tone.ALERT, labelWidth = 112.dp)
        FactRow("Active time", "${b.activeSeconds / 60} min in Managed Agents", labelWidth = 112.dp)
    }
    Kicker("Who spent it", Modifier.padding(top = Space.l, bottom = Space.xs))
    for (l in b.lines) Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(l.label, Type.small.copy(fontWeight = Type.action.fontWeight), if (l.micros > 0) c.ink else c.ink3, maxLines = 1)
            l.note?.let { Line(it.replaceFirstChar { ch -> ch.uppercase() }, Type.caption, c.ink3, maxLines = 1) }
        }
        Line(MetronWords.usd(l.micros), Tabular, if (l.micros > 0) c.ink else c.ink3, Modifier.widthIn(min = 64.dp), maxLines = 1)
    }
    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line("Gemini Live", Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            Line("${b.liveSeconds / 60} min this month. Outside the cap; free tier for now", Type.caption, c.ink3, maxLines = 2)
        }
        Spacer(Modifier.width(Space.m))
        Line(MetronWords.usd(b.liveMicros), Tabular, c.ink3, Modifier.widthIn(min = 64.dp), maxLines = 1)
    }
}

/** Metron's page, from plain data (so it renders in shots). */
@Composable
fun MetronContent(usage: UsageSnapshot?, budget: Budget?, paired: Boolean, wide: Boolean, gutter: Dp, now: ZonedDateTime, onBack: () -> Unit, onRefresh: () -> Unit) {
    val c = Athena.colors
    val plans: @Composable () -> Unit = {
        Kicker("Plans", Modifier.padding(top = Space.s, bottom = Space.xs))
        if (!paired && usage == null) Line("Iris's and Atlas's plan windows show here once this phone is paired with ${Place.VPS.title()}.", Type.small, c.ink3, Modifier.padding(vertical = Space.m))
        else {
            WorkerBlock(Workers.IRIS, usage?.agent("iris"), now)
            WorkerBlock(Workers.ATLAS, usage?.agent("atlas"), now)
        }
        Line(
            "Zeus holds new jobs for a worker above ${UsageRouting.LIMIT_PCT.toInt()}% of any window and offers another worker or waits for the reset. One alert per window at 80%.",
            Type.caption, c.ink3, Modifier.padding(top = Space.s).widthIn(max = 520.dp),
        )
    }
    val spend: @Composable () -> Unit = {
        Kicker("Athena this month", Modifier.padding(top = if (wide) Space.s else Space.xl, bottom = Space.xs))
        budget?.let { BudgetBlock(it) }
        Line("One cap covers Delphi, the background mind, the circle and Metis. Alerts at 80% and 95%, once each.", Type.caption, c.ink3, Modifier.padding(top = Space.s).widthIn(max = 520.dp))
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.USAGE.title(), gutter, emblem = Place.USAGE, subtitle = Place.USAGE.subtitle()) {
            if (paired) TextAction("Refresh", onRefresh)
            TextAction(Place.SETTINGS.title(), onBack)
        }
        if (wide) Row(Modifier.fillMaxSize().padding(horizontal = gutter)) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { plans() } }
            Spacer(Modifier.width(Space.xxl))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { spend() } }
        } else LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item { plans() }
            item { spend() }
        }
    }
}

@Composable
fun MetronScreen(hub: LemnosHub, circle: Map<String, Long>, wide: Boolean, gutter: Dp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val metron = remember { MetronCore.get(ctx) }
    val core = remember { BrainCore.get(ctx) }
    val s by hub.state.collectAsStateWithLifecycle()
    val usage by metron.usage.collectAsStateWithLifecycle()
    val spend by core.spend.state.collectAsStateWithLifecycle()
    val paired = s.pairing == DeviceKeys.State.PAIRED
    LaunchedEffect(paired) { if (paired) hub.runAsync("Metron") { metron.fetch() } }
    androidx.activity.compose.BackHandler(onBack = onBack)
    val now = ZonedDateTime.now()
    val budget = remember(spend, circle, usage) { MetronBudget.of(spend, circle, runCatching { LiveLedgerStore(ctx).load(now.toLocalDate()) }.getOrNull(), now) }
    MetronContent(usage, budget, paired, wide, gutter, now, onBack) { hub.runAsync("Metron") { metron.fetch() } }
}

/** Themis's row into Metron: the fullest window and the cap in one line. */
fun metronLine(usage: UsageSnapshot?, b: Budget?): String {
    val worst = usage?.agents.orEmpty().mapNotNull { a -> a.worst?.let { a.name to it } }.maxByOrNull { it.second.usedPct ?: 0.0 }
    val plans = worst?.let { "${it.first} ${it.second.usedPct!!.toInt()}% of the ${it.second.words} window" } ?: "Plan windows once paired"
    return plans + b?.let { ", Athena ${it.pct.toInt()}% of her cap" }.orEmpty()
}
