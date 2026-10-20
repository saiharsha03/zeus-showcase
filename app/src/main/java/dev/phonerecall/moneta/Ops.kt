package dev.phonerecall.moneta

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Iris has full control of Moneta. Her ops, and his own edits on the screen,
 * apply directly: no proposal card. Each lands in the change log with a snapshot of what it replaced, so he can undo
 * any one of them. Nothing here moves money, and no op can store a full card number.
 */

data class TxnFields(
    val at: Long? = null, val cents: Long? = null, val merchant: String? = null, val category: Category? = null,
    val kind: TxnKind? = null, val last4: String? = null, val currency: String? = null, val note: String? = null,
    /** last4 was given as null on purpose (clear it). */
    val clearLast4: Boolean = false,
)

sealed interface MonetaOp {
    data class Add(val key: String, val f: TxnFields) : MonetaOp
    data class Edit(val ref: String, val f: TxnFields) : MonetaOp
    data class Recategorize(val ref: String, val category: Category) : MonetaOp
    data class Merge(val keep: String, val drop: List<String>) : MonetaOp
    /** A folded row back on its own; Moneta never folds it again by itself. */
    data class Unmerge(val ref: String) : MonetaOp
    data class Delete(val ref: String) : MonetaOp
    data class Budget(val category: Category, val cents: Long?) : MonetaOp
    data class SubAdd(val sub: Subscription) : MonetaOp
    data class SubRemove(val name: String) : MonetaOp
    data class ChartPut(val spec: ChartSpec) : MonetaOp
    data class ChartRemove(val id: String) : MonetaOp
}

