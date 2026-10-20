package dev.phonerecall.odyssey

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/*
 * Lake-effect bands on his route, October to April. Radar and forecast reflectivity arrive as map tiles at
 * zoom 7 (one tile is ~300 km across, so a fetch says "western New York" and nothing finer), through
 * Aeolia's WeatherNet. The route itself is sampled here, on the phone, and never leaves it.
 */

/** One radar or forecast frame: when it is valid, and the reflectivity (dBZ) at a point; 0 is clear, null is no data. */
class RadarFrame(val time: Long, val source: String, val dbz: (Double, Double) -> Int?)

/** Web Mercator tiles, as the radar hosts serve them. */
object Tiles {
    const val ZOOM = 7

    data class Px(val x: Int, val y: Int, val px: Int, val py: Int)

    /** The tile, and the pixel in it, for a point. */
    fun at(lat: Double, lng: Double, z: Int = ZOOM): Px {
        val n = 256.0 * (1 shl z)
        val r = Math.toRadians(lat)
        val x = floor((lng + 180) / 360 * n).toInt()
        val y = floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
        return Px(x / 256, y / 256, x % 256, y % 256)
    }

    /** The tiles a route needs: usually one, two near an edge. */
    fun needed(points: List<Pt>, z: Int = ZOOM): Set<Pair<Int, Int>> = points.map { at(it.lat, it.lng, z) }.map { it.x to it.y }.toSet()

    /** A frame from decoded tiles (ARGB, 256 x 256, row by row). A point on a missing tile reads as no data. */
    fun frame(time: Long, source: String, tiles: Map<Pair<Int, Int>, IntArray>, palette: (Int) -> Int, z: Int = ZOOM) = RadarFrame(time, source) { lat, lng ->
        val p = at(lat, lng, z)
        tiles[p.x to p.y]?.let { palette(it[p.py * 256 + p.px]) }
    }
}

/** Tile colour to dBZ. A transparent pixel is clear; a colour far from the ramp (clutter, the weak grey-blues) counts as clear too. */
object Palette {
    // IEM's N0Q / HRRR REFD ramp (radar.js LEGEND.iem): 5, 10, ... 60 dBZ.
    private val IEM = intArrayOf(0x04e9e7, 0x019ff4, 0x0300f4, 0x02fd02, 0x01c501, 0x008e00, 0xfdf802, 0xe5bc00, 0xfd9500, 0xfd0000, 0xbc0000, 0xf800fd)
    private val IEM_DBZ = IntArray(IEM.size) { 5 + it * 5 }
    // RainViewer's universal blue (colour scheme 2), roughly.
    private val RV = intArrayOf(0x88ddee, 0x0099cc, 0x0077aa, 0x005588, 0xffee00, 0xffaa00, 0xff4400, 0xc10000, 0xffaaff)
    private val RV_DBZ = intArrayOf(10, 20, 25, 30, 35, 40, 45, 50, 60)
    private const val FAR = 24_000

    fun iem(argb: Int): Int = nearest(argb, IEM, IEM_DBZ)
    fun rainviewer(argb: Int): Int = nearest(argb, RV, RV_DBZ)

    private fun nearest(argb: Int, ramp: IntArray, dbz: IntArray): Int {
        if ((argb ushr 24) < 0x40) return 0
        var best = -1; var bestD = Int.MAX_VALUE
        for (i in ramp.indices) {
            val dr = (argb shr 16 and 0xff) - (ramp[i] shr 16 and 0xff)
            val dg = (argb shr 8 and 0xff) - (ramp[i] shr 8 and 0xff)
            val db = (argb and 0xff) - (ramp[i] and 0xff)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return if (bestD > FAR) 0 else dbz[best]
    }
}

object SnowBand {
    /** Heavy: lake-effect snow at 25 dBZ and up is an inch an hour or more, with whiteouts. */
    const val HEAVY = 25
    /** A band must cover two sampled points (~1 km of road) for a card; one pixel is not a band. */
    const val MIN_POINTS = 2
    private const val MIN = 60_000L

    fun season(d: LocalDate) = d.monthValue >= 10 || d.monthValue <= 4

    /** When he reaches each point if he leaves at `dep`, spread by distance along the route. */
    fun timeline(points: List<Pt>, seconds: Int, dep: Long): LongArray {
        val cum = DoubleArray(points.size)
        for (i in 1 until points.size) cum[i] = cum[i - 1] + Geo.meters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        val total = cum.lastOrNull()?.takeIf { it > 0 } ?: 1.0
        return LongArray(points.size) { dep + (cum[it] / total * seconds * 1000).toLong() }
    }

    /** The frame valid nearest `t`, within ten minutes; null past what the frames cover. */
    fun frameAt(frames: List<RadarFrame>, t: Long, tol: Long = 10 * MIN): RadarFrame? =
        frames.minByOrNull { kotlin.math.abs(it.time - t) }?.takeIf { kotlin.math.abs(it.time - t) <= tol }

