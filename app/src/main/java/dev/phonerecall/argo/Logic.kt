package dev.phonerecall.argo

import dev.phonerecall.aeolia.Sky
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * What a trip means right now: which phase it is in, what is missing, what the Live Update says, which days
 * he is away, and the words the brain may see. Pure.
 */

enum class Phase { IDEA, BEFORE, TRAVEL, AWAY, AFTER }

data class Moment(val phase: Phase, val leg: Leg? = null)

object Clock {
    private val hm = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val day = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    fun hm(ms: Long, zone: ZoneId): String = hm.format(Instant.ofEpochMilli(ms).atZone(zone))
    fun day(d: LocalDate): String = day.format(d)
    fun month(m: java.time.YearMonth): String = m.month.getDisplayName(TextStyle.FULL, Locale.US)
    fun mins(m: Long): String = if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"
    private val md = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    /** "Mar 21–29", "Apr 22–May 2", "Mar 21". */
    fun range(s: LocalDate, e: LocalDate): String = when {
        e == s -> md.format(s)
        e.year == s.year && e.month == s.month -> "${md.format(s)}–${e.dayOfMonth}"
        else -> "${md.format(s)}–${md.format(e)}"
    }
}

object Travel {
    private const val MIN = 60_000L

    /** When to leave for the leg: at the airport 90 min early, the station 15, plus 30 to get there. A drive leaves at its time. */
    fun leaveBy(l: Leg): Long = when (l.mode) {
        LegMode.FLIGHT -> l.departAt - 120 * MIN
        LegMode.TRAIN -> l.departAt - 45 * MIN
        LegMode.BUS -> l.departAt - 40 * MIN
        LegMode.DRIVE -> l.departAt
    }

    private fun arrive(l: Leg) = if (l.arriveAt > l.departAt) l.arriveAt else l.departAt + 60 * MIN

    /** Travel mode for a leg: three hours before leave-by until half an hour after arrival. */
    fun window(l: Leg): LongRange? = if (l.departAt <= 0) null else (leaveBy(l) - 180 * MIN)..(arrive(l) + 30 * MIN)

    fun moment(t: Trip, now: Long, zone: ZoneId): Moment {
        if (!t.open) return Moment(Phase.AFTER)
        t.legs.filter { it.departAt > 0 }.sortedBy { it.departAt }.firstOrNull { now in window(it)!! }?.let { return Moment(Phase.TRAVEL, it) }
        val w = t.window(zone)
        if (w == null) {
            val m = t.monthOf() ?: return Moment(Phase.IDEA)
            return Moment(if (Trip.day(now, zone) > m.atEndOfMonth()) Phase.AFTER else Phase.IDEA)
        }
        return Moment(when { now >= w.second -> Phase.AFTER; now >= w.first -> Phase.AWAY; else -> Phase.BEFORE })
    }

    /** The trip that owns the moment: travelling beats away beats the next one coming. */
    fun current(trips: List<Trip>, now: Long, zone: ZoneId): Pair<Trip, Moment>? {
        val all = trips.filter { it.open }.map { it to moment(it, now, zone) }
        return all.firstOrNull { it.second.phase == Phase.TRAVEL } ?: all.firstOrNull { it.second.phase == Phase.AWAY }
            ?: all.filter { it.second.phase == Phase.BEFORE }.minByOrNull { it.first.window(zone)!!.first }
            ?: all.firstOrNull { it.second.phase == Phase.IDEA }
    }

    /** Away for the working day: the trip covers five hours or more of 8 AM to 6 PM. Ideas never count. */
    fun awayOn(trips: List<Trip>, date: LocalDate, zone: ZoneId): Boolean {
        val a = date.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val b = date.atTime(18, 0).atZone(zone).toInstant().toEpochMilli()
        return trips.any { t -> t.open && t.window(zone)?.let { (s, e) -> minOf(b, e) - maxOf(a, s) >= 5 * 3_600_000L } == true }
    }

    /** "in 3 weeks", "tomorrow", "in March". */
    fun countdown(t: Trip, now: Long, zone: ZoneId): String {
        val today = Trip.day(now, zone)
        val start = t.days(zone)?.first ?: return t.monthOf()?.let { if (java.time.YearMonth.from(today) == it) "this month" else "in ${Clock.month(it)}" } ?: "someday"
        val d = ChronoUnit.DAYS.between(today, start)
        return when {
            d <= 0 -> "today"
            d == 1L -> "tomorrow"
            d < 14 -> "in $d days"
            d < 63 -> "in ${(d + 3) / 7} weeks"
            else -> "in ${(d + 15) / 30} months"
        }
    }