/** Iris's op JSON (lib/moneta_ops.py), checked again here. Null with a reason when anything is off. */
object OpJson {
    private val KEY = Regex("^[A-Za-z0-9:_.-]{1,64}$")
    private val LONG_DIGITS = Regex("\\d(?:[ -]?\\d){6,}")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2})?)?$")
    private val FIELDS = setOf("date", "amount", "merchant", "category", "kind", "last4", "currency", "note")

    class Bad(reason: String) : Exception(reason)

    fun kind(word: String?): TxnKind? = when (word?.lowercase()?.trim()) {
        null, "" -> null
        "spend", "charge", "debit", "purchase", "expense" -> TxnKind.SPEND
        "refund", "credit", "return" -> TxnKind.REFUND
        "payment", "card payment" -> TxnKind.PAYMENT
        "transfer", "zelle", "venmo" -> TxnKind.TRANSFER
        "income", "deposit", "salary", "paycheck" -> TxnKind.INCOME
        "declined" -> TxnKind.DECLINED
        else -> null
    }

    fun date(s: String, zone: ZoneId): Long? {
        if (!DATE.matches(s.trim())) return null
        val t = s.trim().replace(' ', 'T')
        return runCatching {
            if (t.length == 10) LocalDate.parse(t).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
            else LocalDateTime.parse(if (t.length == 16) "$t:00" else t).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun text(n: JsonNode?, name: String, cap: Int): String? {
        if (n == null || n.isNull) return null
        if (!n.isTextual || n.asText().length > cap) throw Bad("$name must be text, at most $cap")
        if (LONG_DIGITS.containsMatchIn(n.asText())) throw Bad("$name looks like a card or account number")
        return n.asText().trim()
    }

    private fun cents(n: JsonNode?, name: String = "amount"): Long? {
        if (n == null || n.isNull) return null
        if (!n.isNumber || n.asDouble() <= 0 || n.asDouble() > 10_000_000) throw Bad("$name must be a positive number of dollars")
        return Math.round(n.asDouble() * 100)
    }

    private fun key(n: JsonNode?, name: String = "key"): String = n?.takeIf { it.isTextual && KEY.matches(it.asText()) }?.asText() ?: throw Bad("$name is invalid")

    fun fields(n: JsonNode?, zone: ZoneId): TxnFields {
        if (n == null || !n.isObject) throw Bad("fields must be an object")
        n.fieldNames().forEach { if (it !in FIELDS) throw Bad("unknown field $it") }
        val l4 = n.get("last4")
        if (l4 != null && !l4.isNull && !(l4.isTextual && Regex("\\d{4}").matches(l4.asText()))) throw Bad("last4 must be four digits")
        val cur = n.get("currency")?.takeIf { !it.isNull }?.asText()
        if (cur != null && !Regex("[A-Z]{3}").matches(cur)) throw Bad("currency must be like USD")
        return TxnFields(
            at = n.get("date")?.takeIf { !it.isNull }?.let { date(it.asText(), zone) ?: throw Bad("date must be YYYY-MM-DD") },
            cents = cents(n.get("amount")), merchant = text(n.get("merchant"), "merchant", 60),
            category = text(n.get("category"), "category", 40)?.let { Categories.named(it) },
            kind = n.get("kind")?.takeIf { !it.isNull }?.let { kind(it.asText()) ?: throw Bad("unknown kind") },
            last4 = l4?.takeIf { !it.isNull }?.asText(), currency = cur, note = text(n.get("note"), "note", 200),
            clearLast4 = l4 != null && l4.isNull,
        )
    }

    /** The op inside one gateway envelope: {"v":1, "op_id", "op", "args", "at", "from"}. */
    fun parse(env: JsonNode, zone: ZoneId): MonetaOp {
        val a = env.get("args") ?: throw Bad("no args")
        return when (env.get("op")?.asText()) {
            "add" -> {
                val f = fields((a.deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>()).apply { remove("key") }, zone)
                if (f.at == null || f.cents == null || f.merchant.isNullOrBlank()) throw Bad("add needs date, amount and merchant")
                MonetaOp.Add(key(a.get("key")), f)
            }
            "edit" -> MonetaOp.Edit(key(a.get("key")), fields(a.get("fields"), zone))
            "recategorize" -> MonetaOp.Recategorize(key(a.get("key")), Categories.named(text(a.get("category"), "category", 40) ?: throw Bad("category is required")))
            "merge" -> {
                val keep = key(a.get("keep"), "keep")
                val drop = a.get("drop")?.takeIf { it.isArray && it.size() in 1..20 }?.map { key(it, "drop") } ?: throw Bad("drop must list 1 to 20 keys")
                if (keep in drop) throw Bad("keep can't be dropped")
                MonetaOp.Merge(keep, drop)
            }
            "delete" -> MonetaOp.Delete(key(a.get("key")))
            "budget" -> MonetaOp.Budget(Categories.named(text(a.get("category"), "category", 40) ?: throw Bad("category is required")), cents(a.get("amount")))
            "sub_add" -> {
                val cycle = when (a.get("cycle")?.asText() ?: "monthly") { "weekly" -> Cycle.WEEKLY; "yearly" -> Cycle.YEARLY; "monthly" -> Cycle.MONTHLY; else -> throw Bad("cycle") }
                val next = a.get("next")?.takeIf { !it.isNull }?.asText()?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw Bad("next must be YYYY-MM-DD") }
                MonetaOp.SubAdd(Subscription(text(a.get("name"), "name", 40) ?: throw Bad("name is required"), cents(a.get("amount")), cycle, next, SubSource.IRIS, text(a.get("bank"), "bank", 30)))
            }
            "sub_remove" -> MonetaOp.SubRemove(text(a.get("name"), "name", 40) ?: throw Bad("name is required"))
            "chart_put" -> MonetaOp.ChartPut(ChartSpecs.parse(a.get("spec")?.toString() ?: throw Bad("spec is required")).getOrElse { throw Bad(it.message ?: "bad spec") })
            "chart_remove" -> MonetaOp.ChartRemove(a.get("id")?.asText()?.takeIf { ChartSpecs.ID.matches(it) } ?: throw Bad("id is invalid"))
            else -> throw Bad("unknown op")
        }
    }
}

/** Applies ops, keeps the change log and undoes changes. One instance per store; call on Moneta's thread. */
class MonetaOps(private val store: MonetaStore, private val zone: ZoneId = ZoneId.systemDefault(), private val clock: () -> Long = System::currentTimeMillis) {
    private val json = jsonMapper()

    private fun snap(t: Txn?): Map<String, Any?>? = t?.let {
        mapOf("id" to it.id, "at" to it.at, "cents" to it.cents, "kind" to it.kind.name, "merchant" to it.merchant, "category" to it.category.name, "bank" to it.bank,
            "last4" to it.last4, "source" to it.source.name, "note" to it.note, "key" to it.key, "currency" to it.currency, "merged" to it.mergedInto, "edited" to it.edited,
            "via" to it.via?.name, "pending" to it.pending)
    }

