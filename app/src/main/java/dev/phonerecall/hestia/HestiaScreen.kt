package dev.phonerecall.hestia

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the Hestia screen can do. Taps act directly: these are the owner's own hands, not a model's. */
class HestiaCalls(
    val connect: () -> Unit = {},
    val send: (deviceId: String, command: HomeCommand) -> Unit = { _, _ -> },
    /** Hold a tile: on or off the home-screen widget. */
    val pick: (deviceId: String) -> Unit = {},
)

@Composable
fun HestiaScreen(wide: Boolean, gutter: Dp) {
    val core = HestiaCore.get(LocalContext.current)
    val s by core.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current.applicationContext
    var notice by remember { mutableStateOf<String?>(null) }
    var picks by remember { mutableStateOf(HestiaPicks.load(ctx)) }
    LaunchedEffect(Unit) { core.backend.start() }
    LaunchedEffect(notice) { if (notice != null) { delay(4000); notice = null } }
    val calls = remember(core) {
        HestiaCalls(
            connect = { scope.launch { notice = core.backend.requestPermission() } },
            send = { id, cmd -> scope.launch { core.tap(id, cmd)?.let { notice = it } } },
            pick = { id ->
                picks = HestiaPicks.toggle(ctx, id)
                notice = if (id in picks) "On the widget" else "Off the widget"
                dev.phonerecall.widgets.WidgetBus.hestia(ctx, core.snapshot, force = true)
            },
        )
    }
    HestiaContent(s, HestiaCore.whereabouts.away(), gutter, wide, notice, calls, picks = picks.toSet())
}

@Composable
fun HestiaContent(s: HomeSnapshot, away: Boolean?, gutter: Dp, wide: Boolean, notice: String?, calls: HestiaCalls, startOpen: String? = null, picks: Set<String> = emptySet()) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.DEVICES.title(), gutter, emblem = Place.DEVICES, subtitle = Place.DEVICES.subtitle()) {
            if (s.link == Link.Connected) TextAction("Access", calls.connect)
        }
        if (notice != null) Line(notice, Type.small, c.alert, Modifier.padding(horizontal = gutter).padding(bottom = Space.s))
        when (val l = s.link) {
            Link.Connected -> Connected(s, away, gutter, wide, calls, startOpen, picks)
            else -> NotConnected(l, gutter, calls)
        }
    }
}

@Composable
private fun NotConnected(l: Link, gutter: Dp, calls: HestiaCalls) {
    val c = Athena.colors
    Column(Modifier.padding(start = gutter, end = gutter, top = Space.xl).widthIn(max = 560.dp)) {
        Kicker(if (l == Link.Starting) "Google Home" else "Not connected yet", color = c.accentInk)
        Spacer(Modifier.height(Space.m))
        Line(
            when (l) {
                Link.Starting -> "Connecting to Google Home."
                Link.NeedsPermission -> "Let Zeus see the lights and plugs in your Google Home."
                else -> "Google Home isn't connected."
            },
            Type.headline, c.ink,
        )
        if (l is Link.NotConnected) { Spacer(Modifier.height(Space.m)); Line(l.reason + ".", Type.body, c.ink) }
        Spacer(Modifier.height(Space.m))
        Line(
            "Your AiDot lights and plugs reach Zeus through Google Home. Google asks which home and devices to share, and you can change that later with Access.",
            Type.body, c.ink2,
        )
        Spacer(Modifier.height(Space.xl))
        if (l != Link.Starting) {
            Kicker("What it takes", Modifier.padding(bottom = Space.xs))
            FactRow("1", labelWidth = 28.dp, value = "AiDot linked in the Google Home app (Devices, Add, Works with Google Home, AiDot)")
            FactRow("2", labelWidth = 28.dp, value = "Zeus registered in the Google Home Developer Console, your account a test user")
            FactRow("3", labelWidth = 28.dp, value = "Connect here and pick the devices to share")
            Spacer(Modifier.height(Space.xl))
            PrimaryAction(if (l == Link.NeedsPermission) "Connect Google Home" else "Try again", calls.connect)
        }
        Spacer(Modifier.height(Space.xl))
        Line("Locks, garage doors, cameras and security systems stay in the Google Home app. Zeus never controls them.", Type.small, c.ink3)
    }
}

