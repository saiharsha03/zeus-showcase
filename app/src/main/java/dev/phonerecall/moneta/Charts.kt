package dev.phonerecall.moneta

import androidx.compose.runtime.Immutable
import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/*
 * Moneta's charts. A chart is a small typed spec (the same one Iris sends, lib/moneta_ops.py): what to draw and
 * which slice of the ledger. The phone computes the numbers from its own ledger and draws them natively; a spec
 * never carries data, images or markup.
 */

enum class ChartType { LINE, BAR, STACKED, DONUT, TABLE }
enum class Dim(val time: Boolean) { MONTH(true), WEEK(true), DAY(true), CATEGORY(false), MERCHANT(false), CARD(false) }
enum class Metric { SPEND, COUNT }

@Immutable
data class ChartQuery(
    val metric: Metric = Metric.SPEND,
    val by: Dim = Dim.MONTH,
    val split: Dim? = null,
    val months: Int = 6,
    val categories: Set<Category> = emptySet(),
    val merchants: List<String> = emptyList(),
    val cards: Set<String> = emptySet(),
    val kinds: Set<TxnKind> = emptySet(),
    val top: Int = 8,
    val cumulative: Boolean = false,
    val budgetLine: Boolean = false,
)

@Immutable
data class ChartSpec(val id: String, val type: ChartType, val title: String, val subtitle: String?, val query: ChartQuery, val json: String)

object ChartSpecs {
    val ID = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
    const val MAX_BYTES = 4 * 1024
    const val MAX_PINNED = 12
    private val LONG_DIGITS = Regex("\\d(?:[ -]?\\d){6,}")
    private val SPEC_KEYS = setOf("id", "type", "title", "subtitle", "query")
    private val QUERY_KEYS = setOf("metric", "by", "split", "months", "categories", "merchants", "cards", "kinds", "top", "cumulative", "reference")

    class Bad(reason: String) : Exception(reason)

    private fun strings(n: JsonNode?, name: String, pattern: Regex? = null): List<String> {
        if (n == null || n.isNull) return emptyList()
        if (!n.isArray || n.size() > 20) throw Bad("$name: at most 20")
        return n.map { x ->
            val s = x.takeIf { it.isTextual }?.asText()?.trim() ?: throw Bad("$name: text only")
            if (s.isEmpty() || s.length > 40 || LONG_DIGITS.containsMatchIn(s) || (pattern != null && !pattern.matches(s))) throw Bad("$name: short text only")
            s
        }
    }

    private fun int(n: JsonNode?, name: String, range: IntRange, default: Int): Int {
        if (n == null || n.isNull) return default
        if (!n.isInt || n.asInt() !in range) throw Bad("$name must be ${range.first} to ${range.last}")
        return n.asInt()
    }

