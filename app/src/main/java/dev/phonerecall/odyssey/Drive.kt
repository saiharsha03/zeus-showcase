package dev.phonerecall.odyssey

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class DriveEventKind(val label: String, val penalty: Int) {
    HARSH_BRAKE("Hard braking", 6), RAPID_ACCEL("Rapid acceleration", 4), SHARP_TURN("Sharp turn", 4),
    SPEEDING("Speeding", 5), HANDLING("Phone picked up", 3), UNLOCK("Unlocked while moving", 4),
}

/** One event, where it happened (kept on the phone for "worst spots"). `g` is the peak force. */
data class DriveEvent(
    val kind: DriveEventKind = DriveEventKind.HARSH_BRAKE,
    val at: Long = 0,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val g: Double = 0.0,
    val kmh: Int = 0,
    /** The street, from the Geocoder on the phone, filled in after the drive. */
    val street: String? = null,
)

/** Phone use while driving, from screen and unlock broadcasts plus UsageStats when he allowed it. */
data class PhoneUse(
    val screenMinutesMoving: Double = 0.0,
    val screenMinutesStopped: Double = 0.0,
    val unlocksMoving: Int = 0,
    /** Foreground minutes per app while moving. Navigation and music in a mount count as hands-free. */
    val apps: Map<String, Double> = emptyMap(),
    val handsFree: Map<String, Double> = emptyMap(),
)

/** A drive's report. All on the phone. */
data class DriveReport(
    val id: String = "",
    val start: Long = 0,
    val end: Long = 0,
    val meters: Int = 0,
    val maxKmh: Int = 0,
    val events: List<DriveEvent> = emptyList(),
    val phone: PhoneUse = PhoneUse(),
    val score: Int = 100,
    /** False when the mount could not be worked out (the phone moved the whole time): braking from GPS only. */
    val aligned: Boolean = true,
    /** "Main St, Buffalo" at each end, from the Geocoder on the phone after the drive. */
    val from: String? = null,
    val to: String? = null,
    /** Aeolia at the start, in words ("48°F, Rain"), and its WMO code (-1 unknown). */
    val weather: String? = null,
    val weatherCode: Int = -1,
    /** His correction of the trip tag (a TripTag name); null means the automatic one. */
    val tag: String? = null,
) {
    val minutes: Long get() = (end - start) / 60_000L
    fun count(k: DriveEventKind) = events.count { it.kind == k }
}

/**
 * Turns a drive's raw sensors into events, on the phone, as they arrive (~50 Hz accelerometer and
 * gyroscope, 1 Hz GPS). Nothing raw is kept: only the events and the totals.
 *
 * Mount handling: gravity comes from a slow low-pass of the accelerometer. The car's forward axis is the
 * horizontal acceleration that lines up with GPS speed changes (speeding up or slowing down); it is
 * re-learned whenever gravity's direction shifts, which means the phone moved in its mount or was picked up.
 * Thresholds start at the usual telematics levels: 0.3 g along the car, 0.35 g across it, held 0.4 s.
 */
class DriveAnalyzer(private val id: String, private val start: Long) {
    companion object {
        const val G = 9.80665
        const val BRAKE_G = 0.30
        const val ACCEL_G = 0.30
        const val TURN_G = 0.35
        const val HOLD_MS = 400L
        /** Phone use counts only above ~8 km/h; below it he is stopped (at a light). */
        const val MOVING_MS = 8 / 3.6
        val HANDS_FREE = setOf("com.google.android.projection.gearhead", "com.google.android.apps.maps", "com.waze", "com.spotify.music", "com.apple.android.music", "com.google.android.apps.youtube.music", "com.samsung.android.app.spage")
        /** US posted limits in mph, for the speed estimate. */
        private val LIMITS = intArrayOf(25, 30, 35, 40, 45, 55, 65, 70)
    }

