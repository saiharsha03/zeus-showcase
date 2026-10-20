package dev.phonerecall.data

import java.time.LocalDate
import java.time.ZoneId

/** What Home, the cards and the Now Bar get from the calendar: curated events and the days' availability. */
data class CuratedCalendar(val snapshot: CalendarSnapshot, val availability: Map<LocalDate, Availability>)

/**
 * The one path from raw calendar data to what Athena shows. Used by the ViewModel and by the Now Bar
 * receiver (when the app is not running), so both see the same curated calendar.
 */
object CalendarPipeline {
    fun curate(
        raw: CalendarSnapshot,
        calendars: Map<Long, CalendarInfo>,
        config: RelevanceConfig,
        birthdays: List<ContactBirthday>,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        overrides: Map<LocalDate, DayType> = DayOverrides.state.value,
    ): CuratedCalendar {
        val fromCalendars = EventKinds.dedupe(raw.events.filter { Relevance.relevant(it.title, calendars[it.calendarId], config) }, calendars, zone)
        // Contact birthdays the Birthdays calendar already has are not added twice.
        val seen = fromCalendars.filter { it.category == EventCategory.BIRTHDAY }.map { it.title.lowercase() to it.begin }.toSet()
        val contact = BirthdaySource.asItems(birthdays, today, 9, zone).filter { (it.title.lowercase() to it.begin) !in seen }
        val relevant = fromCalendars + contact
        // Availability reads his own and work entries (OOO is never filtered away), plus holidays that passed relevance.
        val forAvailability = raw.events.filter { e ->
            val k = calendars[e.calendarId]?.kind
            e.category == EventCategory.OOO || k == CalKind.OWN || k == CalKind.WORK || e in fromCalendars
        }
        return CuratedCalendar(
            raw.copy(events = relevant.sortedBy { it.begin }),
            AvailabilityModel.range(today, 9, forAvailability, zone, overrides),
        )
    }
}
