package dev.phonerecall.clio

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.moneta.ChartCard
import dev.phonerecall.moneta.ChartColors
import dev.phonerecall.moneta.ChartData
import dev.phonerecall.moneta.ChartEngine
import dev.phonerecall.moneta.ChartQuery
import dev.phonerecall.moneta.ChartSpec
import dev.phonerecall.moneta.ChartType
import dev.phonerecall.moneta.Dim
import dev.phonerecall.moneta.Metric
import dev.phonerecall.moneta.Series
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
private val TIME = DateTimeFormatter.ofPattern("h:mm:ss a", Locale.US)
private const val DAY_MS = 86_400_000L
/** The clipboard goes over binder (about 1 MB): past this, Copy keeps the newest lines and says so. */
private const val CLIP_CHARS = 900_000

/** Clio from Themis: reads the files off the main thread, then hands the lines to [ClioContent]. */
@Composable
fun ClioPage(gutter: Dp, back: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<ClioLine>?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { lines = withContext(Dispatchers.IO) { Clio.get()?.lines(System.currentTimeMillis() - ClioLog.KEEP_DAYS * DAY_MS).orEmpty() } }
    var car by remember { mutableStateOf(DeviceWatch.canCar(ctx)) }
    val askCar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { car = it }
    ClioContent(
        lines, gutter, notice,
        onCopy = {
            val since = System.currentTimeMillis() - DAY_MS
            val zone = ZoneId.systemDefault()
            val day = lines.orEmpty().filter { it.ts >= since }.asReversed().map { ClioLog.plain(it, zone) }
            var text = day.joinToString("\n")
            val cut = text.length > CLIP_CHARS
            if (cut) text = "(older lines left out: too long for the clipboard; pull export.jsonl for all of it)\n" + text.takeLast(CLIP_CHARS).substringAfter('\n')
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Clio, last 24 h", text))
            notice = "Copied ${day.size} lines from the last 24 hours" + if (cut) " (the newest that fit)." else "."
        },
        onExport = {
            scope.launch {
                val f = withContext(Dispatchers.IO) { runCatching { Clio.get()?.export() }.getOrNull() }
                notice = if (f == null) "Export failed." else "Wrote files/clio/${f.name}. adb exec-out run-as dev.phonerecall cat files/clio/${f.name}"
            }
        },
        onRefresh = { reload++ },
        back = back,
        now = System.currentTimeMillis(),
        onAllowCar = if (car) null else ({ askCar.launch(Manifest.permission.BLUETOOTH_CONNECT) }),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClioContent(
    lines: List<ClioLine>?,
    gutter: Dp,
    notice: String?,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onRefresh: () -> Unit,
    back: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
    onAllowCar: (() -> Unit)? = null,
    startOnDay: Boolean = false,
) {
    val c = Athena.colors
    var dayMode by remember { mutableStateOf(startOnDay) }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    var date by remember { mutableStateOf(today) }
    var query by remember { mutableStateOf("") }
    var level by remember { mutableStateOf<Level?>(null) }
    var area by remember { mutableStateOf<String?>(null) }
    val areas = remember(lines) { lines.orEmpty().map { it.area }.distinct().sorted() }
    val shown = remember(lines, query, level, area) {
        val q = query.trim()
        lines.orEmpty().filter { l ->
            (level == null || l.level == level) && (area == null || l.area == area) &&
                (q.isEmpty() || l.event.contains(q, true) || l.detail.contains(q, true) || l.area.contains(q, true))
        }
    }
    val days = remember(shown) { shown.groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.LOGS.title(), gutter, kicker = Place.SETTINGS.title(), subtitle = Place.LOGS.subtitle()) { TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line(
                    "What the phone did, newest first: Athena's turns, workers, requests (host only), proposals, drives, alarms, the device and errors. " +
                        "Day puts one day back together. Kept 14 days on the phone. No message text, amounts or people's names; places by name only, never where.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Word("Log", !dayMode) { dayMode = false }
                    Word("Day", dayMode) { dayMode = true }
                }
            }
            item {
                Row(Modifier.offset(x = -Space.s), verticalAlignment = Alignment.CenterVertically) {
                    TextAction("Copy last 24 h", onCopy, emphasis = true)
                    TextAction("Export", onExport)
                    TextAction("Refresh", onRefresh)
                }
            }
            if (dayMode) {
                notice?.let { item { Line(it, Type.small, c.ink2, Modifier.padding(bottom = Space.s)) } }
                item {
                    Row(Modifier.offset(x = -Space.s), verticalAlignment = Alignment.CenterVertically) {
                        TextAction("Earlier", { date = date.minusDays(1) }, enabled = date > today.minusDays(ClioLog.KEEP_DAYS - 1L))
                        Line(DAY.format(date), Type.small.copy(fontWeight = FontWeight.Medium), c.ink, Modifier.weight(1f).padding(horizontal = Space.xs), maxLines = 1)
                        TextAction("Later", { date = date.plusDays(1) }, enabled = date < today)
                    }
                }
                if (lines == null) item { Line("Reading…", Type.body, c.ink2, Modifier.padding(top = Space.l)) }
                else item(key = "day-$date") { DayPanel(remember(lines, date, now) { DeviceDay.of(lines, date, zone, now) }, zone, onAllowCar) }
                return@LazyColumn
            }
            notice?.let { item { Line(it, Type.small, c.ink2, Modifier.padding(bottom = Space.s)) } }
            item { UnderlineField(query, { query = it }, "Search events and details", Modifier.fillMaxWidth().padding(vertical = Space.s)) }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Word("All levels", level == null) { level = null }
                    for (l in Level.entries) Word(l.name.lowercase().replaceFirstChar { it.uppercase() }, level == l) { level = if (level == l) null else l }
                }
            }
            if (areas.size > 1) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Word("All areas", area == null) { area = null }
                    for (a in areas) Word(a, area == a) { area = if (area == a) null else a }
                }
            }
            if (lines == null) item { Line("Reading…", Type.body, c.ink2, Modifier.padding(top = Space.l)) }
            else if (shown.isEmpty()) item { Line(if (lines.isEmpty()) "Nothing logged yet." else "Nothing matches.", Type.body, c.ink2, Modifier.padding(top = Space.l)) }
            for ((day, list) in days) {
                item(key = "d-$day") { Kicker(DAY.format(day), Modifier.padding(top = Space.l, bottom = Space.xs)) }
                for ((i, l) in list.withIndex()) item(key = "l-$day-$i-${l.ts}") { Entry(l, zone) }
            }
        }
    }
}

