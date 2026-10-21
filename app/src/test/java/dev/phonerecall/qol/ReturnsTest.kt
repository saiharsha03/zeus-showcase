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

/** Return windows from shops' notices: from 3 days before the last day. */
class ReturnsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int) = ZonedDateTime.of(2026, 10, day, 10, 0, 0, 0, zone)

    @Test fun statedWindowsOnly() {
        assertEquals(ReturnBy("Amazon", "2026-11-19", 5L), Returns.parse("com.amazon.mShop.android.shopping", "Delivered", "Eligible for return through Nov 19", 5L, at(20).toLocalDate()))
        assertEquals("2026-10-30", Returns.parse("com.google.android.gm", "Best Buy", "Your order arrives Oct 22. Return by Oct 30.", 5L, at(20).toLocalDate())?.by)
        assertNull(Returns.parse("com.amazon.mShop.android.shopping", "Delivered", "Your package was delivered", 5L, at(20).toLocalDate()))
    }

    @Test fun cardFromThreeDaysBefore() {
        val r = ReturnBy("Amazon", "2026-10-30", 5L)
        assertNull(Returns.card(MomentContext(at(26), qol = QolSignals(returns = listOf(r)))))
        val c = MomentRules.pick(MomentContext(at(27), qol = QolSignals(returns = listOf(r))), MomentsPrefs())!!
        assertEquals(MomentKind.RETURN_BY, c.kind)
        assertEquals("Return by Fri, Oct 30", c.content.title)
        assertEquals("Return by today", Returns.card(MomentContext(at(30), qol = QolSignals(returns = listOf(r))))!!.content.title)
        assertNull(Returns.card(MomentContext(at(31), qol = QolSignals(returns = listOf(r)))))
    }
}
