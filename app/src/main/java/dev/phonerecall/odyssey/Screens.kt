package dev.phonerecall.odyssey

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.SealedSecret
import dev.phonerecall.home.Fmt
import dev.phonerecall.lemnos.TextTabs
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/** What Odyssey may use, as the screen shows it. */
data class Perms(
    val fine: Boolean = false, val background: Boolean = false, val activity: Boolean = false,
    val usage: Boolean = false, val tomtom: Boolean = false, val ny511: Boolean = false,
)

class OdysseyCallbacks(
    val onStart: () -> Unit = {}, val onPause: (Boolean) -> Unit = {},
    val onAllowFine: () -> Unit = {}, val onAllowBackground: () -> Unit = {}, val onAllowActivity: () -> Unit = {}, val onAllowUsage: () -> Unit = {},
    val onYes: (String) -> Unit = {}, val onNo: (String) -> Unit = {}, val onMaps: (Double, Double) -> Unit = { _, _ -> },
    val onForgetVisit: (String) -> Unit = {}, val onForgetDay: (LocalDate) -> Unit = {}, val onForgetPlace: (String) -> Unit = {},
    val onRename: (String, String) -> Unit = { _, _ -> }, val onLink: (String) -> Unit = {}, val onHere: (String) -> Unit = {},
    val onSettings: (OdysseySettings) -> Unit = {}, val openVault: () -> Unit = {}, val onWipe: () -> Unit = {},
    val onLease: (Lease) -> Unit = {}, val onAskIris: () -> Unit = {}, val onDeleteRoute: (String) -> Unit = {},
    val onTag: (String, TripTag?) -> Unit = { _, _ -> },
    /** Place questions (Learn.kt): yes/no (Keep/Undo when applied on its own), his own kind and name, ignore. */
    val onGuess: (String, Boolean) -> Unit = { _, _ -> }, val onGuessKind: (String, Kind, String?) -> Unit = { _, _, _ -> }, val onGuessIgnore: (String) -> Unit = {},
)