    /** The points where leaving at `dep` meets heavy precipitation; null when the frames don't cover the drive. */
    fun hits(points: List<Pt>, seconds: Int, dep: Long, frames: List<RadarFrame>): List<Int>? {
        val ts = timeline(points, seconds, dep)
        val out = ArrayList<Int>()
        for (i in points.indices) {
            val f = frameAt(frames, ts[i]) ?: return null
            if ((f.dbz(points[i].lat, points[i].lng) ?: 0) >= HEAVY) out += i
        }
        return out
    }

    private fun clear(points: List<Pt>, seconds: Int, dep: Long, frames: List<RadarFrame>): Boolean? = hits(points, seconds, dep, frames)?.let { it.size < MIN_POINTS }

    data class Advice(val road: String?, val from: Long, val to: Long, val before: Long?, val after: Long?)

    /**
     * Null unless leaving at `dep` drives into a real band. Then: when the band sits on that stretch of
     * road, the latest clear departure before (up to an hour earlier, not in the past) and the first
     * clear one after (within what the forecast covers).
     */
    fun advise(points: List<Pt>, roads: List<RoadSpan>, seconds: Int, dep: Long, frames: List<RadarFrame>, now: Long): Advice? {
        if (points.size < 2 || frames.isEmpty()) return null
        val h = hits(points, seconds, dep, frames) ?: return null
        if (h.size < MIN_POINTS) return null
        val sorted = frames.sortedBy { it.time }
        val onStretch = sorted.map { f -> h.any { i -> (f.dbz(points[i].lat, points[i].lng) ?: 0) >= HEAVY } }
        // The run of frames with the band on that stretch, around when he would get there.
        val meet = timeline(points, seconds, dep)[h.first()]
        var k = sorted.indices.minByOrNull { kotlin.math.abs(sorted[it].time - meet) } ?: return null
        if (!onStretch[k]) k = sorted.indices.filter { onStretch[it] }.minByOrNull { kotlin.math.abs(sorted[it].time - meet) } ?: return null
        var a = k; var b = k
        while (a > 0 && onStretch[a - 1]) a--
        while (b < sorted.lastIndex && onStretch[b + 1]) b++
        val step = if (sorted.size > 1) (sorted.last().time - sorted.first().time) / (sorted.size - 1) else 15 * MIN
        val from = if (a == b) sorted[a].time - step / 2 else sorted[a].time
        val to = if (a == b) sorted[b].time + step / 2 else sorted[b].time
        var before: Long? = null
        for (n in 1..12) {
            val d = dep - n * 5 * MIN
            if (d < now + 5 * MIN) break
            if (clear(points, seconds, d, frames) == true) { before = d; break }
        }
        var after: Long? = null
        for (n in 1..36) {
            val d = dep + n * 5 * MIN
            val c = clear(points, seconds, d, frames) ?: break
            if (c) { after = d; break }
        }
        return Advice(TomTom.roadAt(roads, h[h.size / 2]), from, to, before, after)
    }

    /** "Snow band crosses I-190 8:10–8:40" and "Leave 7:40 or after 8:50." Times to five minutes. */
    fun words(a: Advice, snow: Boolean, zone: ZoneId): Pair<String, String> {
        fun t(ms: Long, up: Boolean? = null): String {
            val m = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
            val r = when (up) { true -> (m + 4) / 5 * 5; false -> m / 5 * 5; null -> (m / 5.0).roundToInt() * 5 }
            return Clock.hm(r).let { if (it.endsWith(" AM") || it.endsWith(" PM")) it.substringBefore(' ') + ":00" else it }
        }
        val what = if (snow) "Snow band" else "Heavy band"
        val title = "$what crosses ${a.road ?: "your route"} ${t(a.from, false)}–${t(a.to, true)}"
        val body = when {
            a.before != null && a.after != null -> "Leave ${t(a.before, false)} or after ${t(a.after, true)}."
            a.before != null -> "Leave by ${t(a.before, false)}."
            a.after != null -> "Leave after ${t(a.after, true)}."
            else -> "It sits on the road for the whole forecast. Allow extra time."
        }
        return title to body
    }

    /** The card's note. Radar can't tell snow from rain; the word follows Aeolia's temperature. */
    fun note(key: String, a: Advice, snow: Boolean, destination: String, dep: Long, minutes: Int, source: String, now: Long, zone: ZoneId): RoadNote {
        val (title, body) = words(a, snow, zone)
        val leave = Instant.ofEpochMilli(dep).atZone(zone).toLocalTime().let { Clock.hm(it.hour * 60 + it.minute) }
        return RoadNote(
            key, "snow", now, title, body,
            listOf(NoteFact("Your drive", "$leave, $minutes min to $destination"), NoteFact("From", source)),
            expiresAt = maxOf(a.to, a.after ?: 0L, dep) + 30 * MIN,
        )
    }
}
