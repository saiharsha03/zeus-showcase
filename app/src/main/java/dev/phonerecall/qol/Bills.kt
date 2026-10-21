package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.When
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A bill or statement notice: whose (the app's label), its app, the due date when it said one, when it came. No amounts. */
data class Bill(val bank: String = "", val pkg: String = "", val due: String? = null, val at: Long = 0)

/**
 * Bill due pin: a "payment due" or "statement ready" notice stays on Kairos until he marks it Paid or a card payment
 * to that bank shows up in Moneta. "Open <bank>" opens the bank's app; Zeus never pays.
 */
object Bills {
    private val DUE = Regex("(?i)\\b(payment (is )?due|due (on|by|date)|minimum payment|statement (is )?(ready|available)|new statement|bill (is )?(ready|due|available)|amount due)\\b")
    private val DONE = Regex("(?i)\\b(payment (received|posted|confirmed|successful|scheduled)|thank(s| you) for (your )?payment|you paid|autopay (is )?(on|scheduled|set))\\b")
    private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun parse(pkg: String, app: String, title: String, text: String, at: Long, today: LocalDate): Bill? {
        val all = "$title\n$text"
        if (!DUE.containsMatchIn(all) || DONE.containsMatchIn(all)) return null
        return Bill(app.ifBlank { pkg }, pkg, When.date(all, today)?.toString(), at)
    }

    /** One per bank: a newer notice replaces the older. */
    fun add(list: List<Bill>, b: Bill): List<Bill> = list.filter { it.bank != b.bank } + b

    /** Kept until paid, a payment to that bank, 3 days after the due date, or 35 days. */
    fun open(list: List<Bill>, payments: List<Pair<String?, Long>>, today: LocalDate, now: Long): List<Bill> = list.filter { b ->
        val paid = payments.any { (bank, at) -> at > b.at && bank != null && (bank.contains(b.bank, true) || b.bank.contains(bank, true)) }
        val stale = b.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { today.isAfter(it.plusDays(3)) } ?: (now - b.at > 35 * 86_400_000L)
        !paid && !stale
    }

    fun card(ctx: MomentContext): MomentCard? {
        val b = ctx.qol.bills.minByOrNull { it.due ?: "9999" } ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.BILL_DUE
        val key = "${b.bank}:${b.at}"
        val due = b.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val title = when {
            due == null -> "${b.bank}: bill ready"
            due == ctx.now.toLocalDate() -> "${b.bank} bill due today"
            due == ctx.now.toLocalDate().plusDays(1) -> "${b.bank} bill due tomorrow"
            else -> "${b.bank} bill due ${SHORT.format(due)}"
        }
        return MomentCard(
            k, key,
            CardContent(
                title = title, body = "Marked paid when Moneta sees the payment.",
                actions = listOf(
                    CardAction("Open ${b.bank}", ActionRole.MOMENT_GO, QolRules.payload(k, key, "open")),
                    CardAction("Paid", ActionRole.MOMENT_GO, QolRules.payload(k, key, "paid")),
                    QolRules.never(k, key),
                ),
            ),
            fit = if (due != null && !due.isAfter(ctx.now.toLocalDate().plusDays(1))) 22f else 12f,
            plans = emptyMap(), opens = mapOf("open" to "app:${b.pkg}"), keep = setOf("open"),
        )
    }
}
