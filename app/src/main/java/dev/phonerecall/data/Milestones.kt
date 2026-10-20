package dev.phonerecall.data

import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/** A personal milestone ("Car delivered"): its day, and each anniversary, in Chronos. */
data class Milestone(val date: LocalDate, val title: String, val source: String)

/** Anything that knows milestones (Odyssey's car today; the general milestones list joins here). */
fun interface MilestoneSource {
    fun milestones(): List<Milestone>
}

object Milestones {
    const val ID = -9L
    val INFO = CalendarInfo(ID, "Milestones", "milestones", CalKind.OWN, 0)
    private val sources = ConcurrentHashMap<String, MilestoneSource>()

    /** One source per key, so registering again replaces it. */
    fun register(key: String, s: MilestoneSource) { sources[key] = s }
    fun all(): List<Milestone> = sources.values.flatMap { runCatching { it.milestones() }.getOrDefault(emptyList()) }.distinctBy { it.date to it.title.lowercase() }

    /** For Eos: the milestones on [date], in Chronos's words ("Car delivered, 1 year"). */
    fun on(date: LocalDate, zone: ZoneId, ms: List<Milestone> = all()): List<String> =
        date.atStartOfDay(zone).toInstant().toEpochMilli().let { from -> items(ms, from, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone) }
            .filter { java.time.Instant.ofEpochMilli(it.begin).atZone(zone).toLocalDate() == date }.map { it.title }

    /** The milestone days (and anniversaries: "Car delivered, 1 year") inside [from, to), as all-day items. */
    fun items(ms: List<Milestone>, from: Long, to: Long, zone: ZoneId): List<CalItem> {
        val first = java.time.Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val last = java.time.Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        return ms.flatMap { m ->
            (first.year..last.year).mapNotNull { y ->
                val day = runCatching { m.date.withYear(y) }.getOrNull() ?: return@mapNotNull null
                if (day.isBefore(m.date)) return@mapNotNull null
                val begin = day.atStartOfDay(zone).toInstant().toEpochMilli()
                if (begin < from - 86_400_000L || begin >= to) return@mapNotNull null
                val years = y - m.date.year
                CalItem(
                    -(("${m.title}:$y").hashCode().toLong() and 0x7fffffffL) - 3_000_000_000L, ID, if (years == 0) m.title else "${m.title}, $years ${if (years == 1) "year" else "years"}",
                    begin, day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), true, EventCategory.PERSONAL, INFO.name, null, m.source, years > 0,
                )
            }
        }
    }
}