    private fun unsnap(m: Map<*, *>) = Txn(
        (m["id"] as Number).toLong(), (m["at"] as Number).toLong(), (m["cents"] as Number).toLong(), TxnKind.valueOf(m["kind"] as String), m["merchant"] as String,
        Category.valueOf(m["category"] as String), m["bank"] as String?, m["last4"] as String?, Source.valueOf(m["source"] as String), m["note"] as String?,
        m["key"] as String?, m["currency"] as String? ?: "USD", m["merged"] as String?, m["edited"] == true,
        (m["via"] as String?)?.let { Via.valueOf(it) }, m["pending"] == true,
    )

    private fun applyFields(t: Txn, f: TxnFields) = t.copy(
        at = f.at ?: t.at, cents = f.cents ?: t.cents, merchant = f.merchant?.takeIf { it.isNotBlank() } ?: t.merchant, category = f.category ?: t.category,
        kind = f.kind ?: t.kind, last4 = if (f.clearLast4) null else f.last4 ?: t.last4, currency = f.currency ?: t.currency, note = f.note ?: t.note, edited = true,
    )

    private fun txnUndo(priors: List<Pair<String, Txn?>>) = mapOf("txns" to priors.map { (ref, t) -> mapOf("ref" to ref, "prior" to snap(t)) })

    /** Applies one op. Returns the change id, or throws [OpJson.Bad] with a reason. */
    fun apply(op: MonetaOp, who: String): Long {
        fun need(ref: String) = store.byRef(ref) ?: throw OpJson.Bad("there is no transaction $ref")
        val (summary, undo) = when (op) {
            is MonetaOp.Add -> {
                val prior = store.byRef(op.key)
                val base = prior ?: Txn(at = op.f.at!!, cents = op.f.cents!!, kind = TxnKind.SPEND, merchant = op.f.merchant!!, category = Category.OTHER,
                    source = if (who == WHO_OWNER) Source.MANUAL else Source.IRIS, key = op.key)
                val t = applyFields(base, op.f).let { if (op.f.category == null && it.category == Category.OTHER) it.copy(category = Categories.of(it.merchant)) else it }
                store.put(t)
                "added ${t.merchant}, ${Money.fmt(t.cents)}" to txnUndo(listOf(op.key to prior))
            }
            is MonetaOp.Edit -> {
                val t = need(op.ref)
                store.put(applyFields(t, op.f))
                "edited ${t.merchant}" to txnUndo(listOf(op.ref to t))
            }
            is MonetaOp.Recategorize -> {
                val t = need(op.ref)
                store.put(t.copy(category = op.category, edited = true))
                "moved ${t.merchant} to ${op.category.word}" to txnUndo(listOf(op.ref to t))
            }
            is MonetaOp.Merge -> {
                val keep = need(op.keep)
                val drops = op.drop.map { need(it) }
                drops.forEach { store.put(it.copy(mergedInto = keep.ref, edited = true)) }
                "folded ${drops.size} duplicate${if (drops.size == 1) "" else "s"} into ${keep.merchant}" to txnUndo(drops.map { it.ref to it })
            }
            is MonetaOp.Unmerge -> {
                val t = need(op.ref)
                if (t.mergedInto == null) throw OpJson.Bad("${t.merchant} isn't folded into anything")
                store.put(t.copy(mergedInto = null, edited = true))
                store.keepApart(t.ref)
                "unmerged ${t.merchant}" to txnUndo(listOf(op.ref to t))
            }
            is MonetaOp.Delete -> {
                val t = need(op.ref)
                store.delete(t.id)
                "deleted ${t.merchant}, ${Money.fmt(t.cents)}" to txnUndo(listOf(op.ref to t))
            }
            is MonetaOp.Budget -> {
                val prior = store.budgets()
                store.saveBudgets(prior.toMutableMap().apply { if (op.cents == null) remove(op.category) else put(op.category, op.cents) })
                (if (op.cents == null) "removed the ${op.category.word} budget" else "set ${op.category.word} budget to ${Money.fmt(op.cents, whole = true)}") to
                    mapOf("budgets" to prior.mapKeys { it.key.name })
            }
            is MonetaOp.SubAdd -> {
                val prior = store.manualSubs()
                store.saveManualSubs(prior.filterNot { it.name.equals(op.sub.name, true) } + op.sub.copy(source = if (who == WHO_OWNER) SubSource.MANUAL else SubSource.IRIS))
                "added subscription ${op.sub.name}" to mapOf("subs" to prior.map { store.subMapOf(it) })
            }
            is MonetaOp.SubRemove -> {
                val prior = store.manualSubs()
                if (prior.none { it.name.equals(op.name, true) }) throw OpJson.Bad("no subscription called ${op.name}")
                store.saveManualSubs(prior.filterNot { it.name.equals(op.name, true) })
                "removed subscription ${op.name}" to mapOf("subs" to prior.map { store.subMapOf(it) })
            }
            is MonetaOp.ChartPut -> {
                val prior = store.charts()
                val others = prior.filter { ChartSpecs.parse(it).getOrNull()?.id != op.spec.id }
                if (others.size >= ChartSpecs.MAX_PINNED) throw OpJson.Bad("at most ${ChartSpecs.MAX_PINNED} charts; remove one first")
                store.saveCharts(others + op.spec.json)
                "pinned the chart \"${op.spec.title}\"" to mapOf("charts" to prior)
            }
            is MonetaOp.ChartRemove -> {
                val prior = store.charts()
                val left = prior.filter { ChartSpecs.parse(it).getOrNull()?.id != op.id }
                if (left.size == prior.size) throw OpJson.Bad("no chart ${op.id}")
                store.saveCharts(left)
                "removed a chart" to mapOf("charts" to prior)
            }
        }
        return store.logChange(clock(), who, summary, undo)
    }

