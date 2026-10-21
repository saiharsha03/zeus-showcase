package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Bill due pins: parsed, cleared by a payment, never pays. */
class BillsTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, zone)
    private val today: LocalDate = now.toLocalDate()
    private val ms = now.toInstant().toEpochMilli()

    @Test fun dueNoticesOnly() {
        val b = Bills.parse("com.bank.sample", "Sample Bank", "Your payment is due", "Minimum payment due Oct 25", ms, today)!!
        assertEquals("Sample Bank", b.bank)
        assertEquals("2026-10-25", b.due)
        assertNull(Bills.parse("com.bank.sample", "Sample Bank", "Payment received", "Thank you for your payment", ms, today))
        assertNull(Bills.parse("com.whatsapp", "WhatsApp", "Alex", "lunch?", ms, today))
        assertEquals("Statement ready", Bills.parse("com.bank.sample", "Sample Bank", "Statement ready", "Your statement is available", ms, today)?.let { "Statement ready" })
    }

    @Test fun clearedByAPaymentOrAfterTheDueDate() {
        val b = Bill("Sample Bank", "com.bank.sample", "2026-10-25", ms - 86_400_000L)
        assertEquals(listOf(b), Bills.open(listOf(b), emptyList(), today, ms))
        assertTrue(Bills.open(listOf(b), listOf("SAMPLE BANK" to ms), today, ms).isEmpty())
        assertEquals(listOf(b), Bills.open(listOf(b), listOf("Other Bank" to ms), today, ms))
        assertTrue(Bills.open(listOf(b), emptyList(), LocalDate.of(2026, 10, 29), ms).isEmpty())
    }

    @Test fun theCardOpensTheBanksApp() {
        val b = Bill("Sample Bank", "com.bank.sample", "2026-10-21", ms)
        val c = MomentRules.pick(MomentContext(now, qol = QolSignals(bills = listOf(b))), MomentsPrefs())!!
        assertEquals(MomentKind.BILL_DUE, c.kind)
        assertEquals("Sample Bank bill due tomorrow", c.content.title)
        assertEquals(listOf("Open Sample Bank", "Paid", "Never"), c.content.actions.map { it.label })
        assertEquals("app:com.bank.sample", c.opens["open"])
    }
}
