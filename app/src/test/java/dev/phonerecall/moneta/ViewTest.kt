package dev.phonerecall.moneta

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ViewTest {
    private val zone = ZoneId.of("America/New_York")
    private val now = LocalDate.of(2026, 10, 19).atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
    private val day = 86_400_000L

    private fun txn(daysAgo: Int, cents: Long, merchant: String, last4: String? = "1234", source: Source = Source.NOTIFICATION, key: String? = null, id: Long = 0) =
        Txn(id = id, at = now - daysAgo * day, cents = cents, kind = TxnKind.SPEND, merchant = merchant, category = Category.GROCERIES, last4 = last4, source = source, key = key)

    private fun view(txns: List<Txn>, flagged: Set<String> = emptySet(), subs: List<Subscription> = emptyList()): JsonNode =
        jsonMapper().readTree(MonetaView.build(txns, subs, mapOf(Category.GROCERIES to 40000L), flagged, now, zone))

    @Test fun `phone and Iris rows are tagged, newest first, folded and old ones stay home`() {
        val v = view(listOf(
            txn(3, 1549, "Netflix", source = Source.IRIS, key = "iris:812"),
            txn(1, 4782, "Whole Foods", id = 4),
            txn(2, 4782, "Whole Foods", id = 5).copy(mergedInto = "iris:812"),
            txn(95, 100, "Long ago", id = 6),
            txn(0, 500, "Coffee", source = Source.MANUAL, id = 7).copy(pending = true),
        ), flagged = setOf("local:4"))
        val rows = v["rows"].toList()
        assertEquals(listOf("local:7", "local:4", "iris:812"), rows.map { it["id"].asText() })
        assertEquals(listOf("phone", "phone", "iris"), rows.map { it["source"].asText() })
        assertEquals(listOf("pending", "posted", "posted"), rows.map { it["status"].asText() })
        assertEquals("2026-10-18", rows[1]["date"].asText())
        assertEquals(4782L, rows[1]["cents"].asLong())
        assertEquals("Groceries", rows[1]["category"].asText())
        assertTrue(rows[1]["fraud_flag"].asBoolean())
        assertNull(rows[2]["fraud_flag"])
        assertEquals(40000L, v["budgets"]["Groceries"].asLong())
        assertEquals(1549L + 4782 + 500, v["week"]["spent_cents"].asLong())
        assertFalse(v["truncated"].asBoolean())
    }

    @Test fun `only the last four digits leave, and no digit runs or balances in merchant text`() {
        val txns = listOf(
            txn(1, 100, "AMZN 4111 1111 1111 1111 Mktp", last4 = "4111111111111111", id = 1),
            txn(1, 100, "Shell avail bal \$1,234.56", last4 = "98765", id = 2),
            txn(1, 100, "Acct 123456789 transfer", last4 = "4321", id = 3),
        )
        val rows = view(txns)["rows"].toList()
        assertEquals(setOf("AMZN Mktp", "Shell", "Acct transfer"), rows.map { it["merchant"].asText() }.toSet())
        assertEquals(setOf(true, false), rows.map { !it.hasNonNull("last4") }.toSet())
        assertEquals("4321", rows.first { it["id"].asText() == "local:3" }["last4"].asText())
        val text = MonetaView.build(txns, emptyList(), emptyMap(), emptySet(), now, zone)
        assertFalse(Regex("""\d{7,}""").containsMatchIn(text.replace(Regex(""""generated":\d+"""), "")))
    }

    @Test fun `capped at 2000 rows and 512 KB, oldest dropped first`() {
        val many = (0 until 2500).map { txn(it % 80, 100, "Merchant $it", id = it.toLong()) }
        val v = view(many)
        assertEquals(MonetaView.MAX_ROWS, v["rows"].size())
        assertTrue(v["truncated"].asBoolean())
        val long = (0 until 2000).map { txn(it % 80, 100, "M".repeat(60), id = it.toLong()) }
        val big = MonetaView.build(long, List(1500) { Subscription("S".repeat(40), 100, Cycle.MONTHLY, null, SubSource.DETECTED) }, emptyMap(), emptySet(), now, zone)
        assertTrue(big.toByteArray().size <= MonetaView.MAX_BYTES)
        val rows = jsonMapper().readTree(big)["rows"].toList()
        assertTrue(rows.size in 1 until 2000)
        assertEquals(rows.map { it["date"].asText() }.sortedDescending(), rows.map { it["date"].asText() })
    }

    @Test fun `publishes after a change at most every 10 minutes, and always on the full read`() {
        assertFalse(MonetaView.due(dirty = false, last = 0, now = now))
        assertTrue(MonetaView.due(dirty = true, last = 0, now = now))
        assertFalse(MonetaView.due(dirty = true, last = now - 9 * 60_000L, now = now))
        assertTrue(MonetaView.due(dirty = true, last = now - 10 * 60_000L, now = now))
        assertTrue(MonetaView.due(dirty = false, last = now - 60_000L, now = now, full = true))
    }
}
