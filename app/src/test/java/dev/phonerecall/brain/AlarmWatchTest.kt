package dev.phonerecall.brain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.test.core.app.ApplicationProvider
import dev.phonerecall.home.ActionRole
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmWatchTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    // Monday 2026-10-19 is today; the history runs back four weeks.
    private val today = LocalDate.of(2026, 10, 19)
    private val now = at(today, 21, 0)

    /** Weekdays 6:45, weekends none, for the four weeks before today. */
    private fun weekdayHistory(): List<Long> = (1..28).map { today.minusDays(it.toLong()) }
        .filter { it.dayOfWeek.value <= 5 }.map { at(it, 6, 45) }

    @Test fun nextAlarmParsing() {
        assertEquals("Clock", NextAlarm.of(1L, "com.sec.android.app.clockpackage", "dev.phonerecall").app)
        assertEquals("Clock", NextAlarm.of(1L, "com.google.android.deskclock", "dev.phonerecall").app)
        assertEquals("Zeus", NextAlarm.of(1L, "dev.phonerecall", "dev.phonerecall").app)
        assertEquals("com.other.app", NextAlarm.of(1L, "com.other.app", "dev.phonerecall").app)
        assertNull(NextAlarm.of(1L, null, "dev.phonerecall").app)
        // Samsung Calendar's midnight wake is not his alarm; the clocks and Zeus are.
        assertTrue(NextAlarm.notAnAlarm("com.samsung.android.calendar"))
        assertTrue(NextAlarm.notAnAlarm("com.google.android.calendar"))
        assertFalse(NextAlarm.notAnAlarm("com.sec.android.app.clockpackage"))
        assertFalse(NextAlarm.notAnAlarm("dev.phonerecall"))
        assertFalse(NextAlarm.notAnAlarm(null))
    }

    @Test fun habitIsWeekdaysAtSixFortyFive() {
        val h = AlarmRules.habit(weekdayHistory(), now, zone)
        assertEquals(6 * 60 + 45, h.forDay(DayOfWeek.TUESDAY))
        assertNull(h.forDay(DayOfWeek.SATURDAY))
        assertEquals("weekdays ~6:45 AM, weekends none", AlarmRules.habitLine(h))
    }

    @Test fun oneOffAlarmIsNotAHabit() {
        val h = AlarmRules.habit(weekdayHistory() + at(today.minusDays(5), 3, 0), now, zone) // a Wednesday... plus one odd 3 AM
        assertEquals(6 * 60 + 45, h.forDay(DayOfWeek.WEDNESDAY)) // the median ignores it
        val few = AlarmRules.habit(listOf(at(today.minusDays(2), 7, 0)), now, zone)
        assertTrue(AlarmRules.habitLine(few).startsWith("Not enough history"))
    }

    @Test fun differingDaysAreListedOneByOne() {
        val seen = (1..28).map { today.minusDays(it.toLong()) }.mapNotNull {
            when (it.dayOfWeek) { DayOfWeek.MONDAY -> at(it, 6, 0); DayOfWeek.THURSDAY -> at(it, 8, 30); else -> null }
        }
        val line = AlarmRules.habitLine(AlarmRules.habit(seen, now, zone))
        assertTrue(line, "Mon 6:00 AM" in line && "Thu 8:30 AM" in line && "Tue none" in line)
    }

    @Test fun historyKeepsSixtyDaysAndNoDuplicates() {
        val old = now - 61 * 86_400_000L
        val kept = AlarmRules.record(listOf(old, now + 1000), now + 1000, now)
        assertEquals(listOf(now + 1000), kept)
    }

    @Test fun zeusAlarmsRoundTripAndOneOffsDropAfterTheyPass() {
        val set = at(today, 21, 0)
        val oneOff = ZeusAlarm(6, 45, "Gym | early\nday", "", set)
        val repeating = ZeusAlarm(7, 0, null, "MONDAY,FRIDAY", set)
        val back = AlarmRules.decode(AlarmRules.encode(listOf(oneOff, repeating)))
        assertEquals(listOf("Gym   early day", null), back.map { it.label })
        assertEquals(at(today.plusDays(1), 6, 45), AlarmRules.nextRing(oneOff, set, zone))
        assertEquals(at(today.plusDays(4), 7, 0), AlarmRules.nextRing(repeating, set, zone)) // Friday
        assertEquals(2, AlarmRules.live(back, at(today.plusDays(1), 5, 0), zone).size)
        assertEquals(listOf(repeating.copy()), AlarmRules.live(back, at(today.plusDays(1), 7, 0), zone))
    }

    // ---- the evening card ----

    private val habit = AlarmRules.habit(weekdayHistory(), now, zone)
    private fun evening(h: Int) = ZonedDateTime.of(today.year, today.monthValue, today.dayOfMonth, h, 0, 0, 0, zone)

    @Test fun showsTomorrowsAlarmAfterEight() {
        val next = NextAlarm.of(at(today.plusDays(1), 6, 45), "com.sec.android.app.clockpackage", "dev.phonerecall")
        val c = AlarmCards.card(next, habit, evening(21))!!
        assertEquals("Alarm 6:45 AM", c.content.title)
        assertNull(AlarmCards.card(next, habit, evening(19)))
    }

    @Test fun offersTheUsualAlarmWhenNoneIsSet() {
        val c = AlarmCards.card(null, habit, evening(21))!! // tomorrow is Tuesday, a habit day
        assertEquals("No alarm set for tomorrow", c.content.title)
        val set = c.content.actions.first()
        assertEquals(ActionRole.ALARM_SET, set.role)
        assertEquals("Set 6:45 AM", set.label)
        assertEquals("6:45", set.payload)
        assertTrue(c.content.actions.any { it.role == ActionRole.OPEN_ALARMS })
        // Once offered it isn't offered again.
        assertTrue(AlarmCards.card(null, habit, evening(21), offered = true)!!.content.actions.none { it.role == ActionRole.ALARM_SET })
    }

    @Test fun staysQuietWithoutAHabitOrWhenTonightStillHasOne() {
        val friday = LocalDate.of(2026, 10, 23).atTime(21, 0).atZone(zone) // tomorrow is Saturday: no habit
        assertNull(AlarmCards.card(null, habit, friday))
        assertNull(AlarmCards.card(NextAlarm(at(today, 23, 0), null), habit, evening(21)))
        assertNotNull(AlarmCards.card(NextAlarm(at(today.plusDays(1), 9, 0), null), AlarmRules.habit(emptyList(), now, zone), evening(21)))
    }
}

