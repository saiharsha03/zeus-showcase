package dev.phonerecall.odyssey

import dev.phonerecall.brain.NetGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * TomTom's calculateRoute and 511NY's getevents, against fixtures shaped like the providers' documented
 * examples (this laptop's network intercepts TLS for api.tomtom.com, so nothing here calls them live).
 */
class TrafficTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("odyssey/$name")!!.readText(Charsets.UTF_8)

    @Test fun parsesTheMainRouteAndTheAlternative() {
        val r = TomTom.parse(fixture("tomtom_route_traffic.json"))
        assertEquals(2, r.size)
        assertEquals(1820, r[0].seconds)
        assertEquals(840, r[0].delaySeconds)
        assertEquals(13140, r[0].meters)
        // The crash section (TEC cause 2) and the road work; the 20 s slowdown is not worth a line.
        assertEquals(listOf("Crash, +13 min", "Road work, +1 min"), r[0].incidents)
        assertEquals(1500, r[1].seconds)
        assertTrue(r[1].incidents.isEmpty())
    }

    @Test fun anErrorResponseThrowsWithItsReason() {
        try { TomTom.parse(fixture("tomtom_error.json")); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("NO_ROUTE_FOUND")) }
    }

    @Test fun theUrlCarriesOnlyTheTwoEnds() {
        val url = TomTom.routeUrl(42.955012345, -85.69, 42.8864, -85.6784, "k3y/with+chars")
        assertTrue(url.startsWith("https://api.tomtom.com/routing/1/calculateRoute/42.95501,-85.69000:42.88640,-85.67840/json?"))
        assertTrue(url.contains("key=k3y%2Fwith%2Bchars"))
        assertTrue(url.contains("traffic=true") && url.contains("maxAlternatives=1"))
        assertEquals("exactly two coordinate pairs", 4, Regex("""-?\d{1,3}\.\d{5}""").findAll(url).count())
        assertTrue(NetGate.allows(url))
    }

    @Test fun ny511SendsNoLocationAndFiltersToTheCorridor() {
        val url = Ny511.eventsUrl("abc123")
        assertEquals("https://511ny.org/api/getevents?key=abc123&format=json", url)
        assertTrue(NetGate.allows(url))
        val events = Ny511.parse(fixture("ny511_events.json"))
        assertEquals("the one without coordinates is skipped", 3, events.size)
        val on = Ny511.onRoute(events, Fx.HOME_LAT, Fx.HOME_LNG, Fx.WORK_LAT, Fx.WORK_LNG)
        // Brooklyn road work is far away; the special event is not a road problem.
        assertEquals(listOf("Crash on I-190 S near exit 7"), on)
    }

    @Test fun netGateAllowsExactlyTheTwoRoadHosts() {
        assertEquals(setOf("api.tomtom.com", "511ny.org"), NetGate.ROAD_HOSTS)
        assertTrue(NetGate.allows("https://api.tomtom.com/routing/1/calculateRoute/1,2:3,4/json"))
        assertFalse(NetGate.allows("http://api.tomtom.com/routing/1/calculateRoute/1,2:3,4/json"))
        assertFalse(NetGate.allows("https://api.tomtom.com.evil.com/"))
        assertFalse(NetGate.allows("https://tiles.tomtom.com/map/1/tile/basic/main/1/0/0.png"))
        assertFalse(NetGate.allows("https://www.511ny.org/api/getevents"))
        assertFalse(NetGate.allows("https://api.tomtom.com:8443/"))
    }
}
