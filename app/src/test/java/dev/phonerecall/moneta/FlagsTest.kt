package dev.phonerecall.moneta

import android.app.Notification
import dev.phonerecall.MainActivity
import dev.phonerecall.brain.FakeSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** The abnormal-charge rules, and what "Did you make this?" does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlagsTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = 86_400_000L
    private val h = 3_600_000L
    private fun at(d: Int, hour: Int, min: Int = 0) = LocalDate.of(2026, 10, d).atTime(hour, min).atZone(zone).toInstant().toEpochMilli()
    private val now = at(19, 18)

    private fun s(at: Long, cents: Long, merchant: String, last4: String? = "1234", kind: TxnKind = TxnKind.SPEND, currency: String = "USD", via: Via = Via.APP, bank: String = "Chase") =
        Merchants.clean(merchant).let { m -> Txn(at = at, cents = cents, kind = kind, merchant = m, category = Categories.of(m), bank = bank, last4 = last4, currency = currency, via = via) }

    /** Two months of ordinary life on card 1234: Wegmans, Shell, Starbucks, at most $52. */
    private val history = (1..12).map { s(now - it * 5 * day, 4000L + it * 100, listOf("Wegmans", "Shell", "Starbucks")[it % 3]) }

    private fun flags(extra: List<Txn>, known: Set<String> = emptySet(), travelling: Boolean = false) =
        Money.unusual(history + extra, now, zone, known = known, travelling = { travelling })

    private fun flag(t: Txn, known: Set<String> = emptySet(), travelling: Boolean = false, more: List<Txn> = emptyList()) =
        flags(more + t, known, travelling).firstOrNull { it.txn == t }

    @Test fun `ordinary charges raise nothing`() {
        assertTrue(flags(emptyList()).isEmpty())
        assertNull(flag(s(now - h, 4300, "Wegmans")))
    }

    @Test fun `test charges`() {
        val one = flag(s(now - h, 100, "Qwik Pay Store"))!!
        assertEquals("test", one.rule)
        assertTrue(one.fraud)
        assertEquals("A small charge from a merchant you haven't used: a card being tested?", one.reason)
        assertNull("he said it was his", flag(s(now - h, 100, "Qwik Pay Store"), known = setOf(Reconcile.norm("Qwik Pay Store"))))
        // Several small ones within an hour, even at places he knows.
        val a = s(now - 50 * 60_000, 150, "Starbucks"); val b = s(now - 30 * 60_000, 175, "Starbucks"); val c = s(now - 10 * 60_000, 199, "Shell")
        val f = flags(listOf(a, b, c))
        assertNull(f.firstOrNull { it.txn == a })
        assertEquals("3 small charges within an hour: a card being tested?", f.single { it.txn == c }.reason)
        assertTrue(f.single { it.txn == c }.fraud)
    }

    @Test fun `a charge right after a decline somewhere else`() {
        val u = flag(s(now - 10 * 60_000, 3000, "Wegmans"), more = listOf(s(now - 30 * 60_000, 8200, "SUNOCO", kind = TxnKind.DECLINED)))!!
        assertEquals(listOf("after-decline", "Charged right after a decline at Sunoco", true), listOf(u.rule, u.reason, u.fraud))
        assertNull("the same merchant retrying is not it", flag(s(now - 10 * 60_000, 8200, "Sunoco"), more = listOf(s(now - 30 * 60_000, 8200, "SUNOCO", kind = TxnKind.DECLINED))))
    }

    @Test fun `odd hours at a store`() {
        val known = flag(s(at(19, 3, 12), 3000, "Wegmans"))!!
        assertEquals(listOf("odd-hour", "At 3 AM, an odd hour to be at Wegmans", false), listOf(known.rule, known.reason, known.fraud))
        val stranger = flag(s(at(19, 2), 3000, "Kwik Fill"))!!
        assertEquals(listOf("At 2 AM, at a merchant you haven't used", true), listOf(stranger.reason, stranger.fraud))
        assertNull("online at night is normal", flag(s(at(19, 3), 3000, "Amazon")))
        assertNull("5 AM is morning", flag(s(at(19, 5, 30), 3000, "Wegmans")))
    }

    @Test fun `foreign charges when Argo has no trip`() {
        val inr = s(now - h, 400000, "Carrefour", currency = "EUR")
        assertEquals("Charged in EUR, and Argo has no trip for you", flag(inr)!!.reason)
        assertNull("on a trip", flag(inr, travelling = true))
        assertEquals("foreign", flag(s(now - h, 1200, "Pret A Manger London"))!!.rule)
    }

    @Test fun `a card he rarely uses, and a charge far over a card's usual`() {
        val rare = flag(s(now - h, 3000, "Costco", "9999"), more = listOf(s(now - 100 * day, 3000, "Costco", "9999")))!!
        assertEquals("rare-card", rare.rule)
        assertTrue(rare.reason, rare.reason.startsWith("Card ••9999 hadn't been used in 99 days"))
        val big = flag(s(now - h, 15000, "Shell"))!!
        assertEquals(listOf("card-max", "The most on ••1234 in 90 days (the most before was $52.00)"), listOf(big.rule, big.reason))
    }

    @Test fun `three declines in a day`() {
        val d = listOf(5, 3, 1).map { s(now - it * h, 8200, "Sunoco", kind = TxnKind.DECLINED) }
        assertEquals(listOf("3 declines within a day"), flags(d).filter { it.rule == "declines" }.map { it.reason })
        assertTrue(flags(d.take(2)).none { it.rule == "declines" })
    }

    @Test fun `the first big charge from a new merchant is quiet once he says it's his`() {
        assertEquals("First charge from Best Buy", flag(s(now - h, 64900, "Best Buy"))!!.reason)
        // Known now, it is still far over what card 1234 usually sees.
        assertEquals("card-max", flag(s(now - h, 64900, "Best Buy"), known = setOf(Reconcile.norm("BEST BUY 00123")))!!.rule)
    }

    @Test fun `quiet hours hold back all but fraud-grade flags`() {
        val night = LocalDate.of(2026, 10, 19).atTime(2, 0).atZone(zone)
        val noon = night.withHour(12)
        val plain = Unusual(s(now, 64900, "Best Buy"), "First charge from Best Buy", "k", "new-merchant")
        val fraud = plain.copy(rule = "test", fraud = true)
        assertFalse(MonetaCore.Alerts.rings(plain, night, napQuiet = false))
        assertTrue(MonetaCore.Alerts.rings(plain, noon, napQuiet = false))
        assertFalse(MonetaCore.Alerts.rings(plain, noon, napQuiet = true))
        assertTrue(MonetaCore.Alerts.rings(fraud, night, napQuiet = true))
    }

    @Test fun `the notification's Yes and No`() {
        val app = RuntimeEnvironment.getApplication()
        val u = Unusual(s(now, 100, "Qwik Pay Store", bank = "Capital One", last4 = "4321"), "A small charge from a merchant you haven't used: a card being tested?", "${now}:100:Qwik Pay Store", "test", fraud = true)
        val n = MonetaCore.Alerts.build(app, u, ring = true)
        assertEquals(MonetaCore.Alerts.FRAUD_CHANNEL, n.channelId)
        assertEquals("Qwik Pay Store $1.00 on Capital One ••4321", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Yes, that's me", "No"), n.actions.map { it.title.toString() })
        val yes = shadowOf(n.actions[0].actionIntent)
        assertTrue(yes.isBroadcastIntent)
        assertEquals(MonetaCore.Alerts.ACTION_YES, yes.savedIntent.action)
        assertEquals("Qwik Pay Store", yes.savedIntent.getStringExtra(MonetaCore.Alerts.EXTRA_MERCHANT))
        val no = shadowOf(n.actions[1].actionIntent)
        assertTrue(no.isActivityIntent)
        assertEquals(u.key, no.savedIntent.getStringExtra(MonetaCore.Alerts.EXTRA_NO))
        assertEquals("Capital One", no.savedIntent.getStringExtra(MonetaCore.Alerts.EXTRA_BANK))
        assertEquals("MONEY", no.savedIntent.getStringExtra(MainActivity.EXTRA_DEST))
        assertEquals(MonetaCore.Alerts.CHANNEL, MonetaCore.Alerts.build(app, u.copy(fraud = false), ring = false).channelId)
    }

    @Test fun `Yes learns the merchant, No waits on the page, and Iris hears no amount`() {
        val store = MonetaStore(RuntimeEnvironment.getApplication(), FakeSealer(), name = null)
        val t = s(at(17, 14), 64900, "BEST BUY 00123", "4321", bank = "Capital One")
        store.add(t)
        val key = "${t.at}:${t.cents}:${t.merchant}"
        MonetaFlags.mine(store, key, t.merchant)
        assertTrue(key in store.dismissed())
        assertEquals(setOf("bestbuy"), store.known())
        assertNull(Money.unusual(store.txns() + history, now, zone, store.dismissed(), known = store.known()).firstOrNull { it.txn.merchant == "Best Buy" })

        MonetaFlags.disown(store, "k2"); MonetaFlags.disown(store, "k2")
        assertEquals(listOf("k2"), store.disowned())
        MonetaFlags.settle(store, "k2")
        assertTrue(store.disowned().isEmpty())

        val found = MonetaFlags.txnFor(store, key)!!
        val note = MonetaFlags.irisNote(found, zone)
        assertEquals("The owner says he didn't make Best Buy Sat Oct 17 on Capital One ••4321", note)
        assertFalse(note, note.contains("$") || note.contains("649"))
    }
}
