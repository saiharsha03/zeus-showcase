package dev.phonerecall.argo

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Journey assembly: a trip is leaving home, every leg and stay until landing back home. Legs are ordered by time; a
 * stop under a day between legs is a layover; the destination is the longest stay; every stay is a stop (Denver and
 * Boulder). A hotel or Iris's dated line with no legs still makes a trip. Then a sanity pass flags what looks
 * wrong for Athena, and the rebuild maps the result onto the stored trips (ids, his marks, locks). Pure.
 */

data class Draft(
    val place: String = "",
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val month: YearMonth? = null,
    val legs: List<Leg> = emptyList(),
    val stays: List<Stay> = emptyList(),
    val plans: List<TripPlan> = emptyList(),
    val stops: List<Stop> = emptyList(),
    val layovers: List<String> = emptyList(),
    val ids: Set<String> = emptySet(),
    val sources: Set<String> = emptySet(),
    /** Evidence feeds behind it ("cal:6", "iris", "sweep", "said"). */
    val feeds: Set<String> = emptySet(),
    val returned: Boolean = false,
    /** Its first leg leaves from somewhere other than home, and nothing says he was there before. */
    val startsAway: Boolean = false,
    /** The longest time at the destination, in hours: a few hours means it is really a connection. */
    val destHours: Long = 0,
)

object Assemble {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR

    /** When a leg happens: its departure, else noon on its day where it leaves. */
    fun at(l: Leg, zone: ZoneId): Long = l.departAt.takeIf { it > 0 }
        ?: l.dayOf()?.atTime(12, 0)?.atZone(Airports.zoneOf(l.from) ?: zone)?.toInstant()?.toEpochMilli() ?: 0

    fun landed(l: Leg, zone: ZoneId): Long = l.arriveAt.takeIf { it > 0 && it > l.departAt } ?: at(l, zone)

    fun isHome(city: String?) = city != null && Cities.same(city, Cities.HOME)

    private fun placeZone(place: String, zone: ZoneId) = Airports.zoneOfCity(place)?.let { ZoneId.of(it) } ?: zone

    /** The days a piece of evidence covers, in his zone for legs and where it is for the rest; null for an undated idea. */
    fun days(e: Ev, zone: ZoneId): Pair<LocalDate, LocalDate>? = when (e.kind) {
        EvKind.LEG -> e.leg?.let { l -> at(l, zone).takeIf { it > 0 }?.let { Trip.day(it, zone) to Trip.day(landed(l, zone), zone) } }
        EvKind.STAY -> e.stay?.takeIf { it.checkIn > 0 }?.let { s -> placeZone(e.place, zone).let { z -> Trip.day(s.checkIn, z) to Trip.day(maxOf(s.checkOut, s.checkIn), z) } }
        EvKind.SPAN -> e.start?.let { s -> LocalDate.parse(s).let { a -> a to maxOf(a, LocalDate.parse(e.end ?: s)) } }
        EvKind.PLAN -> e.plan?.at?.takeIf { it > 0 }?.let { Trip.day(it, placeZone(e.place, zone)) }?.let { it to it }
    }

    private class Group {
        val legs = ArrayList<Ev>()
        val blocks = ArrayList<Ev>()
        val plans = ArrayList<Ev>()
        val ideas = ArrayList<Ev>()
        var returned = false
        var startsAway = false
        var lo: LocalDate? = null
        var hi: LocalDate? = null
        fun widen(s: LocalDate, e: LocalDate) { lo = lo?.let { minOf(it, s) } ?: s; hi = hi?.let { maxOf(it, e) } ?: e }
    }

    private fun overlap(a: Pair<LocalDate, LocalDate>, b: Pair<LocalDate, LocalDate>): Long =
        ChronoUnit.DAYS.between(maxOf(a.first, b.first), minOf(a.second, b.second)) + 1

    fun linked(a: String, b: String, rules: List<TripRule>) = Cities.same(a, b) ||
        rules.any { it.kind == RuleKind.PART_OF && ((Cities.same(it.place, a) && Cities.same(it.of, b)) || (Cities.same(it.place, b) && Cities.same(it.of, a))) }

