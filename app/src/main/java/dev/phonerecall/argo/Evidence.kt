package dev.phonerecall.argo

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/*
 * The evidence layer: everything a source said about his travel, as legs, stays, dated spans ("Denver, Mar 21 to
 * 29") and plans, each with its source and how far it is trusted. Trips are assembled from this list on every change
 * (Assemble.kt), so a wrong trip is mended by better evidence instead of staying stuck. Then the same flight from two
 * calendars is one leg, and a calendar that keeps airport-local times as UTC is repaired. Pure.
 */

enum class EvKind { LEG, STAY, SPAN, PLAN }

data class Ev(
    val id: String = "",
    val kind: EvKind = EvKind.LEG,
    /** calendar, iris, delphi, you or athena: what the trip says it came from. */
    val source: String = "",
    /** One feed per calendar ("cal:6"), "iris" (trip_info), "sweep" (her weekly lines) or "said" (his words): time repair works per feed. */
    val feed: String = "",
    val confidence: Double = 0.5,
    /** The city: a leg's destination, a stay's or a span's place. */
    val place: String = "",
    val leg: Leg? = null,
    val stay: Stay? = null,
    val plan: TripPlan? = null,
    /** A span's days (ISO); only `month` for an idea; neither for "someday". */
    val start: String? = null,
    val end: String? = null,
    val month: String? = null,
    /** The other copies merged into this one (the other calendar's event for the same flight). */
    val also: List<String> = emptyList(),
    /** A calendar event's own zone, and whether it was all day: Athena reads raw times with these. */
    val tz: String? = null,
    val allDay: Boolean = false,
) {
    @get:JsonIgnore val ids: List<String> get() = listOf(id) + also
}

object Evidence {
    /** His words and Athena's findings: they keep a trip even with no booking. */
    val WORDS = setOf("delphi", "you", "athena")
    private const val CAL = 0.9
    private const val IRIS = 0.95
    private const val SWEEP = 0.7

    fun of(i: TripItem, feed: String, confidence: Double): Ev? = when (i) {
        is TripItem.LegItem -> Ev(i.leg.id, EvKind.LEG, i.source, feed, confidence, i.place, leg = i.leg)
        is TripItem.StayItem -> Ev(i.stay.id, EvKind.STAY, i.source, feed, confidence, i.place, stay = i.stay)
        is TripItem.PlanItem -> Ev(i.plan.id, EvKind.PLAN, i.source, feed, confidence, i.place, plan = i.plan)
        is TripItem.Dates -> Ev(i.id, EvKind.SPAN, i.source, feed, confidence, i.place, start = i.start.toString(), end = i.end.toString())
        is TripItem.Cancel -> null
    }

    /** Every calendar of his, one feed each. Subscribed, sports, holiday, birthday and followed calendars are never evidence. */
    fun calendar(events: List<CalItem>, zone: ZoneId, kinds: Map<Long, CalKind> = emptyMap()): List<Ev> =
        events.filter { kinds[it.calendarId] !in CalendarCapture.NOT_HIS }.mapNotNull { e ->
            CalendarCapture.item(e, zone)?.let { of(it, "cal:${e.calendarId}", if (e.allDay) CAL - 0.1 else CAL)?.copy(tz = e.timeZone, allDay = e.allDay) }
        }

    /** Iris's trip.info: added (a resend replaces it by id), or a cancel removes what it names, with her earlier copies of it. */
    fun iris(ev: List<Ev>, items: List<TripItem>): List<Ev> = items.fold(ev) { acc, i ->
        when {
            i is TripItem.Cancel && i.match != null -> acc.filter { e -> !samePlan(e, i.place, i.match) }
            i is TripItem.Cancel -> {
                val gone = acc.firstOrNull { it.id == i.ref }
                acc.filter { e -> e.id != i.ref && !(gone?.leg != null && e.feed == "iris" && e.leg != null && Dedupe.same(gone.leg, e.leg, ZoneOffset.UTC)) }
            }
            // A changed booking replaces her earlier copy of it (the same title in the same city).
            i is TripItem.PlanItem -> of(i, "iris", IRIS)!!.let { n -> acc.filter { it.id != n.id && !(it.feed == "iris" && samePlan(it, i.place, i.plan, days = if (i.changed) 30 else 0)) } + n }
            else -> of(i, "iris", IRIS)?.let { n -> acc.filter { it.id != n.id } + n } ?: acc
        }
    }