/** The phone side and the read tool, on Robolectric's AlarmManager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlarmToolTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun readAlarmsReportsNextAlarmZeusAlarmsAndHabit() {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val trigger = System.currentTimeMillis() + 8 * 3_600_000L
        val show = PendingIntent.getActivity(ctx, 0, Intent(AlarmClock.ACTION_SHOW_ALARMS), PendingIntent.FLAG_IMMUTABLE)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), PendingIntent.getBroadcast(ctx, 1, Intent("x"), PendingIntent.FLAG_IMMUTABLE))
        val watch = AlarmWatch.get(ctx)
        watch.zeusSet(6, 45, "Gym")
        val box = ToolBox(EngineTest.EmptyReads, Fates(MemoryStore(), FakeState(), FakeExecutor(), { _, _, _ -> }), alarms = watch)
        val out = box.run("read_alarms", emptyMap(), null)
        assertFalse(out.text, out.isError)
        assertTrue(out.text, "next_alarm" in out.text && "\"set_in\"" in out.text)
        assertTrue(out.text, "Gym" in out.text && "6:45 AM" in out.text && "set_by_zeus" in out.text)
        assertTrue(out.text, "habit" in out.text && "can't be read" in out.text)
        assertTrue(watch.next()!!.at == trigger)
    }

    @Test fun readAlarmsWithoutTheWatchIsAnError() {
        val box = ToolBox(EngineTest.EmptyReads, Fates(MemoryStore(), FakeState(), FakeExecutor(), { _, _, _ -> }))
        assertTrue(box.run("read_alarms", emptyMap(), null).isError)
    }
}
