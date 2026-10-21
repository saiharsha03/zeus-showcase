package dev.phonerecall.odyssey

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** US units for everything he reads. Storage stays metric (metres, km/h); this is the one place that converts. */
object Units {
    const val M_PER_MI = 1609.344
    const val KMH_PER_MPH = 1.609344

    fun miles(meters: Int): Double = meters / M_PER_MI
    fun mph(kmh: Int): Int = (kmh / KMH_PER_MPH).roundToInt()
    fun mphOf(kmh: Double): Double = kmh / KMH_PER_MPH

    /** "8.8 mi", "0.3 mi", "142 mi" (whole miles from 100 up). */
    fun mi(meters: Int): String = miles(meters).let { if (it >= 100) "${it.roundToInt()} mi" else "%.1f mi".format(Locale.US, it) }
    fun mphText(kmh: Int): String = "${mph(kmh)} mph"
}

/** What a drive was for, from Odyssey's places. He can correct it with one tap (DriveReport.tag). */
enum class TripTag(val label: String) { COMMUTE("Commute"), OFFICE_DAY("Office day"), ERRAND("Errand"), TRIP("Trip"), DRIVE("Drive") }

/** Stops (20 s or more under 5 km/h, after moving and before the end), idle time, and time and distance under 12.5 mph. */
data class DriveMotion(val stops: Int, val idleMinutes: Double, val slowMeters: Int, val slowMinutes: Double)

/** One line in the report's Insights card. */
data class Insight(val label: String, val text: String)

/** The report's insights, each from data Zeus already has on the phone. Pure; no network. */
object DriveInsights {
    private const val DAY = 86_400_000L
    const val SLOW_MPH = 12.5
    private val ERRAND_KINDS = setOf(Kind.GROCERY, Kind.CAR_WASH, Kind.TEMPLE, Kind.GYM, Kind.FUEL, Kind.RESTAURANT, Kind.CAFE, Kind.SHOP)

    /** The trip (visit to visit) this drive was, by time: the one whose start is nearest the drive's within 15 min. */
    fun tripOf(d: OdysseyData, r: DriveReport): Trip? =
        d.trips.filter { it.start <= r.end + 15 * 60_000L && it.end >= r.start - 15 * 60_000L }.minByOrNull { abs(it.start - r.start) }

    fun tag(d: OdysseyData, r: DriveReport, zone: ZoneId = ZoneId.systemDefault()): TripTag {
        r.tag?.let { t -> TripTag.entries.firstOrNull { it.name == t }?.let { return it } }
        return autoTag(d, r, zone)
    }

    /** Home to the office or back is a commute; any other drive on a day at the office is an office day; 50 mi or more is a trip. */
    fun autoTag(d: OdysseyData, r: DriveReport, zone: ZoneId = ZoneId.systemDefault()): TripTag {
        val t = tripOf(d, r)
        // No trip yet (the stay it ended at is still open): the visit it left and the stay it reached.
        val left = d.visits.lastOrNull { it.end in (r.start - 15 * 60_000L)..(r.start + 5 * 60_000L) }?.placeId
        val reached = d.stay?.takeIf { it.start in (r.end - 5 * 60_000L)..(r.end + 15 * 60_000L) }?.let { Visits.placeFor(d.places, it.lat, it.lng, it.accuracy)?.id }
        val from = d.place(t?.from ?: left)?.kind; val to = d.place(t?.to ?: reached)?.kind
        if ((from == Kind.HOME && to == Kind.WORK) || (from == Kind.WORK && to == Kind.HOME)) return TripTag.COMMUTE
        // Arriving at the office in the morning, or leaving it in the afternoon, by the trip or the office geofence.
        val endHour = Instant.ofEpochMilli(r.end).atZone(zone).hour; val startHour = Instant.ofEpochMilli(r.start).atZone(zone).hour
        val work = d.work()?.id
        val arrived = work != null && d.arrivals.any { it.placeId == work && it.at in (r.end - 10 * 60_000L)..(r.end + 20 * 60_000L) }
        if ((to == Kind.WORK || arrived) && endHour < 12) return TripTag.COMMUTE
        if (from == Kind.WORK && startHour >= 14) return TripTag.COMMUTE
        if (Units.miles(r.meters) >= 50) return TripTag.TRIP
        if (from == Kind.WORK || to == Kind.WORK) return TripTag.OFFICE_DAY
        val day = Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate()
        if (work != null && d.visits.any { it.placeId == work && Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == day }) return TripTag.OFFICE_DAY
        if (to in ERRAND_KINDS || (to == Kind.HOME && from in ERRAND_KINDS)) return TripTag.ERRAND
        return TripTag.DRIVE
    }

