package dev.phonerecall.qol

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.odyssey.RoadCheck
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Small touches: Kairos cards (and two quiet notifications) for the moments in between. Where he parked at a
 * big lot. Leave for an event, with
 * one tap to navigate. The rules here are pure; QolCore reads the phone into [QolSignals]. Coordinates stay on the phone,
 * sealed, and only ever go into an intent he taps (Maps).
 */

/** Where he parked, sealed on the phone until he drives off. */
data class ParkedCar(val at: Long = 0, val lat: Double = 0.0, val lng: Double = 0.0, val near: String? = null, val bigLot: Boolean = false)

/** What QolCore keeps (sealed, no-backup). `carLink`: the last time the car's Bluetooth connected. */
data class QolState(
    val car: ParkedCar? = null, val carLink: Long = 0, val delivery: Delivery? = null,
    /** Street parking: his rule, Iris's offer (once), whether the car's last park was at Home, when it last moved, the flip already chipped. */
    val street: StreetRule? = null, val streetOffer: StreetRule? = null, val streetOfferDone: Boolean = false,
    val carHome: Boolean = false, val carMoved: Long = 0, val streetChip: Long = 0,
    /** The parking meter he started from the parked-car card (0 when none). */
    val meterUntil: Long = 0,
    /** Match ids whose hidden score he chose to see. */
    val revealed: List<String> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val refunds: List<Refund> = emptyList(),
    val returns: List<ReturnBy> = emptyList(),
    val pickups: List<Pickup> = emptyList(),
    /** The car's app said fuel is low; the last gas nudge. */
    val lowFuelAt: Long = 0, val gasNudged: Long = 0,
    /** Message cards he put off until he gets home (their Home instance keys). */
    val laterHome: List<String> = emptyList(),
    /** Family-group messages from last night, and Nano's line about them. */
    val familyNight: List<FamilyNote> = emptyList(), val familyLine: String? = null,
)

/** The car as the rules see it: no coordinates, only the walking intent back to it. */
data class CarSpot(val at: Long, val near: String?, val bigLot: Boolean, val walk: String)

/** A calendar event with a place, read live from his calendars (never kept). */
data class CalEvent(val title: String, val begin: Long, val end: Long, val location: String)

/** The next event to drive to: when to leave (Odyssey's traffic check when it ran, else 30 minutes before). */
data class LeaveBy(val title: String, val begin: Long, val leaveBy: Long, val dest: String, val delayMinutes: Int = 0)

/** Everything the small touches look at, already derived. */
data class QolSignals(
    /** On a call or a game in the foreground: nothing from here. */
    val busy: Boolean = false,
    val car: CarSpot? = null,
    val leave: LeaveBy? = null,
    val delivery: Delivery? = null,
    /** Subscription names (Moneta) renewing tomorrow. */
    val renewals: List<String> = emptyList(),
    val flight: FlightDay? = null,
    /** A late night out just ended and his car isn't there (RideHome). */
    val late: LateOut? = null,
    val rides: Rides? = null,
    val street: StreetReminder? = null,
    val streetOffer: StreetRule? = null,
    val offAlarm: OffAlarm? = null,
    /** A meeting on his usual call-home time today (IndiaClock). */
    val clash: CalEvent? = null,
    val spoiler: SpoilerResult? = null,
    val bills: List<Bill> = emptyList(),
    val refunds: List<Refund> = emptyList(),
    val returns: List<ReturnBy> = emptyList(),
    val pickups: List<Pickup> = emptyList(),
    /** The name of the place he is at now (Odyssey's stay), for "you're near". */
    val stayName: String? = null,
    val member: Member? = null,
    val steps: StepsNudge? = null,
    val split: SplitOffer? = null,
    val family: FamilyDigest? = null,
)

object QolRules {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
    private val CHIP = DateTimeFormatter.ofPattern("h:mm", Locale.US)

    /** A spot is kept two weeks (a trip from the airport lot), then dropped. */
    const val CAR_KEEP_MS = 14 * 86_400_000L
    /** A second park signal (Bluetooth off, then the drive ending) within this is the same park. */
    const val SAME_PARK_MS = 10 * 60_000L

    fun payload(kind: MomentKind, key: String, option: String) = "${kind.name}|$key|$option"
    private fun go(label: String, k: MomentKind, key: String, option: String) = CardAction(label, ActionRole.MOMENT_GO, payload(k, key, option))
    fun later(k: MomentKind, key: String) = CardAction("Not now", ActionRole.MOMENT_LATER, payload(k, key, ""))
    fun never(k: MomentKind, key: String) = CardAction("Never", ActionRole.MOMENT_NEVER, payload(k, key, ""))

    /** "7:12 PM" today, "Sat 7:12 PM" otherwise. */
    fun clock(ms: Long, now: ZonedDateTime): String {
        val t = Instant.ofEpochMilli(ms).atZone(now.zone)
        return (if (t.toLocalDate() == now.toLocalDate()) TIME else DAY_TIME).format(t)
    }