    // gravity (low-pass) and the last smoothed horizontal acceleration
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    private var gReady = 0
    private var refGx = 0.0; private var refGy = 0.0; private var refGz = 0.0
    // forward axis, learned from GPS speed changes
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
    private var fVotes = 0
    private var everAligned = false
    // accumulated horizontal accel since the last GPS fix
    private var sumX = 0.0; private var sumY = 0.0; private var sumZ = 0.0; private var sumN = 0
    // smoothing window for longitudinal and lateral g
    private val win = ArrayDeque<DoubleArray>()
    private var winLong = 0.0; private var winLat = 0.0

    private var speed = 0.0
    private var lastLat = 0.0; private var lastLng = 0.0; private var lastGpsAt = 0L; private var lastSpeed = Double.NaN
    private var firstLat = 0.0; private var firstLng = 0.0

    /** The first and last fixes, for the addresses on the report (geocoded on the phone, then dropped). */
    val ends: Pair<Pt, Pt>? get() = if (lastGpsAt == 0L) null else Pt(firstLat, firstLng) to Pt(lastLat, lastLng)
    private var meters = 0.0
    private var maxSpeed = 0.0
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private var speedingSince = 0L; private var speedingFlagged = false

    private val events = ArrayList<DriveEvent>()
    private val lastOf = HashMap<DriveEventKind, Long>()
    private var heldSince = HashMap<DriveEventKind, Long>()
    private var peak = HashMap<DriveEventKind, Double>()
    private var gyroSince = 0L
    // a fast low-pass of the accelerometer (gravity right now) and the phone's own rotation
    private var qx = 0.0; private var qy = 0.0; private var qz = 0.0
    private var twistAt = 0L; private var twisted = 0.0; private var lastGyroAt = 0L

    private var movingSince = 0L
    private val movingSpans = ArrayList<Pair<Long, Long>>()

    /** Milliseconds of [a, b) spent moving, for UsageStats' foreground spans. */
    fun movingOverlap(a: Long, b: Long): Long =
        (movingSpans + listOfNotNull(movingSince.takeIf { it != 0L }?.let { it to Long.MAX_VALUE })).sumOf { (s, e) -> (minOf(b, e) - maxOf(a, s)).coerceAtLeast(0) }

    private var screenOnAt = 0L
    private var screenMoving = 0.0; private var screenStopped = 0.0
    private var unlocks = 0

    val aligned: Boolean get() = fVotes >= 3

    fun accel(t: Long, x: Double, y: Double, z: Double) {
        // ~4 s time constant at 50 Hz, nearly frozen while the car is pushing the phone (a stop, a corner),
        // so the push isn't swallowed into "gravity".
        val dx = x - gx; val dy = y - gy; val dz = z - gz
        val a = if (gReady < 50) 0.1 else if (sqrt(dx * dx + dy * dy + dz * dz) > 0.12 * G) 0.0002 else 0.005
        gx += a * (x - gx); gy += a * (y - gy); gz += a * (z - gz)
        qx += 0.1 * (x - qx); qy += 0.1 * (y - qy); qz += 0.1 * (z - qz)
        gReady++
        if (gReady == 100) { refGx = gx; refGy = gy; refGz = gz }
        if (gReady < 100) return
        // A tilt just ended (the gyroscope saw the phone turn, not the car): gravity is where it is now.
        if (twistAt > 0 && t - twistAt >= 500L) {
            if (twisted > Math.toRadians(20.0)) {
                if (speed > MOVING_MS) flag(DriveEventKind.HANDLING, t, 0.0, 10_000L)
                remount(qx, qy, qz)
            }
            twistAt = 0L; twisted = 0.0
        }
        // No gyroscope, or a slow slide in the mount: gravity drifted more than 25 degrees.
        if (angle(gx, gy, gz, refGx, refGy, refGz) > 25.0) {
            if (speed > MOVING_MS) flag(DriveEventKind.HANDLING, t, 0.0, 10_000L)
            remount(gx, gy, gz)
        }
        val gn = sqrt(gx * gx + gy * gy + gz * gz).takeIf { it > 1e-6 } ?: return
        val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
        val lx = x - gx; val ly = y - gy; val lz = z - gz
        val dot = lx * ux + ly * uy + lz * uz
        val hx = lx - dot * ux; val hy = ly - dot * uy; val hz = lz - dot * uz
        // While the phone itself is turning, its readings say nothing about the car.
        if (twistAt > 0) { win.clear(); winLong = 0.0; winLat = 0.0; heldSince.clear(); return }
        sumX += hx; sumY += hy; sumZ += hz; sumN++
        if (!aligned) return
        val long = (hx * fx + hy * fy + hz * fz) / G
        // lateral axis = gravity x forward
        val sx = uy * fz - uz * fy; val sy = uz * fx - ux * fz; val sz = ux * fy - uy * fx
        val lat = (hx * sx + hy * sy + hz * sz) / G
        win.addLast(doubleArrayOf(long, lat)); winLong += long; winLat += lat
        if (win.size > 25) { val o = win.removeFirst(); winLong -= o[0]; winLat -= o[1] }
        val ml = winLong / win.size; val mt = winLat / win.size
        if (speed > 2.0) {
            hold(DriveEventKind.HARSH_BRAKE, t, -ml >= BRAKE_G, -ml)
            hold(DriveEventKind.RAPID_ACCEL, t, ml >= ACCEL_G, ml)
        }
        if (speed > 15 / 3.6) hold(DriveEventKind.SHARP_TURN, t, abs(mt) >= TURN_G, abs(mt))
    }

