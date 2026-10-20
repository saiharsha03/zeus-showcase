package dev.phonerecall.argo

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/*
 * Argo (Trips): a trip and what it is made of. Plain data with defaults, kept sealed on the phone
 * (ArgoStore). Confirmation numbers are never stored here: a leg or a stay only points at a Calypso item.
 */

enum class LegMode(val word: String) { FLIGHT("Flight"), TRAIN("Train"), BUS("Bus"), DRIVE("Drive") }

/** IDEA: a place and a vague when ("March"). PLANNED: dates known. DONE: over, recap written. */
enum class TripStatus { IDEA, PLANNED, DONE, ARCHIVED, DISMISSED }

data class Leg(
    val id: String = "",
    val mode: LegMode = LegMode.FLIGHT,
    /** "Buffalo (BUF)", "Buffalo Exchange St". */
    val from: String = "",
    /** "Boston (BOS)", "South Station". */
    val to: String = "",
    /** Epoch ms; 0 when not known yet. */
    val departAt: Long = 0,
    val arriveAt: Long = 0,
    val carrier: String = "",
    /** A flight or train number ("B6 1234", "Amtrak 449"), never a confirmation code. */
    val number: String = "",
    /** "Platform 5", "Gate B12", once known. */
    val platform: String = "",
    val returning: Boolean = false,
    /** The confirmation's Calypso item id. The item opens only behind his fingerprint. */
    val doc: String? = null,
    val source: String = "",
    /** What Iris said it costs, and whether it is paid (false: awaiting payment). Shown only behind Moneta's fingerprint. */
    val cents: Long? = null,
    val paid: Boolean? = null,
    /** The day (ISO) when only that is known ("Buffalo-DEN Sun 03-21" in Iris's words); departAt is then 0. */
    val day: String? = null,
)

data class Stay(
    val id: String = "",
    val name: String = "",
    /** A public business address, for Maps. Never sent to the brain. */
    val address: String = "",
    val checkIn: Long = 0,
    val checkOut: Long = 0,
    val doc: String? = null,
    val source: String = "",
    val cents: Long? = null,
    val paid: Boolean? = null,
)

/** A place the trip stays at, with its days (ISO). A connection under a day is a layover, not a stop. */
data class Stop(val place: String = "", val start: String? = null, val end: String? = null)

data class TripPlan(
    val id: String = "",
    val title: String = "",
    val at: Long = 0,
    val place: String = "",
    val people: List<String> = emptyList(),
    val source: String = "",
    /** When it ends, when known (a car rental, a tour). */
    val end: Long = 0,
    /** plan, event, tour, car, restaurant or parking: what Iris saw in the mail. `place` is the venue's name, never an address. */
    val kind: String = "plan",
)

