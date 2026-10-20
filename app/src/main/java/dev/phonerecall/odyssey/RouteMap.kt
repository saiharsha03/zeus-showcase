package dev.phonerecall.odyssey

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.phonerecall.aeolia.WeatherNet
import dev.phonerecall.moneta.ChartData
import dev.phonerecall.moneta.Metric
import dev.phonerecall.moneta.Series
import dev.phonerecall.moneta.TimeChart
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/**
 * Esri's gray canvas, the same tiles and the same door (WeatherNet, server.arcgisonline.com) as the weather map.
 * Only tile numbers go out; the route itself is drawn on the phone.
 */
object MapTiles {
    private val cache by lazy { android.util.LruCache<String, ImageBitmap>(96) }

    fun url(z: Int, x: Int, y: Int, dark: Boolean): String {
        val n = 1 shl z
        return "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_${if (dark) "Dark" else "Light"}_Gray_Base/MapServer/tile/$z/$y/${((x % n) + n) % n}"
    }

    fun cached(url: String): ImageBitmap? = cache.get(url)

    /** Null when offline or the tile is not an image. */
    fun fetch(url: String): ImageBitmap? = runCatching {
        val b = WeatherNet.get(url)
        BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap()
    }.getOrNull()?.also { cache.put(url, it) }
}

/** The route's speed colours: one blue, light to deep (deep to light in the dark), and alert red over the limit. */
@Composable
fun bandColor(b: SpeedBand): Color {
    val c = Athena.colors
    return when (b) {
        SpeedBand.SLOW -> Color(if (c.isDark) 0xFF2D5F9C else 0xFF9DC1EE)
        SpeedBand.STEADY -> Color(if (c.isDark) 0xFF3987E5 else 0xFF2A78D6)
        SpeedBand.FAST -> Color(if (c.isDark) 0xFF9CC8F7 else 0xFF174A8C)
        SpeedBand.OVER -> c.alert
    }
}

fun phoneUse(k: DriveEventKind) = k == DriveEventKind.HANDLING || k == DriveEventKind.UNLOCK

/** One colour per kind of event, the same on the map, the speed line's ticks and the Details rows (chart palette). */
@Composable
fun eventColor(k: DriveEventKind): Color {
    val c = Athena.colors
    return when (k) {
        DriveEventKind.RAPID_ACCEL -> Color(if (c.isDark) 0xFFD95926 else 0xFFEB6834)
        DriveEventKind.HARSH_BRAKE -> Color(if (c.isDark) 0xFFD55181 else 0xFFD9558A)
        DriveEventKind.SHARP_TURN -> Color(if (c.isDark) 0xFF9085E9 else 0xFF6250D6)
        DriveEventKind.SPEEDING -> c.alert
        DriveEventKind.HANDLING, DriveEventKind.UNLOCK -> Color(0xFFC98500)
    }
}

private fun what(k: DriveEventKind) = if (phoneUse(k)) "Phone use (${k.label.lowercase()})" else k.label

/**
 * A drive's report map: the route coloured by speed over Esri's gray canvas, start and end pins, the events
 * as markers (tap one for what, when and where). Pinch to zoom, drag to pan. Offline, or with `online` off,
 * the route sits on a plain background with a scale bar.
 */
