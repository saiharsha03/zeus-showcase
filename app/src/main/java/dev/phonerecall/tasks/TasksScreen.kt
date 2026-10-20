package dev.phonerecall.tasks

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.AthenaViewModel
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskContext
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.Fmt
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.ItemBounds
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Motion
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ReorderState
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.ToastClearance
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.Undo
import dev.phonerecall.ui.UndoToast
import dev.phonerecall.ui.liftAndDrag
import dev.phonerecall.ui.swipeAway
import dev.phonerecall.ui.topRule
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

private const val PROJECTS = "PROJECTS"
private const val PROJECT = "project:"
private const val REVIEW = "REVIEW"

/** What the left side (or the top row) can show. Review opens the weekly walk-through. */
private val VIEWS = listOf(TaskList.INBOX, TaskList.NEXT, TaskList.WAITING, TaskList.SCHEDULED, TaskList.SOMEDAY).map { it.name } + PROJECTS + TaskList.DONE.name + REVIEW

/** The Next view's filter: what fits now, everything, or one context. */
private const val FITS_NOW = "NOW"
private const val ALL = "ALL"

private fun label(v: String, snap: TaskSnapshot): String = when {
    v == PROJECTS -> "Projects"
    v == REVIEW -> "Review"
    v.startsWith(PROJECT) -> snap.project(v.removePrefix(PROJECT).toLongOrNull())?.name ?: "Project"
    else -> TaskList.valueOf(v).label
}

private fun count(v: String, snap: TaskSnapshot): Int = when {
    v == PROJECTS -> snap.projects.size
    v == TaskList.DONE.name || v == REVIEW -> 0
    else -> snap.tasks.count { it.list.name == v }
}

/** The tasks in a view, in the order shown. */
internal fun tasksIn(v: String, snap: TaskSnapshot): List<Task> = when {
    v.startsWith(PROJECT) -> {
        val id = v.removePrefix(PROJECT).toLongOrNull()
        snap.open().filter { it.projectId == id }
    }
    v == TaskList.SCHEDULED.name -> snap.tasks.filter { it.list == TaskList.SCHEDULED }.sortedBy { it.due ?: Long.MAX_VALUE }
    v == TaskList.WAITING.name -> snap.tasks.filter { it.list == TaskList.WAITING }.sortedWith(compareBy({ it.followUp ?: Long.MAX_VALUE }, { it.sort }))
    v == TaskList.DONE.name -> snap.tasks.filter { it.list == TaskList.DONE }.sortedByDescending { it.doneAt ?: 0 }
    v == PROJECTS || v == REVIEW -> emptyList()
    else -> snap.tasks.filter { it.list.name == v }.sortedWith(compareBy({ !it.quick }, { it.sort }))
}

/** One clarify decision, with the details its choice needs. */
data class ClarifyMove(
    val choice: Clarify, val context: TaskContext? = null, val who: String? = null, val date: LocalDate? = null,
    val project: String? = null, val firstAction: String? = null,
)

/** What Praxis asks of the app. The defaults do nothing, for previews and shots. */
class PraxisCalls(
    val addTask: (String) -> Unit = {},
    val addProject: (String, (Long) -> Unit) -> Unit = { _, _ -> },
    val update: (Task) -> Unit = {},
    val complete: (Long) -> Unit = {},
    val uncomplete: (Long) -> Unit = {},
    val delete: (Long) -> Unit = {},
    val restore: (Task) -> Unit = {},
    val reorder: (List<Long>) -> Unit = {},
    val clarify: (Task, ClarifyMove, (Undo) -> Unit) -> Unit = { _, _, _ -> },
    val addNextAction: (Long, String) -> Unit = { _, _ -> },
    val reviewDone: () -> Unit = {},
    val reviewSeen: () -> Unit = {},
    val openLethe: () -> Unit = {},
    val openJobs: () -> Unit = {},
)

