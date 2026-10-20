package dev.phonerecall.odyssey

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** What Activity Recognition said. Play services sends transitions only; nothing runs in between. */
enum class Act { VEHICLE_ENTER, VEHICLE_EXIT, STILL_ENTER, WALKING_ENTER }

/**
 * The visit detector, as plain functions over [OdysseyData]. A stay opens with one fix when a drive ends
 * (or he goes still far from the last stay) and closes when the next drive starts. Stays of 5 minutes or
 * more become visits, clustered into places; the drive between two visits becomes a trip. No continuous GPS.
 */
object Visits {
    /** Every stop counts: two minutes still after a drive is a visit (a gas pump, a car wash, a pickup). */
    const val MIN_STAY_MS = 2 * 60_000L
    /** A fix this close to a known place is that place. */
    const val SAME_PLACE_M = 120.0
    /** Going still this far from the open stay means he left it on foot. */
    const val MOVED_M = 250.0
    const val KEEP_DAYS = 180L

    data class Outcome(val data: OdysseyData, val needFix: Boolean = false, val visit: Visit? = null)

    fun tracking(d: OdysseyData) = d.settings.on && !d.settings.paused

    /** The named place this fix is at, if any (within [SAME_PLACE_M]). */
    fun known(d: OdysseyData, f: Fix): KnownPlace? =
        d.places.filter { it.name.isNotBlank() }.map { it to Geo.meters(it.lat, it.lng, f.lat, f.lng) }.filter { it.second < SAME_PLACE_M }.minByOrNull { it.second }?.first

    /** One transition. `needFix` asks the caller for one fix, then [fix]. */
    fun activity(d: OdysseyData, act: Act, at: Long): Outcome {
        if (!tracking(d)) return Outcome(d)
        return when (act) {
            Act.VEHICLE_ENTER -> close(d.copy(driving = true), at)
            Act.VEHICLE_EXIT -> Outcome(d.copy(driving = false), needFix = true)
            // Still with no stay open (a stop after walking somewhere), or still after a walk: check where he is.
            Act.STILL_ENTER -> Outcome(d, needFix = !d.driving && (d.stay == null || d.walkStart > d.stay.start))
            Act.WALKING_ENTER -> Outcome(if (d.stay != null) d.copy(walkStart = at) else d)
        }
    }

    /** The fix asked for by [activity]. Opens a stay, or ends one he walked away from and opens the next. */
    fun fix(d: OdysseyData, f: Fix, poi: String? = null, street: String? = null, wifi: String? = null, stop: PoiHit? = null): Outcome {
        val poiName = Poi.label(stop, street); val poiKind = stop?.kind?.takeIf { poiName != null }
        if (!tracking(d) || d.driving) return Outcome(d)
        if (d.zones.any { Geo.meters(it.lat, it.lng, f.lat, f.lng) < it.radius }) return Outcome(d.copy(stay = null))
        val open = d.stay
        if (open != null) {
            if (Geo.meters(open.lat, open.lng, f.lat, f.lng) < MOVED_M) return Outcome(d.copy(walkStart = 0))
            val closed = close(d, if (d.walkStart > open.start) d.walkStart else f.at)
            return Outcome(closed.data.copy(stay = OpenStay(f.at, f.lat, f.lng, f.accuracy, poi = poi, street = street, wifi = wifi, poiName = poiName, poiKind = poiKind)), visit = closed.visit)
        }
        return Outcome(d.copy(stay = OpenStay(f.at, f.lat, f.lng, f.accuracy, poi = poi, street = street, wifi = wifi, poiName = poiName, poiKind = poiKind), walkStart = 0))
    }

    /** A payment, receipt or check-in notification while he is at a stay. */
    fun hint(d: OdysseyData, text: String): OdysseyData {
        val s = d.stay ?: return d
        if (d.driving) return d
        val merchant = Naming.merchant(text)
        val amount = Naming.amount(text)
        if (merchant == null && amount == null) return d
        return d.copy(stay = s.copy(hints = (s.hints + listOfNotNull(merchant)).takeLast(6), spent = amount?.let { (s.spent ?: 0.0) + it } ?: s.spent))
    }