    fun drafts(ev: List<Ev>, rules: List<TripRule>, zone: ZoneId): List<Draft> {
        // 1. Legs into journeys: one starts when he leaves home (or with the first leg seen), and landing home closes it.
        val groups = ArrayList<Group>()
        var cur: Group? = null
        for (e in ev.filter { it.kind == EvKind.LEG && it.leg != null && at(it.leg, zone) > 0 }.sortedBy { at(it.leg!!, zone) }) {
            val l = e.leg!!
            val from = Cities.city(l.from); val to = Cities.city(l.to)
            val toHome = if (to != null) isHome(to) else l.returning
            val last = cur?.legs?.last()?.leg
            if (last != null && (isHome(from) || at(l, zone) - at(last, zone) > 45 * DAY)) cur = null
            val g = cur ?: Group().also { n -> n.startsAway = !isHome(from) && (from != null || toHome); groups += n }
            g.legs += e
            days(e, zone)?.let { (s, en) -> g.widen(s, en) }
            cur = if (toHome) { g.returned = true; null } else g
        }
        // An open journey (no way back yet) reaches until the next one starts, or a month.
        val journeys = groups.size
        val reach = groups.mapIndexed { i, g -> if (g.returned) g.hi!! else minOf(groups.getOrNull(i + 1)?.lo?.minusDays(1) ?: g.hi!!.plusDays(30), g.hi!!.plusDays(30)) }

        // 2. Stays and dated spans: overlapping ones (or ones his rules link, a day or three apart) are one block.
        val blocks = ev.filter { (it.kind == EvKind.STAY || it.kind == EvKind.SPAN) && days(it, zone) != null }.sortedBy { days(it, zone)!!.first }
        val clusters = ArrayList<MutableList<Ev>>()
        for (e in blocks) {
            val d = days(e, zone)!!
            val hit = clusters.firstOrNull { c -> c.any { o -> days(o, zone)!!.let { od -> overlap(od, d) > 0 || (linked(o.place, e.place, rules) && !Cities.same(o.place, e.place) && overlap(od, d.first.minusDays(3) to d.second.plusDays(3)) > 0) } } }
            if (hit != null) hit += e else clusters += mutableListOf(e)
        }
        for (c in clusters) {
            val span = c.map { days(it, zone)!! }.let { ds -> ds.minOf { it.first } to ds.maxOf { it.second } }
            // Joins the journey it overlaps most (a day of slack), else is its own trip (a hotel, or Iris's line, alone).
            val best = (0 until journeys).map { i -> i to overlap(span, groups[i].lo!!.minusDays(1) to reach[i].plusDays(1)) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            val g = best?.let { groups[it] } ?: Group().also { groups += it }
            g.blocks += c
            g.widen(span.first, span.second)
        }

        // 3. Ideas (a month, or only a place) join a dated trip to that place in that month; plans join the trip they fall in.
        val dated = groups.sortedBy { it.lo }
        val ideas = ArrayList<Group>()
        for (e in ev.filter { it.kind == EvKind.SPAN && days(it, zone) == null }) {
            val m = e.month?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            val g = dated.firstOrNull { g -> g.places().any { Cities.same(it, e.place) } && (m == null || YearMonth.from(g.lo!!) == m || YearMonth.from(g.hi!!) == m) }
            if (g != null) g.ideas += e else ideas += Group().also { it.ideas += e }
        }
        val all = dated + ideas
        for (e in ev.filter { it.kind == EvKind.PLAN }) {
            val d = days(e, zone) ?: continue
            all.firstOrNull { g -> g.lo != null && d.first in g.lo!!..g.hi!! }?.plans?.add(e)
        }
        return all.map { g ->
            val i = groups.indexOf(g)
            val until = if (i in 0 until journeys && !g.returned) reach[i].plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() else null
            shape(g.legs, g.blocks, g.plans, g.ideas, g.returned, g.startsAway, rules, zone, openUntil = until)
        }
    }

    private fun Group.places(): List<String> = (legs.mapNotNull { it.leg?.to?.let(Cities::city) } + blocks.map { it.place }).filter { it.isNotBlank() }

    private data class Iv(val place: String, val from: Long, val to: Long, val gap: Boolean) { val ms get() = to - from }

    /**
     * One trip from its evidence: where he stops and for how long, which stops are layovers, the destination, and which
     * legs are the way back. `dest` fixes the destination (a confirmed trip). With no way back yet, the last stop lasts
     * until `openUntil` (the next journey, or a month): a one-way to Austin is a trip to Austin, not to its layover.
     */
    fun shape(
        legEv: List<Ev>, blocks: List<Ev>, plans: List<Ev>, ideas: List<Ev>, returned: Boolean, startsAway: Boolean, rules: List<TripRule>, zone: ZoneId,
        dest: String? = null, openUntil: Long? = null,
    ): Draft {
        val ls = legEv.map { it.leg!! }.sortedBy { at(it, zone) }
        val ivs = ArrayList<Iv>()
        for ((a, b) in ls.zipWithNext()) {
            val x = Cities.city(a.to) ?: Cities.city(b.from) ?: continue
            if (!isHome(x)) ivs += Iv(x, landed(a, zone), at(b, zone), true)
        }
        val blockIvs = blocks.mapNotNull { e ->
            val z = placeZone(e.place, zone)
            when (e.kind) {
                EvKind.STAY -> e.stay!!.let { s -> Iv(Cities.canonical(e.place), s.checkIn, maxOf(s.checkOut, s.checkIn + HOUR), false) }
                else -> days(e, zone)?.let { (s, en) -> Iv(Cities.canonical(e.place), s.atStartOfDay(z).toInstant().toEpochMilli(), en.atStartOfDay(z).toInstant().toEpochMilli().let { if (en == s) it + DAY else it }, false) }
            }
        }.filter { it.place.isNotBlank() && !isHome(it.place) }
        ivs += blockIvs
        if (!returned && ls.isNotEmpty()) Cities.city(ls.last().to)?.takeIf { !isHome(it) }?.let { x ->
            val land = landed(ls.last(), zone)
            ivs += Iv(x, land, maxOf(land + DAY, openUntil ?: 0, blockIvs.filter { Cities.same(it.place, x) }.maxOfOrNull { it.to } ?: 0), false)
        }
        val longest = ivs.groupBy { Cities.canonical(it.place) }.mapValues { (_, v) -> v.maxOf { it.ms } }
        val ruleLayover = rules.filter { it.kind == RuleKind.LAYOVER }.map { Cities.canonical(it.place) }.toSet()
        val lay = longest.filter { (p, ms) -> (ms < DAY && ivs.any { it.gap && Cities.same(it.place, p) }) || (p in ruleLayover && ms < 3 * DAY) }.keys
        val firstHome = ls.firstOrNull { isHome(Cities.city(it.to)) }?.let { at(it, zone) } ?: Long.MAX_VALUE
        val candidates = longest.keys.filter { it !in lay }
        val place = dest?.let(Cities::canonical)
            ?: candidates.maxWithOrNull(compareBy<String> { longest.getValue(it) / (6 * HOUR) }.thenBy { p -> ivs.filter { Cities.same(it.place, p) && it.from < firstHome }.maxOfOrNull { it.from } ?: 0 })
            // A day trip: the longest short stop is still where he went.
            ?: longest.maxByOrNull { it.value }?.key
            ?: ls.lastOrNull()?.let { Cities.city(it.to) }?.takeIf { !isHome(it) }
            ?: ideas.firstOrNull()?.place?.let(Cities::canonical).orEmpty()
        val destIv = ivs.filter { Cities.same(it.place, place) }.maxByOrNull { it.ms }
        val back = destIv?.to ?: Long.MAX_VALUE
        val legs = ls.map { l -> val r = l.returning || isHome(Cities.city(l.to)) || (destIv != null && at(l, zone) >= back - HOUR); if (r != l.returning) l.copy(returning = r) else l }
        val stopPlaces = (candidates + listOf(place)).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        val stops = stopPlaces.mapNotNull { p ->
            val mine = ivs.filter { Cities.same(it.place, p) }.ifEmpty { return@mapNotNull null }
            val z = placeZone(p, zone)
            Stop(p, Trip.day(mine.minOf { it.from }, z).toString(), Trip.day(mine.maxOf { it.to }, z).toString())
        }.sortedBy { it.start }
        val starts = legEv.mapNotNull { days(it, zone)?.first } + blocks.mapNotNull { days(it, zone)?.first }
        val ends = legEv.mapNotNull { days(it, zone)?.second } + blocks.mapNotNull { days(it, zone)?.second }
        val start = starts.minOrNull()
        val month = if (start == null) ideas.firstNotNullOfOrNull { it.month }?.let { runCatching { YearMonth.parse(it) }.getOrNull() } else null
        val every = legEv + blocks + plans + ideas
        return Draft(
            place = place, start = start, end = ends.maxOrNull()?.let { e -> start?.let { maxOf(it, e) } ?: e }, month = month,
            legs = legs, stays = blocks.mapNotNull { it.stay }, plans = plans.mapNotNull { it.plan },
            stops = stops, layovers = (lay - place).filter { l -> stops.none { Cities.same(it.place, l) } }.sortedBy { p -> ivs.filter { Cities.same(it.place, p) }.minOf { it.from } },
            ids = every.flatMap { it.ids }.toSet(), sources = every.map { it.source }.filter { it.isNotBlank() }.toSet(), feeds = every.map { it.feed }.toSet(),
            returned = returned,
            // No leg leaves home: a hotel or a dated line says where he was, not how he got there.
            startsAway = startsAway && legEv.none { Cities.city(it.leg!!.from)?.let(::isHome) == true },
            destHours = (destIv?.ms ?: 0) / HOUR,
        )
    }
}

enum class FlagKind { STARTS_AWAY, LAYOVER_TRIP, CLOSE_TRIPS, TIMES_DISAGREE, IRIS_ONLY, NOT_FROM_IRIS }

data class Flag(val kind: FlagKind, val text: String)

/** What looks wrong after assembly. Any flag sends Athena one note (Reconcile.kt); the trips stand until she answers. */
object Sanity {
    private fun d(s: LocalDate?) = s?.let { Clock.day(it).substringAfter(", ") } ?: "?"

