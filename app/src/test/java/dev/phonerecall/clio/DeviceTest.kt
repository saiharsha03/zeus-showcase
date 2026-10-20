package dev.phonerecall.clio

import dev.phonerecall.brief.ScreenTime
import dev.phonerecall.brief.UsageEvent
import dev.phonerecall.mind.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.ZoneId

class DeviceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 20)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun at(h: Int, m: Int = 0) = at(today, h, m)
    private val min = 60_000L

    @Test fun broadcastValuesBecomeWords() {
        assertEquals(listOf("AC", "USB", "wireless", "dock", "power"), listOf(1, 2, 4, 8, 0).map(DeviceLog::plug))
        assertEquals("42%, AC", DeviceLog.plugged(42, "AC"))
        assertEquals("42%, USB (noticed when Zeus started)", DeviceLog.plugged(42, "USB", late = true))
        assertEquals("20% to 85%, 1 h 30 min", DeviceLog.unplugged(at(9), 20, at(10, 30), 85))
        assertEquals("85%", DeviceLog.unplugged(null, null, at(10), 85))
        assertEquals("64%, charging (AC), 31°C, saver on", DeviceLog.battery(64, "AC", 314, saver = true))
        assertEquals("64%, 29°C", DeviceLog.battery(64, null, 290, saver = false))
        assertEquals(listOf("on Wi-Fi", "on Wi-Fi", "on mobile data", "offline"), listOf(DeviceLog.network(true, false), DeviceLog.network(true, true), DeviceLog.network(false, true), DeviceLog.network(false, false)))
        assertTrue(DeviceLog.NETWORK.containsAll(listOf("on Wi-Fi", "on mobile data", "offline")))
        assertEquals(listOf("ringer silent", "ringer vibrate", "ringer normal"), listOf(0, 1, 2).map(DeviceLog::ringer))
        assertEquals("do not disturb off", DeviceLog.dnd(1))
        assertEquals("do not disturb on (priority)", DeviceLog.dnd(2))
        assertEquals("do not disturb on (alarms only)", DeviceLog.dnd(4))
        assertEquals("airplane mode on", DeviceLog.airplane(true))
        assertTrue(DeviceLog.isCar(0x0420) && DeviceLog.isCar(0x0408))
        assertFalse(DeviceLog.isCar(0x0404))   // a headset is not a car
    }

    @Test fun overnightIsThroughThreeAmAndThreeHours() {
        val y = today.minusDays(1)
        assertTrue(DeviceLog.overnight(at(y, 23, 40), at(7, 5), zone))
        assertTrue(DeviceLog.overnight(at(1), at(6), zone))
        assertFalse(DeviceLog.overnight(at(2, 30), at(4), zone))      // through 3 AM but short
        assertFalse(DeviceLog.overnight(at(9), at(14), zone))          // long but daytime
        assertEquals("11:40 PM to 7:05 AM, 7 h 25 min, 22% to 100%", DeviceLog.overnightDetail(at(y, 23, 40), at(7, 5), 22, 100, zone))
    }

    @Test fun heatLogsOncePerSpikeWithHysteresis() {
        var hot = false
        val logged = listOf(380, 421, 430, 410, 399, 425).map { t -> DeviceLog.heat(t, hot).also { hot = it.first }.second }
        assertEquals(listOf(false, true, false, false, false, true), logged)
    }

    @Test fun alarmChangeIsSnoozeDismissOrNothing() {
        val ring = at(7)
        assertEquals(DeviceLog.ALARM_SNOOZED, DeviceLog.alarm(ring, ring + 10 * min, ring + min))
        assertEquals(DeviceLog.ALARM_DISMISSED, DeviceLog.alarm(ring, at(today.plusDays(1), 7), ring + 2 * min))
        assertEquals(DeviceLog.ALARM_DISMISSED, DeviceLog.alarm(ring, null, ring + min))
        assertNull(DeviceLog.alarm(ring, ring, ring))                                  // unchanged
        assertNull(DeviceLog.alarm(at(today.plusDays(1), 7), at(today.plusDays(1), 8), ring))   // edited, not due
        assertNull(DeviceLog.alarm(null, ring, ring))
    }

    private fun ev(pkg: String, type: Int, d: LocalDate, h: Int, m: Int = 0) = UsageEvent(pkg, type, at(d, h, m))

    @Test fun screenLinesNameTopFifteenAndFoldTheRestIntoOther() {
        val y = today.minusDays(1)
        val events = ArrayList<UsageEvent>()
        events += ev("", ScreenTime.KEYGUARD_HIDDEN, y, 3, 30)          // before 4 AM: last night's
        events += ev("", ScreenTime.KEYGUARD_HIDDEN, y, 7, 2)
        // 17 apps, app0 longest; app16 and app15 fall outside the top 15.
        for (i in 0 until 17) {
            events += ev("com.app$i", ScreenTime.RESUMED, y, 8 + i / 2, (i % 2) * 30)
            events += ev("com.app$i", ScreenTime.PAUSED, y, 8 + i / 2, (i % 2) * 30 + (20 - i))
        }
        events += ev("com.chase.mobile", ScreenTime.RESUMED, y, 18)
        events += ev("com.chase.mobile", ScreenTime.PAUSED, y, 18, 9)
        events += ev("com.wire", ScreenTime.RESUMED, y, 19)
        events += ev("com.wire", ScreenTime.PAUSED, y, 19, 25)          // "Wire" reads as money to the sanitizer
        events += ev("", DeviceLog.SCREEN_NON_INTERACTIVE, y, 23, 48)
        events += ev("", DeviceLog.SCREEN_NON_INTERACTIVE, today, 1, 10)  // after midnight, before 4 AM: counts
        val label = { p: String -> if (p == "com.wire") "Wire" else if (p.startsWith("com.chase")) "Chase" else p.removePrefix("com.") }
        val lines = DeviceLog.screenLines(events, today, zone, "dev.phonerecall", label).toMap()
        val total = (0 until 17).sumOf { 20 - it } + 9 + 25
        assertEquals("$y · ${ScreenTime.duration(total)} · 2 pickups", lines[DeviceLog.SCREEN_TIME])
        val apps = DeviceDay.apps(lines.getValue(DeviceLog.APP_TIME).removePrefix("$y · "))
        assertEquals(16, apps.size)
        assertEquals("app0" to 20, apps.first())
        assertFalse(apps.any { it.first == "Chase" || it.first == "Wire" })
        assertEquals("other" to (5 + 4 + 9 + 25), apps.last())      // app15, app16, the bank and Wire
        assertEquals(total, apps.sumOf { it.second })
        assertEquals("$y · 7:02 AM", lines[DeviceLog.FIRST_UNLOCK])
        assertEquals("$y · 1:10 AM", lines[DeviceLog.LAST_OFF])
    }

    @Test fun screenLinesSurviveTheSanitizerAndTheLongerDetail() {
        val log = ClioLog(tmp.newFolder(), { Sanitizer(mapOf("Mom" to "family (mother)")) }, { at(9) }, { zone })
        val app = "2026-10-19 · " + (1..15).joinToString(" · ") { "Application$it ${60 - it}" } + " · other 7"
        log.append(DeviceLog.AREA, Level.INFO, DeviceLog.APP_TIME, app, max = 800)
        log.append(DeviceLog.AREA, Level.INFO, DeviceLog.SCREEN_TIME, "2026-10-19 · 3 h 10 min · 54 pickups")
        val back = log.lines().associate { it.event to it.detail }
        assertEquals(app, back[DeviceLog.APP_TIME])
        assertEquals("2026-10-19 · 3 h 10 min · 54 pickups", back[DeviceLog.SCREEN_TIME])
    }

    private fun l(ts: Long, event: String, detail: String = "", area: String = DeviceLog.AREA) = ClioLine(ts, area, Level.INFO, event, detail)

    @Test fun spansPairStartsAndEndsPerLane() {
        val lines = listOf(
            l(at(7), DeviceLog.SCREEN_ON), l(at(7, 1), DeviceLog.UNLOCKED), l(at(7, 20), DeviceLog.SCREEN_OFF),
            l(at(8), DeviceLog.UNLOCKED), l(at(8, 5), DeviceLog.SCREEN_OFF),            // an unlock alone opens it
            l(at(9), DeviceLog.SCREEN_ON), l(at(23), DeviceLog.SCREEN_OFF),             // no off for 14 h: cut at 4 h
            l(at(10), DeviceLog.ARRIVED, "Costco", DeviceLog.PLACES), l(at(10, 1), DeviceLog.ARRIVED, "Costco", DeviceLog.PLACES),
            l(at(10, 40), DeviceLog.LEFT, "Wegmans", DeviceLog.PLACES), l(at(11), DeviceLog.LEFT, "Costco", DeviceLog.PLACES),
            l(at(12), "drive started", area = "odyssey"), l(at(12, 30), "drive ended", "score 90 · 30 min", "odyssey"),
            l(at(13), DeviceLog.PLUGGED, "40%, AC"),                                   // still charging
        )
        val s = DeviceDay.spans(lines, at(14))
        assertEquals(
            listOf(
                Span(Lane.SCREEN, at(7), at(7, 20)), Span(Lane.SCREEN, at(8), at(8, 5)), Span(Lane.SCREEN, at(9), at(13)),
                Span(Lane.PLACES, at(10), at(11), "Costco"), Span(Lane.DRIVES, at(12), at(12, 30)), Span(Lane.CHARGING, at(13), at(14)),
            ),
            s,
        )
    }

    @Test fun aDayIsClippedAtMidnightAndSummarized() {
        val y = today.minusDays(1)
        val lines = listOf(
            l(at(y, 23), DeviceLog.PLUGGED, "22%, AC"),
            l(at(6, 40), DeviceLog.FULL, "after 7 h 40 min"),
            l(at(7, 5), DeviceLog.UNPLUGGED, "22% to 100%, 8 h 5 min"),
            l(at(7, 5), DeviceLog.OVERNIGHT, "11:00 PM to 7:05 AM, 8 h 5 min, 22% to 100%"),
            l(at(9), DeviceLog.BATTERY, "88%, 30°C"), l(at(21, 30), DeviceLog.BATTERY, "18%, 33°C"),
            l(at(15), DeviceLog.HOT, "43°C at 50%"),
            l(at(7, 10), DeviceLog.SCREEN_ON), l(at(7, 40), DeviceLog.SCREEN_OFF),
            l(at(8), "on mobile data"), l(at(8, 30), "on Wi-Fi"), l(at(12), DeviceLog.airplane(true)),
            l(at(8, 15), DeviceLog.CAR_ON), l(at(8, 45), DeviceLog.CAR_OFF),
            l(at(22), "ringer vibrate"), l(at(22, 1), "do not disturb on (priority)"),
            l(at(7), DeviceLog.ALARM_SNOOZED), l(at(7, 10), DeviceLog.ALARM_DISMISSED),
            l(at(8, 10), "drive started", area = "odyssey"), l(at(8, 40), "drive ended", "score 82 · 30 min", "odyssey"),
            l(at(17), "drive started", area = "odyssey"), l(at(17, 1), "drive ended", "score 50 · 1 min (too short, not kept)", "odyssey"),
            l(at(9), DeviceLog.ARRIVED, "Office", DeviceLog.PLACES), l(at(16), DeviceLog.LEFT, "Office", DeviceLog.PLACES),
            // written the next morning about this day; the line about yesterday is ignored
            l(at(today.plusDays(1), 5), DeviceLog.SCREEN_TIME, "$today · 3 h 10 min · 54 pickups"),
            l(at(today.plusDays(1), 5), DeviceLog.APP_TIME, "$today · YouTube 52 · WhatsApp 31 · other 12"),
            l(at(today.plusDays(1), 5), DeviceLog.FIRST_UNLOCK, "$today · 7:02 AM"),
            l(at(5), DeviceLog.SCREEN_TIME, "$y · 9 h"),
        )
        val d = DeviceDay.of(lines, today, zone, at(today.plusDays(1), 9))
        assertEquals(Span(Lane.CHARGING, at(0), at(7, 5)), d.spans.first { it.lane == Lane.CHARGING })   // from midnight
        assertEquals(listOf("YouTube" to 52, "WhatsApp" to 31, "other" to 12), d.apps)
        val rows = d.rows.toMap()
        assertEquals("3 h 10 min · 54 pickups", rows["Screen time"])
        assertEquals("1 time, 30 min (while Zeus was running)", rows["Screen on"])
        assertEquals("7:02 AM", rows["First unlock"])
        assertEquals("1 session, 7 h 5 min · full at 6:40 AM", rows["Charging"])
        assertEquals("11:00 PM to 7:05 AM, 8 h 5 min, 22% to 100%", rows["Overnight"])
        assertEquals("lowest 18% at 9:30 PM · hot 1 time", rows["Battery"])
        assertEquals("Office", rows["Places"])
        assertEquals("1 drive, 30 min · best score 82", rows["Drives"])
        assertEquals("connected 1 time", rows["Car Bluetooth"])
        assertEquals("2 switches, last Wi-Fi · airplane mode 1 time", rows["Network"])
        assertEquals("2 changes, last do not disturb on (priority)", rows["Sound"])
        assertEquals("dismissed 1, snoozed 1", rows["Alarms"])
        assertNull(rows["Last screen-off"])
        // Nothing logged: no rows, no spans.
        val empty = DeviceDay.of(emptyList(), today, zone, at(12))
        assertTrue(empty.rows.isEmpty() && empty.spans.isEmpty() && empty.apps.isEmpty())
    }
}