    // ---- leave for an event ----

    const val DEFAULT_DRIVE_MS = 30 * 60_000L

    /** A place to drive to, not a meeting link (the same test as Odyssey's event checks). */
    fun realPlace(loc: String) = loc.trim().length >= 4 && !loc.startsWith("http") &&
        listOf("teams.microsoft", "zoom.us", "meet.google", "webex").none { loc.contains(it, true) }

    fun nextLeave(events: List<CalEvent>, checks: List<RoadCheck>, now: Long): LeaveBy? =
        events.filter { it.begin > now && realPlace(it.location) }.minByOrNull { it.begin }?.let { e ->
            val rc = checks.firstOrNull { it.key == "event:${e.title.hashCode()}:${e.begin}" && it.leaveBy > 0 }
            LeaveBy(e.title, e.begin, rc?.leaveBy ?: (e.begin - DEFAULT_DRIVE_MS), e.location.trim(), rc?.delayMinutes ?: 0)
        }

    /** Turn-by-turn in Google Maps to the event's location text. */
    fun navigateTo(dest: String) = "google.navigation:q=" + java.net.URLEncoder.encode(dest, "UTF-8")

    /** From 15 minutes before leave-by until the event starts: one tap starts navigation. */
    fun navigate(ctx: MomentContext): MomentCard? {
        val l = ctx.qol.leave ?: return null
        if (ctx.qol.busy || ctx.driving) return null
        val ms = ctx.now.toInstant().toEpochMilli()
        if (ms !in (l.leaveBy - 15 * 60_000L) until l.begin) return null
        val k = MomentKind.NAVIGATE
        val key = "${l.title.hashCode()}:${l.begin}"
        return MomentCard(
            k, key,
            CardContent(
                title = if (ms < l.leaveBy) "Leave by ${clock(l.leaveBy, ctx.now)}" else "Leave now",
                body = "${l.title} at ${clock(l.begin, ctx.now)}." + if (l.delayMinutes > 0) " Traffic adds ${l.delayMinutes} min." else "",
                facts = listOf(Fact("Where", l.dest)),
                actions = listOf(go("Navigate", k, key, "nav"), later(k, key), never(k, key)),
            ),
            fit = 28f, plans = emptyMap(), opens = mapOf("nav" to navigateTo(l.dest)),
        )
    }

    // ---- where he parked ----

    /** Airports, stadiums, arenas, malls, garages: lots where the car is easy to lose. From the geocoder's names. */
    private val BIG = Regex("(?i)\\b(airport|terminal|stadium|arena|field|park(ing)?|ramp|garage|lot|mall|galleria|outlets?|center|centre|casino|fairgrounds?|convention|university|college|hospital|zoo|costco|walmart|target|wegmans)\\b")

    fun bigLot(vararg names: String?): Boolean = names.any { it != null && BIG.containsMatchIn(it) }

    /** Walking directions back to the car in Google Maps. */
    fun walkTo(lat: Double, lng: Double) = "google.navigation:q=$lat,$lng&mode=w"

    /** The Now Bar chip: at most 12 characters, "Parked 7:12". */
    fun chip(at: Long, now: ZonedDateTime) = "Parked " + CHIP.format(Instant.ofEpochMilli(at).atZone(now.zone))

    /** A park signal keeps the spot unless it is the same park again or he is home or at work. */
    fun keepPark(prev: ParkedCar?, at: Long, nearHomeOrWork: Boolean): Boolean =
        !nearHomeOrWork && (prev == null || at - prev.at > SAME_PARK_MS)

    /**
     * A drive ending (activity recognition) saves the spot only when it was his car: its Bluetooth connected in
     * the last 3 hours, or he has never linked a car (a bus ride must not move the car).
     */
    fun driveWasCar(carLink: Long, now: Long) = carLink == 0L || now - carLink in 0..3 * 3_600_000L

    fun carSpot(ctx: MomentContext): MomentCard? {
        val s = ctx.qol.car ?: return null
        if (ctx.qol.busy || ctx.driving || ctx.atHome == true || !s.bigLot) return null
        if (ctx.now.toInstant().toEpochMilli() - s.at !in 0..CAR_KEEP_MS) return null
        val k = MomentKind.CAR_SPOT
        val key = "${s.at}"
        return MomentCard(
            k, key,
            CardContent(
                title = "Parked · ${clock(s.at, ctx.now)}",
                body = s.near?.let { "Near $it." },
                // Four actions fit the cover screen; a swipe clears the card.
                actions = listOf(go("Navigate to car", k, key, "walk"), go("Snap the sign", k, key, "snap"), go("1h meter", k, key, "m60"), go("2h meter", k, key, "m120")),
            ),
            fit = 16f, plans = emptyMap(), opens = mapOf("walk" to s.walk, "snap" to Opens.CAMERA), keep = setOf("walk", "snap", "m60", "m120"),
        )
    }
}
