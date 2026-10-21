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

/** The afternoon walk nudge: office day, quiet on foot, never a count. */
class StepsTest {
    private fun at(h: Int) = ZonedDateTime.of(2026, 10, 20, h, 10, 0, 0, ZoneId.of("America/New_York"))

    @Test fun onlyAQuietOfficeAfternoon() {
        assertTrue(Steps.due(true, officeDay = true, hour = 15))
        assertFalse(Steps.due(false, officeDay = true, hour = 15))
        assertFalse(Steps.due(null, officeDay = true, hour = 15))
        assertFalse(Steps.due(true, officeDay = false, hour = 15))
        assertFalse(Steps.due(true, officeDay = true, hour = 12))
    }

    @Test fun walkTenMinutesWithTheWeather() {
        val c = MomentRules.pick(MomentContext(at(15), qol = QolSignals(steps = StepsNudge("54°F, Cloudy"))), MomentsPrefs())!!
        assertEquals(MomentKind.STEPS, c.kind)
        assertEquals("Walk 10 min?", c.content.title)
        assertEquals("A quiet day on foot so far. Outside: 54°F, Cloudy.", c.content.body)
        assertFalse(c.content.toString().contains("1500") || c.content.toString().contains("1,500"))
        assertNull(Steps.card(MomentContext(at(15), driving = true, qol = QolSignals(steps = StepsNudge()))))
    }
}