@Composable
fun DriveMap(route: DriveRoute, report: DriveReport, zone: ZoneId, online: Boolean = true, hotspots: List<Hotspot> = emptyList(), modifier: Modifier = Modifier) {
    val c = Athena.colors
    val density = LocalDensity.current.density
    val tile = 256f * density
    val pts = route.points
    val marks = report.events.filter { it.lat != 0.0 || it.lng != 0.0 }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var view by remember(route.id) { mutableStateOf<MapView?>(null) }
    var picked by remember(route.id) { mutableStateOf<Int?>(null) }
    val tiles = remember { mutableStateMapOf<String, ImageBitmap>() }
    var offline by remember { mutableStateOf(!online) }
    val dark = c.isDark
    val measurer = rememberTextMeasurer()

    LaunchedEffect(box, route.id) {
        if (box.width > 0 && view == null) {
            val b = Track.bounds(pts.map { it.lat to it.lng } + marks.map { it.lat to it.lng }) ?: return@LaunchedEffect
            view = Track.fit(b, box.width.toFloat(), box.height.toFloat(), 28f * density, tile)
        }
    }
    val v = view
    val need = if (v == null || offline) emptyList() else Track.tilesFor(v, box.width.toFloat(), box.height.toFloat(), tile).map { (z, x, y) -> MapTiles.url(z, x, y, dark) }
    LaunchedEffect(need) {
        for (u in need) {
            if (u in tiles) continue
            val img = MapTiles.cached(u) ?: withContext(Dispatchers.IO) { MapTiles.fetch(u) }
            if (img == null) { if (tiles.isEmpty()) offline = true; return@LaunchedEffect }
            tiles[u] = img
        }
    }

    val shape = RoundedCornerShape(Radius.block)
    val bandColors = SpeedBand.entries.associateWith { bandColor(it) }
    val kindColors = DriveEventKind.entries.associateWith { eventColor(it) }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(260.dp).clip(shape).background(c.block(Tint.NEUTRAL)).border(1.dp, c.edge, shape)) {
            Canvas(
                Modifier.fillMaxWidth().height(260.dp).onSizeChanged { box = it }
                    .pointerInput(route.id) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val cur = view ?: return@detectTransformGestures
                            val s = tile * 2.0.pow(cur.zoom)
                            val wx = cur.cx + (centroid.x - pan.x - size.width / 2) / s
                            val wy = cur.cy + (centroid.y - pan.y - size.height / 2) / s
                            val z = (cur.zoom + kotlin.math.log2(zoom.toDouble())).coerceIn(Track.MIN_ZOOM, Track.MAX_ZOOM)
                            val s2 = tile * 2.0.pow(z)
                            view = MapView(z, wx - (centroid.x - size.width / 2) / s2, wy - (centroid.y - size.height / 2) / s2)
                        }
                    }
                    .pointerInput(route.id) {
                        detectTapGestures { p ->
                            val cur = view ?: return@detectTapGestures
                            val hit = marks.indices.minByOrNull { i ->
                                val (x, y) = Track.project(cur, marks[i].lat, marks[i].lng, size.width.toFloat(), size.height.toFloat(), tile)
                                (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y)
                            }?.takeIf { i ->
                                val (x, y) = Track.project(cur, marks[i].lat, marks[i].lng, size.width.toFloat(), size.height.toFloat(), tile)
                                (x - p.x) * (x - p.x) + (y - p.y) * (y - p.y) < (28 * density).let { it * it }
                            }
                            picked = if (hit == picked) null else hit
                        }
                    },
            ) {
                val cur = view ?: return@Canvas
                val w = size.width; val h = size.height
                if (!offline) for ((z, x, y) in Track.tilesFor(cur, w, h, tile)) {
                    val img = tiles[MapTiles.url(z, x, y, dark)] ?: continue
                    val n = 1 shl z
                    val s = tile * 2.0.pow(cur.zoom)
                    val left = ((x.toDouble() / n - cur.cx) * s + w / 2).toFloat(); val top = ((y.toDouble() / n - cur.cy) * s + h / 2).toFloat()
                    val side = (s / n).toFloat()
                    drawImage(img, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize((left + side).roundToInt() - left.roundToInt(), (top + side).roundToInt() - top.roundToInt()))
                }
                fun at(lat: Double, lng: Double) = Track.project(cur, lat, lng, w, h, tile).let { Offset(it.first, it.second) }
                // Hotspots first, under everything: a soft dot that grows with how often it happens there.
                for (hs in hotspots) {
                    val o = at(hs.lat, hs.lng)
                    val r = (14 + 6 * kotlin.math.sqrt(hs.count.toFloat())).dp.toPx()
                    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(kindColors.getValue(hs.kind).copy(alpha = 0.45f), Color.Transparent), o, r), r, o)
                }
                val xy = pts.map { at(it.lat, it.lng) }
                if (xy.size >= 2) {
                    val casing = Path().apply { moveTo(xy[0].x, xy[0].y); for (i in 1 until xy.size) lineTo(xy[i].x, xy[i].y) }
                    drawPath(casing, c.surface, style = Stroke(7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    for ((band, path) in bandPaths(pts, xy)) drawPath(path, bandColors.getValue(band), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    pin(xy.first(), bandColors.getValue(SpeedBand.STEADY), c.surface)
                    pin(xy.last(), c.ink, c.surface)
                }
                marks.forEachIndexed { i, e ->
                    val o = at(e.lat, e.lng)
                    val r = if (i == picked) 8.dp.toPx() else 6.dp.toPx()
                    drawCircle(c.surface, r + 2.dp.toPx(), o)
                    drawCircle(kindColors.getValue(e.kind), r, o)
                }
                // Scale bar, bottom left.
                val mpp = Track.metersPerPx(cur, (pts.firstOrNull()?.lat ?: 0.0), tile)
                val (meters, scaleText) = Track.scale(mpp, 96.dp.toPx())
                val len = (meters / mpp).toFloat()
                val x0 = 12.dp.toPx(); val y0 = h - 12.dp.toPx()
                drawLine(c.ink2, Offset(x0, y0), Offset(x0 + len, y0), 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c.ink2, Offset(x0, y0 - 5.dp.toPx()), Offset(x0, y0), 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(c.ink2, Offset(x0 + len, y0 - 5.dp.toPx()), Offset(x0 + len, y0), 2.dp.toPx(), cap = StrokeCap.Round)
                val label = measurer.measure(scaleText, Type.caption.copy(color = c.ink2))
                drawText(label, topLeft = Offset(x0, y0 - 6.dp.toPx() - label.size.height))
                if (!offline && tiles.isNotEmpty()) {
                    val credit = measurer.measure("Esri", Type.caption.copy(color = c.ink3))
                    drawText(credit, topLeft = Offset(w - credit.size.width - 10.dp.toPx(), h - credit.size.height - 6.dp.toPx()))
                }
            }
        }
        val e = picked?.let { marks.getOrNull(it) }
        if (e != null) {
            Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(kindColors.getValue(e.kind)))
                Spacer(Modifier.width(Space.s))
                Line(
                    "${what(e.kind)}, ${TIME.format(Instant.ofEpochMilli(e.at).atZone(zone))}" + (e.street?.let { ", $it" } ?: "") + ", ${Units.mphText(e.kmh)}" + (if (e.g > 0) ", ${"%.2f".format(e.g)} g" else ""),
                    Type.small, c.ink,
                )
            }
        } else {
            FlowRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                for ((b, label) in listOf(SpeedBand.SLOW to "Under 12.5", SpeedBand.STEADY to "12.5–50", SpeedBand.FAST to "50+", SpeedBand.OVER to "75+ mph")) Key(bandColors.getValue(b), label)
                if (marks.isNotEmpty()) Line("Tap a dot for details", Type.caption, c.ink3)
                if (hotspots.isNotEmpty()) Line("Soft dots: spots where it keeps happening", Type.caption, c.ink3)
                if (offline) Line("Offline: no map tiles", Type.caption, c.ink3)
            }
        }
    }
}

