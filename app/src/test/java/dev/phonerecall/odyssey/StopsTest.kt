package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** Every stop as a visit, named from TomTom's nearby search, with the Geocoder when offline. */
class StopsTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val day = LocalDate.of(2026, 10, 17)
    private val wegmans = PoiHit("Wegmans", "Cedar St", Kind.GROCERY, 22.0)

    /** A stop the way the receivers make one, with TomTom's answer (or none, offline). */
    private fun stop(d: OdysseyData, lat: Double, lng: Double, from: Long, to: Long, hit: PoiHit?, street: String? = "Cedar St"): OdysseyData {
        var x = Visits.activity(d, Act.VEHICLE_EXIT, from).data
        x = Visits.fix(x, Fix(from, lat, lng, 15f), null, street, null, hit).data
        return Visits.activity(x, Act.VEHICLE_ENTER, to).data
    }

    @Test fun stopsOfTwoToThreeMinutesAreVisits() {
        var d = stop(Fx.on(), 42.95, -85.67, Fx.at(day, 10), Fx.at(day, 10, 1), null)
        assertTrue("a minute at a light", d.visits.isEmpty())
        d = stop(d, 42.95, -85.67, Fx.at(day, 10, 10), Fx.at(day, 10, 12), null)
        assertEquals("two minutes is a stop", 1, d.visits.size)
        d = stop(d, 42.96, -85.66, Fx.at(day, 11), Fx.at(day, 11, 3), PoiHit("Delta Sonic", "Maple Ave", Kind.CAR_WASH, 15.0))
        assertEquals(2, d.visits.size)
        assertEquals(Kind.CAR_WASH, d.place(d.visits.last().placeId)!!.kind)
    }

    @Test fun tomTomNamesTheStopAndRepeatVisitsJoinOnePlace() {
        var d = stop(Fx.on(), 42.9601, -85.6601, Fx.at(day, 12), Fx.at(day, 12, 18), wegmans)
        val p = d.places.single()
        assertEquals("Wegmans (Cedar St)", p.name); assertEquals(NameSource.POI, p.source); assertEquals(Kind.GROCERY, p.kind)
        // Next week, 40 m off, TomTom down: still Wegmans, one place, two visits; no question asked.
        d = stop(d, 42.9604, -85.6602, Fx.at(day.plusDays(7), 12), Fx.at(day.plusDays(7), 12, 25), null)
        assertEquals(1, d.places.size)
        assertEquals("Wegmans (Cedar St)", d.places.single().name); assertEquals(2, d.places.single().visits)
        assertTrue(PlaceLearner.review(d, Fx.at(day.plusDays(7), 13), Fx.zone).asked.isEmpty())
        // A receipt beats TomTom.
        d = Visits.hint(Visits.fix(Visits.activity(d, Act.VEHICLE_EXIT, Fx.at(day.plusDays(8), 9)).data, Fix(Fx.at(day.plusDays(8), 9), 42.9602, -85.6601, 15f), stop = wegmans).data, "You spent $41.20 at WEGMANS #12")
        d = Visits.activity(d, Act.VEHICLE_ENTER, Fx.at(day.plusDays(8), 9, 30)).data
        assertEquals(NameSource.HINT, d.places.single().source)
    }

    @Test fun offlineTheGeocoderNamesIt() {
        val d = stop(Fx.on(), 42.97, -85.65, Fx.at(day, 9), Fx.at(day, 9, 10), null, street = "Maple Ave")
        val p = d.places.single()
        assertEquals("Near Maple Ave", p.name); assertEquals(NameSource.NONE, p.source)
        assertNull("unnamed spots stay out of Athena's words", Stops.plain(p))
    }

    @Test fun poiParsingAndLabels() {
        val json = """{"results":[
            {"dist":61.0,"poi":{"name":"Delta Sonic Car Wash","categories":["car wash"],"classifications":[{"code":"CAR_WASH"}]},"address":{"streetName":"Maple Ave"}},
            {"dist":12.5,"poi":{"name":"Wegmans Food Markets","brands":[{"name":"Wegmans"}],"categories":["shop","supermarkets hypermarkets"]},"address":{"streetName":"Cedar St"}},
            {"dist":140.0,"poi":{"name":"Far Away Diner","categories":["restaurant"]},"address":{"streetName":"Main St"}}]}"""
        val hits = Poi.parseNearby(json)
        assertEquals(listOf("Wegmans", "Delta Sonic Car Wash"), hits.map { it.name })
        assertEquals(listOf(Kind.GROCERY, Kind.CAR_WASH), hits.map { it.kind })
        assertEquals("Wegmans (Cedar St)", Poi.label(hits[0], null))
        assertEquals("Car wash on Maple Ave", Poi.label(PoiHit("Car Wash", null, Kind.CAR_WASH, 10.0), "Maple Ave"))
        assertEquals("Fuel stop on Birch Dr", Poi.label(PoiHit(null, "Birch Dr", Kind.FUEL), null))
        assertNull("offline: no label", Poi.label(null, "Main St"))
        assertTrue(Poi.parseNearby("not json").isEmpty())
        assertEquals("Cedar St", Poi.parseStreet("""{"addresses":[{"address":{"streetName":"Cedar St"}}]}"""))
        for ((words, kind) in listOf(listOf("petrol station") to Kind.FUEL, listOf("fitness club center") to Kind.GYM, listOf("pharmacy") to Kind.HEALTH, listOf("restaurant") to Kind.RESTAURANT, listOf("cafe pub") to Kind.CAFE, listOf("department store") to Kind.SHOP))
            assertEquals(words.toString(), kind, Poi.category(words, "x"))
        // Only the two coordinates and the key go to TomTom.
        val url = Poi.nearbyUrl(42.960123456, -85.660123456, "k")
        assertTrue(url, url.startsWith("https://api.tomtom.com/search/2/nearbySearch/.json?lat=42.96012&lon=-85.66012&radius=80"))
        assertTrue(dev.phonerecall.brain.NetGate.allows(url))
    }

    @Test fun stopsInWordsForAthenaMonetaAndTheReport() {
        var d = stop(Fx.on(), 42.9601, -85.6601, Fx.at(day, 12), Fx.at(day, 12, 18), wegmans)
        d = stop(d, 42.98, -85.61, Fx.at(day, 13), Fx.at(day, 13, 9), PoiHit("Car Wash", null, Kind.CAR_WASH), street = "Maple Ave")
        val facts = Stops.facts(d, Fx.at(day, 15), Fx.zone)
        assertEquals(listOf("Stopped at Wegmans 18 min today", "Stopped at a car wash 9 min today"), facts)
        assertFalse("no streets for Athena", facts.any { it.contains("Cedar") || it.contains("Maple") })
        assertTrue(OdysseyBrain.facts(d, Fx.at(day, 15), Fx.zone).contains("Stopped at Wegmans 18 min today"))
        assertEquals("a charge at Wegmans while there", "Wegmans (Cedar St)", Stops.placeAt(d, Fx.at(day, 12, 10)))
        assertNull(Stops.placeAt(d, Fx.at(day, 12, 40)))
        val drive = DriveReport("r", Fx.at(day, 11, 40), Fx.at(day, 11, 58), 9_000)
        assertEquals("Wegmans (Cedar St), 18 min", Stops.after(d, drive))
        assertTrue(DriveInsights.all(d, drive, null, Fx.at(day, 15), Fx.zone).any { it.label == "Stopped at" && it.text == "Wegmans (Cedar St), 18 min" })
    }
}
