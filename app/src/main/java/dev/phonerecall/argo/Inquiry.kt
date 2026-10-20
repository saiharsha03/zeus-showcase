package dev.phonerecall.argo

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.mind.AskReply
import dev.phonerecall.mind.Confidence
import dev.phonerecall.mind.ConfidenceRule
import dev.phonerecall.ui.Workers
import java.time.LocalDate
import java.time.ZoneId

/*
 * Argo's investigations (the shared loop is mind/Investigate.kt). A weak hint that he may be travelling starts one;
 * Athena asks Iris, searches Alexandria and concludes. High confidence records the trip; anything less is ONE
 * question card, never more than one per place, and never again for a month once he answered. Pure.
 */

enum class InqStatus { OPEN, HANDED, ASKED, QUESTION, DONE, DROPPED }

data class Inquiry(
    val id: String = "",
    /** The hint's key, so the same hint never starts a second investigation. */
    val key: String = "",
    /** moneta, calendar, notification. */
    val source: String = "",
    val place: String? = null,
    val start: String? = null,
    val end: String? = null,
    val month: String? = null,
    /** The hint in words, the way it may be shown to him and asked of Iris. */
    val hint: String = "",
    val status: InqStatus = InqStatus.OPEN,
    val askId: String? = null,
    val evidence: List<String> = emptyList(),
    val confidence: Confidence? = null,
    val tripId: String? = null,
    /** yes, no: his answer to the question card. */
    val answer: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

data class Finding(val place: String?, val start: LocalDate?, val end: LocalDate?, val month: java.time.YearMonth?, val confidence: Confidence, val evidence: List<String>, val denied: Boolean = false)

object Hints {
    private const val DAY = 86_400_000L

    private fun id(now: Long, key: String) = "q" + java.lang.Long.toString(now, 36) + Integer.toHexString(key.hashCode() and 0xffff)

    /** A booking-like charge (merchant and time only) in the last 30 days. */
    fun fromCharges(charges: List<Pair<Long, String>>, now: Long, zone: ZoneId): List<Inquiry> =
        charges.filter { (at, m) -> now - at in 0..30 * DAY && ArgoMoney.isBooking(m) }.map { (at, m) ->
            val key = "charge:$at:${m.lowercase().take(24)}"
            Inquiry(id(now, key), key, "moneta", Cities.find(m)?.takeIf { it != Cities.HOME }, hint = "A charge at ${m.take(28)} on ${Clock.day(Trip.day(at, zone))} looks like a travel booking", createdAt = now, updatedAt = now)
        }

    /** Events in another city with no trip around them (holds, busy blocks, plans). */
    fun fromCalendar(items: List<TripItem>, trips: List<Trip>, now: Long, zone: ZoneId): List<Inquiry> =
        items.filterIsInstance<TripItem.PlanItem>().filter { it.place != Cities.HOME && it.plan.at > now && Trips.covering(trips, it.plan.at, zone) == null }.map { p ->
            val key = "cal:" + p.plan.id
            val d = Trip.day(p.plan.at, zone)
            Inquiry(id(now, key), key, "calendar", p.place, d.toString(), d.toString(), hint = "Something on his calendar in ${p.place} on ${Clock.day(d)}", createdAt = now, updatedAt = now)
        }

    /** A notification naming another city and a date. Only the city and the date are kept, never the text. */
    fun fromNotification(text: String, at: Long, zone: ZoneId): Inquiry? {
        val city = Cities.find(text)?.takeIf { it != Cities.HOME } ?: return null
        val w = WhenParse.parse(text, Trip.day(at, zone))
        if (w.start == null && w.month == null) return null
        val key = "note:${city.lowercase()}:${w.start ?: w.month}"
        val words = w.start?.let { Clock.day(it) } ?: w.month?.let { "in ${Clock.month(it)}" }
        return Inquiry(id(at, key), key, "notification", city, w.start?.toString(), w.end?.toString(), w.month?.toString(), "A notification mentioned $city and $words", createdAt = at, updatedAt = at)
    }

    /**
     * Which hints start an investigation: not one seen before, not one an open trip already explains, and at most
     * one open investigation per place.
     */
    fun fresh(hints: List<Inquiry>, d: ArgoData, zone: ZoneId): List<Inquiry> {
        val seen = d.inquiries.map { it.key }.toMutableSet()
        val out = ArrayList<Inquiry>()
        for (h in hints) {
            if (h.key in seen) continue
            seen += h.key
            // A layover city (Atlanta on the way to Austin) is explained by the trip it is on.
            val explained = d.trips.any { t ->
                t.open && h.place != null && (Cities.same(t.place, h.place) || (t.stops.map { it.place } + t.layovers).any { Cities.same(it, h.place) } ||
                    t.legs.any { l -> listOf(l.from, l.to).any { e -> Cities.known(e)?.let { Cities.same(it, h.place) } == true } })
            } ||
                (h.place == null && d.trips.any { it.open })
            val busy = (d.inquiries + out).any { it.status in OPEN && (it.place == h.place || (it.place != null && h.place != null && Cities.same(it.place, h.place))) }
            if (!explained && !busy) out += h
        }
        return out
    }

    val OPEN = setOf(InqStatus.OPEN, InqStatus.HANDED, InqStatus.ASKED, InqStatus.QUESTION)
}

object Inquiries {
    /** What Iris is asked: words about the hint, read only, never an amount or a place more exact than a city. */
    fun question(q: Inquiry): String = buildString {
        append(if (q.place != null) "Is the owner planning a trip to ${q.place}" else "Did the owner book travel recently")
        append(when {
            q.start != null -> " around ${Clock.day(LocalDate.parse(q.start))}"
            q.month != null -> " in ${Clock.month(java.time.YearMonth.parse(q.month))}"
            else -> ""
        })
        append("? Hint: ${q.hint}. Check his mail for flight, train, bus or hotel bookings and trip plans. Read only: change nothing. ")
        append("Reply with sections trip (yes, no or unknown), place (the city), dates (YYYY-MM-DD to YYYY-MM-DD), bookings (one line each, no confirmation numbers).")
    }

    /** The brief for Athena's circle when the mind is set up. */
    fun brief(q: Inquiry): String =
        "Find out whether the owner is planning a trip. Investigation ${q.id}. Hint (weak): ${q.hint}. " +
            "Use ask_worker (worker iris, topic trip) with a read-only question, search_memory (past Delphi chats, notes, documents), read_calendar and read_trips. " +
            "Then call record_trip_finding once with investigation_id ${q.id}, the city, when (dates or a month), your confidence (high only if a booking or Iris confirms it) and the evidence in words. " +
            "Never book or ask anyone to book; never put amounts, addresses or coordinates in a question."

    /** The evidence in, a conclusion out. Iris confirming with dates or a booking is high on its own. */
    fun conclude(q: Inquiry, reply: AskReply?, memory: List<String>, today: LocalDate): Finding {
        val s = reply?.sections.orEmpty().mapKeys { it.key.lowercase() }
        val said = s["trip"]?.firstOrNull()?.lowercase()?.trim()
        val bookings = s["bookings"].orEmpty().filter { it.isNotBlank() && !it.equals("none", true) }
        val place = s["place"]?.firstOrNull()?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let(Cities::canonical) ?: q.place
        val w = s["dates"]?.firstOrNull()?.let { WhenParse.parse(it, today) }?.takeIf { it.start != null || it.month != null }
        val start = w?.start ?: q.start?.let(LocalDate::parse)
        val end = w?.end ?: q.end?.let(LocalDate::parse)
        val month = if (start != null) null else w?.month ?: q.month?.let { java.time.YearMonth.parse(it) }
        val denied = said == "no" && bookings.isEmpty()
        val confirmed = said == "yes" && (bookings.isNotEmpty() || w?.start != null)
        val memoryHits = memory.filter { line -> place != null && Cities.find(line)?.let { Cities.same(it, place) } == true }
        val corroborating = (if (said == "yes" && !confirmed) 1 else 0) + (if (memoryHits.isNotEmpty()) 1 else 0)
        val evidence = buildList {
            add(q.hint)
            if (reply != null) add("${Workers.IRIS}: " + (listOfNotNull(said?.let { "trip $it" }) + bookings.take(3)).joinToString("; ").ifEmpty { "no clear answer" })
            else if (q.askId != null) add("${Workers.IRIS} didn't answer in 30 minutes")
            memoryHits.take(2).forEach { add("Memory: ${it.take(120)}") }
        }
        return Finding(place, start, end, month, if (denied) Confidence.LOW else ConfidenceRule.of(confirmed, corroborating), evidence, denied)
    }

    enum class Decision { RECORD, ASK_HIM, DROP }

    /**
     * High with a city: record it. Iris saying no with nothing else: drop quietly. Otherwise one question, unless
     * one is already open for that place or he answered one for it in the last 30 days.
     */
    fun decide(q: Inquiry, f: Finding, d: ArgoData, now: Long): Decision {
        if (f.confidence == Confidence.HIGH && f.place != null) return Decision.RECORD
        if (f.denied && f.evidence.none { it.startsWith("Memory:") }) return Decision.DROP
        val samePlace = { o: Inquiry -> o.id != q.id && (o.place == f.place || (o.place != null && f.place != null && Cities.same(o.place, f.place))) }
        if (d.inquiries.any { samePlace(it) && it.status == InqStatus.QUESTION }) return Decision.DROP
        if (d.inquiries.any { samePlace(it) && it.answer != null && now - it.updatedAt < 30 * 86_400_000L }) return Decision.DROP
        return Decision.ASK_HIM
    }

    /** "mid-March", "early April", "in March", "on Thu, Mar 4". */
    fun roughly(f: Inquiry): String = when {
        f.start != null -> LocalDate.parse(f.start).let { d -> (when (d.dayOfMonth) { in 1..10 -> "early "; in 11..20 -> "mid-"; else -> "late " }) + Clock.month(java.time.YearMonth.from(d)) }
        f.month != null -> "in " + Clock.month(java.time.YearMonth.parse(f.month))
        else -> ""
    }

    /** The one question card: "Looks like Boston early March. Right?" with quick replies. */
    fun card(q: Inquiry): CardContent {
        val when_ = roughly(q)
        return CardContent(
            title = if (q.place != null) "Looks like ${q.place}${if (when_.isNotEmpty()) " $when_" else ""}. Right?" else "A trip coming up?",
            body = (q.evidence.ifEmpty { listOf(q.hint) }.joinToString(". ") + "." + if (q.start == null) " Dates?" else "").trim(),
            actions = listOf(
                CardAction(if (q.place != null) "Yes" else "Add a trip", if (q.place != null) ActionRole.ARGO else ActionRole.OPEN_ARGO, if (q.place != null) "q:${q.id}:yes" else "new"),
                CardAction("Not a trip", ActionRole.ARGO, "q:${q.id}:no"),
                CardAction("Add dates", ActionRole.OPEN_ARGO, "new").takeIf { q.place != null && q.start == null },
            ).filterNotNull(),
        )
    }

    /** Applies a finding: the inquiry's new state and, for RECORD, the trips with it noted (source "athena"). */
    fun apply(d: ArgoData, q: Inquiry, f: Finding, now: Long, zone: ZoneId): ArgoData {
        val withFinding = q.copy(place = f.place ?: q.place, start = f.start?.toString() ?: q.start, end = f.end?.toString() ?: q.end, month = f.month?.toString() ?: q.month,
            evidence = f.evidence, confidence = f.confidence, updatedAt = now)
        return when (decide(q, f, d, now)) {
            Decision.RECORD -> {
                val whenText = f.start?.let { s -> if (f.end != null && f.end != s) "$s to ${f.end}" else s.toString() } ?: f.month?.let { Clock.month(it) }
                val (trips, t) = Trips.note(d.trips, f.place!!, whenText, null, now, zone)
                val marked = trips.map { if (it.id == t.id) it.copy(sources = (it.sources - "delphi" + "athena").distinct(), notes = it.notes) else it }
                d.copy(trips = marked, inquiries = d.inquiries.map { if (it.id == q.id) withFinding.copy(status = InqStatus.DONE, tripId = t.id) else it })
            }
            Decision.ASK_HIM -> d.copy(inquiries = d.inquiries.map { if (it.id == q.id) withFinding.copy(status = InqStatus.QUESTION) else it })
            Decision.DROP -> d.copy(inquiries = d.inquiries.map { if (it.id == q.id) withFinding.copy(status = InqStatus.DROPPED) else it })
        }
    }

    /** His tap on the question card. Yes records the trip (a vague one is fine); no closes it for a month. */
    fun answer(d: ArgoData, id: String, yes: Boolean, now: Long, zone: ZoneId): ArgoData {
        val q = d.inquiries.firstOrNull { it.id == id && it.status == InqStatus.QUESTION } ?: return d
        if (!yes || q.place == null) return d.copy(inquiries = d.inquiries.map { if (it.id == id) it.copy(status = InqStatus.DONE, answer = if (yes) "yes" else "no", updatedAt = now) else it })
        val whenText = q.start?.let { s -> if (q.end != null && q.end != s) "$s to ${q.end}" else s } ?: q.month?.let { Clock.month(java.time.YearMonth.parse(it)) }
        val (trips, t) = Trips.note(d.trips, q.place, whenText, null, now, zone)
        return d.copy(trips = trips.map { if (it.id == t.id) it.copy(sources = (it.sources - "delphi" + "you").distinct()) else it },
            inquiries = d.inquiries.map { if (it.id == id) it.copy(status = InqStatus.DONE, answer = "yes", tripId = t.id, updatedAt = now) else it })
    }
}

/**
 * Iris reads his mail for every trip already booked. She tells Argo about new bookings as they arrive, so one made
 * before Argo existed (or that she missed) would never show up; this asks her outright, once a week.
 */
object Sweep {
    const val OWNER = "argo:sweep"
    private const val EVERY = 7 * 86_400_000L

    fun due(d: ArgoData, now: Long) = now - d.sweptAt >= EVERY

    fun question(): String =
        "List every trip the owner has booked or planned from today on: flights, trains, buses, hotels or rentals, and tickets for events in another city. " +
            "Check his own mail only. Read only: change nothing. Send each booking with trip_info as usual, then reply with section trips, one line per trip: " +
            "City, YYYY-MM-DD to YYYY-MM-DD (the full itinerary: each flight with its number and airport codes, like AA 101 BUF-ATL 04-22, and each hotel's city). " +
            "Start a line with ~ for anything unsure or cancelled; none when there are none."

    /**
     * Her answer replaces her last sweep's evidence: each "City, start to end (itinerary)" line is a span, and the legs
     * in its parentheses are legs known by their day. Lines starting "~" are skipped. An answer with nothing readable
     * (and no "none") changes nothing. Trips are rebuilt; one he dismissed or archived stays that way.
     */
    fun apply(d: ArgoData, r: AskReply?, now: Long, zone: ZoneId): ArgoData {
        if (r == null) return d
        val lines = r.sections.entries.firstOrNull { it.key.equals("trips", true) }?.value
            ?: r.sections.values.flatten().ifEmpty { r.text.lines() }
        val ev = Evidence.sweep(lines, Trip.day(now, zone))
        if (ev.isEmpty() && lines.none { it.trim().equals("none", true) }) return d
        return Rebuild.run(d.copy(evidence = d.evidence.filter { it.feed != "sweep" } + ev, sweepAnswered = now), now, zone)
    }
}

/** The day after: the first thing he can't skip once back, and a light block before it. */
object DayAfter {
    /** The real return: the way back's arrival, once known. */
    fun returnAt(t: Trip): Long? = t.landing?.arriveAt?.takeIf { it > 0 }

    /** The first timed commitment within three days of getting back (not travel, birthdays or holidays). */
    fun first(events: List<CalItem>, back: Long): CalItem? = events.filter { e ->
        !e.allDay && e.begin >= back && e.begin - back < 3 * 86_400_000L && e.category !in setOf(EventCategory.TRAVEL, EventCategory.BIRTHDAY, EventCategory.HOLIDAY, EventCategory.OOO)
    }.minByOrNull { it.begin }

    /** "First thing back: 9:00 AM Standup, Tue". */
    fun words(e: CalItem, zone: ZoneId): String =
        "First thing back: ${Clock.hm(e.begin, zone)} ${e.title.take(40)}, ${Clock.day(Trip.day(e.begin, zone)).substringBefore(',')}"

    /**
     * The next morning, 8 to 10, ending earlier when the first commitment starts sooner; none when it starts before
     * 9 AM (no room to recover) or when he gets back in the morning (the day is ahead of him anyway).
     */
    fun recovery(back: Long, first: CalItem?, zone: ZoneId): Pair<Long, Long>? {
        val arrived = java.time.Instant.ofEpochMilli(back).atZone(zone)
        if (arrived.hour < 12) return null
        val day = arrived.toLocalDate().plusDays(1)
        val start = day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        var end = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        first?.let { if (it.begin < end) end = it.begin }
        return if (end - start < 60 * 60_000L) null else start to end
    }

    /** The proposal's add_calendar_event input (he picks the calendar on the card). */
    fun eventArgs(block: Pair<Long, Long>, zone: ZoneId): Map<String, String> {
        val iso = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        fun t(ms: Long) = iso.format(java.time.Instant.ofEpochMilli(ms).atZone(zone))
        return mapOf("title" to "Recovery morning (light)", "start" to t(block.first), "end" to t(block.second))
    }
}

/** Committed vs discretionary money for a trip. Amounts show only behind Moneta's fingerprint; the words never name one. */
data class TripMoney(val paid: Int, val due: Int, val committedCents: Long, val dueCents: Long, val budgetCents: Long?) {
    val discretionaryCents: Long? get() = budgetCents?.let { it - committedCents }
    fun words(): String = buildString {
        val n = paid + due
        append(if (n == 0) "No bookings with prices yet" else "$n ${if (n == 1) "booking" else "bookings"} (${listOfNotNull(paid.takeIf { it > 0 }?.let { "$it paid" }, due.takeIf { it > 0 }?.let { "$it awaiting payment" }).joinToString(", ")})")
        append(if (budgetCents == null) ". No budget set" else ". Budget set")
    }
}

object TripLedger {
    /**
     * Committed: the legs and stays Iris priced (paid or awaiting payment), else booking charges Moneta saw from 120
     * days before the trip to its end. Discretionary: the budget less what is committed.
     */
    fun of(t: Trip, charges: List<Triple<Long, String, Long>>, zone: ZoneId): TripMoney {
        val priced = t.legs.mapNotNull { l -> l.cents?.let { it to (l.paid != false) } } + t.stays.mapNotNull { s -> s.cents?.let { it to (s.paid != false) } }
        if (priced.isNotEmpty()) {
            val paid = priced.filter { it.second }; val due = priced.filter { !it.second }
            return TripMoney(paid.size, due.size, priced.sumOf { it.first }, due.sumOf { it.first }, t.budgetCents)
        }
        val w = t.window(zone)
        val seen = if (w == null) emptyList() else charges.filter { (at, m, _) -> ArgoMoney.isBooking(m) && at in (w.first - 120 * 86_400_000L)..w.second }
        return TripMoney(seen.size, 0, seen.sumOf { it.third }, 0, t.budgetCents)
    }

    fun dollars(cents: Long): String = "$" + String.format(java.util.Locale.US, "%,d", Math.round(cents / 100.0))
}