private val TABS = listOf("Timeline", "Places", "Habits", "Logs", "Drives", "Car")
private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** Odyssey: his own timeline, on the phone only. Visits and trips by day, places, habits, logs and drives. */
@Composable
fun OdysseyScreen(wide: Boolean, gutter: Dp, openVault: () -> Unit) {
    val ctx = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val d by OdysseyStore.state.collectAsStateWithLifecycle()
    val routes by RouteStore.state.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf(Perms()) }
    var sections by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    val read = {
        scope.launch {
            perms = withContext(Dispatchers.IO) {
                OdysseyStore.load(ctx)
                RouteStore.load(ctx)
                runCatching { Odyssey.learn(ctx) }
                sections = BrainCore.get(ctx).sections.visible().map { it.id to it.name }
                Perms(Odyssey.canFine(ctx), Odyssey.canBackground(ctx), Odyssey.canActivity(ctx), Odyssey.canUsage(ctx), tomTomKey(ctx).has(), ny511Key(ctx).has())
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { read() }
    val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { read() }
    val askOne = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { read() }
    fun io(block: () -> Unit) = thread(name = "odyssey-ui") { block() }
    val cb = OdysseyCallbacks(
        onStart = { io { Odyssey.settings(ctx) { it.copy(on = true, paused = false) } } },
        onPause = { p -> io { Odyssey.settings(ctx) { it.copy(paused = p) } } },
        onAllowFine = { askFine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        onAllowBackground = { askOne.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        onAllowActivity = { askOne.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
        onAllowUsage = { runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        onYes = { id -> io { Odyssey.accept(ctx, id) } },
        onNo = { id -> io { Odyssey.decline(ctx, id) } },
        onMaps = { lat, lng -> runCatching { ctx.startActivity(Odyssey.mapsIntent(lat, lng)) } },
        onForgetVisit = { id -> io { Odyssey.forgetVisit(ctx, id) } },
        onForgetDay = { day -> io { Odyssey.forgetDay(ctx, day) } },
        onForgetPlace = { id -> io { Odyssey.forgetPlace(ctx, id) } },
        onRename = { id, name -> io { Odyssey.rename(ctx, id, name) } },
        onLink = { text -> io { message = Odyssey.shared(ctx, text) } },
        onHere = { name ->
            dev.phonerecall.places.Places.here(ctx, { lat, lng -> io { message = Odyssey.shared(ctx, "$name\ngeo:$lat,$lng") } }, { message = it })
        },
        onSettings = { s -> io { Odyssey.settings(ctx) { s } } },
        openVault = openVault,
        onWipe = { io { OdysseyStore.wipe(ctx); RouteStore.wipe(ctx); Odyssey.settings(ctx) { it.copy(on = false) } } },
        onLease = { l -> io { Garage.setLease(ctx, l) } },
        onAskIris = { io { Garage.reaskLease(ctx) } },
        onDeleteRoute = { id -> io { RouteStore.delete(ctx, id) } },
        onTag = { id, t -> io { Odyssey.tagDrive(ctx, id, t) } },
        onGuess = { id, yes -> io { Odyssey.answerGuess(ctx, id, yes) } },
        onGuessKind = { id, k, name -> io { Odyssey.relabelGuess(ctx, id, k, name) } },
        onGuessIgnore = { id -> io { Odyssey.ignoreGuess(ctx, id) } },
    )
    OdysseyContent(d, sections, perms, ZonedDateTime.now(), gutter, wide, cb, message, routes = routes)
}

@Composable
fun OdysseyContent(
    d: OdysseyData, sections: List<Pair<Long, String>>, perms: Perms, now: ZonedDateTime, gutter: Dp, wide: Boolean,
    cb: OdysseyCallbacks, message: String? = null, startTab: Int = 0,
    /** Kept drive routes by drive id (RouteStore); `online` off draws maps without tiles (previews). */
    routes: Map<String, DriveRoute> = emptyMap(), online: Boolean = true, openDrive: String? = null,
) {
    val c = Athena.colors
    var tab by rememberSaveable { mutableStateOf(if (CarNav.take()) 5 else startTab) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.PLACES.title(), gutter, emblem = Place.PLACES, subtitle = Place.PLACES.subtitle()) {
            if (d.settings.on) TextAction(if (d.settings.paused) "Resume" else "Pause tracking", { cb.onPause(!d.settings.paused) }, emphasis = d.settings.paused)
        }
        if (!d.settings.on) { Setup(perms, gutter, cb); return@Column }
        if (d.settings.paused) Line("Paused. Nothing is noted and no drive is sampled until you resume.", Type.small, c.accentInk, Modifier.padding(horizontal = gutter, vertical = Space.xs))
        message?.let { Line(it, Type.small, c.ink2, Modifier.padding(horizontal = gutter, vertical = Space.xs)) }
        TextTabs(TABS, tab, { tab = it }, gutter)
        val ms = now.toInstant().toEpochMilli()
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl), modifier = Modifier.fillMaxWidth().then(if (wide) Modifier.widthIn(max = 720.dp) else Modifier)) {
            when (tab) {
                0 -> timeline(d, sections, now, cb)
                1 -> places(d, perms, cb)
                2 -> habits(d, ms)
                3 -> logs(d, now, cb)
                4 -> drives(d, routes, ms, now.zone, online, cb, openDrive)
                else -> car(d, now, cb.onLease, cb.onAskIris)
            }
        }
    }
}

// ---- setup ----

@Composable
private fun Setup(p: Perms, gutter: Dp, cb: OdysseyCallbacks) {
    val c = Athena.colors
    LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
        item {
            Line("Your own timeline, kept on this phone.", Type.headline, c.ink, Modifier.padding(top = Space.l).widthIn(max = 560.dp))
            Line(
                "Odyssey notes stays of five minutes or more and the drives between them, names places from your receipts and the Geocoder, and learns your commute. " +
                    "Nothing raw leaves the phone: Athena hears only things like \"usual office commute 22 min\". TomTom gets the two ends of a trip when it checks traffic.",
                Type.small, c.ink2, Modifier.padding(top = Space.s, bottom = Space.l).widthIn(max = 560.dp),
            )
        }
        item { Permission("Precise location", "One fix when a stay starts; GPS only during a drive.", p.fine, true, cb.onAllowFine) }
        item { Permission("All the time", "So a stay can be noted with Zeus closed. Android asks on its own page: choose Allow all the time.", p.background, p.fine, cb.onAllowBackground) }
        item { Permission("Physical activity", "Driving, still and walking, from Play services. This is what wakes Odyssey; no tracking runs in between.", p.activity, true, cb.onAllowActivity) }
        item { Permission("Usage access (optional)", "Which apps were on screen during a drive, for the driving report. Settings > Usage access > Zeus.", p.usage, true, cb.onAllowUsage) }
        item {
            Row(Modifier.padding(top = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                if (p.fine && p.background && p.activity) PrimaryAction("Start Odyssey", cb.onStart)
                else Line("Precise, all the time and physical activity are needed to start.", Type.small, c.ink3)
            }
        }
    }
}

@Composable
private fun Permission(title: String, about: String, granted: Boolean, can: Boolean, onAllow: () -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).then(if (!granted && can) Modifier.clickable(role = Role.Button, onClick = onAllow) else Modifier).padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
            Line(about, Type.small, c.ink3, Modifier.widthIn(max = 540.dp))
        }
        Spacer(Modifier.width(Space.m))
        StatusWord(if (granted) "Allowed" else if (can) "Allow" else "After precise", if (granted) Tone.OK else if (can) Tone.ACTIVE else Tone.QUIET)
    }
}