@Composable
private fun Connected(s: HomeSnapshot, away: Boolean?, gutter: Dp, wide: Boolean, calls: HestiaCalls, startOpen: String?, picks: Set<String>) {
    val c = Athena.colors
    var open by rememberSaveable { mutableStateOf(startOpen) }
    val cols = if (wide) 3 else 2
    val lightsOn = s.lightsOn.size
    val groups = s.rooms.map { it.name to s.devices.filter { d -> d.roomId == it.id } } +
        listOf("No room" to s.devices.filter { d -> s.room(d.roomId) == null })
    LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
        item(key = "summary") {
            Column(Modifier.padding(top = Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Kicker(s.structures.firstOrNull()?.name ?: "Google Home", Modifier.weight(1f))
                    when (away) { true -> StatusWord("Away", Tone.ACTIVE); false -> StatusWord("Home", Tone.OK); null -> Unit }
                }
                Spacer(Modifier.height(Space.xs))
                Line(if (lightsOn == 0) "Every light is off" else if (lightsOn == 1) "1 light on" else "$lightsOn lights on", Type.headline, c.ink)
                Line("${s.devices.size} devices in ${s.rooms.size} rooms. Tap a tile to switch it; hold one to put it on the widget.", Type.small, c.ink3)
            }
        }
        for ((room, ds) in groups) {
            if (ds.isEmpty()) continue
            item(key = "room-$room") { Kicker(room, Modifier.padding(top = Space.xl, bottom = Space.s)) }
            for ((i, row) in ds.chunked(cols).withIndex()) item(key = "row-$room-$i") {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(bottom = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (d in row) Tile(d, open == d.id, d.id in picks, Modifier.weight(1f).fillMaxHeight(), onOpen = { open = if (open == d.id) null else d.id }, calls)
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            ds.firstOrNull { it.id == open && it.controllable && it.online }?.let { d ->
                item(key = "panel-${d.id}") { Panel(d, calls) { open = null } }
            }
        }
        if (s.automations.isNotEmpty()) {
            item(key = "autos") { Kicker("Automations", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            for (a in s.automations) item(key = "auto-${a.id}") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(a.name, Type.body.copy(fontWeight = FontWeight.Medium), c.ink)
                        if (a.description.isNotBlank()) Line(a.description, Type.small, c.ink3, maxLines = 2)
                    }
                    Spacer(Modifier.width(Space.m))
                    StatusWord(if (a.active) "Active" else "Off", if (a.active) Tone.OK else Tone.QUIET)
                }
            }
        }
        item(key = "foot") {
            Line(
                "Automations are read only here; edit them in the Google Home app. Athena can only propose a change, and you confirm it in Fates. Locks, garage doors, cameras and security systems stay in Google Home.",
                Type.small, c.ink3, Modifier.padding(top = Space.xl),
            )
        }
    }
}

@Composable
private fun Tile(d: HomeDevice, opened: Boolean, picked: Boolean, modifier: Modifier, onOpen: () -> Unit, calls: HestiaCalls) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.blockSmall)
    val lit = d.on == true && d.online && !d.kind.forbidden
    val act = d.controllable && d.online
    val adjustable = act && (Trait.LEVEL in d.traits || Trait.COLOR_TEMP in d.traits || Trait.COLOR in d.traits)
    Column(
        modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(if (lit) c.block(Tint.SUN, strong = true) else c.block(Tint.NEUTRAL))
            .border(if (opened) 2.dp else 1.dp, if (opened) c.ink else c.edge, shape)
            .combinedClickable(enabled = act, role = Role.Switch, onLongClick = { calls.pick(d.id) }) { calls.send(d.id, HomeCommand.Power(d.on != true)) }
            .padding(start = Space.m, end = Space.m, top = Space.m, bottom = Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(if (picked) "${d.kind.word} · Widget" else d.kind.word, Modifier.weight(1f))
            StatusWord(
                when { !d.online -> "Offline"; d.kind.forbidden -> "Read only"; d.on == true -> "On"; d.on == false -> "Off"; else -> "-" },
                when { !d.online -> Tone.ALERT; lit -> Tone.OK; else -> Tone.QUIET },
            )
        }
        Spacer(Modifier.height(Space.xs))
        Line(d.name, Type.title, if (act || d.kind.forbidden) c.ink else c.ink3, maxLines = 2)
        Spacer(Modifier.weight(1f, fill = false))
        val detail = when {
            d.kind.forbidden -> "In Google Home"
            !d.online -> "Not reachable"
            lit -> listOfNotNull(d.level?.takeIf { Trait.LEVEL in d.traits }?.let { "$it%" }, d.kelvin?.takeIf { Trait.COLOR_TEMP in d.traits }?.let { "$it K" }).joinToString("  ").ifEmpty { null }
            else -> null
        }
        if (detail != null) Line(detail, Type.small, c.ink2, Modifier.padding(top = Space.xs), maxLines = 1)
        if (adjustable) TextAction(if (opened) "Close" else "Adjust", onOpen, Modifier.offset(x = -Space.s))
        else Spacer(Modifier.height(Space.s))
    }
}

