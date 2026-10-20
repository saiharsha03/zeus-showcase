package dev.phonerecall.moneta

import dev.phonerecall.brain.FakeSealer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReconcileTest {
    private val zone = ZoneId.of("America/New_York")
    private val t0 = LocalDate.of(2026, 10, 19).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    private val now = t0 + 3_600_000L
    private val h = 3_600_000L
    private val store = MonetaStore(RuntimeEnvironment.getApplication(), FakeSealer(), name = null)
    private val ops = MonetaOps(store, zone) { now }

    private fun phone(at: Long, cents: Long, merchant: String, last4: String? = "1234", via: Via = Via.APP, kind: TxnKind = TxnKind.SPEND, bank: String = "Chase", pending: Boolean = false) =
        Merchants.clean(merchant).let { m -> Txn(at = at, cents = cents, kind = kind, merchant = m, category = Categories.of(m), bank = bank, last4 = last4, via = via, pending = pending) }

    private fun iris(id: Int, at: Long, cents: Long, merchant: String, last4: String? = null, kind: TxnKind = TxnKind.SPEND) =
        Merchants.clean(merchant).let { m -> Txn(at = at, cents = cents, kind = kind, merchant = m, category = Categories.of(m), last4 = last4, source = Source.IRIS, key = "iris:$id") }

    private fun insert(t: Txn) = Reconcile.insert(store, ops, t)
    private fun merge() = Ledger.merge(store, emptyList(), full = false, now = now)
    private fun counted() = store.txns().filter { it.counted }

    @Test fun `a Google Wallet tap and the bank app's alert for it are one purchase, the bank app's row kept`() {
        val w = BankParser.parse("com.google.android.apps.walletnfcrel", "Google Wallet", "Starbucks", "$23.45 with Chase Visa •••• 1234", t0)!!
        val a = BankParser.parse("com.chase.sig.android", "", "Transaction alert", "A charge of (\$USD) 23.45 at STARBUCKS STORE 0412 has been authorized on your card ending in 1234.", t0 + 40_000)!!
        assertFalse(insert(w).second)
        val (kept, folded) = insert(a)
        assertTrue(folded)
        val one = counted().single()
        assertEquals(listOf(Via.APP, t0, "1234", "Chase", "Starbucks"), listOf(one.via, one.at, one.last4, one.bank, one.merchant))
        assertEquals(kept.ref, store.txns().single { it.via == Via.WALLET }.mergedInto)
        // The bank's text for it a minute later folds in too.
        assertTrue(insert(BankParser.parse("com.google.android.apps.messaging", "Messages", "22000", "Chase: A $23.45 transaction at STARBUCKS was made on card ending 1234", t0 + 70_000)!!).second)
        assertEquals(1, counted().size)
        // Each fold is Moneta's, logged, and undoable.
        val log = store.changes().filter { it.who == MonetaOps.WHO_MONETA }
        assertEquals(2, log.size)
        assertTrue(log.all { it.undoable && it.summary.startsWith("folded 1 duplicate into Starbucks") })
        assertTrue(ops.undo(log.last().id))
        assertEquals(2, counted().size)
    }

    @Test fun `a text after the bank app folds into the app's row, and a different card never does`() {
        insert(phone(t0, 999, "Spotify"))
        assertTrue(insert(phone(t0 + 60_000, 999, "SPOTIFY USA", via = Via.SMS)).second)
        assertEquals(Via.APP, counted().single().via)
        assertFalse("another card, another purchase", insert(phone(t0 + 90_000, 999, "Spotify", last4 = "9876", via = Via.WALLET)).second)
        assertFalse("the same app twice, minutes apart, is two charges", insert(phone(t0 + 8 * 60_000, 999, "Spotify")).second)
        assertFalse("too far apart", insert(phone(t0 + 30 * 60_000, 999, "Spotify", via = Via.SMS)).second)
        assertEquals(4, counted().size)
        // A wallet alert without a merchant still pairs on the card.
        insert(phone(t0 + 2 * h, 4100, "Wegmans", last4 = "5678", bank = "Bank of America"))
        assertTrue(insert(phone(t0 + 2 * h + 30_000, 4100, BankParser.NO_MERCHANT, last4 = "5678", via = Via.WALLET, bank = "Google Wallet")).second)
        assertEquals("Wegmans", counted().last().merchant)
    }

    @Test fun `when Iris logged it first, the bank alert folds into her row on arrival`() {
        store.put(iris(1, t0 - 6 * h, 5421, "COSTCO WHSE #0123", "5678"))
        val (row, folded) = insert(phone(t0, 5421, "Costco", "5678", bank = "Bank of America"))
        assertTrue(folded)
        assertEquals("iris:1", store.byRef(row.ref)!!.mergedInto)
        assertEquals(Recon.MATCHED, Reconcile.status(store.txns())["iris:1"])
        assertNull("nothing left to pair", merge())
        // A second alert for the same purchase from the wallet joins her row too, and Iris's row stays one-to-one.
        assertTrue(insert(phone(t0 + 60_000, 5421, "Costco", "5678", via = Via.WALLET)).second)
        assertEquals(5421L, counted().sumOf { it.cents })
    }

    @Test fun `a tip on a meal and a pump's hold still match, but only at the same merchant`() {
        store.put(phone(t0, 4000, "Joe's Pizza"))
        store.put(iris(2, t0 + 18 * h, 4800, "JOES PIZZA #12", "1234"))
        store.put(phone(t0 + 5 * 24 * h, 4000, "Joe's Pizza"))
        store.put(iris(3, t0 + 5 * 24 * h + 18 * h, 5300, "JOES PIZZA #12"))
        store.put(phone(t0 + 2 * h, 100, "SUNOCO 0363"))
        store.put(iris(4, t0 + 20 * h, 4612, "SUNOCO"))
        store.put(phone(t0 + 3 * h, 100, "SPEEDWAY 0042"))
        store.put(iris(5, t0 + 21 * h, 3999, "Kwik Fill"))
        assertEquals("2 matched to bank alerts", merge())
        val folded = store.txns().filter { it.mergedInto != null }.associate { it.merchant to it.mergedInto }
        assertEquals(mapOf("Joe's Pizza" to "iris:2", "Sunoco" to "iris:4"), folded)
        assertEquals("a 32% tip is too much to be one meal", Recon.PHONE, Reconcile.status(store.txns())[store.txns().last { it.merchant == "Joe's Pizza" }.ref])
    }

    @Test fun `two cards never match, and each Iris row takes one alert`() {
        store.put(phone(t0, 5421, "Costco", "5678"))
        store.put(iris(1, t0 + h, 5421, "Costco", "9999"))
        assertNull(merge())
        // Two coffees at the same price, one Iris row: the closer one pairs; the other waits for its own row.
        store.put(phone(t0 - 9 * h, 525, "Starbucks"))
        store.put(phone(t0 - 3 * h, 525, "Starbucks"))
        store.put(iris(6, t0, 525, "STARBUCKS"))
        assertEquals("1 matched to bank alerts", merge())
        assertEquals(t0 - 3 * h, store.txns().single { it.mergedInto == "iris:6" }.at)
        store.put(iris(7, t0 - 8 * h, 525, "STARBUCKS"))
        assertEquals("1 matched to bank alerts", merge())
        assertEquals(t0 - 9 * h, store.txns().single { it.mergedInto == "iris:7" }.at)
    }

    @Test fun `a category-only match counts only when it is the one candidate`() {
        val a = phone(t0, 1500, "TST* MAIN ST CAFE")
        assertEquals(listOf(a to iris(8, t0 + h, 1500, "Square Restaurant")), Reconcile.pair(listOf(a), listOf(iris(8, t0 + h, 1500, "Square Restaurant"))))
        val b = phone(t0 + 2 * h, 1500, "Pizza Palace")
        assertTrue("two alerts could be the one row: neither is folded", Reconcile.pair(listOf(a.copy(id = 1), b.copy(id = 2)), listOf(iris(8, t0 + h, 1500, "Square Restaurant"))).isEmpty())
    }

    @Test fun `refunds, declines and payments never fold into spends, and a decline never counts`() {
        store.put(phone(t0, 8200, "SUNOCO 0363", kind = TxnKind.DECLINED))
        assertFalse("a charge after a decline is not the decline", insert(phone(t0 + 60_000, 8200, "Sunoco", via = Via.SMS)).second)
        store.put(phone(t0, 1999, "AMAZON.COM", kind = TxnKind.REFUND))
        store.put(iris(1, t0 + h, 1999, "Amazon"))
        store.put(iris(2, t0 + h, 8200, "Sunoco"))
        store.put(phone(t0, 50000, "Card payment", kind = TxnKind.PAYMENT))
        store.put(iris(3, t0 + h, 50000, "Card payment", kind = TxnKind.SPEND))
        assertEquals("only the spend pairs with her spend", "1 matched to bank alerts", merge())
        assertEquals("iris:2", store.txns().single { it.mergedInto != null }.mergedInto)
        assertEquals(TxnKind.SPEND, store.txns().single { it.mergedInto != null }.kind)
        assertTrue(store.txns().none { it.kind == TxnKind.DECLINED && it.counted })
        // Fuel: her Sunoco only (the decline never counts); shopping: Amazon less its refund; her mistaken "payment" spend.
        assertEquals(mapOf(Category.FUEL to 8200L, Category.OTHER to 50000L), Money.spend(store.txns(), java.time.YearMonth.of(2026, 10), zone))
    }

    @Test fun `a posted charge replaces its pending one`() {
        insert(phone(t0, 4000, "Joe's Pizza", pending = true))
        val (posted, folded) = insert(phone(t0 + 3 * h, 4800, "Joe's Pizza"))
        assertTrue(folded)
        assertEquals(posted.ref, store.txns().single { it.pending }.mergedInto)
        assertEquals(listOf(4800L), counted().map { it.cents })
        // Not a pending one: two meals.
        assertFalse(insert(phone(t0 + 26 * h, 4500, "Joe's Pizza")).second)
    }

    @Test fun `an unmerged row stays apart`() {
        insert(phone(t0, 2345, "Starbucks", via = Via.WALLET))
        insert(phone(t0 + 30_000, 2345, "Starbucks"))
        val wallet = store.txns().single { it.via == Via.WALLET }
        ops.apply(MonetaOp.Unmerge(wallet.ref), MonetaOps.WHO_OWNER)
        assertNull(store.byRef(wallet.ref)!!.mergedInto)
        assertTrue(store.changes().first().let { it.who == "The owner" && it.summary == "unmerged Starbucks" && it.undoable })
        store.put(iris(9, t0 + 5 * h, 2345, "Starbucks"))
        assertEquals("1 matched to bank alerts", merge())
        assertNull("he said it was separate", store.byRef(wallet.ref)!!.mergedInto)
        assertTrue(runCatching { ops.apply(MonetaOp.Unmerge(wallet.ref), MonetaOps.WHO_OWNER) }.isFailure)
    }

    @Test fun `the daily check lists what only one side has`() {
        val day = 24 * h
        val txns = listOf(
            phone(now - 4 * day, 3210, "Target").copy(id = 1),
            phone(now - day, 1200, "Tim Hortons").copy(id = 2),
            iris(1, now - 2 * day, 1549, "Netflix"),
            iris(2, now - 2 * day, 5421, "Costco"), phone(now - 2 * day, 5421, "Costco").copy(id = 3, mergedInto = "iris:2"),
            iris(3, now - 20 * day, 999, "Spotify"),
            Txn(at = now - 2 * day, cents = 500, kind = TxnKind.SPEND, merchant = "Farmers market", category = Category.GROCERIES, source = Source.IRIS, key = "iris:op-1"),
        )
        val c = Reconcile.checks(txns, now, synced = true, dismissed = emptySet())
        assertEquals(listOf("Netflix", "Target"), c.map { it.txn.merchant })
        assertTrue(c[0].reason.startsWith("In Iris's log, but no bank alert came"))
        assertTrue(c[1].reason.startsWith("A bank alert Iris's log never matched"))
        assertEquals(listOf("Target"), Reconcile.checks(txns, now, true, setOf(c[0].key)).map { it.txn.merchant })
        assertTrue(Reconcile.checks(txns, now, synced = false, dismissed = emptySet()).isEmpty())
        assertEquals(mapOf("local:1" to Recon.PHONE, "local:2" to Recon.PHONE, "iris:1" to Recon.IRIS, "iris:2" to Recon.MATCHED, "iris:3" to Recon.IRIS, "iris:op-1" to Recon.IRIS), Reconcile.status(txns))
    }

    @Test fun `merchant names compare without store numbers, prefixes and case`() {
        assertTrue(Reconcile.sameName("SQ *BLUE BOTTLE 0123", "Blue Bottle"))
        assertTrue(Reconcile.sameName("TST* JOE'S PIZZA #12", "joes pizza"))
        assertTrue(Reconcile.sameName("POS WEGMANS #057", "Wegmans"))
        assertFalse(Reconcile.sameName("Shell", "Sunoco"))
    }
}
