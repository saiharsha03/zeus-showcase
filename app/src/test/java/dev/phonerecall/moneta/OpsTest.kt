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
class OpsTest {
    private val zone = ZoneId.of("America/New_York")
    private var now = LocalDate.of(2026, 10, 19).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    private val store = MonetaStore(RuntimeEnvironment.getApplication(), FakeSealer(), name = null)
    private val ops = MonetaOps(store, zone) { now }
    private var n = 0

    /** An op exactly as the gateway hands it over. */
    private fun env(op: String, args: String) = """{"v":1,"op_id":"${"%024x".format(++n)}","op":"$op","args":$args,"at":$now,"from":"iris"}"""

    private fun ledger(vararg rows: String) = Ledger.parse("""{"rows":[${rows.joinToString(",")}],"next":${rows.size},"more":false,"subscriptions":[]}""", zone)

    @Test fun `Iris's ops apply at once, are logged as hers, and each one undoes`() {
        val acked = ops.applyEnvelopes(listOf(
            env("add", """{"key":"iris:op-1","date":"2026-10-18","amount":47.82,"merchant":"Whole Foods","last4":"1234"}"""),
            env("recategorize", """{"key":"iris:op-1","category":"Eating out"}"""),
            env("budget", """{"category":"Groceries","amount":400}"""),
            env("sub_add", """{"name":"Netflix","amount":15.49,"cycle":"monthly","next":"2026-11-05"}"""),
        ))
        assertEquals(4, acked.size)
        val t = store.byRef("iris:op-1")!!
        assertEquals(listOf(4782L, Category.DINING, Source.IRIS, "1234"), listOf(t.cents, t.category, t.source, t.last4))
        assertEquals(mapOf(Category.GROCERIES to 40000L), store.budgets())
        assertEquals(SubSource.IRIS, store.manualSubs().single().source)
        val log = store.changes()
        assertTrue(log.all { it.who == "Iris" && it.undoable })
        assertEquals("moved Whole Foods to Eating out", log[2].summary)

        // Undo the recategorize only: the row is back to Groceries, the budget stays.
        assertTrue(ops.undo(log[2].id))
        assertEquals(Category.GROCERIES, store.byRef("iris:op-1")!!.category)
        assertEquals(mapOf(Category.GROCERIES to 40000L), store.budgets())
        assertFalse("an undo is one-shot", ops.undo(log[2].id))
        assertTrue(store.changes().first().summary.startsWith("undid: Iris moved Whole Foods"))
        // Undo the add: the row is gone again.
        assertTrue(ops.undo(log[3].id))
        assertNull(store.byRef("iris:op-1"))
        // The same op id twice applies once.
        val e = env("budget", """{"category":"Fuel","amount":100}""")
        val before = store.changes(500).size
        ops.applyEnvelopes(listOf(e)); ops.applyEnvelopes(listOf(e))
        assertEquals(before + 1, store.changes(500).size)
    }

    @Test fun `merge folds duplicates out of the totals and delete undoes to the same row`() {
        val a = store.add(Txn(at = now, cents = 2345, kind = TxnKind.SPEND, merchant = "Starbucks", category = Category.DINING))
        store.add(Txn(at = now, cents = 2345, kind = TxnKind.SPEND, merchant = "Starbucks", category = Category.DINING, key = "iris:7", source = Source.IRIS))
        ops.applyEnvelopes(listOf(env("merge", """{"keep":"iris:7","drop":["local:$a"]}""")))
        assertEquals("iris:7", store.byRef("local:$a")!!.mergedInto)
        assertEquals(2345L, Money.spend(store.txns(), java.time.YearMonth.of(2026, 10), zone).values.sum())
        ops.apply(MonetaOp.Delete("iris:7"), MonetaOps.WHO_OWNER)
        assertNull(store.byRef("iris:7"))
        assertTrue(ops.undo(store.changes().first().id))
        assertEquals("Starbucks", store.byRef("iris:7")!!.merchant)
    }

    @Test fun `bad ops are refused and logged, never applied`() {
        val acked = ops.applyEnvelopes(listOf(
            env("add", """{"date":"2026-10-18","amount":5,"merchant":"Card 4111111111111111","key":"iris:x"}"""),
            env("add", """{"date":"2026-10-18","amount":5,"merchant":"x","last4":"41009","key":"iris:y"}"""),
            env("edit", """{"key":"iris:nope","fields":{"amount":3}}"""),
            env("pay", """{"to":"landlord","amount":1200}"""),
            env("chart_put", """{"spec":{"id":"x","type":"line","title":"<script>","query":{}}}"""),
        ))
        assertEquals(5, acked.size)
        assertTrue(store.txns().isEmpty())
        assertEquals(5, store.changes().count { it.summary.startsWith("op refused") && !it.undoable })
    }