    fun of(drafts: List<Draft>, disagree: List<String>, rules: List<TripRule>, sweepAnswered: Boolean, today: LocalDate): List<Flag> = buildList {
        val dated = drafts.filter { it.start != null }.sortedBy { it.start }
        for (t in dated) {
            val first = t.legs.firstOrNull()
            if (t.startsAway && first != null) add(Flag(FlagKind.STARTS_AWAY, "${t.place} (${d(t.start)} to ${d(t.end)}) starts away from home: its first leg leaves ${Cities.city(first.from) ?: "somewhere unknown"} and nothing shows the way there"))
            val layoverElsewhere = dated.any { o -> o !== t && o.layovers.any { Cities.same(it, t.place) } && kotlin.math.abs(ChronoUnit.DAYS.between(o.start, t.start)) <= 14 }
            val ruled = rules.any { it.kind == RuleKind.LAYOVER && Cities.same(it.place, t.place) }
            if (t.legs.isNotEmpty() && (t.destHours in 1 until 4 || layoverElsewhere || ruled)) add(Flag(FlagKind.LAYOVER_TRIP, "${t.place} (${d(t.start)}) looks like a layover, not a trip"))
            val onlyIris = t.feeds.all { it == "sweep" }
            if (onlyIris && t.start!! >= today) add(Flag(FlagKind.IRIS_ONLY, "Iris says ${t.place} ${d(t.start)} to ${d(t.end)}, but no booking or calendar event shows it"))
            if (sweepAnswered && t.legs.isNotEmpty() && "iris" !in t.sources && t.start!! >= today && ChronoUnit.DAYS.between(today, t.start) <= 180)
                add(Flag(FlagKind.NOT_FROM_IRIS, "${t.place} (${d(t.start)} to ${d(t.end)}) is only on the calendar; Iris's sweep didn't mention it"))
        }
        for ((a, b) in dated.zipWithNext()) {
            if (a.end != null && ChronoUnit.DAYS.between(a.end, b.start) <= 3 && !(a.stops + Stop(a.place)).any { x -> (b.stops + Stop(b.place)).any { y -> Assemble.linked(x.place, y.place, rules) } })
                add(Flag(FlagKind.CLOSE_TRIPS, "${a.place} (${d(a.start)} to ${d(a.end)}) and ${b.place} (${d(b.start)} to ${d(b.end)}) are ${maxOf(0, ChronoUnit.DAYS.between(a.end, b.start))} days apart"))
        }
        disagree.forEach { add(Flag(FlagKind.TIMES_DISAGREE, it)) }
    }
}

/** A conflict with a confirmed trip, shown as one card until he decides. */
data class Conflict(val key: String = "", val tripId: String = "", val text: String = "")

/**
 * Evidence onto the stored trips. A trip keeps its id (and his notes, weather, flags) when the new assembly shares its
 * evidence; dismissed and archived trips stay as they are and are never made again; a stored trip nothing supports any
 * more is dropped (the old layover-only trip). Locked trips take the evidence that falls in their days and keep their shape.
 */
object Rebuild {
    data class View(val ev: List<Ev>, val drafts: List<Draft>, val inside: Map<String, List<Ev>>, val corrections: Map<String, String>, val disagree: List<String>)

