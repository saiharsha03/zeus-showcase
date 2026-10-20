package dev.phonerecall.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskContext
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.Fmt
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/** Athena's pre-pick in words: "Next, @computer", "Waiting for Taylor, check back Thu, Oct 23". */
internal fun suggestionText(s: Suggestion): String = listOfNotNull(
    s.choice.label + (if (s.choice == Clarify.WAITING && s.who != null) " ${s.who}" else ""),
    s.context?.takeIf { s.choice == Clarify.NEXT }?.label,
    s.date?.let { if (s.choice == Clarify.WAITING) "check back " + DAY.format(it) else DAY.format(it) },
).joinToString(", ")

private fun moveLabel(c: Clarify) = when (c) {
    Clarify.SOMEDAY -> "Move to Someday"
    Clarify.REFERENCE -> "File in Reference"
    Clarify.TRASH -> "Delete"
    Clarify.DO_NOW -> "Done"
    Clarify.WAITING -> "Move to Waiting for"
    Clarify.SCHEDULED -> "Schedule"
    Clarify.PROJECT -> "Make project"
    Clarify.NEXT -> "Move to Next"
}

/**
 * The Inbox, one item at a time, as GTD's questions with closed choices. Athena's pick (local rules) is
 * already selected; one tap on the button accepts it. Every move comes back with Undo.
 */
@Composable
internal fun ClarifyPane(queue: List<Task>, snap: TaskSnapshot, gutter: Dp, wide: Boolean, onMove: (Task, ClarifyMove) -> Unit, onSkip: (Task) -> Unit, onClose: () -> Unit) {
    val c = Athena.colors
    val t = queue.firstOrNull()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Clarify", gutter, kicker = when (queue.size) { 0 -> "Inbox"; 1 -> "Last one"; else -> "${queue.size} to go" }) {
            if (t != null) TextAction("Skip", { onSkip(t) })
            TextAction("Close", onClose)
        }
        if (t == null) {
            Column(Modifier.padding(horizontal = gutter, vertical = Space.xl)) {
                Line("Inbox is empty. Everything is clarified.", Type.body, c.ink2)
                Spacer(Modifier.height(Space.l))
                Row(Modifier.offset(x = -Space.s)) { TextAction("Back to Praxis", onClose, emphasis = true) }
            }
            return
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(220.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = gutter, end = Space.l, top = Space.xs)) {
                    Kicker("Up next")
                    Spacer(Modifier.height(Space.s))
                    val rest = queue.drop(1)
                    if (rest.isEmpty()) Line("Nothing after this one.", Type.small, c.ink3)
                    for (r in rest.take(12)) Line(r.title, Type.small, c.ink2, Modifier.padding(vertical = Space.xs), maxLines = 2)
                    if (rest.size > 12) Line("and ${rest.size - 12} more", Type.small, c.ink3)
                }
                ClarifyItem(t, snap, Modifier.weight(1f).padding(end = gutter), onMove)
            }
        } else ClarifyItem(t, snap, Modifier.padding(horizontal = gutter), onMove)
    }
}