    @Test fun `Iris's log syncs in, matches bank alerts, and respects edits`() {
        store.add(Txn(at = now - 3_600_000, cents = 5421, kind = TxnKind.SPEND, merchant = "Costco", category = Category.GROCERIES, bank = "Bank of America", last4 = "5678"))
        val p = ledger(
            """{"id":1,"date":"2026-10-19","cents":5421,"currency":"USD","category":"Groceries","merchant":"COSTCO WHSE #0123","kind":"charge","last4":"5678","status":"posted"}""",
            """{"id":2,"date":"2026-10-18","cents":1549,"currency":"USD","category":"Streaming","merchant":"NETFLIX.COM","kind":"charge","last4":null,"status":""}""",
            """{"id":3,"date":"2026-10-17","cents":8200,"currency":"USD","category":"Fuel","merchant":"SUNOCO","kind":"charge","last4":"4321","status":"declined"}""",
        )
        assertEquals(listOf("Costco", "Netflix", "Sunoco"), p.rows.map { it.merchant })
        assertEquals(listOf(TxnKind.SPEND, TxnKind.SPEND, TxnKind.DECLINED), p.rows.map { it.kind })
        assertEquals(Category.SUBSCRIPTIONS, p.rows[1].category)
        assertEquals("3 new, 1 matched to bank alerts", Ledger.merge(store, p.rows, full = false))
        assertEquals("iris:1", store.txns().single { it.source == Source.NOTIFICATION }.mergedInto)
        assertEquals(5421L + 1549, Money.spend(store.txns(), java.time.YearMonth.of(2026, 10), zone).values.sum())
        assertNull("nothing changed", Ledger.merge(store, p.rows, full = false))
        // Iris recategorizes her own row through an op; a later sync of the same row leaves the edit alone.
        ops.apply(MonetaOp.Recategorize("iris:2", Category.FUN), MonetaOps.WHO_IRIS)
        Ledger.merge(store, p.rows, full = true)
        assertEquals(Category.FUN, store.byRef("iris:2")!!.category)
        // A full sync without row 3: it was deleted from her log, so it goes here too.
        assertEquals("1 gone from her log", Ledger.merge(store, p.rows.take(2), full = true))
        assertNull(store.byRef("iris:3"))
    }

    @Test fun `no card number gets in through Iris`() {
        val p = Ledger.parse("""{"rows":[{"id":5,"date":"2026-10-19","cents":1200,"merchant":"TIM HORTONS","kind":"charge","last4":"41009"}],"next":5,"more":false,"subscriptions":[]}""", zone)
        assertNull(p.rows.single().last4)
        Ledger.merge(store, p.rows, false)
        ops.applyEnvelopes(listOf(env("edit", """{"key":"iris:5","fields":{"note":"acct 0012345678"}}""")))
        val raw = store.raw().joinToString()
        assertFalse(raw, Regex("\\d{5,}").containsMatchIn(raw.replace(Regex("\"at\":\\d+"), "")))
    }

    @Test fun `charts Iris pins are capped, replace by id, and undo`() {
        fun chart(id: String) = env("chart_put", """{"spec":{"id":"$id","type":"bar","title":"Chart $id","query":{"by":"month","months":3}}}""")
        ops.applyEnvelopes((1..12).map { chart("c$it") })
        assertEquals(12, store.charts().size)
        ops.applyEnvelopes(listOf(chart("c13")))
        assertEquals(12, store.charts().size)
        assertTrue(store.changes().first().summary.contains("at most 12"))
        ops.applyEnvelopes(listOf(env("chart_put", """{"spec":{"id":"c1","type":"donut","title":"Now a donut","query":{"by":"category"}}}""")))
        assertEquals("Now a donut", store.charts().mapNotNull { ChartSpecs.parse(it).getOrNull() }.single { it.id == "c1" }.title)
        assertTrue(ops.undo(store.changes().first().id))
        assertEquals("Chart c1", store.charts().mapNotNull { ChartSpecs.parse(it).getOrNull() }.single { it.id == "c1" }.title)
    }
}
