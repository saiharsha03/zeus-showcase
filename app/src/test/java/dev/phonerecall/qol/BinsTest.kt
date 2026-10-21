package dev.phonerecall.qol

import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/** The bins-out chip: the evening before pickup, at his time. */
class BinsTest {
    private val zone = ZoneId.of("America/New_York")
    // 2026-10-20 is a Tuesday.
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)

    @Test fun theEveningBeforePickupAtHisTime() {
        val p = MomentsPrefs(trashDay = DayOfWeek.FRIDAY)
        assertEquals(at(22, 19), Bins.next(p, at(20, 10)))
        assertEquals(at(22, 20, 30), Bins.next(p.copy(trashMinute = 20 * 60 + 30), at(20, 10)))
        // Past tonight's time: next week.
        assertEquals(at(29, 19), Bins.next(p, at(22, 19, 1)))
        assertNull(Bins.next(MomentsPrefs(trashDay = null), at(20, 10)))
        // Thursday by default: Wednesday evening.
        assertEquals(at(21, 19), Bins.next(MomentsPrefs(), at(20, 10)))
    }

    @Test fun thursdayIsPresetOnce() {
        // Thursday is the default; a store saved before that (an explicit null) gets it once.
        assertEquals(DayOfWeek.THURSDAY, MomentsPrefs().trashDay)
        val p = Bins.preset(MomentsPrefs(trashDay = null))
        assertEquals(DayOfWeek.THURSDAY, p.trashDay)
        // He changed it, or cleared it after the preset: left alone.
        assertEquals(DayOfWeek.MONDAY, Bins.preset(MomentsPrefs(trashDay = DayOfWeek.MONDAY)).trashDay)
        assertNull(Bins.preset(p.copy(trashDay = null)).trashDay)
        // Wednesday 7 PM is the first chip.
        assertEquals(at(21, 19), Bins.next(p, at(20, 10)))
    }
}