    /** The same plan: its title in the same city, within `days` of it (a day for a cancel; a month for a change). */
    private fun samePlan(e: Ev, city: String, p: TripPlan, days: Int = 1): Boolean = e.kind == EvKind.PLAN && e.plan != null &&
        Cities.same(e.place, city) && e.plan.title.trim().equals(p.title.trim(), true) && (p.at <= 0 || e.plan.at <= 0 || abs(e.plan.at - p.at) <= days * 86_400_000L)

    /** When a piece of evidence happens, for keeping calendar evidence older than the read window. */
    fun time(e: Ev, zone: ZoneId): Long? = when (e.kind) {
        EvKind.LEG -> e.leg?.let { Assemble.at(it, zone) }?.takeIf { it > 0 }
        EvKind.STAY -> e.stay?.checkIn?.takeIf { it > 0 }
        EvKind.PLAN -> e.plan?.at?.takeIf { it > 0 }
        EvKind.SPAN -> e.start?.let { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }
    }

    // ---- Iris's sweep lines: "City (anything), YYYY-MM-DD to YYYY-MM-DD (anything) [src]" ----

    private val RANGE = Regex("""(\d{4}-\d{2}-\d{2})(?:\s*(?:to|until|through|-|–)\s*(\d{4}-\d{2}-\d{2}))?""")
    private val ISO = Regex("""\d{4}-\d{2}-\d{2}""")
    private val MONTH_DAY = Regex("""\b(\d{1,2})[-/](\d{1,2})\b""")
    private val ROUTE = Regex("""\b([A-Z][A-Za-z.']+(?:\s[A-Z][a-z]+)?)\s*(?:-|–|→|->|\sto\s)\s*([A-Z][A-Za-z.']+(?:\s[A-Z][a-z]+)?)""")
    private val TRAIN = Regex("(?i)\\b(train|amtrak|rail)\\b")
    private val BUS = Regex("(?i)\\b(bus|greyhound|megabus)\\b")

    /** A leg's end in Iris's words: "DEN", "Buffalo", "DEN Sun" (the day after the code is ignored). */
    private fun end(token: String): Pair<String, String>? {
        Airports.find(token)?.let { return Airports.label(it) to it.city }
        return Cities.find(token)?.let { it to it }
    }

    /** One line of her trips section: a span, and the legs its parentheses name. Lines starting "~" are her doubts: skipped. */
    fun line(raw: String, today: LocalDate): List<Ev> {
        var t = raw.trim().trimStart('-', '*', '•', ' ')
        if (t.isEmpty() || t.startsWith("~") || t.equals("none", true)) return emptyList()
        t = t.replace(Regex("""\s*\[[^\]]*]\s*$"""), "")
        val m = RANGE.find(t) ?: return emptyList()
        val head = t.substring(0, m.range.first).replace(Regex("""\([^)]*\)"""), " ").trim().trimEnd(',', ':', ' ', '–', '-')
        val name = head.substringBefore(',').trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val city = Cities.canonical(name)
        if (Cities.same(city, Cities.HOME)) return emptyList()
        val s = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull() ?: return emptyList()
        val e = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: s
        if (e < s || ChronoUnit.DAYS.between(s, e) > 120 || s < today.minusDays(30)) return emptyList()
        val span = Ev("sweep:${city.lowercase()}:$s", EvKind.SPAN, "iris", "sweep", SWEEP, city, start = s.toString(), end = e.toString())
        val legs = t.substring(m.range.last + 1).split(Regex("""\band\b|;|,""")).mapNotNull { seg ->
            val r = ROUTE.find(seg) ?: return@mapNotNull null
            val (fromLabel, from) = end(r.groupValues[1]) ?: return@mapNotNull null
            val (toLabel, to) = end(r.groupValues[2]) ?: return@mapNotNull null
            if (Cities.same(from, to)) return@mapNotNull null
            val home = Cities.same(to, Cities.HOME)
            val date = ISO.find(seg)?.let { runCatching { LocalDate.parse(it.value) }.getOrNull() }
                ?: MONTH_DAY.find(seg)?.let { md ->
                    val mo = md.groupValues[1].toInt(); val d = md.groupValues[2].toInt()
                    runCatching { LocalDate.of(if (mo < s.monthValue) s.year + 1 else s.year, mo, d) }.getOrNull()
                }
                ?: if (Cities.same(from, Cities.HOME)) s else if (home) e else return@mapNotNull null
            val mode = if (TRAIN.containsMatchIn(seg)) LegMode.TRAIN else if (BUS.containsMatchIn(seg)) LegMode.BUS else LegMode.FLIGHT
            val code = { label: String -> Airports.code(label) ?: label.lowercase() }
            val leg = Leg("sweep:${code(fromLabel)}-${code(toLabel)}:$date", mode, fromLabel, toLabel, returning = home, source = "iris", day = date.toString())
            Ev(leg.id, EvKind.LEG, "iris", "sweep", SWEEP - 0.1, if (home) city else to, leg = leg)
        }
        return listOf(span) + legs
    }

