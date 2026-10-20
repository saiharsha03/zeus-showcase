package dev.phonerecall.argo

import java.time.ZoneId

/*
 * Where Argo meets features other workers own. Each is a tiny interface, null until that feature sets it,
 * so Argo works (and is tested) without them.
 */

/** Janus (clocks): the destination's zone during a trip when it differs from home; null when back. */
fun interface TwoClocks { fun destination(zone: ZoneId?, label: String?) }

/** Kairos (moments): "Arrived in Boston", "Back from Boston". Words only. */
fun interface KairosMoments { fun moment(at: Long, title: String, body: String) }

object ArgoLinks {
    @Volatile var clocks: TwoClocks? = null
    @Volatile var moments: KairosMoments? = null
    /** Hypnos (alarms): the next alarm, for "No alarm before you leave at 5:40 AM". */
    @Volatile var alarms: AlarmWatch? = null
}

/** Eos (the brief): at most two lines about trips for the morning brief. Pure; Eos calls it. */
object ArgoBrief {
    fun lines(d: ArgoData, now: Long, zone: ZoneId): List<String> {
        val (t, m) = Travel.current(d.trips, now, zone) ?: return emptyList()
        return when (m.phase) {
            Phase.TRAVEL -> listOf(LiveWords.of(t, m.leg!!, now, zone).let { "${it.title}. ${it.text}." })
            Phase.AWAY -> listOfNotNull("${t.place} today" + (ArgoCards.todayPlans(t, now, zone).firstOrNull()?.let { ": ${it.title} at ${Clock.hm(it.at, t.destZone(zone))}" } ?: "."),
                ArgoCards.weatherLine(d.weather[t.id], Trip.day(now, t.destZone(zone)))?.let { "There: $it." })
            Phase.BEFORE, Phase.IDEA -> {
                val gaps = Gaps.of(t, d.weather[t.id], now, zone).filter { it.gap != Gap.ALARM }
                listOf("${t.place} ${Travel.countdown(t, now, zone)}" + if (gaps.isEmpty()) ", all set." else ": " + gaps.joinToString(", ") { it.text.lowercase() } + ".")
            }
            Phase.AFTER -> emptyList()
        }
    }
}

/** Delphi's and the mind's trip tools. The words they get are [TripWords]: city names, no addresses or confirmations. */
interface TripTools {
    fun note(place: String, whenText: String?, details: String?, budgetDollars: Double? = null): String
    fun read(): List<String>
}

class ArgoTools(private val app: android.content.Context) : TripTools {
    override fun note(place: String, whenText: String?, details: String?, budgetDollars: Double?): String {
        val t = Argo.note(app, place, whenText, details, budgetCents = budgetDollars?.takeIf { it in 0.0..1_000_000.0 }?.let { Math.round(it * 100) })
        val zone = ZoneId.systemDefault()
        return "Noted in Argo: ${t.place}, ${Travel.dates(t, zone)}" + (if (t.status == TripStatus.IDEA) " (an idea until dates are known)" else "") +
            ". Bookings on his calendar and from Iris join it on their own. Nothing was booked."
    }

    override fun read(): List<String> = Argo.read(app)
}
