package dev.phonerecall.odyssey

import android.content.Context
import dev.phonerecall.brain.Sealer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

/** One kept point of a drive's path. `t` epoch ms, rounded to ~1 m. */
data class TrackPoint(val t: Long = 0, val lat: Double = 0.0, val lng: Double = 0.0, val kmh: Int = 0)

/** A drive's path, for the map on its report. Sealed on the phone in its own file; Athena never sees it. */
data class DriveRoute(val id: String = "", val end: Long = 0, val points: List<TrackPoint> = emptyList())

/**
 * The path while a drive runs, already thinned: a fix is kept when it is 25 m or 10 s from the last kept one
 * and GPS says it is good to 30 m. [finish] simplifies it to at most 1500 points.
 */
class TrackRecorder {
    private val kept = ArrayList<TrackPoint>()

    fun add(t: Long, lat: Double, lng: Double, speedMs: Double, accuracy: Float) {
        if (accuracy >= Track.MAX_ACCURACY_M) return
        val last = kept.lastOrNull()
        if (last != null && t - last.t < Track.KEEP_MS && Geo.meters(last.lat, last.lng, lat, lng) < Track.KEEP_M) return
        kept += TrackPoint(t, round5(lat), round5(lng), (speedMs * 3.6).roundToInt())
    }

    val size: Int get() = kept.size

    fun finish(): List<TrackPoint> = Track.simplify(kept, Track.MAX_POINTS)

    private fun round5(v: Double) = (v * 1e5).roundToInt() / 1e5
}

/** Speed bands for the route's colour, slow to fast (mph thresholds; speeds are stored in km/h). */
enum class SpeedBand { SLOW, STEADY, FAST, OVER }

/** Where the map looks: Web Mercator centre (0..1 on both axes) and a fractional zoom. */
data class MapView(val zoom: Double, val cx: Double, val cy: Double)

data class Bounds(val minLat: Double, val minLng: Double, val maxLat: Double, val maxLng: Double)

object Track {
    const val KEEP_M = 25.0
    const val KEEP_MS = 10_000L
    const val MAX_ACCURACY_M = 30f
    const val MAX_POINTS = 1500
    const val KEEP_DAYS = 90
    const val MIN_ZOOM = 3.0
    const val MAX_ZOOM = 18.0

    /** Douglas–Peucker with the tolerance doubled until the path fits in `max` points. Ends always stay. */
    fun simplify(points: List<TrackPoint>, max: Int): List<TrackPoint> {
        if (points.size <= max || points.size < 3) return points
        var eps = 1.0
        while (true) {
            val out = douglasPeucker(points, eps)
            if (out.size <= max) return out
            eps *= 2
        }
    }

