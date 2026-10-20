package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.TimeZone

/** Commute averages, the pre-departure window, leave-by times and the weekly insight. */
class CommuteTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val start = LocalDate.of(2026, 9, 7) // a Monday

    @Test fun usualDepartureAndAverages() {
        // Tuesdays are slow (28 min); Monday and Wednesday 20.
        val d = Fx.officeWeeks(5, start, driveMin = { if (it.dayOfWeek == DayOfWeek.TUESDAY) 28 else 20 })
        val now = Fx.at(start.plusWeeks(5), 6)
        val s = Commute.stats(d, now, Fx.zone)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), s.departures.keys)
        assertEquals(8 * 60 + 20, s.departures[DayOfWeek.MONDAY])
        assertEquals("You leave for the office ~8:20 on Mon–Wed, 23 min usual", Commute.usual(s))
        assertEquals("Commute to office: avg 23 min, Tue slowest (28)", Commute.summary(s))
    }

    @Test fun thePreDepartureCheckRunsOnceInItsWindow() {
        val d = Fx.officeWeeks(3, start)
        val monday = start.plusWeeks(3)
        val s = Commute.stats(d, Fx.at(monday, 6), Fx.zone)
        fun at(h: Int, m: Int) = java.time.ZonedDateTime.of(monday.atTime(h, m), Fx.zone)
        assertFalse(Commute.departureDue(s, at(7, 45), emptyMap(), atHome = true))
        assertTrue(Commute.departureDue(s, at(7, 50), emptyMap(), atHome = true))
        assertTrue(Commute.departureDue(s, at(8, 10), emptyMap(), atHome = null))
        assertFalse(Commute.departureDue(s, at(8, 11), emptyMap(), atHome = true))
        assertFalse("already checked today", Commute.departureDue(s, at(8, 0), mapOf(Commute.departureKey(monday) to 1L), atHome = true))
        assertFalse("he already left", Commute.departureDue(s, at(8, 0), emptyMap(), atHome = false))
        assertFalse("out of office", Commute.departureDue(s, at(8, 0), emptyMap(), atHome = true, workday = false))
        // Thursday is not an office day.
        val thu = java.time.ZonedDateTime.of(monday.plusDays(3).atTime(7, 55), Fx.zone)
        assertFalse(Commute.departureDue(s, thu, emptyMap(), atHome = true))
        assertEquals(at(7, 50), Commute.nextDeparture(s, at(6, 0)))
    }

    @Test fun leaveByForAnEvent() {
        val start = Fx.at(LocalDate.of(2026, 10, 21), 18)
        assertEquals(start - 24 * Fx.MIN - 5 * Fx.MIN, Commute.leaveBy(start, 24 * 60))
        assertTrue(Commute.eventDue(start, start - 2 * Fx.H, "e", emptyMap()))
        assertFalse("too early", Commute.eventDue(start, start - 3 * Fx.H, "e", emptyMap()))
        assertFalse("already started", Commute.eventDue(start, start + Fx.MIN, "e", emptyMap()))
        assertFalse("checked once", Commute.eventDue(start, start - Fx.H, "e", mapOf("e" to 1L)))
    }

    @Test fun weeklyInsightCompareEarlyAndLateTuesdays() {
        var n = 0
        val d = Fx.officeWeeks(8, start,
            departMin = { if (it.dayOfWeek == DayOfWeek.TUESDAY && n++ % 2 == 0) 8 * 60 else if (it.dayOfWeek == DayOfWeek.TUESDAY) 8 * 60 + 15 else 8 * 60 + 20 },
            driveMin = { 20 })
        // Recompute drive minutes from the departures: early Tuesdays 20, late ones 30.
        val tuesdays = d.trips.filter { java.time.Instant.ofEpochMilli(it.start).atZone(Fx.zone).dayOfWeek == DayOfWeek.TUESDAY && it.from == d.home()?.id }
        val slow = d.copy(trips = d.trips.map { t -> if (t in tuesdays && java.time.Instant.ofEpochMilli(t.start).atZone(Fx.zone).minute == 15) t.copy(end = t.start + 30 * Fx.MIN) else t })
        assertEquals("Leaving 15 min earlier on Tuesdays saves 10 min.", Commute.insight(slow, Fx.at(start.plusWeeks(8), 6), Fx.zone))
        assertNull("no difference, no insight", Commute.insight(d, Fx.at(start.plusWeeks(8), 6), Fx.zone))
    }

    @Test fun cardWordsFromACheck() {
        val routes = listOf(Route(1820, 840, 13140, listOf("Crash, +13 min")), Route(1500, 45, 14980, emptyList()))
        val arrive = Fx.at(LocalDate.of(2026, 10, 20), 8, 42)
        val rc = RoadWords.check("commute:2026-10-20", "commute", "Office", arrive, routes, listOf("Crash on I-190 S near exit 7"), arrive - 40 * Fx.MIN, Fx.WORK_LAT, Fx.WORK_LNG, usualMinutes = 22)
        assertEquals("Crash on I-190 S near exit 7, +8 min", rc.title)
        assertEquals(30, rc.minutes)
        assertEquals(-5, rc.alternative)
        assertEquals("Take the other route (5 min faster) or leave by 8:11.", RoadWords.advice(rc, Fx.zone))
        val card = OdysseyCards.commute(Fx.on().copy(checks = listOf(rc)), arrive - 35 * Fx.MIN, Fx.zone)!!
        assertEquals(listOf("Open in Maps", "Got it"), card.actions.map { it.label })
        assertEquals("${Fx.WORK_LAT},${Fx.WORK_LNG}", card.actions.first().payload)
        // While driving, only alerts about this route.
        assertFalse(RoadWords.whileDriving(driving = true, routeRelevant = false))
        assertTrue(RoadWords.whileDriving(driving = true, routeRelevant = true))
    }

    @Test fun leaveByEntriesInChronos() {
        val arrive = Fx.at(LocalDate.of(2026, 10, 21), 18)
        val rc = RoadWords.check("event:1", "event", "Dentist", arrive, listOf(Route(24 * 60, 0, 9000, emptyList())), emptyList(), arrive - 2 * Fx.H, 0.0, 0.0, null)
        val items = OdysseyCalendar.items(Fx.on().copy(checks = listOf(rc)), arrive - Fx.DAY, arrive + Fx.DAY)
        val e = items.single()
        assertEquals("Leave for Dentist", e.title)
        assertEquals(arrive - 29 * Fx.MIN, e.begin)
        assertEquals(OdysseyCalendar.ID, e.calendarId)
    }
}