    /** A spec from Iris, checked strictly: unknown keys, wrong types, oversize or markup-shaped text are refused. */
    fun parse(text: String): Result<ChartSpec> = runCatching {
        if (text.toByteArray().size > MAX_BYTES) throw Bad("spec over $MAX_BYTES bytes")
        val n = runCatching { jsonMapper().readTree(text) }.getOrNull()?.takeIf { it.isObject } ?: throw Bad("spec must be a JSON object")
        n.fieldNames().forEach { if (it !in SPEC_KEYS) throw Bad("unknown key $it") }
        val id = n.get("id")?.takeIf { it.isTextual }?.asText()?.takeIf { ID.matches(it) } ?: throw Bad("id is invalid")
        val type = ChartType.entries.firstOrNull { it.name.equals(n.get("type")?.asText(), true) } ?: throw Bad("type must be line, bar, stacked, donut or table")
        fun text(k: String, cap: Int, required: Boolean): String? {
            val v = n.get(k)
            if (v == null || v.isNull) { if (required) throw Bad("$k is required"); return null }
            val s = v.takeIf { it.isTextual }?.asText()?.trim() ?: throw Bad("$k must be text")
            if (s.length > cap || LONG_DIGITS.containsMatchIn(s) || s.contains('<')) throw Bad("$k: plain text, at most $cap")
            return s
        }
        val title = text("title", 60, true)!!.ifEmpty { throw Bad("title is required") }
        val q = n.get("query") ?: jsonMapper().createObjectNode()
        if (!q.isObject) throw Bad("query must be an object")
        q.fieldNames().forEach { if (it !in QUERY_KEYS) throw Bad("unknown query key $it") }
        fun dim(k: String, allowed: Set<Dim>): Dim? = q.get(k)?.takeIf { !it.isNull }?.asText()?.let { w -> allowed.firstOrNull { it.name.equals(w, true) } ?: throw Bad("$k is invalid") }
        val by = dim("by", Dim.entries.toSet()) ?: Dim.MONTH
        val split = dim("split", setOf(Dim.MONTH, Dim.CATEGORY, Dim.MERCHANT, Dim.CARD))
        if (split == by) throw Bad("split must differ from by")
        val metric = when (q.get("metric")?.asText() ?: "spend") { "spend" -> Metric.SPEND; "count" -> Metric.COUNT; else -> throw Bad("metric must be spend or count") }
        val cum = q.get("cumulative")?.let { if (!it.isBoolean) throw Bad("cumulative must be true or false"); it.asBoolean() } ?: false
        val ref = q.get("reference")?.takeIf { !it.isNull }?.asText()?.let { if (it != "budget") throw Bad("reference must be budget"); true } ?: false
        val kinds = strings(q.get("kinds"), "kinds").map { OpJson.kind(it) ?: throw Bad("unknown kind $it") }.toSet()
        ChartSpec(
            id, type, title, text("subtitle", 100, false),
            ChartQuery(
                metric, by, split, int(q.get("months"), "months", 1..24, 6), strings(q.get("categories"), "categories").map { Categories.named(it) }.toSet(),
                strings(q.get("merchants"), "merchants"), strings(q.get("cards"), "cards", Regex("\\d{4}")).toSet(), kinds,
                int(q.get("top"), "top", 1..12, 8), cum, ref,
            ),
            text,
        )
    }

    private fun spec(json: String) = parse(json).getOrThrow()

    /** Moneta's own charts, always there. Built from the same specs Iris can send. */
    val BUILT_IN: List<ChartSpec> = listOf(
        spec("""{"id":"spend-over-time","type":"line","title":"Spending by month","subtitle":"Last 12 months","query":{"by":"month","months":12}}"""),
        spec("""{"id":"categories-now","type":"donut","title":"Where it went this month","query":{"by":"category","months":1}}"""),
        spec("""{"id":"month-vs-month","type":"bar","title":"This month and last, by category","query":{"by":"category","split":"month","months":2,"top":6}}"""),
        spec("""{"id":"top-merchants","type":"bar","title":"Top merchants","subtitle":"Last 3 months","query":{"by":"merchant","months":3,"top":8}}"""),
        spec("""{"id":"subscription-costs","type":"stacked","title":"Subscriptions by month","subtitle":"Last 12 months","query":{"by":"month","split":"merchant","months":12,"categories":["Subscriptions"],"top":6}}"""),
        spec("""{"id":"budget-burn","type":"line","title":"This month against your budgets","subtitle":"Running total","query":{"by":"day","months":1,"cumulative":true,"reference":"budget"}}"""),
    )
}

@Immutable data class Series(val name: String, val values: List<Long>)

@Immutable data class ChartData(val labels: List<String>, val series: List<Series>, val reference: Long?, val metric: Metric) {
    val empty: Boolean get() = series.isEmpty() || series.all { s -> s.values.all { it == 0L } }
    fun total(): Long = series.sumOf { it.values.sum() }
}

