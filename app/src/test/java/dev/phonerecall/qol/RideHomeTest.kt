package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** A ride home after a late night out. */
class RideHomeTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0, day: Int = 20) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val home = 1.5 to 2.5

    @Test fun lateEventsOnly() {
        val game = CalEvent("Game", ms(at(19)), ms(at(22, 40)), "Sample Arena")
        assertEquals(LateOut("Game", ms(at(22, 40))), RideHome.lateEvent(listOf(game), ms(at(22, 50)), zone))
        // Ended before 10 PM, over an hour ago, or an online meeting: no.
        assertNull(RideHome.lateEvent(listOf(game.copy(end = ms(at(21, 30)))), ms(at(21, 40)), zone))
        assertNull(RideHome.lateEvent(listOf(game), ms(at(23, 50)), zone))
        assertNull(RideHome.lateEvent(listOf(game.copy(location = "https://zoom.us/j/1")), ms(at(22, 50)), zone))
        // A long visit he just walked out of after 10.
        assertEquals(LateOut(null, ms(at(23, 5))), RideHome.lateVisit(ms(at(20)), ms(at(23, 5)), ms(at(23, 10)), zone))
        assertNull(RideHome.lateVisit(ms(at(22, 30)), ms(at(23, 5)), ms(at(23, 10)), zone))
    }

    @Test fun uberWithHomeAsDropoffAndLyftWhenInstalled() {
        val ctx = MomentContext(at(22, 50), atHome = false, qol = QolSignals(late = LateOut("Game", ms(at(22, 40))), rides = Rides(RideHome.uber(home, true), RideHome.lyft(home))))
        val c = MomentRules.pick(ctx, MomentsPrefs())!!
        assertEquals(MomentKind.RIDE_HOME, c.kind)
        assertEquals("Game ended at 10:40 PM. You didn't drive here.", c.content.body)
        assertEquals(listOf("Uber home", "Lyft", "Not now"), c.content.actions.map { it.label })
        assertEquals("uber://?action=setPickup&pickup=my_location&dropoff[latitude]=1.5&dropoff[longitude]=2.5&dropoff[nickname]=Home", c.opens["uber"])
        assertEquals("lyft://ridetype?id=lyft&destination[latitude]=1.5&destination[longitude]=2.5", c.opens["lyft"])
        assertEquals("https://m.uber.com/ul/?action=setPickup&pickup=my_location", RideHome.uber(null, false))
        // Shows after 11 PM too (quiet hours), but not driving, home, or on a call.
        assertEquals(MomentKind.RIDE_HOME, MomentRules.pick(ctx.copy(now = at(23, 20)), MomentsPrefs())?.kind)
        assertNull(RideHome.card(ctx.copy(driving = true)))
        assertNull(RideHome.card(ctx.copy(atHome = true)))
        assertNull(RideHome.card(ctx.copy(qol = ctx.qol.copy(busy = true))))
    }
}