@Composable
private fun Key(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(Space.xs))
        Line(label, Type.caption, Athena.colors.ink2, maxLines = 1)
    }
}

private fun DrawScope.pin(o: Offset, fill: Color, ring: Color) {
    drawCircle(ring, 8.dp.toPx(), o)
    drawCircle(fill, 6.dp.toPx(), o)
}

/** Runs of the route in one speed band, each its own path; neighbours share an end so there are no gaps. */
private fun bandPaths(pts: List<TrackPoint>, xy: List<Offset>): List<Pair<SpeedBand, Path>> {
    val out = ArrayList<Pair<SpeedBand, Path>>()
    var cur: SpeedBand? = null
    var path = Path()
    for (i in 1 until xy.size) {
        val b = Track.band((pts[i - 1].kmh + pts[i].kmh) / 2)
        if (b != cur) {
            path = Path().apply { moveTo(xy[i - 1].x, xy[i - 1].y) }
            out += b to path
            cur = b
        }
        path.lineTo(xy[i].x, xy[i].y)
    }
    return out
}

/** A route's shape alone, fitted to its box: the thumbnail on drive rows and the weekly card. */
@Composable
fun RouteThumb(route: DriveRoute, modifier: Modifier = Modifier) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.blockSmall)
    Canvas(modifier.clip(shape).background(c.tile(Tint.SERVER))) {
        val b = Track.bounds(route.points.map { it.lat to it.lng }) ?: return@Canvas
        val v = Track.fit(b, size.width, size.height, 6.dp.toPx(), 256f * density)
        val xy = route.points.map { Track.project(v, it.lat, it.lng, size.width, size.height, 256f * density).let { (x, y) -> Offset(x, y) } }
        if (xy.size < 2) return@Canvas
        drawPath(Path().apply { moveTo(xy[0].x, xy[0].y); for (i in 1 until xy.size) lineTo(xy[i].x, xy[i].y) }, c.accentInk, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.surface, 3.dp.toPx(), xy.first()); drawCircle(c.ok, 2.dp.toPx(), xy.first())
        drawCircle(c.surface, 3.dp.toPx(), xy.last()); drawCircle(c.ink, 2.dp.toPx(), xy.last())
    }
}