    /** Ends the open stay at [at]: a visit if it lasted 5 minutes, a trip from the last departure. */
    fun close(d: OdysseyData, at: Long): Outcome {
        val s = d.stay ?: return Outcome(d)
        if (at - s.start < MIN_STAY_MS) return Outcome(d.copy(stay = null, walkStart = 0))
        var next = d.nextId
        val (places, place) = assign(d.places, s) { "k" + next++ }
        val visit = Visit("v" + next++, place.id, s.start, at, s.spent)
        val trip = d.departure?.takeIf { it.at < s.start && s.start - it.at < 4 * 3_600_000L && it.placeId != place.id }?.let {
            Trip("t" + next++, it.placeId, place.id, it.at, s.start, d.drives.lastOrNull { r -> r.start in (it.at - 60_000L)..s.start }?.id)
        }
        val roled = Roles.apply(places, d.visits + visit)
        return Outcome(
            d.copy(
                places = roled, visits = (d.visits + visit).takeLastDays(at), trips = (d.trips + listOfNotNull(trip)).filter { at - it.end < KEEP_DAYS * DAY },
                stay = null, walkStart = 0, departure = Departure(place.id, at), nextId = next,
            ),
            visit = visit,
        )
    }

    private fun List<Visit>.takeLastDays(now: Long) = filter { now - it.end < KEEP_DAYS * DAY }

    /** The place this stay belongs to: the nearest known one within reach, or a new one. Names it if it can. */
    fun assign(places: List<KnownPlace>, s: OpenStay, newId: () -> String): Pair<List<KnownPlace>, KnownPlace> {
        val reach = max(SAME_PLACE_M, s.accuracy.toDouble())
        val near = places.minByOrNull { Geo.meters(it.lat, it.lng, s.lat, s.lng) }?.takeIf { Geo.meters(it.lat, it.lng, s.lat, s.lng) < reach }
        val named = Naming.pick(near, s)
        // TomTom's category when the name is TomTom's; otherwise what the name says.
        val kindOf = if (named.second == NameSource.POI) s.poiKind ?: near?.kind?.takeIf { it != Kind.OTHER } ?: Naming.kind(named.first) else Naming.kind(named.first)
        val place = if (near != null) {
            // Running mean of the centre, so a few noisy fixes settle onto the store.
            val n = near.visits.toDouble()
            near.copy(
                lat = (near.lat * n + s.lat) / (n + 1), lng = (near.lng * n + s.lng) / (n + 1), visits = near.visits + 1,
                name = named.first, source = named.second, kind = if (near.kind == Kind.HOME || near.kind == Kind.WORK) near.kind else kindOf,
                street = near.street ?: s.street, wifi = s.wifi ?: near.wifi,
            )
        } else KnownPlace(newId(), named.first, named.second, kindOf, s.lat, s.lng, s.street, s.wifi, 1)
        return (places.filter { it.id != place.id } + place) to place
    }

    private const val DAY = 86_400_000L
}

/** Naming from what the phone already has: payment notifications, the Wi-Fi name, the Geocoder. */
object Naming {
    /** Card-statement spellings to the store's everyday name. */
    private val CANON = listOf(
        "costco" to "Costco", "wal-mart" to "Walmart", "walmart" to "Walmart", "wegmans" to "Wegmans", "tops mkt" to "Tops", "tops markets" to "Tops",
        "aldi" to "Aldi", "whole foods" to "Whole Foods", "wholefds" to "Whole Foods", "trader joe" to "Trader Joe's", "target" to "Target",
        "sam's club" to "Sam's Club", "sams club" to "Sam's Club", "patel brothers" to "Patel Brothers", "delta sonic" to "Delta Sonic",
        "mister car wash" to "Mister Car Wash", "planet fitness" to "Planet Fitness", "la fitness" to "LA Fitness", "ymca" to "YMCA",
        "starbucks" to "Starbucks", "tim hortons" to "Tim Hortons", "dunkin" to "Dunkin'", "shell oil" to "Shell", "shell" to "Shell",
        "sunoco" to "Sunoco", "speedway" to "Speedway", "kwik fill" to "Kwik Fill", "mobil" to "Mobil", "exxon" to "Exxon", "citgo" to "Citgo",
        "home depot" to "Home Depot", "lowe's" to "Lowe's", "lowes" to "Lowe's", "cvs" to "CVS", "walgreens" to "Walgreens",
    )

