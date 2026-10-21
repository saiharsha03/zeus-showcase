package dev.phonerecall.qol

import dev.phonerecall.data.DayType
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Skip tomorrow's alarm?" before a holiday or a day out of office. */
class SkipAlarmTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun onlyHolidaysAndDaysOffWithAFreeMorning() {
        val now = at(20, 20)
        val a = SkipAlarm.check(ms(at(21, 6, 30)), "Clock", DayType.HOLIDAY, "A holiday", 0, now)!!
        assertEquals("A holiday", a.why)
        assertEquals("Out of office", SkipAlarm.check(ms(at(21, 6, 30)), "Clock", DayType.OOO, null, 0, now)?.why)
        assertNull(SkipAlarm.check(ms(at(21, 6, 30)), "Clock", DayType.WORKDAY, null, 0, now))
        assertNull(SkipAlarm.check(ms(at(21, 6, 30)), "Clock", DayType.HOLIDAY, null, 1, now))
        assertNull(SkipAlarm.check(ms(at(21, 13)), "Clock", DayType.HOLIDAY, null, 0, now))
        assertNull(SkipAlarm.check(ms(at(22, 6, 30)), "Clock", DayType.HOLIDAY, null, 0, now))
        assertNull(SkipAlarm.check(null, null, DayType.HOLIDAY, null, 0, now))
    }

    @Test fun theCardOpensTheClock() {
        val zeus = OffAlarm(ms(at(21, 6, 30)), "Zeus", "A holiday")
        val c = MomentRules.pick(MomentContext(at(20, 20), qol = QolSignals(offAlarm = zeus)), MomentsPrefs(trashAsks = 3))!!
        assertEquals(MomentKind.SKIP_ALARM, c.kind)
        assertEquals("Skip tomorrow's 6:30 AM alarm?", c.content.title)
        assertEquals("A holiday and nothing before noon.", c.content.body)
        assertEquals("dismiss:6:30", c.opens["off"])
        assertEquals(Opens.ALARMS, SkipAlarm.target(zeus.copy(app = "Clock"), zone))
        assertNull(SkipAlarm.card(MomentContext(at(20, 17), qol = QolSignals(offAlarm = zeus))))
    }
}
