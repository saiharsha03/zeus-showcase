package dev.phonerecall.odyssey

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.phonerecall.home.Fmt
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY = DateTimeFormatter.ofPattern("EEE, MMMM d", Locale.US)
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * One drive's report, opened from its row: the map card, the summary (when, from where to where, how long),
 * Details (each event kind and phone use, in the map's colours), Speed, and Insights from his own history.
 * Everything is read from the phone; nothing is fetched for it except the map tiles.
 */
@Composable
fun DriveReportView(
    d: OdysseyData, r: DriveReport, route: DriveRoute?, zone: ZoneId, now: Long, online: Boolean,
    onTag: (String, TripTag?) -> Unit, onDeleteRoute: (String) -> Unit,
) {
    val c = Athena.colors
    val ctx = LocalContext.current
    fun name(pkg: String) = dev.phonerecall.brain.SensesListener.label(ctx, pkg)
    val recent = d.drives.filter { now - it.end < 28 * 86_400_000L }
    val hotspots = DrivingReport.hotspots(recent)
    Column(verticalArrangement = Arrangement.spacedBy(Space.blockGap)) {
        if (route != null) DriveMap(route, r, zone, online, hotspots)
        Summary(d, r, zone, onTag)
        Details(r, route, zone, ::name)
        if (route != null && route.points.size >= 3) Card("Speed") { SpeedChart(route, r, zone) }
        val insights = DriveInsights.all(d, r, route, now, zone, ::name)
        if (insights.isNotEmpty()) Card("Insights") {
            val trend = DriveInsights.scoreTrend(d.drives, r)
            for (i in insights) {
                if (i.label == "Score" && trend != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { FactRow(i.label, i.text) }
                    Sparkline(trend.second, Modifier.size(72.dp, 24.dp))
                } else FactRow(i.label, i.text)
            }
        }
        if (route != null) {
            var sure by remember(r.id) { mutableStateOf(false) }
            LaunchedEffect(sure) { if (sure) { kotlinx.coroutines.delay(4000); sure = false } }
            Row { TextAction(if (sure) "Tap again to delete the route" else "Delete this route", { if (sure) { onDeleteRoute(r.id); sure = false } else sure = true }) }
        }
    }
}

@Composable
private fun Card(title: String?, action: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.block(Tint.SERVER)).border(1.dp, c.edge, shape).padding(Space.blockPad)) {
        if (title != null) Row(Modifier.padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Line(title, Type.title, c.ink, Modifier.weight(1f))
            action?.invoke()
        }
        content()
    }
}

/** When, the trip tag (tap to correct), from and to as street and city joined by a dotted line, then minutes and miles. */
@Composable
private fun Summary(d: OdysseyData, r: DriveReport, zone: ZoneId, onTag: (String, TripTag?) -> Unit) {
    val c = Athena.colors
    val tag = DriveInsights.tag(d, r, zone)
    var picking by remember(r.id) { mutableStateOf(false) }
    val trip = DriveInsights.tripOf(d, r)
    fun place(id: String?) = d.place(id)?.let { p -> listOfNotNull(p.name.takeIf { it.isNotBlank() }, p.street).distinct().joinToString(", ") }?.ifBlank { null }
    Card(null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line(DAY.format(Instant.ofEpochMilli(r.start).atZone(zone)), Type.title, c.ink, maxLines = 1)
                r.weather?.let { Line(it, Type.small, c.ink3, maxLines = 1) }
            }
            Chip(tag.label, selected = picking) { picking = !picking }
        }
        if (picking) FlowRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            for (t in TripTag.entries) Chip(t.label, selected = t == tag) { onTag(r.id, t); picking = false }
            if (r.tag != null) Chip("Automatic", selected = false) { onTag(r.id, null); picking = false }
        }
        Spacer(Modifier.height(Space.m))
        End(TIME.format(Instant.ofEpochMilli(r.start).atZone(zone)), r.from ?: place(trip?.from), first = true)
        End(TIME.format(Instant.ofEpochMilli(r.end).atZone(zone)), r.to ?: place(trip?.to), first = false)
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.Bottom) {
            Line(Fmt.minutes(r.minutes.coerceAtLeast(1)), Type.display, c.ink)
            Line(" · ${Units.mi(r.meters)}", Type.body, c.ink2, Modifier.padding(bottom = 6.dp).weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(c.tile(Tint.SERVER)), contentAlignment = Alignment.Center) {
                Line("${r.score}", Type.title, if (r.score >= 80) c.ok else if (r.score >= 60) c.ink else c.alert)
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.forSize(32.dp))
    Box(
        Modifier.heightIn(min = 32.dp).clip(shape).background(if (selected) c.tile(Tint.SERVER) else Color.Transparent).border(1.dp, c.edge, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = Space.m, vertical = Space.xs),
        contentAlignment = Alignment.Center,
    ) { Line(label, Type.small, c.ink, maxLines = 1) }
}