    private val KINDS = listOf(
        Kind.CAR_WASH to listOf("car wash", "carwash", "delta sonic", "auto spa"),
        Kind.TEMPLE to listOf("temple", "mandir", "gurdwara", "church", "mosque", "masjid", "cultural center", "venkateswara", "ganesha"),
        Kind.GYM to listOf("gym", "fitness", "ymca", "crunch", "orangetheory", "crossfit"),
        Kind.FUEL to listOf("shell", "sunoco", "speedway", "kwik fill", "mobil", "exxon", "citgo", " bp", "fuel", "gas station"),
        Kind.GROCERY to listOf("costco", "walmart", "wegmans", "tops", "aldi", "whole foods", "trader joe", "sam's club", "patel brothers", "grocery", "market", "halal", "bazaar", "supermarket", "foods"),
        Kind.CAFE to listOf("starbucks", "tim hortons", "dunkin", "cafe", "coffee"),
        Kind.RESTAURANT to listOf("restaurant", "grill", "pizza", "kitchen", "diner", "biryani", "bistro", "tavern", "wings"),
        Kind.SHOP to listOf("target", "home depot", "lowe's", "mall", "store", "cvs", "walgreens", "best buy"),
    )

    fun kind(name: String): Kind {
        val n = " " + name.lowercase()
        return KINDS.firstOrNull { (_, words) -> words.any { n.contains(it) } }?.first ?: Kind.OTHER
    }

    private val AT = Regex("""(?i)\b(?:at|@|from|to)\s+([A-Za-z][A-Za-z0-9&'.\- ]{1,40}?)(?:\s+(?:on|for|was|has|using|with|ending|card)\b|[#*]|\s{2}|[.,;!]|\s+\d|$)""")
    private val SPEND = Regex("""(?i)(?:purchase|spent|charged|payment|paid|transaction|receipt|order|visit|thanks for)""")
    private val MONEY = Regex("""\$\s?(\d{1,5}(?:,\d{3})*(?:\.\d{2})?)""")

    /** The store in "You spent $82.14 at COSTCO WHSE #1234" or "Your receipt from Starbucks". Null if it isn't a payment line. */
    fun merchant(text: String): String? {
        if (!SPEND.containsMatchIn(text)) return null
        val raw = AT.findAll(text).map { it.groupValues[1].trim() }.firstOrNull { it.length >= 3 && !it.equals("your", true) && !it.lowercase().startsWith("your ") } ?: return null
        return clean(raw)
    }

