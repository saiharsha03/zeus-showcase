package dev.phonerecall.qol

import dev.phonerecall.argo.LegMode
import dev.phonerecall.argo.Travel
import dev.phonerecall.argo.Trip
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Today's flight from Argo, as the kit card needs it. `apps`: installed packages for the airline and a wallet. */
data class FlightDay(
    val legId: String, val what: String, val gate: String?, val departAt: Long, val leaveBy: Long, val zone: String,
    val airline: String? = null, val airlinePkg: String? = null, val walletPkg: String? = null,
)

/**
 * The flight-day kit: from 90 minutes before leave-by until wheels up, one card with the boarding pass (a wallet
 * app), the airline's app, the gate when Argo knows it, and leave-by. Argo's own travel card stays the trip.
 */
object FlightKit {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** Airline name or IATA code to its Android app. Checked against what is installed before it shows. */
    val AIRLINES = listOf(
        Triple("Delta", "DL", "com.delta.mobile.android"), Triple("American", "AA", "com.aa.android"),
        Triple("United", "UA", "com.united.mobile.android"), Triple("JetBlue", "B6", "com.jetblue.JetBlueAndroid"),
        Triple("Southwest", "WN", "com.southwestairlines.mobile"), Triple("Alaska", "AS", "com.alaskaairlines.android"),
        Triple("Spirit", "NK", "com.spirit.customerapp"), Triple("Air Canada", "AC", "com.aircanada"),
        Triple("Emirates", "EK", "com.emirates.ek.android"), Triple("Qatar", "QR", "com.m.qr"),
        Triple("Lufthansa", "LH", "com.lufthansa.android.lufthansa"), Triple("British Airways", "BA", "com.ba.mobile"),
    )
    val WALLETS = listOf("com.google.android.apps.walletnfcrel", "com.samsung.android.spay")

    fun airline(carrier: String, number: String): Triple<String, String, String>? =
        AIRLINES.firstOrNull { (name, code, _) -> carrier.contains(name, true) || carrier.equals(code, true) || number.trim().uppercase().startsWith(code) }

    /** The next flight leaving within the kit's window, from open trips. */
    fun today(trips: List<Trip>, now: Long, home: ZoneId, installed: (String) -> Boolean): FlightDay? {
        val (t, l) = trips.filter { it.open }.flatMap { t -> t.legs.map { t to it } }
            .filter { (_, l) -> l.mode == LegMode.FLIGHT && l.departAt > now && now >= Travel.leaveBy(l) - 90 * 60_000L }
            .minByOrNull { it.second.departAt } ?: return null
        val a = airline(l.carrier, l.number)
        return FlightDay(
            l.id, listOf(l.carrier, l.number).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Flight" }, l.platform.ifBlank { null },
            l.departAt, Travel.leaveBy(l), (if (l.returning) t.destZone(home) else home).id,
            a?.first, a?.third?.takeIf(installed), WALLETS.firstOrNull(installed),
        )
    }

    fun card(ctx: MomentContext): MomentCard? {
        val f = ctx.qol.flight ?: return null
        if (ctx.qol.busy) return null
        val ms = ctx.now.toInstant().toEpochMilli()
        if (ms >= f.departAt) return null
        val z = ZoneId.of(f.zone)
        fun hm(t: Long) = TIME.format(Instant.ofEpochMilli(t).atZone(z))
        val k = MomentKind.FLIGHT_KIT
        val key = f.legId
        val pass = f.walletPkg ?: f.airlinePkg
        val options = buildMap {
            if (pass != null) put("pass", "app:$pass")
            if (f.airlinePkg != null) put("airline", "app:${f.airlinePkg}")
        }
        return MomentCard(
            k, key,
            CardContent(
                title = "Flight kit: ${f.what}",
                facts = listOfNotNull(
                    Fact("Departs", hm(f.departAt)),
                    f.gate?.let { Fact("Gate", it) },
                    if (ms < f.leaveBy) Fact("Leave by", hm(f.leaveBy)) else null,
                ),
                actions = listOfNotNull(
                    if (pass != null) CardAction("Boarding pass", ActionRole.MOMENT_GO, QolRules.payload(k, key, "pass")) else null,
                    if (f.airlinePkg != null) CardAction("Open ${f.airline}", ActionRole.MOMENT_GO, QolRules.payload(k, key, "airline")) else null,
                    CardAction("Got it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")),
                ),
            ),
            fit = 32f, plans = emptyMap(), opens = options, keep = options.keys,
        )
    }
}
