package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The small touches: when each comes up, and the quiet rules. */
class QolTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0, day: Int = 20) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    // ---- where he parked ----

    private fun parked(now: ZonedDateTime, ago: Long = 30 * 60_000L, big: Boolean = true) =
        MomentContext(now, odysseyOn = true, atHome = false, qol = QolSignals(car = CarSpot(ms(now) - ago, "City Stadium", big, QolRules.walkTo(42.77, -78.78))))

    @Test fun carSpotAtABigLotWithNavigateAndSnap() {
        val c = MomentRules.pick(parked(at(13)), MomentsPrefs())!!
        assertEquals(MomentKind.CAR_SPOT, c.kind)
        assertEquals("Parked · 12:30 PM", c.content.title)
        assertEquals(listOf("Navigate to car", "Snap the sign", "1h meter", "2h meter"), c.content.actions.map { it.label })
        assertEquals("google.navigation:q=42.77,-78.78&mode=w", c.opens["walk"])
        assertEquals(Opens.CAMERA, c.opens["snap"])
        // Every option leaves the card up; a swipe or driving off clears it.
        assertEquals(setOf("walk", "snap", "m60", "m120"), c.keep)
    }

    @Test fun carSpotQuiet() {
        assertNull(MomentRules.pick(parked(at(13), big = false), MomentsPrefs()))
        assertNull(MomentRules.pick(parked(at(13)).copy(atHome = true), MomentsPrefs()))
        assertNull(MomentRules.pick(parked(at(13)).copy(driving = true), MomentsPrefs()))
        assertNull(MomentRules.pick(parked(at(13)).let { it.copy(qol = it.qol.copy(busy = true)) }, MomentsPrefs()))
        assertNull(MomentRules.pick(parked(at(13), ago = 15 * 86_400_000L), MomentsPrefs()))
        assertNull(MomentRules.pick(parked(at(13)), MomentsPrefs(never = setOf(MomentKind.CAR_SPOT))))
    }

    @Test fun carSpotStillShowsLateAtNight() {
        val c = MomentRules.pick(parked(at(23, 40)), MomentsPrefs())
        assertEquals(MomentKind.CAR_SPOT, c?.kind)
        assertEquals("Parked · 11:10 PM", c?.content?.title)
        // Another day: the weekday is in the title.
        assertEquals("Parked · Sat 9:00 AM", QolRules.carSpot(parked(at(13), ago = ms(at(13)) - ms(at(9, day = 17))))!!.content.title)
    }

    @Test fun bigLotsFromTheGeocodersNames() {
        assertTrue(QolRules.bigLot("Buffalo Niagara International Airport", null))
        assertTrue(QolRules.bigLot(null, "City Stadium"))
        assertTrue(QolRules.bigLot("Riverside Galleria", "River Rd"))
        assertTrue(QolRules.bigLot("Pine St Parking Ramp", null))
        assertFalse(QolRules.bigLot(null, "Oak Street Ave"))
        assertFalse(QolRules.bigLot(null, null))
    }

    @Test fun whichParkSignalsKeepTheSpot() {
        val first = ParkedCar(ms(at(12)), 0.0, 0.0, null, true)
        assertTrue(QolRules.keepPark(null, ms(at(12)), nearHomeOrWork = false))
        // Bluetooth off, then the drive ending: one park.
        assertFalse(QolRules.keepPark(first, ms(at(12, 5)), nearHomeOrWork = false))
        assertTrue(QolRules.keepPark(first, ms(at(14)), nearHomeOrWork = false))
        assertFalse(QolRules.keepPark(null, ms(at(12)), nearHomeOrWork = true))
        // A drive ending moves the car only if it was the car (its Bluetooth in the last 3 h), or no car is linked.
        assertTrue(QolRules.driveWasCar(0L, ms(at(12))))
        assertTrue(QolRules.driveWasCar(ms(at(11)), ms(at(12))))
        assertFalse(QolRules.driveWasCar(ms(at(7)), ms(at(12))))
    }

    @Test fun meterNudgesTenMinutesBefore() {
        assertEquals(ms(at(13, 50)), Meter.nudgeAt(ms(at(12)), 120))
        assertEquals(ms(at(12)), Meter.nudgeAt(ms(at(12)), 5))
    }

    @Test fun theChipFitsTheStatusBar() {
        val chip = QolRules.chip(ms(at(19, 12)), at(20))
        assertEquals("Parked 7:12", chip)
        assertTrue(chip.length <= 12)
        assertNotNull(QolRules.chip(ms(at(10, 5)), at(20)))
    }

    // ---- leave for an event ----

    private val concert = CalEvent("Concert", 0, 0, "Sample Arena, 1 Example Way")
    private fun leaving(now: ZonedDateTime, begin: ZonedDateTime, checks: List<dev.phonerecall.odyssey.RoadCheck> = emptyList()) =
        MomentContext(now, qol = QolSignals(leave = QolRules.nextLeave(listOf(concert.copy(begin = ms(begin), end = ms(begin) + 3_600_000L)), checks, ms(now))))

    @Test fun navigateFromFifteenMinutesBeforeLeaveBy() {
        assertNull(QolRules.navigate(leaving(at(18, 10), at(19))))
        val c = MomentRules.pick(leaving(at(18, 20), at(19)), MomentsPrefs())!!
        assertEquals(MomentKind.NAVIGATE, c.kind)
        assertEquals("Leave by 6:30 PM", c.content.title)
        assertEquals("Concert at 7:00 PM.", c.content.body)
        assertEquals("google.navigation:q=Sample+Arena%2C+1+Example+Way", c.opens["nav"])
        assertEquals("Leave now", MomentRules.pick(leaving(at(18, 40), at(19)), MomentsPrefs())!!.content.title)
        // Started: gone.
        assertNull(QolRules.navigate(leaving(at(19, 1), at(19))))
    }

    @Test fun navigateUsesOdysseysTrafficCheck() {
        val rc = dev.phonerecall.odyssey.RoadCheck(key = "event:${"Concert".hashCode()}:${ms(at(19))}", leaveBy = ms(at(18, 5)), delayMinutes = 12)
        val c = MomentRules.pick(leaving(at(17, 55), at(19), listOf(rc)), MomentsPrefs())!!
        assertEquals("Leave by 6:05 PM", c.content.title)
        assertEquals("Concert at 7:00 PM. Traffic adds 12 min.", c.content.body)
    }

    @Test fun navigateQuietAndOnlineMeetings() {
        assertNull(MomentRules.pick(leaving(at(18, 20), at(19)).copy(driving = true), MomentsPrefs()))
        assertNull(MomentRules.pick(leaving(at(18, 20), at(19)).let { it.copy(qol = it.qol.copy(busy = true)) }, MomentsPrefs()))
        assertNull(QolRules.nextLeave(listOf(CalEvent("Standup", ms(at(19)), ms(at(19, 30)), "https://teams.microsoft.com/l/x")), emptyList(), ms(at(18))))
        assertNull(QolRules.nextLeave(listOf(CalEvent("Sync", ms(at(19)), ms(at(19, 30)), "Zoom: zoom.us/j/1")), emptyList(), ms(at(18))))
        // An early start in quiet hours still gets its card.
        assertEquals(MomentKind.NAVIGATE, MomentRules.pick(leaving(at(5, 20), at(6)), MomentsPrefs())?.kind)
    }

    // ---- charge before leaving ----

    @Test fun chargeBeforeTheNextLeaveBy() {
        val ctx = leaving(at(17, 40), at(19)).copy(battery = 30)
        val c = MomentRules.pick(ctx, MomentsPrefs())!!
        assertEquals(MomentKind.BATTERY, c.kind)
        assertEquals("Charge now: 30%", c.content.title)
        assertEquals("Leave by 6:30 PM for Concert.", c.content.body)
        assertNull(MomentRules.battery(ctx.copy(battery = 35)))
        assertNull(MomentRules.battery(ctx.copy(charging = true)))
        // More than an hour before leave-by: not yet.
        assertNull(MomentRules.battery(leaving(at(17, 10), at(19)).copy(battery = 30)))
        assertNull(MomentRules.battery(ctx.copy(qol = ctx.qol.copy(busy = true))))
    }
}
