package dev.phonerecall.qol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Ride and delivery notifications read into one Live Update chip. */
class LiveTripTest {
    private val zone = ZoneId.of("America/New_York")
    private val at = ZonedDateTime.of(2026, 10, 20, 19, 30, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun aRideArriving() {
        val t = LiveTrips.parse("com.ubercab", "Your driver is arriving in 4 min", "Gray Toyota Camry · ABC1234. Your PIN is 5821", at, zone)!!
        assertTrue(t.ride)
        assertEquals(at + 4 * 60_000L, t.etaAt)
        assertEquals("Gray Toyota Camry", t.car)
        assertEquals("ABC1234", t.plate)
        assertEquals("5821", t.pin)
        assertEquals("Uber arriving · 4 min", LiveTrips.title(t, at))
        assertEquals("Gray Toyota Camry · ABC1234 · PIN 5821", LiveTrips.details(t))
        assertEquals("Uber 4m", LiveTrips.chip(t, at))
        assertEquals("Uber is here", LiveTrips.title(t, at + 5 * 60_000L))
    }

    @Test fun aDeliveryOnTheWay() {
        val t = LiveTrips.parse("com.dd.doordash", "Your order is on the way", "Arriving at 7:52 PM", at, zone)!!
        assertEquals(ZonedDateTime.of(2026, 10, 20, 19, 52, 0, 0, zone).toInstant().toEpochMilli(), t.etaAt)
        assertNull(t.plate)
        assertEquals("DoorDash on the way · 22 min", LiveTrips.title(t, at))
    }

    @Test fun otherAppsAndOtherNews() {
        assertNull(LiveTrips.parse("com.whatsapp", "Alex", "on the way!", at, zone))
        assertNull(LiveTrips.parse("com.ubercab", "Your receipt", "Thanks for riding", at, zone))
    }
}