    fun motion(points: List<TrackPoint>): DriveMotion {
        var stops = 0; var idle = 0L; var slowM = 0.0; var slowMs = 0L
        var moved = false; var stopAt = -1L
        for ((a, b) in points.zipWithNext()) {
            val dt = b.t - a.t
            if (Units.mphOf((a.kmh + b.kmh) / 2.0) < SLOW_MPH) { slowMs += dt; slowM += Geo.meters(a.lat, a.lng, b.lat, b.lng) }
            val still = a.kmh < 5 && b.kmh < 5
            if (still) { if (stopAt < 0) stopAt = a.t }
            else {
                if (stopAt >= 0 && moved && a.t - stopAt >= 20_000L) { stops++; idle += a.t - stopAt }
                stopAt = -1L; moved = true
            }
        }
        return DriveMotion(stops, idle / 60_000.0, slowM.roundToInt(), slowMs / 60_000.0)
    }

    /** "4 min slower than usual (22 min)": against the median of the same trip at about the same time (±90 min), last 8 weeks, 3 or more. */
    fun vsUsual(d: OdysseyData, r: DriveReport, zone: ZoneId = ZoneId.systemDefault()): String? {
        val t = tripOf(d, r) ?: return null
        if (t.from == null || t.to == null) return null
        fun mod(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toSecondOfDay() / 60
        val same = d.trips.filter { it.id != t.id && it.from == t.from && it.to == t.to && it.start < t.start && t.start - it.start < 56 * DAY && it.minutes in 3..150 && abs(mod(it.start) - mod(t.start)) <= 90 }
        if (same.size < 3) return null
        val usual = same.map { it.minutes }.sorted().let { it[it.size / 2] }
        val diff = (t.minutes - usual).toInt()
        return when {
            abs(diff) < 2 -> "About usual ($usual min)"
            diff > 0 -> "$diff min slower than usual ($usual min)"
            else -> "${-diff} min faster than usual ($usual min)"
        }
    }

    /** TomTom's check before he left (2 hours or less before the start): the delay and the first incident. */
    fun traffic(d: OdysseyData, r: DriveReport): String? {
        val c = d.checks.filter { it.at in (r.start - 2 * 3_600_000L)..r.start }.maxByOrNull { it.at } ?: return null
        return (if (c.delayMinutes > 0) "TomTom saw +${c.delayMinutes} min of traffic before you left" else "TomTom saw clear roads before you left") +
            (c.incidents.firstOrNull()?.let { ", $it" } ?: "")
    }

    /** Commute drives only: his own history on that weekday says whether leaving earlier pays. */
    fun leaveEarlier(d: OdysseyData, r: DriveReport, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (tag(d, r, zone) != TripTag.COMMUTE) return null
        val t = tripOf(d, r) ?: return null
        if (d.place(t.from)?.kind != Kind.HOME) return null
        return Commute.insight(d, now, zone, Instant.ofEpochMilli(r.start).atZone(zone).dayOfWeek)
    }

    /** "8.8 mi this drive · month 8/36, 7,450 mi, pace 11,200/yr vs 10,000". Null until the lease is known. */
    fun lease(d: OdysseyData, r: DriveReport, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val p = LeasePace.pace(d.car.lease, d.car.readings, d.drives, Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), zone) ?: return null
        val (title, body) = LeasePace.words(p)
        return "${Units.mi(r.meters)} this drive · ${title.replace(" · ", ", ")}, ${body.replaceFirstChar { it.lowercase() }}"
    }

    /** Gallons from his own mpg (fill-ups with odometers) and cost at the last price he paid. */
    fun fuel(d: OdysseyData, r: DriveReport): String? {
        val fills = d.car.fillups
        val miles = Units.miles(r.meters)
        val mpg = LeasePace.mpg(fills)?.let { (latest, avg) -> avg ?: latest }
        val price = fills.filter { it.state == "logged" }.sortedBy { it.at }.lastOrNull { it.pricePerGal != null || (it.cents != null && (it.gallons ?: 0.0) > 0) }
            ?.let { it.pricePerGal ?: (it.cents!! / 100.0 / it.gallons!!) }
        if (mpg != null) {
            val gal = miles / mpg
            return "~${"%.2f".format(Locale.US, gal)} gal at ${mpg.roundToInt()} mpg" + (price?.let { " · ~$${"%.2f".format(Locale.US, gal * it)} at $${"%.2f".format(Locale.US, it)}/gal" } ?: "")
        }
        return LeasePace.centsPerMile(fills)?.let { "~$${"%.2f".format(Locale.US, miles * it / 100)} of fuel at your ${"%.1f".format(Locale.US, it)}¢ a mile" }
    }

