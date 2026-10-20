package dev.phonerecall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WiringTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test fun firstAlarmPrefersOneThatAlreadyRangThenTheNextOneToday() {
        val now = at(9)
        assertEquals(at(6, 30), Wiring.firstAlarm(listOf(at(7, 0, day.minusDays(1)), at(6, 30), at(18)), at(18), day, now, zone))
        assertEquals(at(18), Wiring.firstAlarm(emptyList(), at(18), day, now, zone))
        // A seen value still in the future is a reading, not a ring; tomorrow's next alarm is not today's.
        assertNull(Wiring.firstAlarm(listOf(at(20)), at(7, 0, day.plusDays(1)), day, now, zone))
    }

    @Test fun unusualIsAnHourOffTheUsual() {
        assertTrue(Wiring.unusual(at(5), 7 * 60, zone))
        assertFalse(Wiring.unusual(at(6, 30), 7 * 60, zone))
        assertFalse(Wiring.unusual(at(5), null, zone))
    }
}
