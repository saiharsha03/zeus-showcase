package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Refund watch: promised, then nothing in Moneta for a week. */
class RefundsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(day: Int) = ZonedDateTime.of(2026, 10, day, 10, 0, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun promisedRefundsOnly() {
        assertEquals(Refund("Amazon", 5L), Refunds.parse("com.amazon.mShop.android.shopping", "Refund issued", "Your refund has been issued", 5L))
        assertEquals("Best Buy", Refunds.parse("com.google.android.gm", "Best Buy", "We've refunded your order", 5L)?.shop)
        assertNull(Refunds.parse("com.amazon.mShop.android.shopping", "Delivered", "Your package was delivered", 5L))
        assertNull(Refunds.parse("com.whatsapp", "Alex", "did you get your refund?", 5L))
    }

    @Test fun clearedWhenMonetaSeesIt() {
        val r = Refund("Amazon", ms(at(5)))
        assertEquals(listOf(r), Refunds.waiting(listOf(r), emptyList(), ms(at(13))))
        assertTrue(Refunds.waiting(listOf(r), listOf("AMAZON.COM" to ms(at(9))), ms(at(13))).isEmpty())
        assertTrue(Refunds.waiting(listOf(r.copy(at = ms(at(1)) - 40L * 86_400_000L)), emptyList(), ms(at(13))).isEmpty())
    }

    @Test fun cardAfterAWeek() {
        val r = Refund("Amazon", ms(at(5)))
        assertNull(Refunds.card(MomentContext(at(11), qol = QolSignals(refunds = listOf(r)))))
        val c = MomentRules.pick(MomentContext(at(13), qol = QolSignals(refunds = listOf(r))), MomentsPrefs())!!
        assertEquals(MomentKind.REFUND_WATCH, c.kind)
        assertEquals("Amazon refund not in yet", c.content.title)
        assertEquals("Promised Oct 5; Moneta hasn't seen it.", c.content.body)
        assertEquals("https://www.amazon.com/gp/css/order-history", c.opens["orders"])
    }
}
