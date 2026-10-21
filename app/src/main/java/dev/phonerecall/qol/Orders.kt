package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A refund a shop said it sent: the shop and when. Never the amount. */
data class Refund(val shop: String = "", val at: Long = 0)

/** Shops by app or name, and each one's orders page. */
object Shops {
    private val BY_PKG = mapOf(
        "com.amazon.mShop.android.shopping" to "Amazon", "com.costco.app.android" to "Costco", "com.bestbuy.android" to "Best Buy",
        "com.walmart.android" to "Walmart", "com.target.ui" to "Target",
    )
    private val NAMES = listOf("Amazon", "Costco", "Best Buy", "Walmart", "Target")
    private val ORDERS = mapOf(
        "Amazon" to "https://www.amazon.com/gp/css/order-history", "Costco" to "https://www.costco.com/OrderStatusCmd",
        "Best Buy" to "https://www.bestbuy.com/profile/ss/orders", "Walmart" to "https://www.walmart.com/orders", "Target" to "https://www.target.com/orders",
    )

    fun of(pkg: String, text: String): String? = BY_PKG[pkg] ?: NAMES.firstOrNull { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
    fun orders(shop: String): String = ORDERS[shop] ?: "https://www.google.com/search?q=" + java.net.URLEncoder.encode("$shop orders", "UTF-8")
}

/** Refund watch: promised, and after 7 days still no matching refund in Moneta. */
object Refunds {
    private val SAID = Regex("(?i)\\b(refund (has been |was |is )?(issued|processed|initiated|approved|on its way|sent)|we('ve| have)? refunded|your refund|refund of)\\b")
    private val SHORT = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    const val WAIT_MS = 7 * 86_400_000L
    const val GIVE_UP_MS = 45 * 86_400_000L

    fun parse(pkg: String, title: String, text: String, at: Long): Refund? {
        val all = "$title\n$text"
        if (!SAID.containsMatchIn(all)) return null
        return Shops.of(pkg, all)?.let { Refund(it, at) }
    }

    /** Still waiting: no refund from that shop in Moneta since, and not given up. */
    fun waiting(list: List<Refund>, refunds: List<Pair<String, Long>>, now: Long): List<Refund> = list.filter { r ->
        now - r.at < GIVE_UP_MS && refunds.none { (merchant, at) -> at >= r.at - 86_400_000L && merchant.contains(r.shop, true) }
    }

    fun card(ctx: MomentContext): MomentCard? {
        val ms = ctx.now.toInstant().toEpochMilli()
        val r = ctx.qol.refunds.filter { ms - it.at >= WAIT_MS }.minByOrNull { it.at } ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.REFUND_WATCH
        val key = "${r.shop}:${r.at}"
        return MomentCard(
            k, key,
            CardContent(
                title = "${r.shop} refund not in yet",
                body = "Promised ${SHORT.format(Instant.ofEpochMilli(r.at).atZone(ctx.now.zone))}; Moneta hasn't seen it.",
                actions = listOf(
                    CardAction("Orders", ActionRole.MOMENT_GO, QolRules.payload(k, key, "orders")),
                    CardAction("Got it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")),
                    QolRules.never(k, key),
                ),
            ),
            fit = 12f, plans = emptyMap(), opens = mapOf("orders" to Shops.orders(r.shop)), keep = setOf("orders"),
        )
    }
}

/** A return window a shop stated: the shop and the last day (ISO). */
data class ReturnBy(val shop: String = "", val by: String = "", val at: Long = 0)

/** Return window: from 3 days before the last day until it, "Return by <date>" with the orders page. */
object Returns {
    private val SAID = Regex("""(?i)\b(return (it )?(by|until|before|through)|eligible for (a )?return (until|through)|return window (closes|ends)( on)?)\b""")
    private val SHORT = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

    fun parse(pkg: String, title: String, text: String, at: Long, today: java.time.LocalDate): ReturnBy? {
        val all = "$title\n$text"
        val m = SAID.find(all) ?: return null
        val shop = Shops.of(pkg, all) ?: return null
        val by = dev.phonerecall.moments.When.date(all.substring(m.range.first), today) ?: return null
        return ReturnBy(shop, by.toString(), at)
    }

    fun card(ctx: MomentContext): MomentCard? {
        val today = ctx.now.toLocalDate()
        val r = ctx.qol.returns.mapNotNull { r -> runCatching { java.time.LocalDate.parse(r.by) }.getOrNull()?.let { r to it } }
            .filter { (_, d) -> !today.isAfter(d) && !today.isBefore(d.minusDays(3)) }.minByOrNull { it.second } ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.RETURN_BY
        val key = "${r.first.shop}:${r.first.by}"
        return MomentCard(
            k, key,
            CardContent(
                title = "Return by ${if (r.second == today) "today" else SHORT.format(r.second)}",
                body = "Your ${r.first.shop} order, if it's going back.",
                actions = listOf(
                    CardAction("Orders", ActionRole.MOMENT_GO, QolRules.payload(k, key, "orders")),
                    CardAction("Keeping it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")),
                    QolRules.never(k, key),
                ),
            ),
            fit = 14f, plans = emptyMap(), opens = mapOf("orders" to Shops.orders(r.first.shop)), keep = setOf("orders"),
        )
    }
}
