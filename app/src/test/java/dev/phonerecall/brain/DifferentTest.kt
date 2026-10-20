package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class DifferentTest {
    private val ny = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 19, 20, 0, 0, 0, ny)
    private val clocks = TwoClocks(ny, TwoClocks.INDIA)

    private fun alarm(tomorrow: Int?, usual: Int?) = object : AlarmSignal {
        override fun tomorrowMinutes() = tomorrow
        override fun usualMinutes() = usual
    }

    private fun item(title: String, begin: ZonedDateTime, allDay: Boolean = false, cat: EventCategory = EventCategory.entries.first { it != EventCategory.TRAVEL && it != EventCategory.OOO }) = CalItem(
        eventId = title.hashCode().toLong(), calendarId = 1, title = title, begin = begin.toInstant().toEpochMilli(), end = begin.toInstant().toEpochMilli() + 3_600_000,
        allDay = allDay, category = cat, calendarName = "x", place = null, notes = null, recurring = false,
    )

    private val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay(ny)

    @Test fun dasaraWithAnEarlyAlarmMentionsBoth() {
        val lines = Different.lines(Different.Inputs(now, clocks, alarm(5 * 60, 7 * 60), listOf(item("Dasara", tomorrow, allDay = true))))
        val text = Different.block(lines)!!
        assertTrue(text, text.contains("5:00 AM") && text.contains("7:00 AM"))
        assertTrue(text, text.contains("Dasara is tomorrow in India (it starts Mon 2:30 PM your time)") || text.contains("Dasara"))
        assertTrue(text.contains("your time"))
    }

    @Test fun nothingDiffersMeansNoDigest() {
        val lines = Different.lines(Different.Inputs(now, clocks, alarm(7 * 60, 7 * 60 + 15), listOf(item("Standup", tomorrow.plusHours(9)))))
        assertTrue(lines.isEmpty())
        assertNull(Different.block(lines))
        assertTrue(Different.lines(Different.Inputs(now, clocks)).isEmpty())
    }

    @Test fun busyDayTravelAndWeather() {
        val busy = (0 until 6).map { item("Meeting $it", tomorrow.plusHours(8L + it)) }
        val trip = item("Flight to Denver", tomorrow.plusHours(6), cat = EventCategory.TRAVEL)
        val lines = Different.lines(Different.Inputs(now, clocks, events = busy + trip, weatherNext = "Rain from 3 PM", weatherAlerts = listOf("Winter Storm Warning")))
        val text = lines.joinToString(" ")
        assertTrue(text, text.contains("Tomorrow has 6 events"))
        assertTrue(text, text.contains("Travel tomorrow: Flight to Denver"))
        assertTrue(text, text.contains("Rain from 3 PM") && text.contains("Winter Storm Warning"))
    }

    @Test fun onlyAwayFactsFromOdysseyAndNoSensitiveFields() {
        val lines = Different.lines(Different.Inputs(now, clocks, odyssey = listOf("Usual office commute 22 min", "You are away from your usual places this week", "Driving score this week 91")))
        assertEquals(listOf("You are away from your usual places this week"), lines)
        val all = Different.lines(Different.Inputs(now, clocks, alarm(5 * 60, 7 * 60), listOf(item("Dasara", tomorrow, allDay = true)), "Rain from 3 PM", odyssey = listOf("away from usual")))
            .joinToString(" ")
        assertFalse(all, listOf("$", "heart", "sleep", "steps").any { all.contains(it) })
    }
}
