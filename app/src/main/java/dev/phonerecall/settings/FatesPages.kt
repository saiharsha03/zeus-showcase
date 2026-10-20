package dev.phonerecall.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.phonerecall.brain.AttentionBudget
import dev.phonerecall.brain.AttentionDay
import dev.phonerecall.brain.DigestItem
import dev.phonerecall.brain.Fatigue
import dev.phonerecall.brain.KindStats
import dev.phonerecall.brain.Outbound
import dev.phonerecall.brain.OutboundRow
import dev.phonerecall.brain.ProposalKind
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

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** What the Auto-approve page shows: the list, each kind's last 30 days, and the attention budget. */
class AutoApproveView(
    val on: Set<ProposalKind> = emptySet(),
    val stats: Map<ProposalKind, Pair<KindStats, Long?>> = emptyMap(),
    val budget: AttentionBudget = AttentionBudget(),
    val today: AttentionDay = AttentionDay(),
    val waiting: List<DigestItem> = emptyList(),
)

class AutoApproveCalls(
    val toggle: (ProposalKind) -> Unit = {},
    val budget: (AttentionBudget) -> Unit = {},
    val back: () -> Unit = {},
)

/** "16 confirmed, 0 turned down, 1 edited, 2 ignored in 30 days". */
fun statsLine(s: KindStats): String =
    if (s.total == 0) "Nothing proposed in 30 days" else "${s.confirms} confirmed, ${s.rejects} turned down, ${s.edits} edited, ${s.ignores} ignored in 30 days"

@Composable
private fun Row2(title: String, about: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            if (about != null) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 520.dp))
        }
        Spacer(Modifier.width(Space.m))
        trailing()
    }
}

/**
 * Themis → Auto-approve: each eligible kind with its last 30 days (the fatigue meter), quiet kinds with
 * when they wake, the kinds that always ask, then the attention budget and what waits for the brief.
 */
@Composable
fun AutoApproveContent(gutter: androidx.compose.ui.unit.Dp, v: AutoApproveView, calls: AutoApproveCalls, zone: ZoneId = ZoneId.systemDefault()) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Auto-approve", gutter, kicker = Place.SETTINGS.title()) { TextAction("Back", calls.back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line(
                    "Kinds on this list run without asking, then show as done automatically with Undo. Empty unless you add one. " +
                        "After ${Fatigue.AUTO_CONFIRMS} confirms and no rejections in 30 days, the Fates ask once whether to add a kind; never more than once a week.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
            }
            for (k in ProposalKind.autoCandidates) item(key = "auto-${k.wire}") {
                val (s, quiet) = v.stats[k] ?: (KindStats() to null)
                val about = statsLine(s) + (quiet?.let { ". Quiet until ${DAY.format(Instant.ofEpochMilli(it).atZone(zone))}: you turned down ${Fatigue.QUIET_REJECTS} lately" } ?: "")
                val on = k in v.on
                Row2(k.label, about, onClick = { calls.toggle(k) }) {
                    StatusWord(if (on) "Automatic" else if (quiet != null) "Quiet" else "Ask", if (on) Tone.ACTIVE else if (quiet != null) Tone.ALERT else Tone.QUIET)
                }
            }
            item { Kicker("Always ask", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            for (k in ProposalKind.entries.filter { it !in ProposalKind.autoCandidates && Fatigue.tracked(it) && v.stats[it]?.first?.total ?: 0 > 0 }) item(key = "ask-${k.wire}") {
                val (s, quiet) = v.stats[k] ?: (KindStats() to null)
                Row2(k.label, statsLine(s)) { StatusWord(if (quiet != null) "Quiet" else "Always ask", if (quiet != null) Tone.ALERT else Tone.QUIET) }
            }
            item { Row2("Money, messages, deletes, the server", "Never automatic, whatever the numbers say") { StatusWord("Never", Tone.QUIET) } }

            item { Kicker("Attention", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            item {
                Line(
                    "A ceiling, not a target: urgent things (due today, safety, about right now) always show. Nice-to-haves past it wait for the morning brief. A quiet day stays quiet.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
            }
            item {
                Row2("Cards a day", "Proactive cards on Olympus. ${v.today.cards.size} shown today", onClick = { calls.budget(v.budget.copy(cards = AttentionBudget.next(AttentionBudget.CARD_STEPS, v.budget.cards))) }) {
                    StatusWord("${v.budget.cards}", Tone.ACTIVE)
                }
            }
            item {
                Row2("Notifications a day", "${v.today.notified} sent today", onClick = { calls.budget(v.budget.copy(notifications = AttentionBudget.next(AttentionBudget.NOTIFICATION_STEPS, v.budget.notifications))) }) {
                    StatusWord("${v.budget.notifications}", Tone.ACTIVE)
                }
            }
            item {
                Row2("Waiting for the brief", if (v.waiting.isEmpty()) "Nothing. Today fit in the budget." else v.waiting.joinToString(", ") { it.title }) {
                    StatusWord("${v.waiting.size}", if (v.waiting.isEmpty()) Tone.QUIET else Tone.ACTIVE)
                }
            }
        }
    }
}

/**
 * Themis → What left the phone: every request Zeus's own code made in the last 7 days, newest first,
 * by day. Host, what it was for, and which kinds of data were in it. Never the content.
 */
@Composable
fun OutboundContent(gutter: androidx.compose.ui.unit.Dp, rows: List<OutboundRow>, back: () -> Unit, zone: ZoneId = ZoneId.systemDefault()) {
    val c = Athena.colors
    val days = remember(rows) { rows.groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toSortedMap(compareByDescending { it }) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("What left the phone", gutter, kicker = Place.SETTINGS.title()) { TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line(
                    "Every request Zeus made through NetGate in the last 7 days: the host, what it was for, and what kinds of data it held. The content itself is never kept. " +
                        "Wording that would carry a person's name stays on the phone.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
            }
            if (rows.isEmpty()) item { Line("Nothing in the last 7 days.", Type.body, c.ink2, Modifier.padding(top = Space.l)) }
            for ((day, list) in days) {
                item(key = "d-$day") { Kicker(DAY.format(day), Modifier.padding(top = Space.l, bottom = Space.xs)) }
                for ((i, r) in list.withIndex()) item(key = "r-$day-$i") {
                    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Line(r.purpose, Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
                            Line((if (r.count > 1) "${r.count} times, last " else "") + TIME.format(Instant.ofEpochMilli(r.at).atZone(zone)), Type.small, c.ink3, maxLines = 1)
                        }
                        Line(r.host, Type.caption, c.ink3, maxLines = 1)
                        Line(Outbound.describe(r.fields), Type.small, if (dev.phonerecall.brain.FieldCat.NAMES in r.fields) c.accentInk else c.ink2)
                    }
                }
            }
        }
    }
}
