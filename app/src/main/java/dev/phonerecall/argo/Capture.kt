package dev.phonerecall.argo

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalKind
import dev.phonerecall.data.EventCategory
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * How trips come in: the calendar, Iris's trip.info messages, Delphi's note_trip, and Moneta bookings (a
 * suggestion only). Each becomes evidence (Evidence.kt) and trips are assembled from it (Assemble.kt). Pure.
 */

/** When, as he said it. A month alone is a vague trip; a day range is a planned one. */
data class When(val start: LocalDate? = null, val end: LocalDate? = null, val month: YearMonth? = null, val words: String = "")

object WhenParse {
    private val MONTHS = (1..12).associateBy { java.time.Month.of(it).getDisplayName(TextStyle.FULL, Locale.US).lowercase() } +
        (1..12).associateBy { java.time.Month.of(it).getDisplayName(TextStyle.SHORT, Locale.US).lowercase().trimEnd('.') } + mapOf("sept" to 9)
    private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private val ISO = Regex("""(\d{4}-\d{2}-\d{2})(?:\s*(?:to|until|-|–|through)\s*(\d{4}-\d{2}-\d{2}))?""")
    // "Mar 4-8", "March 4 to 8", "Mar 4 - Mar 8", "Mar 29 to Apr 2"
    private val MD = Regex("""(?i)\b($MONTH)\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:\s*(?:-|–|to|through|until)\s*(?:($MONTH)\.?\s+)?(\d{1,2})(?:st|nd|rd|th)?)?\b""")
    // "4-8 March", "4 March"
    private val DM = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?(?:\s*(?:-|–|to)\s*(\d{1,2})(?:st|nd|rd|th)?)?\s+($MONTH)\b""")
    private val MONTH_ONLY = Regex("""(?i)\b($MONTH)\b""")

    fun parse(text: String?, today: LocalDate): When {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return When()
        ISO.find(t)?.let { m ->
            val s = LocalDate.parse(m.groupValues[1])
            val e = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } ?: s
            return When(s, maxOf(s, e), words = t)
        }
        MD.find(t)?.let { m ->
            val mo = MONTHS.getValue(m.groupValues[1].lowercase())
            val year = yearFor(mo, today)
            val s = runCatching { LocalDate.of(year, mo, m.groupValues[2].toInt()) }.getOrNull() ?: return@let
            val endMonth = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { MONTHS.getValue(it.lowercase()) } ?: mo
            val e = m.groupValues[4].takeIf { it.isNotEmpty() }?.let { d ->
                runCatching { LocalDate.of(if (endMonth < mo) year + 1 else year, endMonth, d.toInt()) }.getOrNull()
            } ?: s
            return When(s, maxOf(s, e), words = t)
        }
        DM.find(t)?.let { m ->
            val mo = MONTHS.getValue(m.groupValues[3].lowercase())
            val year = yearFor(mo, today)
            val s = runCatching { LocalDate.of(year, mo, m.groupValues[1].toInt()) }.getOrNull() ?: return@let
            val e = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.of(year, mo, it.toInt()) }.getOrNull() } ?: s
            return When(s, maxOf(s, e), words = t)
        }
        val l = t.lowercase()
        when {
            "tomorrow" in l -> return When(today.plusDays(1), today.plusDays(1), words = t)
            "next weekend" in l -> today.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY)).plusWeeks(1).let { return When(it, it.plusDays(1), words = t) }
            "this weekend" in l || l == "weekend" -> today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SATURDAY)).let { return When(it, it.plusDays(1), words = t) }
            "next month" in l -> return When(month = YearMonth.from(today).plusMonths(1), words = t)
            "this month" in l -> return When(month = YearMonth.from(today), words = t)
        }
        MONTH_ONLY.find(t)?.let { m ->
            val mo = MONTHS.getValue(m.groupValues[1].lowercase())
            return When(month = YearMonth.of(yearFor(mo, today), mo), words = t)
        }
        return When(words = t)
    }

    /** This year unless that month has already gone. */
    private fun yearFor(month: Int, today: LocalDate) = if (month < today.monthValue) today.year + 1 else today.year
}

/** City names Zeus recognises, from words, airport codes and "to <City>". Home is Buffalo. */
object Cities {
    const val HOME = "Buffalo"
    private val ALIASES: List<Pair<Regex, String>> = listOf(
        "nyc|new york( city)?|(?<=(?:to|in|for) )ny\\b|ny trip|big apple|manhattan|brooklyn|queens|jfk|lga|laguardia|ewr|newark|penn station|moynihan|grand central|port authority" to "New York",
        "boston|bos\\b|logan|south station|back bay" to "Boston", "chicago|ord\\b|o'hare|midway" to "Chicago", "toronto|yyz|pearson" to "Toronto",
        "washington,? d\\.?c\\.?|\\bdc\\b|dca\\b|iad\\b" to "Washington", "philadelphia|philly|phl\\b" to "Philadelphia",
        "san francisco|sfo\\b" to "San Francisco", "los angeles|lax\\b" to "Los Angeles", "seattle|sea-tac" to "Seattle", "boise" to "Boise", "nashville" to "Nashville",
        "miami|mia\\b" to "Miami", "orlando|mco\\b" to "Orlando", "atlanta|atl\\b" to "Atlanta", "dallas|dfw\\b" to "Dallas",
        "houston|iah\\b" to "Houston", "austin" to "Austin", "denver|den\\b" to "Denver", "boulder" to "Boulder", "las vegas|vegas" to "Las Vegas",
        "montreal|yul\\b" to "Montreal", "detroit|dtw\\b" to "Detroit", "cleveland" to "Cleveland", "pittsburgh" to "Pittsburgh",
        "rochester" to "Rochester", "albany" to "Albany", "syracuse" to "Syracuse", "hyderabad|hyd\\b" to "Hyderabad",
        "bangalore|bengaluru|blr\\b" to "Bengaluru", "chennai|maa\\b" to "Chennai", "mumbai|bom\\b" to "Mumbai", "delhi" to "Delhi",
        "london|heathrow|lhr\\b" to "London", "jersey city|hoboken" to "Jersey City", "buffalo|buf\\b|exchange st" to HOME,
    ).map { (re, city) -> Regex("(?i)\\b($re)") to city }
    /** Airport codes, capitals only since some are words ("den"): "BUF → DEN". Not the ones that read as other things ("IND vs AUS"). */
    private val CODES = Airports.ALL.filter { it.code !in setOf("IND", "AUS", "SAN", "SIN", "DEL", "MEX", "AMS", "PIT", "ALB", "ANC") }.associate { it.code to it.city }
    private val CODE = Regex("\\b(" + CODES.keys.joinToString("|") + ")\\b")
    private val TO = Regex("""\b(?:to|in|at)\s+([A-Z][a-z]+(?:\s[A-Z][a-z]+){0,2})""")
    private val NOT_CITIES = setOf("The", "Work", "Office", "Home", "Dinner", "Lunch", "Breakfast", "Team", "Gym", "Church", "Temple", "Airport", "Hotel")
    private val ARROW = Regex("""\s(?:to|TO|To|→|->|–|-)\s""")

    /**
     * The canonical city in the text, or null: known names, airport codes, then "to <City>". The destination wins: the
     * first city away from home after "to" or an arrow, else the first away from home, else home ("BUF to BNA" is Nashville).
     */
    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val known = ALIASES.flatMap { (re, city) -> re.findAll(text).map { it.range.first to city }.toList() } +
            CODE.findAll(text).map { it.range.first to CODES.getValue(it.value) }
        // A known city away from home beats a guess from "at <Name>" ("Stay at Example Hotel Boulder" is Boulder).
        val loose = if (known.any { !it.second.equals(HOME, true) }) emptyList()
            else TO.findAll(text).filter { it.groupValues[1].split(' ').first() !in NOT_CITIES }.map { it.groups[1]!!.range.first to it.groupValues[1] }.toList()
        val hits = (known + loose).sortedBy { it.first }
        val cut = ARROW.find(text)?.range?.first ?: -1
        val away = hits.filter { !it.second.equals(HOME, true) }
        return (away.firstOrNull { it.first > cut } ?: away.firstOrNull() ?: hits.firstOrNull())?.second
    }

    /** A leg's end ("Nashville (BNA)", "South Station") as a known city, or null. */
    fun known(end: String): String? = end.takeIf { it.isNotBlank() }?.let(::find)

    /** A leg's end as a city: a known one, else the name without its airport code ("Spokane (GEG)" is Spokane). */
    fun city(end: String): String? = end.takeIf { it.isNotBlank() }?.let { known(it) ?: canonical(it.substringBefore('(').ifBlank { it }) }

    /** "NYC" and "new york" are the same city. Unknown names keep their own capitalised form. */
    fun canonical(name: String): String = find(name) ?: name.trim().split(Regex("\\s+")).joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    fun same(a: String, b: String) = canonical(a).equals(canonical(b), ignoreCase = true)
}

/** One thing a source told us about a trip. */
sealed class TripItem {
    abstract val place: String
    abstract val source: String
    data class LegItem(override val place: String, val leg: Leg, override val source: String) : TripItem()
    data class StayItem(override val place: String, val stay: Stay, override val source: String) : TripItem()
    /** `changed`: Iris says it replaces her earlier copy (a new time or day). */
    data class PlanItem(override val place: String, val plan: TripPlan, override val source: String, val changed: Boolean = false) : TripItem()
    data class Dates(override val place: String, val start: LocalDate, val end: LocalDate, override val source: String, val id: String) : TripItem()
    /** By her id (`ref`), or for a plan cancelled by name, `match`: its title and time. */
    data class Cancel(override val place: String, val ref: String, override val source: String, val match: TripPlan? = null) : TripItem()
}

object CalendarCapture {
    private val FLY = Regex("(?i)\\b(flight|fly|flying|boarding|airport|departs?|jetblue|united airlines|delta air( lines)?|american airlines|southwest airlines|alaska airlines|air canada|air india)\\b")
    private val TRAIN = Regex("(?i)\\b(train|amtrak|rail|via rail|acela|empire service|lake shore|maple leaf)\\b")
    private val BUS = Regex("(?i)\\b(bus|greyhound|megabus|flixbus|trailways|ourbus)\\b")
    private val DRIVE = Regex("(?i)\\b(drive to|road trip|driving to)\\b")
    private val STAY = Regex("(?i)\\b(hotel|airbnb|check-?in|stay at|inn|motel|hostel|marriott|hilton|hyatt)\\b")
    private val TRIP = Regex("(?i)\\b(trip|vacation|holiday in|visit(ing)?)\\b")
    private val HOMEWARD = Regex("(?i)(\\b(to|→|->)\\s*(buffalo|buf)\\b|\\b(fly|flight|train|drive|bus) (back )?home\\b)")

    /** Calendars that hold other people's schedules (F1 races, holidays, birthdays, his teams' fixtures): never a trip. */
    val NOT_HIS = setOf(CalKind.SUBSCRIBED, CalKind.SPORTS, CalKind.HOLIDAY, CalKind.BIRTHDAYS, CalKind.FOLLOWED)

    /** "Example Hotel" from the event's place, else the title without "Hotel:" or "Check-in". */
    private fun stayName(e: CalItem): String = e.place?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
        ?: e.title.replace(Regex("(?i)^(hotel|check-?in|stay)\\s*[:-]?\\s*"), "").ifBlank { e.title }

    /** An all-day event's day: Android keeps them at UTC midnight. */
    fun allDay(ms: Long): LocalDate = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()

    /**
     * A flight from its title and place. Gmail's "Flight to Atlanta (AA 101)" sits at the airport it leaves from
     * ("Buffalo BUF"); another account's "Flight AA 101: BUF to ATL" names both ends.
     */
    private fun flight(e: CalItem, id: String, city: String, home: Boolean): Leg {
        val num = Flights.number(e.title) ?: Flights.number(e.place)
        val route = Flights.route(e.title)
        val from = route?.first?.let(Airports::of) ?: Airports.code(e.place)?.let(Airports::of)
        val to = route?.second?.let(Airports::of)
        return Leg(
            id, LegMode.FLIGHT, from = from?.let(Airports::label).orEmpty(), to = to?.let(Airports::label) ?: city,
            departAt = e.begin, arriveAt = if (e.allDay) 0 else e.end, carrier = num?.first.orEmpty(), number = num?.let { "${it.first} ${it.second}" }.orEmpty(),
            returning = home, source = "calendar",
        )
    }

    /**
     * Travel events and events in another city, as trip items. Ids come from the event, so reading the calendar again
     * replaces rather than duplicates. A leg toward Buffalo is the way back. Calendars in [NOT_HIS] are skipped.
     */
    fun items(events: List<CalItem>, zone: ZoneId, kinds: Map<Long, CalKind> = emptyMap()): List<TripItem> =
        events.filter { kinds[it.calendarId] !in NOT_HIS }.mapNotNull { item(it, zone) }

    fun item(e: CalItem, zone: ZoneId): TripItem? {
        val text = listOfNotNull(e.title, e.place).joinToString(" · ")
        val city = if (HOMEWARD.containsMatchIn(e.title)) Cities.HOME else Cities.find(e.title) ?: Cities.find(e.place)
        val id = "cal:${e.eventId}:${e.begin}"
        val overnight = e.end - e.begin >= 12 * 3_600_000L
        val multiDay = if (e.allDay) e.end - e.begin >= 2 * 86_400_000L else e.end - e.begin >= 20 * 3_600_000L
        val mode = when {
            TRAIN.containsMatchIn(text) -> LegMode.TRAIN
            BUS.containsMatchIn(text) -> LegMode.BUS
            FLY.containsMatchIn(text) -> LegMode.FLIGHT
            DRIVE.containsMatchIn(text) -> LegMode.DRIVE
            else -> null
        }
        return when {
            mode != null && city != null -> {
                val home = city == Cities.HOME
                val leg = if (mode == LegMode.FLIGHT) flight(e, id, city, home)
                    else Leg(id, mode, to = if (home) Cities.HOME else city, departAt = e.begin, arriveAt = if (e.allDay) 0 else e.end, returning = home, source = "calendar")
                TripItem.LegItem(city, leg, "calendar")
            }
            // A hotel the calendar didn't file as travel still counts once it is overnight in another city.
            STAY.containsMatchIn(text) && (e.category == EventCategory.TRAVEL || (overnight && city != null && city != Cities.HOME)) -> {
                if (city == Cities.HOME) return null
                // An all-day hotel runs from check-in day to check-out day: 3 PM to 11 AM where it is.
                val z = Airports.zoneOfCity(city)?.let { ZoneId.of(it) } ?: zone
                val (inAt, outAt) = if (!e.allDay) e.begin to e.end
                    else allDay(e.begin).atTime(15, 0).atZone(z).toInstant().toEpochMilli() to allDay(e.end).atTime(11, 0).atZone(z).toInstant().toEpochMilli()
                TripItem.StayItem(city.orEmpty(), Stay(id, stayName(e), checkIn = inAt, checkOut = outAt, source = "calendar"), "calendar")
            }
            city == null || city == Cities.HOME -> null
            // "Trip to Boston" all day, or any event in another city that runs over days ("Boston", Mar 4 to 8).
            (e.allDay && (TRIP.containsMatchIn(e.title) || e.category == EventCategory.TRAVEL)) || multiDay -> {
                val s = if (e.allDay) allDay(e.begin) else Trip.day(e.begin, zone)
                val en = if (e.allDay) allDay(e.end - 1) else Trip.day(e.end - 1, zone)
                TripItem.Dates(city, s, maxOf(s, en), "calendar", id)
            }
            else -> TripItem.PlanItem(city, TripPlan(id, e.title, e.begin, e.place.orEmpty(), source = "calendar"), "calendar")
        }
    }
}

/**
 * Iris's `trip.info` (server/docs/IRIS-ARGO.md), already checked by the gateway and checked again here.
 * A confirmation number never travels in it: Iris seals the confirmation into Calypso and sends its id.
 */
object IrisInfo {
    /** Every travel booking: legs, hotels, and plans (tickets and attractions, tours, car rentals, tables, parking). */
    val PLANS = setOf("plan", "event", "tour", "car", "restaurant", "parking")
    val KINDS = setOf("flight", "train", "bus", "drive", "hotel", "dates", "cancel") + PLANS
    private val CODEY = Regex("""(?i)\b(confirmation|conf\.?|record locator|pnr|booking (ref|reference|code|number)|reservation (number|code))\b""")
    private val DIGITS7 = Regex("""\d{7,}""")

    fun parse(json: String, homeZone: ZoneId): TripItem? = runCatching {
        val o = JSONObject(json)
        val kind = o.optString("kind")
        if (kind !in KINDS) return null
        val id = "iris:" + o.optString("id").also { require(it.length in 8..40) }
        fun s(k: String, max: Int = 80): String = o.optString(k, "").trim().take(max).also { v -> require(!CODEY.containsMatchIn(v) && !DIGITS7.containsMatchIn(v)) }
        val place = Cities.canonical(s("place").ifEmpty { return null })
        val tz = s("tz", 40).takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        fun time(k: String, zone: ZoneId): Long = s(k, 40).takeIf { it.isNotEmpty() }?.let { v ->
            runCatching { OffsetDateTime.parse(v).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
        } ?: 0L
        val doc = o.optString("calypso_id", "").trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) }
        val returning = o.optBoolean("returning", false)
        // A booking's price in dollars and whether it is paid: amounts stay on the phone, behind Moneta's fingerprint.
        val cents = if (o.has("price") && !o.isNull("price")) Math.round(o.getDouble("price") * 100).also { require(it in 0..10_000_000) } else null
        val paid = if (o.has("paid") && !o.isNull("paid")) o.getBoolean("paid") else null
        val destZone = tz ?: homeZone
        when (kind) {
            "flight", "train", "bus", "drive" -> TripItem.LegItem(
                place,
                Leg(
                    id, LegMode.valueOf(kind.uppercase()), s("from"), s("to"),
                    time("depart", if (returning) destZone else homeZone), time("arrive", if (returning) homeZone else destZone),
                    s("carrier", 40), s("number", 16), s("platform", 24), returning, doc, "iris", cents, paid,
                ),
                "iris",
            )
            "hotel" -> TripItem.StayItem(place, Stay(id, s("name"), s("address", 120), time("check_in", destZone), time("check_out", destZone), doc, "iris", cents, paid), "iris")
            in PLANS -> {
                val plan = TripPlan(
                    id, s("title"), time("at", destZone), s("venue").ifEmpty { s("where") },
                    (0 until (o.optJSONArray("people")?.length() ?: 0)).map { o.getJSONArray("people").optString(it).take(40) }, "iris",
                    end = time("until", destZone), kind = kind,
                )
                when (s("status", 12).lowercase()) {
                    "cancelled", "canceled" -> TripItem.Cancel(place, o.optString("ref").takeIf { it.startsWith("iris:") }.orEmpty(), "iris", plan.takeIf { o.optString("ref").isBlank() && it.title.isNotBlank() })
                    "changed" -> TripItem.PlanItem(place, plan, "iris", changed = true)
                    else -> TripItem.PlanItem(place, plan, "iris")
                }
            }
            "dates" -> {
                val st = LocalDate.parse(s("start", 10)); val en = s("end", 10).takeIf { it.isNotEmpty() }?.let { LocalDate.parse(it) } ?: st
                TripItem.Dates(place, st, maxOf(st, en), "iris", id)
            }
            else -> TripItem.Cancel(place, "iris:" + o.optString("ref").removePrefix("iris:").also { require(it.matches(Regex("[0-9a-f]{24}"))) }, "iris")
        }
    }.getOrNull()

    /** The Iris id of an envelope, for acking. */
    fun id(json: String): String? = runCatching { JSONObject(json).optString("id").takeIf { it.isNotEmpty() } }.getOrNull()
}

/** Trips as stored: ids, his notes, what covers a moment. The trips themselves are rebuilt from evidence (Assemble.kt). */
object Trips {
    fun newId(now: Long, place: String) = "t" + java.lang.Long.toString(now, 36) + Integer.toHexString(place.lowercase().hashCode() and 0xffff)

    /** The open trip whose days (a day either side) hold `at`, if any. */
    fun covering(trips: List<Trip>, at: Long, zone: ZoneId): Trip? = trips.filter { it.open }.firstOrNull { t ->
        t.days(zone)?.let { (s, e) -> Trip.day(at, zone) in s.minusDays(1)..e.plusDays(1) } == true
    }

    /**
     * Delphi's note_trip ("I'm going to Boston in March"): a vague trip, or more detail on the one he has.
     * Details are kept as a note in his words. Returns the trips and the one touched.
     */
    fun note(trips: List<Trip>, place: String, whenText: String?, details: String?, now: Long, zone: ZoneId, budgetCents: Long? = null): Pair<List<Trip>, Trip> {
        val city = Cities.canonical(place)
        val w = WhenParse.parse(whenText, Trip.day(now, zone))
        val at = w.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: w.month?.atDay(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()
        val hit = trips.filter { it.open && Cities.same(it.place, city) }.firstOrNull { t ->
            at == null || t.window(zone)?.let { (s, e) -> at in (s - 30 * 86_400_000L)..(e + 30 * 86_400_000L) } ?: (t.monthOf() == null || w.month == null || t.monthOf() == w.month || w.start?.let { YearMonth.from(it) == t.monthOf() } == true)
        }
        val base = hit ?: Trip(newId(now, city), city, createdAt = now, status = TripStatus.IDEA, sources = listOf("delphi"))
        var t = base.copy(
            vague = w.words.ifEmpty { base.vague },
            startDate = w.start?.toString() ?: base.startDate, endDate = w.end?.toString() ?: base.endDate,
            saidStart = w.start?.toString() ?: base.saidStart, saidEnd = w.end?.toString() ?: base.saidEnd,
            month = if (w.start != null) null else w.month?.toString() ?: base.month,
            notes = (base.notes + listOfNotNull(details?.trim()?.take(300)?.takeIf { it.isNotEmpty() })).distinct(),
            sources = (base.sources + "delphi").distinct(), updatedAt = now, budgetCents = budgetCents ?: base.budgetCents,
        )
        if (t.status == TripStatus.IDEA && t.days(zone) != null) t = t.copy(status = TripStatus.PLANNED)
        return (if (hit == null) trips + t else trips.map { if (it.id == hit.id) t else it }) to t
    }
}

/** Moneta's side: a booking charge offers a trip, and charges inside a trip are tagged with it. */
object ArgoMoney {
    private val BOOKING = Regex(
        "(?i)\\b(amtrak|jetblue|delta|united|american air(lines)?|southwest|spirit|frontier|alaska air|air canada|air india|emirates|" +
            "greyhound|megabus|flixbus|marriott|hilton|hyatt|ihg|holiday inn|sheraton|westin|airbnb|vrbo|booking\\.com|expedia|hotels\\.com|kayak)\\b",
    )

    fun isBooking(merchant: String) = BOOKING.containsMatchIn(merchant)

    /** The trip a charge at `at` falls in (its days, in his zone), by city name. Dismissed ideas don't count. */
    fun tripAt(trips: List<Trip>, at: Long, zone: ZoneId): String? = trips.firstOrNull { t ->
        t.status != TripStatus.DISMISSED && t.days(zone)?.let { (s, e) -> Trip.day(at, zone) in s..e } == true
    }?.place
}