    fun amount(text: String): Double? = MONEY.find(text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { SPEND.containsMatchIn(text) }

    /** "COSTCO WHSE #1234" to "Costco"; otherwise title case without store numbers. */
    fun clean(raw: String): String {
        val l = raw.lowercase()
        CANON.firstOrNull { l.contains(it.first) }?.let { return it.second }
        return raw.replace(Regex("""[#*]?\d{2,}"""), "").replace(Regex("""\s+"""), " ").trim()
            .split(' ').joinToString(" ") { w -> if (w.length == 2 && w.all { it.isUpperCase() }) w else w.lowercase().replaceFirstChar { it.uppercase() } }
    }

    /** A Wi-Fi name that says where he is ("Costco-Guest", "Starbucks WiFi"); home routers say nothing. */
    fun fromWifi(ssid: String?): String? {
        val s = ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: return null
        val l = s.lowercase()
        CANON.firstOrNull { l.contains(it.first.replace(" ", "")) || l.contains(it.first) }?.let { return it.second }
        return null
    }

    /**
     * The name for a place after a stay, best source first: his own name, a Maps link, the payment hints of
     * this stay, the Wi-Fi, the Geocoder's place name, else "Near <street>".
     */
    fun pick(known: KnownPlace?, s: OpenStay): Pair<String, NameSource> {
        if (known != null && (known.source == NameSource.HIM || known.source == NameSource.MAPS_LINK)) return known.name to known.source
        val hint = s.hints.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        if (hint != null) return hint to NameSource.HINT
        if (known != null && known.source == NameSource.HINT) return known.name to known.source
        s.poiName?.let { return it to NameSource.POI }
        if (known != null && known.source == NameSource.POI) return known.name to known.source
        fromWifi(s.wifi)?.let { return it to NameSource.WIFI }
        if (known != null && known.source == NameSource.WIFI) return known.name to known.source
        s.poi?.takeIf { poi -> poi.any { it.isLetter() } && !poi.first().isDigit() && poi != s.street }?.let { return clean(it) to NameSource.GEOCODER }
        if (known != null && known.source == NameSource.GEOCODER) return known.name to known.source
        return (known?.name?.takeIf { it.isNotBlank() } ?: s.street?.let { "Near $it" } ?: "A place") to NameSource.NONE
    }
}

/** Home and the office, from when he is there: nights at home, weekday working hours at the office. */
object Roles {
    fun apply(places: List<KnownPlace>, visits: List<Visit>, zone: ZoneId = ZoneId.systemDefault()): List<KnownPlace> {
        if (places.any { (it.source == NameSource.HIM || it.learned) && (it.kind == Kind.HOME || it.kind == Kind.WORK) }) return places
        val night = HashMap<String, Long>()
        val workday = HashMap<String, MutableSet<Long>>()
        for (v in visits) {
            night[v.placeId] = (night[v.placeId] ?: 0) + overlap(v, zone, 2, 5)
            val start = Instant.ofEpochMilli(v.start).atZone(zone)
            if (start.dayOfWeek.value <= 5 && overlap(v, zone, 10, 15) >= 2 * 3_600_000L) workday.getOrPut(v.placeId) { HashSet() } += start.toLocalDate().toEpochDay()
        }
        val home = night.filter { it.value >= 2 * 3 * 3_600_000L }.maxByOrNull { it.value }?.key
        val work = workday.filter { it.key != home && it.value.size >= 3 }.maxByOrNull { it.value.size }?.key
        return places.map { p ->
            when (p.id) {
                home -> p.copy(kind = Kind.HOME, name = if (p.source == NameSource.HIM) p.name else "Home")
                work -> p.copy(kind = Kind.WORK, name = if (p.source == NameSource.HIM) p.name else "Office")
                else -> if (p.kind == Kind.HOME || p.kind == Kind.WORK) p.copy(kind = Naming.kind(p.name)) else p
            }
        }
    }

    /** Milliseconds of the visit inside [fromHour, toHour) on any day it covers. */
    fun overlap(v: Visit, zone: ZoneId, fromHour: Int, toHour: Int): Long {
        var total = 0L
        var day = Instant.ofEpochMilli(v.start).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(v.end).atZone(zone).toLocalDate()
        while (!day.isAfter(last)) {
            val a = day.atTime(fromHour, 0).atZone(zone).toInstant().toEpochMilli()
            val b = day.atTime(toHour, 0).atZone(zone).toInstant().toEpochMilli()
            total += (minOf(b, v.end) - maxOf(a, v.start)).coerceAtLeast(0)
            day = day.plusDays(1)
        }
        return total
    }
}

/** A line in Logs: "Car wash: Sat Oct 12, 11:20 (14 min)". */
data class LogLine(val visit: Visit, val place: KnownPlace, val kind: Kind)

/** A habit: "Temple every Sunday ~10 AM". */
data class Pattern(val placeId: String, val text: String)

object Patterns {
    fun logs(d: OdysseyData): List<LogLine> = d.visits.mapNotNull { v ->
        val p = d.place(v.placeId) ?: return@mapNotNull null
        if (!p.kind.autoLog) null else LogLine(v, p, p.kind)
    }.sortedByDescending { it.visit.start }

