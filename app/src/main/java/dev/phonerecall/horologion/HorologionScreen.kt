package dev.phonerecall.horologion

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.LemnosHub
import dev.phonerecall.lemnos.unlockPrompt
import dev.phonerecall.mind.Cron
import dev.phonerecall.mind.SchedChange
import dev.phonerecall.mind.SchedJob
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
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What the page shows: every item, and the sources that couldn't be read (shown as one line each). */
data class HorologionView(val items: List<Item> = emptyList(), val missing: List<String> = emptyList(), val loading: Boolean = false)

/** Everything the page can do. Each one asks for his fingerprint first (or signs a request with it). */
class HorologionCalls(
    val back: () -> Unit = {},
    val refresh: () -> Unit = {},
    val athenaSchedule: (SchedJob, SchedChange) -> Unit = { _, _ -> },
    val runNow: (SchedJob) -> Unit = {},
    val phoneInterval: (PhoneJob, Long) -> Unit = { _, _ -> },
    val irisCron: (IrisJob, String, String?) -> Unit = { _, _, _ -> },
    val ask: (Owner, String, String, String, String) -> Unit = { _, _, _, _, _ -> },
    val toggle: (String, Boolean) -> Unit = { _, _ -> },
    val model: (ModelPick, String) -> Unit = { _, _ -> },
    val undoModel: (ModelPick) -> Unit = {},
)

@Composable
private fun Choices(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).offset(x = -Space.m), verticalAlignment = Alignment.CenterVertically) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Column(Modifier.heightIn(min = 44.dp).clickable(role = Role.RadioButton) { onSelect(i) }.padding(horizontal = Space.m), verticalArrangement = Arrangement.Center) {
                Line(label, Type.action, if (on) c.ink else c.ink3, Modifier.drawBehind {
                    if (on) { val w = 2.dp.toPx(); drawLine(c.accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) }
                }, maxLines = 1)
                Spacer(Modifier.height(Space.xs))
            }
        }
    }
}

private val PRESETS = listOf("Hourly", "Every N hours", "Daily", "Weekdays", "Weekly", "Advanced")
private val DAYS = DayOfWeek.entries.map { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }

/** The picker: presets, then the time; the raw cron sits under Advanced. Returns the cron, or null with why. */
@Composable
private fun SchedulePicker(initial: String, onCron: (String?, String?) -> Unit) {
    val p0 = remember(initial) { Cron.of(initial) }
    var kind by remember(initial) { mutableIntStateOf(when (p0) { is Cron.Preset.Hourly -> 0; is Cron.Preset.EveryHours -> 1; is Cron.Preset.Daily -> 2; is Cron.Preset.Weekdays -> 3; is Cron.Preset.Weekly -> 4; is Cron.Preset.Custom -> 5 }) }
    var hour by remember(initial) { mutableStateOf(when (p0) { is Cron.Preset.Daily -> p0.hour; is Cron.Preset.Weekdays -> p0.hour; is Cron.Preset.Weekly -> p0.hour; else -> 7 }.toString()) }
    var minute by remember(initial) { mutableStateOf(when (p0) { is Cron.Preset.Hourly -> p0.minute; is Cron.Preset.EveryHours -> p0.minute; is Cron.Preset.Daily -> p0.minute; is Cron.Preset.Weekdays -> p0.minute; is Cron.Preset.Weekly -> p0.minute; else -> 0 }.toString()) }
    var every by remember(initial) { mutableStateOf(((p0 as? Cron.Preset.EveryHours)?.hours ?: 3).toString()) }
    var day by remember(initial) { mutableIntStateOf(((p0 as? Cron.Preset.Weekly)?.day ?: DayOfWeek.SUNDAY).ordinal) }
    var raw by remember(initial) { mutableStateOf(initial) }
    val h = hour.toIntOrNull(); val m = minute.toIntOrNull(); val n = every.toIntOrNull()
    val cron: String? = when (kind) {
        0 -> m?.takeIf { it in 0..59 }?.let { Cron.Preset.Hourly(it).cron() }
        1 -> if (m in 0..59 && n in 2..12) Cron.Preset.EveryHours(n!!, m!!).cron() else null
        2 -> if (h in 0..23 && m in 0..59) Cron.Preset.Daily(h!!, m!!).cron() else null
        3 -> if (h in 0..23 && m in 0..59) Cron.Preset.Weekdays(h!!, m!!).cron() else null
        4 -> if (h in 0..23 && m in 0..59) Cron.Preset.Weekly(DayOfWeek.entries[day], h!!, m!!).cron() else null
        else -> raw.trim()
    }
    val why = when {
        cron == null -> "Hour 0-23, minute 0-59, every 2-12 hours"
        Cron.problem(cron) != null -> Cron.problem(cron)
        Cron.inDstWindow(cron) -> "Pick an hour outside 1-3 AM (daylight saving skips or repeats it)"
        else -> null
    }
    LaunchedEffect(cron, why) { onCron(cron.takeIf { why == null }, why) }
    val c = Athena.colors
    Choices(PRESETS, kind) { kind = it }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        if (kind == 1) UnderlineField(every, { every = it.filter(Char::isDigit).take(2) }, "Hours", Modifier.width(72.dp), keyboard = KeyboardType.Number)
        if (kind in 2..4) UnderlineField(hour, { hour = it.filter(Char::isDigit).take(2) }, "Hour (0-23)", Modifier.width(96.dp), keyboard = KeyboardType.Number)
        if (kind in 0..4) UnderlineField(minute, { minute = it.filter(Char::isDigit).take(2) }, "Minute", Modifier.width(88.dp), keyboard = KeyboardType.Number)
        if (kind == 5) UnderlineField(raw, { raw = it.take(60) }, "Cron: minute hour day month weekday", Modifier.weight(1f))
    }
    if (kind == 4) Choices(DAYS, day) { day = it }
    Line(why ?: "${Cron.words(cron!!)}  ·  $cron", Type.caption, if (why != null) c.alert else c.ink3, Modifier.padding(vertical = Space.xs))
}