// ---- timeline ----

private sealed class Entry(val start: Long) {
    class V(val v: Visit, val p: KnownPlace?) : Entry(v.start)
    class T(val t: Trip, val drive: DriveReport?) : Entry(t.start)
    class Here(val s: OpenStay) : Entry(s.start)
}

private fun LazyListScope.timeline(d: OdysseyData, sections: List<Pair<Long, String>>, now: ZonedDateTime, cb: OdysseyCallbacks) {
    item { DayPicker(d, sections, now, cb) }
}

@Composable
private fun DayPicker(d: OdysseyData, sections: List<Pair<Long, String>>, now: ZonedDateTime, cb: OdysseyCallbacks) {
    var day by rememberSaveable { mutableStateOf(now.toLocalDate().toEpochDay()) }
    val c = Athena.colors
    val date = LocalDate.ofEpochDay(day)
    val zone = now.zone
    val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
    Column {
        val ms = now.toInstant().toEpochMilli()
        for (rc in d.checks.filter { it.expiresAt > ms }.sortedBy { it.leaveBy }.take(2)) RoadBlock(rc, zone, cb)
        OfferBlocks(d, sections, cb)
        Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            TextAction("Earlier", { day -= 1 })
            Line(if (date == now.toLocalDate()) "Today" else DAY.format(date), Type.title, c.ink, Modifier.weight(1f).padding(horizontal = Space.s), maxLines = 1)
            TextAction("Later", { if (day < now.toLocalDate().toEpochDay()) day += 1 }, enabled = day < now.toLocalDate().toEpochDay())
        }
        val entries = buildList<Entry> {
            d.visits.filter { it.start < to && it.end > from }.forEach { add(Entry.V(it, d.place(it.placeId))) }
            d.trips.filter { it.start < to && it.end > from }.forEach { t -> add(Entry.T(t, d.drives.firstOrNull { it.id == t.driveId })) }
            d.stay?.takeIf { it.start < to && date == now.toLocalDate() }?.let { add(Entry.Here(it)) }
        }.sortedBy { it.start }
        if (entries.isEmpty()) Line(if (date == now.toLocalDate()) "Nothing yet today. Stays of five minutes or more show here." else "Nothing kept for this day.", Type.body, c.ink2, Modifier.padding(vertical = Space.m))
        for ((i, e) in entries.withIndex()) Strip(e, d, zone, first = i == 0, last = i == entries.lastIndex, cb)
        if (entries.isNotEmpty()) Row(Modifier.padding(top = Space.m)) {
            TextAction(if (confirm) "Tap again to forget this day" else "Forget this day", { if (confirm) { cb.onForgetDay(date); confirm = false } else confirm = true })
        }
    }
}

