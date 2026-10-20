package dev.phonerecall.home

import dev.phonerecall.ui.Workers

import dev.phonerecall.data.Availability
import dev.phonerecall.data.AvailabilityModel
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.DayType
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.tintFor
import dev.phonerecall.ui.TileForm
import dev.phonerecall.ui.Tone
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.exp
import kotlin.math.min

/** Everything the board depends on. A pure input: the same inputs give the same board. */
data class BoardInputs(
    val now: ZonedDateTime,
    /** The curated calendar: only events that passed the relevance layer, plus contact birthdays. */
    val calendar: CalendarSnapshot,
    /** Day types for today and the next eight days (office, remote, OOO, weekend, holiday). */
    val availability: Map<LocalDate, Availability>,
    val sample: Boolean,
    val prefs: HomePrefs,
    val learned: Learned,
    val tasks: TaskSnapshot,
    val closet: ClosetSnapshot,
    /** Cards from the brain. Phase 1 has only the sample set. */
    val generated: List<GeneratedCard>,
    val generatedAreSamples: Boolean,
    /** Phase 3: live cards from Lemnos once the phone is paired (null fields fall back to placeholders). */
    val lemnos: dev.phonerecall.lemnos.LemnosHome? = null,
    /** Olympia's card for Olympus (a live match, fresh lineups, a recent result), or null. */
    val sports: dev.phonerecall.olympia.SportsCard? = null,
    /** Aeolia's weather (current, the next change, NWS alerts), or null before the first forecast. */
    val weather: dev.phonerecall.aeolia.WeatherState? = null,
    /** Venus's wear log, favorites and skips. */
    val venus: dev.phonerecall.closet.VenusPrefs = dev.phonerecall.closet.VenusPrefs(),
    /** Odyssey's cards (a traffic check, the week on the roads, a store proposal), or null when it is off. */
    val odyssey: dev.phonerecall.odyssey.OdysseyHome? = null,
    /** Hestia's card ("3 lights on, you're away"), or null when there is nothing to say. */
    val hearth: dev.phonerecall.hestia.HearthCard? = null,
    /** Argo's trip card and a possible-trip offer, or null with no trip. */
    val trip: dev.phonerecall.argo.TripHome? = null,
    /** Prometheus card weights he approved, by learning key (-10..+10). */
    val tuning: Map<String, Int> = emptyMap(),
    /** Today's morning brief once it went out (brief/), or null: the Brief card then falls back to its own lines. */
    val morning: dev.phonerecall.brief.MorningBrief? = null,
    /** The evening alarm card, or null. */
    val alarm: dev.phonerecall.brain.AlarmCards.Card? = null,
    /** Titles of what waited for this morning's brief (over yesterday's attention budget). Empty on a quiet day. */
    val digest: List<String> = emptyList(),
    /** The one Moments card, or null (quiet hours, snoozed, nothing happening). */
    val moment: dev.phonerecall.moments.MomentCard? = null,
    /** Today's birthdays, one per person whatever the source (brain/BirthdayBook): these replace the plain birthday event cards. */
    val birthdays: dev.phonerecall.brain.BirthdayBoard = dev.phonerecall.brain.BirthdayBoard(),
)

object Fmt {
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val date = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
    private val shortDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    fun time(t: ZonedDateTime): String = time.format(t)
    fun time(ms: Long, now: ZonedDateTime): String = time(Instant.ofEpochMilli(ms).atZone(now.zone))
    fun date(t: ZonedDateTime): String = date.format(t)
    fun shortDate(t: ZonedDateTime): String = shortDate.format(t)
    fun minutes(m: Long): String = if (m < 60) "$m min" else if (m % 60 == 0L) "${m / 60} h" else "${m / 60} h ${m % 60} min"

    /** "Today", "Today, 6:00 PM", "Tomorrow", "Fri, Oct 23", "Overdue". */
    fun due(due: Long, hasTime: Boolean, now: ZonedDateTime): String {
        val t = Instant.ofEpochMilli(due).atZone(now.zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val at = if (hasTime) ", ${time(t)}" else ""
        return when {
            days < 0 || (hasTime && t.isBefore(now)) -> "Overdue"
            days == 0L -> "Today$at"
            days == 1L -> "Tomorrow$at"
            else -> shortDate(t) + at
        }
    }
}

/**
 * The card engine. Builds candidate cards, scores them, orders and sizes them.
 *
 * score = base            the kind's weight
 *       + timeFit         0..20, how well the card fits this hour (brief in the morning, recap in the evening)
 *       + recency         15 * e^(-age / 90 min), for content that changed recently
 *       + 20              if something on it is due today
 *       + 40 / + 10       if it is a security alert / a bank alert (both can never be hidden)
 *       + engagement      -15..+15, learned taps vs dismissals for this card at this hour of the week
 *       - 8 * less        "Less like this", up to three times
 *       + tuning          -10..+10, a Prometheus weight he approved
 *       + 1000            if pinned
 * Placeholders ("arrives in phase N") are capped at 5 so they sit at the bottom.
 * Then cards he moved by drag are put back in the slot he dropped them in.
 */
object CardEngine {

    fun idOf(kind: CardKind) = kind.name.lowercase()
    fun idOf(g: GeneratedCard) = "gen:${g.id}"
    fun learnKey(kind: CardKind) = kind.name
    fun learnKey(g: GeneratedCard) = "GENERATED:${g.source.name}"
    fun hourOfWeek(t: ZonedDateTime) = (t.dayOfWeek.value - 1) * 24 + t.hour

    /** The kinds a user can place, in catalogue order. */
    val catalogue: List<CardKind> = CardKind.entries.filter { it != CardKind.GENERATED }

    fun onHome(kind: CardKind, p: HomePrefs): Boolean {
        val id = idOf(kind)
        return id !in p.hidden && (kind.defaultOn || id in p.added)
    }