data class Trip(
    val id: String = "",
    /** The city, as a name only ("Denver"). */
    val place: String = "",
    /** The destination's centre from Open-Meteo's geocoder, for its forecast. Kept on the phone. */
    val lat: Double? = null,
    val lng: Double? = null,
    /** The destination's time zone id, when it differs from home it goes to Janus. */
    val zone: String? = null,
    /** ISO dates, when known. */
    val startDate: String? = null,
    val endDate: String? = null,
    /** "2027-03" when only the month is known. */
    val month: String? = null,
    /** As he said it: "March", "next month". */
    val vague: String = "",
    val legs: List<Leg> = emptyList(),
    val stays: List<Stay> = emptyList(),
    val people: List<String> = emptyList(),
    val plans: List<TripPlan> = emptyList(),
    val notes: List<String> = emptyList(),
    val docs: List<String> = emptyList(),
    val budgetCents: Long? = null,
    val status: TripStatus = TripStatus.IDEA,
    /** calendar, iris, delphi, you, moneta. */
    val sources: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val recap: String? = null,
    /** The Hestia lights-off proposal was made once for this trip. */
    val awayAsked: Boolean = false,
    /** Gaps already handed to Iris ("STAY", "TRANSPORT"), so the card says "Iris is looking". */
    val askedIris: List<String> = emptyList(),
    /** Every source item id this trip absorbed, so a dismissed or archived trip is never re-made from them. */
    val seen: List<String> = emptyList(),
    /** "First thing back: 9:00 AM Standup, Tue", once the return is known. */
    val firstBack: String? = null,
    /** The light recovery block for the morning after was proposed once. */
    val recoveryAsked: Boolean = false,
    /** Every city he stays in, in order ("Denver", then "Boulder" Mar 26 to 28). */
    val stops: List<Stop> = emptyList(),
    /** Connections under a day ("Atlanta", "Nashville"), never the destination. */
    val layovers: List<String> = emptyList(),
    /** He confirmed this trip (or edited it): later evidence joins it, and a contradiction asks him instead of splitting it. */
    val locked: Boolean = false,
    /** The days in his own words (Delphi, his answer, Athena's finding), kept apart from what the bookings say. */
    val saidStart: String? = null,
    val saidEnd: String? = null,
    /** Athena assembled it: it keeps its shape until her next run reshapes it. */
    val athena: Boolean = false,
    /** Argo's offline assembly, until Athena has run on its evidence. */
    val draft: Boolean = false,
    /** Her confidence in it: high, medium or low. */
    val confidence: String? = null,
) {
    @get:JsonIgnore val outbound: Leg? get() = legs.filter { !it.returning }.sortedBy { if (it.departAt > 0) it.departAt else Long.MAX_VALUE }.firstOrNull()
    @get:JsonIgnore val back: Leg? get() = legs.filter { it.returning }.sortedBy { if (it.departAt > 0) it.departAt else Long.MAX_VALUE }.firstOrNull()
    /** The last leg of the way back: with connections (AUS → CLT → BUF) the one that lands home. */
    @get:JsonIgnore val landing: Leg? get() = legs.filter { it.returning && it.departAt > 0 }.maxByOrNull { it.departAt } ?: back
    @get:JsonIgnore val open: Boolean get() = status == TripStatus.IDEA || status == TripStatus.PLANNED
    fun destZone(home: ZoneId): ZoneId = zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: home

    /** First and last day, from the dates, the legs or the stays; null for an idea with only a month. */
    fun days(zone: ZoneId): Pair<LocalDate, LocalDate>? {
        val dz = destZone(zone)
        val starts = buildList {
            startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let(::add)
            legs.filter { !it.returning && it.departAt > 0 }.forEach { add(day(it.departAt, zone)) }
            legs.filter { !it.returning && it.departAt <= 0 }.mapNotNull { it.dayOf() }.forEach(::add)
            stays.filter { it.checkIn > 0 }.forEach { add(day(it.checkIn, dz)) }
        }
        val ends = buildList {
            endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let(::add)
            legs.filter { it.returning && it.arriveAt > 0 }.forEach { add(day(it.arriveAt, zone)) }
            legs.filter { it.returning && it.arriveAt <= 0 }.mapNotNull { l -> l.departAt.takeIf { it > 0 }?.let { day(it, zone) } ?: l.dayOf() }.forEach(::add)
            stays.filter { it.checkOut > 0 }.forEach { add(day(it.checkOut, dz)) }
        }
        val s = starts.minOrNull() ?: return null
        val e = (ends + starts).maxOrNull() ?: s
        return s to maxOf(s, e)
    }

    /** [start, end) in ms: from the first departure (or the first day) to the last arrival (or the end of the last day). */
    fun window(zone: ZoneId): Pair<Long, Long>? {
        val (s, e) = days(zone) ?: return null
        val start = outbound?.departAt?.takeIf { it > 0 } ?: s.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = landing?.arriveAt?.takeIf { it > 0 } ?: e.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to maxOf(start, end)
    }

    fun monthOf(): YearMonth? = month?.let { runCatching { YearMonth.parse(it) }.getOrNull() }

    /** Nights away, when the days are known. */
    fun nights(zone: ZoneId): Long? = days(zone)?.let { (s, e) -> java.time.temporal.ChronoUnit.DAYS.between(s, e) }

    companion object {
        fun day(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    }
}

fun Leg.dayOf(): LocalDate? = day?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** The destination's forecast, the parts Argo shows. Temperatures in Celsius. */
data class DestWeather(
    val at: Long = 0,
    val tempC: Double? = null,
    val code: Int = -1,
    val days: List<DestDay> = emptyList(),
)

data class DestDay(val date: String = "", val highC: Double? = null, val lowC: Double? = null, val code: Int = -1, val rainChance: Int? = null)

data class ArgoData(
    val trips: List<Trip> = emptyList(),
    /** Athena's investigations of hints that he may be travelling. */
    val inquiries: List<Inquiry> = emptyList(),
    /** Iris trip.info ids already applied. */
    val seenInfo: List<String> = emptyList(),
    /** Destination forecasts by trip id. */
    val weather: Map<String, DestWeather> = emptyMap(),
    /** The Live Update's last stage key, so a stage change is a key moment exactly once. */
    val noticeKey: String? = null,
    /** When Iris was last asked for every trip in his mail (Sweep). */
    val sweptAt: Long = 0,
    /** When her last sweep answer came back; legs she never mentions are flagged after one. */
    val sweepAnswered: Long = 0,
    /** Everything the sources said (Evidence.kt); trips are rebuilt from it. The calendar's part is re-read every tick. */
    val evidence: List<Ev> = emptyList(),
    /** Time repairs per feed ("cal:6" to "local-as-utc"), kept so the next booking from that calendar is read right. */
    val corrections: Map<String, String> = emptyMap(),
    /** What his edits taught (Charlotte is a layover; Boulder goes with Denver). */
    val rules: List<TripRule> = emptyList(),
    /** Athena's trip list waiting for his confirm (propose_trips). */
    val plan: Reconciliation? = null,
    /** The last note Athena got about trips, so the same anomalies are sent once. */
    val asked: ReconcileAsk? = null,
    /** New evidence that doesn't fit a confirmed trip: one card each, until he decides. */
    val conflicts: List<Conflict> = emptyList(),
    /** Conflicts he said to keep as they are. */
    val kept: List<String> = emptyList(),
    /** 2 once trips are rebuilt from evidence (the first run after the redesign migrates). */
    val schema: Int = 0,
    /** Legs Athena set aside, with why ("a duplicate of AA 404", "cancelled"). */
    val ignored: Map<String, String> = emptyMap(),
    /** Her repairs replaced the phone's guesses once she ran. */
    val athenaRepairs: Boolean = false,
    /** Her last change on its own, for the quiet line with Undo. */
    val change: TripChange? = null,
)