    /** Weekday habits over the last eight weeks: a place visited mostly on one weekday at about the same time. */
    fun habits(d: OdysseyData, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pattern> {
        val recent = d.visits.filter { now - it.start < 56 * 86_400_000L }
        return recent.groupBy { it.placeId }.mapNotNull { (id, vs) ->
            val p = d.place(id) ?: return@mapNotNull null
            if (p.kind == Kind.HOME || p.kind == Kind.WORK || vs.size < 3) return@mapNotNull null
            val days = vs.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).dayOfWeek }
            val (dow, on) = days.maxByOrNull { it.value.size } ?: return@mapNotNull null
            val name = if (p.kind.autoLog && p.kind != Kind.GROCERY) p.kind.label else p.name
            if (on.size >= 3 && on.size * 10 >= vs.size * 6) {
                val mins = on.map { Instant.ofEpochMilli(it.start).atZone(zone).toLocalTime().toSecondOfDay() / 60 }.sorted()
                Pattern(id, "$name every ${dow.getDisplayName(TextStyle.FULL, Locale.US)} ~${Clock.hm(roundTo(mins[mins.size / 2], 15))}")
            } else Pattern(id, "$name about ${perWeek(vs.size, recent, now)} a week")
        }
    }

    private fun perWeek(n: Int, all: List<Visit>, now: Long): String {
        val weeks = ((now - (all.minOfOrNull { it.start } ?: now)) / (7 * 86_400_000.0)).coerceAtLeast(1.0)
        val r = n / weeks
        return if (r >= 1.5) "${r.roundToInt()} times" else "once"
    }

    /** "Home by 6:10 on average": weekday arrivals at home after 3 PM. */
    fun homeBy(d: OdysseyData, zone: ZoneId = ZoneId.systemDefault()): String? {
        val home = d.home() ?: return null
        val mins = d.visits.filter { it.placeId == home.id }.map { Instant.ofEpochMilli(it.start).atZone(zone) }
            .filter { it.dayOfWeek.value <= 5 && it.hour >= 15 }.map { it.toLocalTime().toSecondOfDay() / 60 }
        if (mins.size < 3) return null
        return "Home by ${Clock.hm(roundTo(mins.average().roundToInt(), 5))} on average"
    }

    /** Hours per place over the last 30 days, most first. */
    fun timeSpent(d: OdysseyData, now: Long): List<Pair<KnownPlace, Long>> =
        d.visits.filter { now - it.end < 30 * 86_400_000L }.groupBy { it.placeId }
            .mapNotNull { (id, vs) -> d.place(id)?.let { it to vs.sumOf { v -> v.end - v.start } } }.sortedByDescending { it.second }

    fun roundTo(min: Int, step: Int) = ((min + step / 2) / step) * step
}

object Clock {
    fun hm(minutesOfDay: Int): String {
        val t = LocalTime.of((minutesOfDay / 60) % 24, minutesOfDay % 60)
        val h = if (t.hour % 12 == 0) 12 else t.hour % 12
        return if (t.minute == 0) "$h ${if (t.hour < 12) "AM" else "PM"}" else "$h:%02d".format(t.minute)
    }

    fun days(ds: Collection<DayOfWeek>): String {
        val s = ds.sorted()
        if (s.size >= 3 && s.zipWithNext().all { (a, b) -> b.value - a.value == 1 }) return short(s.first()) + "–" + short(s.last())
        return s.joinToString(", ") { short(it) }
    }

    fun short(d: DayOfWeek) = d.getDisplayName(TextStyle.SHORT, Locale.US)
}

/**
 * Store proposals: after the 2nd visit to a named store, one card,
 * "Looks like Costco on Maple Ave. Show Groceries when you arrive?". Nothing is set up without his tap.
 */
object Offers {
    data class Offer(val place: KnownPlace, val text: String, val lists: List<Long>, val listNames: List<String>)

