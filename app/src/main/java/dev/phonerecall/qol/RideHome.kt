package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.Instant
import java.time.ZoneId

/** A late event or visit just ended and he didn't drive there. `what`: the event's title, when it was one. */
data class LateOut(val what: String?, val endedAt: Long)

/** The ride apps' deep links, Home as the drop-off (built on the phone, used only by his tap). */
data class Rides(val uber: String, val lyft: String? = null)

/** After 10 PM, out, no car of his there, no drive started: "Uber home" (Lyft too when installed). */
object RideHome {
    private fun late(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).hour.let { it >= 22 || it < 3 }

    /** An evening event with a place that ended in the last hour, after 10 PM. */
    fun lateEvent(events: List<CalEvent>, now: Long, zone: ZoneId): LateOut? =
        events.filter { QolRules.realPlace(it.location) && it.end in (now - 60 * 60_000L)..now && late(it.end, zone) && Instant.ofEpochMilli(it.begin).atZone(zone).hour >= 16 }
            .maxByOrNull { it.end }?.let { LateOut(it.title, it.end) }

    /** A stay of an hour or more he just walked out of, after 10 PM (a game, a concert, dinner). */
    fun lateVisit(stayStart: Long?, walkStart: Long, now: Long, zone: ZoneId): LateOut? {
        if (stayStart == null || walkStart - stayStart < 60 * 60_000L || now - walkStart !in 0..20 * 60_000L || !late(walkStart, zone)) return null
        return LateOut(null, walkStart)
    }

    fun uber(home: Pair<Double, Double>?, installed: Boolean): String {
        val drop = home?.let { (lat, lng) -> "&dropoff[latitude]=$lat&dropoff[longitude]=$lng&dropoff[nickname]=Home" }.orEmpty()
        return (if (installed) "uber://?" else "https://m.uber.com/ul/?") + "action=setPickup&pickup=my_location$drop"
    }

    fun lyft(home: Pair<Double, Double>?) = "lyft://ridetype?id=lyft" + home?.let { (lat, lng) -> "&destination[latitude]=$lat&destination[longitude]=$lng" }.orEmpty()

    fun card(ctx: MomentContext): MomentCard? {
        val l = ctx.qol.late ?: return null
        val rides = ctx.qol.rides ?: return null
        if (ctx.qol.busy || ctx.driving || ctx.atHome == true) return null
        if (ctx.now.toInstant().toEpochMilli() - l.endedAt !in 0..60 * 60_000L) return null
        val k = MomentKind.RIDE_HOME
        val key = "${l.endedAt}"
        return MomentCard(
            k, key,
            CardContent(
                title = "Ride home?",
                body = (l.what?.let { "$it ended at ${QolRules.clock(l.endedAt, ctx.now)}." } ?: "Late night out.") + " You didn't drive here.",
                actions = listOfNotNull(
                    CardAction("Uber home", ActionRole.MOMENT_GO, QolRules.payload(k, key, "uber")),
                    rides.lyft?.let { CardAction("Lyft", ActionRole.MOMENT_GO, QolRules.payload(k, key, "lyft")) },
                    QolRules.later(k, key),
                ),
            ),
            fit = 30f, plans = emptyMap(), opens = listOfNotNull("uber" to rides.uber, rides.lyft?.let { "lyft" to it }).toMap(),
        )
    }
}