/** A start or end: a ring (start) or dot (end) on a dotted rail, the time, and street, city. */
@Composable
private fun End(time: String, where: String?, first: Boolean) {
    val c = Athena.colors
    Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Canvas(Modifier.width(20.dp).fillMaxHeight()) {
            val cx = size.width / 2; val cy = 11.dp.toPx()
            if (first) drawLine(c.ink3, Offset(cx, cy + 7.dp.toPx()), Offset(cx, size.height), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())))
            if (first) drawCircle(c.ink2, 5.dp.toPx(), Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            else drawCircle(c.ink, 5.dp.toPx(), Offset(cx, cy))
        }
        Spacer(Modifier.width(Space.s))
        Column(Modifier.padding(bottom = if (first) Space.m else 0.dp)) {
            Line(time, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
            Line(where ?: "Address not known", Type.small, if (where == null) c.ink3 else c.ink2, maxLines = 1)
        }
    }
}

/** Each kind of event and phone use as a coloured dot, a label, a dotted leader and the value; the events and apps below. */
@Composable
private fun Details(r: DriveReport, route: DriveRoute?, zone: ZoneId, name: (String) -> String) {
    val c = Athena.colors
    var about by remember(r.id) { mutableStateOf(false) }
    Card("Details", action = { TextAction(if (about) "Close" else "About", { about = !about }) }) {
        if (about) Line(
            "Hard braking and rapid acceleration are 0.3 g held 0.4 s, sharp turns 0.35 g; speeding is 10 s more than 10 mph over the road's usual speed, or 80 mph. " +
                "Phone use is screen time and other apps while moving; maps, music and Android Auto count as hands-free.",
            Type.small, c.ink3, Modifier.padding(bottom = Space.s),
        )
        if (!r.aligned) Line("The phone moved a lot, so braking is from GPS only on this drive.", Type.caption, c.ink3, Modifier.padding(bottom = Space.xs))
        fun times(n: Int) = if (n == 1) "1 time" else "$n times"
        Leader(eventColor(DriveEventKind.RAPID_ACCEL), "Rapid acceleration", times(r.count(DriveEventKind.RAPID_ACCEL)))
        Leader(eventColor(DriveEventKind.HARSH_BRAKE), "Hard braking", times(r.count(DriveEventKind.HARSH_BRAKE)))
        Leader(eventColor(DriveEventKind.SHARP_TURN), "Sharp turns", times(r.count(DriveEventKind.SHARP_TURN)))
        Leader(eventColor(DriveEventKind.SPEEDING), "Speeding", times(r.count(DriveEventKind.SPEEDING)))
        route?.let { DriveInsights.motion(it.points) }?.let { m ->
            Leader(bandColor(SpeedBand.SLOW), "Below 12.5 mph", "${Units.mi(m.slowMeters)}, ${Fmt.minutes(m.slowMinutes.toLong().coerceAtLeast(if (m.slowMinutes > 0) 1 else 0))}")
        }
        val p = r.phone
        val (free, apps) = p.apps.entries.partition { it.key in DriveAnalyzer.HANDS_FREE }
        val phone = p.screenMinutesMoving + apps.sumOf { it.value }
        Leader(eventColor(DriveEventKind.HANDLING), "Phone use", "${"%.1f".format(Locale.US, phone).removeSuffix(".0")} min")
        if (r.events.isNotEmpty() || p.unlocksMoving > 0 || p.apps.isNotEmpty() || p.handsFree.isNotEmpty()) Spacer(Modifier.height(Space.s))
        for (e in r.events) FactRow(TIME.format(Instant.ofEpochMilli(e.at).atZone(zone)), "${e.kind.label}" + (if (e.g > 0) ", ${"%.2f".format(Locale.US, e.g)} g" else "") + ", ${Units.mphText(e.kmh)}" + (e.street?.let { ", $it" } ?: ""), labelWidth = 80.dp)
        if (p.screenMinutesMoving > 0 || p.screenMinutesStopped > 0) FactRow("Screen", "${"%.1f".format(Locale.US, p.screenMinutesMoving)} min moving, ${"%.1f".format(Locale.US, p.screenMinutesStopped)} min stopped", labelWidth = 80.dp)
        if (p.unlocksMoving > 0) FactRow("Unlocks", "${p.unlocksMoving} while moving", labelWidth = 80.dp)
        // Drives logged before Android Auto counted as hands-free still list it under apps: show it where it belongs.
        for ((app, m) in apps) FactRow("App", "${name(app)}, ${"%.1f".format(Locale.US, m)} min", labelWidth = 80.dp)
        for ((app, m) in p.handsFree.entries + free) FactRow("Hands-free", "${name(app)}, ${"%.1f".format(Locale.US, m)} min", "OK", Tone.OK, labelWidth = 80.dp)
    }
}

@Composable
private fun Leader(color: Color, label: String, value: String) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Space.m))
        Line(label, Type.body, c.ink, maxLines = 1)
        Canvas(Modifier.weight(1f).height(12.dp).padding(horizontal = Space.s)) {
            drawLine(c.rule, Offset(0f, size.height * 0.75f), Offset(size.width, size.height * 0.75f), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5.dp.toPx(), 4.dp.toPx())), cap = androidx.compose.ui.graphics.StrokeCap.Round)
        }
        Line(value, Type.body, c.ink2, maxLines = 1)
    }
}