    fun sweep(lines: List<String>, today: LocalDate): List<Ev> = lines.flatMap { line(it, today) }.distinctBy { it.id }

    // ---- his words, kept on the stored trips ----

    /**
     * What he (or Athena, from a finding he'd see) said about a trip, as a span on it: the days, else the month, else
     * just the place. Its id names the trip, so the assembled trip goes back to the one holding his notes.
     */
    // Trips she placed (or he locked) are held as they are: their "athena" source is not words to feed back as an idea.
    fun said(trips: List<Trip>): List<Ev> = trips.filter { t -> t.open && !t.locked && !t.athena && t.sources.any { it in WORDS } }.map { t ->
        val src = t.sources.firstOrNull { it in WORDS } ?: "delphi"
        val start = t.saidStart ?: t.startDate?.takeIf { t.legs.isEmpty() && t.stays.isEmpty() }
        val end = t.saidEnd ?: t.endDate?.takeIf { start != null && t.legs.isEmpty() && t.stays.isEmpty() } ?: start
        Ev("said:${t.id}", EvKind.SPAN, src, "said", 0.6, t.place, start = start, end = end, month = if (start == null) t.month else null)
    }

    /**
     * The first run after the redesign: what only lived on the stored trips (Iris's trip_info, already acked, and her
     * sweep's dates) becomes evidence again. The calendar is read fresh on the same tick.
     */
    fun migrate(trips: List<Trip>): List<Ev> = trips.filter { it.open }.flatMap { t ->
        t.legs.filter { it.id.startsWith("iris:") }.map { Ev(it.id, EvKind.LEG, "iris", "iris", IRIS, t.place, leg = it) } +
            t.stays.filter { it.id.startsWith("iris:") }.map { Ev(it.id, EvKind.STAY, "iris", "iris", IRIS, t.place, stay = it) } +
            t.plans.filter { it.id.startsWith("iris:") }.map { Ev(it.id, EvKind.PLAN, "iris", "iris", IRIS, t.place, plan = it) } +
            listOfNotNull(t.startDate?.takeIf { "iris" in t.sources }?.let { s ->
                Ev("sweep:${t.place.lowercase()}:$s", EvKind.SPAN, "iris", "sweep", SWEEP, t.place, start = s, end = t.endDate ?: s)
            })
    }.distinctBy { it.id }
}

/**
 * Time repair. A calendar that stores the airport's local time as if it were UTC (or as his own zone) shows every
 * flight hours off. Per feed, the reading is chosen that makes its flights agree with the same flights elsewhere, take a
 * plausible time in the air and connect in order. The choice is kept per feed, so its next booking is read right too.
 */
