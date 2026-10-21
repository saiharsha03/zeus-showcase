package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/**
 * A sample day: Home 7:22–9:09, drive, the office 9:35–2:28, drive 2:28–2:49, home since 2:49. The open stay
 * at home showed as "Near Oak Avenue" and the drive home was missing until he left again.
 */
class HomeTodayTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val day = LocalDate.of(2026, 10, 20)
    private val home = KnownPlace("kh", "Home", NameSource.HINT, Kind.HOME, Fx.HOME_LAT, Fx.HOME_LNG, "Oak Ave", learned = true, visits = 1)
    private val office = KnownPlace("kw", "Office", NameSource.HINT, Kind.WORK, 42.8770, -85.6590, "Pine St", learned = true, visits = 1)

    /** 135 m east of where the morning's fix put Home (the other side of the house). */
    private val homeLng2 = Fx.HOME_LNG + 135 / (111_320.0 * kotlin.math.cos(Math.toRadians(Fx.HOME_LAT)))

    private fun day(): OdysseyData = OdysseyData(
        settings = OdysseySettings(on = true), places = listOf(home, office),
        visits = listOf(Visit("v1", "kh", Fx.at(day, 7, 22), Fx.at(day, 9, 9)), Visit("v2", "kw", Fx.at(day, 9, 35), Fx.at(day, 14, 28))),
        trips = listOf(Trip("t1", "kh", "kw", Fx.at(day, 9, 9), Fx.at(day, 9, 35))),
        drives = listOf(DriveReport("d1", Fx.at(day, 9, 9), Fx.at(day, 9, 35), 14_300, score = 14), DriveReport("d2", Fx.at(day, 14, 28), Fx.at(day, 14, 49), 14_100, score = 57)),
        stay = OpenStay(Fx.at(day, 14, 49), Fx.HOME_LAT, homeLng2, 12f, street = "Oak Ave"),
        departure = Departure("kw", Fx.at(day, 14, 28)),
    )

    @Test fun homeAndTheOfficeReach150MetresOtherPlaces120() {
        assertEquals("kh", Visits.placeFor(listOf(home), Fx.HOME_LAT, homeLng2, 12f)?.id)
        assertNull("a shop 135 m away is another place", Visits.placeFor(listOf(home.copy(kind = Kind.SHOP)), Fx.HOME_LAT, homeLng2, 12f))
        assertEquals("kh", Visits.known(day(), Fix(0, Fx.HOME_LAT, homeLng2, 12f))?.id)
    }

    @Test fun theStayAtHomeClosesOntoHome() {
        val d = Visits.close(day(), Fx.at(day, 18)).data
        assertEquals(2, d.places.size)
        assertEquals("kh", d.visits.last().placeId)
        assertEquals("the drive home is a trip now", listOf("kh>kw", "kw>kh"), d.trips.map { "${it.from}>${it.to}" })
    }

    @Test fun theDriveHomeShowsBeforeTheStayCloses() {
        val d = day()
        val from = Fx.at(day, 0); val to = Fx.at(day.plusDays(1), 0)
        val drives = Timeline.drives(d, from, to)
        assertEquals(listOf("d1", "d2"), drives.map { it.second?.id })
        assertEquals("the morning trip finds its drive by time", "t1", drives[0].first.id)
        assertEquals(21L, drives[1].first.minutes)
        // Once the stay closes the trip takes the drive over; it isn't shown twice.
        val closed = Visits.close(d, Fx.at(day, 18)).data
        assertEquals(listOf("d1", "d2"), Timeline.drives(closed, from, to).map { it.second?.id })
        assertEquals(2, Timeline.drives(closed, from, to).size)
    }

    @Test fun athenaKnowsHeIsHomeAndHowHeGotThere() {
        val now = Fx.at(day, 15, 30)
        val lines = OdysseyBrain.today(day(), now, Fx.zone)
        assertEquals(listOf("At home since 2:49 PM today", "Last drive today: the office to home, 2:28 PM to 2:49 PM, 21 min, score 57"), lines)
        val facts = OdysseyBrain.facts(day(), now, Fx.zone)
        assertTrue(facts.toString(), facts.containsAll(lines))
        assertTrue(facts.toString(), facts.contains("Left the office at 2:28 today (there from 9:35)"))
        assertTrue("no street for Athena", facts.none { it.contains("Oak Ave") || it.contains("Pine St") })
    }

    /** His "Home" pin in Places fired the arrival notification; the stay must land on the same Home. */
    @Test fun hisPinIsTheOneTruth() {
        val far = Fx.HOME_LNG + 220 / (111_320.0 * kotlin.math.cos(Math.toRadians(Fx.HOME_LAT)))
        val pin = Pin("Home", Fx.HOME_LAT, far - 0.0005, 150f)
        // 220 m from Odyssey's Home centre (beyond 150 m), but inside his pin: the stay heals onto Home.
        val d = day().copy(stay = day().stay!!.copy(lng = far))
        assertNull(Visits.placeFor(d.places, d.stay!!.lat, d.stay!!.lng, 12f))
        val healed = Pins.heal(d, listOf(pin))
        assertEquals("kh", Visits.placeFor(healed.places, healed.stay!!.lat, healed.stay!!.lng, 12f)?.id)
        assertEquals("kh", Visits.close(healed, Fx.at(day, 18)).data.visits.last().placeId)
        // A new fix inside the pin snaps the same way.
        val (_, f) = Pins.snap(d, listOf(pin), Fix(0, Fx.HOME_LAT, far, 20f))
        assertEquals(Fx.HOME_LNG, f.lng, 1e-9)
        // No Home in Odyssey yet: his pin makes it, as his.
        val (made, p) = Pins.place(OdysseyData(nextId = 7), pin)
        assertEquals(Kind.HOME, p!!.kind); assertEquals(NameSource.HIM, p.source); assertEquals("k7", p.id); assertEquals(1, made.places.size)
        // A store pin with no Odyssey place of that name changes nothing.
        assertEquals(d, Pins.heal(d, listOf(Pin("Fresh Mart", Fx.HOME_LAT, far, 150f))))
    }

    /** Odyssey's day for an Athena read (read_zeus): names, times, minutes, miles; no streets, no coordinates. */
    @Test fun readPlacesSaysWhereHeIsWithoutStreetsOrCoordinates() {
        val store = KnownPlace("kg", "Fresh Mart (Maple St)", NameSource.POI, Kind.GROCERY, 42.9601, -85.6601, "Maple St", visits = 4)
        val unnamed = KnownPlace("ku", "Near Elm St", NameSource.NONE, Kind.OTHER, 42.93, -85.60, "Elm St", visits = 1)
        val d = day().copy(places = day().places + store + unnamed, visits = day().visits + Visit("v3", "ku", Fx.at(day, 6), Fx.at(day, 6, 20)))
        val out = OdysseyBrain.places(d, Fx.at(day, 15, 30), Fx.zone)
        assertEquals("Home since 2:49 PM", out["now"])
        @Suppress("UNCHECKED_CAST") val today = out["today"] as List<Map<String, Any?>>
        assertEquals(listOf("stay", "stay", "drive", "stay", "drive", "stay"), today.map { it["type"] })
        assertEquals(listOf("an unnamed place", "Home", null, "Office", null, "Home"), today.map { it["place"] })
        assertEquals(mapOf("type" to "drive", "from" to "2:28 PM", "to" to "2:49 PM", "minutes" to 21L, "miles" to "8.8", "score" to 57, "tag" to "Commute"), today[4])
        @Suppress("UNCHECKED_CAST") val places = out["places"] as List<Map<String, Any?>>
        assertEquals(listOf("Fresh Mart", "Home", "Office"), places.map { it["name"] })
        val text = dev.phonerecall.mind.stateJson.writeValueAsString(out)
        assertTrue(text, (out["lines"] as List<*>).contains("At home since 2:49 PM today"))
        assertTrue("no coordinates: $text", !Regex("""-?\d{1,3}\.\d{3,}""").containsMatchIn(text))
        for (street in listOf("Maple", "Elm", "Oak Ave", "Pine St", "Near ")) assertTrue("leaked $street: $text", !text.contains(street))
    }

    /** 10-20 evening: no IN_VEHICLE came; leaving his Home pin starts the drive, and the drive's end makes the next stay. */
    @Test fun aGeofenceExitStartsTheDriveWhenActivityRecognitionIsSilent() {
        val home = day()
        assertTrue(Visits.startOnExit(home))
        val driving = Visits.activity(home, Act.VEHICLE_ENTER, Fx.at(day, 17, 59)).data
        assertTrue(driving.driving)
        assertEquals("the home stay closed when he left", "kh", driving.visits.last().placeId)
        assertTrue("no second start while one is under way", !Visits.startOnExit(driving))
        // DriveService ends with no VEHICLE_EXIT seen: it sends one, which asks for the fix that opens the next stay.
        val parked = Visits.activity(driving, Act.VEHICLE_EXIT, Fx.at(day, 18, 25))
        assertTrue(parked.needFix); assertTrue(!parked.data.driving)
        assertTrue("paused: nothing starts", !Visits.startOnExit(home.copy(settings = home.settings.copy(paused = true))))
        assertTrue("drive report off: nothing starts", !Visits.startOnExit(home.copy(settings = home.settings.copy(driveReport = false))))
    }

    private val homePin = Pin("Home", Fx.HOME_LAT, Fx.HOME_LNG, 150f)

    /** Tonight, backfilled from Clio's places lines: Home 2:49–5:59, away 5:59–7:22 (no drive noted), Home since 7:22. */
    @Test fun tonightsLeaveAndArrivalShowAsTimeAway() {
        val clio = listOf(Fx.at(day, 17, 57) to false, Fx.at(day, 17, 59) to true, Fx.at(day, 19, 22) to false)
        val d = Pins.backfill(day(), listOf(homePin), clio)
        assertEquals("kh", d.visits.last().placeId)
        assertEquals(Fx.at(day, 14, 49) to Fx.at(day, 17, 59), d.visits.last().start to d.visits.last().end)
        assertEquals(Fx.at(day, 19, 22), d.stay!!.start)
        assertEquals("kh", Visits.placeFor(d.places, d.stay!!.lat, d.stay!!.lng, 20f)?.id)
        assertEquals(listOf(Fx.at(day, 17, 59) to Fx.at(day, 19, 22)), Timeline.gaps(d, Fx.at(day, 0), Fx.at(day.plusDays(1), 0)))
        assertEquals(listOf(true, false), d.pinEvents.map { it.left })
        assertEquals("once only", d, Pins.backfill(d, listOf(homePin), clio))
        assertEquals("not without a Home pin around the stay", day(), Pins.backfill(day(), listOf(homePin.copy(name = "Gym")), clio))
    }

    @Test fun pinEventsEndAndOpenStaysAndDrivesCloseTheGap() {
        val left = Pins.event(day(), homePin, left = true, at = Fx.at(day, 18))
        assertNull(left.stay)
        assertEquals(Fx.at(day, 18), left.visits.last().end)
        val back = Pins.event(left, homePin, left = false, at = Fx.at(day, 19))
        assertEquals(Fx.at(day, 19), back.stay!!.start)
        val range = Fx.at(day, 0) to Fx.at(day.plusDays(1), 0)
        assertEquals(1, Timeline.gaps(back, range.first, range.second).size)
        val drove = back.copy(drives = back.drives + DriveReport("d3", Fx.at(day, 18, 2), Fx.at(day, 18, 55), 20_000))
        assertTrue("a drive covers it: no gap", Timeline.gaps(drove, range.first, range.second).isEmpty())
        assertEquals("arriving while driving opens nothing", null, Pins.event(left.copy(driving = true), homePin, left = false, at = Fx.at(day, 19)).stay)
    }

    /** Odyssey › Places is the one list: each pin shows what Odyssey learned there, and that place isn't listed twice. */
    @Test fun pinsAndLearnedPlacesAreOneList() {
        val d = day()
        assertEquals("by role", "kh", Pins.learnedFor(d, homePin)?.id)
        assertEquals("by name", "kw", Pins.learnedFor(d, Pin("office", 0.0, 0.0, 150f))?.id)
        assertEquals("inside its circle", "kw", Pins.learnedFor(d, Pin("Work lot", 42.8771, -85.6591, 150f))?.id)
        assertNull("a store pin with nothing learned yet", Pins.learnedFor(d, Pin("Fresh Mart", 42.9601, -85.6601, 150f)))
    }
}
