package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.TimeZone

/** Naming a place from receipts, the Wi-Fi and the Geocoder; kinds and auto-logs; shared Maps links. */
class NamingTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    @Test fun merchantsFromPaymentNotifications() {
        assertEquals("Costco", Naming.merchant("You spent $82.14 at COSTCO WHSE #1234 with your card ending 5521"))
        assertEquals("Delta Sonic", Naming.merchant("Purchase of $14.00 at DELTA SONIC CAR WASH on 10/12"))
        assertEquals("Starbucks", Naming.merchant("Your receipt from Starbucks"))
        assertEquals("Patel Brothers", Naming.merchant("Transaction alert: $43.50 at PATEL BROTHERS 0042."))
        assertEquals("Shell", Naming.merchant("Payment to SHELL OIL 57444 was approved"))
        assertEquals("Wegmans", Naming.merchant("Thanks for shopping at Wegmans! Your order total"))
        assertEquals(82.14, Naming.amount("You spent $82.14 at COSTCO WHSE #1234")!!, 1e-9)
        // Chat about a store is not a receipt.
        assertNull(Naming.merchant("meet you at Costco at 5?"))
        assertNull(Naming.amount("it was $5 cheaper there"))
    }

    @Test fun unknownMerchantsAreTidied() {
        assertEquals("Hilltop Temple", Naming.clean("HILLTOP TEMPLE 0099"))
        assertEquals("BJ Tavern", Naming.clean("BJ TAVERN #12"))
    }

    @Test fun kindsFromNames() {
        assertEquals(Kind.GROCERY, Naming.kind("Costco"))
        assertEquals(Kind.CAR_WASH, Naming.kind("Delta Sonic"))
        assertEquals(Kind.TEMPLE, Naming.kind("Riverside Cultural Center"))
        assertEquals(Kind.GYM, Naming.kind("Planet Fitness"))
        assertEquals(Kind.FUEL, Naming.kind("Kwik Fill"))
        assertEquals(Kind.CAFE, Naming.kind("Tim Hortons"))
        assertEquals(Kind.OTHER, Naming.kind("Near Oak Ave"))
    }

    @Test fun bestSourceWins() {
        val s = OpenStay(hints = listOf("Costco"), wifi = "Costco-Guest", poi = "Costco Wholesale", street = "Maple Ave")
        assertEquals("Costco" to NameSource.HINT, Naming.pick(null, s))
        assertEquals("Costco" to NameSource.WIFI, Naming.pick(null, s.copy(hints = emptyList())))
        assertEquals("Costco" to NameSource.GEOCODER, Naming.pick(null, s.copy(hints = emptyList(), wifi = "NETGEAR42")))
        // A Geocoder "name" that is just the house number is not a name.
        assertEquals("Near Maple Ave" to NameSource.NONE, Naming.pick(null, s.copy(hints = emptyList(), wifi = null, poi = "1585")))
        // His own name is never replaced by a receipt.
        val his = KnownPlace("k1", "Bulk run", NameSource.HIM)
        assertEquals("Bulk run" to NameSource.HIM, Naming.pick(his, s))
        // A receipt-named place keeps its name on a visit with no receipt.
        val learned = KnownPlace("k1", "Costco", NameSource.HINT)
        assertEquals("Costco" to NameSource.HINT, Naming.pick(learned, s.copy(hints = emptyList(), wifi = null, poi = null)))
    }

    @Test fun aReceiptDuringTheStayNamesTheVisitAndLogsIt() {
        val day = LocalDate.of(2026, 10, 10)
        val d = Fx.stay(Fx.on(), 42.98, -85.60, Fx.at(day, 11, 20), Fx.at(day, 11, 34), hints = listOf("You spent $14.00 at DELTA SONIC CAR WASH"), street = "Birch Dr")
        val p = d.places.single()
        assertEquals("Delta Sonic", p.name)
        assertEquals(Kind.CAR_WASH, p.kind)
        assertEquals(14.0, d.visits.single().spent!!, 1e-9)
        val log = Patterns.logs(d).single()
        assertEquals(Kind.CAR_WASH, log.kind)
        assertEquals(14L, log.visit.minutes)
    }

    @Test fun habitsByWeekday() {
        var d = Fx.on()
        var day = LocalDate.of(2026, 9, 6) // a Sunday
        repeat(5) {
            d = Fx.stay(d, Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(day, 10, 5), Fx.at(day, 11, 30), poi = "Riverside Cultural Center")
            day = day.plusDays(7)
        }
        val h = Patterns.habits(d, Fx.at(day, 12), Fx.zone)
        assertEquals(listOf("Temple every Sunday ~10 AM"), h.map { it.text })
    }

    @Test fun sharedMapsLinksAreReadOnThePhone() {
        val full = MapsLink.parse("Costco Wholesale\nhttps://www.google.com/maps/place/Costco+Wholesale/@43.0012,-85.6197,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d43.00125!4d-85.61968")!!
        assertEquals("Costco Wholesale", full.name)
        assertEquals(43.00125, full.lat!!, 1e-9)
        assertEquals(-85.61968, full.lng!!, 1e-9)
        val short = MapsLink.parse("Patel Brothers\n100 Maple Ave, Grand Rapids, MI 49503\nhttps://maps.app.goo.gl/AbCdEf12345")!!
        assertEquals("Patel Brothers", short.name)
        assertNull("a short link has no coordinates; the address goes to the Geocoder", short.lat)
        assertEquals("100 Maple Ave, Grand Rapids, MI 49503", short.address)
        assertEquals(42.95, MapsLink.parse("Here geo:42.95,-85.69")!!.lat!!, 1e-9)
        assertNull(MapsLink.parse("just a note about groceries"))
    }
}
