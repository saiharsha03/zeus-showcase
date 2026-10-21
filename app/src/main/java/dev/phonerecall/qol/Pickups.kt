package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind

/** Something waiting to be picked up: the store (or "Amazon Locker"), what kind, when it said so. */
data class Pickup(val store: String = "", val what: String = "", val at: Long = 0)

/**
 * Pickup reminder: "ready for pickup" from a pharmacy, a store or a locker stays on Kairos until Picked up, and comes
 * forward with Navigate when Odyssey sees him near that store.
 */
object Pickups {
    private val READY = Regex("""(?i)\b(ready (for|to) pick ?up|is ready at|ready at the|pick ?up code|arrived at (the |your )?locker|in (the |your )?locker)\b""")
    private val NOT = Regex("""(?i)\b(picked up|was collected|has been collected|thanks for picking up)\b""")
    private val STORES = listOf(
        "Amazon Locker" to Regex("""(?i)\blocker\b"""), "CVS" to Regex("""(?i)\bcvs\b"""), "Walgreens" to Regex("""(?i)\bwalgreens\b"""),
        "Rite Aid" to Regex("""(?i)\brite ?aid\b"""), "Wegmans" to Regex("""(?i)\bwegmans\b"""), "Target" to Regex("""(?i)\btarget\b"""),
        "Walmart" to Regex("""(?i)\bwalmart\b"""), "Best Buy" to Regex("""(?i)\bbest buy\b"""), "Costco" to Regex("""(?i)\bcostco\b"""),
    )
    private val BY_PKG = mapOf("com.cvs.launchers.cvs" to "CVS", "com.walgreens.android" to "Walgreens", "com.riteaid.android" to "Rite Aid")
    const val KEEP_MS = 7 * 86_400_000L

    fun parse(pkg: String, app: String, title: String, text: String, at: Long): Pickup? {
        val all = "$title\n$text"
        if (!READY.containsMatchIn(all) || NOT.containsMatchIn(all)) return null
        val store = STORES.firstOrNull { it.second.containsMatchIn(all) }?.first ?: BY_PKG[pkg] ?: STORES.firstOrNull { it.second.containsMatchIn(app) }?.first ?: return null
        val what = when {
            Regex("""(?i)prescription|\brx\b|pharmacy""").containsMatchIn(all) -> "Prescription"
            store == "Amazon Locker" -> "Package"
            else -> "Order"
        }
        return Pickup(store, what, at)
    }

    /** Odyssey's current stay is at (or named like) that store. */
    fun near(p: Pickup, stayName: String?): Boolean =
        stayName != null && (stayName.contains(p.store.removeSuffix(" Locker"), true))

    fun navigate(p: Pickup) = QolRules.navigateTo(p.store)

    fun card(ctx: MomentContext): MomentCard? {
        val ms = ctx.now.toInstant().toEpochMilli()
        val open = ctx.qol.pickups.filter { ms - it.at in 0..KEEP_MS }
        val p = open.firstOrNull { near(it, ctx.qol.stayName) } ?: open.minByOrNull { it.at } ?: return null
        if (ctx.qol.busy) return null
        val here = near(p, ctx.qol.stayName)
        val k = MomentKind.PICKUP
        val key = "${p.store}:${p.at}"
        return MomentCard(
            k, key,
            CardContent(
                title = if (here) "You're near ${p.store}: ${p.what.lowercase()} ready" else "${p.what} ready at ${p.store}",
                body = "Since ${QolRules.clock(p.at, ctx.now)}.",
                actions = listOf(
                    CardAction("Navigate", ActionRole.MOMENT_GO, QolRules.payload(k, key, "nav")),
                    CardAction("Picked up", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")),
                    QolRules.never(k, key),
                ),
            ),
            fit = if (here) 30f else 10f, plans = emptyMap(), opens = mapOf("nav" to navigate(p)), keep = setOf("nav"),
        )
    }
}
