package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The driving report on synthetic sensor traces: a car's own forward and sideways acceleration, turned
 * into what a phone in an odd mount would read (gravity included), at 50 Hz, with 1 Hz GPS.
 */
class DriveTest {
    /** A phone mount: yaw, pitch and roll of the phone relative to the car, in degrees. */
    private class Mount(yaw: Double, pitch: Double, roll: Double) {
        private val m: Array<DoubleArray>
        init {
            val (a, b, c) = listOf(yaw, pitch, roll).map { Math.toRadians(it) }
            val rz = arrayOf(doubleArrayOf(cos(a), -sin(a), 0.0), doubleArrayOf(sin(a), cos(a), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
            val ry = arrayOf(doubleArrayOf(cos(b), 0.0, sin(b)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(b), 0.0, cos(b)))
            val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(c), -sin(c)), doubleArrayOf(0.0, sin(c), cos(c)))
            m = mul(mul(rz, ry), rx)
        }
        private fun mul(p: Array<DoubleArray>, q: Array<DoubleArray>) = Array(3) { i -> DoubleArray(3) { j -> (0..2).sumOf { p[i][it] * q[it][j] } } }
        /** Car frame (forward, left, up) to phone frame: the transpose of the phone's rotation. */
        fun phone(v: DoubleArray) = DoubleArray(3) { i -> (0..2).sumOf { m[it][i] * v[it] } }
    }

    /** A drive as phases of (seconds, forward m/s², lateral m/s²). */
    private fun drive(a: DriveAnalyzer, mount: Mount, phases: List<Triple<Double, Double, Double>>, start: Long = 1_000_000L, onTick: (Long, Double) -> Unit = { _, _ -> }): Long {
        var t = start
        var v = 0.0
        var lat = 42.95
        var nextGps = start
        for ((secs, fwd, side) in phases) {
            val end = t + (secs * 1000).toLong()
            while (t < end) {
                v = (v + fwd * 0.02).coerceAtLeast(0.0)
                val p = mount.phone(doubleArrayOf(fwd, side, DriveAnalyzer.G))
                a.accel(t, p[0], p[1], p[2])
                // The car's yaw is about its vertical axis, which the mount turns into phone axes too.
                val yaw = mount.phone(doubleArrayOf(0.0, 0.0, if (v > 1) side / v else 0.0))
                a.gyro(t, yaw[0], yaw[1], yaw[2])
                if (t >= nextGps) { a.gps(t, v, lat, -85.68); lat += v / 111_320.0; nextGps += 1000 }
                onTick(t, v)
                t += 20
            }
        }
        return t
    }

    private val calm = listOf(
        Triple(5.0, 0.0, 0.0),        // parked, phone settles
        Triple(10.0, 1.5, 0.0),       // pulling away gently (0.15 g)
        Triple(30.0, 0.0, 0.0),       // cruising at 15 m/s
        Triple(6.0, -1.2, 0.0),       // easing off
        Triple(20.0, 0.0, 0.0),
    )

    @Test fun aGentleDriveHasNoEventsAndScoresFull() {
        val a = DriveAnalyzer("d1", 0)
        val end = drive(a, Mount(37.0, -62.0, 12.0), calm)
        val r = a.finish(end)
        assertTrue(r.events.toString(), r.events.isEmpty())
        assertEquals(100, r.score)
        assertTrue(r.aligned)
    }

    @Test fun hardBrakingAndASharpTurnInAnyMount() {
        for (mount in listOf(Mount(0.0, 0.0, 0.0), Mount(90.0, -70.0, 0.0), Mount(-140.0, 25.0, 80.0))) {
            val a = DriveAnalyzer("d2", 0)
            val end = drive(a, mount, calm.take(3) + listOf(
                Triple(1.5, -4.2, 0.0),   // hard braking, 0.43 g
                Triple(5.0, 0.0, 0.0),
                Triple(2.0, 0.0, 4.0),    // sharp left, 0.41 g at ~8.7 m/s
                Triple(10.0, 0.0, 0.0),
            ))
            val r = a.finish(end)
            assertEquals(r.events.toString(), listOf(DriveEventKind.HARSH_BRAKE, DriveEventKind.SHARP_TURN), r.events.map { it.kind })
            assertTrue(r.events[0].g >= 0.3)
            assertTrue(r.score < 100)
        }
    }

    @Test fun rapidAcceleration() {
        val a = DriveAnalyzer("d3", 0)
        val end = drive(a, Mount(20.0, -50.0, 5.0), calm.take(3) + listOf(Triple(2.0, -1.0, 0.0), Triple(2.0, 3.6, 0.0), Triple(10.0, 0.0, 0.0)))
        assertEquals(listOf(DriveEventKind.RAPID_ACCEL), a.finish(end).events.map { it.kind })
    }

    @Test fun withoutAMountGpsStillCatchesHardBraking() {
        val a = DriveAnalyzer("d4", 0)
        var t = 0L
        val speeds = listOf(0.0, 2.5, 5.0, 7.5, 10.0, 12.5, 14.0, 14.0, 14.0, 10.0, 6.0, 5.0, 5.0)
        for (v in speeds) { a.gps(t, v, 42.95, -85.68); t += 1000 }
        val r = a.finish(t)
        assertEquals(listOf(DriveEventKind.HARSH_BRAKE), r.events.map { it.kind })
        assertTrue(!r.aligned)
    }