/** GTD: capture to Inbox, clarify each item into a list, a date, a project, or do it now. */
@Composable
fun TasksScreen(vm: AthenaViewModel, wide: Boolean, gutter: Dp, openLethe: () -> Unit = {}, openJobs: () -> Unit = {}) {
    val mind by vm.brain.mind.collectAsStateWithLifecycle()
    val snap by vm.tasks.collectAsStateWithLifecycle()
    val senses by vm.brain.senses.collectAsStateWithLifecycle()
    val situation by vm.situation.collectAsStateWithLifecycle()
    val ask by vm.reviewAsk.collectAsStateWithLifecycle()
    val calls = remember(vm, openLethe, openJobs) {
        PraxisCalls(
            addTask = vm::addTask, addProject = { n, then -> vm.addProject(n, then) }, update = vm::updateTask,
            complete = { vm.completeTask(it) }, uncomplete = vm::uncompleteTask, delete = vm::deleteTask, restore = vm::restoreTask,
            reorder = vm::reorderTasks, clarify = vm::clarify, addNextAction = vm::addNextAction,
            reviewDone = vm::reviewDone, reviewSeen = vm::reviewSeen, openLethe = openLethe, openJobs = openJobs,
        )
    }
    TasksContent(snap, situation, wide, gutter, calls, askReview = ask) {
        // Athena's circle at work: open long jobs, one line each.
        dev.phonerecall.mind.LongJobsZone(mind.jobs, gutter, openJobs)
        // Parnassus jobs for Iris or Atlas, stored until the phase 3 channel. Circle jobs show under Long jobs.
        dev.phonerecall.brain.WorkerJobsZone(senses.jobs, gutter)
    }
}

