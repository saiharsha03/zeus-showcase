package dev.phonerecall.data

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** The kind of day. The whole app reads this one model: cards, wardrobe, brief, Now Bar, later the brain. */
enum class DayType(val label: String) {
    WORKDAY("Office day"), REMOTE("Remote day"), OOO("Out of office"), WEEKEND("Weekend"), HOLIDAY("Holiday"),
}

/** One day's availability. `reason` is a short label ("Thanksgiving"), never event details. */
@Immutable
data class Availability(val date: LocalDate, val type: DayType, val reason: String? = null)

/**
 * Phase 2 hook: the Managed Agent and the workers get availability (day types only), never event
 * titles, places or notes. The stub sends nothing.
 */
fun interface AvailabilityPublisher {
    fun publish(days: List<Availability>)
}

val NoPublisher = AvailabilityPublisher { }

/** Argo's side: whether he is away on a trip for that working day. Set by Argo; null before it starts. */
fun interface TravelDays {
    fun travelling(date: LocalDate, zone: ZoneId): Boolean
}

object AvailabilityModel {
    val OFFICE_DAYS = setOf(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)

    /** A day away on a trip is out of office ("Travelling"), so no agent plans office tasks or commutes on it. */
    @Volatile var travel: TravelDays? = null

    /**
     * Rules, first match wins: an OOO event covering the day (Outlook status or title); a US federal
     * holiday that passed relevance; an explicit "office" or "remote"/"WFH" entry; the weekend; then
     * the default office-day pattern ([OFFICE_DAYS]).
     */
    fun of(date: LocalDate, items: List<CalItem>, zone: ZoneId): Availability {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val today = items.filter { it.begin < end && it.end > start }
        // A timed OOO block counts only if it covers most of the working day.
        val ooo = today.firstOrNull { it.category == EventCategory.OOO && (it.allDay || it.end - maxOf(it.begin, start) >= 5 * 3_600_000L) }
        if (ooo != null) return Availability(date, DayType.OOO, "Out of office")
        if (travel?.travelling(date, zone) == true) return Availability(date, DayType.OOO, "Travelling")
        today.firstOrNull { it.category == EventCategory.HOLIDAY }?.let { return Availability(date, DayType.HOLIDAY, it.title) }
        val titles = today.map { it.title.lowercase() }
        if (titles.any { t -> listOf("wfh", "remote", "work from home").any { t.contains(it) } }) return Availability(date, DayType.REMOTE)
        if (titles.any { it.contains("office") }) return Availability(date, DayType.WORKDAY)
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) return Availability(date, DayType.WEEKEND)
        return Availability(date, if (date.dayOfWeek in OFFICE_DAYS) DayType.WORKDAY else DayType.REMOTE)
    }

    /** `overrides` are the days he set himself ([DayOverrides]); they win over the calendar. */
    fun range(from: LocalDate, days: Int, items: List<CalItem>, zone: ZoneId, overrides: Map<LocalDate, DayType> = emptyMap()): Map<LocalDate, Availability> =
        (0 until days).associate { from.plusDays(it.toLong()).let { d -> d to (overrides[d]?.let { t -> Availability(d, t) } ?: of(d, items, zone)) } }
}
