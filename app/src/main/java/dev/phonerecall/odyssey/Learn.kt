package dev.phonerecall.odyssey

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/**
 * Odyssey learning Home and the office on its own, from the stays it already notes (30 minutes or more,
 * clustered within 150 m), the open stay included so a morning at a new office is seen the same day.
 * Saved on its own with Undo when it is clear; asked once only when it is ambiguous.
 *
 * - The office: the first office-day morning (Mon–Wed by default, from Availability; arriving 6:00–11:30,
 *   staying 3 h or still there after 30 min) right after a commute-length drive (10–75 min) is saved at once.
 *   So are three such mornings without a drive. Ambiguous, so asked: two mornings without a drive, a second
 *   office while one is known, or long weekday or weekend daytime stays (3 h, twice) somewhere new.
 * - Home: the first overnight stay (2 h or more between midnight and 5 AM) that began with an evening drive
 *   back (arriving 4 PM or later) is saved at once, as are three nights. Asked: one night without that drive,
 *   or a second overnight spot while Home is known.
 * Each spot is decided once; No sticks. Pure: the caller stores the result and sets up geofences.
 */
object PlaceLearner {
    const val CLUSTER_M = 150.0
    const val MIN_STAY_MS = 30 * 60_000L
    const val AUTO = 3

    data class Stay(val lat: Double, val lng: Double, val street: String?, val start: Long, val end: Long, val open: Boolean, val placeId: String?)
    data class Cluster(val lat: Double, val lng: Double, val stays: List<Stay>) {
        val street: String? get() = stays.lastOrNull { it.street != null }?.street
    }

    /** Stays of 30 minutes or more: closed visits, and the open one once it has lasted that long. */
    fun stays(d: OdysseyData, now: Long): List<Stay> = buildList {
        for (v in d.visits) {
            if (v.end - v.start < MIN_STAY_MS) continue
            val p = d.place(v.placeId) ?: continue
            add(Stay(p.lat, p.lng, p.street, v.start, v.end, false, p.id))
        }
        d.stay?.takeIf { now - it.start >= MIN_STAY_MS }?.let { add(Stay(it.lat, it.lng, it.street, it.start, now, true, null)) }
    }.sortedBy { it.start }

    /** Greedy clustering: each stay joins the first cluster whose centre is within 150 m (the centre is the running mean). */
    fun cluster(stays: List<Stay>): List<Cluster> {
        val out = ArrayList<Cluster>()
        for (s in stays) {
            val i = out.indexOfFirst { Geo.meters(it.lat, it.lng, s.lat, s.lng) < CLUSTER_M }
            if (i < 0) { out += Cluster(s.lat, s.lng, listOf(s)); continue }
            val c = out[i]; val n = c.stays.size
            out[i] = Cluster((c.lat * n + s.lat) / (n + 1), (c.lng * n + s.lng) / (n + 1), c.stays + s)
        }
        return out
    }

    /** Distinct nights with 2 h or more of the stay between midnight and 5 AM. */
    fun nights(c: Cluster, zone: ZoneId): Int = c.stays.flatMap { s ->
        var day = Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(s.end).atZone(zone).toLocalDate()
        buildList {
            while (!day.isAfter(last)) {
                val a = day.atStartOfDay(zone).toInstant().toEpochMilli(); val b = day.atTime(5, 0).atZone(zone).toInstant().toEpochMilli()
                if (minOf(b, s.end) - maxOf(a, s.start) >= 2 * 3_600_000L) add(day)
                day = day.plusDays(1)
            }
        }
    }.toSet().size

    /** An office morning: an office weekday, arriving 6:00–11:30, staying 3 h (or still there after 30 min). */
    fun officeMorning(s: Stay, zone: ZoneId, officeDays: Set<DayOfWeek>): Boolean {
        val at = Instant.ofEpochMilli(s.start).atZone(zone)
        val m = at.hour * 60 + at.minute
        return at.dayOfWeek in officeDays && m in 6 * 60..11 * 60 + 30 && (s.open || s.end - s.start >= 3 * 3_600_000L)
    }