/** Praxis without the ViewModel: what the shots render. */
@Composable
fun TasksContent(
    snap: TaskSnapshot, situation: Situation, wide: Boolean, gutter: Dp, calls: PraxisCalls,
    askReview: Int = 0, startView: String = TaskList.INBOX.name, startClarify: Boolean = false, startReview: Boolean = false,
    jobs: @Composable () -> Unit = {},
) {
    var view by rememberSaveable { mutableStateOf(startView) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var clarifying by rememberSaveable { mutableStateOf(startClarify) }
    var reviewing by rememberSaveable { mutableStateOf(startReview) }
    var filter by rememberSaveable { mutableStateOf(FITS_NOW) }
    val skipped = remember { mutableStateListOf<Long>() }
    var undo by remember { mutableStateOf<Undo?>(null) }

    // Olympus's review card asked for the walk-through.
    LaunchedEffect(askReview) { if (askReview > 0) { reviewing = true; calls.reviewSeen() } }

    val open = openId?.let { id -> snap.tasks.firstOrNull { it.id == id } }
    if (open != null) {
        BackHandler { openId = null }
        Process(open, snap, gutter, calls, onClose = { openId = null }, onUndo = { undo = it })
        return
    }

    Box(Modifier.fillMaxSize()) {
        when {
            clarifying -> {
                BackHandler { clarifying = false; skipped.clear() }
                val queue = tasksIn(TaskList.INBOX.name, snap).filter { it.id !in skipped }
                ClarifyPane(
                    queue, snap, gutter, wide,
                    onMove = { t, m -> calls.clarify(t, m) { undo = it } },
                    onSkip = { skipped += it.id },
                    onClose = { clarifying = false; skipped.clear() },
                )
            }
            reviewing -> {
                BackHandler { reviewing = false }
                ReviewPane(
                    snap, gutter, wide, ZonedDateTime.now(),
                    onClarify = { clarifying = true },
                    onAddNext = calls.addNextAction,
                    onFinish = { calls.reviewDone(); reviewing = false; undo = Undo("Weekly review done.") {} },
                    onClose = { reviewing = false },
                )
            }
            else -> Main(snap, situation, wide, gutter, calls, view, { view = it }, filter, { filter = it }, { openId = it }, { clarifying = true }, { reviewing = true }, { undo = it }, jobs)
        }
        UndoToast(undo, gutter) { undo = null }
    }
}

@Composable
private fun Main(
    snap: TaskSnapshot, situation: Situation, wide: Boolean, gutter: Dp, calls: PraxisCalls,
    view: String, go: (String) -> Unit, filter: String, setFilter: (String) -> Unit,
    open: (Long) -> Unit, clarify: () -> Unit, review: () -> Unit, undo: (Undo) -> Unit, jobs: @Composable () -> Unit,
) {
    BackHandler(view.startsWith(PROJECT)) { go(PROJECTS) }
    val pick = { v: String -> if (v == REVIEW) review() else go(v) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            if (view.startsWith(PROJECT)) label(view, snap) else Place.TASKS.title(), gutter,
            emblem = if (view.startsWith(PROJECT)) null else Place.TASKS,
            kicker = if (view.startsWith(PROJECT)) "Project" else null,
            subtitle = if (view.startsWith(PROJECT)) null else Place.TASKS.subtitle(),
        ) {
            if (view.startsWith(PROJECT)) TextAction("Back", { go(PROJECTS) })
            // Lethe, the river of forgetting: what hygiene closed, with Undo.
            else {
                TextAction("Long jobs", calls.openJobs)
                TextAction(Place.EXPIRED.title(), calls.openLethe)
            }
        }
        jobs()
        var draft by rememberSaveable { mutableStateOf("") }
        val addingProject = view == PROJECTS
        Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            val submit = {
                if (draft.isNotBlank()) {
                    if (addingProject) calls.addProject(draft) {} else calls.addTask(draft)
                    draft = ""
                }
            }
            UnderlineField(draft, { draft = it }, if (addingProject) "New project" else "Add to Inbox", Modifier.weight(1f), onSubmit = submit)
            Spacer(Modifier.width(Space.s))
            TextAction("Add", submit, emphasis = draft.isNotBlank(), enabled = draft.isNotBlank())
        }
        Spacer(Modifier.height(Space.s))
        val body: @Composable ColumnScope.() -> Unit = {
            ViewTop(view, snap, situation, gutter, filter, setFilter, calls, clarify)
            Body(view, snap, gutter, calls, nextFilter(view, filter, situation), go, open, undo)
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(196.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = gutter - Space.s, top = Space.s)) {
                    for (v in VIEWS) ListWord(label(v, snap), count(v, snap), v == view || (v == PROJECTS && view.startsWith(PROJECT)), vertical = true) { pick(v) }
                }
                Column(Modifier.weight(1f), content = body)
            }
        } else {
            Row(Modifier.padding(horizontal = gutter - Space.s).horizontalScroll(rememberScrollState())) {
                for (v in VIEWS) ListWord(label(v, snap), count(v, snap), v == view || (v == PROJECTS && view.startsWith(PROJECT)), vertical = false) { pick(v) }
            }
            Spacer(Modifier.height(Space.s))
            body()
        }
    }
}

/** The Next view's filter as a test on each task, or null for everything. */
private fun nextFilter(view: String, filter: String, s: Situation): ((Task) -> Boolean)? {
    if (view != TaskList.NEXT.name) return null
    return when (filter) {
        ALL -> null
        FITS_NOW -> Gtd.contextsNow(s)?.let { now -> { t: Task -> Gtd.fits(t, now) } }
        else -> TaskContext.entries.firstOrNull { it.name == filter }?.let { c -> { t: Task -> t.context == c } }
    }
}

/** What sits above a view's list: Inbox's clarify button, Next's contexts, a stuck project's prompt. */
@Composable
private fun ViewTop(view: String, snap: TaskSnapshot, situation: Situation, gutter: Dp, filter: String, setFilter: (String) -> Unit, calls: PraxisCalls, clarify: () -> Unit) {
    val c = Athena.colors
    when {
        view == TaskList.INBOX.name -> {
            val n = snap.tasks.count { it.list == TaskList.INBOX }
            if (n > 0) Row(Modifier.padding(start = gutter, end = gutter, bottom = Space.m), verticalAlignment = Alignment.CenterVertically) {
                PrimaryAction("Clarify one by one", clarify)
                Spacer(Modifier.width(Space.m))
                Line(if (n == 1) "1 to process" else "$n to process", Type.small, c.ink3, maxLines = 1)
            }
        }
        view == TaskList.NEXT.name -> ContextFilter(snap, situation, gutter, filter, setFilter)
        view.startsWith(PROJECT) -> {
            val id = view.removePrefix(PROJECT).toLongOrNull() ?: return
            if (Gtd.stuck(snap).none { it.id == id }) return
            NextActionPrompt(id, gutter, calls.addNextAction)
        }
    }
}