    /** Takes one change back. False when it is gone, already undone, or can't be undone. */
    fun undo(changeId: Long, who: String = WHO_OWNER): Boolean {
        val u = store.undoOf(changeId) ?: return false
        (u["txns"] as? List<*>)?.forEach { e ->
            val m = e as? Map<*, *> ?: return@forEach
            val ref = m["ref"] as String
            val prior = m["prior"] as? Map<*, *>
            if (prior == null) store.byRef(ref)?.let { store.delete(it.id) } else store.restore(unsnap(prior))
        }
        @Suppress("UNCHECKED_CAST")
        (u["budgets"] as? Map<String, Number>)?.let { b -> store.saveBudgets(b.entries.associate { Category.valueOf(it.key) to it.value.toLong() }) }
        @Suppress("UNCHECKED_CAST")
        (u["subs"] as? List<Map<String, Any?>>)?.let { s -> store.saveManualSubs(s.map { store.subOf(it, SubSource.MANUAL) }) }
        @Suppress("UNCHECKED_CAST")
        (u["charts"] as? List<String>)?.let { store.saveCharts(it) }
        store.markUndone(changeId)
        val what = store.changes(500).firstOrNull { it.id == changeId }
        store.logChange(clock(), who, "undid: ${what?.who ?: ""} ${what?.summary ?: ""}".trim(), null)
        return true
    }

    /** Ops from the gateway, in order. Already-applied ids are skipped; a bad op is logged and skipped. Returns the ids to ack. */
    fun applyEnvelopes(envs: List<String>): List<String> {
        val done = store.appliedOps()
        val acked = ArrayList<String>()
        for (raw in envs) {
            val n = runCatching { json.readTree(raw) }.getOrNull() ?: continue
            val id = n.get("op_id")?.asText()?.takeIf { Regex("[0-9a-f]{24}").matches(it) } ?: continue
            acked += id
            if (id in done) continue
            try { apply(OpJson.parse(n, zone), WHO_IRIS) }
            catch (e: OpJson.Bad) { store.logChange(clock(), WHO_IRIS, "op refused: ${e.message}", null) }
        }
        store.addApplied(acked)
        return acked
    }

    companion object {
        const val WHO_IRIS = "Iris"
        const val WHO_OWNER = "The owner"
        /** Moneta's own folds of duplicate alerts. */
        const val WHO_MONETA = "Moneta"
    }
}

/** Iris's money log through /api/iris/ledger. */
object Ledger {
    private const val DAY = 86_400_000L

    data class Page(val rows: List<Txn>, val next: Long, val more: Boolean, val subs: List<Subscription>)

