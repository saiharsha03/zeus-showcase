package dev.phonerecall.qol

import dev.phonerecall.moments.CallSlot
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** The India clock: late there, 9 AM there, and a meeting on the call-home time. */
class IndiaClockTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0, day: Int = 20) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun lateThereAndNineThere() {
        // 2:10 PM in Buffalo is 11:40 PM in India (EDT, +9:30).
        assertTrue(IndiaClock.late(at(14, 10)))
        assertEquals("11:40 PM IST, probably asleep", IndiaClock.words(at(14, 10)))
        assertFalse(IndiaClock.late(at(10, 0)))
        // The next 9 AM in India is 11:30 PM here.
        assertEquals(at(23, 30), IndiaClock.nineIst(at(14, 10)).withZoneSameInstant(zone))
    }

    @Test fun aMeetingOnTheUsualCallTime() {
        val slot = CallSlot(DayOfWeek.TUESDAY, 20 * 60)
        val sync = CalEvent("Team sync", ms(at(19, 45)), ms(at(20, 30)), "")
        assertEquals(sync, IndiaClock.clash(slot, listOf(sync), at(18)))
        assertNull(IndiaClock.clash(slot, listOf(sync.copy(begin = ms(at(21)), end = ms(at(22)))), at(18)))
        assertNull(IndiaClock.clash(slot, listOf(sync), at(16)))
        assertNull(IndiaClock.clash(slot.copy(day = DayOfWeek.MONDAY), listOf(sync), at(18)))
        val c = MomentRules.pick(MomentContext(at(18), callSlot = slot, qol = QolSignals(clash = sync)), MomentsPrefs(trashAsks = 3))!!
        assertEquals(MomentKind.CALL_CLASH, c.kind)
        assertEquals("Team sync is on your call-home time", c.content.title)
        assertEquals("You usually call around 8:00 PM. It's 3:30 AM in India now.", c.content.body)
    }
}
