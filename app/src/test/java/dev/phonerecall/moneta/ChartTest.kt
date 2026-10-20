package dev.phonerecall.moneta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ChartTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDate.of(2026, 10, 19).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    private fun at(m: Int, d: Int) = LocalDate.of(2026, m, d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private fun t(m: Int, d: Int, cents: Long, merchant: String, last4: String? = "1234", kind: TxnKind = TxnKind.SPEND) =
        Txn(at = at(m, d), cents = cents, kind = kind, merchant = merchant, category = Categories.of(merchant), last4 = last4)

    private val txns = listOf(
        t(8, 3, 5000, "Costco"), t(9, 4, 6000, "Costco"), t(10, 2, 7000, "Costco"), t(10, 5, 1000, "Costco", kind = TxnKind.REFUND),
        t(9, 10, 2000, "Starbucks", "9999"), t(10, 12, 3000, "Starbucks", "9999"), t(10, 13, 1549, "Netflix"), t(9, 13, 1549, "Netflix"),
        t(10, 14, 900, "Uber Eats").copy(mergedInto = "iris:1"), t(10, 15, 99999, "Carrefour").copy(currency = "EUR"),
    )

    private fun spec(json: String) = ChartSpecs.parse(json).getOrThrow()

    @Test fun `specs are checked strictly`() {
        assertTrue(ChartSpecs.parse("""{"id":"a","type":"line","title":"Ok","query":{"by":"month","months":6}}""").isSuccess)
        val bad = listOf(
            """{"id":"a","type":"line","title":"Ok","html":"<b>"}""",
            """{"id":"A B","type":"line","title":"Ok"}""",
            """{"id":"a","type":"pie3d","title":"Ok"}""",
            """{"id":"a","type":"line","title":"<img src=x>"}""",
            """{"id":"a","type":"line","title":"Card 4111111111111111"}""",
            """{"id":"a","type":"line","title":"Ok","query":{"months":30}}""",
            """{"id":"a","type":"line","title":"Ok","query":{"by":"month","split":"month"}}""",
            """{"id":"a","type":"line","title":"Ok","query":{"cards":["41009"]}}""",
            """{"id":"a","type":"line","title":"Ok","query":{"sql":"select *"}}""",
            """{"id":"a","type":"line","title":"Ok","subtitle":"${"x".repeat(5000)}"}""",
            """[1,2,3]""",
        )
        bad.forEach { assertTrue(it.take(60), ChartSpecs.parse(it).isFailure) }
        assertEquals(6, ChartSpecs.BUILT_IN.size)
    }

    @Test fun `spend by month nets refunds and skips duplicates and other currencies`() {
        val d = ChartEngine.compute(spec("""{"id":"m","type":"line","title":"By month","query":{"by":"month","months":3}}"""), txns, emptyMap(), now, zone)
        assertEquals(listOf("Aug", "Sep", "Oct"), d.labels)
        assertEquals(listOf(5000L, 9549L, 10549L), d.series.single().values)
    }

    @Test fun `split by month compares categories, and top folds the rest into Other`() {
        val d = ChartEngine.compute(spec("""{"id":"x","type":"bar","title":"x","query":{"by":"category","split":"month","months":2}}"""), txns, emptyMap(), now, zone)
        assertEquals(listOf("Groceries", "Eating out", "Subscriptions"), d.labels)
        assertEquals(listOf("Sep", "Oct"), d.series.map { it.name })
        assertEquals(listOf(6000L, 2000L, 1549L), d.series[0].values)
        val top = ChartEngine.compute(spec("""{"id":"y","type":"bar","title":"y","query":{"by":"merchant","months":3,"top":1}}"""), txns, emptyMap(), now, zone)
        assertEquals(listOf("Costco", "Other"), top.labels)
        assertEquals(listOf(17000L, 8098L), top.series.single().values)
    }

    @Test fun `filters, cards, counts and a cumulative budget line`() {
        val cards = ChartEngine.compute(spec("""{"id":"c","type":"donut","title":"c","query":{"by":"card","months":2,"cards":["9999"]}}"""), txns, emptyMap(), now, zone)
        assertEquals(listOf("••9999"), cards.labels)
        val count = ChartEngine.compute(spec("""{"id":"n","type":"bar","title":"n","query":{"metric":"count","by":"month","months":1,"merchants":["costco"]}}"""), txns, emptyMap(), now, zone)
        assertEquals(listOf(1L), count.series.single().values)
        val burn = ChartEngine.compute(ChartSpecs.BUILT_IN.single { it.id == "budget-burn" }, txns, mapOf(Category.GROCERIES to 20000L, Category.DINING to 5000L), now, zone)
        assertEquals(19, burn.labels.size)
        assertEquals(10549L, burn.series.single().values.last())
        assertEquals(25000L, burn.reference)
        // The Oct 5 refund brings the running total down.
        assertEquals(listOf(0L, 7000L, 7000L, 7000L, 6000L), burn.series.single().values.take(5))
    }
}