/** Brightness, white and color for one light, under its room. */
@Composable
private fun Panel(d: HomeDevice, calls: HestiaCalls, onClose: () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(
        Modifier.fillMaxWidth().padding(bottom = Space.s).clip(shape).background(c.block(Tint.NEUTRAL, strong = true)).border(1.dp, c.edge, shape).padding(Space.blockPad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Line(d.name, Type.title, c.ink, Modifier.weight(1f), maxLines = 1)
            TextAction("Done", onClose)
        }
        if (Trait.LEVEL in d.traits) {
            Kicker("Brightness", Modifier.padding(top = Space.m))
            LevelBar(d.level ?: 100) { calls.send(d.id, HomeCommand.Brightness(it)) }
        }
        if (Trait.COLOR_TEMP in d.traits) {
            Kicker("White", Modifier.padding(top = Space.m))
            val whites = HomeRules.WHITES.filterValues { it in d.minKelvin..d.maxKelvin }
            Words(whites.keys.toList(), d.kelvin?.let { HomeRules.whiteWord(it) }) { calls.send(d.id, HomeCommand.ColorTemp(whites.getValue(it))) }
        }
        if (Trait.COLOR in d.traits) {
            Kicker("Color", Modifier.padding(top = Space.m))
            val current = d.hue?.takeIf { (d.saturation ?: 0) > 50 }?.let { h -> HomeRules.COLORS.minByOrNull { (_, v) -> minOf((v.first - h).mod(360), (h - v.first).mod(360)) }?.key }
            Words(HomeRules.COLORS.keys.toList(), current) { name -> HomeRules.COLORS.getValue(name).let { (h, sat) -> calls.send(d.id, HomeCommand.Color(h, sat)) } }
        }
    }
}

/** Plain words in a wrapping row; the current one in full ink and medium weight. */
@Composable
private fun Words(words: List<String>, current: String?, onPick: (String) -> Unit) {
    val c = Athena.colors
    FlowRow {
        for (w in words) {
            val on = w == current
            Box(
                Modifier.heightIn(min = 44.dp).clickable(role = Role.Button) { onPick(w) }.padding(end = Space.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                Line(w.replaceFirstChar { it.uppercase() }, Type.action.copy(fontWeight = if (on) FontWeight.Bold else FontWeight.Normal), if (on) c.ink else c.ink2,
                    Modifier.drawBehind { if (on) drawLine(c.ink, Offset(0f, size.height + 3.dp.toPx()), Offset(size.width, size.height + 3.dp.toPx()), 2.dp.toPx()) })
            }
        }
    }
}

/** A flat bar he drags or taps; the command goes out when he lets go. */
@Composable
private fun LevelBar(value: Int, onSet: (Int) -> Unit) {
    val c = Athena.colors
    var local by remember(value) { mutableStateOf(value) }
    val track = c.rule
    val fill = c.ink
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.weight(1f).height(44.dp)
                .pointerInput(Unit) { detectTapGestures { o -> local = pct(o.x, size.width.toFloat()); onSet(local) } }
                .pointerInput(Unit) { detectHorizontalDragGestures(onDragEnd = { onSet(local) }) { ch, _ -> local = pct(ch.position.x, size.width.toFloat()) } }
                .drawBehind {
                    val y = size.height / 2
                    val h = 6.dp.toPx()
                    drawLine(track, Offset(0f, y), Offset(size.width, y), h)
                    drawLine(fill, Offset(0f, y), Offset(size.width * local / 100f, y), h)
                },
        )
        Line("$local%", Type.action, c.ink, Modifier.width(56.dp).padding(start = Space.m))
    }
}

private fun pct(x: Float, w: Float): Int = if (w <= 0f) 1 else (x / w * 100).roundToInt().coerceIn(1, 100)
