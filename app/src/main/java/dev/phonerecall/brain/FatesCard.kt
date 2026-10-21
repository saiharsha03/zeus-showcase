package dev.phonerecall.brain

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.topRule
import java.time.ZoneId

/** What a Fates card can ask for. Every call lands in BrainHub, which goes through Fates. */
class FatesCallbacks(
    /** Confirms what the card showed (`seen`); the callback gets the reason when it was refused. */
    val confirm: (Long, Binding, (String?) -> Unit) -> Unit = { _, _, _ -> },
    /** Binds what the card shows now. */
    val bind: (Proposal) -> Binding = { Bindings.of(it, emptyList(), System.currentTimeMillis()) },
    val reject: (Long) -> Unit = {},
    val undo: (Long) -> Unit = {},
    val toggle: (Long, Int) -> Unit = { _, _ -> },
    /** Saves an edit; the callback gets the reason when it was refused, or null. */
    val edit: (Long, Int, Map<String, String>, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    val pickCalendar: (Long, Int, CalendarChoice) -> Unit = { _, _, _ -> },
    val allowCalendar: (Long) -> Unit = {},
    /** Birthday cards: Google's one-time consent for Google Calendar events. */
    val allowGoogleCalendar: (Long) -> Unit = {},
    /** Birthday cards: reject and teach. */
    val notBirthday: (Long) -> Unit = {},
    /** True on the one card a day that asks "Was this useful?". */
    val ask: (Proposal) -> Boolean = { false },
    val useful: (ProposalKind, Boolean) -> Unit = { _, _ -> },
)

/** The words on a card for each state. Uppercase coloured text, never a badge. */
fun statusWord(p: Proposal): Pair<String, Tone> = when (p.status) {
    ProposalStatus.PENDING -> (if (p.needs != null) "Needs a choice" else "Needs approval") to Tone.ACTIVE
    ProposalStatus.RUNNING -> "Running" to Tone.ACTIVE
    ProposalStatus.CONFIRMED -> (if (p.auto) "Done automatically" else "Done") to Tone.OK
    ProposalStatus.REJECTED -> "Rejected" to Tone.QUIET
    ProposalStatus.EXPIRED -> "Expired" to Tone.QUIET
    ProposalStatus.FAILED -> "Failed" to Tone.ALERT
    ProposalStatus.UNDONE -> "Undone" to Tone.QUIET
}

/** The fields Edit offers per kind, with their plain labels. complete_task has nothing to edit. */
private fun editable(kind: ProposalKind): List<Pair<String, String>> = when (kind) {
    ProposalKind.SET_ALARM -> listOf("hour" to "Hour (0 to 23)", "minute" to "Minute", "label" to "Label")
    ProposalKind.SET_TIMER, ProposalKind.SHORT_TIMER -> listOf("seconds" to "Seconds", "label" to "Label")
    ProposalKind.ADD_REMINDER -> listOf("at" to "When (2026-10-20T08:00)", "text" to "Says")
    ProposalKind.ADD_TASK -> listOf(
        "title" to "Task", "list" to "List", "due" to "Due (2026-10-20)", "context" to "Context (calls, errands, home...)",
        "waiting_on" to "Waiting on", "follow_up" to "Check back (2026-10-23)",
    )
    ProposalKind.COMPLETE_TASK -> emptyList()
    ProposalKind.ADD_CALENDAR_EVENT -> listOf("title" to "Event", "start" to "Starts", "end" to "Ends")
    ProposalKind.CREATE_CARD -> listOf("title" to "Title", "body" to "Says", "hours" to "Hours it stays")
    ProposalKind.SET_RELATION -> listOf("relation" to "Relation (mother, father, sister, brother, manager, friend...)")
    ProposalKind.ADD_BIRTHDAY -> listOf("name" to "Name", "date" to "Date (10-19)")
    ProposalKind.ADD_TO_LIST -> listOf("items" to "Items, separated by commas")
    ProposalKind.PLAY_MUSIC, ProposalKind.MUSIC_WHILE_DRIVING -> listOf("query" to "What to play")
    ProposalKind.MUSIC_CONTROL -> listOf("action" to "pause, resume, next or previous")
    ProposalKind.HOME_CONTROL, ProposalKind.LIGHTS -> listOf("action" to "on, off, brightness, color_temp or color", "value" to "Value (40, 2700, blue)")
    ProposalKind.PLACE_CALL -> listOf("via" to "whatsapp_video, whatsapp_voice or phone")
    ProposalKind.BOOKMARK_FOR -> listOf("note" to "What to tell them")
    ProposalKind.ADD_AUTO_APPROVE, ProposalKind.SET_SCHEDULE, ProposalKind.SET_TRIPS, ProposalKind.SWITCH_MODEL, ProposalKind.TASK_BUDGET, ProposalKind.LEARN_SHORTCUT, ProposalKind.IDEA, ProposalKind.BUILD_APP, ProposalKind.SET_ROUTINE -> emptyList()
}

/** A schedule prompt longer than this opens in full before Confirm. */
internal const val PROMPT_PEEK = 240

/**
 * A proposal as a block on the brain tint: what it will do as labelled values, then Confirm, Edit,
 * Reject. A plan shows one checkable row per step. `compact` is the inbox history form: title, state
 * and outcome only.
 */
@Composable
fun FatesCard(
    p: Proposal,
    cb: FatesCallbacks,
    modifier: Modifier = Modifier,
    calendars: List<CalendarChoice> = emptyList(),
    compact: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    val pending = p.status == ProposalStatus.PENDING
    var editing by remember(p.id) { mutableStateOf<Int?>(null) }
    var refused by remember(p.id) { mutableStateOf<String?>(null) }
    // What he is looking at, bound when it is drawn: Confirm carries it, and refuses if it no longer holds.
    var seen by remember(p) { mutableStateOf(cb.bind(p)) }
    var changed by remember(p.id) { mutableStateOf<String?>(null) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var rated by remember(p.id) { mutableStateOf(false) }
    val (word, tone) = if (changed != null && pending) "Changed" to Tone.ALERT else statusWord(p)

    Column(
        modifier.fillMaxWidth().clip(shape).background(c.block(Tint.BRAIN, strong = pending)).border(1.dp, c.edge, shape).padding(Space.blockPad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p.proposer == "Athena") { dev.phonerecall.ui.AthenaAvatar(20.dp); Spacer(Modifier.width(Space.s)) }
            Kicker(if (p.steps.size > 1) "Plan from ${p.proposer}" else "From ${p.proposer}", Modifier.weight(1f))
            StatusWord(word, tone)
        }
        Spacer(Modifier.height(Space.s))
        Line(p.title, Type.title, c.ink)
        if (compact) {
            p.outcome?.let { Spacer(Modifier.height(Space.xs)); Line(it, Type.small, c.ink2) }
            return@Column
        }
        Spacer(Modifier.height(Space.s))

        // A schedule's prompt is what deploys: shown whole (a long one opens before Confirm), the text the fingerprint binds.
        val prompt = p.steps.firstNotNullOfOrNull { s -> Bindings.schedulePrompt(s)?.takeIf { s.selected } }
        val long = prompt != null && prompt.length > PROMPT_PEEK
        var whole by remember(p) { mutableStateOf(false) }
        // Athena's offers (learned shortcuts, ideas): one row each, answered on its own.
        val answerCard = p.steps.all { it.kind == ProposalKind.LEARN_SHORTCUT || it.kind == ProposalKind.IDEA }
        if (answerCard && pending) AnswerRows(p, ctx)
        else if (p.steps.size == 1) {
            for ((label, value) in Validate.facts(p.steps[0], zone)) if (label != "Each run") FactRow(label, value)
        } else {
            p.steps.forEachIndexed { i, s ->
                StepRow(s, enabled = pending, onToggle = { cb.toggle(p.id, i) }, onEdit = if (pending && editable(s.kind).isNotEmpty()) ({ editing = i }) else null)
            }
        }
        if (prompt != null) {
            FactRow("Each run", if (!long || whole) prompt else prompt.take(PROMPT_PEEK).trimEnd() + "…")
            if (long) TextAction(if (whole) "Show less" else "Show all ${prompt.length} characters", { whole = !whole; if (whole) seen = cb.bind(p) })
        }
        if (pending) {
            FactRow("Tier", when { p.tier >= 3 -> "3, outward. Always asks."; p.tier == 1 -> "1, Athena's own notes"; else -> "2, your phone, reversible" })
            FactRow("Waits until", Validate.instantText(p.expiresAt, zone))
        }

        // A missing choice: the card asks for it here, and Confirm waits.
        if (pending) {
            val needIndex = p.steps.indexOfFirst { it.selected && it.needs != null }
            when (p.needs) {
                "calendar" -> {
                    Spacer(Modifier.height(Space.s))
                    Kicker("Which calendar")
                    if (calendars.isEmpty()) Line("No Google calendar Zeus can write to.", Type.small, c.ink2)
                    for (cal in calendars) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).topRule(c.rule).clickable(role = Role.Button) { cb.pickCalendar(p.id, needIndex, cal) }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Line(cal.name, Type.body, c.ink, maxLines = 1)
                                Line(cal.account, Type.small, c.ink3, maxLines = 1)
                            }
                        }
                    }
                }
                "calendar_permission" -> {
                    Spacer(Modifier.height(Space.s))
                    Line("Zeus needs your permission to add events to your calendar.", Type.small, c.ink2)
                    TextAction("Allow calendar", { cb.allowCalendar(p.id) }, emphasis = true)
                }
                "google_calendar_auth" -> {
                    Spacer(Modifier.height(Space.s))
                    Line("Zeus needs your permission to add birthdays to Google Calendar.", Type.small, c.ink2)
                    TextAction("Allow Google Calendar", { cb.allowGoogleCalendar(p.id) }, emphasis = true)
                }
            }
        }

        editing?.let { i ->
            val step = p.steps.getOrNull(i)
            if (step != null && pending) EditStep(step, onCancel = { editing = null; refused = null }) { payload ->
                cb.edit(p.id, i, payload) { reason -> refused = reason; if (reason == null) editing = null }
            }
        }
        refused?.let { Line(it, Type.small, c.alert, Modifier.padding(top = Space.xs)) }
        if (!pending) p.outcome?.let { Line(it, Type.small, c.ink2, Modifier.padding(top = Space.xs)) }
        if (pending) changed?.let {
            Column(Modifier.fillMaxWidth().padding(top = Space.s).topRule(c.rule).padding(top = Space.s)) {
                Line(it, Type.small.copy(fontWeight = Type.action.fontWeight), c.alert)
                Line("Nothing ran. What it would do is above, as it is now.", Type.small, c.ink2)
            }
        }
        if (cb.ask(p) && !rated) {
            Row(Modifier.fillMaxWidth().padding(top = Space.s).topRule(c.rule).padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Line("Was this useful?", Type.small, c.ink2, Modifier.weight(1f))
                TextAction("Yes", { rated = true; cb.useful(p.steps.first().kind, true) })
                TextAction("No", { rated = true; cb.useful(p.steps.first().kind, false) })
            }
        }

        if (pending && long && !whole) Line("Read the whole prompt before Confirm: it runs every time.", Type.small, c.ink2, Modifier.padding(top = Space.xs))
        Spacer(Modifier.height(Space.s))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
            when {
                pending && changed != null -> {
                    TextAction("Review again", { seen = cb.bind(p); changed = null }, emphasis = true)
                    TextAction("Reject", { cb.reject(p.id) })
                }
                // Each row answers itself; the card closes once every row has an answer.
                pending && answerCard -> Unit
                pending && editing == null -> {
                    // A question gets Yes and No; everything else Confirm and Reject.
                    val question = p.steps.size == 1 && p.steps[0].kind in setOf(ProposalKind.SET_RELATION, ProposalKind.ADD_AUTO_APPROVE)
                    val model = p.steps.any { it.kind == ProposalKind.SWITCH_MODEL }
                    val confirm = { cb.confirm(p.id, seen) { r -> if (r in Bindings.REASONS) changed = r else refused = r } }
                    // Athena's schedules run on their own every day: confirming one takes his fingerprint.
                    val onConfirm = if (p.steps.any { it.kind == ProposalKind.SET_SCHEDULE }) ({
                        dev.phonerecall.lemnos.unlockPrompt(ctx, "Change Athena's schedule", p.title, { confirm() }, { refused = it })
                    }) else if (model) ({
                        // A model change reaches every later call of that feature: his fingerprint, like a schedule.
                        dev.phonerecall.lemnos.unlockPrompt(ctx, "Switch model", p.title, { confirm() }, { refused = it })
                    }) else confirm
                    TextAction(if (model) "Switch" else if (question) "Yes" else "Confirm", onConfirm, emphasis = true, enabled = p.needs == null && p.steps.any { it.selected } && (!long || whole))
                    if (p.steps.size == 1 && editable(p.steps[0].kind).isNotEmpty()) TextAction("Edit", { editing = 0 })
                    TextAction(if (model) "Not now" else if (question) "No" else "Reject", { cb.reject(p.id) })
                    if (p.steps.any { it.kind == ProposalKind.ADD_BIRTHDAY }) TextAction("Not a birthday", { cb.notBirthday(p.id) })
                    // A learned shortcut: Yes, Not now (a month), or Never (never asked again).
                }
                p.undoable -> TextAction("Undo", { cb.undo(p.id) })
            }
        }
    }
}