/** One row of the day: a dot on a hairline for a visit, a dashed run for a trip. No map. */
@Composable
private fun Strip(e: Entry, d: OdysseyData, zone: ZoneId, first: Boolean, last: Boolean, cb: OdysseyCallbacks) {
    val c = Athena.colors
    val rule = c.rule
    val accent = c.accent
    val ink3 = c.ink3
    val visit = e !is Entry.T
    Row(Modifier.fillMaxWidth().heightIn(min = if (visit) 64.dp else 44.dp)) {
        Box(
            Modifier.width(28.dp).heightIn(min = if (visit) 64.dp else 44.dp).drawBehind {
                val x = size.width / 2
                val w = 2.dp.toPx()
                if (visit) {
                    drawLine(rule, Offset(x, if (first) size.height / 2 else 0f), Offset(x, if (last) size.height / 2 else size.height), w)
                    drawCircle(if (e is Entry.Here) accent else ink3, 5.dp.toPx(), Offset(x, size.height / 2))
                } else {
                    var y = 0f
                    while (y < size.height) { drawLine(ink3, Offset(x, y), Offset(x, minOf(y + 4.dp.toPx(), size.height)), w); y += 8.dp.toPx() }
                }
            },
        )
        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
            when (e) {
                is Entry.V -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Line(e.p?.name ?: "A place", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                            Line("${t(e.v.start, zone)} to ${t(e.v.end, zone)}, ${Fmt.minutes(e.v.minutes)}" + (e.p?.kind?.takeIf { it != Kind.OTHER && it != Kind.HOME && it != Kind.WORK }?.let { " · ${it.label}" } ?: ""), Type.small, c.ink3, maxLines = 2)
                            e.v.spent?.let { Line("Spent about $${"%.0f".format(it)}, from a payment alert (on the phone only)", Type.caption, c.ink3) }
                        }
                        TextAction("Forget", { cb.onForgetVisit(e.v.id) })
                    }
                }
                is Entry.T -> Line(
                    "Drive, ${Fmt.minutes(e.t.minutes.coerceAtLeast(1))}" + (e.drive?.let { r -> ", ${Units.mi(r.meters)}, score ${r.score}" } ?: ""),
                    Type.small, c.ink2, maxLines = 1,
                )
                is Entry.Here -> {
                    Line(e.s.hints.lastOrNull() ?: Naming.fromWifi(e.s.wifi) ?: e.s.poi ?: e.s.street?.let { "Near $it" } ?: "Here now", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                    Line("Here since ${t(e.s.start, zone)}", Type.small, c.accentInk)
                }
            }
        }
    }
}

private fun t(ms: Long, zone: ZoneId) = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

@Composable
private fun RoadBlock(rc: RoadCheck, zone: ZoneId, cb: OdysseyCallbacks) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.fillMaxWidth().padding(top = Space.s).clip(shape).background(c.block(Tint.CALENDAR)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        Kicker(if (rc.reason == "event") "Leave-by" else "Commute")
        Line(rc.title, Type.title, c.ink, Modifier.padding(top = Space.xs))
        Line(RoadWords.advice(rc, zone), Type.small, c.ink2)
        for (i in rc.incidents) FactRow("On the way", i)
        Row { TextAction("Open in Maps", { cb.onMaps(rc.lat, rc.lng) }, emphasis = true) }
    }
}

@Composable
private fun OfferBlocks(d: OdysseyData, sections: List<Pair<Long, String>>, cb: OdysseyCallbacks) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    for (g in d.guesses.filter { it.open }) GuessBlock(g, cb)
    for (o in Offers.due(d, sections)) {
        Column(Modifier.fillMaxWidth().padding(top = Space.s).clip(shape).background(c.block(Tint.BRAIN)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
            Kicker("Fates · a place")
            Line(o.text, Type.title, c.ink, Modifier.padding(top = Space.xs))
            Line("Seen ${o.place.visits} times. Nothing is set up without your tap.", Type.small, c.ink3)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                TextAction(if (o.lists.isEmpty()) "Keep it" else "Yes", { cb.onYes(o.place.id) }, emphasis = true)
                TextAction("Not now", { cb.onNo(o.place.id) })
            }
        }
    }
}

/** "Is this your office? (Pine St)": Yes / No / Something else (Gym, Grocery, his own name, or ignore). Applied ones offer Undo. */
@Composable
private fun GuessBlock(g: PlaceGuess, cb: OdysseyCallbacks) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    var other by remember(g.id) { mutableStateOf(false) }
    var name by remember(g.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = Space.s).clip(shape).background(c.block(Tint.BRAIN)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        Kicker(if (g.state == "auto") "Odyssey · learned a place" else "Odyssey · a place")
        Line(PlaceLearner.text(g), Type.title, c.ink, Modifier.padding(top = Space.xs))
        Line(if (g.state == "auto") "Arrivals there are noted from now on, and drives to it count as the commute." else "From ${g.visits} ${if (g.visits == 1) "stay" else "stays"} of half an hour or more. Asked once.", Type.small, c.ink3)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (g.state == "auto") {
                TextAction("Keep it", { cb.onGuess(g.id, true) }, emphasis = true)
                TextAction("Undo", { cb.onGuess(g.id, false) })
            } else {
                if (g.kind != Kind.OTHER) TextAction("Yes", { cb.onGuess(g.id, true) }, emphasis = true)
                if (g.kind != Kind.OTHER) TextAction("No", { cb.onGuess(g.id, false) })
                TextAction(if (g.kind == Kind.OTHER) "Name it" else "Something else", { other = !other })
                if (g.kind == Kind.OTHER) TextAction("Ignore", { cb.onGuessIgnore(g.id) })
            }
        }
        if (other) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                for (k in listOf(Kind.HOME, Kind.WORK, Kind.GYM, Kind.GROCERY).filter { it != g.kind }) TextAction(k.label, { cb.onGuessKind(g.id, k, null) })
                if (g.kind != Kind.OTHER) TextAction("Ignore", { cb.onGuessIgnore(g.id) })
            }
            UnderlineField(name, { name = it }, "Its name", Modifier.fillMaxWidth())
            if (name.isNotBlank()) TextAction("Save \"${name.trim()}\"", { cb.onGuessKind(g.id, Naming.kind(name), name) }, emphasis = true)
        }
    }
}