/** "Fits now", "All" and the six contexts, as plain words; the reason it fits on the line below. */
@Composable
internal fun ContextFilter(snap: TaskSnapshot, situation: Situation, gutter: Dp, filter: String, setFilter: (String) -> Unit) {
    val c = Athena.colors
    val now = Gtd.contextsNow(situation)
    val next = snap.tasks.filter { it.list == TaskList.NEXT }
    // With nothing known, "Fits now" is everything: the words start at All.
    val on = if (filter == FITS_NOW && now == null) ALL else filter
    Column(Modifier.padding(bottom = Space.s)) {
        Row(Modifier.padding(horizontal = gutter - Space.s).horizontalScroll(rememberScrollState())) {
            if (now != null) ListWord("Fits now", next.count { Gtd.fits(it, now) }, on == FITS_NOW, vertical = false) { setFilter(FITS_NOW) }
            ListWord("All", next.size, on == ALL, vertical = false) { setFilter(ALL) }
            for (ctx in TaskContext.entries) ListWord(ctx.label, next.count { it.context == ctx }, on == ctx.name, vertical = false) { setFilter(ctx.name) }
        }
        val line = when {
            on != FITS_NOW || now == null -> null
            else -> Gtd.whereLine(situation)?.let { w -> "$w: " + TaskContext.entries.filter { it in now }.joinToString(", ") { it.label } }
        }
        if (line != null) Line(line, Type.small, c.ink3, Modifier.padding(start = gutter, end = gutter, top = Space.xs), maxLines = 2)
        if (on == FITS_NOW && now != null) {
            val hidden = next.count { !Gtd.fits(it, now) }
            if (hidden > 0) Row(Modifier.padding(start = gutter - Space.s)) { TextAction(if (hidden == 1) "1 more elsewhere" else "$hidden more elsewhere", { setFilter(ALL) }) }
        }
    }
}

/** A project with no open Next action asks for one. */
@Composable
private fun NextActionPrompt(projectId: Long, gutter: Dp, add: (Long, String) -> Unit) {
    val c = Athena.colors
    var draft by rememberSaveable(projectId) { mutableStateOf("") }
    Column(Modifier.padding(start = gutter, end = gutter, bottom = Space.m)) {
        StatusWord("No next action", Tone.ACTIVE)
        Line("What is the very next step?", Type.small, c.ink2, Modifier.padding(top = Space.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val submit = { if (draft.isNotBlank()) { add(projectId, draft); draft = "" } }
            UnderlineField(draft, { draft = it }, "Next action", Modifier.weight(1f), onSubmit = submit)
            Spacer(Modifier.width(Space.s))
            TextAction("Add", submit, emphasis = draft.isNotBlank(), enabled = draft.isNotBlank())
        }
    }
}

@Composable
internal fun ListWord(text: String, n: Int, on: Boolean, vertical: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    Row(
        Modifier.then(if (vertical) Modifier.fillMaxWidth() else Modifier).heightIn(min = 44.dp)
            .clickable(role = Role.Tab, onClick = onClick).padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(
            text, Type.action, if (on) c.ink else c.ink3,
            Modifier.drawBehind { if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) } },
            maxLines = 1,
        )
        if (n > 0) { Spacer(Modifier.width(Space.xs)); Line("$n", Type.caption, c.ink3, maxLines = 1) }
    }
}