    /** `sections` are his lists (id to name). */
    fun due(d: OdysseyData, sections: List<Pair<Long, String>>): List<Offer> = d.places.filter {
        (it.visits >= 2 && it.offer == OfferState.NONE) || it.offer == OfferState.OFFERED
    }.filter { it.kind != Kind.HOME && it.kind != Kind.WORK && it.source != NameSource.NONE && it.geofence == null }
        .map { p ->
            val want = p.kind.list
            val lists = sections.filter { (_, n) -> want != null && (n.equals(want, true) || n.contains(want, true) || (want == "Groceries" && n.contains("grocer", true))) }
            val at = p.name + (p.street?.let { " on $it" } ?: "")
            val text = if (lists.isNotEmpty()) "Looks like $at. Show ${lists.joinToString(" and ") { it.second }} when you arrive?"
            else "Looks like $at. Keep it as a place?"
            Offer(p, text, lists.map { it.first }, lists.map { it.second })
        }

    fun mark(d: OdysseyData, placeId: String, state: OfferState, geofence: String? = null): OdysseyData =
        d.copy(places = d.places.map { if (it.id == placeId) it.copy(offer = state, geofence = geofence ?: it.geofence) else it })
}

/** Forgetting (his action): a visit, a day, or a place and everything at it. */
object Forget {
    fun visit(d: OdysseyData, id: String): OdysseyData {
        val v = d.visits.firstOrNull { it.id == id } ?: return d
        return d.copy(visits = d.visits - v, trips = d.trips.filter { it.end != v.start && it.start != v.end },
            places = d.places.map { if (it.id == v.placeId) it.copy(visits = (it.visits - 1).coerceAtLeast(0)) else it })
    }

    fun day(d: OdysseyData, from: Long, to: Long): OdysseyData = d.copy(
        visits = d.visits.filter { it.end <= from || it.start >= to },
        trips = d.trips.filter { it.end <= from || it.start >= to },
        drives = d.drives.filter { it.end <= from || it.start >= to },
    )

    /** Removes the place, its visits and trips, and keeps a zone so stays there are not noted again. */
    fun place(d: OdysseyData, id: String): OdysseyData {
        val p = d.place(id) ?: return d
        return d.copy(
            places = d.places - p, visits = d.visits.filter { it.placeId != id }, trips = d.trips.filter { it.from != id && it.to != id },
            zones = d.zones + Zone(p.lat, p.lng, 200.0), departure = d.departure?.takeIf { it.placeId != id },
            shapes = d.shapes.filter { s -> s.key.split('>').none { it == id } },
        )
    }
}

/** A Google Maps place shared to Zeus, read from the shared text on the phone (no network). */
object MapsLink {
    data class Shared(val name: String?, val lat: Double?, val lng: Double?, val address: String?)

    private val PIN = Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)""")
    private val AT = Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val Q = Regex("""[?&](?:q|ll|query|destination|center)=(-?\d{1,2}\.\d+)(?:,|%2C)\s*(-?\d{1,3}\.\d+)""")
    private val GEO = Regex("""geo:(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)""")
    private val PLACE = Regex("""/maps/place/([^/@?]+)""")

    fun isMapsLink(text: String) = Regex("""(?i)(maps\.app\.goo\.gl|goo\.gl/maps|google\.[a-z.]+/maps|maps\.google\.|geo:-?\d)""").containsMatchIn(text)

    fun parse(text: String): Shared? {
        if (!isMapsLink(text)) return null
        val m = PIN.find(text) ?: AT.find(text) ?: Q.find(text) ?: GEO.find(text)
        val lat = m?.groupValues?.get(1)?.toDoubleOrNull()
        val lng = m?.groupValues?.get(2)?.toDoubleOrNull()
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("http") && !it.startsWith("geo:") }
        val fromUrl = PLACE.find(text)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it.replace("+", " "), "UTF-8") }
        val name = (fromUrl ?: lines.firstOrNull())?.trim()?.takeIf { it.isNotEmpty() && it.any { c -> c.isLetter() } }
        val address = lines.drop(1).firstOrNull { l -> l.any { it.isDigit() } && l.contains(',') }
        if (lat != null && (abs(lat) > 90 || abs(lng ?: 0.0) > 180)) return null
        return Shared(name, lat, lng, address)
    }
}