    private fun lockDays(t: Trip, zone: ZoneId): Pair<LocalDate, LocalDate>? =
        t.startDate?.let { s -> runCatching { LocalDate.parse(s) to LocalDate.parse(t.endDate ?: s) }.getOrNull() } ?: t.days(zone)

    private fun holds(t: Trip, e: Ev, zone: ZoneId): Boolean {
        val (s, en) = lockDays(t, zone) ?: return false
        val (a, b) = Assemble.days(e, zone) ?: return false
        return a >= s.minusDays(1) && b <= en.plusDays(1)
    }

    /** Trips that keep their shape: the ones he confirmed or edited, and the ones Athena assembled. */
    fun held(t: Trip) = t.open && (t.locked || t.athena)

    fun view(d: ArgoData, zone: ZoneId): View {
        val all = (d.evidence + Evidence.said(d.trips)).filter { it.id !in d.ignored }
        // Once Athena has run, her repairs stand; until then the phone's own guess serves the offline draft.
        val clean = Dedupe.run(all, d.corrections, zone, choose = !d.athenaRepairs)
        val holds = d.trips.filter(::held)
        val seen = d.asked?.takeIf { it.answered }?.ids.orEmpty().toSet()
        val inside = HashMap<String, MutableList<Ev>>()
        val rest = ArrayList<Ev>()
        for (e in clean.ev) holds.firstOrNull { holds(it, e, zone) }?.let { inside.getOrPut(it.id) { ArrayList() } += e }
            // What she already placed (or left out) makes no offline draft; an idea with no dates always stays.
            ?: e.takeIf { x -> x.ids.none { it in seen } || (x.kind == EvKind.SPAN && x.start == null) }?.let { rest.add(it) }
        return View(clean.ev, Assemble.drafts(rest, d.rules, zone), inside, clean.corrections, clean.disagree)
    }

