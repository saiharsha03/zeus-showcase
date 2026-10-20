package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

/** Odyssey learning Home and the office from his stays: clustering, the rules, and when it applies on its own. */
class LearnTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val tue = LocalDate.of(2026, 10, 20)
    private val pine = 42.8770 to -85.6590
    private val office = setOf(java.time.DayOfWeek.TUESDAY, java.time.DayOfWeek.WEDNESDAY, java.time.DayOfWeek.THURSDAY)

    private fun place(id: String, at: Pair<Double, Double>, street: String? = "Pine St", source: NameSource = NameSource.GEOCODER, kind: Kind = Kind.OTHER) =
        KnownPlace(id, street ?: "Place", source, kind, at.first, at.second, street)

    private fun data(places: List<KnownPlace>, visits: List<Visit>, drives: List<DriveReport> = emptyList()) =
        OdysseyData(settings = OdysseySettings(on = true), places = places, visits = visits, drives = drives, nextId = 100)

    /** Office mornings at Pine St: 9:34 to 5 PM on the given days. */
    private fun mornings(vararg days: LocalDate) = days.mapIndexed { i, d -> Visit("v$i", "k1", Fx.at(d, 9, 34), Fx.at(d, 17)) }

    private fun commuteDrive(d: LocalDate) = DriveReport("dr$d", Fx.at(d, 9, 9), Fx.at(d, 9, 34), 14_000)

    @Test fun staysClusterWithin150Metres() {
        val a = PlaceLearner.Stay(42.8770, -85.6590, "Pine St", 0, 1, false, null)
        val b = a.copy(lat = 42.8778, start = 2) // ~90 m north
        val far = a.copy(lat = 42.8800, start = 3) // ~330 m
        val cs = PlaceLearner.cluster(listOf(a, b, far))
        assertEquals(listOf(2, 1), cs.map { it.stays.size })
        assertEquals(42.8774, cs[0].lat, 1e-6)
        // Short stays don't count; an open one counts once it has lasted 30 minutes.
        val d = data(listOf(place("k1", pine)), listOf(Visit("v1", "k1", 0, 20 * 60_000L), Visit("v2", "k1", 0, 40 * 60_000L)))
        assertEquals(1, PlaceLearner.stays(d, 0).size)
        val open = d.copy(stay = OpenStay(Fx.at(tue, 9, 34), pine.first, pine.second, street = "Pine St"))
        assertEquals(1, PlaceLearner.stays(open, Fx.at(tue, 10)).size)
        assertEquals(2, PlaceLearner.stays(open, Fx.at(tue, 10, 5)).size)
    }

    @Test fun theFirstOfficeMorningAfterACommuteDriveIsSavedAtOnce() {
        val one = data(listOf(place("k1", pine)), mornings(tue))
        assertTrue("one morning, no drive: nothing yet", PlaceLearner.review(one, Fx.at(tue, 18), Fx.zone, office).let { it.asked.isEmpty() && it.applied.isEmpty() })
        val r = PlaceLearner.review(one.copy(drives = listOf(commuteDrive(tue))), Fx.at(tue, 18), Fx.zone, office)
        val g = r.applied.single()
        assertTrue(r.asked.isEmpty())
        assertEquals(Kind.WORK, g.kind)
        assertEquals("Saved Pine St as your office", PlaceLearner.text(g))
        assertTrue(r.data.work()!!.learned)
        assertTrue("decided once", PlaceLearner.review(r.data, Fx.at(tue, 19), Fx.zone, office).let { it.asked.isEmpty() && it.applied.isEmpty() })
        val sat = LocalDate.of(2026, 10, 17)
        assertNull("a Saturday is not an office morning", PlaceLearner.review(data(listOf(place("k1", pine)), mornings(sat), listOf(commuteDrive(sat))), Fx.at(sat, 18), Fx.zone, office).data.work())
    }

    /** Today, backfilled: the open stay at Pine St since 9:34, 25 minutes after leaving the last place. */
    @Test fun todaysOpenStayIsSavedAsTheOfficeRightAway() {
        val d = OdysseyData(settings = OdysseySettings(on = true), stay = OpenStay(Fx.at(tue, 9, 34), pine.first, pine.second, street = "Pine St"), departure = Departure("k0", Fx.at(tue, 9, 9)))
        assertTrue("not before half an hour", PlaceLearner.review(d, Fx.at(tue, 9, 50), Fx.zone, office).applied.isEmpty())
        val r = PlaceLearner.review(d, Fx.at(tue, 10, 10), Fx.zone, office)
        val g = r.applied.single()
        val p = r.data.work()!!
        assertEquals("Office", p.name); assertTrue(p.learned)
        assertTrue(OfficeDay.arrived(r.data, ZonedDateTime.of(tue.atTime(10, 15), Fx.zone)))
        assertEquals("At the office since 9:34 today", OdysseyBrain.arrival(r.data, Fx.at(tue, 10, 15), Fx.zone))
        // Undo: the place Odyssey made goes away, and the spot isn't saved again.
        val undone = PlaceLearner.answer(r.data, g.id, false)
        assertNull(undone.work())
        assertTrue(undone.places.isEmpty())
        assertTrue(PlaceLearner.review(undone, Fx.at(tue, 11), Fx.zone, office).applied.isEmpty())
    }

    @Test fun ambiguousOfficesAreAskedAndThreeMorningsApply() {
        val two = data(listOf(place("k1", pine)), mornings(tue.minusDays(7), tue))
        val asked = PlaceLearner.review(two, Fx.at(tue, 18), Fx.zone, office)
        assertEquals("Is this your office? (Pine St)", PlaceLearner.text(asked.asked.single()))
        // His Yes makes it his.
        val yes = PlaceLearner.answer(asked.data, asked.asked.single().id, true)
        assertEquals(NameSource.HIM, yes.work()!!.source); assertFalse(yes.work()!!.learned)
        // Something else: his own kind and name.
        val gym = PlaceLearner.relabel(asked.data, asked.asked.single().id, Kind.GYM, "LA Fitness")
        assertEquals(Kind.GYM, gym.place("k1")!!.kind); assertEquals("LA Fitness", gym.place("k1")!!.name); assertEquals(NameSource.HIM, gym.place("k1")!!.source)
        val three = data(listOf(place("k1", pine)), mornings(tue.minusDays(7), tue.minusDays(6), tue))
        val r = PlaceLearner.review(three, Fx.at(tue, 18), Fx.zone, office)
        val g = r.applied.single()
        assertEquals("k1", r.data.work()!!.id)
        // Roles leaves a learned office alone when the next visit closes.
        assertEquals(Kind.WORK, Roles.apply(r.data.places, r.data.visits, Fx.zone).first { it.id == "k1" }.kind)
        // Undo on a place that was there: back to its street name.
        val undone = PlaceLearner.answer(r.data, g.id, false)
        assertEquals("Pine St", undone.place("k1")!!.name); assertEquals(NameSource.GEOCODER, undone.place("k1")!!.source); assertNull(undone.work())
        assertEquals("Keep it", "yes", PlaceLearner.answer(r.data, g.id, true).guesses.single().state)
        // A second office while one is known is asked about, never saved on its own.
        val main = 42.9000 to -85.6700
        val second = r.data.copy(places = r.data.places + place("k2", main, "Main St"), visits = r.data.visits + Visit("m1", "k2", Fx.at(tue.plusDays(2), 9, 30), Fx.at(tue.plusDays(2), 16)) + Visit("m2", "k2", Fx.at(tue.plusDays(7), 9, 30), Fx.at(tue.plusDays(7), 16)), drives = listOf(commuteDrive(tue.plusDays(7)).copy(start = Fx.at(tue.plusDays(7), 9, 5), end = Fx.at(tue.plusDays(7), 9, 30))))
        val s2 = PlaceLearner.review(second, Fx.at(tue.plusDays(7), 18), Fx.zone, office)
        assertTrue(s2.applied.isEmpty()); assertEquals("Is this your office? (Main St)", PlaceLearner.text(s2.asked.single()))
        // Long weekend daytime stays somewhere new: asked, not saved.
        val sat = LocalDate.of(2026, 10, 17)
        val weekend = data(listOf(place("k1", pine)), listOf(Visit("w1", "k1", Fx.at(sat, 9), Fx.at(sat, 15)), Visit("w2", "k1", Fx.at(sat.plusDays(1), 9), Fx.at(sat.plusDays(1), 15))))
        val w = PlaceLearner.review(weekend, Fx.at(sat.plusDays(1), 18), Fx.zone, office)
        assertTrue(w.applied.isEmpty()); assertEquals(Kind.WORK, w.asked.single().kind)
    }

    @Test fun overnightsAreHome() {
        val home = 42.9550 to -85.6900
        fun nights(n: Int) = (0 until n).map { i -> Visit("h$i", "kh", Fx.at(tue.minusDays(i + 1L), 21), Fx.at(tue.minusDays(i.toLong()), 7, 30)) }
        val one = data(listOf(place("kh", home, "Oak Ave")), nights(1))
        assertEquals("one night, no drive back: asked", "Is this home? (Oak Ave)", PlaceLearner.text(PlaceLearner.review(one, Fx.at(tue, 12), Fx.zone, office).asked.single()))
        val back = one.copy(drives = listOf(DriveReport("e", Fx.at(tue.minusDays(1), 20, 35), Fx.at(tue.minusDays(1), 20, 58), 12_000)))
        val saved = PlaceLearner.review(back, Fx.at(tue, 12), Fx.zone, office)
        assertEquals("the first night after an evening drive back: saved", "Saved Oak Ave as home", PlaceLearner.text(saved.applied.single()))
        assertEquals("Home", saved.data.home()!!.name)
        assertEquals(Kind.HOME, PlaceLearner.review(data(listOf(place("kh", home, "Oak Ave")), nights(3)), Fx.at(tue, 12), Fx.zone, office).applied.single().kind)
        // A second overnight spot while Home is known (a hotel, a friend's): asked.
        val hotel = 43.0900 to -85.8600
        val two = saved.data.copy(places = saved.data.places + place("kt", hotel, "Lake Ave"), visits = saved.data.visits + Visit("t1", "kt", Fx.at(tue, 22), Fx.at(tue.plusDays(1), 8)))
        val r2 = PlaceLearner.review(two, Fx.at(tue.plusDays(1), 12), Fx.zone, office)
        assertTrue(r2.applied.isEmpty()); assertEquals("Is this home? (Lake Ave)", PlaceLearner.text(r2.asked.single()))
        // A known Home (his) is never asked about again.
        val his = data(listOf(place("kh", home, "Oak Ave", NameSource.HIM, Kind.HOME)), nights(3))
        assertTrue(PlaceLearner.review(his, Fx.at(tue, 12), Fx.zone, office).let { it.applied.isEmpty() && it.asked.isEmpty() })
    }

    @Test fun theOfficeArrivalTagsTheDriveAndReachesAthena() {
        val known = data(listOf(place("k1", pine, source = NameSource.HIM, kind = Kind.WORK).copy(name = "Office")), emptyList())
        val drive = commuteDrive(tue)
        assertEquals("no trip, no arrival: just a drive", TripTag.DRIVE, DriveInsights.tag(known, drive, Fx.zone))
        val arrived = known.copy(arrivals = listOf(Arrival("k1", Kind.WORK, Fx.at(tue, 9, 36))))
        assertEquals(TripTag.COMMUTE, DriveInsights.tag(arrived, drive, Fx.zone))
        assertEquals("At the office since 9:36 today", OdysseyBrain.arrival(arrived, Fx.at(tue, 11), Fx.zone))
        assertTrue(OdysseyBrain.facts(arrived, Fx.at(tue, 11), Fx.zone).contains("At the office since 9:36 today"))
        val left = arrived.copy(arrivals = listOf(Arrival("k1", Kind.WORK, Fx.at(tue, 9, 36), Fx.at(tue, 17, 2))))
        assertEquals("Left the office at 5:02 today (there from 9:36)", OdysseyBrain.arrival(left, Fx.at(tue, 18), Fx.zone))
        assertNull("yesterday's arrival is not today's", OdysseyBrain.arrival(arrived, Fx.at(tue.plusDays(1), 11), Fx.zone))
        // The WFH question is never asked once he is there.
        val now = ZonedDateTime.of(tue.atTime(10, 0), Fx.zone)
        assertTrue(OfficeDay.arrived(arrived, now))
        assertFalse(OfficeDay.due(dev.phonerecall.data.Availability(tue, dev.phonerecall.data.DayType.WORKDAY), CommuteStats(), now, true, false, emptyMap(), arrived = true))
    }
}
