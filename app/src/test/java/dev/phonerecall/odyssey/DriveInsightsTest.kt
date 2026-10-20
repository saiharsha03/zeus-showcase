package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/** The drive report's US units and each insight's math. */
class DriveInsightsTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val start = LocalDate.of(2026, 9, 7)

    private fun dow(ms: Long) = Instant.ofEpochMilli(ms).atZone(Fx.zone).dayOfWeek

    /** The drive for a trip, same times. */
    private fun driveFor(t: Trip, id: String = "d${t.start}", meters: Int = 13_000, score: Int = 90) = DriveReport(id, t.start, t.end, meters, 90, score = score)

    @Test fun unitsAreMilesAndMph() {
        assertEquals(1.0, Units.miles(1609), 0.001)
        assertEquals("8.8 mi", Units.mi(14_200))
        assertEquals("0.3 mi", Units.mi(500))
        assertEquals("124 mi", Units.mi(200_000))
        assertEquals(62, Units.mph(100))
        assertEquals("61 mph", Units.mphText(98))
        assertEquals("0 mph", Units.mphText(0))
        val w = DrivingReport.week(listOf(DriveReport("a", 0, 60_000, 16_093)), 120_000)
        assertEquals(10, w.miles)
    }

    /** No Odyssey screen, card or brain line shows km or km/h; storage stays metric, display converts. */
    @Test fun noKilometresOnAnyDriveDisplay() {
        val dir = File(System.getProperty("user.dir"), "src/main/java/dev/phonerecall/odyssey")
        val literal = Regex("\"[^\"]*\\bkm(/h)?\\b[^\"]*\"")
        for (name in listOf("Screens.kt", "DriveReportView.kt", "RouteMap.kt", "Bridges.kt", "CarScreens.kt", "DriveInsights.kt")) {
            val bad = File(dir, name).readLines().filter { l -> val t = l.trim(); !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("/**") && literal.containsMatchIn(t) }
            assertTrue("$name: $bad", bad.isEmpty())
        }
    }

    @Test fun tripTags() {
        val d = Fx.officeWeeks(2, start)
        val commute = d.trips.first { d.place(it.from)?.kind == Kind.HOME && d.place(it.to)?.kind == Kind.WORK }
        assertEquals(TripTag.COMMUTE, DriveInsights.tag(d, driveFor(commute)))
        val back = d.trips.first { d.place(it.from)?.kind == Kind.WORK }
        assertEquals(TripTag.COMMUTE, DriveInsights.tag(d, driveFor(back)))
        // An errand: Costco and home again, on a Saturday.
        val sat = start.plusDays(5)
        var e = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(sat, 12), Fx.at(sat, 12, 40), hints = listOf("You spent $61.20 at COSTCO WHSE #1234"))
        e = Fx.stay(e, Fx.HOME_LAT, Fx.HOME_LNG, Fx.at(sat, 13), Fx.at(sat, 18))
        val home = e.trips.first { it.start == Fx.at(sat, 12, 40) }
        assertEquals(TripTag.ERRAND, DriveInsights.tag(e, driveFor(home)))
        assertEquals("50 mi or more is a trip", TripTag.TRIP, DriveInsights.tag(e, driveFor(home, meters = 90_000)))
        // A drive on an office day that isn't the commute.
        val monday = start
        val lunch = DriveReport("lunch", Fx.at(monday, 12, 0), Fx.at(monday, 12, 10), 3_000)
        assertEquals(TripTag.OFFICE_DAY, DriveInsights.tag(d, lunch))
        assertEquals(TripTag.DRIVE, DriveInsights.tag(OdysseyData(), lunch))
        assertEquals("his correction wins", TripTag.TRIP, DriveInsights.tag(d, driveFor(commute).copy(tag = "TRIP")))
    }

    @Test fun stopsIdleAndSlowStretch() {
        val t0 = 1_000_000L
        // 60 s at 50 km/h, a 40 s stop at a light, 60 s more, then parked 5 min (not a stop: the drive is over).
        val pts = buildList {
            var t = t0; var lat = 42.95
            repeat(7) { add(TrackPoint(t, lat, -85.69, 50)); t += 10_000; lat += 0.00125 }
            repeat(5) { add(TrackPoint(t, lat, -85.69, 0)); t += 10_000 }
            repeat(7) { add(TrackPoint(t, lat, -85.69, 50)); t += 10_000; lat += 0.00125 }
            repeat(30) { add(TrackPoint(t, lat, -85.69, 0)); t += 10_000 }
        }
        val m = DriveInsights.motion(pts)
        assertEquals(1, m.stops)
        assertEquals(40 / 60.0, m.idleMinutes, 0.01)
        // Under 12.5 mph: the stop, the parked tail, and the half-speed segments either side of each.
        assertTrue(m.slowMinutes.toString(), m.slowMinutes in 5.0..6.5)
        assertTrue(m.slowMeters.toString(), m.slowMeters < 300)
        assertEquals(DriveMotion(0, 0.0, 0, 0.0), DriveInsights.motion(listOf(TrackPoint(t0, 42.9, -85.7, 60), TrackPoint(t0 + 1000, 42.9003, -85.7, 60))))
    }

    @Test fun commuteAgainstUsualAndTraffic() {
        val d = Fx.officeWeeks(6, start)
        val last = d.trips.filter { d.place(it.from)?.kind == Kind.HOME }.maxBy { it.start }
        assertEquals("About usual (22 min)", DriveInsights.vsUsual(d, driveFor(last)))
        val slow = d.copy(trips = d.trips.map { if (it.id == last.id) it.copy(end = it.start + 26 * Fx.MIN) else it })
        assertEquals("4 min slower than usual (22 min)", DriveInsights.vsUsual(slow, driveFor(slow.trips.first { it.id == last.id })))
        val fast = d.copy(trips = d.trips.map { if (it.id == last.id) it.copy(end = it.start + 17 * Fx.MIN) else it })
        assertEquals("5 min faster than usual (22 min)", DriveInsights.vsUsual(fast, driveFor(fast.trips.first { it.id == last.id })))
        val first = d.trips.filter { d.place(it.from)?.kind == Kind.HOME }.minBy { it.start }
        assertNull("too few before it", DriveInsights.vsUsual(d, driveFor(first)))
        val check = RoadCheck("commute:x", last.start - 20 * Fx.MIN, "Office", "Office", 0, 28, 6, listOf("Crash on I-190 S near exit 7"), reason = "commute")
        assertEquals("TomTom saw +6 min of traffic before you left, Crash on I-190 S near exit 7", DriveInsights.traffic(d.copy(checks = listOf(check)), driveFor(last)))
        assertNull("a check from the night before doesn't count", DriveInsights.traffic(d.copy(checks = listOf(check.copy(at = last.start - 10 * 3_600_000L))), driveFor(last)))
    }

    @Test fun leaveEarlierOnlyForCommutesFromHomeOnThatWeekday() {
        var n = 0
        val d = Fx.officeWeeks(8, start,
            departMin = { if (it.dayOfWeek == DayOfWeek.TUESDAY && n++ % 2 == 0) 8 * 60 else if (it.dayOfWeek == DayOfWeek.TUESDAY) 8 * 60 + 15 else 8 * 60 + 20 },
            driveMin = { 20 })
        val slow = d.copy(trips = d.trips.map { t -> if (dow(t.start) == DayOfWeek.TUESDAY && t.from == d.home()?.id && Instant.ofEpochMilli(t.start).atZone(Fx.zone).minute == 15) t.copy(end = t.start + 30 * Fx.MIN) else t })
        val now = Fx.at(start.plusWeeks(8), 6)
        val tue = slow.trips.filter { dow(it.start) == DayOfWeek.TUESDAY && it.from == slow.home()?.id }.maxBy { it.start }
        assertEquals("Leaving 15 min earlier on Tuesdays saves 10 min.", DriveInsights.leaveEarlier(slow, driveFor(tue), now, Fx.zone))
        val wed = slow.trips.filter { dow(it.start) == DayOfWeek.WEDNESDAY && it.from == slow.home()?.id }.maxBy { it.start }
        assertNull("Wednesdays show no gain", DriveInsights.leaveEarlier(slow, driveFor(wed), now, Fx.zone))
        val back = slow.trips.filter { dow(it.start) == DayOfWeek.TUESDAY && it.from == slow.work()?.id }.maxBy { it.start }
        assertNull("not for the drive home", DriveInsights.leaveEarlier(slow, driveFor(back), now, Fx.zone))
    }

    private fun car(): CarData = CarData(
        lease = Lease(start = "2026-03-01", termMonths = 36, startOdometer = 12),
        fillups = listOf(
            FillUp("f1", Fx.at(LocalDate.of(2026, 9, 17), 18), "Costco", 4_210, 11.8, 3.57, odometer = 6_820, state = "logged"),
            FillUp("f2", Fx.at(LocalDate.of(2026, 10, 8), 18), "Costco", 4_005, 11.9, 3.37, odometer = 7_191, state = "logged"),
        ),
        readings = listOf(Reading(Fx.at(LocalDate.of(2026, 10, 8), 18), 7_191)),
    )

    @Test fun fuelFromHisMpgAndLastPrice() {
        val r = DriveReport("d", 0, 60_000, 14_200)
        // 371 miles on 11.9 gal = 31.2 mpg; 8.82 mi / 31.2 = 0.28 gal; at $3.37 = $0.95.
        assertEquals("~0.28 gal at 31 mpg · ~$0.95 at $3.37/gal", DriveInsights.fuel(OdysseyData(car = car()), r))
        assertNull("no fill-ups, no estimate", DriveInsights.fuel(OdysseyData(), r))
    }

    @Test fun leasePaceWithThisDrive() {
        val now = Fx.at(LocalDate.of(2026, 10, 17), 15)
        val r = DriveReport("d", now - 30 * Fx.MIN, now - 5 * Fx.MIN, 14_200)
        val d = OdysseyData(car = car(), drives = listOf(r))
        // 7,191 - 12 + 8.8 GPS miles since the reading = 7,188 over 230 days: ~11,400 a year.
        assertEquals("8.8 mi this drive · Month 8/36, 7,188 mi, pace 11,400/yr → ~4,200 over", DriveInsights.lease(d, r, now, Fx.zone))
        assertNull(DriveInsights.lease(OdysseyData(drives = listOf(r)), r, now, Fx.zone))
    }

    @Test fun scoreTrendAndWeeklySparkline() {
        val day = 86_400_000L
        val prior = listOf(80, 90, 70, 80).mapIndexed { i, s -> DriveReport("p$i", i * day, i * day + 600_000, 5_000, score = s) }
        val r = DriveReport("r", 10 * day, 10 * day + 600_000, 5_000, score = 84)
        assertEquals("4 above your last 4 (80)" to listOf(80, 90, 70, 80, 84), DriveInsights.scoreTrend(prior + r, r))
        assertEquals("16 below your last 4 (80)", DriveInsights.scoreTrend(prior + r, r.copy(score = 64))!!.first)
        assertNull("needs 3 before it", DriveInsights.scoreTrend(prior.take(2) + r, r))
        val now = 30 * day
        val weeks = listOf(DriveReport("a", now - 20 * day, now - 20 * day + 600_000, score = 70), DriveReport("b", now - 2 * day, now - 2 * day + 600_000, score = 90), DriveReport("c", now - day, now - day + 600_000, score = 80))
        assertEquals(listOf(70, 85), DriveInsights.weekScores(weeks, now))
    }

    @Test fun handsFreeAgainstPhoneUse() {
        val r = DriveReport("d", phone = PhoneUse(1.4, 0.8, 1, mapOf("com.whatsapp" to 0.9, "com.google.android.projection.gearhead" to 12.0), mapOf("com.google.android.apps.maps" to 21.0)))
        assertEquals("Hands-free 33 min (maps, gearhead) · phone 2.3 min", DriveInsights.handsFree(r))
        assertNull(DriveInsights.handsFree(DriveReport("e")))
    }

    @Test fun hotspotsAndSnow() {
        val e1 = DriveEvent(DriveEventKind.HARSH_BRAKE, 1, 42.98, -85.64, 0.4, 46, "Maple Ave")
        val e2 = e1.copy(at = 2, lat = 42.98030)
        val far = e1.copy(at = 3, lat = 42.99)
        val hs = DrivingReport.hotspots(listOf(DriveReport("a", events = listOf(e1, far)), DriveReport("b", events = listOf(e2))))
        assertEquals(1, hs.size)
        assertEquals(2, hs[0].count)
        assertEquals(42.98015, hs[0].lat, 1e-6)
        assertEquals(listOf("Hard braking 2x at the same spot on Maple Ave"), DrivingReport.spots(listOf(DriveReport("a", events = listOf(e1, far)), DriveReport("b", events = listOf(e2)))))
        val snowy = DriveReport("s", 10_000_000, 11_000_000, weather = "28°F, Snow", weatherCode = 73)
        assertTrue(DriveInsights.road(OdysseyData(), snowy)!!.startsWith("Snow at the start"))
        val note = RoadNote("snow:x", "snow", 9_000_000, "Snow band on I-290 at 8:30")
        assertTrue(DriveInsights.road(OdysseyData(notes = listOf(note)), snowy.copy(weatherCode = 3))!!.startsWith("Snow day: Snow band on I-290"))
        assertNull(DriveInsights.road(OdysseyData(), snowy.copy(weatherCode = 3)))
    }

    @Test fun allInsightsHaveDataBehindThem() {
        val empty = DriveInsights.all(OdysseyData(), DriveReport("x", 0, 60_000, 1_000), null, 120_000, Fx.zone)
        assertTrue(empty.toString(), empty.isEmpty())
        val r = syntheticRoute("d1", Fx.at(LocalDate.of(2026, 10, 17), 12, 55))
        val drive = DriveReport("d1", r.points.first().t, r.end, 14_200, 105, weather = "48°F, Cloudy")
        val all = DriveInsights.all(OdysseyData(drives = listOf(drive), car = car()), drive, r, r.end + 60_000, Fx.zone).map { it.label }
        assertEquals(listOf("Stops", "Lease", "Fuel", "Weather"), all)
        assertFalse(all.contains("Vs usual"))
    }
}