@Composable
private fun StepRow(s: Step, enabled: Boolean, onToggle: () -> Unit, onEdit: (() -> Unit)?) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).topRule(c.rule).clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = RoundedCornerShape(Radius.forSize(20.dp))
        Box(Modifier.size(20.dp).clip(box).border(1.5.dp, if (s.selected) c.accent else c.ink3, box), contentAlignment = Alignment.Center) {
            if (s.selected) Icon(AthenaIcons.Check, contentDescription = null, Modifier.size(16.dp), tint = c.accentInk)
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
            Line(s.summary, Type.small, if (s.selected) c.ink else c.ink3)
            s.result?.takeIf { it.startsWith("Not done") }?.let { Line(it, Type.small, c.alert) }
        }
        if (onEdit != null) TextAction("Edit", onEdit)
    }
}

@Composable
private fun EditStep(s: Step, onCancel: () -> Unit, onSave: (Map<String, String>) -> Unit) {
    val values = remember(s) { mutableStateMapOf<String, String>().apply { putAll(s.payload) } }
    Column(Modifier.padding(top = Space.s)) {
        for ((key, label) in editable(s.kind)) {
            Kicker(label, Modifier.padding(top = Space.s))
            UnderlineField(values[key].orEmpty(), { values[key] = it }, label, Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            TextAction("Save", { onSave(values.toMap()) }, emphasis = true)
            TextAction("Cancel", onCancel)
        }
    }
}

/**
 * A grouped offer: per row Yes / Not now / Never (a shortcut) or Yes / No (an idea), with her one-line why. Not now,
 * Never and No count at once; the Yes rows apply together, with Undo, once every row is answered.
 */
@Composable
private fun AnswerRows(p: Proposal, ctx: android.content.Context) {
    val c = Athena.colors
    var answers by remember(p.id) { mutableStateOf(dev.phonerecall.shortcuts.ShortcutsCore.answers(ctx, p.id)) }
    p.steps.forEachIndexed { i, s ->
        Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.xs)) {
            Line(if (s.kind == ProposalKind.IDEA) s.payload["title"].orEmpty() else s.summary.replaceFirstChar { it.uppercase() }, Type.body, c.ink)
            s.payload["why"]?.let { Line(it, Type.small, c.ink2) }
            if (s.kind == ProposalKind.IDEA) Line(if (s.payload["action"] != null) "Becomes a learned shortcut" else "Needs a code change: drafted for Prometheus", Type.caption, c.ink3)
            val mine = answers[i]
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalAlignment = Alignment.CenterVertically) {
                val choices = if (s.kind == ProposalKind.IDEA) listOf("yes" to "Yes", "no" to "No") else listOf("yes" to "Yes", "notnow" to "Not now", "never" to "Never")
                for ((v, word) in choices) TextAction(word, {
                    answers = answers + (i to v)
                    Thread { dev.phonerecall.shortcuts.ShortcutsCore.answer(ctx, p.id, i, v) }.start()
                }, emphasis = mine == v, enabled = mine == null)
            }
        }
    }
}
