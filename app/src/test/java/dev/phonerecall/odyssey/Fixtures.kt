package dev.phonerecall.odyssey

import java.time.LocalDate
import java.time.ZoneId

/** Synthetic test places (a made-up grid near Grand Rapids, MI) and helpers that drive the visit detector the way the receivers do. */
object Fx {
    val zone: ZoneId = ZoneId.of("America/New_York")
    const val MIN = 60_000L
    const val H = 60 * MIN
    const val DAY = 24 * H

    // Home on the north side, the office downtown, a Costco on Maple Ave, a temple out of town.
    const val HOME_LAT = 42.9550; const val HOME_LNG = -85.6900
    const val WORK_LAT = 42.8864; const val WORK_LNG = -85.6784
    const val COSTCO_LAT = 43.0012; const val COSTCO_LNG = -85.6197
    const val TEMPLE_LAT = 43.0270; const val TEMPLE_LNG = -85.5790

    fun at(day: LocalDate, h: Int, m: Int = 0): Long = day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    fun on() = OdysseyData(settings = OdysseySettings(on = true))

    /** A straight route between two points, a point every ~500 m. */
    fun line(a: Pt, b: Pt): List<Pt> {
        val n = kotlin.math.ceil(Geo.meters(a.lat, a.lng, b.lat, b.lng) / 500.0).toInt().coerceAtLeast(1)
        return (0..n).map { i -> Pt(a.lat + (b.lat - a.lat) * i / n, a.lng + (b.lng - a.lng) * i / n) }
    }

    /** Arrive (drive ends, one fix), gather hints, leave (next drive starts). */
    fun stay(d: OdysseyData, lat: Double, lng: Double, from: Long, to: Long, hints: List<String> = emptyList(), street: String? = null, poi: String? = null, wifi: String? = null): OdysseyData {
        var x = Visits.activity(d, Act.VEHICLE_EXIT, from).data
        x = Visits.fix(x, Fix(from, lat, lng, 20f), poi, street, wifi).data
        for (h in hints) x = Visits.hint(x, h)
        return Visits.activity(x, Act.VEHICLE_ENTER, to).data
    }

    /** A few weeks of Mon-Wed office days: home overnight, office 9-5, the commute between. */
    fun officeWeeks(weeks: Int, start: LocalDate = LocalDate.of(2026, 9, 7), departMin: (LocalDate) -> Int = { 8 * 60 + 20 }, driveMin: (LocalDate) -> Int = { 22 }): OdysseyData {
        var d = on()
        var day = start
        var homeSince = at(start.minusDays(1), 18)
        repeat(weeks * 7) {
            if (day.dayOfWeek.value <= 3) {
                val leave = at(day, 0) + departMin(day) * MIN
                d = stay(d, HOME_LAT, HOME_LNG, homeSince, leave)
                val arrive = leave + driveMin(day) * MIN
                d = stay(d, WORK_LAT, WORK_LNG, arrive, at(day, 17))
                homeSince = at(day, 17, 25)
            }
            day = day.plusDays(1)
        }
        return d
    }
}

/**
 * A synthetic drive from Costco home, fed to TrackRecorder at 1 Hz the way DriveService does: a few legs with
 * bends, slow at the ends and lights, a fast stretch on the 290 in the middle.
 */
fun syntheticRoute(id: String, start: Long): DriveRoute {
    val legs = listOf(
        Pt(Fx.COSTCO_LAT, Fx.COSTCO_LNG) to 25, Pt(43.0035, -85.6290) to 45, Pt(42.9990, -85.6420) to 105,
        Pt(42.9850, -85.6650) to 70, Pt(42.9720, -85.6780) to 40, Pt(42.9600, -85.6850) to 20, Pt(Fx.HOME_LAT, Fx.HOME_LNG) to 0,
    )
    val rec = TrackRecorder()
    var t = start
    for (i in 0 until legs.size - 1) {
        val (a, kmh) = legs[i]; val b = legs[i + 1].first
        val m = Geo.meters(a.lat, a.lng, b.lat, b.lng)
        val secs = (m / (kmh.coerceAtLeast(15) / 3.6)).toInt().coerceAtLeast(1)
        for (s in 0 until secs) {
            val f = s.toDouble() / secs
            val bend = kotlin.math.sin(f * Math.PI) * 0.0012 * (if (i % 2 == 0) 1 else -1)
            val wobble = (kmh * (0.85 + 0.3 * kotlin.math.sin(s / 9.0))) / 3.6
            rec.add(t, a.lat + (b.lat - a.lat) * f + bend, a.lng + (b.lng - a.lng) * f - bend, wobble, 8f)
            t += 1000
        }
    }
    return DriveRoute(id, t, rec.finish())
}

/** Real AES-GCM in the JVM, standing in for the Keystore sealer. */
class JceSealer : dev.phonerecall.brain.Sealer {
    private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun sealBytes(plain: ByteArray): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding"); c.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }
    override fun openBytes(sealed: ByteArray): ByteArray? = runCatching {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12))
        c.doFinal(sealed, 12, sealed.size - 12)
    }.getOrNull()
    override fun seal(plain: String) = java.util.Base64.getEncoder().encodeToString(sealBytes(plain.toByteArray()))
    override fun open(sealed: String) = openBytes(java.util.Base64.getDecoder().decode(sealed))?.let { String(it) }
}