/** The edit panel under a row: only what that item allows. */
@Composable
private fun Editor(item: Item, calls: HorologionCalls, close: () -> Unit) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(start = Space.m, bottom = Space.m)) {
        when (val a = item.action) {
            is Action.AthenaSchedule -> {
                var cron by remember { mutableStateOf<String?>(null) }
                SchedulePicker(a.job.cron) { v, _ -> cron = v }
                Row(Modifier.offset(x = -Space.s)) {
                    TextAction("Save", { cron?.let { calls.athenaSchedule(a.job, SchedChange("update", a.job.name, it)); close() } }, emphasis = true, enabled = cron != null && cron != a.job.cron)
                    TextAction(if (a.job.paused) "Resume" else "Pause", { calls.athenaSchedule(a.job, SchedChange(if (a.job.paused) "resume" else "pause", a.job.name)); close() })
                    TextAction("Run now", { calls.runNow(a.job); close() })
                    TextAction("Cancel", close)
                }
            }
            is Action.PhoneInterval -> {
                val b = a.job.bounds!!
                var pick by remember { mutableIntStateOf(b.choices.indexOf(a.job.everyMinutes).coerceAtLeast(0)) }
                Choices(b.choices.map { Horologion.every(it) }, pick) { pick = it }
                Row(Modifier.offset(x = -Space.s)) {
                    TextAction("Save", { calls.phoneInterval(a.job, b.choices[pick]); close() }, emphasis = true, enabled = b.choices[pick] != a.job.everyMinutes)
                    TextAction("Cancel", close)
                }
            }
            is Action.IrisCron -> {
                var cron by remember { mutableStateOf<String?>(null) }
                SchedulePicker(a.job.cron ?: "0 7 * * *") { v, _ -> cron = v }
                Line("Sent to Iris as your request, signed with your fingerprint. She changes it herself and confirms.", Type.caption, c.ink3)
                Row(Modifier.offset(x = -Space.s)) {
                    TextAction("Ask Iris", { cron?.let { calls.irisCron(a.job, "change", it); close() } }, emphasis = true, enabled = cron != null && cron != a.job.cron)
                    TextAction(if (a.job.status == "paused") "Ask to resume" else "Ask to pause", { calls.irisCron(a.job, if (a.job.status == "paused") "resume" else "pause", null); close() })
                    TextAction("Cancel", close)
                }
            }
            is Action.Ask -> {
                var verb by remember { mutableIntStateOf(1) }
                var note by remember { mutableStateOf("") }
                Choices(listOf("Turn on", "Turn off", "Change"), verb) { verb = it }
                UnderlineField(note, { note = it.take(300) }, "In your words (optional)", Modifier.fillMaxWidth(), style = Type.small)
                Line("Sent to ${a.owner.label} as your request, signed with your fingerprint. ${if (a.owner == Owner.IRIS) "She applies it and confirms" else "He applies it and confirms"}.", Type.caption, c.ink3)
                Row(Modifier.offset(x = -Space.s)) {
                    TextAction("Ask ${a.owner.label}", { calls.ask(a.owner, a.thing, a.name, listOf("enable", "disable", "change")[verb], note); close() }, emphasis = true, enabled = verb != 2 || note.isNotBlank())
                    TextAction("Cancel", close)
                }
            }
            is Action.Model -> {
                val p = a.pick
                var pick by remember { mutableIntStateOf(p.options.indexOf(p.current).coerceAtLeast(0)) }
                Choices(p.options, pick) { pick = it }
                Line("Only models that can do this. Agents get a new version on Athena's next sync; the rest use it on their next call.", Type.caption, c.ink3)
                Row(Modifier.offset(x = -Space.s)) {
                    TextAction("Save", { calls.model(p, p.options[pick]); close() }, emphasis = true, enabled = p.options[pick] != p.current)
                    p.undo?.let { u -> TextAction("Undo to $u", { calls.undoModel(p); close() }) }
                    TextAction("Cancel", close)
                }
            }
            is Action.Toggle -> Row(Modifier.offset(x = -Space.s)) {
                TextAction(if (a.on) "Turn off" else "Turn on", { calls.toggle(a.key, a.on); close() }, emphasis = true)
                TextAction("Cancel", close)
            }
            null -> Unit
        }
    }
}

@Composable
private fun ItemRow(item: Item, open: Boolean, onOpen: () -> Unit) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().topRule(c.rule).then(if (item.action != null) Modifier.clickable(role = Role.Button, onClick = onOpen) else Modifier).padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Line(item.name, Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 2)
            Line(item.what, Type.caption, c.ink2, maxLines = 2)
            item.value?.let { Line(it, Type.caption, c.ink3, maxLines = 3) }
            Line(item.where, Type.caption, c.ink3, maxLines = 1)
        }
        if (item.action != null) {
            Spacer(Modifier.width(Space.s))
            StatusWord(if (open) "Close" else when (item.action) { is Action.Ask, is Action.IrisCron -> "Ask"; is Action.Toggle -> if (item.value == "on") "On" else "Off"; else -> "Change" },
                if (open) Tone.ACTIVE else Tone.QUIET, Modifier.padding(top = 2.dp))
        }
    }
}

