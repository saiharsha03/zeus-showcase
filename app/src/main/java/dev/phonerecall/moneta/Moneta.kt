package dev.phonerecall.moneta

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/*
 * Moneta: the owner's money, on the phone only. Built from bank notifications, Iris's category summaries and
 * his own entries. Nothing here is a tool, a memory source or a card for any model: Athena never sees
 * amounts. Money actions are proposals that open the bank's app; Zeus never
 * moves money.
 */

enum class Cycle(val word: String, val days: Int) { WEEKLY("Weekly", 7), MONTHLY("Monthly", 30), YEARLY("Yearly", 365) }

enum class SubSource(val word: String) { DETECTED("Seen on your cards"), IRIS("From Iris"), MANUAL("Added by you") }

@Immutable data class Subscription(val name: String, val cents: Long?, val cycle: Cycle, val next: LocalDate?, val source: SubSource, val bank: String? = null)

@Immutable data class CategorySpend(val category: Category, val cents: Long, val usual: Long?, val budget: Long?) {
    val over: Boolean get() = budget != null && cents > budget
}

@Immutable data class MonthView(val month: YearMonth, val total: Long, val usual: Long?, val categories: List<CategorySpend>)

/**
 * A charge worth a second look, with the reason in plain words. `rule` names the rule for Clio (no amounts).
 * `fraud`: the shapes card fraud takes (a test charge, a charge right after a decline, a new merchant at 3 AM), which
 * ring at once, even in quiet hours.
 */
@Immutable data class Unusual(val txn: Txn, val reason: String, val key: String, val rule: String = "", val fraud: Boolean = false)


object Money {
    fun fmt(cents: Long, whole: Boolean = false): String {
        val sign = if (cents < 0) "-" else ""
        val c = abs(cents)
        val dollars = "%,d".format(c / 100)
        return if (whole) "$sign$$dollars" else "$sign$$dollars.%02d".format(c % 100)
    }

    fun ym(at: Long, zone: ZoneId): YearMonth = YearMonth.from(Instant.ofEpochMilli(at).atZone(zone))

    /** Net spend per category for a month: purchases minus refunds, never below zero. */
    fun spend(txns: List<Txn>, month: YearMonth, zone: ZoneId): Map<Category, Long> {
        val out = HashMap<Category, Long>()
        for (t in txns) {
            if (!t.counted || ym(t.at, zone) != month) continue
            when (t.kind) {
                TxnKind.SPEND -> out[t.category] = (out[t.category] ?: 0) + t.cents
                TxnKind.REFUND -> out[t.category] = (out[t.category] ?: 0) - t.cents
                else -> Unit
            }
        }
        return out.mapValues { it.value.coerceAtLeast(0) }.filterValues { it > 0 }
    }

    /** Net spend in [from, to): purchases minus refunds, never below zero. */
    fun net(txns: List<Txn>, from: Long, to: Long): Long = txns.filter { it.counted && it.at in from until to }.sumOf {
        when (it.kind) { TxnKind.SPEND -> it.cents; TxnKind.REFUND -> -it.cents; else -> 0L }
    }.coerceAtLeast(0)

    /** One month by category, against the last three months ("usual") and his budgets. */
    fun month(txns: List<Txn>, month: YearMonth, zone: ZoneId, budgets: Map<Category, Long>): MonthView {
        val now = spend(txns, month, zone)
        val past = (1..3).map { spend(txns, month.minusMonths(it.toLong()), zone) }.filter { it.isNotEmpty() }
        fun usual(c: Category?): Long? = past.takeIf { it.isNotEmpty() }?.let { p -> p.sumOf { m -> if (c == null) m.values.sum() else m[c] ?: 0 } / p.size }
        val cats = (now.keys + budgets.keys).map { c -> CategorySpend(c, now[c] ?: 0, usual(c)?.takeIf { it > 0 }, budgets[c]) }
            .sortedWith(compareByDescending<CategorySpend> { it.cents }.thenBy { it.category.ordinal })
        return MonthView(month, now.values.sum(), usual(null)?.takeIf { it > 0 }, cats)
    }