// ---- places ----

private fun LazyListScope.places(d: OdysseyData, p: Perms, cb: OdysseyCallbacks) {
    item { Kicker("Add a place", Modifier.padding(top = Space.l, bottom = Space.xs)) }
    item { AddPlace(cb) }
    item { Kicker("Learned", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    val list = d.places.sortedWith(compareBy<KnownPlace>({ it.kind != Kind.HOME }, { it.kind != Kind.WORK }, { -it.visits }))
    if (list.isEmpty()) item { Line("None yet. After a couple of stays, places show here with their names.", Type.body, Athena.colors.ink2) }
    for (pl in list) item(key = "pl-${pl.id}") { PlaceRow(pl, cb) }
    item { Kicker("Settings", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    item { OdysseySettingsRows(d.settings, p, cb) }
}

@Composable
private fun AddPlace(cb: OdysseyCallbacks) {
    val c = Athena.colors
    var name by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    Column {
        UnderlineField(name, { name = it }, "Name where you stand, like Patel Brothers", Modifier.fillMaxWidth())
        Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("I'm here now", { if (name.isNotBlank()) { cb.onHere(name.trim()); name = "" } })
            Spacer(Modifier.width(Space.m))
            Line("One fix, then it asks which list to show there.", Type.small, c.ink3, Modifier.weight(1f))
        }
        UnderlineField(link, { link = it }, "Or paste a Google Maps place link", Modifier.fillMaxWidth().padding(top = Space.m), keyboard = KeyboardType.Uri)
        Row {
            TextAction("Add from link", { if (link.isNotBlank()) { cb.onLink(link); link = "" } }, enabled = link.isNotBlank())
            Line("Read on the phone. Share a place from Maps to Zeus works too.", Type.caption, c.ink3, Modifier.padding(top = Space.m))
        }
    }
}

@Composable
private fun PlaceRow(p: KnownPlace, cb: OdysseyCallbacks) {
    val c = Athena.colors
    var renaming by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(p.name) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line(p.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                val role = p.kind == Kind.HOME || p.kind == Kind.WORK
                Line(listOfNotNull(p.kind.takeIf { it != Kind.OTHER && it.label != p.name }?.label, "${p.visits} ${if (p.visits == 1) "visit" else "visits"}", p.street?.let { "on $it" }, if (role && p.source == NameSource.NONE) "learned from your hours" else source(p.source)).joinToString(" · "), Type.small, c.ink3, maxLines = 2)
            }
            when {
                p.geofence != null -> StatusWord("Arrival list", Tone.OK)
                p.offer == OfferState.DECLINED -> StatusWord("No list", Tone.QUIET)
                else -> Unit
            }
        }
        if (renaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(draft, { draft = it }, "Name", Modifier.weight(1f))
                TextAction("Save", { if (draft.isNotBlank()) { cb.onRename(p.id, draft); renaming = false } }, emphasis = true)
                TextAction("Cancel", { renaming = false; draft = p.name })
            }
        } else Row {
            TextAction("Rename", { renaming = true })
            TextAction(if (confirm) "Tap again: forget and stop noting here" else "Forget", { if (confirm) { cb.onForgetPlace(p.id); confirm = false } else confirm = true })
        }
    }
}

private fun source(s: NameSource) = when (s) {
    NameSource.HIM -> "your name"; NameSource.MAPS_LINK -> "from a Maps link"; NameSource.HINT -> "from a receipt"; NameSource.POI -> "from TomTom"
    NameSource.WIFI -> "from the Wi-Fi"; NameSource.GEOCODER -> "from the map"; NameSource.NONE -> "unnamed"
}