/** Horologion from plain data (so it renders in shots). */
@Composable
fun HorologionContent(view: HorologionView, gutter: Dp, wide: Boolean, calls: HorologionCalls, tab0: Tab = Tab.JOBS, query0: String = "") {
    val c = Athena.colors
    var tab by rememberSaveable { mutableStateOf(tab0) }
    var query by rememberSaveable { mutableStateOf(query0) }
    var open by remember { mutableStateOf<String?>(null) }
    val shown = Horologion.search(view.items.filter { query.isNotBlank() || it.tab == tab }, query)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.HOROLOGION.title(), gutter, emblem = Place.HOROLOGION, subtitle = Place.HOROLOGION.subtitle()) {
            TextAction("Refresh", calls.refresh)
            TextAction(Place.SETTINGS.title(), calls.back)
        }
        Column(Modifier.padding(horizontal = gutter)) {
            UnderlineField(query, { query = it.take(60) }, "Search everything: a job, a skill, a host, a model", Modifier.fillMaxWidth(), style = Type.small)
            if (query.isBlank()) Choices(Tab.entries.map { it.label }, tab.ordinal) { tab = Tab.entries[it]; open = null }
            else Line("${shown.size} found across every tab", Type.caption, c.ink3, Modifier.padding(vertical = Space.s))
            if (view.loading) Line("Reading what is set up…", Type.caption, c.ink3)
            view.missing.forEach { Line(it, Type.caption, c.alert, maxLines = 2) }
        }
        val owners = Horologion.grouped(shown)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            if (owners.isEmpty() && !view.loading) item { Line(if (query.isBlank()) "Nothing here yet." else "Nothing matches.", Type.small, c.ink3, Modifier.padding(vertical = Space.l)) }
            for ((owner, groups) in owners) {
                item(key = "o-${owner.name}") { Kicker(owner.label, Modifier.padding(top = Space.xl, bottom = Space.xs), color = c.accentInk) }
                for ((group, list) in groups) {
                    item(key = "g-${owner.name}-$group") { Line(group, Type.caption, c.ink3, Modifier.padding(top = Space.s, bottom = Space.xxs)) }
                    items(list, key = { it.id }) { i ->
                        Column(if (wide) Modifier.widthIn(max = 760.dp) else Modifier) {
                            ItemRow(i, open == i.id) { open = if (open == i.id) null else i.id }
                            if (open == i.id) Editor(i, calls) { open = null }
                        }
                    }
                }
            }
        }
    }
}

/** Reads everything (Athena and the phone here, Iris, Atlas and the server through Lemnos when paired). Blocking. */
fun load(c: android.content.Context, brain: BrainHub, paired: Boolean): HorologionView {
    val zone = ZoneId.of(dev.phonerecall.mind.Schedules.zone(ZoneId.systemDefault()))
    val core = BrainCore.get(c)
    val now = Instant.now()
    val items = ArrayList<Item>()
    val missing = ArrayList<String>()
    val jobs = core.mindHost.locked { book.jobs() to { n: String -> lastRun(n) } }
    items += Horologion.athenaJobs(jobs.first, jobs.second, zone, now)
    items += Horologion.phoneJobs(PhoneJobs.all(c), zone)
    runCatching { items += Horologion.athena(AthenaInventory.facts(c, brain.settings.value, brain.mind.value.heartbeat.on)) }.onFailure { missing += "Athena's setup: ${it.message}" }
    if (!paired) missing += "Iris, Atlas and the server show once this phone is paired with ${Place.VPS.myth}."
    else {
        runCatching { items += Horologion.irisJobs(dev.phonerecall.lemnos.Gateway.irisJobs()) }.onFailure { missing += "Iris's crons: ${it.message}" }
        runCatching { items += Horologion.iris(dev.phonerecall.lemnos.Gateway.irisSetup()) }.onFailure { missing += "Iris's setup: ${it.message}" }
        runCatching { items += Horologion.atlas(dev.phonerecall.lemnos.Gateway.atlasSetup()) }.onFailure { missing += "Atlas's setup: ${it.message}" }
        runCatching { items += Horologion.server(dev.phonerecall.lemnos.Gateway.serverSetup(), zone) }.onFailure { missing += "The server: ${it.message}" }
    }
    return HorologionView(items, missing)
}