    /** "Thu, Mar 4 to Mon, Mar 8 · 4 nights", or "Sometime in March". */
    fun dates(t: Trip, zone: ZoneId): String {
        val (s, e) = t.days(zone) ?: return t.monthOf()?.let { "Sometime in ${Clock.month(it)}" } ?: "No dates yet"
        val n = ChronoUnit.DAYS.between(s, e)
        return if (n == 0L) Clock.day(s) else "${Clock.day(s)} to ${Clock.day(e)} · $n ${if (n == 1L) "night" else "nights"}"
    }
}

/** The trip day by day: legs, check-in and check-out, plans (tickets, tours, tables), in time order. */
object Itinerary {
    data class Item(val at: Long, val text: String)

    fun days(t: Trip, zone: ZoneId): List<Pair<LocalDate, List<Item>>> {
        val dz = t.destZone(zone)
        val items = buildList {
            for (l in t.legs) {
                val z = Airports.zoneOf(l.from) ?: if (l.returning) dz else zone
                val what = listOf(l.number.ifBlank { l.mode.word }, listOf(l.from, l.to).filter { it.isNotBlank() }.joinToString(" to ")).filter { it.isNotBlank() }.joinToString(" ")
                if (l.departAt > 0) add(Trip.day(l.departAt, z) to Item(l.departAt, "${Clock.hm(l.departAt, z)} $what"))
                else l.dayOf()?.let { d -> add(d to Item(d.atTime(12, 0).atZone(z).toInstant().toEpochMilli(), what)) }
            }
            for (s in t.stays) {
                if (s.checkIn > 0) add(Trip.day(s.checkIn, dz) to Item(s.checkIn, "Check in, ${s.name.ifBlank { "hotel" }}"))
                if (s.checkOut > s.checkIn) add(Trip.day(s.checkOut, dz) to Item(s.checkOut, "Check out"))
            }
            for (p in t.plans.filter { it.at > 0 }) add(Trip.day(p.at, dz) to Item(p.at, "${Clock.hm(p.at, dz)} ${p.title}" + (p.place.takeIf { it.isNotBlank() && !p.title.contains(it, true) }?.let { ", $it" } ?: "")))
        }
        return items.groupBy({ it.first }, { it.second }).toSortedMap().map { (d, xs) -> d to xs.sortedBy { it.at } }
    }
}

/** What a trip still lacks. Only these show on the countdown card. */
enum class Gap(val ask: String?) { DATES(null), TRANSPORT("ways to get there"), STAY("places to stay"), BACK("ways to get back"), WEATHER(null), ALARM(null) }

data class Missing(val gap: Gap, val text: String)

/** Hypnos's side (alarms): the next alarm he has set, if any. Null until alarms ship. */
fun interface AlarmWatch { fun nextAlarm(): Long? }

object Gaps {
    fun of(t: Trip, w: DestWeather?, now: Long, zone: ZoneId, alarms: AlarmWatch? = null): List<Missing> {
        val out = ArrayList<Missing>()
        val days = t.days(zone)
        if (days == null) out += Missing(Gap.DATES, "No dates yet")
        if (t.outbound == null) out += Missing(Gap.TRANSPORT, "No way there yet")
        val nights = t.nights(zone)
        if (t.stays.isEmpty() && (nights == null || nights > 0)) out += Missing(Gap.STAY, "No place to stay yet")
        if (t.outbound != null && t.back == null && (nights == null || nights > 0) && t.outbound?.mode != LegMode.DRIVE) out += Missing(Gap.BACK, "No way back yet")
        weatherWarning(t, w, zone)?.let { out += Missing(Gap.WEATHER, it) }
        val o = t.outbound
        if (alarms != null && o != null && o.departAt > now && o.departAt - now < 24 * 3_600_000L) {
            val leave = Travel.leaveBy(o)
            val at = Instant.ofEpochMilli(leave).atZone(zone)
            val alarm = alarms.nextAlarm()
            if (at.hour < 9 && (alarm == null || alarm > leave - 30 * 60_000L)) out += Missing(Gap.ALARM, "No alarm before you leave at ${Clock.hm(leave, zone)}")
        }
        return out
    }