    private fun overlaps(t: Trip, dr: Draft, zone: ZoneId): Boolean {
        val (s, e) = t.days(zone) ?: return false
        val a = dr.start ?: return false
        return a <= e && (dr.end ?: a) >= s
    }

    private fun samePlace(t: Trip, dr: Draft) = (dr.stops.map { it.place } + dr.place).any { p -> Cities.same(p, t.place) || t.stops.any { Cities.same(it.place, p) } }

    private fun score(t: Trip, dr: Draft, zone: ZoneId): Int =
        (if ("said:${t.id}" in dr.ids) 1000 else 0) + 10 * t.seen.count { it in dr.ids } +
            (if (samePlace(t, dr) && overlaps(t, dr, zone)) 5 else 0) +
            (if (samePlace(t, dr) && dr.start == null && t.days(zone) == null && t.month == dr.month?.toString()) 3 else 0)

    /** A dismissed or archived trip already stands for this one: most of its evidence, or the same place on the same days. */
    private fun covers(t: Trip, dr: Draft, zone: ZoneId) = (dr.ids.isNotEmpty() && dr.ids.count { it in t.seen } * 2 >= dr.ids.size) || (samePlace(t, dr) && overlaps(t, dr, zone))

    private fun docs(legs: List<Leg>, stays: List<Stay>) = legs.mapNotNull { it.doc } + stays.mapNotNull { it.doc }

    fun trip(dr: Draft, base: Trip?, id: String, now: Long): Trip {
        val b = base ?: Trip(id, dr.place, createdAt = now, status = TripStatus.IDEA)
        val moved = !Cities.same(b.place, dr.place)
        val dated = dr.start != null
        val t = b.copy(
            place = dr.place, lat = if (moved) null else b.lat, lng = if (moved) null else b.lng,
            zone = Airports.zoneOfCity(dr.place) ?: if (moved) null else b.zone,
            startDate = dr.start?.toString(), endDate = dr.end?.toString(),
            month = if (dated) null else dr.month?.toString() ?: b.month,
            legs = dr.legs, stays = dr.stays, plans = dr.plans, people = (b.people + dr.plans.flatMap { it.people }).distinct(),
            stops = dr.stops, layovers = dr.layovers,
            sources = (b.sources.filter { it in Evidence.WORDS } + dr.sources).distinct(),
            seen = (b.seen + dr.ids).distinct().takeLast(300), docs = (b.docs + docs(dr.legs, dr.stays)).distinct(),
            status = if (b.status == TripStatus.IDEA && dated) TripStatus.PLANNED else b.status, draft = true,
        )
        return if (base != null && t == base) base else t.copy(updatedAt = now)
    }

