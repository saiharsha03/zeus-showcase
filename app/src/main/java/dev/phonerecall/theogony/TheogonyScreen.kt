package dev.phonerecall.theogony

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.AthenaLine
import dev.phonerecall.brain.UserLine
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule

/** What the screen can do. Defaults do nothing, for previews and shots. */
data class TheogonyCalls(
    val skipWaiting: () -> Unit = {},
    val answer: (String, String) -> Unit = { _, _ -> },
    val skip: (String) -> Unit = {},
    val later: () -> Unit = {},
    val forget: (String) -> Unit = {},
    val confirm: (String) -> Unit = {},
    val milestoneNotify: (Boolean) -> Unit = {},
    val decide: (String, Boolean) -> Unit = { _, _ -> },
    val refresh: () -> Unit = {},
    val allow: (() -> Unit)? = null,
    val done: () -> Unit = {},
    /** Free text: the current question's id (or null) and his words. */
    val say: (String?, String) -> Unit = { _, _ -> },
    /** A bundle: its id, the fact keys he left ticked, and the words his line shows. */
    val confirmBundle: (String, Set<String>, String) -> Unit = { _, _, _ -> },
    val askIris: (String) -> Unit = {},
)

private val READS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG)

@Composable
fun TheogonyScreen(gutter: Dp, wide: Boolean, done: () -> Unit) {
    val ctx = LocalContext.current
    val hub = remember { TheogonyHub.get(ctx) }
    val s by hub.state.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf(READS.any { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missing = READS.any { p -> ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED }
        hub.refresh()
    }
    LaunchedEffect(Unit) { if (s.phase == Phase.NONE) hub.start() }
    TheogonyContent(
        s, gutter, wide,
        TheogonyCalls(
            hub::skipWaiting, hub::answer, hub::skip, { hub.later(); done() }, hub::forget, hub::confirm, hub::setMilestoneNotify, hub::decide, hub::refresh,
            allow = if (missing) ({ ask.launch(READS) }) else null, done = done,
            say = hub::say, confirmBundle = hub::confirmBundle, askIris = hub::askIris,
        ),
    )
}

/** The conversation: her lines and his, her current question's quick replies under it, and a box for his own words. */
@Composable
fun TheogonyContent(s: TheogonyState, gutter: Dp, wide: Boolean, cb: TheogonyCalls) {
    val c = Athena.colors
    var draft by rememberSaveable { mutableStateOf("") }
    var showFacts by rememberSaveable { mutableStateOf(false) }
    val list = rememberLazyListState()
    val q = s.question
    val seeding = s.phase == Phase.NONE || s.phase == Phase.SEEDING || s.phase == Phase.ASKING
    val head = if (seeding) 1 else 0
    LaunchedEffect(s.turns.size, q?.id) {
        val last = head + s.turns.size - (if (q == null) 1 else 0)
        if (last > 0) list.animateScrollToItem(last)
    }
    val send: () -> Unit = { if (draft.isNotBlank()) { cb.say(q?.id, draft.trim()); draft = "" } }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(Place.SEED.title(), gutter, emblem = Place.SEED, subtitle = if (s.left > 0) "${s.left} left" else Place.SEED.subtitle()) {
            if (s.phase == Phase.INTERVIEW) TextAction("Later", cb.later)
            if (s.phase == Phase.DONE) TextAction("Done", cb.done)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.s, bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (seeding) item(key = "seeding") { Seeding(s, cb) }
            itemsIndexed(s.turns, key = { _, t -> t.id }) { i, t ->
                if (t.who == ChatTurn.YOU) UserLine(t.text)
                else Column {
                    AthenaLine(t.text, first = s.turns.getOrNull(i - 1)?.who != ChatTurn.ATHENA)
                    // A bundle already answered keeps its lines, quietly; the open one is the tick list below.
                    if (t.items.isNotEmpty() && t.qid != q?.id) for (line in t.items) Line("· $line", Type.small, c.ink3, Modifier.padding(start = 40.dp, top = Space.xs).widthIn(max = 560.dp))
                }
            }
            if (q != null) item(key = "reply-" + q.id) { Reply(q, s, cb, wide) { draft = it(draft) } }
            if (s.phase == Phase.DONE) item(key = "refresh") { Row(Modifier.padding(start = 40.dp)) { TextAction("Refresh what you know", cb.refresh, emphasis = true) } }
            if (s.irisPending && !seeding) item(key = "iris") { Line("Iris is still looking. What she finds comes into this chat.", Type.caption, c.ink3, Modifier.padding(start = 40.dp)) }
            val pending = s.settings.filter { it.status == "pending" }
            if (pending.isNotEmpty()) {
                item(key = "ok") { Kicker("Waiting for your OK", Modifier.padding(top = Space.m)) }
                for (p in pending) item(key = p.id) {
                    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Line(p.label, Type.small, c.ink, Modifier.weight(1f))
                        TextAction("Turn on", { cb.decide(p.id, true) }, emphasis = true)
                        TextAction("No", { cb.decide(p.id, false) })
                    }
                }
            }
            if (s.facts.isNotEmpty() && !seeding) {
                item(key = "facts") {
                    Row(Modifier.padding(top = Space.l)) {
                        TextAction(if (showFacts) "Hide what I know" else "What I know about you (${s.facts.size})", { showFacts = !showFacts })
                    }
                }
                if (showFacts) facts(s, cb)
            }
        }
        if (!seeding) {
            Row(
                Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Space.s, bottom = Space.l).widthIn(max = 720.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val hint = when {
                    q == null -> "Tell me anything else"
                    q.bundle.isNotEmpty() -> "Or tell me what's off"
                    else -> q.hint ?: "Or in your own words. Skip and later work too"
                }
                UnderlineField(draft, { draft = it }, hint, Modifier.weight(1f), onSubmit = send)
                Spacer(Modifier.width(Space.s))
                TextAction("Send", send, emphasis = true, enabled = draft.isNotBlank())
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.facts(s: TheogonyState, cb: TheogonyCalls) {
    item(key = "facts-note") {
        Line(
            "Each line is in my memory with where it came from. Observed: a signal or a document. Inferred: my guess, which nothing acts on until you confirm it. Forget removes a line for good.",
            Type.small, Athena.colors.ink3, Modifier.widthIn(max = 560.dp),
        )
    }
    for (p in SeedPath.entries) {
        val mine = s.facts.filter { it.path == p }
        if (mine.isEmpty()) continue
        item(key = "h-" + p.name) { Kicker(p.title, Modifier.padding(top = Space.s), color = Athena.colors.ink2) }
        for (f in mine) item(key = f.key) { FactLine(f, cb.forget, cb.confirm) }
    }
    if (s.facts.any { it.milestone && it.status == Status.CONFIRMED }) item(key = "ms") {
        val c = Athena.colors
        Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Line("Milestones show as a quiet line in ${Place.CALENDAR.title()} on the day. A notification too?", Type.small, c.ink2, Modifier.weight(1f))
            TextAction(if (s.milestoneNotify) "On" else "Off", { cb.milestoneNotify(!s.milestoneNotify) }, emphasis = s.milestoneNotify)
        }
    }
}

/** Step 1 as her first line: what she's doing, each reader's progress, and the way on without Iris. */
@Composable
private fun Seeding(s: TheogonyState, cb: TheogonyCalls) {
    val c = Athena.colors
    val learned = s.facts.size
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        AthenaLine(
            "Give me a minute. I'm asking Iris what she may share and reading this phone, words only. Nothing with numbers, money, health or where you go is kept. " +
                if (learned == 1) "1 thing so far." else "$learned things so far.",
            first = true,
        )
        Column(Modifier.padding(start = 40.dp, top = Space.m).widthIn(max = 560.dp)) {
            for (st in s.steps) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).topRule(c.rule).padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(st.label + if (st.status == StepStatus.RUNNING) "…" else "", Type.small, if (st.status == StepStatus.WAITING) c.ink3 else c.ink)
                        st.note?.let { Line(it, Type.caption, c.ink3) }
                    }
                    when (st.status) {
                        StepStatus.WAITING -> StatusWord("Waiting", Tone.QUIET)
                        StepStatus.RUNNING -> StatusWord("Working", Tone.ACTIVE)
                        StepStatus.DONE -> StatusWord(if (st.learned == 0) "Nothing new" else "${st.learned} learned", Tone.OK)
                        StepStatus.SKIPPED -> StatusWord("Skipped", Tone.QUIET)
                    }
                }
            }
            cb.allow?.let { allow ->
                Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Line("Calendar, contacts or calls aren't allowed yet. I read them on the phone only.", Type.small, c.ink2, Modifier.weight(1f))
                    TextAction("Allow", allow, emphasis = true)
                }
            }
            if (s.phase == Phase.ASKING) {
                Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Line("Iris is looking through your mail and calendars, up to 10 minutes.", Type.small, c.ink2, Modifier.weight(1f))
                    TextAction("Go on without her", cb.skipWaiting, emphasis = true)
                }
            }
        }
    }
}