    /** A drive of `minutes` ended within 15 minutes before the stay (a drive report, or the departure from the last place). */
    fun afterDrive(d: OdysseyData, s: Stay, minutes: IntRange = 10..75): Boolean {
        val drive = d.drives.any { it.end in (s.start - 15 * 60_000L)..(s.start + 5 * 60_000L) && it.minutes in minutes }
        val dep = d.departure?.let { s.open && (s.start - it.at) in (minutes.first * 60_000L)..(minutes.last * 60_000L) } == true
        return drive || dep
    }

    /** A long daytime stay that isn't an office morning: 3 h or more between 9 and 5 on a weekend or a non-office weekday. */
    fun daytime(s: Stay, zone: ZoneId, officeDays: Set<DayOfWeek>): Boolean {
        val at = Instant.ofEpochMilli(s.start).atZone(zone)
        return at.dayOfWeek !in officeDays && at.hour in 8..12 && s.end - s.start >= 3 * 3_600_000L
    }

    data class Review(val data: OdysseyData, val applied: List<PlaceGuess>, val asked: List<PlaceGuess>)

    fun review(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault(), officeDays: Set<DayOfWeek> = dev.phonerecall.data.AvailabilityModel.OFFICE_DAYS): Review {
        var data = d
        val applied = ArrayList<PlaceGuess>(); val asked = ArrayList<PlaceGuess>()
        fun known(kind: Kind) = data.places.any { it.kind == kind } || data.guesses.any { it.kind == kind && it.open }
        fun guessed(c: Cluster) = data.guesses.any { Geo.meters(it.lat, it.lng, c.lat, c.lng) < CLUSTER_M }
        fun placeIsKnown(c: Cluster) = data.places.any { Geo.meters(it.lat, it.lng, c.lat, c.lng) < CLUSTER_M && (it.source == NameSource.HIM || it.learned || it.kind == Kind.HOME || it.kind == Kind.WORK) }
        fun decide(kind: Kind, c: Cluster, n: Int, auto: Boolean) {
            val g = PlaceGuess("g${data.nextId}", kind, c.lat, c.lng, c.street, n, if (auto) "auto" else "ask", now)
            data = data.copy(nextId = data.nextId + 1)
            if (auto) { data = apply(data, g, by = null); applied += data.guesses.last() } else { data = data.copy(guesses = data.guesses + g); asked += g }
        }
        val clusters = cluster(stays(d, now))
        // Home: nights, the clearest first.
        for (c in clusters.sortedByDescending { nights(it, zone) }) {
            val n = nights(c, zone)
            if (n == 0 || guessed(c) || placeIsKnown(c)) continue
            val second = known(Kind.HOME)
            val evening = c.stays.any { s -> Instant.ofEpochMilli(s.start).atZone(zone).hour >= 16 && afterDrive(d, s, 5..180) }
            decide(Kind.HOME, c, n, auto = !second && (n >= AUTO || evening))
        }
        // The office: office mornings, the most first; long daytime stays elsewhere are only asked about.
        for (c in clusters.sortedByDescending { cl -> cl.stays.count { officeMorning(it, zone, officeDays) } }) {
            if (guessed(c) || placeIsKnown(c)) continue
            val mornings = c.stays.filter { officeMorning(it, zone, officeDays) }
            val days = mornings.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }.toSet().size
            val second = known(Kind.WORK)
            when {
                days > 0 && !second && (days >= AUTO || mornings.any { afterDrive(d, it) }) -> decide(Kind.WORK, c, days, auto = true)
                days >= 2 -> decide(Kind.WORK, c, days, auto = false)
                !second && c.stays.count { daytime(it, zone, officeDays) } >= 2 -> decide(Kind.WORK, c, c.stays.count { daytime(it, zone, officeDays) }, auto = false)
            }
        }
        return Review(data, applied, asked)
    }

    /**
     * Makes the spot a place of `kind`: the nearest known place within 150 m, or a new one. `by` null is Odyssey
     * on its own (learned, Undo offered); HIM is his answer. `name` is his own name for it.
     */
    fun apply(d: OdysseyData, g: PlaceGuess, by: NameSource?, kind: Kind = g.kind, name: String? = null): OdysseyData {
        var next = d.nextId
        val near = d.places.filter { Geo.meters(it.lat, it.lng, g.lat, g.lng) < CLUSTER_M }.minByOrNull { Geo.meters(it.lat, it.lng, g.lat, g.lng) }
        val label = name ?: when (kind) { Kind.HOME -> "Home"; Kind.WORK -> "Office"; else -> kind.label }
        val base = near ?: KnownPlace("k" + next++, label, NameSource.NONE, Kind.OTHER, g.lat, g.lng, g.street)
        val place = base.copy(
            name = if (base.source == NameSource.HIM && name == null) base.name else label,
            source = if (by == NameSource.HIM) NameSource.HIM else base.source.takeIf { it != NameSource.NONE } ?: NameSource.HINT,
            kind = kind, learned = by == null && (kind == Kind.HOME || kind == Kind.WORK),
        )
        // Only one Home and one office: a stale role elsewhere goes back to what its name says.
        val others = d.places.filter { it.id != place.id }.map { if ((kind == Kind.HOME || kind == Kind.WORK) && it.kind == kind) it.copy(kind = Naming.kind(it.name), learned = false) else it }
        val done = g.copy(state = if (by == null) "auto" else "yes", placeId = place.id, prevKind = near?.kind, prevName = near?.name, prevSource = near?.source)
        return d.copy(places = others + place, nextId = next, guesses = d.guesses.filter { it.id != g.id } + done)
    }

    /** His answer to a question. Yes applies it as his; No and Ignore are kept so it isn't asked again. */
    fun answer(d: OdysseyData, id: String, yes: Boolean): OdysseyData {
        val g = d.guesses.firstOrNull { it.id == id } ?: return d
        return when {
            g.state == "auto" && yes -> d.copy(guesses = d.guesses.map { if (it.id == id) it.copy(state = "yes") else it })
            g.state == "auto" -> undo(d, id)
            yes && g.kind != Kind.OTHER -> apply(d, g, NameSource.HIM)
            else -> d.copy(guesses = d.guesses.map { if (it.id == id) it.copy(state = if (yes) "yes" else "no") else it })
        }
    }

    /** "Something else": his own kind and name for the spot. */
    fun relabel(d: OdysseyData, id: String, kind: Kind, name: String?): OdysseyData {
        val g = d.guesses.firstOrNull { it.id == id } ?: return d
        return apply(d, g, NameSource.HIM, kind, name?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun ignore(d: OdysseyData, id: String): OdysseyData = d.copy(guesses = d.guesses.map { if (it.id == id) it.copy(state = "ignored") else it })

    /** Undo an automatic one: the place goes back to what it was (or away, if Odyssey made it), and it isn't proposed again. */
    fun undo(d: OdysseyData, id: String): OdysseyData {
        val g = d.guesses.firstOrNull { it.id == id } ?: return d
        val places = d.places.mapNotNull { p ->
            if (p.id != g.placeId) p
            else if (g.prevKind == null) null
            else p.copy(kind = g.prevKind, name = g.prevName ?: p.name, source = g.prevSource ?: p.source, learned = false)
        }
        return d.copy(places = places, guesses = d.guesses.map { if (it.id == id) it.copy(state = "no") else it })
    }

    /** The question in words. */
    fun text(g: PlaceGuess): String {
        val at = g.street?.let { " ($it)" } ?: ""
        return when {
            g.state == "auto" -> "Saved ${g.street ?: "this spot"} as ${if (g.kind == Kind.HOME) "home" else "your office"}"
            g.kind == Kind.HOME -> "Is this home?$at"
            g.kind == Kind.WORK -> "Is this your office?$at"
            else -> "What is this place?$at You stayed ${g.visits} times."
        }
    }
}
