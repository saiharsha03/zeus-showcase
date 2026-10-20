package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** The visit detector: stays of 2 minutes or more, clustered into places, trips joined between them. */
class VisitsTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val day = LocalDate.of(2026, 10, 17)

    @Test fun aStayOfTwoMinutesIsAVisitAndShorterIsNot() {
        var d = Fx.stay(Fx.on(), Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 11), Fx.at(day, 11, 1))
        assertTrue("a 1 minute stop (a light) is not a visit", d.visits.isEmpty())
        d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 11, 30), Fx.at(day, 11, 33))
        assertEquals("3 minutes at the pump is", 1, d.visits.size)
        d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 12), Fx.at(day, 12, 40))
        assertEquals(2, d.visits.size)
        assertEquals(40L, d.visits.last().minutes)
        assertNull(d.stay)
    }

    @Test fun nearbyFixesClusterIntoOnePlaceAndFarOnesDont() {
        var d = Fx.stay(Fx.on(), Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 11), Fx.at(day, 11, 30))
        // 60 m away in the same lot, a week later: the same place, its centre moves halfway.
        d = Fx.stay(d, Fx.COSTCO_LAT + 0.00054, Fx.COSTCO_LNG, Fx.at(day.plusDays(7), 11), Fx.at(day.plusDays(7), 11, 30))
        assertEquals(1, d.places.size)
        assertEquals(2, d.places.single().visits)
        assertEquals(Fx.COSTCO_LAT + 0.00027, d.places.single().lat, 1e-6)
        d = Fx.stay(d, Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(day.plusDays(8), 10), Fx.at(day.plusDays(8), 11, 30))
        assertEquals(2, d.places.size)
    }

    @Test fun tripsJoinTheVisitsTheyConnect() {
        var d = Fx.stay(Fx.on(), Fx.HOME_LAT, Fx.HOME_LNG, Fx.at(day, 7), Fx.at(day, 10, 40))
        // A 1-minute stop on the way (a light, a drop-off) does not split the trip; 2 minutes would (every stop counts).
        d = Fx.stay(d, 42.98, -85.65, Fx.at(day, 10, 50), Fx.at(day, 10, 51))
        d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 11, 2), Fx.at(day, 11, 50))
        val t = d.trips.single()
        assertEquals(d.visits.first().placeId, t.from)
        assertEquals(d.visits.last().placeId, t.to)
        assertEquals(22L, t.minutes)
    }

    @Test fun walkingAwayAndGoingStillElsewhereEndsTheStay() {
        var d = Visits.activity(Fx.on(), Act.VEHICLE_EXIT, Fx.at(day, 9)).data
        d = Visits.fix(d, Fix(Fx.at(day, 9), Fx.WORK_LAT, Fx.WORK_LNG)).data
        d = Visits.activity(d, Act.WALKING_ENTER, Fx.at(day, 12)).data
        val still = Visits.activity(d, Act.STILL_ENTER, Fx.at(day, 12, 10))
        assertTrue("going still after a walk asks for one fix", still.needFix)
        // Still in the building: nothing changes.
        assertNull(Visits.fix(still.data, Fix(Fx.at(day, 12, 10), Fx.WORK_LAT + 0.0003, Fx.WORK_LNG)).visit)
        // 600 m away at lunch: the office visit ends when he started walking, a new stay opens.
        val lunch = Visits.fix(still.data, Fix(Fx.at(day, 12, 10), Fx.WORK_LAT + 0.0054, Fx.WORK_LNG))
        assertEquals(Fx.at(day, 12), lunch.visit!!.end)
        assertEquals(Fx.at(day, 12, 10), lunch.data.stay!!.start)
    }

    @Test fun nothingIsNotedWhenPausedOrOff() {
        val paused = Fx.on().copy(settings = OdysseySettings(on = true, paused = true))
        assertTrue(Fx.stay(paused, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 11), Fx.at(day, 12)).visits.isEmpty())
        assertFalse(Visits.activity(OdysseyData(), Act.VEHICLE_EXIT, 0).needFix)
    }

    @Test fun aForgottenPlaceIsNotRelearned() {
        var d = Fx.stay(Fx.on(), Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(day, 10), Fx.at(day, 11))
        d = Forget.place(d, d.places.single().id)
        assertTrue(d.places.isEmpty() && d.visits.isEmpty())
        d = Fx.stay(d, Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(day.plusDays(7), 10), Fx.at(day.plusDays(7), 11))
        assertTrue(d.visits.isEmpty())
    }

    @Test fun forgetADayAndAVisit() {
        var d = Fx.stay(Fx.on(), Fx.HOME_LAT, Fx.HOME_LNG, Fx.at(day, 7), Fx.at(day, 10, 40))
        d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day, 11), Fx.at(day, 11, 50))
        d = Fx.stay(d, Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(day.plusDays(1), 10), Fx.at(day.plusDays(1), 11))
        val one = Forget.visit(d, d.visits.last().id)
        assertEquals(2, one.visits.size)
        val gone = Forget.day(d, Fx.at(day, 0), Fx.at(day.plusDays(1), 0))
        assertEquals(1, gone.visits.size)
        assertTrue(gone.trips.isEmpty())
    }

    @Test fun homeAndOfficeAreLearnedFromWhenHeIsThere() {
        val d = Fx.officeWeeks(3)
        assertEquals("Home", d.home()?.name)
        assertEquals("Office", d.work()?.name)
        assertEquals(Fx.HOME_LAT, d.home()!!.lat, 1e-3)
        assertNotNull(d.trips.firstOrNull { it.from == d.home()!!.id && it.to == d.work()!!.id })
        // His own name wins over the learned label.
        val renamed = d.copy(places = d.places.map { if (it.kind == Kind.HOME) it.copy(name = "Oak Ave flat", source = NameSource.HIM) else it })
        assertEquals("Oak Ave flat", Roles.apply(renamed.places, renamed.visits).first { it.kind == Kind.HOME }.name)
    }

    @Test fun stateSurvivesTheSealedRoundTrip() {
        val d = Fx.officeWeeks(1)
        val sealer = JceSealer()
        val bytes = OdysseyStore.encode(sealer, d)
        assertFalse("nothing in the clear", String(bytes, Charsets.ISO_8859_1).contains("42.955"))
        assertEquals(d, OdysseyStore.decode(sealer, bytes))
    }
}