object ChartEngine {
    const val OTHER = "Other"
    private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.US)
    private val WEEK = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    private fun key(t: Txn, d: Dim, zone: ZoneId): String = when (d) {
        Dim.MONTH -> YearMonth.from(day(t.at, zone)).toString()
        Dim.WEEK -> day(t.at, zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
        Dim.DAY -> day(t.at, zone).toString()
        Dim.CATEGORY -> t.category.word
        Dim.MERCHANT -> t.merchant
        Dim.CARD -> t.last4?.let { "••$it" } ?: "No card"
    }

    private fun label(k: String, d: Dim): String = when (d) {
        Dim.MONTH -> MONTH.format(YearMonth.parse(k))
        Dim.WEEK -> WEEK.format(LocalDate.parse(k))
        Dim.DAY -> LocalDate.parse(k).dayOfMonth.toString()
        else -> k
    }

    /** Every time bucket in the window, so a quiet month shows as zero instead of disappearing. */
    private fun timeKeys(d: Dim, from: LocalDate, to: LocalDate): List<String> = when (d) {
        Dim.MONTH -> generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.map { it.toString() }.toList()
        Dim.WEEK -> generateSequence(from.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) { it.plusWeeks(1) }.takeWhile { !it.isAfter(to) }.map { it.toString() }.toList()
        Dim.DAY -> generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.map { it.toString() }.toList()
        else -> emptyList()
    }

    fun compute(spec: ChartSpec, txns: List<Txn>, budgets: Map<Category, Long>, now: Long, zone: ZoneId): ChartData {
        val q = spec.query
        val today = day(now, zone)
        val from = YearMonth.from(today).minusMonths((q.months - 1).toLong()).atDay(1)
        val kinds = q.kinds.ifEmpty { if (q.metric == Metric.SPEND) setOf(TxnKind.SPEND, TxnKind.REFUND) else setOf(TxnKind.SPEND) }
        val rows = txns.filter { t ->
            t.counted && t.kind in kinds && !day(t.at, zone).isBefore(from) && !day(t.at, zone).isAfter(today) &&
                (q.categories.isEmpty() || t.category in q.categories) && (q.cards.isEmpty() || t.last4 in q.cards) &&
                (q.merchants.isEmpty() || q.merchants.any { t.merchant.contains(it, ignoreCase = true) })
        }
        fun value(t: Txn) = if (q.metric == Metric.COUNT) 1L else if (t.kind == TxnKind.REFUND) -t.cents else t.cents

        // x: every time bucket, or the biggest groups with the rest folded into "Other".
        val xKeys: List<String> = if (q.by.time) timeKeys(q.by, from, today) else {
            val totals = rows.groupBy { key(it, q.by, zone) }.mapValues { (_, l) -> l.sumOf(::value) }.filterValues { it > 0 }
            val ranked = totals.entries.sortedByDescending { it.value }.map { it.key }
            if (ranked.size > q.top) ranked.take(q.top) + OTHER else ranked
        }
        fun xOf(t: Txn): String = key(t, q.by, zone).let { if (q.by.time || it in xKeys) it else OTHER }

        // Series: one, or one per split value (biggest first; past eight, the rest is "Other": never a ninth hue).
        val sKeys: List<String> = if (q.split == null) listOf(spec.title) else {
            val totals = rows.groupBy { key(it, q.split, zone) }.mapValues { (_, l) -> l.sumOf(::value) }.filterValues { it > 0 }
            val ranked = if (q.split.time) totals.keys.sorted() else totals.entries.sortedByDescending { it.value }.map { it.key }
            val cap = minOf(q.top, 8)
            if (ranked.size > cap) ranked.take(cap - 1) + OTHER else ranked
        }
        fun sOf(t: Txn): String = if (q.split == null) spec.title else key(t, q.split, zone).let { if (it in sKeys) it else OTHER }

        val grid = sKeys.associateWith { LongArray(xKeys.size) }
        val xIndex = xKeys.withIndex().associate { it.value to it.index }
        for (t in rows) { val xi = xIndex[xOf(t)] ?: continue; grid[sOf(t)]?.let { it[xi] += value(t) } }
        val series = sKeys.map { s ->
            // A refund lowers a running total; on its own a bucket never shows below zero.
            val raw = grid.getValue(s).toList()
            val v = (if (q.cumulative) raw.runningReduce { a, b -> a + b } else raw).map { it.coerceAtLeast(0) }
            Series(if (q.split?.time == true) label(s, q.split) else s, v)
        }
        val ref = if (!q.budgetLine) null else (if (q.categories.isEmpty()) budgets.values.sum() else q.categories.sumOf { budgets[it] ?: 0 }).takeIf { it > 0 }
        return ChartData(xKeys.map { if (it == OTHER) it else label(it, q.by) }, series, ref, q.metric)
    }
}