@Composable
private fun OdysseySettingsRows(s: OdysseySettings, p: Perms, cb: OdysseyCallbacks) {
    val c = Athena.colors
    var wipe by remember { mutableStateOf(false) }
    LaunchedEffect(wipe) { if (wipe) { kotlinx.coroutines.delay(4000); wipe = false } }
    Column {
        Toggle("Driving report", "Accelerometer, gyroscope and GPS during a detected drive only. Battery is used only while driving.", s.driveReport) { cb.onSettings(s.copy(driveReport = it)) }
        Toggle("Keep routes", "Each drive's path for the map on its report: sealed in its own file, deleted after ${Track.KEEP_DAYS} days, never sent anywhere or shown to Athena. Off stops new ones; delete one from its report.", s.keepRoutes) { cb.onSettings(s.copy(keepRoutes = it)) }
        Toggle("Commute checks", "Before your usual departure and before events with a place. TomTom gets the two ends of that one trip, at that moment.", s.commuteChecks) { cb.onSettings(s.copy(commuteChecks = it)) }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).clickable(role = Role.Button, onClick = cb.openVault).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("Traffic keys", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                Line("TomTom ${if (p.tomtom) "set" else "not set"}, 511NY ${if (p.ny511) "set" else "not set (optional)"}. In ${Place.VAULT.myth}.", Type.small, c.ink3)
            }
            StatusWord(if (p.tomtom) "Ready" else "Add key", if (p.tomtom) Tone.OK else Tone.ACTIVE)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).clickable(role = Role.Button, onClick = cb.onAllowUsage).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("Phone use while driving", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                Line("Screen and unlocks always; which apps only with Usage access. Maps and music count as hands-free.", Type.small, c.ink3)
            }
            StatusWord(if (p.usage) "Apps too" else "Screen only", if (p.usage) Tone.OK else Tone.QUIET)
        }
        Row(Modifier.padding(top = Space.m)) {
            TextAction(if (wipe) "Tap again to forget everything" else "Forget everything", { if (wipe) { cb.onWipe(); wipe = false } else wipe = true })
        }
        Line("Sealed on this phone with a Keystore key, never in a backup. Athena gets derived facts only; raw places and drives never leave.", Type.caption, c.ink3, Modifier.padding(top = Space.xs).widthIn(max = 560.dp))
    }
}

@Composable
private fun Toggle(title: String, about: String, on: Boolean, set: (Boolean) -> Unit) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).clickable(role = Role.Switch) { set(!on) }.padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
            Line(about, Type.small, c.ink3, Modifier.widthIn(max = 540.dp))
        }
        Spacer(Modifier.width(Space.m))
        StatusWord(if (on) "On" else "Off", if (on) Tone.OK else Tone.QUIET)
    }
}

// ---- habits ----