@Composable
private fun ClarifyItem(t: Task, snap: TaskSnapshot, modifier: Modifier, onMove: (Task, ClarifyMove) -> Unit) {
    val c = Athena.colors
    val now = ZonedDateTime.now()
    val today = now.toLocalDate()
    val s = remember(t.id, t.title) { Gtd.suggest(t.title, today, t.due?.let { Gtd.day(it, now) }) }
    var choice by remember(t.id) { mutableStateOf(s.choice) }
    var ctx by remember(t.id) { mutableStateOf(s.context ?: Gtd.contextOf(t.title)) }
    var who by remember(t.id) { mutableStateOf(s.who.orEmpty()) }
    var date by remember(t.id) { mutableStateOf(s.date) }
    var project by remember(t.id) { mutableStateOf(t.title) }
    var first by remember(t.id) { mutableStateOf("") }
    fun pick(to: Clarify) {
        choice = to
        // A date the choice needs, filled with the usual default.
        if (to == Clarify.SCHEDULED && (date == null || s.choice != Clarify.SCHEDULED)) date = if (s.choice == Clarify.SCHEDULED) s.date else today.plusDays(1)
        if (to == Clarify.WAITING && (date == null || s.choice != Clarify.WAITING)) date = if (s.choice == Clarify.WAITING) s.date else today.plusDays(3)
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).wrapContentWidth(Alignment.Start).widthIn(max = 640.dp).padding(bottom = 96.dp)) {
        Line(t.title, Type.title, c.ink, maxLines = 4)
        snap.project(t.projectId)?.let { Kicker(it.name, Modifier.padding(top = Space.xs)) }

        Spacer(Modifier.height(Space.l))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusWord("Suggested", Tone.ACTIVE)
            Spacer(Modifier.width(Space.s))
            Line(suggestionText(s), Type.small, c.ink2, Modifier.weight(1f), maxLines = 2)
        }

        Section("Actionable?")
        Words(listOf("Yes", "No"), if (choice.actionable) 0 else 1) { i ->
            val yes = i == 0
            if (yes != choice.actionable) pick(if (s.choice.actionable == yes) s.choice else if (yes) Clarify.NEXT else Clarify.SOMEDAY)
        }
        val opts = Clarify.entries.filter { it.actionable == choice.actionable }
        Section(if (choice.actionable) "Then" else "Keep it?")
        Words(opts.map { it.label }, opts.indexOf(choice)) { pick(opts[it]) }
        Line(choice.hint, Type.small, c.ink2, Modifier.padding(top = Space.xs))

        when (choice) {
            Clarify.NEXT -> {
                Section("Where")
                Words(TaskContext.entries.map { it.label }, ctx.ordinal) { ctx = TaskContext.entries[it] }
            }
            Clarify.SCHEDULED -> {
                Section("When")
                val days = dayChoices(today)
                val idx = days.indexOfFirst { it.second == date }
                Words(days.map { it.first } + listOfNotNull(date?.takeIf { idx < 0 }?.let { DAY.format(it) }), if (idx >= 0) idx else days.size) { i ->
                    if (i < days.size) date = days[i].second
                }
            }
            Clarify.WAITING -> {
                Section("Waiting on")
                UnderlineField(who, { who = it }, "Name or label", Modifier.fillMaxWidth())
                Section("Check back")
                val checks = followUpChoices(today)
                val idx = checks.indexOfFirst { it.second == date }
                Words(checks.map { it.first } + listOfNotNull(date?.takeIf { idx < 0 }?.let { DAY.format(it) }), if (idx >= 0) idx else checks.size) { i ->
                    if (i < checks.size) date = checks[i].second
                }
            }
            Clarify.PROJECT -> {
                Section("Project")
                UnderlineField(project, { project = it }, "Project name", Modifier.fillMaxWidth())
                Section("First next action")
                UnderlineField(first, { first = it; ctx = Gtd.contextOf(it) }, "The very next step", Modifier.fillMaxWidth())
                if (first.isNotBlank()) {
                    Section("Where")
                    Words(TaskContext.entries.map { it.label }, ctx.ordinal) { ctx = TaskContext.entries[it] }
                }
            }
            else -> Unit
        }

        Spacer(Modifier.height(Space.xl))
        PrimaryAction(moveLabel(choice), {
            onMove(t, ClarifyMove(
                choice, context = ctx.takeIf { choice == Clarify.NEXT || choice == Clarify.PROJECT },
                who = who.trim().ifEmpty { null }, date = date.takeIf { choice == Clarify.SCHEDULED || choice == Clarify.WAITING },
                project = project.trim().ifEmpty { null }, firstAction = first.trim().ifEmpty { null },
            ))
        })
    }
}

private fun dayChoices(today: LocalDate) = listOf(
    "Today" to today, "Tomorrow" to today.plusDays(1),
    "This weekend" to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)),
    "Next week" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
)