    /** A new mount: gravity from [x, y, z], and the forward axis is learned again. */
    private fun remount(x: Double, y: Double, z: Double) {
        gx = x; gy = y; gz = z; refGx = x; refGy = y; refGz = z
        fx = 0.0; fy = 0.0; fz = 0.0; fVotes = 0
        sumX = 0.0; sumY = 0.0; sumZ = 0.0; sumN = 0
        win.clear(); winLong = 0.0; winLat = 0.0
    }

    /**
     * Rotation of the phone itself: the part of the turn rate that is not about gravity (the car turning
     * is about gravity, so a corner doesn't count). A quick twist while moving means it was picked up.
     */
    fun gyro(t: Long, x: Double, y: Double, z: Double) {
        val dt = if (lastGyroAt == 0L) 0.0 else ((t - lastGyroAt) / 1000.0).coerceIn(0.0, 0.1)
        lastGyroAt = t
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
        val about = if (gn > 1e-6) (x * gx + y * gy + z * gz) / gn else 0.0
        val tilt = sqrt((x * x + y * y + z * z - about * about).coerceAtLeast(0.0))
        if (tilt > 0.5) { twistAt = t; twisted += tilt * dt }
        if (speed > MOVING_MS && tilt > 2.0) {
            if (gyroSince == 0L) gyroSince = t
            if (t - gyroSince >= 300L) flag(DriveEventKind.HANDLING, t, 0.0, 10_000L)
        } else gyroSince = 0L
    }