object Repair {
    const val NONE = "none"
    const val UTC = "local-as-utc"
    const val HOME = "local-as-home"
    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L

    fun words(h: String): String = when {
        h == UTC -> "keeps airport-local times as UTC"
        h == HOME -> "keeps airport-local times in his own zone"
        h.startsWith("shift:") -> "is ${h.removePrefix("shift:")} h off"
        else -> "is right"
    }

    fun time(ms: Long, h: String, at: ZoneId?, home: ZoneId): Long {
        if (ms <= 0 || h == NONE) return ms
        if (h.startsWith("shift:")) return ms - (h.removePrefix("shift:").toLongOrNull() ?: 0) * HOUR
        val z = at ?: return ms
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), if (h == UTC) ZoneOffset.UTC else home).atZone(z).toInstant().toEpochMilli()
    }

    fun leg(l: Leg, h: String, home: ZoneId): Leg {
        if (h == NONE || l.departAt <= 0) return l
        val dep = time(l.departAt, h, Airports.zoneOf(l.from), home)
        val arr = if (l.arriveAt <= 0) l.arriveAt else Airports.zoneOf(l.to)?.let { time(l.arriveAt, h, it, home) } ?: (l.arriveAt + dep - l.departAt)
        return l.copy(departAt = dep, arriveAt = arr)
    }

    /** In the air too short or too long for the distance (or landing before it leaves). */
    fun implausible(l: Leg): Boolean {
        if (l.mode != LegMode.FLIGHT || l.departAt <= 0 || l.arriveAt <= 0) return false
        val m = (l.arriveAt - l.departAt) / MIN
        if (m <= 0) return true
        val a = Airports.find(l.from) ?: return false
        val b = Airports.find(l.to) ?: return false
        return a.code != b.code && m !in Airports.flightMinutes(a, b)
    }

    /** Connections out of order: the next flight from where one landed leaves before it lands (or within 25 minutes). */
    fun broken(legs: List<Leg>): Int = legs.filter { it.departAt > 0 }.sortedBy { it.departAt }.zipWithNext().count { (a, b) ->
        val x = Cities.city(a.to); val y = Cities.city(b.from)
        x != null && y != null && Cities.same(x, y) && b.departAt - a.departAt < 30 * HOUR &&
            b.departAt - (if (a.arriveAt > a.departAt) a.arriveAt else a.departAt) < 25 * MIN
    }

    /** One reading per calendar feed. `prior` is what was chosen before, a small nudge toward the same again. */
    fun choose(legs: List<Ev>, prior: Map<String, String>, home: ZoneId): Map<String, String> {
        val timed = legs.filter { (it.leg?.departAt ?: 0) > 0 }
        return timed.map { it.feed }.distinct().filter { it.startsWith("cal:") }.associateWith { f ->
            val mine = timed.filter { it.feed == f }
            val others = timed.filter { it.feed != f }
            val pairs = mine.flatMap { a -> others.filter { b -> Dedupe.same(a.leg!!, b.leg!!, home, loose = true) }.map { a.leg!! to it.leg!! } }
            // A uniform skew (every copy N hours off) is its own reading.
            val shift = pairs.map { (a, b) -> Math.round((a.departAt - b.departAt) / HOUR.toDouble()) }.distinct().singleOrNull()
                ?.takeIf { it != 0L && abs(it) <= 14 && pairs.size >= 2 }?.let { "shift:$it" }
            val readings = listOfNotNull(NONE, UTC, HOME, shift, prior[f]).distinct()
            fun score(h: String): Int {
                val fixed = mine.map { leg(it.leg!!, h, home) }
                val agree = pairs.count { (a, b) -> abs(leg(a, h, home).departAt - b.departAt) <= 10 * MIN }
                // As is unless the copies agree on another reading, or it mends two or more impossible flights; a kept repair counts most.
                return agree * 10 - fixed.count(::implausible) * 3 - broken(fixed) * 3 + (if (prior[f] == h) 5 else 0) + (if (h == NONE) 4 else 0)
            }
            readings.maxByOrNull(::score)!!
        }
    }
}