    private fun day(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    private const val DAY = 86_400_000L

    /** Merchants a card thief tests or a stranger taps at: in a store, not online or delivered. */
    private val CARD_PRESENT = setOf(Category.GROCERIES, Category.DINING, Category.FUEL, Category.CAR, Category.FUN, Category.HEALTH)
    private val DELIVERED = Regex("(?i)uber eats|doordash|grubhub|instacart|amazon|online|\\.com")
    private val FOREIGN = Regex("(?i)\\b(intl|international|foreign|gmbh|ltd|plc|pvt|s\\.?a\\.?r\\.?l|srl|b\\.?v\\.?|london|paris|toronto|mexico|cancun|mumbai|delhi|bangalore|sydney|dubai|lagos|beijing|shanghai|moscow)\\b|\\.(co\\.uk|de|fr|in|cn|ru|br|mx)\\b")

    private fun hour(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).hour
    private fun card(t: Txn) = "••${t.last4}"

    /**
     * Charges worth a second look. `dismissed` are keys he already said were fine; `known` merchants he said were his
     * (normalised, [Reconcile.norm]); `travelling` is Argo's word on whether he was on a trip at that time.
     */
    fun unusual(
        txns: List<Txn>, now: Long, zone: ZoneId, dismissed: Set<String> = emptySet(), days: Int = 30,
        known: Set<String> = emptySet(), travelling: (Long) -> Boolean = { false },
    ): List<Unusual> {
        val live = txns.filter { it.mergedInto == null }.sortedBy { it.at }
        val spends = live.filter { it.kind == TxnKind.SPEND }
        val declines = live.filter { it.kind == TxnKind.DECLINED }
        val out = ArrayList<Unusual>()
        for ((i, t) in spends.withIndex()) {
            if (now - t.at > days * DAY) continue
            val before = spends.subList(0, i)
            val usd = before.filter { it.currency == "USD" }
            val key = "${t.at}:${t.cents}:${t.merchant}"
            val familiar = Reconcile.norm(t.merchant) in known || before.any { t.at - it.at < 365 * DAY && Reconcile.sameName(it.merchant, t.merchant) }
            val onCard = t.last4?.let { l -> usd.filter { it.last4 == l } }.orEmpty()
            val card90 = onCard.filter { t.at - it.at < 90 * DAY }
            val small = spends.filter { it.cents <= 200 && it.at <= t.at && t.at - it.at <= 3_600_000L }
            val decline = declines.lastOrNull { it.at <= t.at && t.at - it.at <= 3_600_000L && !Reconcile.sameName(it.merchant, t.merchant) }
            val odd = hour(t.at, zone) in 1..4 && (t.via == Via.WALLET || t.category in CARD_PRESENT) && !DELIVERED.containsMatchIn(t.merchant)
            val foreign = (t.currency != "USD" || FOREIGN.containsMatchIn(t.merchant)) && !travelling(t.at) && Reconcile.norm(t.merchant) !in known
            val median = usd.filter { it.category == t.category && t.at - it.at < 90 * DAY }.map { it.cents }.sorted().let { s -> s.getOrNull(s.size / 2)?.takeIf { s.size >= 3 } }
            val (rule, reason, fraud) = when {
                usd.any { it.merchant == t.merchant && it.cents == t.cents && t.at - it.at < DAY } -> Triple("twice", "Charged twice within a day", false)
                t.cents <= 200 && small.size >= 3 -> Triple("test", "${small.size} small charges within an hour: a card being tested?", true)
                t.cents <= 200 && !familiar -> Triple("test", "A small charge from a merchant you haven't used: a card being tested?", true)
                decline != null -> Triple("after-decline", "Charged right after a decline at ${decline.merchant}", true)
                odd && !familiar -> Triple("odd-hour", "At ${hour(t.at, zone)} AM, at a merchant you haven't used", true)
                odd -> Triple("odd-hour", "At ${hour(t.at, zone)} AM, an odd hour to be at ${t.merchant}", false)
                foreign -> Triple("foreign", if (t.currency != "USD") "Charged in ${t.currency}, and Argo has no trip for you" else "${t.merchant} sounds foreign, and Argo has no trip for you", false)
                t.currency != "USD" -> null
                t.cents >= 20_000 && !familiar -> Triple("new-merchant", "First charge from ${t.merchant}", false)
                t.last4 != null && onCard.isNotEmpty() && usd.size >= 10 && card90.size <= 1 && t.at - onCard.last().at > 45 * DAY ->
                    Triple("rare-card", "Card ${card(t)} hadn't been used in ${(t.at - onCard.last().at) / DAY} days", false)
                card90.size >= 5 && t.cents >= 10_000 && t.cents > card90.maxOf { it.cents } ->
                    Triple("card-max", "The most on ${card(t)} in 90 days (the most before was ${fmt(card90.maxOf { it.cents })})", false)
                median != null && t.cents >= 5_000 && t.cents >= median * 5 / 2 ->
                    Triple("category", "About ${t.cents / median.coerceAtLeast(1)}× your usual ${t.category.word.lowercase()} charge", false)
                else -> null
            } ?: continue
            if (key !in dismissed) out += Unusual(t, reason, key, rule, fraud)
        }
        // Three or more declines in a day: someone trying the card, or a card that needs a call to the bank.
        for (d in declines) {
            if (now - d.at > days * DAY) continue
            val run = declines.count { it.at <= d.at && d.at - it.at < DAY }
            val key = "declines:${d.at}:${d.merchant}"
            if (run >= 3 && key !in dismissed) out += Unusual(d, "$run declines within a day", key, "declines")
        }
        // A subscription that went up.
        for (s in recurring(txns, now, zone)) {
            val charges = spends.filter { it.merchant == s.name }
            if (charges.size < 2) continue
            val (prev, last) = charges[charges.size - 2] to charges.last()
            val key = "up:${last.at}:${s.name}"
            if (now - last.at <= days * DAY && last.cents > prev.cents * 11 / 10 && key !in dismissed && out.none { it.txn == last }) {
                out += Unusual(last, "${s.name} went up from ${fmt(prev.cents)}", key)
            }
        }
        return out.sortedByDescending { it.txn.at }
    }

    /** Subscriptions seen on his cards: the same merchant, a steady amount, on a weekly, monthly or yearly beat. */
    fun recurring(txns: List<Txn>, now: Long, zone: ZoneId): List<Subscription> =
        txns.filter { it.kind == TxnKind.SPEND && it.counted }.groupBy { it.merchant }.mapNotNull { (merchant, list) ->
            val sorted = list.sortedBy { it.at }
            if (sorted.size < 2) return@mapNotNull null
            val gaps = sorted.zipWithNext { a, b -> (b.at - a.at) / DAY }
            val cycle = Cycle.entries.firstOrNull { c -> gaps.all { g -> abs(g - c.days) <= maxOf(2, c.days / 10) } } ?: return@mapNotNull null
            val amounts = sorted.map { it.cents }.sorted()
            val median = amounts[amounts.size / 2]
            // Allow one price change, but not wildly different charges.
            if (sorted.count { abs(it.cents - median) > median / 4 } > 1) return@mapNotNull null
            // Weekly needs a longer run and a near-fixed price, or every grocery run would look like a subscription.
            if (cycle == Cycle.WEEKLY && (sorted.size < 4 || sorted.any { abs(it.cents - median) > median / 20 })) return@mapNotNull null
            val last = sorted.last()
            // A missed renewal means it stopped (or moved cards): only live ones count.
            if (now - last.at > (cycle.days + maxOf(3, cycle.days / 8)) * DAY) return@mapNotNull null
            val d = day(last.at, zone)
            val next = when (cycle) { Cycle.WEEKLY -> d.plusWeeks(1); Cycle.MONTHLY -> d.plusMonths(1); Cycle.YEARLY -> d.plusYears(1) }
            Subscription(merchant, last.cents, cycle, next, SubSource.DETECTED, last.bank)
        }

    /** Detected, Iris's and his own, one per name (his own entry wins, then Iris's renewal date). */
    fun subscriptions(detected: List<Subscription>, iris: List<Subscription>, manual: List<Subscription>): List<Subscription> {
        val byName = LinkedHashMap<String, Subscription>()
        for (s in detected + iris + manual) {
            val k = s.name.lowercase().replace(Regex("[^a-z0-9]"), "")
            val old = byName[k]
            byName[k] = if (old == null) s else s.copy(cents = s.cents ?: old.cents, next = s.next ?: old.next, bank = s.bank ?: old.bank)
        }
        return byName.values.sortedWith(compareBy(nullsLast()) { it.next })
    }
}

// ---- money actions: proposals only ----

enum class MoneyActionKind(val word: String) { PAY("Pay"), TRANSFER("Transfer"), CANCEL_SUBSCRIPTION("Cancel subscription"), DISPUTE("Dispute a charge") }

/** A money action Zeus suggests. Confirming it opens the bank's app; the owner does the rest there. */
@Immutable data class MoneyProposal(val kind: MoneyActionKind, val title: String, val bank: String?, val reason: String)

sealed interface MoneyEffect {
    /** The only thing a confirmed money proposal does: open his bank's app (or its site if the app is missing). */
    data class OpenBank(val pkg: String?, val bank: String?) : MoneyEffect
}

object MoneyActions {
    /** Money is never auto-approved, whatever an auto-approve list says. */
    @Suppress("UNUSED_PARAMETER")
    fun autoApproved(kind: MoneyActionKind, autoList: Set<String>): Boolean = false