@Composable
private fun Body(view: String, snap: TaskSnapshot, gutter: Dp, calls: PraxisCalls, filter: ((Task) -> Boolean)?, go: (String) -> Unit, open: (Long) -> Unit, undo: (Undo) -> Unit) {
    val c = Athena.colors
    if (view == PROJECTS) {
        if (snap.projects.isEmpty()) {
            Line("No projects yet. Name one above, then link tasks to it.", Type.body, c.ink2, Modifier.padding(horizontal = gutter, vertical = Space.xl))
            return
        }
        val stuck = Gtd.stuck(snap).map { it.id }.toSet()
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = ToastClearance), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            items(snap.projects, key = { it.id }) { p ->
                val n = snap.open().count { it.projectId == p.id }
                val shape = RoundedCornerShape(Radius.blockSmall)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(c.block(Tint.TASKS)).border(1.dp, c.edge, shape)
                        .clickable(role = Role.Button) { go(PROJECT + p.id) }.padding(horizontal = Space.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = Space.m)) {
                        Line(p.name, Type.body, c.ink, maxLines = 2)
                        if (p.id in stuck) { Spacer(Modifier.height(Space.xs)); StatusWord("No next action", Tone.ACTIVE) }
                    }
                    if (p.sample) { StatusWord("Sample", Tone.QUIET); Spacer(Modifier.width(Space.s)) }
                    Kicker(if (n == 1) "1 open" else "$n open")
                }
            }
        }
        return
    }
    val all = tasksIn(view, snap)
    val tasks = if (filter == null) all else all.filter(filter)
    if (tasks.isEmpty()) {
        val line = when {
            // Easter egg: nothing open anywhere.
            view != TaskList.DONE.name && snap.loaded && snap.open().isEmpty() -> "Even Heracles rested."
            all.isNotEmpty() -> "Nothing fits here right now."
            else -> when (view) {
                TaskList.INBOX.name -> "Inbox is empty. Everything is processed."
                TaskList.DONE.name -> "Nothing done yet."
                else -> "Nothing here."
            }
        }
        if (line == "Even Heracles rested.") LaunchedEffect(Unit) { dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.HERACLES) }
        Line(line, Type.body, c.ink2, Modifier.padding(horizontal = gutter, vertical = Space.xl))
        return
    }
    val canReorder = view != TaskList.DONE.name && view != TaskList.SCHEDULED.name && view != TaskList.WAITING.name && !view.startsWith(PROJECT) && filter == null
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val list = rememberLazyListState()
    val reorder = remember(list) {
        ReorderState(
            scope,
            bounds = { list.layoutInfo.visibleItemsInfo.map { ItemBounds(it.key, androidx.compose.ui.unit.IntOffset(0, it.offset), androidx.compose.ui.unit.IntSize(list.layoutInfo.viewportSize.width, it.size)) } },
            viewportHeight = { list.layoutInfo.viewportSize.height },
            scrollBy = { list.scrollBy(it) },
            edgePx = with(density) { 64.dp.toPx() },
        )
    }
    LaunchedEffect(tasks) { reorder.settle() }
    val byId = tasks.associateBy { it.id }
    val shown = reorder.order?.mapNotNull { byId[it as Long] } ?: tasks
    val done = view == TaskList.DONE.name
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = ToastClearance),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        userScrollEnabled = reorder.dragging == null,
    ) {
        items(shown, key = { it.id }) { t ->
            val tNow by rememberUpdatedState(t)
            val shownNow by rememberUpdatedState(shown)
            var liftIndex by remember { mutableIntStateOf(-1) }
            val lifted = reorder.dragging == t.id
            val scale by animateFloatAsState(if (lifted) 1.02f else 1f, Motion.settle(), label = "lift")
            val swipe = remember(t.id) { Animatable(0f) }
            var width by remember { mutableIntStateOf(1) }
            val shadow = with(density) { 8.dp.toPx() }
            val shape = RoundedCornerShape(Radius.blockSmall)
            TaskBlock(
                t, snap,
                Modifier
                    .then(if (lifted) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.place, fadeOutSpec = null))
                    .onSizeChanged { width = it.width }
                    .graphicsLayer {
                        val tr = reorder.translation(t.id)
                        translationX = swipe.value; translationY = tr.y
                        scaleX = scale; scaleY = scale
                        alpha = 1f - (kotlin.math.abs(swipe.value) / width).coerceIn(0f, 1f) * 0.6f
                        shadowElevation = if (lifted) shadow else 0f
                        this.shape = shape; clip = false
                    }
                    .swipeAway(t.id, swipe, { width.toFloat() }, locked = false, scope = scope) {
                        val task = tNow
                        if (done) { calls.uncomplete(task.id); undo(Undo("Moved back.") { calls.complete(task.id) }) }
                        else { calls.complete(task.id); undo(Undo("Done.") { calls.uncomplete(task.id) }) }
                    }
                    .liftAndDrag(
                        t.id, haptics,
                        onTap = { open(tNow.id) },
                        onLift = {
                            if (canReorder) { liftIndex = shownNow.indexOfFirst { it.id == t.id }; reorder.start(t.id, shownNow.map { it.id }) }
                        },
                        onDrag = { if (canReorder) reorder.dragBy(it) },
                        onDrop = {
                            val r = reorder.end()
                            if (r != null && r.second != liftIndex) calls.reorder(reorder.order.orEmpty().map { it as Long })
                            else reorder.settle()
                        },
                        onMenu = { open(tNow.id) },
                    ),
            )
        }
    }
}