    fun gps(t: Long, speedMs: Double, lat: Double, lng: Double, accuracy: Float = 10f) {
        if (lastGpsAt > 0 && accuracy < 50f) meters += Geo.meters(lastLat, lastLng, lat, lng)
        val dt = (t - lastGpsAt) / 1000.0
        if (!lastSpeed.isNaN() && dt in 0.5..3.0) {
            val dv = (speedMs - lastSpeed) / dt
            // Teach the forward axis: the horizontal push that matches the speed change.
            if (abs(dv) > 0.8 && sumN > 0) {
                val s = if (dv > 0) 1.0 else -1.0
                fx = fx * fVotes + s * sumX / sumN; fy = fy * fVotes + s * sumY / sumN; fz = fz * fVotes + s * sumZ / sumN
                val n = sqrt(fx * fx + fy * fy + fz * fz)
                if (n > 1e-6) { fx /= n; fy /= n; fz /= n; fVotes++; if (fVotes >= 3) everAligned = true }
            }
            // No mount yet: hard braking and launches from GPS alone.
            if (!aligned && (speedMs > 2.0 || lastSpeed > 2.0)) {
                if (dv <= -BRAKE_G * G) flag(DriveEventKind.HARSH_BRAKE, t, -dv / G, 4_000L)
                if (dv >= ACCEL_G * G) flag(DriveEventKind.RAPID_ACCEL, t, dv / G, 4_000L)
            }
        }
        sumX = 0.0; sumY = 0.0; sumZ = 0.0; sumN = 0
        if (lastGpsAt == 0L) { firstLat = lat; firstLng = lng }
        val moving = speedMs > MOVING_MS
        if (moving && movingSince == 0L) movingSince = t
        if (!moving && movingSince != 0L) { movingSpans += movingSince to t; movingSince = 0L }
        lastSpeed = speedMs; speed = speedMs; lastGpsAt = t; lastLat = lat; lastLng = lng
        maxSpeed = max(maxSpeed, speedMs)
        speeding(t, speedMs)
    }

    /**
     * Speeding without a speed-limit service: the road's limit is estimated as the posted step just above
     * the median of the last three minutes, and a stretch of 10 s more than 10 mph over it (or above
     * 80 mph anywhere) counts once.
     */
    private fun speeding(t: Long, v: Double) {
        recent.addLast(t to v)
        while (recent.isNotEmpty() && t - recent.first().first > 180_000L) recent.removeFirst()
        val mph = v * 2.23694
        val median = recent.map { it.second * 2.23694 }.sorted().let { it[it.size / 2] }
        val limit = LIMITS.firstOrNull { it >= median - 2 } ?: 70
        val over = mph > 80.0 || (recent.size >= 30 && mph > limit + 10)
        if (over) {
            if (speedingSince == 0L) speedingSince = t
            if (!speedingFlagged && t - speedingSince >= 10_000L) { speedingFlagged = true; add(DriveEventKind.SPEEDING, t, 0.0) }
        } else { speedingSince = 0L; speedingFlagged = false }
    }

    fun screenOn(t: Long) { if (screenOnAt == 0L) screenOnAt = t }
    fun screenOff(t: Long) {
        if (screenOnAt == 0L) return
        val moving = movingOverlap(screenOnAt, t) / 60_000.0
        screenMoving += moving
        screenStopped += (t - screenOnAt) / 60_000.0 - moving
        screenOnAt = 0L
    }
    fun unlock(t: Long) { if (speed > MOVING_MS) { unlocks++; flag(DriveEventKind.UNLOCK, t, 0.0, 30_000L) } }

    private fun hold(k: DriveEventKind, t: Long, over: Boolean, g: Double) {
        if (!over) { heldSince.remove(k); peak.remove(k); return }
        val since = heldSince.getOrPut(k) { t }
        peak[k] = max(peak[k] ?: 0.0, g)
        if (t - since >= HOLD_MS) { flag(k, t, peak[k] ?: g, 4_000L); heldSince.remove(k); peak.remove(k) }
    }

    private fun flag(k: DriveEventKind, t: Long, g: Double, refractory: Long) {
        if (t - (lastOf[k] ?: Long.MIN_VALUE / 2) < refractory) return
        lastOf[k] = t
        add(k, t, g)
    }

    private fun add(k: DriveEventKind, t: Long, g: Double) {
        events += DriveEvent(k, t, lastLat, lastLng, (g * 100).roundToInt() / 100.0, (speed * 3.6).roundToInt())
    }

    /** `apps` is foreground minutes per package while moving, from UsageStats (empty when not allowed). */
    fun finish(end: Long, apps: Map<String, Double> = emptyMap()): DriveReport {
        screenOff(end)
        val (free, distracting) = apps.entries.partition { it.key in HANDS_FREE }
        val phone = PhoneUse(round1(screenMoving), round1(screenStopped), unlocks, distracting.associate { it.key to round1(it.value) }, free.associate { it.key to round1(it.value) })
        val base = DriveReport(id, start, end, meters.roundToInt(), (maxSpeed * 3.6).roundToInt(), events.toList(), phone, 100, everAligned)
        return base.copy(score = Score.of(base))
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    private fun angle(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val n = sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz)
        if (n < 1e-9) return 0.0
        return Math.toDegrees(acos(((ax * bx + ay * by + az * bz) / n).coerceIn(-1.0, 1.0)))
    }
}

