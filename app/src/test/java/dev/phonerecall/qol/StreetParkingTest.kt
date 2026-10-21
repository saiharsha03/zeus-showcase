package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Alternate-side street parking, with a synthetic rule (north from Tuesday 9 AM, south from Friday 9 AM). */
class StreetParkingTest {
    private val zone = ZoneId.of("America/New_York")
    // 2026-10-20 is a Tuesday; the 23rd a Friday.
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val rule = StreetParking.parse("north Tue 9 AM, south Fri 9 AM", car = "the hatchback")!!.copy(turnAround = true)

    @Test fun parsesHisWordsAndAColleaguesSentence() {
        assertEquals(listOf(SideWindow("north", 2, 540), SideWindow("south", 5, 540)), rule.windows)
        assertEquals("north Tue 9:00 AM, south Fri 9:00 AM", StreetParking.words(rule))
        val iris = StreetParking.parse("Street parking: east side Wed 7 PM–Sat 7 PM, west side Sat 7 PM–Wed 7 PM. Park facing the direction of travel.")!!
        assertEquals(listOf(SideWindow("east", 3, 1140), SideWindow("west", 6, 1140)), iris.windows)
        assertTrue(iris.turnAround)
        assertNull(StreetParking.parse("north Tue 9 AM"))
        assertNull(StreetParking.parse("see you at 9 pm"))
    }

    @Test fun fortyFiveMinutesBeforeTheFlipWhenTheCarIsHome() {
        val r = StreetParking.reminder(rule, at(23, 8, 30), carHome = true, out = false, moved = 0)!!
        assertEquals(ms(at(23, 9)), r.flipAt)
        assertEquals("Move the hatchback to the SOUTH side · turn it around", r.words)
        assertNull(StreetParking.reminder(rule, at(23, 8, 0), carHome = true, out = false, moved = 0))
        assertNull(StreetParking.reminder(rule, at(23, 8, 30), carHome = false, out = false, moved = 0))
        assertNull(StreetParking.reminder(null, at(23, 8, 30), carHome = true, out = false, moved = 0))
        // Moved since the reminder began: cleared. Moved before it: still up.
        assertNull(StreetParking.reminder(rule, at(23, 8, 30), carHome = true, out = false, moved = ms(at(23, 8, 20))))
        assertEquals("south", StreetParking.reminder(rule, at(23, 8, 30), carHome = true, out = false, moved = ms(at(23, 8, 0)))?.side)
    }

    @Test fun outWithoutTheCarItSaysBeforeYouSleep() {
        val r = StreetParking.reminder(rule, at(23, 11), carHome = true, out = true, moved = 0)!!
        assertEquals("Move the hatchback to the SOUTH side before you sleep · turn it around", r.words)
        assertNull(StreetParking.reminder(rule, at(23, 11), carHome = true, out = false, moved = 0))
        assertNull(StreetParking.reminder(rule, at(23, 16), carHome = true, out = true, moved = 0))
    }

    @Test fun theCardAndTheNextFlip() {
        assertEquals(at(23, 9) to "south", StreetParking.nextFlip(rule, at(20, 10)))
        assertEquals(at(27, 9) to "north", StreetParking.nextFlip(rule, at(23, 9, 30)))
        val s = StreetParking.reminder(rule, at(23, 8, 30), carHome = true, out = false, moved = 0)
        val c = MomentRules.pick(MomentContext(at(23, 8, 30), qol = QolSignals(street = s)), MomentsPrefs())!!
        assertEquals(MomentKind.STREET_PARKING, c.kind)
        assertEquals("Move the hatchback to the SOUTH side · turn it around", c.content.title)
        assertEquals("The side flips at 9:00 AM.", c.content.body)
        assertEquals(listOf("Done", "Never"), c.content.actions.map { it.label })
        // No street names, no fines.
        assertFalse(c.content.toString().contains("fine", ignoreCase = true))
    }

    @Test fun irisRuleIsOfferedOnce() {
        val c = MomentRules.pick(MomentContext(at(20, 12), qol = QolSignals(streetOffer = rule)), MomentsPrefs())!!
        assertEquals(MomentKind.STREET_SEED, c.kind)
        assertEquals("Use Iris's parking rule for Home?", c.content.title)
        assertEquals(listOf("Use it", "No"), c.content.actions.map { it.label })
    }
}