/**
 * The answers to her current question, under her line: a tick list for a bundle, else quick-reply chips
 * (several for a multi question, then Send), Ask Iris when she can, and Skip. `edit` changes the text box.
 */
@Composable
private fun Reply(q: Question, s: TheogonyState, cb: TheogonyCalls, wide: Boolean, edit: ((String) -> String) -> Unit) {
    Column(Modifier.padding(start = 40.dp).widthIn(max = if (wide) 600.dp else 560.dp)) {
        if (q.bundle.isNotEmpty()) Bundle(q, s, cb)
        else {
            var picked by remember(q.id) { mutableStateOf(emptyList<String>()) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                for (chip in q.chips) {
                    Chip(chip, chip in picked) {
                        when {
                            // "I'll type it": the box below is where he types.
                            chip.startsWith("I'll type") -> {}
                            q.prefill && !chip.startsWith("None") -> edit { text -> (text.trim().trimEnd(';').let { if (it.isEmpty()) "" else "$it; " }) + "$chip, " }
                            q.multi -> picked = if (chip in picked) picked - chip else picked + chip
                            else -> cb.answer(q.id, chip)
                        }
                    }
                }
                if (s.canAskIris) Chip(Interview.ASK_IRIS, false, quiet = true) { cb.askIris(q.id) }
                Chip(Interview.SKIP, false, quiet = true) { cb.skip(q.id) }
            }
            if (picked.isNotEmpty()) Row(Modifier.padding(top = Space.m)) { PrimaryAction("Send", { cb.answer(q.id, picked.joinToString("; ")) }) }
        }
    }
}