/** The same flight, stay or train from two sources is one piece of evidence. */
object Dedupe {
    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L

    data class Clean(val ev: List<Ev>, val corrections: Map<String, String>, val disagree: List<String>)

    private fun day(l: Leg, home: ZoneId): LocalDate? = l.departAt.takeIf { it > 0 }?.let { Trip.day(it, Airports.zoneOf(l.from) ?: home) } ?: l.dayOf()

    /**
     * The same flight: the same number on the same date (a codeshare, "AA 303" and "AS 303", has the same digits
     * and route), or with no number the same route within two hours. A leg known only by its day joins a timed one on
     * that route and day. `loose` widens the unnumbered window for comparing feeds before their times are repaired.
     */
    fun same(a: Leg, b: Leg, home: ZoneId, loose: Boolean = false): Boolean {
        if (a.id == b.id) return true
        if (a.mode != b.mode) return false
        fun ok(x: String?, y: String?) = x == null || y == null || Cities.same(x, y)
        val fa = Cities.city(a.from); val fb = Cities.city(b.from); val ta = Cities.city(a.to); val tb = Cities.city(b.to)
        val route = ok(fa, fb) && ok(ta, tb)
        val timed = a.departAt > 0 && b.departAt > 0
        val da = Flights.digits(a.number); val db = Flights.digits(b.number)
        if (da != null && db != null) {
            if (da != db || !route) return false
            val near = if (timed) abs(a.departAt - b.departAt) < 30 * HOUR
                else day(a, home)?.let { x -> day(b, home)?.let { y -> abs(ChronoUnit.DAYS.between(x, y)) <= 1 } } == true
            val carrier = Flights.carrierCode(a.carrier.ifBlank { Flights.carrierOf(a.number) }) == Flights.carrierCode(b.carrier.ifBlank { Flights.carrierOf(b.number) })
            return near && (carrier || (fa != null && fb != null) || (ta != null && tb != null))
        }
        if (!route || ta == null || tb == null) return false
        if (timed) return abs(a.departAt - b.departAt) <= (if (loose) 16 else 2) * HOUR
        return fa != null && fb != null && day(a, home) != null && day(a, home) == day(b, home)
    }

    private fun sameStay(a: Ev, b: Ev): Boolean {
        val x = a.stay!!; val y = b.stay!!
        if (x.id == y.id) return true
        if (a.place.isNotBlank() && b.place.isNotBlank() && !Cities.same(a.place, b.place)) return false
        if (x.checkIn <= 0 || y.checkIn <= 0) return false
        return x.checkIn < maxOf(y.checkOut, y.checkIn + HOUR) && y.checkIn < maxOf(x.checkOut, x.checkIn + HOUR)
    }

    private fun <T> groups(xs: List<T>, same: (T, T) -> Boolean): List<List<T>> {
        val root = IntArray(xs.size) { it }
        fun find(i: Int): Int { var r = i; while (root[r] != r) r = root[r]; return r }
        for (i in xs.indices) for (j in i + 1 until xs.size) if (same(xs[i], xs[j])) root[find(j)] = find(i)
        return xs.indices.groupBy(::find).values.map { g -> g.map { xs[it] } }
    }

    /** Iris first (she reads the booking), then a copy that names both airports, then one with times. */
    private fun rank(e: Ev): Int = (if (e.source == "iris" && e.feed == "iris") 8 else 0) +
        (if (e.leg?.let { Airports.code(it.from) != null && Airports.code(it.to) != null } == true) 2 else 0) + (if ((e.leg?.departAt ?: 0) > 0) 1 else 0)

