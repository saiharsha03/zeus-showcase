package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moneta.Subscription
import java.time.LocalDate

/** Subscriptions (Moneta) renewing tomorrow: Keep, or Manage in the service's own page. Names only, never amounts. */
object Renewals {
    const val PLAY = "https://play.google.com/store/account/subscriptions"

    private val MANAGE = listOf(
        "netflix" to "https://www.netflix.com/youraccount",
        "spotify" to "https://www.spotify.com/account/subscription/",
        "youtube" to "https://www.youtube.com/paid_memberships",
        "prime" to "https://www.amazon.com/mc",
        "amazon" to "https://www.amazon.com/mc",
        "hulu" to "https://secure.hulu.com/account",
        "disney" to "https://www.disneyplus.com/account",
        "max" to "https://auth.max.com/subscription",
        "apple" to "https://apps.apple.com/account/subscriptions",
        "claude" to "https://claude.ai/settings/billing",
        "google one" to PLAY,
    )

    /** The service's own subscription page when known, else Google Play's subscriptions. */
    fun manage(name: String): String = MANAGE.firstOrNull { (k, _) -> Regex("(?i)\\b${Regex.escape(k)}\\b").containsMatchIn(name) }?.second ?: PLAY

    fun tomorrow(subs: List<Subscription>, today: LocalDate): List<String> =
        subs.filter { it.next == today.plusDays(1) }.map { it.name }.distinct().sorted()

    fun card(ctx: MomentContext): MomentCard? {
        val names = ctx.qol.renewals.takeIf { it.isNotEmpty() } ?: return null
        if (ctx.qol.busy || ctx.now.hour !in 9..21) return null
        val k = MomentKind.RENEWAL
        val key = "${ctx.now.toLocalDate()}:${names.joinToString(",")}"
        val title = if (names.size == 1) "${names[0]} renews tomorrow" else "${names.size} renew tomorrow"
        return MomentCard(
            k, key,
            CardContent(
                title = title, body = if (names.size > 1) names.joinToString(", ") + "." else "Keep it, or manage it now.",
                actions = listOf(
                    CardAction("Keep", ActionRole.MOMENT_GO, QolRules.payload(k, key, "keep")),
                    CardAction("Manage", ActionRole.MOMENT_GO, QolRules.payload(k, key, "manage")),
                    QolRules.never(k, key),
                ),
            ),
            fit = 14f, plans = emptyMap(), opens = mapOf("manage" to if (names.size == 1) manage(names[0]) else PLAY),
        )
    }
}