@Composable
fun HorologionScreen(brain: BrainHub, lemnos: LemnosHub, wide: Boolean, gutter: Dp, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val s by lemnos.state.collectAsStateWithLifecycle()
    val paired = s.pairing == DeviceKeys.State.PAIRED
    var view by remember { mutableStateOf(HorologionView(loading = true)) }
    var note by remember { mutableStateOf<String?>(null) }
    val reload: () -> Unit = { scope.launch { view = view.copy(loading = true); view = withContext(Dispatchers.IO) { runCatching { load(app, brain, paired) }.getOrElse { HorologionView(missing = listOf("Couldn't read: ${it.message}")) } } } }
    LaunchedEffect(paired) { reload() }
    val core = remember { BrainCore.get(app) }
    /** His fingerprint, then [block] off the main thread, then a fresh read. */
    fun withPrint(what: String, block: () -> Unit) = unlockPrompt(ctx, what, Place.HOROLOGION.myth, {
        scope.launch { note = withContext(Dispatchers.IO) { runCatching { block(); null }.getOrElse { it.message ?: "Failed" } }; reload() }
    }, { note = it })
    val calls = HorologionCalls(
        back = onBack, refresh = reload,
        athenaSchedule = { job, change ->
            withPrint("Change ${job.full}") {
                core.mindHost.locked { book.apply(change); ensure(); applyPauses(runCatching { core.spend.check() }.isFailure) }
                core.audit.log("The owner", "schedule ${change.action}", job.full + (change.cron?.let { " (${Cron.words(it)})" } ?: ""))
            }
        },
        runNow = { job -> withPrint("Run ${job.full} now") { core.mindHost.locked { runNow(job.name, trial = false) }; core.audit.log("The owner", "run now", job.full) } },
        phoneInterval = { job, minutes -> withPrint("Change ${job.name}") { if (!PhoneJobs.set(app, job.id, minutes)) error("Out of bounds"); core.audit.log("The owner", "Horologion", "${job.name}: ${Horologion.every(minutes)}") } },
        irisCron = { job, action, cron ->
            val r = OwnerRequests.cron(job, action, cron, UUID.randomUUID().toString().take(8))
            if (r == null) note = "That schedule isn't valid" else DeviceKeys.approve(ctx, r.first, r.second, onToken = { tok ->
                lemnos.requestFromOwner(OwnerRequests.outgoing(r.first, r.second, tok)) { err -> note = err ?: "Sent to Iris. She confirms in her room." }
            }, onFail = { note = it })
        },
        ask = { owner, thing, name, action, words ->
            val r = OwnerRequests.setup(owner, thing, name, action, words, UUID.randomUUID().toString().take(8))
            if (r != null) DeviceKeys.approve(ctx, r.first, r.second, onToken = { tok ->
                lemnos.requestFromOwner(OwnerRequests.outgoing(r.first, r.second, tok)) { err -> note = err ?: "Sent to ${owner.label}." }
            }, onFail = { note = it })
        },
        model = { p, id -> withPrint("${p.label}: $id") { if (!AthenaInventory.setModel(p.key, id)) error("${p.label} can't use $id"); core.audit.log("The owner", "model", "${p.label}: ${p.current} to $id") } },
        undoModel = { p -> withPrint("${p.label}: undo") { if (!AthenaInventory.undoModel(p.key)) error("Nothing to undo"); core.audit.log("The owner", "model undo", "${p.label}: back to ${p.undo}") } },
        toggle = { key, on -> withPrint("Turn ${if (on) "off" else "on"}") { if (!AthenaInventory.toggle(app, key, on, { brain.toggleAuto(it) }, { brain.setHeartbeat(it) })) error("Not a switch") } },
    )
    Column(Modifier.fillMaxSize()) {
        HorologionContent(view.copy(missing = view.missing + listOfNotNull(note)), gutter, wide, calls)
    }
}
