package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind

/** At a store with a membership or rewards app he has: its name and the app (installed only). */
data class Member(val store: String, val pkg: String)

/** Member card at the store: while he is at one, one tap opens its membership app (or the wallet with his passes). */
object MemberCard {
    val APPS = listOf(
        "Costco" to "com.costco.app.android", "Sam's Club" to "com.rfi.sams.android", "BJ's" to "com.bjs.android",
        "Target" to "com.target.ui", "Walmart" to "com.walmart.android", "CVS" to "com.cvs.launchers.cvs",
        "Walgreens" to "com.walgreens.android", "Starbucks" to "com.starbucks.mobilecard", "Wegmans" to "com.wegmans.wegmansapp",
        "Tops" to "com.topsmarkets.android", "Kohl's" to "com.kohls.mcommerce.opal", "Best Buy" to "com.bestbuy.android",
    )

    /** The store he is at and its app, when installed; else the wallet when he has one. */
    fun at(stayName: String?, installed: (String) -> Boolean): Member? {
        val name = stayName ?: return null
        val (store, pkg) = APPS.firstOrNull { (s, _) -> name.contains(s, true) } ?: return null
        return when {
            installed(pkg) -> Member(store, pkg)
            else -> FlightKit.WALLETS.firstOrNull(installed)?.let { Member(store, it) }
        }
    }

    fun card(ctx: MomentContext): MomentCard? {
        val m = ctx.qol.member ?: return null
        if (ctx.qol.busy || ctx.driving) return null
        val k = MomentKind.MEMBER_CARD
        val key = "${m.store}:${ctx.now.toLocalDate()}"
        val wallet = m.pkg in FlightKit.WALLETS
        return MomentCard(
            k, key,
            CardContent(
                title = "At ${m.store}", body = if (wallet) "Your pass is in the wallet." else "Your membership is in the ${m.store} app.",
                actions = listOf(
                    CardAction(if (wallet) "Open wallet" else "Member card", ActionRole.MOMENT_GO, QolRules.payload(k, key, "open")),
                    QolRules.later(k, key), QolRules.never(k, key),
                ),
            ),
            fit = 26f, plans = emptyMap(), opens = mapOf("open" to "app:${m.pkg}"),
        )
    }
}