/** The weekly review: five short steps, one screen each. Finishing records the date. */
@Composable
internal fun ReviewPane(
    snap: TaskSnapshot, gutter: Dp, wide: Boolean, now: ZonedDateTime,
    onClarify: () -> Unit, onAddNext: (Long, String) -> Unit, onFinish: () -> Unit, onClose: () -> Unit, startStep: Int = 0,
) {
    val c = Athena.colors
    var at by rememberSaveable { mutableIntStateOf(startStep) }
    val steps = Gtd.reviewSteps(snap, now)
    val (step, n) = steps[at.coerceIn(steps.indices)]
    val drafts = remember { mutableStateMapOf<Long, String>() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Weekly review", gutter, kicker = "Step ${at + 1} of ${steps.size}") { TextAction("Close", onClose) }
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                Column(Modifier.width(220.dp).padding(start = gutter - Space.s, top = Space.xs)) {
                    steps.forEachIndexed { i, (st, k) -> ListWord(st.label, k, i == at, vertical = true) { at = i } }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = gutter).wrapContentWidth(Alignment.Start).widthIn(max = 640.dp).padding(bottom = 96.dp)) {
                Line(step.label, Type.title, c.ink)
                Line(step.ask, Type.small, c.ink2, Modifier.padding(top = Space.xxs))
                Spacer(Modifier.height(Space.l))
                val open = snap.open().filter { !it.sample }
                val nowMs = now.toInstant().toEpochMilli()
                if (n == 0) Line(step.empty, Type.body, c.ink2)
                else when (step) {
                    ReviewStep.INBOX -> {
                        Rows(open.filter { it.list == TaskList.INBOX }.map { it.title to null })
                        Row(Modifier.offset(x = -Space.s)) { TextAction("Clarify now", onClarify, emphasis = true) }
                    }
                    ReviewStep.STUCK -> Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        for (p in Gtd.stuck(snap).filter { !it.sample }) {
                            Column {
                                Line(p.name, Type.body, c.ink)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val d = drafts[p.id].orEmpty()
                                    val submit = { if (d.isNotBlank()) { onAddNext(p.id, d); drafts[p.id] = "" } }
                                    UnderlineField(d, { drafts[p.id] = it }, "Next action", Modifier.weight(1f), onSubmit = submit)
                                    TextAction("Add", submit, emphasis = d.isNotBlank(), enabled = d.isNotBlank())
                                }
                            }
                        }
                    }
                    ReviewStep.WAITING -> Rows(open.filter { it.list == TaskList.WAITING && (it.followUp == null || it.followUp <= nowMs) }.map { t ->
                        t.title to listOfNotNull(t.waitingOn, if (t.followUp == null) "no day to check back" else "follow up").joinToString(", ")
                    })
                    ReviewStep.WEEK -> Rows(open.filter { it.list == TaskList.SCHEDULED && it.due != null }.sortedBy { it.due }.take(n).map { it.title to Fmt.due(it.due!!, it.dueHasTime, now) })
                    ReviewStep.SOMEDAY -> Rows(open.filter { it.list == TaskList.SOMEDAY }.map { it.title to null })
                }
                Spacer(Modifier.height(Space.xl))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (at == steps.lastIndex) PrimaryAction("Finish review", onFinish) else PrimaryAction("Next step", { at++ })
                    Spacer(Modifier.width(Space.m))
                    if (at > 0) TextAction("Back", { at-- })
                }
            }
        }
    }
}

@Composable
private fun Rows(rows: List<Pair<String, String?>>) {
    val c = Athena.colors
    Column(Modifier.padding(bottom = Space.s)) {
        for ((title, note) in rows.take(10)) {
            Column(Modifier.padding(vertical = Space.xs)) {
                Line(title, Type.body, c.ink, maxLines = 2)
                if (note != null) Line(note, Type.small, c.ink3, maxLines = 1)
            }
        }
        if (rows.size > 10) Line("and ${rows.size - 10} more", Type.small, c.ink3)
    }
}
