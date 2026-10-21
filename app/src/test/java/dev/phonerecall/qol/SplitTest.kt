package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import dev.phonerecall.moneta.Category
import dev.phonerecall.moneta.Txn
import dev.phonerecall.moneta.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Split this charge: dining or groceries in a group setting; the amount stays off the card. */
class SplitTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private fun txn(h: Int, cat: Category = Category.DINING) = Txn(id = 7, at = ms(at(h)), cents = 8_450, kind = TxnKind.SPEND, merchant = "Sample Grill", category = cat)

    @Test fun groupSettingsOnly() {
        val dinner = CalEvent("Team dinner", ms(at(12)), ms(at(13, 30)), "Sample Grill")
        assertEquals(SplitOffer("local:7", "Sample Grill", 8_450, "Team dinner"), Split.offer(listOf(txn(13)), listOf(dinner), ms(at(14)), zone))
        assertEquals(SplitOffer("local:7", "Sample Grill", 8_450, null), Split.offer(listOf(txn(20)), emptyList(), ms(at(21)), zone))
        // A lunch alone, an old charge, or a fuel stop: no.
        assertNull(Split.offer(listOf(txn(13)), emptyList(), ms(at(14)), zone))
        assertNull(Split.offer(listOf(txn(13)), listOf(dinner), ms(at(18)), zone))
        assertNull(Split.offer(listOf(txn(20, Category.FUEL)), emptyList(), ms(at(21)), zone))
    }

    @Test fun theCardNeverShowsTheAmount() {
        val c = MomentRules.pick(MomentContext(at(14), qol = QolSignals(split = SplitOffer("local:7", "Sample Grill", 8_450, "Team dinner"))), MomentsPrefs())!!
        assertEquals(MomentKind.SPLIT, c.kind)
        assertEquals("Split Sample Grill from Team dinner?", c.content.title)
        assertFalse(c.content.toString().contains("84"))
        assertEquals("app:${Split.SPLITWISE}", c.opens["split"])
        assertEquals("84.50", Split.amount(8_450))
        assertEquals("0.05", Split.amount(5))
    }
}
