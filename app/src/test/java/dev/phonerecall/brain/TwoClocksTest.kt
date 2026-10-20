package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoClocksTest {
    private val ny = ZoneId.of("America/New_York")
    private val ist = TwoClocks.INDIA
    private val clocks = TwoClocks(ny, ist)

    private fun ny(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), ny).toInstant()

    @Test fun contextLineInSummerAndWinter() {
        // EDT is UTC-4: 2:30 PM there is midnight in India.
        assertEquals("Now: Mon 2:30 PM in Buffalo (EDT) · Tue 12:00 AM in India (IST)", clocks.contextLine(ny("2026-10-19T14:30")))
        // EST is UTC-5: the same wall clock is 1:00 AM in India.
        assertEquals("Now: Mon 2:30 PM in Buffalo (EST) · Tue 1:00 AM in India (IST)", clocks.contextLine(ny("2026-12-14T14:30")))
    }

    @Test fun bothClocksAreSkippedWhenTheZonesAgree() {
        assertNull(TwoClocks(ny, ny).both(ny("2026-10-19T14:30")))
        assertEquals("Mon 2:30 PM EDT · Tue 12:00 AM IST", clocks.both(ny("2026-10-19T14:30")))
    }

    @Test fun lateNightForThemOffersTheNextGoodTime() {
        val r = clocks.reach(ny("2026-10-19T14:30"), ist)
        assertTrue(r.late)
        assertEquals("Tue 12:00 AM IST", r.local)
        assertEquals("8:00 AM there, Mon 10:30 PM for you", r.nextText)
        // Evening there (22:30) rolls to the next morning; mid-afternoon there is fine.
        assertEquals("8:00 AM there, Tue 10:30 PM for you", clocks.reach(ny("2026-10-20T13:00"), ist).nextText)
        assertFalse(clocks.reach(ny("2026-10-19T08:30"), ist).late)
        assertNull(clocks.reach(ny("2026-10-19T08:30"), ist).next)
    }

    @Test fun reachBoundaries() {
        // 22:00 IST is late, 21:59 is not, 07:00 is fine, 06:59 is late.
        assertTrue(clocks.reach(ZonedDateTime.of(2026, 10, 20, 22, 0, 0, 0, ist).toInstant(), ist).late)
        assertFalse(clocks.reach(ZonedDateTime.of(2026, 10, 20, 21, 59, 0, 0, ist).toInstant(), ist).late)
        assertFalse(clocks.reach(ZonedDateTime.of(2026, 10, 20, 7, 0, 0, 0, ist).toInstant(), ist).late)
        assertTrue(clocks.reach(ZonedDateTime.of(2026, 10, 20, 6, 59, 0, 0, ist).toInstant(), ist).late)
    }

    @Test fun anIstDayStartsInTheAfternoonHere() {
        assertEquals("starts Mon 2:30 PM your time", clocks.startsAt(LocalDate.of(2026, 10, 20), ist))
        // After daylight saving ends the same Indian midnight is an hour earlier here.
        assertEquals("starts Sun 1:30 PM your time", clocks.startsAt(LocalDate.of(2026, 12, 14), ist))
    }

    @Test fun indiaNumbersAndWhatsAppIds() {
        assertTrue(TwoClocks.isIndia("+91 98765 43210"))
        assertTrue(TwoClocks.isIndia("+91-9876543210"))
        assertTrue(TwoClocks.isIndia("919876543210@s.whatsapp.net"))
        assertFalse(TwoClocks.isIndia("+1 716 555 0142"))
        assertFalse(TwoClocks.isIndia("17165550142@s.whatsapp.net"))
        assertFalse(TwoClocks.isIndia("Alex"))
        assertFalse(TwoClocks.isIndia(null))
        assertEquals(ist, Person("k", "Alex", zone = "Asia/Kolkata").zoneId)
        assertNull(Person("k", "Alex").zoneId)
        assertNull(Person("k", "Alex", zone = "Not/AZone").zoneId)
    }

    private fun item(title: String, allDay: Boolean, begin: Long, zone: String? = null, place: String? = null) = CalItem(
        eventId = 1, calendarId = 1, title = title, begin = begin, end = begin + 3_600_000, allDay = allDay, category = EventCategory.entries.first(),
        calendarName = "x", place = place, notes = null, recurring = false, timeZone = zone,
    )

    @Test fun birthdaysFestivalsAndEventsOnIndiaTime() {
        val day = ZonedDateTime.of(2026, 10, 20, 0, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals("starts Mon 2:30 PM your time", clocks.dayStart(item("Alex's birthday", true, day, zone = "Asia/Kolkata")))
        assertEquals("starts Mon 2:30 PM your time", clocks.dayStart(item("Diwali", true, day)))
        assertNull(clocks.dayStart(item("Jordan's birthday", true, day)))
        val meeting = ny("2026-10-19T14:30").toEpochMilli()
        assertEquals("Mon 2:30 PM EDT · Tue 12:00 AM IST", clocks.bothFor(item("Family call", false, meeting, zone = "Asia/Kolkata")))
        assertEquals("Mon 2:30 PM EDT · Tue 12:00 AM IST", clocks.bothFor(item("Pooja 7 AM IST", false, meeting)))
        assertNull(clocks.bothFor(item("Standup", false, meeting)))
    }

    private val alex = FakeState(people = mapOf("Alex" to ist))
    private val now = ny("2026-10-19T09:00").toEpochMilli()

    @Test fun callProposalWarnsWhenItIsTheMiddleOfTheNightThere() {
        val c = Validate.step(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-19T14:30", "text" to "Call Alex", "with" to "Alex"), alex, now) as Check.Ok
        assertTrue(c.step.summary, c.step.summary.contains("Tue 12:00 AM IST for Alex, late there. Better 8:00 AM there, Mon 10:30 PM for you"))
        val facts = Validate.facts(c.step, alex.zone).toMap()
        assertEquals("Tue 12:00 AM IST", facts["For Alex"])
        assertEquals("Better 8:00 AM there, Mon 10:30 PM for you", facts["Late there"])
    }

    @Test fun callProposalAtAGoodHourOnlyShowsTheirTime() {
        val c = Validate.step(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-19T10:00", "text" to "Call Alex", "with" to "Alex"), alex, now) as Check.Ok
        assertFalse(c.step.summary.contains("late"))
        assertEquals("Mon 7:30 PM IST", Validate.facts(c.step, alex.zone).toMap()["For Alex"])
        assertFalse(Validate.facts(c.step, alex.zone).toMap().containsKey("Late there"))
    }

    @Test fun peopleOnHisOwnClockAddNothing() {
        val c = Validate.step(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-19T14:30", "text" to "Call Jordan", "with" to "Jordan"), alex, now) as Check.Ok
        assertEquals("Remind you Mon, Oct 19, 2:30 PM: Call Jordan", c.step.summary)
        assertEquals(2, Validate.facts(c.step, alex.zone).size)
    }

    @Test fun eventWithAnIndiaGuestShowsTheirClock() {
        val c = Validate.step(ProposalKind.ADD_CALENDAR_EVENT, mapOf("title" to "Catch up with Alex", "start" to "2026-10-19T14:30", "with" to "Alex", "calendar_id" to "3"), alex, now) as Check.Ok
        assertEquals("Tue 12:00 AM IST", Validate.facts(c.step, alex.zone).toMap()["For Alex"])
    }

    @Test fun secondClockIsSettable() {
        val before = TwoClocks.secondZone
        try {
            TwoClocks.secondZone = ZoneId.of("Europe/London")
            assertEquals("Now: Mon 2:30 PM in Buffalo (EDT) · Mon 7:30 PM in London (BST)", TwoClocks.now(ny).contextLine(ny("2026-10-19T14:30")))
        } finally { TwoClocks.secondZone = before }
    }

    @Test fun instantOverloadHelper() {
        assertEquals("Mon 2:30 PM EDT", clocks.inZone(Instant.parse("2026-10-19T18:30:00Z"), ny))
    }
}