    private class Candidate(
        val id: String,
        val kind: CardKind,
        val learnKey: String,
        val kicker: String,
        val content: CardContent,
        val tint: Tint,
        val timeFit: Float = 0f,
        val updatedAt: Long? = null,
        val dueToday: Boolean = false,
        val alert: Float = 0f,
        val neverHideReason: String? = null,
        val instance: String? = null,
        val relevantUntil: Long? = null,
    )

    /** A card with no item behind it (weather, brief) is one instance per day. */
    private fun instanceKeyOf(c: Candidate, now: ZonedDateTime) = c.instance ?: "${c.id}@${now.toLocalDate().toEpochDay()}"

    /** One event's identity on every card that shows it (its own card, Next up, the Now Bar). */
    private fun eventInstance(e: CalItem, now: ZonedDateTime) = "event:${e.key}@${Instant.ofEpochMilli(e.begin).atZone(now.zone).toLocalDate().toEpochDay()}"

    private fun liveDismissed(key: String?, i: BoardInputs) =
        key != null && (i.prefs.dismissed[dev.phonerecall.data.dismissKey(key)] ?: 0L) > i.now.toInstant().toEpochMilli()

    private fun dismissed(c: Candidate, i: BoardInputs) =
        (i.prefs.dismissed[dev.phonerecall.data.dismissKey(instanceKeyOf(c, i.now))] ?: 0L) > i.now.toInstant().toEpochMilli()

    fun build(i: BoardInputs): Board {
        val p = i.prefs
        val nowMs = i.now.toInstant().toEpochMilli()

        val placed = catalogue.filter { onHome(it, p) }
        val candidates = ArrayList<Candidate>()
        for (kind in placed) content(kind, i)?.let { candidates += it }
        if (CardKind.EVENT in placed) {
            val (cards, overflow) = eventCards(i)
            candidates += cards
            candidates += i.birthdays.active.map { birthdayCandidate(it, i) }
            // More than four: the rest roll into Now and next.
            if (overflow.isNotEmpty()) {
                val at = candidates.indexOfFirst { it.kind == CardKind.NOW_NEXT }
                if (at >= 0) {
                    val nn = candidates[at]
                    val first = overflow.first()
                    val more = if (overflow.size > 1) ", and ${overflow.size - 1} more" else ""
                    val also = Fact("Also", "${first.title}, ${whenText(first, i.now)}$more")
                    candidates[at] = Candidate(nn.id, nn.kind, nn.learnKey, nn.kicker, nn.content.copy(facts = nn.content.facts + also), nn.tint, nn.timeFit, nn.updatedAt, nn.dueToday, nn.alert, nn.neverHideReason, nn.instance, nn.relevantUntil)
                }
            }
        }
        for (g in i.generated) if (g.expiresAt > nowMs) candidates += generated(g, i)

        val scored = candidates.map { c ->
            var s = c.kind.base + c.timeFit
            c.updatedAt?.let { s += 15f * exp(-((nowMs - it).coerceAtLeast(0) / 60_000f) / 90f) }
            if (c.dueToday) s += 20f
            s += c.alert
            s += engagement(i.learned, c.learnKey, hourOfWeek(i.now))
            s -= 8f * min(p.less[c.learnKey] ?: 0, 3)
            s += (i.tuning[c.learnKey] ?: 0).coerceIn(-10, 10).toFloat()
            if (c.content.placeholder) s = min(s, 5f) - (40f - c.kind.base) / 100f
            if (c.id in p.pins) s += 1000f
            c to s
        }

        // Hidden cards stay off, unless they carry something that must never be hidden.
        val ordered = scored.filter { (c, _) -> c.neverHideReason != null || (c.id !in p.hidden && !dismissed(c, i)) }
            .sortedByDescending { it.second }
            .toMutableList()
        // Moved cards go back to the slot he dropped them in.
        for ((id, slot) in p.positions.entries.sortedBy { it.value }) {
            val at = ordered.indexOfFirst { it.first.id == id }
            if (at < 0) continue
            val item = ordered.removeAt(at)
            ordered.add(slot.coerceIn(0, ordered.size), item)
        }

        val cards = ordered.mapIndexed { idx, (c, s) ->
            ScoredCard(
                id = c.id, kind = c.kind, learnKey = c.learnKey, kicker = c.kicker,
                content = if (liveDismissed(c.content.live?.key, i)) c.content.copy(live = null) else c.content,
                score = s, size = sizeFor(idx, s, c), tint = c.tint, pinned = c.id in p.pins, moved = c.id in p.positions,
                neverHide = c.neverHideReason != null, neverHideReason = c.neverHideReason,
                instanceKey = instanceKeyOf(c, i.now), relevantUntil = c.relevantUntil,
            )
        }

        val urgent = candidates.filter { it.neverHideReason != null }.map { it.id }.toSet()
        val onHomeRows = placed.sortedByDescending { it.base }.map { k ->
            EditRow(idOf(k), k.title, k.about, idOf(k) in p.pins, idOf(k) in p.positions, idOf(k) in urgent)
        }
        val hidden = buildList {
            for (k in catalogue) if (k.defaultOn && idOf(k) in p.hidden && idOf(k) !in urgent) add(HiddenRow(idOf(k), k.title))
            for (g in i.generated) if (g.expiresAt > nowMs && idOf(g) in p.hidden && idOf(g) !in urgent) add(HiddenRow(idOf(g), g.title))
        }
        val catalogueRows = catalogue.map { CatalogueRow(idOf(it), it.title, it.about, onHome(it, p)) }
        return Board(cards, onHomeRows, hidden, catalogueRows, Fmt.date(i.now), i.calendar.allowed, nextChange(i))
    }

    /** The next moment the board may change on its own: an event edge, an expiry, a snooze, the hour. */
    fun nextChange(i: BoardInputs): Long {
        val nowMs = i.now.toInstant().toEpochMilli()
        val times = ArrayList<Long>()
        for (e in i.calendar.events) if (!e.allDay) { times += e.begin - 3_600_000L; times += e.begin; times += e.end }
        for (g in i.generated) times += g.expiresAt
        times += i.prefs.snoozed.values
        times += i.now.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
        return times.filter { it > nowMs + 1_000 }.minOrNull() ?: (nowMs + 3_600_000L)
    }