@Composable
internal fun TaskBlock(t: Task, snap: TaskSnapshot, modifier: Modifier) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.blockSmall)
    val now = ZonedDateTime.now()
    val open = t.list != TaskList.DONE
    Column(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clip(shape).background(c.block(Tint.TASKS)).border(1.dp, c.edge, shape)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.Center,
    ) {
        Line(t.title, Type.body, if (open) c.ink else c.ink2, maxLines = 3)
        val project = snap.project(t.projectId)?.name
        val waiting = open && t.list == TaskList.WAITING
        if (t.due != null || t.quick || project != null || t.sample || (open && t.context != null) || (waiting && (t.waitingOn != null || t.followUp != null))) {
            Spacer(Modifier.height(Space.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
                if (t.quick && open) StatusWord("2 min", Tone.ACTIVE)
                if (t.due != null && open) {
                    val d = Fmt.due(t.due, t.dueHasTime, now)
                    StatusWord(d, when { d == "Overdue" -> Tone.ALERT; d.startsWith("Today") -> Tone.ACTIVE; else -> Tone.QUIET })
                }
                if (waiting && t.followUp != null) {
                    if (t.followUp <= now.toInstant().toEpochMilli()) StatusWord("Follow up", Tone.ALERT)
                    else StatusWord("Check " + Fmt.due(t.followUp, false, now), Tone.QUIET)
                }
                if (waiting && t.waitingOn != null) Kicker(t.waitingOn, Modifier.weight(1f, fill = false))
                if (open && t.context != null) Kicker(t.context.label)
                if (project != null) Kicker(project, Modifier.weight(1f, fill = false))
                if (t.sample) StatusWord("Sample", Tone.QUIET)
            }
        }
    }
}

