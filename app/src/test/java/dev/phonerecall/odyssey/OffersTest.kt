package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

/** "Looks like Costco on Maple Ave. Show Groceries when you arrive?" after the 2nd visit, once. */
class OffersTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val lists = listOf(1L to "Groceries", 2L to "Hardware")
    private val day = LocalDate.of(2026, 10, 4)

    private fun costco(d: OdysseyData, week: Int) =
        Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(day.plusDays(7L * week), 11), Fx.at(day.plusDays(7L * week), 11, 45), hints = listOf("You spent $82.14 at COSTCO WHSE #1234"), street = "Maple Ave")

    @Test fun offeredAfterTheSecondVisitWithTheMatchingList() {
        val one = costco(Fx.on(), 0)
        assertTrue("one visit is not a habit", Offers.due(one, lists).isEmpty())
        val two = costco(one, 1)
        val o = Offers.due(two, lists).single()
        assertEquals("Looks like Costco on Maple Ave. Show Groceries when you arrive?", o.text)
        assertEquals(listOf(1L), o.lists)
        // On Olympus, as a card with Yes / Not now.
        val card = OdysseyCards.offer(two, lists)!!
        assertEquals(o.text, card.title)
        assertEquals(listOf("Yes", "Not now"), card.actions.map { it.label })
        assertEquals(o.place.id, card.actions.first().payload)
    }

    @Test fun yesSetsTheGeofenceOnceAndNotNowNeverAsksAgain() {
        val two = costco(costco(Fx.on(), 0), 1)
        val id = Offers.due(two, lists).single().place.id
        val yes = Offers.mark(two, id, OfferState.ACCEPTED, "o$id")
        assertTrue(Offers.due(costco(yes, 2), lists).isEmpty())
        assertEquals("o$id", yes.place(id)!!.geofence)
        val no = Offers.mark(two, id, OfferState.DECLINED)
        assertTrue(Offers.due(costco(costco(no, 2), 3), lists).isEmpty())
    }

    @Test fun unnamedPlacesAndHomeAreNeverOffered() {
        var d = Fx.on()
        repeat(3) { d = Fx.stay(d, 42.99, -85.63, Fx.at(day.plusDays(it.toLong()), 15), Fx.at(day.plusDays(it.toLong()), 15, 30), street = "Main St") }
        assertTrue(Offers.due(d, lists).isEmpty())
        assertTrue(Offers.due(Fx.officeWeeks(3), lists).isEmpty())
    }

    @Test fun aStoreWithNoMatchingListIsOfferedAsAPlace() {
        var d = Fx.on()
        repeat(2) { d = Fx.stay(d, 42.97, -85.66, Fx.at(day.plusDays(7L * it), 9), Fx.at(day.plusDays(7L * it), 10), hints = listOf("Purchase at PLANET FITNESS #33")) }
        val o = Offers.due(d, lists).single()
        assertEquals("Looks like Planet Fitness. Keep it as a place?", o.text)
        assertTrue(o.lists.isEmpty())
    }

    @Test fun noCardsWhenOdysseyIsOff() {
        val d = costco(costco(Fx.on(), 0), 1).copy(settings = OdysseySettings(on = false))
        assertNull(OdysseyCards.home(d, lists, ZonedDateTime.now(Fx.zone)))
    }
}
