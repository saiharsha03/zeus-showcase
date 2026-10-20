package dev.phonerecall.moneta

import androidx.compose.runtime.Immutable
import kotlin.math.abs

/*
 * One purchase, one row. A tap can alert from the bank's app, Google Wallet and a bank text within a minute, and
 * Iris logs the same charge from her Telegram bank alerts hours later, sometimes before the phone hears of it. The
 * extra rows are folded (mergedInto), never deleted: each fold is in the change log with an undo, and any folded row
 * can be unmerged from the screen, after which Moneta leaves it apart for good.
 */

enum class Recon(val word: String) { MATCHED("Matched with Iris"), PHONE("Only from bank alert"), IRIS("Only from Iris") }

/** A row the daily check wants him to look at: a missed alert, or a duplicate Moneta couldn't pair. */
@Immutable data class Check(val txn: Txn, val reason: String, val key: String)

object Reconcile {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    /** Alerts for one purchase from the app, the wallet and a text land within this of each other. */
    const val PHONE_WINDOW = 15 * MIN
    /** Iris's log is a day or so behind (or ahead of) the phone. */
    const val IRIS_WINDOW = 36 * HOUR
    /** A pending charge is replaced by its posted one within this. */
    private const val POSTING_WINDOW = 3 * DAY
    /** Holds a pump puts on the card before the fill-up's real amount. */
    private val FUEL_HOLDS = setOf(100L, 5_000L, 7_500L, 10_000L, 12_500L, 15_000L, 17_500L, 20_000L)
    private val LEADING = Regex("(?i)^(pos|dbt|debit|purchase|chk|ach|card)\\s+")

    private const val MERCHANT = 1000
    private const val EXACT = 100
    private const val CARD = 50

    /** A merchant name as the matcher compares it: no "SQ *", "TST*", "POS", store numbers, punctuation or case. */
    fun norm(m: String): String = Merchants.clean(m.trim().replace(LEADING, "")).lowercase().replace(Regex("[^a-z0-9]"), "")

