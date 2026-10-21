package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind

/** A quiet day on foot at the office: Iaso's one derived fact (no count), and the weather in words. */
data class StepsNudge(val weather: String? = null)

/** Steps nudge: an office day, 3 to 5 PM, under 1,500 steps so far (Health Connect via Iaso): "Walk 10 min?". */
object Steps {
    fun due(quietOnFoot: Boolean?, officeDay: Boolean, hour: Int): Boolean = quietOnFoot == true && officeDay && hour in 15..16

    fun card(ctx: MomentContext): MomentCard? {
        val s = ctx.qol.steps ?: return null
        if (ctx.qol.busy || ctx.driving || ctx.now.hour !in 15..16) return null
        val k = MomentKind.STEPS
        val key = "${ctx.now.toLocalDate()}"
        return MomentCard(
            k, key,
            CardContent(
                title = "Walk 10 min?", body = "A quiet day on foot so far." + (s.weather?.let { " Outside: $it." } ?: ""),
                actions = listOf(CardAction("Going", ActionRole.MOMENT_GO, QolRules.payload(k, key, "go")), QolRules.later(k, key), QolRules.never(k, key)),
            ),
            fit = 10f, plans = emptyMap(),
        )
    }
}