/** A smoothness score out of 100: event penalties per 10 km, plus distracted screen minutes. */
object Score {
    fun of(r: DriveReport): Int {
        val penalty = r.events.sumOf { it.kind.penalty } + 2.0 * (r.phone.screenMinutesMoving + r.phone.apps.values.sum() * 0.5)
        val per = max(1.0, r.meters / 10_000.0)
        return (100 - penalty / per).roundToInt().coerceIn(0, 100)
    }
}

/** A spot where one kind of event keeps happening, across drives. On the phone only (the map's soft dot). */
data class Hotspot(val kind: DriveEventKind, val lat: Double, val lng: Double, val count: Int, val street: String?)

/** The weekly driving report: trends and the spots where the same thing keeps happening. */
data class DrivingWeek(
    val trips: Int, val minutes: Long, val miles: Int, val score: Int?, val lastWeekScore: Int?,
    val counts: Map<DriveEventKind, Int>, val screenMinutes: Double, val worst: DriveReport?, val spots: List<String>,
)

object DrivingReport {
    private const val WEEK = 7 * 86_400_000L

    fun week(drives: List<DriveReport>, now: Long): DrivingWeek {
        val w = drives.filter { now - it.end < WEEK }
        val prev = drives.filter { now - it.end in WEEK until 2 * WEEK }
        fun avg(ds: List<DriveReport>) = ds.takeIf { it.isNotEmpty() }?.let { l -> (l.sumOf { it.score * it.minutes.coerceAtLeast(1) }.toDouble() / l.sumOf { it.minutes.coerceAtLeast(1) }).roundToInt() }
        return DrivingWeek(
            w.size, w.sumOf { it.minutes }, Units.miles(w.sumOf { it.meters }).roundToInt(), avg(w), avg(prev),
            DriveEventKind.entries.associateWith { k -> w.sumOf { it.count(k) } }.filterValues { it > 0 },
            w.sumOf { it.phone.screenMinutesMoving }, w.filter { it.events.isNotEmpty() }.minByOrNull { it.score }, spots(drives.filter { now - it.end < 4 * WEEK }),
        )
    }

    /** "Hard braking 3x at the same spot on Main St": events of one kind within 60 m of each other. */
    fun spots(drives: List<DriveReport>): List<String> =
        hotspots(drives).take(3).map { h -> "${h.kind.label} ${h.count}x at the same spot" + (h.street?.let { " on $it" } ?: "") }

    /** Where the same event keeps happening (2 or more within 60 m), most first. The centre is the group's mean. */
    fun hotspots(drives: List<DriveReport>): List<Hotspot> {
        val evs = drives.flatMap { it.events }.filter { it.kind == DriveEventKind.HARSH_BRAKE || it.kind == DriveEventKind.SHARP_TURN || it.kind == DriveEventKind.RAPID_ACCEL }
            .filter { it.lat != 0.0 || it.lng != 0.0 }
        val used = HashSet<Int>()
        val out = ArrayList<Hotspot>()
        for ((i, e) in evs.withIndex()) {
            if (i in used) continue
            val group = evs.withIndex().filter { (j, o) -> j !in used && o.kind == e.kind && Geo.meters(e.lat, e.lng, o.lat, o.lng) < 60.0 }
            if (group.size < 2) continue
            group.forEach { used += it.index }
            out += Hotspot(e.kind, group.map { it.value.lat }.average(), group.map { it.value.lng }.average(), group.size, group.firstNotNullOfOrNull { it.value.street })
        }
        return out.sortedByDescending { it.count }
    }
}