    fun sameName(a: String, b: String): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || (minOf(x.length, y.length) >= 4 && (x.startsWith(y) || y.startsWith(x)))
    }

    fun sameMerchant(a: Txn, b: Txn): Boolean = !noMerchant(a) && !noMerchant(b) && sameName(a.merchant, b.merchant)

    fun noMerchant(t: Txn): Boolean = t.merchant.isBlank() || t.merchant == BankParser.NO_MERCHANT

    private fun cardsAgree(a: Txn, b: Txn) = a.last4 == null || b.last4 == null || a.last4 == b.last4

    /** 2 for the same amount, 1 for a tip on a meal or a pump's hold, null when the amounts can't be one charge. */
    fun amounts(a: Txn, b: Txn): Int? {
        if (a.cents == b.cents) return 2
        val cats = setOf(a.category, b.category)
        val lo = minOf(a.cents, b.cents); val hi = maxOf(a.cents, b.cents)
        if ((Category.DINING in cats || Category.FUEL in cats) && hi * 10 <= lo * 13) return 1
        if (Category.FUEL in cats && (a.cents in FUEL_HOLDS || b.cents in FUEL_HOLDS)) return 1
        return null
    }

    /**
     * Two phone alerts for one purchase: same amount and currency within 15 minutes, of the same kind, from different
     * kinds of alert (or the same app re-posting within two minutes), and the same card, the same merchant, or one
     * side naming no merchant. Two different cards never match.
     */
    fun phoneDup(a: Txn, b: Txn): Boolean {
        if (a.cents != b.cents || a.currency != b.currency || a.kind != b.kind || abs(a.at - b.at) > PHONE_WINDOW || !cardsAgree(a, b)) return false
        if (a.via == b.via && !(a.bank == b.bank && abs(a.at - b.at) <= 2 * MIN)) return false
        return (a.last4 != null && a.last4 == b.last4) || sameMerchant(a, b) || noMerchant(a) || noMerchant(b)
    }

    /** Of two alerts for one purchase, the one kept (bank app over wallet over text, then the earlier), filled in from the other. */
    fun keep(a: Txn, b: Txn): Pair<Txn, Txn> {
        val ra = a.via?.rank ?: 0; val rb = b.via?.rank ?: 0
        val (k, d) = if (ra > rb || (ra == rb && a.at <= b.at)) a to b else b to a
        val merchant = if (noMerchant(k) && !noMerchant(d)) d.merchant else k.merchant
        return k.copy(
            at = minOf(a.at, b.at), merchant = merchant, category = if (merchant == k.merchant) k.category else d.category,
            last4 = k.last4 ?: d.last4, bank = if (k.via == Via.WALLET && d.via == Via.APP) d.bank else k.bank ?: d.bank,
        ) to d
    }

    /** How well a phone alert and one of Iris's rows describe one charge (higher is better), or null when they can't. */
    fun score(p: Txn, i: Txn): Int? {
        if (p.kind != i.kind || p.currency != i.currency || abs(p.at - i.at) > IRIS_WINDOW || !cardsAgree(p, i)) return null
        val amount = amounts(p, i) ?: return null
        val merchant = sameMerchant(p, i)
        // A tip or a pump hold only with the same merchant; otherwise the same category at least.
        if (!merchant && (amount < 2 || p.category != i.category)) return null
        val card = if (p.last4 != null && p.last4 == i.last4) CARD else 0
        return (if (merchant) MERCHANT else 0) + (if (amount == 2) EXACT else 0) + card - (abs(p.at - i.at) / HOUR).toInt()
    }

    /**
     * Phone alerts paired with Iris's rows, one to one, best pairs first. A pair on category alone counts only when
     * it is the alert's one candidate and the alert is that row's one claimant.
     */
    fun pair(phone: List<Txn>, iris: List<Txn>): List<Pair<Txn, Txn>> {
        val byTime = iris.sortedBy { it.at }
        val times = byTime.map { it.at }
        val scored = phone.map { p ->
            var from = times.binarySearch(p.at - IRIS_WINDOW).let { if (it < 0) -it - 1 else it }
            while (from > 0 && times[from - 1] >= p.at - IRIS_WINDOW) from--
            val near = ArrayList<Pair<Txn, Int>>()
            var j = from
            while (j < byTime.size && times[j] <= p.at + IRIS_WINDOW) { score(p, byTime[j])?.let { near += byTime[j] to it }; j++ }
            p to near
        }
        val claimants = HashMap<String, Int>()
        scored.forEach { (_, near) -> near.forEach { (i, _) -> claimants.merge(i.ref, 1, Int::plus) } }
        val all = scored.flatMap { (p, near) ->
            near.filter { (i, s) -> s >= MERCHANT || (near.size == 1 && claimants[i.ref] == 1) }.map { (i, s) -> Triple(p, i, s) }
        }.sortedByDescending { it.third }
        val usedP = HashSet<String>(); val usedI = HashSet<String>()
        return all.mapNotNull { (p, i, _) -> if (p.ref in usedP || i.ref in usedI) null else { usedP += p.ref; usedI += i.ref; p to i } }
    }

    /** A posted charge replacing its pending authorization: same merchant and card, the amount in band, a little later. */
    fun replaces(posted: Txn, pending: Txn): Boolean =
        pending.pending && !posted.pending && posted.ref != pending.ref && posted.kind == pending.kind && posted.currency == pending.currency &&
            posted.at >= pending.at && posted.at - pending.at <= POSTING_WINDOW && cardsAgree(posted, pending) && sameMerchant(posted, pending) && amounts(posted, pending) != null

    /**
     * A new bank alert into the store. It folds into an alert already there for the same purchase (or takes that one's
     * place when it is the richer), into Iris's row when she logged it first, or replaces its own pending charge.
     * Every fold goes through the change log, undoable. Returns the stored row and whether anything was folded.
     */
    fun insert(store: MonetaStore, ops: MonetaOps, t: Txn): Pair<Txn, Boolean> {
        val all = store.txns(t.at - IRIS_WINDOW - POSTING_WINDOW)
        val apart = store.apart()
        fun stored(x: Txn) = x.copy(id = store.add(x))
        fun fold(keep: Txn, drop: Txn) = runCatching { ops.apply(MonetaOp.Merge(keep.ref, listOf(drop.ref)), MonetaOps.WHO_MONETA) }

        val phone = all.filter { it.source == Source.NOTIFICATION && it.ref !in apart && (it.mergedInto == null || it.mergedInto.startsWith("iris:")) }
        phone.firstOrNull { phoneDup(it, t) }?.let { old ->
            // That alert is already matched to Iris's row: this one joins it there.
            old.mergedInto?.let { target -> val row = stored(t); store.byRef(target)?.let { fold(it, row) }; return row to true }
            val (k, _) = keep(old, t)
            if ((t.via?.rank ?: 0) > (old.via?.rank ?: 0)) { val row = stored(k); fold(row, old); return row to true }
            store.put(k.copy(id = old.id))
            val row = stored(t)
            fold(k.copy(id = old.id), row)
            return row to true
        }
        val claimed = all.mapNotNull { it.mergedInto }.toSet()
        val iris = all.filter { it.source == Source.IRIS && it.mergedInto == null && it.ref !in claimed }
        pair(listOf(t), iris).firstOrNull()?.let { (_, i) -> val row = stored(t); fold(i, row); return row to true }
        phone.firstOrNull { it.mergedInto == null && replaces(t, it) }?.let { old -> val row = stored(t); fold(row, old); return row to true }
        return stored(t) to false
    }

    /** Matched, only from a bank alert, or only from Iris: for every row shown. Manual rows have none. */
    fun status(txns: List<Txn>): Map<String, Recon> {
        val folded = txns.filter { it.mergedInto != null }.groupBy { it.mergedInto!! }
        return txns.filter { it.mergedInto == null && it.source != Source.MANUAL }.associate { t ->
            t.ref to when {
                t.source == Source.IRIS && folded[t.ref].orEmpty().any { it.source == Source.NOTIFICATION } -> Recon.MATCHED
                t.source == Source.IRIS -> Recon.IRIS
                else -> Recon.PHONE
            }
        }
    }

    /**
     * The daily check: bank alerts over three days old that Iris's log never matched, and Iris's rows no alert came for,
     * from the last two weeks. Either can be a missed notification or a duplicate. Only once Iris has synced.
     */
    fun checks(txns: List<Txn>, now: Long, synced: Boolean, dismissed: Set<String>): List<Check> {
        if (!synced) return emptyList()
        val withPhone = txns.filter { it.mergedInto != null && it.source == Source.NOTIFICATION }.mapNotNull { it.mergedInto }.toSet()
        return txns.filter { it.mergedInto == null && (it.kind == TxnKind.SPEND || it.kind == TxnKind.REFUND) && now - it.at <= 14 * DAY }.mapNotNull { t ->
            val reason = when {
                t.source == Source.NOTIFICATION && now - t.at > 72 * HOUR -> "A bank alert Iris's log never matched. A charge she missed, or a duplicate."
                t.source == Source.IRIS && t.key?.startsWith("iris:op-") == false && t.ref !in withPhone -> "In Iris's log, but no bank alert came. A missed notification, or a duplicate."
                else -> null
            } ?: return@mapNotNull null
            Check(t, reason, "check:${t.ref}").takeIf { it.key !in dismissed }
        }.sortedByDescending { it.txn.at }.take(8)
    }
}