    @Test fun pickingThePhoneUpWhileMoving() {
        val a = DriveAnalyzer("d5", 0)
        val mount = Mount(0.0, -60.0, 0.0)
        var t = drive(a, mount, calm.take(3))
        // He lifts the phone off the mount: a quick twist (about an axis across gravity) and a new orientation.
        val up = mount.phone(doubleArrayOf(0.0, 0.0, 1.0))
        val twist = doubleArrayOf(0.0, up[2], -up[1]).let { v -> val n = Math.sqrt(v.sumOf { it * it }); DoubleArray(3) { v[it] / n * 2.6 } }
        val lifted = Mount(10.0, -10.0, 30.0)
        repeat(50) { a.gyro(t, twist[0], twist[1], twist[2]); val p = lifted.phone(doubleArrayOf(0.0, 0.0, DriveAnalyzer.G)); a.accel(t, p[0], p[1], p[2]); t += 20 }
        repeat(300) { a.gyro(t, 0.0, 0.0, 0.0); val p = lifted.phone(doubleArrayOf(0.0, 0.0, DriveAnalyzer.G)); a.accel(t, p[0], p[1], p[2]); if (it % 50 == 0) a.gps(t, 15.0, 42.96, -85.68); t += 20 }
        val r = a.finish(t)
        // Only the pick-up: the tilt itself is not read as braking or a turn.
        assertEquals(r.events.toString(), listOf(DriveEventKind.HANDLING), r.events.map { it.kind })
    }

    @Test fun speedingIsOneEventPerStretch() {
        val a = DriveAnalyzer("d6", 0)
        var t = 0L
        repeat(60) { a.gps(t, 29.0, 42.95, -85.68); t += 1000 }   // 65 mph for a minute
        repeat(20) { a.gps(t, 37.0, 42.95, -85.68); t += 1000 }   // 83 mph for 20 s
        repeat(20) { a.gps(t, 29.0, 42.95, -85.68); t += 1000 }
        repeat(15) { a.gps(t, 37.5, 42.95, -85.68); t += 1000 }
        assertEquals(2, a.finish(t).count(DriveEventKind.SPEEDING))
    }

    @Test fun screenUseCountsOnlyWhileMoving() {
        val a = DriveAnalyzer("d7", 0)
        // 0-60 s stopped at a light, 60-180 s moving.
        var t = 0L
        repeat(180) {
            a.gps(t, if (t < 60_000) 0.0 else minOf(12.0, 2.0 + (t - 60_000) / 1000 * 2.0), 42.95, -85.68)
            when (t) {
                20_000L -> a.unlock(t)                 // stopped: not counted
                30_000L -> a.screenOn(t)               // 30 s stopped, then 30 s moving
                90_000L -> a.screenOff(t)
                120_000L -> a.unlock(t)                // moving
            }
            t += 1000
        }
        val r = a.finish(t, apps = mapOf("com.whatsapp" to 1.5, "com.google.android.apps.maps" to 2.0))
        assertEquals(0.5, r.phone.screenMinutesMoving, 0.05)
        assertEquals(0.5, r.phone.screenMinutesStopped, 0.05)
        assertEquals(1, r.phone.unlocksMoving)
        assertEquals(mapOf("com.whatsapp" to 1.5), r.phone.apps)
        assertEquals(mapOf("com.google.android.apps.maps" to 2.0), r.phone.handsFree)
        assertEquals(listOf(DriveEventKind.UNLOCK), r.events.map { it.kind })
    }

    @Test fun weeklyReportAndWorstSpots() {
        val now = 30 * Fx.DAY
        fun brake(at: Long, lat: Double) = DriveEvent(DriveEventKind.HARSH_BRAKE, at, lat, -85.6784, 0.4, 30, "Main St")
        val drives = listOf(
            DriveReport("a", now - Fx.DAY, now - Fx.DAY + 20 * Fx.MIN, 12_000, 70, listOf(brake(now - Fx.DAY, 42.9000)), score = 94),
            DriveReport("b", now - 2 * Fx.DAY, now - 2 * Fx.DAY + 20 * Fx.MIN, 12_000, 70, listOf(brake(now - 2 * Fx.DAY, 42.90003)), score = 90),
            DriveReport("c", now - 3 * Fx.DAY, now - 3 * Fx.DAY + 20 * Fx.MIN, 12_000, 70, listOf(brake(now - 3 * Fx.DAY, 42.90005), brake(now - 3 * Fx.DAY + 1, 42.95)), score = 80),
            DriveReport("old", now - 9 * Fx.DAY, now - 9 * Fx.DAY + 20 * Fx.MIN, 12_000, 70, emptyList(), score = 100),
        )
        val w = DrivingReport.week(drives, now)
        assertEquals(3, w.trips)
        assertEquals(88, w.score)
        assertEquals(100, w.lastWeekScore)
        assertEquals(4, w.counts[DriveEventKind.HARSH_BRAKE])
        assertEquals("c", w.worst!!.id)
        assertEquals(listOf("Hard braking 3x at the same spot on Main St"), w.spots)
    }
}