    private fun douglasPeucker(points: List<TrackPoint>, eps: Double): List<TrackPoint> {
        val lat0 = points[0].lat
        val kx = 111_320.0 * cos(Math.toRadians(lat0)); val ky = 110_540.0
        val xs = DoubleArray(points.size) { (points[it].lng - points[0].lng) * kx }
        val ys = DoubleArray(points.size) { (points[it].lat - lat0) * ky }
        val keep = BooleanArray(points.size)
        keep[0] = true; keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            var far = -1; var farD = eps
            for (i in a + 1 until b) {
                val d = segmentDistance(xs[i], ys[i], xs[a], ys[a], xs[b], ys[b])
                if (d > farD) { far = i; farD = d }
            }
            if (far >= 0) { keep[far] = true; stack.addLast(a to far); stack.addLast(far to b) }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len = dx * dx + dy * dy
        val u = if (len < 1e-9) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0.0, 1.0)
        val ex = ax + u * dx - px; val ey = ay + u * dy - py
        return sqrt(ex * ex + ey * ey)
    }

    /** Under 12.5 mph slow (the report's "below 12.5 mph"), to 50 steady, above fast; 10 mph over a known limit, or 75 mph anywhere without one, is OVER. */
    fun band(kmh: Int, limitKmh: Int? = null): SpeedBand {
        val mph = Units.mphOf(kmh.toDouble())
        return when {
            limitKmh != null && mph > Units.mphOf(limitKmh.toDouble()) + 10 -> SpeedBand.OVER
            mph < DriveInsights.SLOW_MPH -> SpeedBand.SLOW
            mph < 50 -> SpeedBand.STEADY
            limitKmh == null && mph >= 75 -> SpeedBand.OVER
            else -> SpeedBand.FAST
        }
    }

    fun bounds(points: List<Pair<Double, Double>>): Bounds? {
        if (points.isEmpty()) return null
        return Bounds(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first }, points.maxOf { it.second })
    }

    fun mercX(lng: Double) = (lng + 180) / 360
    fun mercY(lat: Double): Double { val r = Math.toRadians(lat.coerceIn(-85.0, 85.0)); return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 }

    /**
     * The view that shows `b` inside a `w` x `h` pixel box with `pad` pixels to spare on every side.
     * `tile` is a 256 px tile's on-screen size (256 x density). A single point gets street level.
     */
    fun fit(b: Bounds, w: Float, h: Float, pad: Float, tile: Float): MapView {
        val x0 = mercX(b.minLng); val x1 = mercX(b.maxLng)
        val y0 = mercY(b.maxLat); val y1 = mercY(b.minLat)
        val dx = x1 - x0; val dy = y1 - y0
        val iw = max(1f, w - 2 * pad); val ih = max(1f, h - 2 * pad)
        val zoom = if (dx < 1e-12 && dy < 1e-12) 16.0
        else log2(min(if (dx < 1e-12) Double.MAX_VALUE else iw / (dx * tile), if (dy < 1e-12) Double.MAX_VALUE else ih / (dy * tile)))
        return MapView(zoom.coerceIn(MIN_ZOOM, MAX_ZOOM), (x0 + x1) / 2, (y0 + y1) / 2)
    }

    /** Screen position of a point in a `w` x `h` box. */
    fun project(v: MapView, lat: Double, lng: Double, w: Float, h: Float, tile: Float): Pair<Float, Float> {
        val s = tile * 2.0.pow(v.zoom)
        return ((mercX(lng) - v.cx) * s + w / 2).toFloat() to ((mercY(lat) - v.cy) * s + h / 2).toFloat()
    }

    /** Ground metres per screen pixel at `lat`. */
    fun metersPerPx(v: MapView, lat: Double, tile: Float): Double = 40_075_016.686 * cos(Math.toRadians(lat)) / (tile * 2.0.pow(v.zoom))

    /** A round scale-bar length in feet or miles that fits in `maxPx`: its metres and its label ("500 ft", "2 mi"). */
    fun scale(mPerPx: Double, maxPx: Float): Pair<Double, String> {
        val steps = listOf(50, 100, 200, 500, 1_000, 2_000).map { it * 0.3048 to "$it ft" } +
            listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0).map { it * Units.M_PER_MI to "${it.toString().removeSuffix(".0")} mi" }
        return steps.lastOrNull { it.first / mPerPx <= maxPx } ?: steps.first()
    }

    /** The tiles (z, x, y) under a view, at the integer zoom below it (Esri's gray canvas stops at 16). `x` is unwrapped; fetch `x mod 2^z`. */
    fun tilesFor(v: MapView, w: Float, h: Float, tile: Float, maxZ: Int = 16): List<Triple<Int, Int, Int>> {
        val z = floor(v.zoom).toInt().coerceIn(1, maxZ)
        val n = 1 shl z
        val s = tile * 2.0.pow(v.zoom)
        val left = v.cx - w / 2 / s; val right = v.cx + w / 2 / s
        val top = v.cy - h / 2 / s; val bottom = v.cy + h / 2 / s
        val xa = floor(left * n).toInt(); val xb = floor(right * n).toInt()
        val ya = floor(top * n).toInt().coerceAtLeast(0); val yb = floor(bottom * n).toInt().coerceAtMost(n - 1)
        return buildList { for (y in ya..yb) for (x in xa..xb) add(Triple(z, x, y)) }
    }

    /** Speed over time for the chart: the drive cut into at most `max` equal stretches, each its mean km/h. */
    fun speedSeries(points: List<TrackPoint>, max: Int = 120): List<Pair<Long, Int>> {
        if (points.size <= max) return points.map { it.t to it.kmh }
        val t0 = points.first().t; val span = (points.last().t - t0).coerceAtLeast(1)
        return points.groupBy { ((it.t - t0) * max / (span + 1)).toInt() }.values.map { g -> g[g.size / 2].t to g.map { it.kmh }.average().roundToInt() }
    }

    /** Distance over time, the whole drive, stops included. */
    fun averageKmh(points: List<TrackPoint>): Int {
        if (points.size < 2) return points.firstOrNull()?.kmh ?: 0
        val m = points.zipWithNext().sumOf { (a, b) -> Geo.meters(a.lat, a.lng, b.lat, b.lng) }
        val s = (points.last().t - points.first().t) / 1000.0
        return if (s <= 0) 0 else (m / s * 3.6).roundToInt()
    }

    fun expired(r: DriveRoute, now: Long) = now - r.end >= KEEP_DAYS * 86_400_000L
}