@Composable
private fun Entry(l: ClioLine, zone: ZoneId) {
    val c = Athena.colors
    var open by remember { mutableStateOf(false) }
    val tone = when (l.level) { Level.ERROR -> c.alert; Level.WARN -> c.accentInk; Level.INFO -> c.ink3 }
    Column(Modifier.fillMaxWidth().topRule(c.rule).clickable { open = !open }.padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Line(l.event, Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
            Line(TIME.format(Instant.ofEpochMilli(l.ts).atZone(zone)), Type.caption, c.ink3, maxLines = 1)
        }
        Line(l.area + if (l.level == Level.INFO) "" else " · " + l.level.name.lowercase(), Type.caption, tone, maxLines = 1)
        if (l.detail.isNotEmpty()) Line(l.detail, Type.small, c.ink2, maxLines = if (open) Int.MAX_VALUE else 2)
    }
}

/** A filter word: plain ink when off, a saffron underline when chosen (as in Harmonia's editor). */
@Composable
private fun Word(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    Box(Modifier.heightIn(min = 40.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = Space.xs), contentAlignment = Alignment.Center) {
        Line(
            text, Type.small.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal), if (on) c.ink else c.ink3,
            Modifier.then(if (on) Modifier.drawBehind { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w), Offset(size.width, size.height + w), w) } else Modifier),
        )
    }
}

/** Clio's Day view: four lanes over the day (screen on, charging, places, drives), app minutes, then the summary rows. */
@Composable
private fun DayPanel(d: DeviceDayView, zone: ZoneId, onAllowCar: (() -> Unit)?) {
    val c = Athena.colors
    val colors = ChartColors.ofLabels(Lane.entries.map { it.title })
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Kicker("Timeline", Modifier.padding(top = Space.m, bottom = Space.xs))
        for (lane in Lane.entries) {
            val spans = d.spans.filter { it.lane == lane }
            val color = colors.getValue(lane.title)
            val track = c.rule
            val tick = c.ink3.copy(alpha = 0.35f)
            val words = if (spans.isEmpty()) "nothing" else spans.joinToString { "${DeviceLog.clock(it.start, zone)} to ${DeviceLog.clock(it.end, zone)}" + if (it.label.isNotEmpty()) " ${it.label}" else "" }
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Line(lane.title, Type.caption, c.ink2, Modifier.width(68.dp), maxLines = 1)
                Canvas(Modifier.weight(1f).height(14.dp).semantics { contentDescription = "${lane.title}: $words" }) {
                    val span = (d.to - d.from).toFloat()
                    val r = CornerRadius(3.dp.toPx())
                    drawRoundRect(track, size = Size(size.width, size.height), cornerRadius = r)
                    for (q in 1..3) { val x = size.width * q / 4f; drawLine(tick, Offset(x, 0f), Offset(x, size.height), 1f) }
                    for (s in spans) {
                        val x0 = size.width * (s.start - d.from) / span
                        val w = maxOf(size.width * (s.end - s.start) / span, 2.dp.toPx())
                        drawRoundRect(color, Offset(x0, 0f), Size(w, size.height), cornerRadius = r)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 68.dp)) {
            for (h in listOf("12 AM", "6 AM", "12 PM", "6 PM")) Line(h, Type.caption, c.ink3, Modifier.weight(1f), maxLines = 1)
        }
        if (d.apps.isNotEmpty()) {
            ChartCard(
                ChartSpec("clio-apps", ChartType.BAR, "Screen time by app", "Minutes, from Usage access", ChartQuery(metric = Metric.COUNT, by = Dim.CATEGORY), "{}"),
                ChartData(d.apps.map { if (it.first == "other") ChartEngine.OTHER else it.first }, listOf(Series("Minutes", d.apps.map { it.second.toLong() })), null, Metric.COUNT),
                kicker = "Apps",
            )
        }
        Kicker("Summary", Modifier.padding(top = Space.m, bottom = Space.xs))
        if (d.rows.isEmpty()) Line("Nothing from the device for this day yet.", Type.small, c.ink3)
        for ((label, text) in d.rows) {
            Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
                Line(label, Type.small, c.ink3, Modifier.width(120.dp), maxLines = 1)
                Line(text, Type.small, c.ink, Modifier.weight(1f))
            }
        }
        onAllowCar?.let {
            Row(Modifier.offset(x = -Space.s).padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Allow car Bluetooth", it)
                Line("Logs connects to car audio only, no names.", Type.caption, c.ink3, Modifier.weight(1f))
            }
        }
    }
}
