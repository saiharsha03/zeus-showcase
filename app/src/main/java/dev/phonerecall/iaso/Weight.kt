package dev.phonerecall.iaso

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Weight trend math. Daily readings bounce by a kilo or so with water, so the line he sees is an
 * exponential moving average (a quarter of each new daily reading, seeded with the mean of the first three
 * so it doesn't start on one high or low day) and the weekly change
 * is the least-squares slope of the last 28 days of readings.
 */
object WeightTrend {
    private const val DAY = 86_400_000L
    private const val ALPHA = 0.25

    /**
     * Health Connect's readings plus his local ones, one per minute. A local reading that Health Connect
     * already holds (same minute, within 0.05 kg) is dropped; when HC is missing, the local list stands alone.
     */
    fun merge(hc: List<WeightPoint>, local: List<WeightPoint>): List<WeightPoint> {
        val out = hc.toMutableList()
        for (l in local) if (out.none { abs(it.at - l.at) < 60_000L && abs(it.kg - l.kg) < 0.05 }) out += l
        return out.sortedBy { it.at }
    }

    /** One reading per day (the earliest, as a morning weigh-in would be), oldest first. */
    fun daily(points: List<WeightPoint>, zone: java.time.ZoneId): List<WeightPoint> =
        points.groupBy { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toSortedMap().map { (_, ps) -> ps.minBy { it.at } }

    /** The smoothed line, one value per input point. */
    fun smooth(points: List<WeightPoint>): List<WeightPoint> {
        if (points.isEmpty()) return emptyList()
        var t = points.take(3).map { it.kg }.average()
        return points.map { p -> t += ALPHA * (p.kg - t); p.copy(kg = t) }
    }

    /** Kilograms per week over the last [days] days, or null with fewer than three readings spanning five days. */
    fun weeklySlope(points: List<WeightPoint>, now: Long, days: Int = 28): Double? {
        val ps = points.filter { it.at in (now - days * DAY)..now }
        if (ps.size < 3 || ps.last().at - ps.first().at < 5 * DAY) return null
        val xs = ps.map { (it.at - ps.first().at).toDouble() / DAY }
        val ys = ps.map { it.kg }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        val perDay = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
        return perDay * 7
    }

    /** "Down 0.3 kg a week", "Steady", or null without enough readings. */
    fun words(slopeKg: Double?): String? {
        slopeKg ?: return null
        val r = (abs(slopeKg) * 10).roundToLong() / 10.0
        return when {
            r < 0.1 -> "Steady"
            slopeKg < 0 -> "Down $r kg a week"
            else -> "Up $r kg a week"
        }
    }

    /** Kilograms, one decimal: "65.6 kg". */
    fun format(kg: Double): String = "${(kg * 10).roundToLong() / 10.0} kg"

    /** What he typed, in kilograms; null if it isn't a believable body weight. */
    fun parse(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in 25.0..300.0 }
}