    /** The first rough day at the destination during the trip: snow, storms, heavy rain, or hard cold. */
    fun weatherWarning(t: Trip, w: DestWeather?, zone: ZoneId): String? {
        val (s, e) = t.days(zone) ?: return null
        val d = w?.days?.firstOrNull { d ->
            val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@firstOrNull false
            date in s..e && (Sky.kind(d.code) == Sky.Kind.SNOW || Sky.kind(d.code) == Sky.Kind.STORM || (d.rainChance ?: 0) >= 70 || (d.lowC ?: 0.0) <= -10.0 || (d.highC ?: 0.0) >= 35.0)
        } ?: return null
        val date = LocalDate.parse(d.date)
        val what = when {
            Sky.kind(d.code) == Sky.Kind.SNOW -> "Snow"
            Sky.kind(d.code) == Sky.Kind.STORM -> "Storms"
            (d.rainChance ?: 0) >= 70 -> "Rain likely"
            (d.lowC ?: 0.0) <= -10.0 -> "Hard cold"
            else -> "Heat"
        }
        return "$what on ${Clock.day(date)} in ${t.place}"
    }
}

/** What the Live Update says, one stage at a time. `key` changes only at a key moment. */
enum class Stage { LEAVE, DEPART, ON_WAY, ARRIVED }

data class LiveText(val stage: Stage, val key: String, val chip: String, val title: String, val text: String, val countdownTo: Long?, val progress: Pair<Int, Int>?) {
    /** Leave-by reached and a gate or platform announced are the moments worth a sound. */
    val sounds: Boolean get() = stage == Stage.DEPART
}

object LiveWords {
    fun of(t: Trip, l: Leg, now: Long, home: ZoneId): LiveText {
        val depZ = if (l.returning) t.destZone(home) else home
        val arrZ = if (l.returning) home else t.destZone(home)
        val leave = Travel.leaveBy(l)
        val what = listOf(l.carrier, l.number).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { l.mode.word }
        val to = l.to.ifBlank { if (l.returning) "home" else t.place }
        return when {
            now < leave -> {
                val m = (leave - now + 59_999) / 60_000
                LiveText(
                    Stage.LEAVE, "${l.id}:leave", "Leave ${Clock.hm(leave, depZ).substringBefore(' ')}",
                    if (m <= 90) "Leave in ${Clock.mins(m)}" else "Leave at ${Clock.hm(leave, depZ)}",
                    listOf("$what to $to", "departs ${Clock.hm(l.departAt, depZ)}", l.platform).filter { it.isNotBlank() }.joinToString(" · "),
                    countdownTo = leave, progress = null,
                )
            }
            now < l.departAt -> LiveText(
                Stage.DEPART, "${l.id}:depart:${l.platform}", l.platform.ifBlank { Clock.hm(l.departAt, depZ).substringBefore(' ') }.take(12),
                "Departs ${Clock.hm(l.departAt, depZ)}" + (l.platform.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                "$what to $to", countdownTo = null, progress = null,
            )
            l.arriveAt > 0 && now < l.arriveAt -> {
                val total = ((l.arriveAt - l.departAt) / 60_000).toInt().coerceAtLeast(1)
                LiveText(
                    Stage.ON_WAY, "${l.id}:onway", "Arr ${Clock.hm(l.arriveAt, arrZ).substringBefore(' ')}",
                    "Arrive ${Clock.hm(l.arriveAt, arrZ)} $to", "On the way · $what",
                    countdownTo = null, progress = (((now - l.departAt) / 60_000).toInt().coerceIn(0, total)) to total,
                )
            }
            l.arriveAt <= 0 && now < l.departAt + 60 * 60_000L -> LiveText(Stage.ON_WAY, "${l.id}:onway", "On the way", "On the way to $to", what, null, null)
            else -> {
                val stay = t.stays.firstOrNull()
                LiveText(
                    Stage.ARRIVED, "${l.id}:arrived", "Arrived", if (l.returning) "Home" else "Arrived $to",
                    if (l.returning) "Welcome back" else stay?.let { s -> listOfNotNull(s.name.takeIf { it.isNotBlank() }, s.checkIn.takeIf { it > 0 }?.let { "check-in ${Clock.hm(it, arrZ)}" }).joinToString(" · ") } ?: "Welcome to ${t.place}",
                    null, null,
                )
            }
        }
    }
}

/**
 * The words about trips that may leave the phone (Delphi's read_trips, the mind's /plans/): the city, the
 * days, how he travels, what is planned and what is missing. Never an address, a hotel's name, coordinates,
 * a time of a single leg or anything from Calypso. The sanitizer runs on top before upload.
 */
object TripWords {
    fun line(t: Trip, now: Long, zone: ZoneId): String {
        val legs = buildList {
            t.outbound?.let { add("${it.mode.word.lowercase()} there") }
            t.back?.let { add("${it.mode.word.lowercase()} back") }
        }
        val parts = buildList {
            add(t.place + ", " + Travel.dates(t, zone).replace(" · ", ", "))
            add(when (t.status) { TripStatus.IDEA -> "an idea (from his words: ${t.vague.ifBlank { "no date" }})"; TripStatus.PLANNED -> "planned, ${Travel.countdown(t, now, zone)}"; TripStatus.DONE -> "done"; else -> t.status.name.lowercase() })
            if (legs.isNotEmpty()) add(legs.joinToString(", "))
            t.stops.filter { !Cities.same(it.place, t.place) && it.start != null }.takeIf { it.isNotEmpty() }?.let { s ->
                add("also " + s.joinToString(", ") { st -> "${st.place} (${Clock.range(LocalDate.parse(st.start), LocalDate.parse(st.end ?: st.start))})" })
            }
            if (t.layovers.isNotEmpty()) add("via " + t.layovers.joinToString(", "))
            if (t.locked) add("confirmed by him") else if (t.draft) add("draft, Athena hasn't checked it yet")
            if (t.stays.isNotEmpty()) add("stay booked")
            if (t.plans.isNotEmpty()) add("plans: " + t.plans.sortedBy { it.at }.take(5).joinToString("; ") { p -> p.title + (p.at.takeIf { it > 0 }?.let { " (${Clock.day(Trip.day(it, t.destZone(zone)))})" } ?: "") })
            if (t.people.isNotEmpty()) add("with " + t.people.take(5).joinToString(", "))
            val gaps = Gaps.of(t, null, now, zone).filter { it.gap != Gap.WEATHER && it.gap != Gap.ALARM }
            if (t.open && gaps.isNotEmpty()) add("missing: " + gaps.joinToString(", ") { it.text.lowercase() })
            t.notes.lastOrNull()?.let { add("he said: $it") }
        }
        return parts.joinToString("; ")
    }