/** Speed over the drive in mph (Moneta's time chart): the peak named, the average as the reference line, events as ticks. */
@Composable
fun SpeedChart(route: DriveRoute, report: DriveReport, zone: ZoneId) {
    val c = Athena.colors
    val series = Track.speedSeries(route.points)
    if (series.size < 2) return
    val t0 = series.first().first; val span = (series.last().first - t0).coerceAtLeast(1)
    val mph = series.map { Units.mph(it.second).toLong() }
    val data = ChartData(series.map { TIME.format(Instant.ofEpochMilli(it.first).atZone(zone)) }, listOf(Series("Speed", mph)), Units.mph(Track.averageKmh(route.points)).toLong(), Metric.COUNT)
    val kinds = DriveEventKind.entries.associateWith { eventColor(it) }
    val ticks = report.events.map { ((it.at - t0).toFloat() / span) to kinds.getValue(it.kind) }
    var picked by remember(route.id) { mutableStateOf<Int?>(null) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Line("mph", Type.caption, c.ink3, Modifier.weight(1f))
            Line(picked?.let { "${data.labels[it]}, ${mph[it]} mph" } ?: "Top ${Units.mphText(report.maxKmh)}", Type.caption, c.ink2, maxLines = 1)
        }
        TimeChart(data, mapOf("Speed" to bandColor(SpeedBand.STEADY)), line = true, picked = picked, ticks = ticks, refLabel = { "Avg $it" }, peakLabel = { "Max $it" }) { picked = it }
    }
}

/** A small line of scores, oldest to newest, the newest as a dot. */
@Composable
fun Sparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val c = Athena.colors
    if (values.size < 2) return
    Canvas(modifier) {
        val lo = values.min() - 2; val hi = values.max() + 2
        val pad = 3.dp.toPx()
        fun p(i: Int) = Offset(pad + (size.width - 2 * pad) * i / (values.size - 1), pad + (size.height - 2 * pad) * (1f - (values[i] - lo).toFloat() / (hi - lo)))
        drawPath(Path().apply { moveTo(p(0).x, p(0).y); for (i in 1 until values.size) lineTo(p(i).x, p(i).y) }, c.ink3, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(c.ink, 3.dp.toPx(), p(values.lastIndex))
    }
}
