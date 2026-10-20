package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardEngine
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BirthdayBookTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, ny).toInstant().toEpochMilli()
    private val oct20 = LocalDate.of(2026, 10, 20)

    /** Sam in his calendar (Google Calendar's birthday event), in Contacts (+91), and in today's WhatsApp wishes. */
    private val sam = listOf(
        BirthdaySeen("Sam", oct20, BirthdayFrom.CALENDAR),
        BirthdaySeen("Sam Garu", oct20, BirthdayFrom.CONTACTS, india = true),
        // Wished in the family group at 3 PM his time, already Oct 20 in India but Oct 19 here: the same birthday.
        BirthdaySeen("sam", LocalDate.of(2026, 10, 19), BirthdayFrom.WHATSAPP, thread = "wa:family"),
    )

    @Test fun threeSourcesAreOnePersonOneDay() {
        val b = BirthdayBook.merge(sam, ny).single()
        assertEquals("sam", b.key)
        assertEquals("Sam Garu", b.name)                                  // Contacts' name first
        assertEquals(oct20, b.date)
        assertTrue(b.india)
        assertEquals(setOf(BirthdayFrom.CALENDAR, BirthdayFrom.CONTACTS, BirthdayFrom.WHATSAPP), b.from)
        assertEquals(setOf("wa:family"), b.threads)
        // Someone else the same day stays separate.
        assertEquals(2, BirthdayBook.merge(sam + BirthdaySeen("Alex", oct20, BirthdayFrom.CALENDAR), ny).size)
    }

    @Test fun anIndiaDayRunsMidnightToMidnightInIndia() {
        val b = BirthdayBook.merge(sam, ny).single()
        assertEquals(at(2026, 10, 19, 14, 30), b.starts)                  // midnight IST = 2:30 PM EDT the day before
        assertEquals(at(2026, 10, 20, 14, 30), b.ends)
        assertEquals("Ends in India at 2:30 PM your time", BirthdayBook.endsText(b, ny))
        assertEquals("Sam Garu's birthday (India, ends 2:30 PM)", BirthdayBook.briefLine(b, ny, at(2026, 10, 20, 7)))
        assertEquals("Sam Garu's birthday (India, starts 2:30 PM)", BirthdayBook.briefLine(b, ny, at(2026, 10, 19, 7)))
    }

    @Test fun theIndiaDeadlineFollowsDaylightSaving() {
        // Daylight saving ends in New York on Nov 1, 2026; India has none.
        val before = BirthdayBook.merge(listOf(BirthdaySeen("Amma", LocalDate.of(2026, 10, 31), BirthdayFrom.CONTACTS, india = true)), ny).single()
        val after = BirthdayBook.merge(listOf(BirthdaySeen("Amma", LocalDate.of(2026, 11, 2), BirthdayFrom.CONTACTS, india = true)), ny).single()
        assertEquals("Ends in India at 2:30 PM your time", BirthdayBook.endsText(before, ny))
        assertEquals("Ends in India at 1:30 PM your time", BirthdayBook.endsText(after, ny))
        assertEquals(at(2026, 11, 1, 13, 30), after.starts)               // her day starts 1:30 PM EST the day before
    }

    @Test fun aUsDayIsHisOwnDay() {
        val b = BirthdayBook.merge(listOf(BirthdaySeen("Taylor", oct20, BirthdayFrom.CALENDAR)), ny).single()
        assertEquals(at(2026, 10, 20, 0), b.starts)
        assertEquals(at(2026, 10, 21, 0), b.ends)
        assertEquals("Ends at midnight tonight", BirthdayBook.endsText(b, ny))
        assertEquals("Taylor's birthday", BirthdayBook.briefLine(b, ny, at(2026, 10, 20, 7)))
    }

    @Test fun onePushAtTheStartOfHerDayOrNineForUsPeople() {
        val india = BirthdayBook.merge(sam, ny).single()
        assertEquals(india.starts, BirthdayBook.pushAt(india, ny, at(2026, 10, 19, 9)))
        val us = BirthdayBook.merge(listOf(BirthdaySeen("Taylor", oct20, BirthdayFrom.CALENDAR)), ny).single()
        assertEquals(at(2026, 10, 20, 9), BirthdayBook.pushAt(us, ny, at(2026, 10, 20, 1)))
    }

    @Test fun quietHoursMoveThePushToSevenOrDropIt() {
        // An India birthday seen at 11:30 PM (her day runs until 2:30 PM tomorrow): 7 AM.
        val india = BirthdayBook.merge(sam, ny).single()
        assertEquals(at(2026, 10, 20, 7), BirthdayBook.pushAt(india, ny, at(2026, 10, 19, 23, 30)))
        // A US birthday first seen at 11:30 PM: her day is over by 7 AM, so the card only.
        val us = BirthdayBook.merge(listOf(BirthdaySeen("Taylor", oct20, BirthdayFrom.WHATSAPP)), ny).single()
        assertNull(BirthdayBook.pushAt(us, ny, at(2026, 10, 20, 23, 30)))
        // Seen at 2 AM: 9 AM, the usual time.
        assertEquals(at(2026, 10, 20, 9), BirthdayBook.pushAt(us, ny, at(2026, 10, 20, 2)))
    }

    @Test fun threeSourcesMakeOnePushAndNeverASecond() {
        val all = BirthdayBook.merge(sam, ny)
        val now = at(2026, 10, 19, 14, 31)
        val (post, next) = BirthdayBook.due(all, ny, now, done = emptySet(), pushed = emptySet())
        assertEquals(listOf("sam@2026-10-20"), post.map { it.id })
        assertNull(next)
        // Pushed once: nothing more, even if he never acted.
        assertTrue(BirthdayBook.due(all, ny, now + 3_600_000L, emptySet(), post.map { it.id }.toSet()).first.isEmpty())
        // Before her day: nothing yet, and the alarm is set for its start.
        assertEquals(all.single().starts, BirthdayBook.due(all, ny, at(2026, 10, 19, 9), emptySet(), emptySet()).second)
    }

    @Test fun aMessageOnHerThreadOrToHerMarksItDone() {
        val all = BirthdayBook.merge(sam, ny)
        val now = at(2026, 10, 19, 16)
        val board = BirthdayBook.board(all, now, emptySet())
        assertEquals(1, board.active.size)
        assertEquals(listOf("sam@2026-10-20"), BirthdayBook.wishedBy(board.active, "wa:family", null).map { it.id })
        assertEquals(listOf("sam@2026-10-20"), BirthdayBook.wishedBy(board.active, "wa:other", "Sam Garu").map { it.id })
        assertTrue(BirthdayBook.wishedBy(board.active, "wa:other", "Alex").isEmpty())
        // Done: the card goes, the push never comes, but the day stays covered so the calendar card doesn't come back.
        val done = setOf("sam@2026-10-20")
        val after = BirthdayBook.board(all, now, done)
        assertTrue(after.active.isEmpty())
        assertTrue("sam@2026-10-20" in after.covered)
        assertTrue(BirthdayBook.due(all, ny, at(2026, 10, 19, 9), done, emptySet()).let { it.first.isEmpty() && it.second == null })
        assertTrue(BirthdayBook.forBrief(all, ny, now, done).isEmpty())
    }

    @Test fun threeSourcesAreOneCardOnOlympus() {
        val now = ZonedDateTime.of(2026, 10, 20, 9, 0, 0, 0, ny)
        val day = oct20.atStartOfDay(ny).toInstant().toEpochMilli()
        fun ev(id: Long, cal: Long, title: String) = CalItem(id, cal, title, day, day + 86_400_000L, true, EventCategory.BIRTHDAY, "Birthdays", null, null, true)
        // The calendar's event and the Contacts item the pipeline adds (calendar id -1), plus WhatsApp: one birthday.
        val events = listOf(ev(1, 5, "Sam's birthday"), ev(2, -1, "Sam's birthday"))
        val all = BirthdayBook.merge(sam, ny)
        val inputs = BoardInputs(
            now, CalendarSnapshot(true, events, true), emptyMap(), false, HomePrefs(), Learned(), TaskSnapshot(loaded = true), ClosetSnapshot(loaded = true), emptyList(), false,
            birthdays = BirthdayBook.board(all, now.toInstant().toEpochMilli(), emptySet()),
        )
        val cards = CardEngine.build(inputs).cards.filter { it.content.title.contains("birthday", ignoreCase = true) }
        assertEquals(listOf("Sam Garu's birthday today"), cards.map { it.content.title })
        val card = cards.single()
        assertEquals(listOf("Video call", "Message", "Already wished"), card.content.actions.map { it.label })
        assertEquals(ActionRole.BIRTHDAY_DONE, card.content.actions.last().role)
        assertEquals("Ends" to "In India at 2:30 PM your time", card.content.facts.first().let { it.label to it.value })
        // After "Already wished" there is no birthday card at all.
        val wished = inputs.copy(birthdays = BirthdayBook.board(all, now.toInstant().toEpochMilli(), setOf("sam@2026-10-20")))
        assertTrue(CardEngine.build(wished).cards.none { it.content.title.contains("birthday", ignoreCase = true) })
    }

    @Test fun namesComeOutOfBirthdayTitles() {
        assertEquals("Sam", BirthdayBook.nameOf("Sam's birthday"))
        assertEquals("Alex", BirthdayBook.nameOf("Birthday of Alex"))
        assertEquals(BirthdayBook.keyOf("Sam Garu"), BirthdayBook.keyOf(BirthdayBook.nameOf("sam’s birthday 🎂")))
    }
}
