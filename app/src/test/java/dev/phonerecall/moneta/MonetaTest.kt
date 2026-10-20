package dev.phonerecall.moneta

import androidx.core.app.NotificationCompat
import dev.phonerecall.brain.FakeSealer
import dev.phonerecall.brain.ProposalKind
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
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MonetaTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDate.of(y, m, d).atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun spend(at: Long, cents: Long, merchant: String, kind: TxnKind = TxnKind.SPEND) = Txn(at = at, cents = cents, kind = kind, merchant = merchant, category = Categories.of(merchant), bank = "Chase", last4 = "1234")
    private val now = at(2026, 10, 19, 18)

    @Test fun `month by category nets refunds and compares to the usual`() {
        val txns = listOf(
            spend(at(2026, 10, 2), 5421, "Costco"), spend(at(2026, 10, 5), 1999, "Costco", TxnKind.REFUND), spend(at(2026, 10, 9), 3000, "Wegmans"),
            spend(at(2026, 10, 3), 210000, "Deposit", TxnKind.INCOME), spend(at(2026, 10, 4), 50000, "Card payment", TxnKind.PAYMENT),
            spend(at(2026, 9, 10), 4000, "Costco"), spend(at(2026, 8, 10), 6000, "Costco"), spend(at(2026, 9, 12), 1200, "Starbucks"),
        )
        val v = Money.month(txns, YearMonth.of(2026, 10), zone, mapOf(Category.GROCERIES to 5000))
        assertEquals(5421 - 1999 + 3000L, v.total)
        val g = v.categories.single { it.category == Category.GROCERIES }
        assertEquals(6422L, g.cents)
        assertEquals(5000L, g.usual)          // (4000 + 6000) / 2 months with data
        assertTrue(g.over)
        assertEquals(5600L, v.usual)          // Sept 5200, Aug 6000
    }

    @Test fun `folded duplicates and other currencies stay out of the totals`() {
        val txns = listOf(spend(at(2026, 10, 2), 5000, "Costco"), spend(at(2026, 10, 2), 5000, "Costco").copy(mergedInto = "iris:9"),
            spend(at(2026, 10, 3), 400000, "Carrefour").copy(currency = "EUR"))
        assertEquals(5000L, Money.month(txns, YearMonth.of(2026, 10), zone, emptyMap()).total)
    }

    @Test fun `unusual charges`() {
        val txns = listOf(
            spend(at(2026, 9, 1), 4000, "Wegmans"), spend(at(2026, 9, 8), 4200, "Tops"), spend(at(2026, 9, 15), 3800, "Aldi"),
            spend(at(2026, 10, 18), 15000, "Whole Foods"),
            spend(at(2026, 10, 18, 9), 2345, "Starbucks"), spend(at(2026, 10, 18, 10), 2345, "Starbucks"),
            spend(at(2026, 10, 17), 64900, "Best Buy"),
        )
        val u = Money.unusual(txns, now, zone).associate { it.txn.merchant to it.reason }
        assertTrue(u["Whole Foods"]!!.contains("usual groceries"))
        assertEquals("Charged twice within a day", u["Starbucks"])
        assertEquals("First charge from Best Buy", u["Best Buy"])
        val dismissed = Money.unusual(txns, now, zone).first { it.txn.merchant == "Best Buy" }.key
        assertFalse(Money.unusual(txns, now, zone, setOf(dismissed)).any { it.txn.merchant == "Best Buy" })
    }

    @Test fun `subscriptions are found on a beat and merged with Iris's and his`() {
        val txns = listOf(spend(at(2026, 8, 5), 1549, "Netflix"), spend(at(2026, 9, 5), 1549, "Netflix"), spend(at(2026, 10, 5), 1799, "Netflix"),
            spend(at(2026, 9, 1), 2500, "Car wash"), spend(at(2026, 10, 1), 2500, "Car wash"), spend(at(2026, 10, 3), 5421, "Costco"), spend(at(2026, 10, 12), 6000, "Costco"))
        val found = Money.recurring(txns, now, zone)
        assertEquals(setOf("Netflix", "Car wash"), found.map { it.name }.toSet())
        assertEquals(LocalDate.of(2026, 11, 5), found.single { it.name == "Netflix" }.next)
        assertTrue(Money.unusual(txns, now, zone).any { it.reason.startsWith("Netflix went up") })
        val merged = Money.subscriptions(found, listOf(Subscription("NETFLIX", null, Cycle.MONTHLY, LocalDate.of(2026, 11, 6), SubSource.IRIS), Subscription("Claude", null, Cycle.MONTHLY, LocalDate.of(2026, 10, 28), SubSource.IRIS)), emptyList())
        assertEquals(listOf("Claude", "Car wash", "NETFLIX"), merged.map { it.name })
        assertEquals(1799L, merged.last().cents)
        // Chronos gets names and dates, never amounts.
        val items = MonetaCalendar.items(merged, at(2026, 10, 1), at(2026, 12, 1), zone)
        assertEquals(listOf("Renews: Claude", "Renews: Car wash", "Renews: NETFLIX"), items.map { it.title })
        assertTrue(items.none { it.title.contains("$") || (it.notes ?: "").contains("$") })
    }

    @Test fun `money actions are never auto-approved and only ever open the bank`() {
        for (k in MoneyActionKind.entries) {
            val p = MoneyActions.propose(k, "Netflix", "Chase")
            assertFalse(MoneyActions.autoApproved(k, setOf(k.name, k.word, "all", "*")))
            assertNull("no tap, no effect", MoneyActions.confirm(p, tappedByOwner = false))
            assertEquals(MoneyEffect.OpenBank("com.chase.sig.android", "Chase"), MoneyActions.confirm(p, tappedByOwner = true))
        }
        // MoneyEffect has one kind: opening an app.
        assertEquals(listOf("OpenBank"), MoneyEffect::class.sealedSubclasses.map { it.simpleName })
        // And nothing money-like can sit on Athena's auto-approve list.
        assertTrue(ProposalKind.autoCandidates.none { Regex("(?i)pay|money|transfer|purchase|subscri|bank|cancel").containsMatchIn(it.wire + it.label) })
    }

    @Test fun `the store keeps no card number and only sealed rows`() {
        val store = MonetaStore(RuntimeEnvironment.getApplication(), FakeSealer(), name = null)
        val t = BankParser.parse("com.chase.sig.android", "", "Chase", "A charge of $12.00 at TIM HORTONS 4111111111111111 was made on card ending in 4111111111111111.", now)
        store.add(t ?: spend(now, 1200, "Tim Hortons"))
        store.add(Txn(at = now, cents = 500, kind = TxnKind.SPEND, merchant = "Farmers market", category = Category.GROCERIES, last4 = "98761234", source = Source.MANUAL))
        val raw = store.raw().joinToString("\n")
        assertFalse(raw, Regex("\\d{5,}").containsMatchIn(raw.replace(now.toString(), "")))
        assertEquals("1234", store.txns().last().last4)
        store.saveBudgets(mapOf(Category.DINING to 20000))
        assertEquals(mapOf(Category.DINING to 20000L), store.budgets())
    }

    @Test fun `the flag notification asks, and says nothing on the lock screen`() {
        val u = Unusual(spend(now, 64900, "Best Buy"), "First charge from Best Buy", "k1", "new-merchant")
        val n = MonetaCore.Alerts.build(RuntimeEnvironment.getApplication(), u, ring = true)
        assertEquals(NotificationCompat.VISIBILITY_SECRET, n.visibility)
        assertEquals("Did you make this?", n.extras.getCharSequence("android.title").toString())
        assertEquals("Best Buy $649.00 on Chase ••1234", n.extras.getCharSequence("android.text").toString())
        assertEquals("Zeus", n.publicVersion.extras.getCharSequence("android.title").toString())
        assertNull("the lock screen names nothing", n.publicVersion.extras.getCharSequence("android.text"))
        assertEquals(MonetaCore.Alerts.CHANNEL, n.channelId)
    }

}