/** "These look right?": every line ticked to start; Send keeps the ticked ones and forgets the rest. */
@Composable
private fun Bundle(q: Question, s: TheogonyState, cb: TheogonyCalls) {
    val c = Athena.colors
    val lines = q.bundle.mapNotNull { k -> s.facts.firstOrNull { it.key == k } }
    var kept by remember(q.id) { mutableStateOf(lines.map { it.key }.toSet()) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.block)).background(c.raised).border(1.dp, c.edge, RoundedCornerShape(Radius.block)).padding(horizontal = Space.m, vertical = Space.s)) {
        for ((i, f) in lines.withIndex()) {
            val on = f.key in kept
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).let { if (i > 0) it.topRule(c.rule) else it }
                    .clickable(role = Role.Checkbox) { kept = if (on) kept - f.key else kept + f.key },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val box = RoundedCornerShape(Radius.forSize(20.dp))
                Box(Modifier.size(20.dp).clip(box).border(1.5.dp, if (on) c.accent else c.ink3, box), contentAlignment = Alignment.Center) {
                    if (on) Icon(AthenaIcons.Check, contentDescription = null, Modifier.size(16.dp), tint = c.accentInk)
                }
                Spacer(Modifier.width(Space.m))
                Line(f.text, Type.small, if (on) c.ink else c.ink3, Modifier.weight(1f).padding(vertical = Space.s))
            }
        }
    }
    Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
        val words = when { kept.size == lines.size -> "Yes, all right"; kept.isEmpty() -> "None of these"; else -> "${kept.size} of ${lines.size} are right" }
        PrimaryAction(if (kept.size == lines.size) "Yes, all right" else "Keep ${kept.size} of ${lines.size}", { cb.confirmBundle(q.id, kept, words) })
        Spacer(Modifier.width(Space.s))
        TextAction(Interview.SKIP, { cb.skip(q.id) })
    }
}

@Composable
private fun Chip(label: String, on: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier.heightIn(min = 44.dp).clip(shape)
            .background(if (on) c.accent else c.surface)
            .border(1.dp, if (on) c.accent else if (quiet) c.rule else c.edge, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.l),
        contentAlignment = Alignment.Center,
    ) { Line(label, Type.action, if (on) c.onAccent else if (quiet) c.ink2 else c.ink, maxLines = 1) }
}

@Composable
private fun FactLine(f: SeedFact, forget: (String) -> Unit, confirm: (String) -> Unit) {
    val c = Athena.colors
    var confirm by remember(f.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(f.text, Type.small, c.ink)
            Line(
                when (f.source) { "iris" -> "From Iris"; "you" -> "You said"; "people" -> "From contacts and chats"; else -> "From the phone" } + ", " + f.status,
                Type.caption, if (f.status == Status.INFERRED) c.accentInk else c.ink3,
            )
        }
        if (f.status == Status.INFERRED) TextAction("Confirm", { confirm(f.key) }, emphasis = true)
        TextAction(if (confirm) "Tap to forget" else "Forget", { if (confirm) forget(f.key) else confirm = true })
    }
}