    /** The mind's /plans/trips.md. Empty when there is nothing to plan. */
    fun plans(trips: List<Trip>, now: Long, zone: ZoneId): String {
        val open = trips.filter { it.open }
        val done = trips.filter { it.status == TripStatus.DONE || it.status == TripStatus.ARCHIVED }.sortedByDescending { it.updatedAt }.take(3)
        if (open.isEmpty() && done.isEmpty()) return ""
        return buildString {
            append("# Trips (Argo, from the phone)\nCity names only. Bookings and confirmations stay on the phone.\n\n")
            if (open.isNotEmpty()) { append("## Coming up\n"); open.forEach { append("- ${line(it, now, zone)}\n") } }
            if (done.isNotEmpty()) { append("\n## Recent\n"); done.forEach { append("- ${it.recap ?: line(it, now, zone)}\n") } }
        }
    }

    /** After the trip: one line in words, kept as its recap. */
    fun recap(t: Trip, zone: ZoneId): String {
        val how = listOfNotNull(t.outbound?.mode?.word?.lowercase(), t.back?.mode?.word?.lowercase()).distinct()
        return buildList {
            add("${t.place}, ${Travel.dates(t, zone).replace(" · ", ", ")}")
            if (how.isNotEmpty()) add((if (how.size == 1 && t.back != null) "${how[0]} there and back" else how.joinToString(" there, ") + if (t.back != null) " back" else " there").replaceFirstChar { it.uppercase() })
            if (t.plans.isNotEmpty()) add("${t.plans.size} ${if (t.plans.size == 1) "plan" else "plans"}: " + t.plans.sortedBy { it.at }.take(4).joinToString(", ") { it.title })
            if (t.people.isNotEmpty()) add("with " + t.people.take(4).joinToString(", "))
        }.joinToString(". ") + "."
    }
}