    /** Learned taps vs dismissals at this hour of the week, and half-weight for the hours either side. */
    fun engagement(l: Learned, key: String, how: Int): Float {
        var taps = 0f
        var dis = 0f
        for ((offset, w) in listOf(-1 to 0.5f, 0 to 1f, 1 to 0.5f)) {
            val c = l.at(key, (how + offset + 168) % 168) ?: continue
            taps += c.taps * w
            dis += c.dismissals * w
        }
        val n = taps + dis
        if (n == 0f) return 0f
        val p = (taps + 1f) / (n + 2f)          // smoothed tap rate
        val confidence = n / (n + 4f)          // few events count for little
        return 15f * (2f * p - 1f) * confidence
    }

    private fun sizeFor(index: Int, score: Float, c: Candidate): CardSize {
        val k = c.content
        val interactive = k.tasks.isNotEmpty() || k.actions.isNotEmpty() || k.checks.isNotEmpty() || k.photos.isNotEmpty()
        return when {
            k.placeholder -> CardSize.COMPACT
            index == 0 -> CardSize.HERO
            score >= 55f -> CardSize.LARGE
            score < 22f && !interactive -> CardSize.COMPACT
            else -> CardSize.NORMAL
        }
    }

    // ---- content per kind ----

    private fun placeholder(kind: CardKind, line: String) =
        Candidate(idOf(kind), kind, learnKey(kind), kind.label, CardContent(title = line, placeholder = true), kind.tint)

    private fun content(kind: CardKind, i: BoardInputs): Candidate? {
        val now = i.now
        val h = now.hour
        fun make(
            content: CardContent, timeFit: Float = 0f, updatedAt: Long? = null, dueToday: Boolean = false,
            alert: Float = 0f, reason: String? = null, kicker: String = kind.label, tint: Tint = kind.tint,
        ) = Candidate(idOf(kind), kind, learnKey(kind), kicker, content, tint, timeFit, updatedAt, dueToday, alert, reason)

        return when (kind) {
            CardKind.NOW_NEXT -> nowNext(i).let { (c, fit) -> make(c, fit, kicker = daypart(now)) }
            CardKind.BRIEF -> brief(i)
            CardKind.COMMUTE -> i.odyssey?.commute?.let { make(it, timeFit = i.odyssey.commuteFit, dueToday = true, kicker = if (it.facts.firstOrNull()?.status == "Late") "Leave now" else kind.label) } ?: run {
                val t = now.toLocalTime()
                if (!officeDay(now.toLocalDate(), i) || t.isBefore(LocalTime.of(5, 0)) || !t.isBefore(LocalTime.of(10, 30))) null
                else if (i.sample) make(SampleData.commute(now), timeFit = 20f)
                else placeholder(kind, "Office day. Turn on Odyssey for leave-by times with traffic.")
            }
            CardKind.TASKS -> tasks(i)?.let { (c, due) ->
                make(
                    c, timeFit = when (h) { in 16..21 -> 10f; in 7..9 -> 5f; else -> 0f }, dueToday = due,
                    reason = if (due) "Due today, so it stays on Home." else null,
                )
            }
            CardKind.REVIEW -> review(i)?.let { make(it, timeFit = 12f) }
            CardKind.WARDROBE -> wardrobe(i)?.let { make(it, timeFit = when (h) { in 6..9 -> 18f; in 10..11 -> 4f; else -> 0f }) }
                ?: placeholder(kind, "Closet not imported yet.")
            CardKind.SHOPPING -> {
                // Only what is still to buy; ticking a line takes it off (with Undo).
                val checks = WinterList.items.filter { it.first !in i.prefs.bought }.map { (id, label) -> CheckItem(id, label, false) }
                make(
                    CardContent(
                        title = if (checks.isEmpty()) "All bought." else "${checks.size} left for winter",
                        checks = checks,
                        actions = listOf(CardAction("Open list", ActionRole.OPEN_CLOSET)),
                    ),
                    timeFit = if (MonthCold.level(now.toLocalDate()) > 0) 3f else 0f,
                )
            }
            CardKind.WEATHER -> {
                val fit = when (h) { in 6..9 -> 12f; in 15..18 -> 5f; else -> 0f }
                val w = i.weather
                val live = w?.let { dev.phonerecall.aeolia.WeatherCards.weather(it, now.toInstant().toEpochMilli()) }
                if (w != null && live != null) make(live, timeFit = fit + if (w.nextAt != null) 4f else 0f, updatedAt = w.updatedAt, tint = if (dev.phonerecall.aeolia.Sky.kind(w.code) == dev.phonerecall.aeolia.Sky.Kind.DRY) Tint.SUN else Tint.RAIN)
                else if (i.sample) make(SampleData.weather(), timeFit = fit, tint = Tint.RAIN)
                else placeholder(kind, "Open Aeolia once to load the weather.")
            }
            // Extreme and Severe alerts lift to the top; Moderate ones are a card at all.
            CardKind.WEATHER_ALERT -> i.weather?.let { w ->
                dev.phonerecall.aeolia.WeatherCards.alert(w, now.toInstant().toEpochMilli())
                    ?.let { (c, lift) -> make(c, alert = lift, updatedAt = w.alertsAt, reason = "An official warning, so it stays on Home.") }
            }
            CardKind.AGENTS ->
                if (!i.sample && i.lemnos?.agents != null) make(i.lemnos.agents, timeFit = if (h >= 20 || h < 3) 12f else 0f)
                else if (i.sample) make(SampleData.agents(now), timeFit = if (h >= 20 || h < 3) 12f else 0f, updatedAt = now.minusMinutes(25).toInstant().toEpochMilli())
                else placeholder(kind, "${Workers.IRIS} and ${Workers.ATLAS} move in with phase 3.")
            CardKind.MUSIC ->
                if (i.sample) make(SampleData.music(now), timeFit = when (h) { in 7..9 -> 8f; in 18..23 -> 10f; else -> 0f })
                else placeholder(kind, "Music for the moment arrives in phase 5.")
            CardKind.VPS ->
                if (!i.sample && i.lemnos?.server != null) make(i.lemnos.server, timeFit = if (h >= 22 || h < 3) 6f else 0f, alert = if (i.lemnos.server.tone == Tone.ALERT) 20f else 0f)
                else if (i.sample) make(SampleData.vps(), timeFit = if (h >= 22 || h < 3) 6f else 0f)
                else placeholder(kind, "Server status arrives in phase 3.")
            CardKind.SECURITY ->
                // Cerberus: high alerts lift the card to the top; medium ones make it a card at all.
                if (!i.sample && i.lemnos?.security != null) make(i.lemnos.security, timeFit = if (h in 6..9) 4f else 0f,
                    alert = when (i.lemnos.security.tone) { Tone.ALERT -> 40f; Tone.ACTIVE -> 10f; else -> 0f })
                else if (i.sample) make(SampleData.security(now), timeFit = if (h in 6..9) 4f else 0f)
                else placeholder(kind, "The security line arrives from ${Workers.IRIS} in phase 2.")
            CardKind.ROADS -> i.odyssey?.roads?.let { make(it, timeFit = if (now.dayOfWeek.value >= 6 || h >= 18) 8f else 0f) }
            CardKind.PLACE_OFFER -> i.odyssey?.offer?.let { make(it, timeFit = 6f) }
            CardKind.SNOW_BAND -> i.odyssey?.snow?.let { make(it, timeFit = 18f, dueToday = true, alert = 12f, reason = "A snow band on your drive, so it stays on Home.") }
            CardKind.WFH -> i.odyssey?.wfh?.let { make(it, timeFit = 20f, dueToday = true) }
            CardKind.FUEL -> i.odyssey?.fuel?.let { make(it, timeFit = 16f, dueToday = true) }
            CardKind.LEASE -> i.odyssey?.lease?.let { make(it, timeFit = 6f) }
            CardKind.HEARTH -> i.hearth?.let { make(it.content, timeFit = it.fit, updatedAt = it.updatedAt) }
            CardKind.ALARM -> i.alarm?.let { make(it.content, timeFit = it.fit, updatedAt = it.updatedAt, kicker = it.kicker) }
            CardKind.MOMENT -> i.moment?.let { make(it.content, timeFit = it.fit) }
            CardKind.TRIP -> i.trip?.let { t -> t.card?.let { make(it, timeFit = t.fit, dueToday = t.today, alert = t.alert, kicker = t.kicker) } }
            CardKind.TRIP_OFFER -> i.trip?.offer?.let { make(it, timeFit = 4f) }
            CardKind.MATCH -> i.sports?.let { make(it.content, timeFit = it.fit, updatedAt = it.updatedAt) }
            CardKind.OOO -> oooCard(i)?.let { (c, today) -> make(c, timeFit = if (today && h in 5..11) 22f else if (today) 10f else 4f) }
            CardKind.EVENT, CardKind.GENERATED -> null
        }
    }