    /** A held trip with the evidence inside its days: its place, days and stops stay as he (or Athena) set them. */
    private fun locked(t: Trip, inside: List<Ev>, rules: List<TripRule>, now: Long, zone: ZoneId): Trip {
        val legs = inside.filter { it.kind == EvKind.LEG }
        val returned = legs.any { Assemble.isHome(it.leg?.to?.let(Cities::city)) }
        val dr = Assemble.shape(legs, inside.filter { it.kind == EvKind.STAY || it.kind == EvKind.SPAN }, inside.filter { it.kind == EvKind.PLAN }, emptyList(), returned, false, rules, zone, dest = t.place)
        val stopNames = t.stops.map { it.place } + t.place
        val n = t.copy(
            legs = dr.legs, stays = dr.stays, plans = dr.plans, people = (t.people + dr.plans.flatMap { it.people }).distinct(),
            layovers = (t.layovers + dr.layovers).distinct().filter { l -> stopNames.none { Cities.same(it, l) } },
            sources = (t.sources + dr.sources).distinct(), seen = (t.seen + dr.ids).distinct().takeLast(300), docs = (t.docs + docs(dr.legs, dr.stays)).distinct(),
        )
        return if (n == t) t else n.copy(updatedAt = now)
    }

    private fun conflicts(t: Trip, inside: List<Ev>, drafts: List<Draft>, zone: ZoneId): List<Conflict> {
        val (s, e) = lockDays(t, zone) ?: return emptyList()
        val known = (t.stops.map { it.place } + t.layovers + t.place + Cities.HOME)
        val out = ArrayList<Conflict>()
        for (dr in drafts) {
            val a = dr.start ?: continue
            if (a <= e.plusDays(1) && (dr.end ?: a) >= s.minusDays(1))
                out += Conflict("near:${t.id}:${dr.ids.minOrNull()}", t.id, "${dr.place} (${Clock.range(a, dr.end ?: a)}) runs into your confirmed ${t.place} trip. One trip or two?")
        }
        for (l in inside.mapNotNull { it.leg }) {
            val to = Cities.city(l.to) ?: continue
            if (known.none { Cities.same(it, to) }) out += Conflict("dest:${t.id}:${to.lowercase()}", t.id, "A booking to $to falls inside your confirmed ${t.place} trip.")
        }
        val legs = inside.mapNotNull { it.leg }.sortedBy { Assemble.at(it, zone) }
        val home = legs.indexOfFirst { Assemble.isHome(Cities.city(it.to)) && Trip.day(Assemble.landed(it, zone), zone) < e.minusDays(1) }
        if (home >= 0 && legs.drop(home + 1).any { Assemble.isHome(Cities.city(it.from)) })
            out += Conflict("split:${t.id}", t.id, "Your bookings have you home in the middle of the ${t.place} trip. Two trips?")
        return out
    }

    fun run(d: ArgoData, now: Long, zone: ZoneId): ArgoData {
        val v = view(d, zone)
        val pool = d.trips.filter { !held(it) }
        val claimed = HashSet<String>()
        val made = ArrayList<Trip>()
        for (dr in v.drafts) {
            val scored = pool.filter { it.id !in claimed }.map { it to score(it, dr, zone) }.filter { it.second > 0 }
            val open = scored.filter { it.first.open }.maxByOrNull { it.second }?.first
            // Dismissed stays dismissed, archived stays archived: not made again from the same bookings.
            if (open == null && scored.any { !it.first.open && covers(it.first, dr, zone) }) continue
            val id = open?.id ?: generateSequence(Trips.newId(now, dr.place)) { it + "x" }.first { n -> d.trips.none { it.id == n } && made.none { it.id == n } }
            open?.let { claimed += it.id }
            made += trip(dr, open, id, now)
        }
        val locks = d.trips.filter(::held).map { locked(it, v.inside[it.id].orEmpty(), d.rules, now, zone) }
        // Only his own trips raise a card; Athena's next run reshapes hers.
        val conflicts = d.trips.filter { it.locked && it.open }.flatMap { conflicts(it, v.inside[it.id].orEmpty(), v.drafts, zone) }.filter { it.key !in d.kept }.distinctBy { it.key }
        val byId = (made + locks).associateBy { it.id }
        val kept = d.trips.mapNotNull { t -> byId[t.id] ?: t.takeIf { !it.open } }
        return d.copy(trips = kept + made.filter { m -> d.trips.none { it.id == m.id } }, corrections = v.corrections, conflicts = conflicts)
    }
}