// ---- Process one item ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Process(t: Task, snap: TaskSnapshot, gutter: Dp, calls: PraxisCalls, onClose: () -> Unit, onUndo: (Undo) -> Unit) {
    val c = Athena.colors
    val zone = ZoneId.systemDefault()
    var title by remember(t.id) { mutableStateOf(t.title) }
    var who by remember(t.id) { mutableStateOf(t.waitingOn.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var newProject by remember { mutableStateOf<String?>(null) }
    fun save(change: (Task) -> Task) = calls.update(change(t.copy(title = title.ifBlank { t.title }, waitingOn = who.trim().ifEmpty { null })))
    fun setDue(day: LocalDate?, time: LocalTime? = null) = save { cur ->
        if (day == null) cur.copy(due = null, dueHasTime = false)
        else {
            val keepTime = time ?: if (cur.dueHasTime && cur.due != null) Instant.ofEpochMilli(cur.due).atZone(zone).toLocalTime() else null
            val at = (if (keepTime != null) day.atTime(keepTime) else day.atStartOfDay()).atZone(zone).toInstant().toEpochMilli()
            // A dated Inbox item is processed: it goes to Scheduled.
            cur.copy(due = at, dueHasTime = keepTime != null, list = if (cur.list == TaskList.INBOX) TaskList.SCHEDULED else cur.list)
        }
    }
    val dueDay = t.due?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Process", gutter, kicker = t.list.label) {
            TextAction("Close", { save { it }; onClose() })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = Space.xxxl).widthIn(max = 640.dp)) {
            UnderlineField(title, { title = it }, "Task", Modifier.fillMaxWidth(), style = Type.title, onSubmit = { save { it } })

            Section("Move to")
            Words(TaskList.entries.filter { it != TaskList.DONE }.map { it.label }, TaskList.entries.indexOf(t.list).takeIf { t.list != TaskList.DONE }) { i ->
                save { it.copy(list = TaskList.entries[i]) }
            }

            if (t.list == TaskList.WAITING) {
                Section("Waiting on")
                UnderlineField(who, { who = it }, "Name or label", Modifier.fillMaxWidth(), onSubmit = { save { it } })
                Section("Check back")
                Line(t.followUp?.let { Fmt.due(it, false, ZonedDateTime.now()) } ?: "No day", Type.body, c.ink, Modifier.padding(bottom = Space.xs))
                val today = LocalDate.now()
                val checks = followUpChoices(today)
                val cur = t.followUp?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                Words(listOf("None") + checks.map { it.first }, if (cur == null) 0 else checks.indexOfFirst { it.second == cur }.let { if (it >= 0) it + 1 else null }) { i ->
                    save { it.copy(followUp = if (i == 0) null else checks[i - 1].second.atStartOfDay(zone).toInstant().toEpochMilli()) }
                }
            }

            Section("Context")
            Words(listOf("None") + TaskContext.entries.map { it.label }, t.context?.let { it.ordinal + 1 } ?: 0) { i ->
                save { it.copy(context = if (i == 0) null else TaskContext.entries[i - 1]) }
            }

            Section("Due")
            Line(t.due?.let { Fmt.due(it, t.dueHasTime, ZonedDateTime.now()) } ?: "No date", Type.body, c.ink, Modifier.padding(bottom = Space.xs))
            val today = LocalDate.now()
            val dayChoices = listOf(
                "None" to null, "Today" to today, "Tomorrow" to today.plusDays(1),
                "This weekend" to today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)),
                "Next week" to today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
            )
            Words(dayChoices.map { it.first } + "Pick a date", dayChoices.indexOfFirst { it.second == dueDay }.takeIf { it >= 0 && (t.due != null || it == 0) }) { i ->
                if (i < dayChoices.size) setDue(dayChoices[i].second) else pickDate = true
            }
            if (t.due != null) {
                val timeChoices = listOf("No time" to null, "9:00 AM" to LocalTime.of(9, 0), "2:00 PM" to LocalTime.of(14, 0), "6:00 PM" to LocalTime.of(18, 0))
                val cur = if (t.dueHasTime) Instant.ofEpochMilli(t.due).atZone(zone).toLocalTime() else null
                Words(timeChoices.map { it.first } + "Pick a time", timeChoices.indexOfFirst { it.second == cur }.takeIf { it >= 0 }) { i ->
                    if (i < timeChoices.size) {
                        val tm = timeChoices[i].second
                        if (tm == null) save { it.copy(due = dueDay!!.atStartOfDay(zone).toInstant().toEpochMilli(), dueHasTime = false) } else setDue(dueDay, tm)
                    } else pickTime = true
                }
            }

            Section("Project")
            val projects = snap.projects
            Words(listOf("None") + projects.map { it.name } + "New project", if (t.projectId == null) 0 else projects.indexOfFirst { it.id == t.projectId }.let { if (it >= 0) it + 1 else null }) { i ->
                when {
                    i == 0 -> save { it.copy(projectId = null) }
                    i <= projects.size -> save { it.copy(projectId = projects[i - 1].id) }
                    else -> newProject = ""
                }
            }
            newProject?.let { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(name, { newProject = it }, "Project name", Modifier.weight(1f), onSubmit = {
                        val n = newProject.orEmpty()
                        if (n.isNotBlank()) calls.addProject(n) { id -> save { it.copy(projectId = id) } }
                        newProject = null
                    })
                    TextAction("Add", {
                        val n = newProject.orEmpty()
                        if (n.isNotBlank()) calls.addProject(n) { id -> save { it.copy(projectId = id) } }
                        newProject = null
                    }, emphasis = true)
                }
            }

            Section("Two-minute rule")
            Line("If it takes less than two minutes, do it now.", Type.small, c.ink2)
            Row(Modifier.offset(x = -Space.s)) {
                TextAction(if (t.quick) "Not a two-minute job" else "Do it now", {
                    save { it.copy(quick = !it.quick, list = if (!it.quick) TaskList.NEXT else it.list) }
                }, emphasis = !t.quick)
            }

            Spacer(Modifier.height(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (t.list == TaskList.DONE) PrimaryAction("Not done", { calls.uncomplete(t.id) })
                else PrimaryAction("Mark done", {
                    val id = t.id
                    calls.complete(id); onUndo(Undo("Done.") { calls.uncomplete(id) }); onClose()
                })
                Spacer(Modifier.width(Space.m))
                TextAction("Delete", {
                    val copy = t
                    calls.delete(copy.id); onUndo(Undo("Deleted.") { calls.restore(copy) }); onClose()
                })
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (dueDay ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextAction("Set", {
                    state.selectedDateMillis?.let { setDue(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }, emphasis = true)
            },
            dismissButton = { TextAction("Cancel", { pickDate = false }) },
        ) { DatePicker(state) }
    }
    if (pickTime) {
        val cur = if (t.dueHasTime && t.due != null) Instant.ofEpochMilli(t.due).atZone(zone).toLocalTime() else LocalTime.of(9, 0)
        val state = rememberTimePickerState(cur.hour, cur.minute, is24Hour = false)
        Dialog(onDismissRequest = { pickTime = false }) {
            val shape = RoundedCornerShape(Radius.block)
            Column(Modifier.clip(shape).background(c.raised).border(1.dp, c.edge, shape).padding(Space.xl)) {
                TimePicker(state)
                Row(Modifier.align(Alignment.End)) {
                    TextAction("Cancel", { pickTime = false })
                    TextAction("Set", { setDue(dueDay ?: LocalDate.now(), LocalTime.of(state.hour, state.minute)); pickTime = false }, emphasis = true)
                }
            }
        }
    }
}

/** When to check back on a Waiting for. */
internal fun followUpChoices(today: LocalDate) = listOf("In 3 days" to today.plusDays(3), "Next week" to today.plusDays(7), "In 2 weeks" to today.plusDays(14))

@Composable
internal fun Section(title: String) {
    Kicker(title, Modifier.padding(top = Space.xl, bottom = Space.xs))
}

/** Options as plain words that wrap; the chosen one is ink with a saffron underline. No chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun Words(options: List<String>, selected: Int?, onPick: (Int) -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    androidx.compose.foundation.layout.FlowRow(Modifier.offset(x = -Space.s).topRule(c.rule)) {
        options.forEachIndexed { i, o ->
            val on = i == selected
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.RadioButton) { onPick(i) }.padding(horizontal = Space.s), contentAlignment = Alignment.Center) {
                Line(
                    o, Type.action, if (on) c.ink else c.ink2,
                    Modifier.drawBehind { if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) } },
                    maxLines = 1,
                )
            }
        }
    }
}