    // ---- out of office ----

    private fun oooCard(i: BoardInputs): Pair<CardContent, Boolean>? {
        val today = i.now.toLocalDate()
        val days = (0..8).map { today.plusDays(it.toLong()) }
        val start = days.firstOrNull { dayType(it, i) == DayType.OOO } ?: return null
        if (start.isAfter(today.plusDays(7))) return null
        var end = start
        while (dayType(end.plusDays(1), i) == DayType.OOO || (dayType(end.plusDays(1), i) == DayType.WEEKEND && dayType(end.plusDays(2), i) == DayType.OOO)) end = end.plusDays(1)
        val short = { d: LocalDate -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.US) }
        val long = { d: LocalDate -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US) }
        val includesToday = start == today
        val title = when {
            includesToday && end == today -> "You're off today"
            includesToday -> "You're off until ${long(end)}"
            start == end -> "You're off ${if (start == today.plusDays(1)) "tomorrow" else long(start)}"
            else -> "You're off ${short(start)} to ${short(end)}"
        }
        var back = end.plusDays(1)
        while (dayType(back, i) == DayType.WEEKEND || dayType(back, i) == DayType.HOLIDAY) back = back.plusDays(1)
        val facts = listOf(Fact("Back", "${long(back)}, ${dayType(back, i).label.lowercase()}"))
        return CardContent(title = title, facts = facts, status = if (includesToday) "Off today" else null, tone = Tone.OK) to includesToday
    }

    // ---- event cards ----

    private val importantWork = listOf("review", "interview", "presentation", "demo", "all hands", "all-hands", "offsite", "kickoff", "kick-off", "deadline", "launch", "1:1", "one on one", "performance")

    /** Which relevant events earn a card of their own. */
    private fun cardWorthy(e: CalItem): Boolean = when (e.category) {
        EventCategory.SPORT, EventCategory.BIRTHDAY, EventCategory.HOLIDAY, EventCategory.TRAVEL -> true
        EventCategory.PERSONAL -> !e.recurring
        EventCategory.WORK -> !e.recurring || importantWork.any { e.title.lowercase().contains(it) }
        EventCategory.OOO -> false
    }

    private fun whenText(e: CalItem, now: ZonedDateTime): String {
        val b = Instant.ofEpochMilli(e.begin).atZone(now.zone)
        val day = when (ChronoUnit.DAYS.between(now.toLocalDate(), b.toLocalDate())) { 0L -> "today"; 1L -> "tomorrow"; else -> Fmt.shortDate(b) }
        return if (e.allDay) day else "$day at ${Fmt.time(b)}"
    }

    /** "Barcelona vs Real Madrid" from "La Liga: Barcelona vs Real Madrid". */
    private fun competitors(title: String): String {
        val m = Regex("([^:|]+?)\\s+(?:vs\\.?|v)\\s+([^|(]+)", RegexOption.IGNORE_CASE).find(title) ?: return title
        return "${m.groupValues[1].trim()} vs ${m.groupValues[2].trim()}"
    }

    private fun birthdayName(title: String): String =
        title.replace(Regex("(?i)'s\\s+birthday|\\s+birthday|birthday:?\\s*|\\s*b-?day"), "").trim().ifEmpty { "Someone" }

    /** Up to four event cards, nearest first; the rest come back as overflow for Now and next. */
    private fun eventCards(i: BoardInputs): Pair<List<Candidate>, List<CalItem>> {
        val now = i.now
        val nowMs = now.toInstant().toEpochMilli()
        val horizon = nowMs + 36 * 3_600_000L
        val today = now.toLocalDate()
        val dayAfterTomorrow = today.plusDays(2).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val worthy = i.calendar.events.filter { e ->
            val grace = if (e.category == EventCategory.SPORT) 30 * 60_000L else 0L
            // Birthdays and festivals show the day before and the day of.
            val dayBefore = e.category == EventCategory.BIRTHDAY || e.category == EventCategory.HOLIDAY
            e.end + grace > nowMs && cardWorthy(e) && (e.begin <= horizon || (dayBefore && e.begin < dayAfterTomorrow)) &&
                // A birthday whose day has started shows once, as the birthday card (or not at all once he wished).
                !(e.category == EventCategory.BIRTHDAY && birthdayId(e, now) in i.birthdays.covered)
        }.sortedBy { it.begin }
        val shown = worthy.take(4)
        return shown.map { eventCandidate(it, i) } to worthy.drop(4)
    }

    private fun birthdayId(e: CalItem, now: ZonedDateTime): String =
        "${dev.phonerecall.brain.BirthdayBook.keyOf(dev.phonerecall.brain.BirthdayBook.nameOf(e.title))}@${Instant.ofEpochMilli(e.begin).atZone(now.zone).toLocalDate()}"

    /** One person's birthday while her day lasts: the deadline on her clock, and one tap to call, message, or say it's done. */
    private fun birthdayCandidate(b: dev.phonerecall.brain.DayBirthday, i: BoardInputs): Candidate {
        val book = dev.phonerecall.brain.BirthdayBook
        val via = if (b.india) dev.phonerecall.calls.CallRouting.VIDEO else dev.phonerecall.calls.CallRouting.PHONE
        val content = CardContent(
            title = "${b.name}'s birthday today",
            facts = listOf(Fact("Ends", book.deadline(b, i.now.zone).replaceFirstChar { it.uppercase() }), Fact("Seen in", b.from.joinToString(", ") { it.word })),
            tone = Tone.ACTIVE,
            stat = Stat("${b.date.dayOfMonth}", "today", TileForm.CLOVER),
            actions = listOf(
                CardAction(if (b.india) "Video call" else "Call", ActionRole.BIRTHDAY_REACH, "${b.id}|${b.name}|$via"),
                CardAction("Message", ActionRole.BIRTHDAY_REACH, "${b.id}|${b.name}|chat"),
                CardAction("Already wished", ActionRole.BIRTHDAY_DONE, b.id),
            ),
        )
        return Candidate(
            "birthday:${b.id}", CardKind.EVENT, "EVENT:BIRTHDAY", EventCategory.BIRTHDAY.label, content, tintFor(EventCategory.BIRTHDAY),
            if (i.now.hour < 12) 45f else 30f, dueToday = true,
        )
    }

    private fun eventCandidate(e: CalItem, i: BoardInputs): Candidate {
        val now = i.now
        val nowMs = now.toInstant().toEpochMilli()
        val grace = if (e.category == EventCategory.SPORT) 30 * 60_000L else 0L
        val live = !e.allDay && e.begin <= nowMs && e.end + grace > nowMs
        val mins = (e.begin - nowMs) / 60_000
        val b = Instant.ofEpochMilli(e.begin).atZone(now.zone)
        val en = Instant.ofEpochMilli(e.end).atZone(now.zone)
        val isToday = b.toLocalDate() == now.toLocalDate()
        val facts = ArrayList<Fact>()
        var title = e.title
        var liveInfo: LiveInfo? = null
        var stat: Stat? = null
        when (e.category) {
            EventCategory.BIRTHDAY -> {
                val name = birthdayName(e.title)
                title = "$name's birthday ${if (isToday) "today" else "tomorrow"}"
                // Birthdays never take the Now Bar; it is for timed things only.
                if (isToday) stat = Stat("${b.dayOfMonth}", "today", TileForm.CLOVER)
            }
            EventCategory.HOLIDAY -> {
                title = "${e.title} ${if (isToday) "today" else whenText(e, now)}"
                Festivals.hint(e.title, b.toLocalDate())?.let { facts += Fact("Wear", it) }
            }
            EventCategory.SPORT -> {
                title = competitors(e.title)
                if (title != e.title) facts += Fact("Event", e.title)
                facts += Fact("Starts", "${Fmt.time(b)}${if (isToday) "" else ", " + whenText(e, now)}")
            }
            else -> {
                facts += Fact("When", if (e.allDay) whenText(e, now) else "${whenText(e, now)}, until ${Fmt.time(en)}")
                e.place?.let { facts += Fact("Where", it) }
            }
        }
        val status = when {
            live -> "Live now"
            !e.allDay && mins in 0..(6 * 60) -> "In ${Fmt.minutes(mins)}"
            else -> null
        }
        if (!e.allDay && !live && mins in 0..59) stat = Stat("$mins", "min", TileForm.BURST)
        val liveKey = eventInstance(e, now)
        if (!e.allDay && live) liveInfo = LiveInfo("Live", title, "Until ${Fmt.time(en)}", countdownTo = e.end, key = liveKey)
        else if (!e.allDay && mins in 0..60) liveInfo = LiveInfo(chipOf(title), title, "Starts at ${Fmt.time(b)}", countdownTo = e.begin, key = liveKey)

        // Rises as the start gets close; at the top in the last hour and while it is on.
        val fit = when {
            live -> 70f
            e.allDay && e.category == EventCategory.BIRTHDAY -> if (isToday) (if (now.hour < 12) 45f else 30f) else 12f
            e.allDay -> if (isToday) 22f else 6f
            mins <= 60 -> 70f
            else -> 40f * exp(-(mins / 60f) / 4f)
        }
        val content = CardContent(
            title = title, facts = facts, status = status, tone = if (live) Tone.ALERT else Tone.ACTIVE,
            stat = stat, live = liveInfo, event = EventRef(e.calendarId, e.title),
        )
        return Candidate(
            "event:${e.key}", CardKind.EVENT, "EVENT:${e.category.name}", e.category.label, content, tintFor(e.category), fit,
            dueToday = e.category == EventCategory.BIRTHDAY && isToday,
            instance = eventInstance(e, now), relevantUntil = e.end,
        )
    }

    /** Due today, overdue and two-minute tasks from the real store. Second value: anything due today. */
    private fun tasks(i: BoardInputs): Pair<CardContent, Boolean>? {
        if (!i.tasks.loaded) return null
        val now = i.now
        val nowMs = now.toInstant().toEpochMilli()
        val endOfDay = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val open = i.tasks.open()
        val due = open.filter { t -> ((t.due != null && t.due < endOfDay) || t.quick) && (i.prefs.snoozed[t.id.toString()] ?: 0L) <= nowMs }
            .sortedWith(compareBy({ !it.quick && it.due == null }, { it.due ?: Long.MAX_VALUE }))
        val items = due.map { t ->
            TaskItem(t.id, t.title, if (t.due != null) Fmt.due(t.due, t.dueHasTime, now) else "2 min", t.due != null, t.sample)
        }
        val dueToday = items.any { it.dueToday }
        val n = items.count { it.dueToday }
        val inbox = open.count { it.list == TaskList.INBOX }
        val facts = if (inbox > 0) listOf(Fact("Inbox", if (inbox == 1) "1 to process" else "$inbox to process")) else emptyList()
        val title = when {
            items.isEmpty() -> "Nothing due today."
            n == 0 -> if (items.size == 1) "1 two-minute job" else "${items.size} two-minute jobs"
            n == 1 -> "1 due today"
            else -> "$n due today"
        }
        val live = if (dueToday) LiveInfo(
            chip = "$n due", title = if (n == 1) "1 task due today" else "$n tasks due today",
            text = items.first { it.dueToday }.title, sample = items.all { it.sample },
        ) else null
        val stat = when {
            n > 0 -> Stat("$n", "due", TileForm.COOKIE)
            items.isNotEmpty() -> Stat("${items.size}", if (items.size == 1) "job" else "jobs", TileForm.COOKIE)
            else -> null
        }
        return CardContent(
            title = title, tasks = items, facts = facts, stat = stat,
            actions = listOf(CardAction("Open Tasks", ActionRole.OPEN_TASKS)),
            sample = items.isNotEmpty() && items.all { it.sample }, live = live,
        ) to dueToday
    }

    /** The weekly review, Sunday 1 PM to 10 PM until he finishes it: what each step holds now. */
    private fun review(i: BoardInputs): CardContent? {
        if (!i.tasks.loaded || !dev.phonerecall.tasks.Gtd.reviewDue(i.now, i.tasks.lastReview)) return null
        val facts = dev.phonerecall.tasks.Gtd.reviewSteps(i.tasks, i.now).filter { it.second > 0 }.map { (s, n) -> Fact(s.label, "$n") }
        return CardContent(
            title = "Weekly review", body = "Ten minutes: empty the Inbox, unstick projects, check what you are waiting for.",
            facts = facts, actions = listOf(CardAction("Start review", ActionRole.OPEN_REVIEW)),
        )
    }

    /** Venus's outfit of the day, mornings only. The same rules as Venus's Today, so they always agree. */
    internal fun wardrobe(i: BoardInputs, morningOnly: Boolean = true): CardContent? {
        if (!i.closet.imported || (morningOnly && i.now.hour !in 5..11)) return null
        val today = i.now.toLocalDate()
        val shift = i.prefs.another.split(':').let { if (it.size == 2 && it[0] == today.toEpochDay().toString()) it[1].toIntOrNull() ?: 0 else 0 }
        val plan = dev.phonerecall.closet.Outfits.plan(i.closet, outfitDay(today, i), i.venus, shift) ?: return null
        val o = plan.main
        val facts = buildList {
            o.traditional?.let { add(Fact("Wear", it)) }
            for ((role, p) in o.pieces) add(Fact(role.label, p.name))
        }
        return CardContent(
            title = o.title.ifEmpty { "What to wear today" },
            body = i.venus.polished(o.key, today.toEpochDay()) ?: o.reason,
            facts = facts,
            photos = o.pieces.mapNotNull { (_, p) -> p.picture?.let { CardPhoto(it, if (p.render != null) p.renderBg else null, fit = p.render != null) } },
            status = if (plan.wearing) "Wearing" else null,
            tone = Tone.OK,
            actions = if (plan.wearing) listOf(CardAction("Open ${dev.phonerecall.ui.Place.CLOSET.myth}", ActionRole.OPEN_CLOSET)) else listOf(
                CardAction("Wore it", ActionRole.WEAR, o.ids.joinToString("+")), CardAction("Another", ActionRole.ANOTHER),
                CardAction("Open ${dev.phonerecall.ui.Place.CLOSET.myth}", ActionRole.OPEN_CLOSET),
            ),
        )
    }

    /** The day an outfit is for, from the same availability and curated events the board uses. */
    fun outfitDay(date: LocalDate, i: BoardInputs): dev.phonerecall.closet.DayContext =
        dev.phonerecall.closet.Outfits.context(date, i.availability[date] ?: AvailabilityModel.of(date, i.calendar.events, i.now.zone), i.calendar.events, i.now.zone, i.weather?.let { dev.phonerecall.closet.VenusWeather.from(it) })

    /** The kicker for Now and next. No weekday: the header already carries the date. */
    private fun daypart(t: ZonedDateTime): String = when (t.hour) {
        in 5..11 -> "Morning"
        in 12..16 -> "Afternoon"
        in 17..20 -> "Evening"
        in 21..23 -> "Tonight"
        else -> "Late night"
    }

    /** The time, then the event that matters now: the one in progress, or the next one. */
    private fun nowNext(i: BoardInputs): Pair<CardContent, Float> {
        val now = i.now
        val nowMs = now.toInstant().toEpochMilli()
        val lead = Fmt.time(now)
        if (!i.calendar.allowed) {
            return CardContent(
                title = "Your next event shows here.",
                lead = lead,
                body = "Calendar access is read live and never copied.",
                actions = listOf(CardAction("Allow calendar", ActionRole.ALLOW_CALENDAR)),
            ) to 0f
        }
        val timed = i.calendar.events.filter { !it.allDay }
        val current = timed.firstOrNull { it.begin <= nowMs && it.end > nowMs }
        val next = timed.firstOrNull { it.begin > nowMs }
        val todayEnd = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val nextToday = next?.takeIf { it.begin < todayEnd }
        // All-day events with a card of their own (festivals, birthdays) are not repeated here.
        val allDay = i.calendar.events.firstOrNull { it.allDay && it.begin < todayEnd && it.end > nowMs && !cardWorthy(it) && it.category != EventCategory.OOO }

        val facts = ArrayList<Fact>()
        val title = when {
            current != null -> {
                if (nextToday != null) facts += Fact("Next", "${nextToday.title} at ${Fmt.time(nextToday.begin, now)}")
                "${current.title}, until ${Fmt.time(current.end, now)}"
            }
            nextToday != null -> "${nextToday.title} at ${Fmt.time(nextToday.begin, now)}"
            else -> {
                if (next != null) facts += Fact("Tomorrow", "${next.title} at ${Fmt.time(next.begin, now)}")
                "Nothing else on the calendar today."
            }
        }
        if (allDay != null) facts += Fact("All day", allDay.title)

        val minsToNext = nextToday?.let { (it.begin - nowMs) / 60_000 }
        val status = when {
            current != null -> "On now"
            minsToNext != null && minsToNext <= 60 -> "In ${Fmt.minutes(minsToNext)}"
            else -> null
        }
        val fit = when {
            minsToNext != null && minsToNext <= 60 -> 20f
            current != null -> 15f
            minsToNext != null && minsToNext <= 180 -> 12f
            else -> 5f
        }
        val live = if (nextToday != null && minsToNext != null && minsToNext <= 60) LiveInfo(
            chip = chipOf(nextToday.title), title = nextToday.title,
            text = "Starts at ${Fmt.time(nextToday.begin, now)}", countdownTo = nextToday.begin, key = eventInstance(nextToday, now),
        ) else null
        // Minutes to the next event as a tile, within three hours.
        val stat = minsToNext?.takeIf { it <= 180 }?.let { m ->
            if (m < 60) Stat("$m", "min", TileForm.BURST) else Stat("${m / 60}", if (m / 60 == 1L) "hour" else "hours", TileForm.CIRCLE)
        }
        return CardContent(title = title, lead = lead, facts = facts, status = status, tone = Tone.ACTIVE, live = live, stat = stat) to fit
    }

    /** A few characters for the status-bar chip: the first word or two. */
    fun chipOf(title: String): String {
        val words = title.split(' ').filter { it.isNotBlank() }
        var out = words.firstOrNull().orEmpty()
        for (w in words.drop(1)) if (out.length + 1 + w.length <= 10) out += " $w" else break
        return out.take(12)
    }

    /** The day's type from the one availability model (OOO, holidays and the office pattern live there). */
    fun dayType(date: LocalDate, i: BoardInputs): DayType =
        (i.availability[date] ?: AvailabilityModel.of(date, i.calendar.events, i.now.zone)).type

    fun officeDay(date: LocalDate, i: BoardInputs): Boolean = dayType(date, i) == DayType.WORKDAY

    private fun dayWord(date: LocalDate, i: BoardInputs): String {
        val a = i.availability[date] ?: AvailabilityModel.of(date, i.calendar.events, i.now.zone)
        return if (a.type == DayType.HOLIDAY && a.reason != null) a.reason else a.type.label
    }

    private fun eventsCovering(date: LocalDate, i: BoardInputs): List<CalItem> {
        val start = date.atStartOfDay(i.now.zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(i.now.zone).toInstant().toEpochMilli()
        return i.calendar.events.filter { it.begin < end && it.end > start }
    }

    private fun eventsOn(date: LocalDate, i: BoardInputs): List<CalItem> {
        val zone = i.now.zone
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return i.calendar.events.filter { !it.allDay && it.begin >= start && it.begin < end }
    }

    /** The kicker names the brief; the title is the day itself. */
    private fun brief(i: BoardInputs): Candidate {
        val now = i.now
        val h = now.hour
        val morning = h in 4..13
        val today = now.toLocalDate()
        val nowMs = now.toInstant().toEpochMilli()
        val startOfDay = today.atStartOfDay(now.zone).toInstant().toEpochMilli()
        val endOfDay = today.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
        val open = i.tasks.open()
        val dueToday = open.count { it.due != null && it.due < endOfDay }
        val facts = ArrayList<Fact>()
        val title: String
        val fit: Float
        val posted = i.morning?.takeIf { morning && it.date == today.toString() }
        if (posted != null) return morningBrief(posted, i)
        if (morning) {
            title = if (dayType(today, i) == DayType.OOO) "You're out of office today" else dayWord(today, i)
            fit = when (h) { in 6..9 -> 20f; in 10..13 -> 5f; else -> 0f }
            if (i.calendar.allowed) {
                val ev = eventsOn(today, i)
                facts += Fact("Calendar", if (ev.isEmpty()) "Clear" else "${count(ev.size)}, first at ${Fmt.time(ev.first().begin, now)}")
            }
            if (dueToday > 0) facts += Fact("Tasks", "$dueToday due today")
            dev.phonerecall.brain.Attention.briefLine(i.digest.map { dev.phonerecall.brain.DigestItem(title = it) })?.let { facts += Fact("Waited", it) }
            if (i.sample) facts += SampleData.morningFacts()
        } else {
            fit = when (h) { in 17..22 -> 18f; else -> 5f }
            val tomorrow = if (h < 4) today else today.plusDays(1)
            title = when (dayType(tomorrow, i)) {
                DayType.WORKDAY -> "Tomorrow is an office day"
                DayType.WEEKEND -> "Tomorrow is the weekend"
                DayType.OOO -> "You're off tomorrow"
                DayType.HOLIDAY -> "Tomorrow is ${dayWord(tomorrow, i)}"
                DayType.REMOTE -> "Tomorrow is a remote day"
            }
            if (i.calendar.allowed) {
                val done = eventsOn(today, i).count { it.end <= nowMs }
                facts += Fact("Today", if (done == 0) "No events" else "${count(done)} done")
                eventsOn(tomorrow, i).firstOrNull()?.let { facts += Fact("First", "${it.title} at ${Fmt.time(it.begin, now)}") }
            }
            val doneToday = i.tasks.tasks.count { it.list == TaskList.DONE && (it.doneAt ?: 0) >= startOfDay }
            if (doneToday > 0 || dueToday > 0) facts += Fact("Tasks", "$doneToday done, $dueToday still due")
            if (i.sample) facts += SampleData.eveningFacts()
        }
        return Candidate(
            idOf(CardKind.BRIEF), CardKind.BRIEF, learnKey(CardKind.BRIEF),
            if (morning) "Morning brief" else "Evening recap", CardContent(title = title, facts = facts), CardKind.BRIEF.tint, fit,
        )
    }

    /** The brief that went out this morning, as the top card for three hours, then an ordinary one until noon. */
    private fun morningBrief(b: dev.phonerecall.brief.MorningBrief, i: BoardInputs): Candidate {
        val nowMs = i.now.toInstant().toEpochMilli()
        val fresh = nowMs - b.postedAt < 3 * 3_600_000L
        val facts = buildList {
            b.today.forEach { add(Fact(it.label, it.text)) }
            b.yesterday.forEach { add(Fact(it.label, it.text)) }
            b.iris.forEach { add(Fact(Workers.IRIS, "${it.label}: ${it.text}")) }
            if (b.iris.isEmpty() && b.irisPending) add(Fact(Workers.IRIS, "Asked, not back yet"))
            if (b.proposals.isNotEmpty()) add(Fact("Suggested", b.proposals.joinToString("; ") { it.title }, tone = Tone.ACTIVE))
        }
        return Candidate(
            idOf(CardKind.BRIEF), CardKind.BRIEF, learnKey(CardKind.BRIEF), dev.phonerecall.ui.Place.BRIEF.myth,
            CardContent(title = b.title.substringAfter(" · ", "Today"), body = b.lede, facts = facts), CardKind.BRIEF.tint,
            timeFit = if (fresh) 20f else 8f, updatedAt = b.postedAt, dueToday = fresh,
        )
    }

    private fun count(n: Int) = if (n == 1) "1 event" else "$n events"

    // ---- generated cards ----

    private val factPattern = Regex("^([A-Za-z][A-Za-z ]{0,15}?)\\s+(\\d.*)$")

    /** Splits a brain body into prose and labelled values. "Alarm 5:15 · Leave 6:00" becomes two fact rows. */
    fun parseBody(body: String): Pair<String?, List<Fact>> {
        val prose = ArrayList<String>()
        val facts = ArrayList<Fact>()
        for (seg in body.split('\n', '·', '|').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = factPattern.matchEntire(seg)
            if (m != null) facts += Fact(m.groupValues[1].trim(), m.groupValues[2].trim()) else prose += seg
        }
        return prose.joinToString(" ").ifEmpty { null } to facts
    }

    private fun roleFor(label: String): ActionRole = when (label.lowercase()) {
        "got it", "ok", "done" -> ActionRole.ACK
        "not now", "skip", "dismiss", "later" -> ActionRole.DISMISS
        else -> ActionRole.OPEN
    }

    private fun generated(g: GeneratedCard, i: BoardInputs): Candidate {
        val (prose, facts) = parseBody(g.body)
        val sample = i.generatedAreSamples && !g.real
        val liveWorthy = g.travel || g.dueToday || g.source == CardSource.ATHENA || g.source == CardSource.SECURITY
        // "Alarm 5:15 AM, leave 6:00 AM"
        val liveText = if (facts.isEmpty()) prose.orEmpty()
        else facts.mapIndexed { idx, f -> (if (idx == 0) f.label else f.label.lowercase()) + " " + f.value }.joinToString(", ")
        val content = CardContent(
            title = g.title,
            body = prose,
            facts = facts,
            actions = g.actions.take(3).map { CardAction(it.take(14), roleFor(it)) },
            sample = sample,
            live = if (liveWorthy) LiveInfo(chipOf(g.title), g.title, liveText, sample = sample) else null,
        )
        val reason = when {
            g.source == CardSource.BANK -> "Bank alerts stay on Home."
            g.source == CardSource.SECURITY -> "Security alerts stay on Home."
            g.dueToday -> "Due today, so it stays on Home."
            else -> null
        }
        val tint = when (g.source) {
            CardSource.BANK, CardSource.SECURITY, CardSource.METRON -> Tint.ALERT
            CardSource.CALENDAR -> Tint.CALENDAR
            else -> Tint.BRAIN
        }
        // Generated cards are new by nature: recency counts from ~20 minutes ago in the sample set.
        val created = i.now.minusMinutes(20).toInstant().toEpochMilli()
        return Candidate(
            idOf(g), CardKind.GENERATED, learnKey(g), g.source.label, content, tint,
            updatedAt = created, dueToday = g.dueToday, neverHideReason = reason,
            instance = idOf(g), relevantUntil = g.expiresAt,
            alert = when (g.source) { CardSource.SECURITY -> 40f; CardSource.BANK, CardSource.METRON -> 10f; else -> 0f },
        )
    }
}