private fun LazyListScope.habits(d: OdysseyData, now: Long) {
    val s = Commute.stats(d, now)
    item { Kicker("Commute", Modifier.padding(top = Space.l, bottom = Space.xs)) }
    val lines = listOfNotNull(Commute.usual(s), Commute.summary(s), Commute.insight(d, now), Patterns.homeBy(d))
    if (lines.isEmpty()) item { Line("Your commute shows here after a few trips between Home and the office.", Type.body, Athena.colors.ink2) }
    for (l in lines) item { Bullet(l) }
    if (s.minutes.isNotEmpty()) item { WeekBars(s) }
    item { Kicker("Habits", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    val h = Patterns.habits(d, now)
    if (h.isEmpty()) item { Line("Weekday habits appear after three visits or so.", Type.body, Athena.colors.ink2) }
    for (p in h) item { Bullet(p.text) }
    item { Kicker("Time spent, last 30 days", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    for ((p, ms) in Patterns.timeSpent(d, now).filter { it.first.kind != Kind.HOME }.take(6)) item {
        Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
            Line(p.name, Type.body, Athena.colors.ink, Modifier.weight(1f), maxLines = 1)
            Line(Fmt.minutes(ms / 60_000L), Type.small, Athena.colors.ink2)
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(Modifier.fillMaxWidth().topRule(Athena.colors.rule).padding(vertical = Space.m)) { Line(text, Type.body, Athena.colors.ink) }
}

/** Minutes per weekday as plain bars: no chart library. */
@Composable
private fun WeekBars(s: CommuteStats) {
    val c = Athena.colors
    val max = (s.minutes.values.maxOrNull() ?: 1).coerceAtLeast(1)
    Column(Modifier.padding(top = Space.m)) {
        for ((dow, m) in s.minutes.toSortedMap()) Row(Modifier.padding(vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
            Line(Clock.short(dow), Type.caption, c.ink3, Modifier.width(40.dp))
            Box(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth(m / max.toFloat()).height(10.dp).clip(RoundedCornerShape(5.dp)).background(if (m == max) c.accent else c.ink3))
            }
            Line("$m min", Type.caption, c.ink2, Modifier.width(56.dp).padding(start = Space.s))
        }
    }
}

// ---- logs ----

private fun LazyListScope.logs(d: OdysseyData, now: ZonedDateTime, cb: OdysseyCallbacks) {
    val logs = Patterns.logs(d)
    item { Line("Car washes, temple, gym, groceries and fuel stops, noted by themselves. Edit by forgetting a line.", Type.small, Athena.colors.ink3, Modifier.padding(top = Space.l, bottom = Space.s)) }
    if (logs.isEmpty()) item { Line("Nothing logged yet.", Type.body, Athena.colors.ink2) }
    for ((kind, ls) in logs.groupBy { it.kind }) {
        item(key = "k-$kind") { Kicker(kind.label, Modifier.padding(top = Space.l, bottom = Space.xs)) }
        for (l in ls.take(12)) item(key = "log-${l.visit.id}") {
            val z = now.zone
            val at = Instant.ofEpochMilli(l.visit.start).atZone(z)
            Row(Modifier.fillMaxWidth().topRule(Athena.colors.rule).padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Line("${kind.label}: ${DAY.format(at)}, ${TIME.format(at)} (${Fmt.minutes(l.visit.minutes)})", Type.body, Athena.colors.ink, maxLines = 1)
                    Line(l.place.name, Type.small, Athena.colors.ink3, maxLines = 1)
                }
                TextAction("Forget", { cb.onForgetVisit(l.visit.id) })
            }
        }
    }
}

// ---- drives ----

private fun LazyListScope.drives(d: OdysseyData, routes: Map<String, DriveRoute>, now: Long, zone: ZoneId, online: Boolean, cb: OdysseyCallbacks, openDrive: String?) {
    val w = DrivingReport.week(d.drives, now)
    val weekRoutes = d.drives.filter { now - it.end < 7 * 86_400_000L }.sortedByDescending { it.start }.mapNotNull { routes[it.id] }
    item { WeekBlock(w, weekRoutes, DriveInsights.weekScores(d.drives, now)) }
    item { Kicker("Drives", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    if (d.drives.isEmpty()) item { Line(if (d.settings.driveReport) "Your first drive shows here when it ends." else "The driving report is off (Places > Settings).", Type.body, Athena.colors.ink2) }
    for (r in d.drives.sortedByDescending { it.start }.take(30)) item(key = "dr-${r.id}") { DriveRow(d, r, routes[r.id], zone, now, online, cb, r.id == openDrive) }
}

@Composable
private fun WeekBlock(w: DrivingWeek, routes: List<DriveRoute>, weeks: List<Int>) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.fillMaxWidth().padding(top = Space.l).clip(shape).background(c.block(Tint.SERVER)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        Kicker("This week")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Line(w.score?.toString() ?: "–", Type.display, c.ink)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Line("Smoothness score", Type.small, c.ink2)
                w.lastWeekScore?.let { Line(if ((w.score ?: 0) >= it) "Up from $it last week" else "Down from $it last week", Type.small, if ((w.score ?: 0) >= it) c.ok else c.alert) }
            }
            if (weeks.size >= 2) Column(horizontalAlignment = Alignment.End) {
                Sparkline(weeks, Modifier.size(96.dp, 32.dp))
                Line("${weeks.size} weeks", Type.caption, c.ink3)
            }
        }
        if (routes.isNotEmpty()) Row(Modifier.padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (r in routes.take(4)) RouteThumb(r, Modifier.weight(1f).height(56.dp))
            repeat(4 - routes.take(4).size) { Spacer(Modifier.weight(1f)) }
        }
        val lw = 132.dp
        if (w.trips > 0) FactRow("Drives", "${w.trips}, ${Fmt.minutes(w.minutes)}, ${w.miles} mi", labelWidth = lw)
        for ((k, n) in w.counts) FactRow(k.label, "$n", labelWidth = lw)
        if (w.screenMinutes > 0) FactRow("Screen", "${"%.1f".format(w.screenMinutes)} min while moving", labelWidth = lw)
        w.worst?.let { FactRow("Worst", "Score ${it.score}, ${events(it.events.size)}", labelWidth = lw) }
        for (s in w.spots) FactRow("Spot", s, labelWidth = lw)
    }
}

@Composable
private fun DriveRow(d: OdysseyData, r: DriveReport, route: DriveRoute?, zone: ZoneId, now: Long, online: Boolean, cb: OdysseyCallbacks, startOpen: Boolean = false) {
    val c = Athena.colors
    var open by rememberSaveable(r.id) { mutableStateOf(startOpen) }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(bottom = Space.m)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            if (route != null) { RouteThumb(route, Modifier.size(56.dp, 44.dp)); Spacer(Modifier.width(Space.m)) }
            Column(Modifier.weight(1f)) {
                val at = Instant.ofEpochMilli(r.start).atZone(zone)
                Line("${DAY.format(at)}, ${TIME.format(at)} · ${DriveInsights.tag(d, r, zone).label}", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Line("${Fmt.minutes(r.minutes.coerceAtLeast(1))}, ${Units.mi(r.meters)}, top ${Units.mphText(r.maxKmh)} · ${events(r.events.size)}", Type.small, c.ink3, maxLines = 1)
            }
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.tile(Tint.SERVER)), contentAlignment = Alignment.Center) {
                Line("${r.score}", Type.title, if (r.score >= 80) c.ok else if (r.score >= 60) c.ink else c.alert)
            }
        }
        if (open) {
            Spacer(Modifier.height(Space.m))
            DriveReportView(d, r, route, zone, now, online, cb.onTag, cb.onDeleteRoute)
        }
    }
}