    fun parse(text: String, zone: ZoneId): Page {
        val n = jsonMapper().readTree(text)
        val rows = n.get("rows")?.mapNotNull { r ->
            val id = r.get("id")?.asLong() ?: return@mapNotNull null
            val cents = r.get("cents")?.asLong()?.takeIf { it > 0 } ?: return@mapNotNull null
            val at = OpJson.date(r.get("date")?.asText().orEmpty().take(19), zone) ?: return@mapNotNull null
            val status = r.get("status")?.asText().orEmpty().lowercase()
            val kind = if (Regex("declin|cancel|fail|void").containsMatchIn(status)) TxnKind.DECLINED else OpJson.kind(r.get("kind")?.asText()) ?: TxnKind.SPEND
            val merchant = Merchants.clean(r.get("merchant")?.asText().orEmpty()).ifBlank { "Iris entry" }
            val cat = r.get("category")?.asText().orEmpty().takeIf { it.isNotBlank() }?.let { Categories.named(it) }?.takeIf { it != Category.OTHER } ?: Categories.of(merchant)
            Txn(
                at = at, cents = cents, kind = kind, merchant = merchant, category = cat, last4 = r.get("last4")?.asText()?.takeIf { Regex("\\d{4}").matches(it) },
                source = Source.IRIS, key = "iris:$id", currency = r.get("currency")?.asText()?.takeIf { Regex("[A-Z]{3}").matches(it) } ?: "USD",
                pending = status.contains("pending"),
            )
        }.orEmpty()
        val subs = n.get("subscriptions")?.mapNotNull { s ->
            if (s.get("status")?.asText()?.lowercase() in setOf("cancelled", "canceled", "inactive")) return@mapNotNull null
            val name = s.get("name")?.asText()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cycle = when (s.get("billing_cycle")?.asText()?.lowercase()) { "weekly" -> Cycle.WEEKLY; "yearly", "annual", "annually" -> Cycle.YEARLY; else -> Cycle.MONTHLY }
            Subscription(name.take(40), s.get("cents")?.takeIf { it.isNumber }?.asLong(), cycle, s.get("next_billing_date")?.asText()?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }, SubSource.IRIS)
        }.orEmpty()
        return Page(rows, n.get("next")?.asLong() ?: 0, n.get("more")?.asBoolean() ?: false, subs)
    }

    /**
     * Folds Iris's rows into the store: new rows added, unchanged ones refreshed, rows someone edited left alone.
     * Bank alerts are then folded into Iris's rows for the same charge, one to one ([Reconcile.pair]): the same amount or
     * a tip or pump hold, within a day and a half, never across two cards or kinds. `full`: the whole log was read, so Iris's rows that are gone from it are removed.
     * Returns a one-line summary, or null when nothing changed.
     */
    fun merge(store: MonetaStore, rows: List<Txn>, full: Boolean, now: Long = System.currentTimeMillis()): String? {
        var added = 0; var updated = 0; var folded = 0; var removed = 0
        for (r in rows) {
            val old = store.byRef(r.key!!)
            if (old?.edited == true) continue
            if (old == null) added++ else if (old.copy(id = 0) != r) updated++ else continue
            store.put(r.copy(id = old?.id ?: 0))
        }
        val all = store.txns(now - 120 * DAY)
        val apart = store.apart()
        val claimed = all.mapNotNull { it.mergedInto }.toSet()
        val phone = all.filter { it.source == Source.NOTIFICATION && it.mergedInto == null && it.ref !in apart }
        val iris = all.filter { it.source == Source.IRIS && it.mergedInto == null && it.ref !in claimed }
        for ((p, i) in Reconcile.pair(phone, iris)) { store.put(p.copy(mergedInto = i.ref)); folded++ }
        if (full) {
            val keep = rows.mapNotNull { it.key }.toSet()
            store.txns().filter { it.source == Source.IRIS && it.key?.startsWith("iris:") == true && !it.key.startsWith("iris:op-") && !it.edited && it.key !in keep }
                .forEach { store.delete(it.id); removed++ }
        }
        if (added + updated + folded + removed == 0) return null
        return listOfNotNull(
            "$added new".takeIf { added > 0 }, "$updated updated".takeIf { updated > 0 },
            "$folded matched to bank alerts".takeIf { folded > 0 }, "$removed gone from her log".takeIf { removed > 0 },
        ).joinToString(", ")
    }

    const val FULL_EVERY = DAY
}