    private fun mergeLegs(g: List<Ev>): Ev {
        if (g.size == 1) return g[0]
        // Ties go to the latest copy: Iris's resend with the gate carries the id she cancels by.
        val p = g.withIndex().maxWithOrNull(compareBy({ rank(it.value) }, { it.index }))!!.value
        val legs = listOf(p.leg!!) + g.filter { it !== p }.map { it.leg!! }
        val timed = legs.firstOrNull { it.departAt > 0 }
        fun end(f: (Leg) -> String) = legs.map(f).firstOrNull { Airports.code(it) != null } ?: legs.map(f).firstOrNull { it.isNotBlank() }.orEmpty()
        val iris = g.filter { it.source == "iris" }.map { it.leg!! }
        val leg = p.leg.copy(
            from = end { it.from }, to = end { it.to },
            departAt = timed?.departAt ?: 0, arriveAt = timed?.arriveAt?.takeIf { it > 0 } ?: legs.firstOrNull { it.arriveAt > 0 }?.arriveAt ?: 0,
            carrier = legs.firstOrNull { it.carrier.isNotBlank() }?.carrier.orEmpty(), number = legs.firstOrNull { it.number.isNotBlank() }?.number.orEmpty(),
            platform = (iris.lastOrNull { it.platform.isNotBlank() } ?: legs.lastOrNull { it.platform.isNotBlank() })?.platform.orEmpty(),
            returning = legs.any { it.returning }, doc = legs.firstNotNullOfOrNull { it.doc }, source = if (iris.isNotEmpty()) "iris" else p.leg.source,
            cents = legs.firstNotNullOfOrNull { it.cents }, paid = legs.firstNotNullOfOrNull { it.paid }, day = if (timed == null) p.leg.day else null,
        )
        return p.copy(leg = leg, source = leg.source, confidence = minOf(1.0, g.maxOf { it.confidence } + 0.05), also = (g.flatMap { it.ids } - p.id).distinct())
    }

    private fun mergeStays(g: List<Ev>): Ev {
        if (g.size == 1) return g[0]
        val p = g.maxByOrNull { it.confidence }!!
        val stays = g.map { it.stay!! }
        val name = g.firstOrNull { it.source == "iris" }?.stay?.name?.takeIf { it.isNotBlank() } ?: stays.map { it.name }.maxByOrNull { it.length }.orEmpty()
        val stay = p.stay!!.copy(
            name = name, address = stays.firstOrNull { it.address.isNotBlank() }?.address.orEmpty(),
            doc = stays.firstNotNullOfOrNull { it.doc }, cents = stays.firstNotNullOfOrNull { it.cents }, paid = stays.firstNotNullOfOrNull { it.paid },
            source = if (g.any { it.source == "iris" }) "iris" else p.stay.source,
        )
        return p.copy(stay = stay, place = g.firstOrNull { it.place.isNotBlank() }?.place ?: p.place, also = (g.flatMap { it.ids } - p.id).distinct())
    }

    private fun what(l: Leg) = l.number.ifBlank { listOf(l.from, l.to).filter { it.isNotBlank() }.joinToString(" to ") }

    /** `choose`: pick each feed's reading here (the offline draft); false keeps `prior` as given (Athena's repairs). */
    fun run(all: List<Ev>, prior: Map<String, String>, home: ZoneId, choose: Boolean = true): Clean {
        val legs = all.filter { it.kind == EvKind.LEG && it.leg != null }
        val fix = if (choose) Repair.choose(legs, prior, home) else prior
        val fixed = legs.map { e -> fix[e.feed]?.takeIf { it != Repair.NONE }?.let { h -> e.copy(leg = Repair.leg(e.leg!!, h, home)) } ?: e }
        val legGroups = groups(fixed) { a, b -> same(a.leg!!, b.leg!!, home) }
        val disagree = legGroups.mapNotNull { g ->
            val ts = g.map { it.leg!!.departAt }.filter { it > 0 }
            if (ts.size > 1 && ts.max() - ts.min() > 15 * MIN) "${what(g.first().leg!!)}: the sources disagree on the time by ${(ts.max() - ts.min()) / MIN} min" else null
        }
        val stays = groups(all.filter { it.kind == EvKind.STAY && it.stay != null }, ::sameStay).map(::mergeStays)
        val corrections = prior.filterKeys { it !in fix } + fix.filterValues { it != Repair.NONE }
        return Clean(legGroups.map(::mergeLegs) + stays + all.filter { it.kind == EvKind.SPAN || it.kind == EvKind.PLAN }, corrections, disagree)
    }
}
