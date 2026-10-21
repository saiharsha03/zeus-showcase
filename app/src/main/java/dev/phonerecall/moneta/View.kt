package dev.phonerecall.moneta

import com.anthropic.core.jsonMapper
import java.time.Instant
import java.time.ZoneId

/**
 * Moneta's picture for Iris, through PUT /api/moneta/view (docs/ZEUS-GATEWAY.md): the last 90 days newest first, his
 * subscriptions and budgets, and this week against a usual week. The owner asked for it (10-20) so Iris sees what the
 * phone caught from his bank alerts; those rows are tagged source "phone" so she doesn't log them twice. Iris already
 * keeps his amounts in owner.db, so they go as they are; everything else follows the ledger route's rules: only the
 * last four card digits, no 7+ digit runs and nothing from "balance" on in merchant text, never a note, the alert
 * itself or anything from Calypso. Folded duplicates stay on the phone.
 */
object MonetaView {
    const val DAYS = 90
    const val MAX_ROWS = 2000
    const val MAX_BYTES = 512 * 1024
    /** At most one publish per 10 minutes after a change; the daily full read publishes regardless. */
    const val EVERY = 10 * 60_000L
    private const val DAY = 86_400_000L
    private val LONG_DIGITS = Regex("""\d(?:[ -]?\d){6,}""")
    private val BALANCE = Regex("""(?i)\b(?:avail(?:able)?\.?\s+)?bal(?:ance)?\b.*""")
    private val FOUR = Regex("""\d{4}""")

    fun scrub(text: String, cap: Int = 60): String = LONG_DIGITS.replace(BALANCE.replace(text, ""), "").split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ").take(cap)

    /** Publish now? Only when something changed and the last one is 10 minutes old, or on the daily full read. */
    fun due(dirty: Boolean, last: Long, now: Long, full: Boolean = false): Boolean = full || dirty && now - last >= EVERY

    fun source(t: Txn): String = if (t.source == Source.IRIS) "iris" else "phone"

    fun status(t: Txn): String = when {
        t.kind == TxnKind.DECLINED -> "declined"
        t.pending -> "pending"
        else -> "posted"
    }

    /** The JSON body, at most [MAX_ROWS] rows and [MAX_BYTES] (oldest rows dropped first). `flagged`: refs of fraud-grade flags. */
    fun build(txns: List<Txn>, subs: List<Subscription>, budgets: Map<Category, Long>, flagged: Set<String>, now: Long, zone: ZoneId): String {
        var rows = txns.filter { it.mergedInto == null && it.at >= now - DAYS * DAY && it.at <= now + DAY }.sortedByDescending { it.at }
        var truncated = rows.size > MAX_ROWS
        rows = rows.take(MAX_ROWS)
        val usual = (1..4).map { Money.net(txns, now - (it + 1) * 7 * DAY, now - it * 7 * DAY) }.filter { it > 0 }.takeIf { it.size >= 2 }?.average()?.toLong()
        fun body() = jsonMapper().writeValueAsString(linkedMapOf(
            "generated" to now,
            "rows" to rows.map { t ->
                linkedMapOf<String, Any?>(
                    "id" to t.ref, "date" to Instant.ofEpochMilli(t.at).atZone(zone).toLocalDate().toString(), "cents" to t.cents,
                    "currency" to t.currency, "merchant" to scrub(t.merchant), "category" to t.category.word, "kind" to t.kind.name.lowercase(),
                    "last4" to t.last4?.takeIf { FOUR.matches(it) }, "source" to source(t), "status" to status(t),
                ).apply { if (t.ref in flagged) put("fraud_flag", true) }
            },
            "subscriptions" to subs.map { s ->
                mapOf("name" to scrub(s.name, 40), "cents" to s.cents, "cycle" to s.cycle.name.lowercase(), "next" to s.next?.toString(), "source" to s.source.name.lowercase())
            },
            "budgets" to budgets.mapKeys { it.key.word },
            "week" to mapOf("spent_cents" to Money.net(txns, now - 7 * DAY, now + 1), "usual_cents" to usual),
            "truncated" to truncated,
        ))
        var out = body()
        while (out.toByteArray().size > MAX_BYTES && rows.isNotEmpty()) {
            rows = rows.take(rows.size * 3 / 4)
            truncated = true
            out = body()
        }
        return out
    }
}