    /** Snow at the start (Aeolia), or a snow band on his route that morning: words for the road. */
    fun road(d: OdysseyData, r: DriveReport): String? {
        val band = d.notes.firstOrNull { it.kind == "snow" && it.at in (r.start - 12 * 3_600_000L)..r.start }
        val snowing = r.weatherCode >= 0 && dev.phonerecall.aeolia.Sky.kind(r.weatherCode) == dev.phonerecall.aeolia.Sky.Kind.SNOW
        return when {
            band != null -> "Snow day: ${band.title}. Braking and turns read harsher on snow; leave more room."
            snowing -> "Snow at the start. Braking and turns read harsher on snow; leave more room."
            else -> null
        }
    }

    /** This drive's score against the mean of his 10 before it (3 or more), and the 11 scores in order for a sparkline. */
    fun scoreTrend(drives: List<DriveReport>, r: DriveReport): Pair<String, List<Int>>? {
        val before = drives.filter { it.start < r.start }.sortedByDescending { it.start }.take(10)
        if (before.size < 3) return null
        val mean = before.map { it.score }.average().roundToInt()
        val diff = r.score - mean
        val words = when {
            diff > 0 -> "$diff above your last ${before.size} ($mean)"
            diff < 0 -> "${-diff} below your last ${before.size} ($mean)"
            else -> "Same as your last ${before.size} ($mean)"
        }
        return words to (before.reversed().map { it.score } + r.score)
    }

    /** Weekly average scores, oldest first, for the weekly card's sparkline. Weeks with no drive are left out. */
    fun weekScores(drives: List<DriveReport>, now: Long, weeks: Int = 8): List<Int> = (weeks - 1 downTo 0).mapNotNull { w ->
        drives.filter { now - it.end in (w * 7 * DAY) until ((w + 1) * 7 * DAY) }.takeIf { it.isNotEmpty() }
            ?.let { l -> (l.sumOf { it.score * it.minutes.coerceAtLeast(1) }.toDouble() / l.sumOf { it.minutes.coerceAtLeast(1) }).roundToInt() }
    }

    /** "Hands-free 21 min (Maps, Android Auto) · phone 1.4 min": hands-free apps against screen time and other apps while moving. */
    fun handsFree(r: DriveReport, name: (String) -> String = { it.substringAfterLast('.') }): String? {
        val p = r.phone
        val (free, apps) = p.apps.entries.partition { it.key in DriveAnalyzer.HANDS_FREE }
        val hf = p.handsFree.entries + free
        val hfMin = hf.sumOf { it.value }
        val phone = p.screenMinutesMoving + apps.sumOf { it.value }
        if (hfMin <= 0 && phone <= 0) return null
        val names = hf.sortedByDescending { it.value }.map { name(it.key) }.distinct().take(2)
        return (if (hfMin > 0) "Hands-free ${fmt1(hfMin)} min (${names.joinToString()})" else "No hands-free apps") +
            " · " + (if (phone > 0) "phone ${fmt1(phone)} min" else "no phone use")
    }

    /** Every insight that has data behind it, in reading order. */
    fun all(d: OdysseyData, r: DriveReport, route: DriveRoute?, now: Long, zone: ZoneId = ZoneId.systemDefault(), name: (String) -> String = { it.substringAfterLast('.') }): List<Insight> = buildList {
        vsUsual(d, r, zone)?.let { add(Insight("Vs usual", it)) }
        traffic(d, r)?.let { add(Insight("Traffic", it)) }
        leaveEarlier(d, r, now, zone)?.let { add(Insight("Leave by", it)) }
        Stops.after(d, r)?.let { add(Insight("Stopped at", it)) }
        route?.let { motion(it.points) }?.let { m ->
            add(Insight("Stops", if (m.stops == 0) "No stops" else "${m.stops} ${if (m.stops == 1) "stop" else "stops"}, ${fmt1(m.idleMinutes)} min idle"))
        }
        scoreTrend(d.drives, r)?.let { add(Insight("Score", it.first)) }
        handsFree(r, name)?.let { add(Insight("Phone", it)) }
        lease(d, r, now, zone)?.let { add(Insight("Lease", it)) }
        fuel(d, r)?.let { add(Insight("Fuel", it)) }
        r.weather?.let { add(Insight("Weather", it)) }
        road(d, r)?.let { add(Insight("Road", it)) }
        DrivingReport.hotspots(d.drives.filter { now - it.end < 28 * DAY }).filter { h -> r.events.any { it.kind == h.kind && Geo.meters(it.lat, it.lng, h.lat, h.lng) < 60 } }
            .firstOrNull()?.let { h -> add(Insight("Hotspot", "${h.kind.label} ${h.count}x at the same spot" + (h.street?.let { " on $it" } ?: ""))) }
    }

    private fun fmt1(v: Double) = "%.1f".format(Locale.US, v).removeSuffix(".0")
}
