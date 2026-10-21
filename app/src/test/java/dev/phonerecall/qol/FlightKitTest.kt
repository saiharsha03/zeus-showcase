package dev.phonerecall.qol

import dev.phonerecall.argo.Leg
import dev.phonerecall.argo.LegMode
import dev.phonerecall.argo.Trip
import dev.phonerecall.argo.TripStatus
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The flight-day kit from Argo's legs. */
class FlightKitTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val trip = Trip(id = "t1", place = "Sample City", status = TripStatus.PLANNED,
        legs = listOf(Leg(id = "l1", mode = LegMode.FLIGHT, from = "Home (AAA)", to = "Sample City (BBB)", departAt = ms(at(15)), carrier = "Delta", number = "DL 100", platform = "Gate B12")))

    private fun kit(now: ZonedDateTime, installed: Set<String>) = FlightKit.today(listOf(trip), ms(now), zone) { it in installed }

    @Test fun windowIsNinetyMinutesBeforeLeaveByUntilDeparture() {
        // Leave-by 1:00 PM (two hours before a flight): from 11:30 AM.
        assertNull(kit(at(11, 0), emptySet()))
        assertEquals("l1", kit(at(11, 45), emptySet())?.legId)
        assertNull(kit(at(15, 1), emptySet()))
    }

    @Test fun boardingPassAirlineGateLeaveBy() {
        val f = kit(at(12), setOf("com.delta.mobile.android", "com.samsung.android.spay"))!!
        val c = MomentRules.pick(MomentContext(at(12), qol = QolSignals(flight = f)), MomentsPrefs())!!
        assertEquals(MomentKind.FLIGHT_KIT, c.kind)
        assertEquals("Flight kit: Delta DL 100", c.content.title)
        assertEquals(listOf("Departs" to "3:00 PM", "Gate" to "Gate B12", "Leave by" to "1:00 PM"), c.content.facts.map { it.label to it.value })
        assertEquals(listOf("Boarding pass", "Open Delta", "Got it"), c.content.actions.map { it.label })
        assertEquals("app:com.samsung.android.spay", c.opens["pass"])
        assertEquals(setOf("pass", "airline"), c.keep)
        // Nothing installed: just the facts and Got it.
        assertEquals(listOf("Got it"), FlightKit.card(MomentContext(at(12), qol = QolSignals(flight = kit(at(12), emptySet()))))!!.content.actions.map { it.label })
    }

    @Test fun airlineFromCodeOrName() {
        assertEquals("United", FlightKit.airline("", "UA 812")?.first)
        assertEquals("JetBlue", FlightKit.airline("JetBlue Airways", "")?.first)
        assertNull(FlightKit.airline("Sample Air", "ZZ 1"))
    }
}
