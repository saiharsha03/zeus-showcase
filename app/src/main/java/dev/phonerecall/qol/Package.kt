package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind

/** A package left at the door while he was out: who brought it and when. Never the item or the address. */
data class Delivery(val carrier: String = "", val at: Long = 0)

/** "Delivered" notifications from shops and carriers. Pure. */
object Deliveries {
    private val DONE = Regex("(?i)\\b(was delivered|has been delivered|been delivered|delivered today|delivered at|delivered to|is delivered|left at|at your (front )?door|on your porch|in your mailbox|handed (it )?to)\\b|^delivered\\b")
    private val NOT_YET = Regex("(?i)\\b(out for delivery|will be delivered|arriving|expected|on (the|its) way|scheduled|attempt(ed)?|delay(ed)?|could(n'?t| not) deliver|undeliverable)\\b")
    private val BY_PKG = mapOf(
        "com.amazon.mShop.android.shopping" to "Amazon", "com.ups.mobile.android" to "UPS", "com.fedex.ida.android" to "FedEx",
        "com.usps" to "USPS", "gov.usps.informeddelivery" to "USPS", "com.walmart.android" to "Walmart", "com.target.ui" to "Target",
        "com.instacart.client" to "Instacart", "com.costco.app.android" to "Costco", "com.dhl.exp.dhlmobile" to "DHL",
    )
    private val BY_NAME = Regex("\\b(Amazon|UPS|FedEx|USPS|DHL|Walmart|Target|Instacart|Costco|Best Buy)\\b", RegexOption.IGNORE_CASE)

    /** The carrier or shop when this says a package was delivered, else null. */
    fun carrier(pkg: String, app: String, title: String, text: String): String? {
        val all = "$title\n$text"
        if (!DONE.containsMatchIn(all) || NOT_YET.containsMatchIn(all)) return null
        return BY_PKG[pkg] ?: (BY_NAME.find(all) ?: BY_NAME.find(app))?.value?.let(::canonical)
    }

    private fun canonical(n: String) = listOf("Amazon", "UPS", "FedEx", "USPS", "DHL", "Walmart", "Target", "Instacart", "Costco", "Best Buy").first { it.equals(n, true) }

    const val KEEP_MS = 18 * 3_600_000L

    /** Home again after a delivery while he was out: one card. */
    fun card(ctx: MomentContext): MomentCard? {
        val d = ctx.qol.delivery ?: return null
        if (ctx.qol.busy || ctx.atHome != true) return null
        if (ctx.now.toInstant().toEpochMilli() - d.at !in 0..KEEP_MS) return null
        val k = MomentKind.PACKAGE
        val key = "${d.at}"
        return MomentCard(
            k, key,
            CardContent(
                title = "Package at the door", body = "${d.carrier}, ${QolRules.clock(d.at, ctx.now)}.",
                actions = listOf(CardAction("Got it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "go")), QolRules.never(k, key)),
            ),
            fit = 20f, plans = emptyMap(),
        )
    }
}