    fun propose(kind: MoneyActionKind, about: String, bank: String?): MoneyProposal = MoneyProposal(
        kind,
        when (kind) {
            MoneyActionKind.PAY -> "Pay $about in ${bank ?: "your bank's app"}"
            MoneyActionKind.TRANSFER -> "Transfer in ${bank ?: "your bank's app"}"
            MoneyActionKind.CANCEL_SUBSCRIPTION -> "Cancel $about"
            MoneyActionKind.DISPUTE -> "Dispute the $about charge in ${bank ?: "your bank's app"}"
        },
        bank,
        "Zeus never moves money. Confirm opens ${bank ?: "the app"}; you finish it there.",
    )

    /** Runs only on his tap. Never pays, transfers or cancels: it can only open an app. */
    fun confirm(p: MoneyProposal, tappedByOwner: Boolean): MoneyEffect? {
        if (!tappedByOwner) return null
        return MoneyEffect.OpenBank(p.bank?.let { b -> BankApps.NAMES.entries.firstOrNull { it.value == b }?.key }, p.bank)
    }
}

/**
 * Odyssey's side of the money link ("Costco, ~$80"). Odyssey sets [visits] when it ships; Moneta only
 * asks "where was he at this time?" and shows the place next to the charge. Nothing leaves the phone.
 */
fun interface VisitLink {
    /** The place name of the visit covering `at`, or null. */
    fun placeAt(at: Long): String?
}

/** Argo's side: the trip a charge falls in ("Boston"), or null. Set by Argo; the city name is all it gives. */
fun interface TripLink {
    fun tripAt(at: Long): String?
}

object MonetaHooks {
    @Volatile var visits: VisitLink? = null
    @Volatile var trips: TripLink? = null
}