/**
 * The routes on disk: one sealed file per drive in `dir` (no-backup storage), so dropping a route touches
 * nothing else and Odyssey's main document (the one the brain reads facts from) never holds a coordinate trail.
 */
class RouteFiles(private val dir: File, private val sealer: Sealer) {
    private val safe = Regex("[A-Za-z0-9_-]{1,64}")

    private fun file(id: String): File? = if (safe.matches(id)) File(dir, "$id.sealed") else null

    fun save(r: DriveRoute) {
        val f = file(r.id) ?: return
        dir.mkdirs()
        val tmp = File(dir, "${r.id}.tmp")
        tmp.writeBytes(encode(r))
        tmp.renameTo(f)
    }

    fun load(id: String): DriveRoute? = file(id)?.takeIf { it.exists() }?.let { f -> runCatching { decode(f.readBytes()) }.getOrNull() }

    fun all(): List<DriveRoute> = (dir.listFiles { f -> f.name.endsWith(".sealed") } ?: emptyArray()).mapNotNull { f -> runCatching { decode(f.readBytes()) }.getOrNull() }

    fun delete(id: String): Boolean = file(id)?.delete() ?: false

    /** Drops routes past their 90 days; returns the ids dropped. The drive reports stay. */
    fun prune(now: Long): List<String> = all().filter { Track.expired(it, now) }.map { it.id }.onEach { delete(it) }

    fun wipe() { dir.listFiles()?.forEach { it.delete() } }

    fun encode(r: DriveRoute): ByteArray = sealer.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(r))
    fun decode(bytes: ByteArray): DriveRoute? = sealer.openBytes(bytes)?.let { dev.phonerecall.mind.stateJson.readValue(it, DriveRoute::class.java) }
}

/** Process-wide view of the routes, for the Drives tab. Same Keystore key as odyssey.sealed. */
object RouteStore {
    private val _state = MutableStateFlow<Map<String, DriveRoute>>(emptyMap())
    val state: StateFlow<Map<String, DriveRoute>> = _state.asStateFlow()
    private val lock = Any()

    private fun files(c: Context) = RouteFiles(File(c.noBackupFilesDir, "odyssey-routes"), OdysseyStore.sealer)

    fun load(c: Context, now: Long = System.currentTimeMillis()): Map<String, DriveRoute> = synchronized(lock) {
        val f = files(c)
        f.prune(now)
        f.all().associateBy { it.id }.also { _state.value = it }
    }

    fun save(c: Context, r: DriveRoute) = synchronized(lock) {
        files(c).save(r)
        _state.value = _state.value + (r.id to r)
    }

    fun delete(c: Context, id: String) = synchronized(lock) {
        files(c).delete(id)
        _state.value = _state.value - id
    }

    fun wipe(c: Context) = synchronized(lock) {
        files(c).wipe()
        _state.value = emptyMap()
    }
}
