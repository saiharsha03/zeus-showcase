package dev.phonerecall.mind

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Five-field cron (minute hour day-of-month month day-of-week), the form Managed Agents deployments and Iris's
 * crons.json use. Numbers, `*`, `*\/n`, ranges `a-b` and lists only; day-of-week 0-7 with 0 and 7 both Sunday.
 */
object Cron {
    private val RANGES = listOf(0..59, 0..23, 1..31, 1..12, 0..7)
    private val NAMES = listOf("minute", "hour", "day of month", "month", "day of week")

    /** Why [expr] isn't a cron we take, or null when it is. */
    fun problem(expr: String): String? {
        val f = expr.trim().split(Regex("\\s+"))
        if (f.size != 5) return "A schedule needs five fields (minute hour day month weekday), like 0 6 * * *"
        f.forEachIndexed { i, s -> if (values(s, RANGES[i]) == null) return "The ${NAMES[i]} field \"$s\" isn't valid (${RANGES[i].first}-${RANGES[i].last})" }
        return null
    }

    fun valid(expr: String) = problem(expr) == null

    /** The values one field allows, or null when it doesn't parse. */
    private fun values(s: String, r: IntRange): Set<Int>? {
        val out = HashSet<Int>()
        for (part in s.split(',')) {
            val (base, step) = part.split('/').let { if (it.size == 2) it[0] to (it[1].toIntOrNull() ?: return null) else if (it.size == 1) it[0] to 1 else return null }
            if (step < 1) return null
            val span = when {
                base == "*" -> r
                '-' in base -> base.split('-').let { p -> if (p.size != 2) return null; (p[0].toIntOrNull() ?: return null)..(p[1].toIntOrNull() ?: return null) }
                else -> (base.toIntOrNull() ?: return null).let { if (step > 1) it..r.last else it..it }
            }
            if (span.first !in r || span.last !in r || span.first > span.last) return null
            for (v in span step step) out += v
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The next times it fires after [from], in [zone] (minute by minute, up to [days] ahead). */
    fun next(expr: String, zone: ZoneId, from: Instant, count: Int = 1, days: Int = 32): List<ZonedDateTime> {
        val f = expr.trim().split(Regex("\\s+"))
        if (problem(expr) != null) return emptyList()
        val sets = f.mapIndexed { i, s -> values(s, RANGES[i])!! }
        val dow = sets[4].map { it % 7 }.toSet()
        val domAny = f[2] == "*"; val dowAny = f[4] == "*"
        val out = ArrayList<ZonedDateTime>()
        var t = from.atZone(zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val end = t.plusDays(days.toLong())
        while (t.isBefore(end) && out.size < count) {
            if (t.monthValue !in sets[3]) { t = t.plusDays(1).withHour(0).withMinute(0); continue }
            val domOk = t.dayOfMonth in sets[2]; val dowOk = (t.dayOfWeek.value % 7) in dow
            // Classic cron: when both day fields are restricted, either one matching is enough.
            val dayOk = if (domAny || dowAny) domOk && dowOk else domOk || dowOk
            if (!dayOk) { t = t.plusDays(1).withHour(0).withMinute(0); continue }
            if (t.hour !in sets[1]) { t = t.plusHours(1).withMinute(0); continue }
            if (t.minute in sets[0]) out += t
            t = t.plusMinutes(1)
        }
        return out
    }

    /** The picker's shapes. [of] reads a cron back into one; anything else is [Custom]. */
    sealed class Preset {
        abstract fun cron(): String
        data class Hourly(val minute: Int) : Preset() { override fun cron() = "$minute * * * *" }
        data class EveryHours(val hours: Int, val minute: Int) : Preset() { override fun cron() = "$minute */$hours * * *" }
        data class Daily(val hour: Int, val minute: Int) : Preset() { override fun cron() = "$minute $hour * * *" }
        data class Weekdays(val hour: Int, val minute: Int) : Preset() { override fun cron() = "$minute $hour * * 1-5" }
        data class Weekly(val day: DayOfWeek, val hour: Int, val minute: Int) : Preset() { override fun cron() = "$minute $hour * * ${day.value % 7}" }
        data class Custom(val expr: String) : Preset() { override fun cron() = expr.trim() }
    }

    fun of(expr: String): Preset {
        val f = expr.trim().split(Regex("\\s+"))
        if (f.size != 5 || problem(expr) != null) return Preset.Custom(expr)
        val (mi, h, dom, mon, dw) = f
        val m = mi.toIntOrNull()
        val hr = h.toIntOrNull()
        if (m == null || dom != "*" || mon != "*") return Preset.Custom(expr)
        return when {
            h == "*" && dw == "*" -> Preset.Hourly(m)
            h.startsWith("*/") && dw == "*" -> h.removePrefix("*/").toIntOrNull()?.takeIf { it in 2..12 }?.let { Preset.EveryHours(it, m) } ?: Preset.Custom(expr)
            hr == null -> Preset.Custom(expr)
            dw == "*" -> Preset.Daily(hr, m)
            dw == "1-5" -> Preset.Weekdays(hr, m)
            dw.toIntOrNull() != null -> Preset.Weekly(DayOfWeek.of(dw.toInt().let { if (it % 7 == 0) 7 else it }), hr, m)
            else -> Preset.Custom(expr)
        }
    }

    private fun clock(h: Int, m: Int) = "${if (h % 12 == 0) 12 else h % 12}:${"%02d".format(m)} ${if (h < 12) "AM" else "PM"}"

    /** "Daily at 6:00 AM", "Weekdays at 8:30 AM", "Sundays at 9:00 AM", "Every 3 hours at :00", else the cron itself. */
    fun words(expr: String): String = when (val p = of(expr)) {
        is Preset.Hourly -> "Every hour at :${"%02d".format(p.minute)}"
        is Preset.EveryHours -> "Every ${p.hours} hours at :${"%02d".format(p.minute)}"
        is Preset.Daily -> "Daily at ${clock(p.hour, p.minute)}"
        is Preset.Weekdays -> "Weekdays at ${clock(p.hour, p.minute)}"
        is Preset.Weekly -> "${p.day.getDisplayName(TextStyle.FULL, Locale.US)}s at ${clock(p.hour, p.minute)}"
        is Preset.Custom -> "Cron ${p.expr.trim()}"
    }

    /** A fixed hour inside 1-3 AM: skipped or doubled on DST nights. */
    fun inDstWindow(expr: String): Boolean = expr.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()?.let { it in 1..2 } ?: false
}
