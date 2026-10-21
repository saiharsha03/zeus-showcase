package dev.phonerecall.qol

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import dev.phonerecall.clio.Clio
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moneta.Category
import dev.phonerecall.moneta.Txn
import dev.phonerecall.moneta.TxnKind
import java.time.Instant
import java.time.ZoneId

/** A dining or grocery charge in a group setting: the merchant, the amount (kept on the phone for the clipboard), the group. */
data class SplitOffer(val ref: String, val merchant: String, val cents: Long, val group: String?)

/**
 * Split this charge: a restaurant or grocery charge (Moneta) during a calendar event or on an evening out. One tap
 * opens Splitwise with the amount on the clipboard (it has no add-expense link); he enters it there. The card
 * never shows the amount.
 */
object Split {
    const val SPLITWISE = "com.Splitwise.SplitwiseMobile"

    fun offer(txns: List<Txn>, events: List<CalEvent>, now: Long, zone: ZoneId): SplitOffer? {
        val t = txns.filter { it.kind == TxnKind.SPEND && it.mergedInto == null && (it.category == Category.DINING || it.category == Category.GROCERIES) && now - it.at in 0..3 * 3_600_000L }
            .maxByOrNull { it.at } ?: return null
        val during = events.firstOrNull { t.at in (it.begin - 30 * 60_000L)..(it.end + 60 * 60_000L) }
        val h = Instant.ofEpochMilli(t.at).atZone(zone).hour
        val eveningOut = t.category == Category.DINING && (h >= 18 || h < 2)
        if (during == null && !eveningOut) return null
        return SplitOffer(t.ref, t.merchant, t.cents, during?.title)
    }

    fun card(ctx: MomentContext): MomentCard? {
        val o = ctx.qol.split ?: return null
        if (ctx.qol.busy || ctx.driving) return null
        val k = MomentKind.SPLIT
        val key = o.ref
        return MomentCard(
            k, key,
            CardContent(
                title = "Split ${o.merchant}" + (o.group?.let { " from $it" } ?: "") + "?",
                body = "Opens Splitwise with the amount copied.",
                actions = listOf(CardAction("Splitwise", ActionRole.MOMENT_GO, QolRules.payload(k, key, "split")), QolRules.later(k, key), QolRules.never(k, key)),
            ),
            fit = 14f, plans = emptyMap(), opens = mapOf("split" to "app:$SPLITWISE"),
        )
    }

    /** "12.34", what Splitwise's amount field takes. */
    fun amount(cents: Long) = "%d.%02d".format(cents / 100, cents % 100)

    /** His tap: the amount to the clipboard, marked sensitive so the keyboard doesn't show it. */
    fun copy(c: Context, o: SplitOffer) {
        runCatching {
            val clip = ClipData.newPlainText("Amount", amount(o.cents)).apply { description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) } }
            c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
        }.onFailure { Clio.error(QolCore.AREA, it) }
        Clio.info(QolCore.AREA, "split opened", o.merchant)
    }
}