/** Odyssey's line icon: a road running away into the distance. */
val RoadIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    val b = androidx.compose.ui.graphics.vector.ImageVector.Builder("road", 24.dp, 24.dp, 24f, 24f)
    for (d in listOf("M8 20.5 L10.6 3.5", "M16 20.5 L13.4 3.5", "M12 5 V7", "M12 10 V12.5", "M12 15.5 V18.5")) {
        b.addPath(androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(), stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black),
            strokeLineWidth = 1.6f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round)
    }
    b.build()
}

private fun events(n: Int) = if (n == 0) "no events" else if (n == 1) "1 event" else "$n events"

// ---- Calypso rows ----

/** Calypso's rows for the two road keys (Calypso is already unlocked when this shows). Last four only. */
@Composable
fun RoadKeyRows() {
    KeyRow("TomTom", "Traffic-aware ETAs for your commute and leave-by times. Goes only to api.tomtom.com, with the two ends of a trip.", "tomtom", 20)
    KeyRow("511NY", "Optional: crashes and closures on NY roads. Goes only to 511ny.org; the list is filtered on the phone.", "ny511", 16)
}

@Composable
internal fun KeyRow(label: String, about: String, slot: String, min: Int) {
    val ctx = LocalContext.current
    val c = Athena.colors
    val scope = rememberCoroutineScope()
    var masked by remember { mutableStateOf<String?>(null) }
    var set by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val secret = { SealedSecret(ctx, slot) }
    val load = { scope.launch { val v = withContext(Dispatchers.IO) { secret().read() }; set = v != null; masked = v?.let { "•".repeat(12) + it.takeLast(4) } } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("$label key", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                Line(about, Type.small, c.ink3)
            }
            StatusWord(if (set) "Set" else "Not set", if (set) Tone.OK else Tone.QUIET)
        }
        if (set) FactRow("Key", masked ?: "Reading")
        if (entering) {
            UnderlineField(draft, { draft = it.trim() }, "$label key", Modifier.fillMaxWidth(), keyboard = KeyboardType.Password,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row {
                TextAction("Save", { if (draft.length >= min) scope.launch { withContext(Dispatchers.IO) { secret().save(draft) }; draft = ""; entering = false; load() } }, emphasis = true, enabled = draft.length >= min)
                TextAction("Cancel", { draft = ""; entering = false })
            }
        } else Row {
            TextAction(if (set) "Rotate" else "Add key", { entering = true }, emphasis = !set)
            if (set) TextAction(if (confirm) "Tap again to delete" else "Delete", {
                if (confirm) scope.launch { withContext(Dispatchers.IO) { secret().delete() }; confirm = false; load() } else confirm = true
            })
        }
    }
}
