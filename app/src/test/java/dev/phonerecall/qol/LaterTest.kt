package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.moments.MomentKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** "Later" on a message card: the fitting time, and when it comes back. */
class LaterTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun theFittingChoice() {
        assertEquals(Later.When.MEETING, Later.pick(ms(at(11)), atHome = false, now = at(10, 20)))
        assertEquals(Later.When.HOME, Later.pick(null, atHome = false, now = at(10, 20)))
        assertEquals(Later.When.SIX, Later.pick(null, atHome = true, now = at(10, 20)))
        assertEquals(Later.When.SIX, Later.pick(null, atHome = null, now = at(10, 20)))
        assertEquals(Later.When.TWO, Later.pick(null, atHome = true, now = at(19)))
    }

    @Test fun whenItComesBack() {
        assertEquals(ms(at(11, 2)), Later.until(Later.When.MEETING, ms(at(11)), at(10, 20)))
        assertEquals(ms(at(18)), Later.until(Later.When.SIX, null, at(10, 20)))
        assertEquals(ms(at(21)), Later.until(Later.When.TWO, null, at(19)))
        assertEquals(ms(at(22, 20)), Later.until(Later.When.HOME, null, at(10, 20)))
    }

    @Test fun theChip() {
        val a = Later.chip("gen:sense-msg:1-5", Later.When.SIX)
        assertEquals("Later, 6 PM", a.label)
        assertEquals(ActionRole.MOMENT_GO, a.role)
        assertEquals("${MomentKind.MESSAGE_LATER.name}|gen:sense-msg:1-5|SIX", a.payload)
    }
}
