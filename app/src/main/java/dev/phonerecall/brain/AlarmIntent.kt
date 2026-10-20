package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.FESTIVAL_WORDS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/*
 * What an alarm is probably for, and what tomorrow asks of tonight's sleep. Pure: every input is passed in,
 * every rule is plain code. Nothing here calls a model, the network or the phone.
 */

/** A one-off thing for Olympus (and a notification when `notify`): a nap summary, a leave-by, a midnight wish. */
data class MomentAction(val label: String, val role: String, val payload: String? = null)

data class Moment(
    val id: String, val title: String, val body: String? = null, val facts: List<Pair<String, String>> = emptyList(),
    val actions: List<MomentAction> = emptyList(), val expiresAt: Long, val notify: Boolean = false,
)

object Days {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** The date an all-day item sits on: calendar items start at UTC midnight, contact birthdays at local midnight. */
    fun dateOf(e: CalItem, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(e.begin).atZone(if (e.allDay && e.calendarId != -1L) ZoneId.of("UTC") else zone).toLocalDate()

    fun minuteOfDay(ms: Long, zone: ZoneId): Int = Instant.ofEpochMilli(ms).atZone(zone).let { it.hour * 60 + it.minute }
    fun at(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)
    fun dur(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(0)
        return when { m < 60 -> "${m}m"; m % 60 == 0L -> "${m / 60}h"; else -> "${m / 60}h ${m % 60}m" }
    }
}

// ---- tomorrow's early start and bedtime ----

/** What forces an early start: an alarm, a flight, or the first event with a place. */
enum class EarlySource { ALARM, FLIGHT, EVENT }

data class EarlyStart(val at: Long, val source: EarlySource, val reason: String)

/**
 * Tomorrow in one value. `reasons` are the lines for the card and the notification ("Flight 8:10 AM",
 * "6 meetings tomorrow"). `why` is the single reason an unusual alarm has (Dasara...), or null.
 */
data class Plan(val date: LocalDate, val early: EarlyStart, val alarmAt: Long?, val reasons: List<String>, val why: String?) {
    val bedtime: Long get() = Bedtime.bedtime(early.at)
    /** The nudge window ends four hours before he has to be up. */
    val windowEnd: Long get() = early.at - 4 * 3_600_000L
}

object Bedtime {
    /** The 7.5 h sleep goal (Iaso's number; Iaso itself stays walled off from the brain) plus twenty minutes to wind down. */
    const val SLEEP_MIN = 450L
    const val WIND_DOWN_MIN = 20L
    /** Only starts before this hour on the day count as "early". */
    const val EARLY_BEFORE_HOUR = 10
    const val FLIGHT_LEAD_MIN = 150L
    /** When Odyssey has no leave-by for an event, assume this much travel. */
    const val DEFAULT_TRAVEL_MIN = 30L

    fun bedtime(earlyAt: Long): Long = earlyAt - (SLEEP_MIN + WIND_DOWN_MIN) * 60_000L

    /** The day he is waking into: today before 10 AM, tomorrow after. */
    fun target(now: ZonedDateTime): LocalDate = if (now.hour < EARLY_BEFORE_HOUR) now.toLocalDate() else now.toLocalDate().plusDays(1)

    private val FLIGHT = Regex("\\bflight\\b|\\b(airlines?|airways)\\b", RegexOption.IGNORE_CASE)
    fun isFlight(e: CalItem) = e.category == EventCategory.TRAVEL || FLIGHT.containsMatchIn(e.title)

    private val FESTIVAL = Regex("\\b($FESTIVAL_WORDS)\\b", RegexOption.IGNORE_CASE)

    /** The festival on `date`: a calendar title first, then the festival list the Wardrobe card uses. */
    fun festival(date: LocalDate, events: List<CalItem>, zone: ZoneId, listed: String?): String? =
        events.filter { it.allDay && Days.dateOf(it, zone) == date || !it.allDay && Days.at(it.begin, zone).toLocalDate() == date }
            .firstNotNullOfOrNull { e -> FESTIVAL.find(e.title)?.value?.replaceFirstChar { it.uppercase() } } ?: listed

    /**
     * Tomorrow's early start, or null when nothing needs him up before 10 AM (a busy day alone never makes one).
     * `leaveBy(event)` is Odyssey's leave-by for that event when it has one.
     */
    fun plan(
        date: LocalDate, zone: ZoneId, next: NextAlarm?, events: List<CalItem>, festival: String? = null,
        usualMinute: Int? = null, leaveBy: (CalItem) -> Long? = { null },
    ): Plan? {
        val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val cutoff = date.atTime(EARLY_BEFORE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
        val timed = events.filter { !it.allDay && it.category != EventCategory.BIRTHDAY && it.begin in dayStart until dayEnd }.sortedBy { it.begin }
        val flights = timed.filter { isFlight(it) }
        fun clock(ms: Long) = AlarmRules.clock(Days.minuteOfDay(ms, zone))

        val candidates = ArrayList<EarlyStart>()
        next?.takeIf { it.at in dayStart until cutoff }?.let { candidates += EarlyStart(it.at, EarlySource.ALARM, "alarm ${clock(it.at)}") }
        for (f in flights) candidates += EarlyStart(f.begin - FLIGHT_LEAD_MIN * 60_000L, EarlySource.FLIGHT, "Flight ${clock(f.begin)}")
        timed.firstOrNull { !isFlight(it) && it.place != null }?.let { e ->
            candidates += EarlyStart(leaveBy(e) ?: (e.begin - DEFAULT_TRAVEL_MIN * 60_000L), EarlySource.EVENT, "${e.title} at ${e.place}, ${clock(e.begin)}")
        }
        val early = candidates.filter { it.at < cutoff }.minByOrNull { it.at } ?: return null

        val meetings = timed.count { it.category == EventCategory.WORK }
        val hours = timed.sumOf { it.end - it.begin } / 3_600_000.0
        val busy = when {
            meetings >= 4 -> "$meetings meetings tomorrow"
            hours >= 5 -> "${Math.round(hours)} h of events tomorrow"
            else -> null
        }
        val reasons = (candidates.filter { it.at < cutoff && it.source != EarlySource.ALARM }.sortedBy { it.at }.map { it.reason } + listOfNotNull(busy)).distinct()
        val why = Alarms.unusualReason(next?.at, usualMinute, zone, festival, flights.firstOrNull(), timed.firstOrNull { !isFlight(it) && it.place != null }, busy)
        return Plan(date, early, next?.at?.takeIf { it in dayStart until cutoff }, reasons, why)
    }
}

// ---- the nudge: one, then at most one follow-up ----

data class NudgeState(
    /** The day the state is for; a new plan date starts over. */
    val night: LocalDate? = null, val sent: Int = 0, val firstAt: Long = 0, val nextAt: Long = 0, val offFor: LocalDate? = null,
    /** When the nudge was first held because a game was in the foreground; 0 when it never was. */
    val heldSince: Long = 0,
)

enum class NudgeStep { NONE, HOLD, FIRST, FOLLOW_UP }

object BedNudges {
    /** A game in the foreground holds the first nudge until he leaves it, for at most this long. */
    const val GAME_HOLD_MS = 15 * 60_000L
    /** The "one more match?" line assumes this much more play. */
    const val ONE_MORE_MIN = 25L

    fun decide(enabled: Boolean, plan: Plan?, state: NudgeState, now: Long, awake: Boolean, napping: Boolean = false, gaming: Boolean = false): NudgeStep {
        if (!enabled || plan == null || napping || !awake) return NudgeStep.NONE
        if (state.offFor == plan.date) return NudgeStep.NONE
        if (now < plan.bedtime || now > plan.windowEnd || now >= plan.early.at) return NudgeStep.NONE
        val s = if (state.night == plan.date) state else NudgeState(night = plan.date, offFor = state.offFor)
        return when {
            s.sent == 0 && gaming && (s.heldSince == 0L || now - s.heldSince < GAME_HOLD_MS) -> NudgeStep.HOLD
            s.sent == 0 -> NudgeStep.FIRST
            s.sent == 1 && now >= s.nextAt -> NudgeStep.FOLLOW_UP
            else -> NudgeStep.NONE
        }
    }

    fun after(plan: Plan, state: NudgeState, step: NudgeStep, now: Long): NudgeState {
        val s = if (state.night == plan.date) state else NudgeState(night = plan.date, offFor = state.offFor)
        return when (step) {
            NudgeStep.FIRST -> s.copy(sent = 1, firstAt = now, nextAt = now + 60 * 60_000L)
            NudgeStep.FOLLOW_UP -> s.copy(sent = 2)
            NudgeStep.HOLD -> if (s.heldSince == 0L) s.copy(heldSince = now) else s
            NudgeStep.NONE -> s
        }
    }

    /** "Snooze 30 min" moves the one follow-up to half an hour from now. It does not add a nudge. */
    fun snooze(plan: Plan, state: NudgeState, now: Long): NudgeState = after(plan, state, NudgeStep.NONE, now).copy(nextAt = now + 30 * 60_000L)

    fun notTonight(plan: Plan, state: NudgeState): NudgeState = state.copy(offFor = plan.date)

    /** Quiet words, no emoji. Returns the notification title and the reason line. */
    fun text(plan: Plan, step: NudgeStep, now: Long, zone: ZoneId, afterGame: Boolean = false): Pair<String, String?> {
        val left = Days.dur(plan.early.at - now)
        val up = when (plan.early.source) {
            EarlySource.ALARM -> "alarm ${AlarmRules.clock(Days.minuteOfDay(plan.early.at, zone))}"
            else -> "up by ${AlarmRules.clock(Days.minuteOfDay(plan.early.at, zone))}"
        }
        val title = when (step) {
            NudgeStep.FOLLOW_UP -> "$left left before your ${up.removePrefix("alarm ").removePrefix("up by ")}" + if (plan.early.source == EarlySource.ALARM) " alarm" else ""
            else -> (plan.why?.let { "$it tomorrow, $up." } ?: "Early day: $up.") + " Sleep now for $left."
        }
        val reason = (plan.reasons + listOfNotNull(if (afterGame && step == NudgeStep.FIRST) oneMoreMatch(plan, now, zone) else null)).takeIf { it.isNotEmpty() }?.joinToString(". ")
        return title to reason
    }

    /** Quiet, no lecture: what another match costs. */
    fun oneMoreMatch(plan: Plan, now: Long, zone: ZoneId): String {
        val done = now + ONE_MORE_MIN * 60_000L
        val left = plan.early.at - done
        return "One more match? Done about ${AlarmRules.clock(Days.minuteOfDay(done, zone))}, leaving ${Days.dur(left)} before ${AlarmRules.clock(Days.minuteOfDay(plan.early.at, zone))}"
    }

    /** The Olympus card: "Early day tomorrow" with what and when to sleep by. */
    fun card(plan: Plan, zone: ZoneId): Pair<String, String> {
        val parts = listOfNotNull(plan.alarmAt?.let { "alarm ${AlarmRules.clock(Days.minuteOfDay(it, zone))}" }) + plan.reasons
        val head = plan.why?.let { "$it tomorrow" } ?: "Early day tomorrow"
        val sleepBy = AlarmRules.clock(Days.minuteOfDay(plan.bedtime, zone))
        return head to (parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "$it - sleep by $sleepBy" } ?: "Sleep by $sleepBy")
    }
}

// ---- what an alarm is for ----

enum class AlarmKind { LABEL, INDIA_MIDNIGHT, LEAVE, NAP, ROUTINE, WAKE, UNKNOWN }

/** `phrase` finishes "2:30 PM - ...". `birthdays` and `event` carry what the ring-time card needs. */
data class Classified(val kind: AlarmKind, val phrase: String, val event: CalItem? = null, val leaveBy: Long? = null, val indiaDate: LocalDate? = null, val people: List<String> = emptyList())

data class IntentInput(
    val ring: Long, val setAt: Long?, val zone: ZoneId,
    /** A label Zeus itself gave the alarm. */
    val label: String? = null,
    /** True at Odyssey's home, false elsewhere, null when unknown. */
    val atHome: Boolean? = null,
    val events: List<CalItem> = emptyList(),
    /** Odyssey's leave-by times, with the event each one is for when known. */
    val leaveBys: List<Pair<CalItem?, Long>> = emptyList(),
    /** Times he has seen this alarm (same weekday, within 10 minutes), this one included. */
    val timesSeen: Int = 1,
    /** What he told Zeus this alarm is for. */
    val routineLabel: String? = null,
    /** Birthdays and anniversaries on an IST date, from the existing sources. */
    val wishes: (LocalDate) -> List<String> = { emptyList() },
)

object Alarms {
    const val NAP_FROM_HOUR = 10
    const val NAP_TO_HOUR = 19
    const val NAP_MAX_SET_MIN = 120L
    const val LEAVE_WITHIN_MIN = 90L
    const val LEAVE_NEAR_MIN = 15L
    const val ROUTINE_MIN_TIMES = 3
    const val ROUTINE_WINDOW_MIN = 10
    const val WAKE_FROM = 3
    const val WAKE_TO = 10
    private const val MIN = 60_000L

    fun isWake(ring: Long, zone: ZoneId): Boolean = Days.at(ring, zone).hour in WAKE_FROM until WAKE_TO

    /** Within ten minutes of midnight in India: the IST date that midnight starts, or null. Zone rules, never an offset. */
    fun indiaMidnight(ring: Long): LocalDate? {
        val ist = Days.at(ring, Days.IST)
        val startOfDay = ist.toLocalDate().atStartOfDay(Days.IST).toInstant().toEpochMilli()
        val nextStart = ist.toLocalDate().plusDays(1).atStartOfDay(Days.IST).toInstant().toEpochMilli()
        return when {
            abs(ring - startOfDay) <= ROUTINE_WINDOW_MIN * MIN -> ist.toLocalDate()
            abs(nextStart - ring) <= ROUTINE_WINDOW_MIN * MIN -> ist.toLocalDate().plusDays(1)
            else -> null
        }
    }

    private fun wishesPhrase(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> " (${names[0]})"
        else -> " (${names.take(3).joinToString(", ")})"
    }

    /** Precedence: label, midnight in India, leave/prep, nap, routine (with an answer), wake, routine, unknown. */
    fun classify(i: IntentInput): Classified {
        val z = i.zone
        i.label?.let { return Classified(AlarmKind.LABEL, "$it (set by Zeus)") }
        indiaMidnight(i.ring)?.let { d ->
            val people = i.wishes(d)
            return Classified(AlarmKind.INDIA_MIDNIGHT, "midnight in India" + wishesPhrase(people), indiaDate = d, people = people)
        }
        val timed = i.events.filter { !it.allDay && it.category != EventCategory.BIRTHDAY }
        val after = timed.filter { it.begin >= i.ring && it.begin <= i.ring + LEAVE_WITHIN_MIN * MIN }.minByOrNull { it.begin }
        val near = i.leaveBys.firstOrNull { abs(it.second - i.ring) <= LEAVE_NEAR_MIN * MIN }
        if (after != null || near != null) {
            val e = after ?: near?.first
            val leave = i.leaveBys.firstOrNull { it.first?.key == e?.key && e != null }?.second ?: near?.second
            val what = e?.let { "${it.title} at ${AlarmRules.clock(Days.minuteOfDay(it.begin, z))}" } ?: "a trip"
            return Classified(AlarmKind.LEAVE, "probably getting ready for $what", event = e, leaveBy = leave)
        }
        val hour = Days.at(i.ring, z).hour
        val setBefore = i.setAt?.let { (i.ring - it) / MIN }
        if (hour in NAP_FROM_HOUR until NAP_TO_HOUR && setBefore != null && setBefore in 0..NAP_MAX_SET_MIN && i.atHome != false) {
            val where = if (i.atHome == true) ", at home" else ""
            return Classified(AlarmKind.NAP, "probably a nap (set $setBefore min before$where)")
        }
        i.routineLabel?.let { return Classified(AlarmKind.ROUTINE, "$it (your usual)") }
        if (isWake(i.ring, z)) return Classified(AlarmKind.WAKE, "wake-up alarm")
        if (i.timesSeen >= ROUTINE_MIN_TIMES) return Classified(AlarmKind.ROUTINE, "a regular alarm (seen ${i.timesSeen} times)")
        return Classified(AlarmKind.UNKNOWN, "")
    }

    /** How many times an alarm like this (same weekday, within ten minutes) shows in the history, this one included. */
    fun timesSeen(ring: Long, seen: List<Long>, zone: ZoneId): Int {
        val z = Days.at(ring, zone)
        val m = z.hour * 60 + z.minute
        return (seen + ring).distinct().map { Days.at(it, zone) }.filter { it.dayOfWeek == z.dayOfWeek && abs(it.hour * 60 + it.minute - m) <= ROUTINE_WINDOW_MIN }
            .map { it.toLocalDate() }.distinct().size
    }

    /**
     * "What's your 4:00 PM alarm for?" is asked once for that time when nothing explains it: a regular alarm after the
     * third time, and an unexplained one (no event, not a nap, not India's midnight) the first time. Wake-ups never.
     */
    fun shouldAsk(c: Classified, ring: Long, zone: ZoneId, timesSeen: Int, answeredNear: Boolean, askedNear: Boolean): Boolean {
        if (answeredNear || askedNear || isWake(ring, zone)) return false
        return c.kind == AlarmKind.UNKNOWN || (c.kind == AlarmKind.ROUTINE && c.phrase.startsWith("a regular") && timesSeen >= ROUTINE_MIN_TIMES)
    }

    val ASK_PICKS = listOf("Nap", "Medicine", "Pickup", "Meeting")

    /** A saved answer or question time within ten minutes of this one. */
    fun nearMinute(minute: Int, saved: Map<Int, String>): String? = saved.entries.firstOrNull { abs(it.key - minute) <= ROUTINE_WINDOW_MIN }?.value

    /**
     * An alarm an hour or more off his usual for that weekday, with the reason when there is one. Reasons in order:
     * a festival, a flight, the first event with a place, a busy day. No reason found: null, and nothing is invented.
     */
    fun unusualReason(
        ring: Long?, usualMinute: Int?, zone: ZoneId, festival: String?, flight: CalItem?, located: CalItem?, busy: String?,
    ): String? {
        if (ring == null || usualMinute == null) return null
        val diff = Days.minuteOfDay(ring, zone) - usualMinute
        if (abs(diff) < 60) return null
        return festival ?: flight?.let { "Flight ${AlarmRules.clock(Days.minuteOfDay(it.begin, zone))}" }
            ?: located?.let { "${it.title} at ${it.place}" } ?: busy
    }

    /** "2 h earlier than usual" (or later); null under an hour. */
    fun offset(ring: Long, usualMinute: Int?, zone: ZoneId): String? {
        if (usualMinute == null) return null
        val diff = Days.minuteOfDay(ring, zone) - usualMinute
        if (abs(diff) < 60) return null
        val m = abs(diff)
        val d = if (m % 60 == 0) "${m / 60} h" else "${m / 60} h ${m % 60} min"
        return "$d ${if (diff < 0) "earlier" else "later"} than usual"
    }
}

// ---- moments built at ring time ----

object AlarmMoments {
    private fun first(name: String) = name.trim().substringBefore(' ')

    /** "It's midnight in India - wish Alex a happy birthday", with a WhatsApp action and an editable draft. */
    fun india(c: Classified, ring: Long, whatsapp: String?, call: String?): Moment {
        val names = c.people
        val title = when {
            names.isEmpty() -> "Midnight in India"
            else -> "It's midnight in India - wish ${names.joinToString(" and ") { first(it) }} a happy birthday"
        }
        val draft = names.firstOrNull()?.let { "Happy birthday ${first(it)}!" }
        val actions = listOfNotNull(
            whatsapp?.let { MomentAction("WhatsApp", "LINK", it) }, call?.let { MomentAction("Call", "LINK", it) },
            MomentAction("Done", "DONE"),
        )
        return Moment("india-$ring", title, draft?.let { "Draft: $it" }, actions = actions, expiresAt = ring + 6 * 3_600_000L, notify = true)
    }

    /** At ring: the event, leave-by and Open in Maps. */
    fun leave(c: Classified, ring: Long, zone: ZoneId, maps: String?): Moment {
        val e = c.event
        val facts = listOfNotNull(
            e?.let { "Starts" to AlarmRules.clock(Days.minuteOfDay(it.begin, zone)) },
            c.leaveBy?.let { "Leave by" to AlarmRules.clock(Days.minuteOfDay(it, zone)) },
            e?.place?.let { "At" to it },
        )
        return Moment(
            "leave-$ring", e?.title ?: "Time to get ready", facts = facts,
            actions = listOfNotNull(maps?.let { MomentAction("Open in Maps", "LINK", it) }, MomentAction("Done", "DONE")),
            expiresAt = (e?.begin ?: ring) + 30 * MIN, notify = true,
        )
    }

    /** At ring or dismiss: "Napped ~35 min - next: 3 PM call". */
    fun nap(setAt: Long?, ring: Long, zone: ZoneId, next: CalItem?): Moment {
        val mins = setAt?.let { (((ring - it) / MIN + 2) / 5 * 5).toInt() }?.takeIf { it > 0 }
        val title = "Napped" + (mins?.let { " ~$it min" } ?: "") + (next?.let { " - next: ${AlarmRules.clock(Days.minuteOfDay(it.begin, zone))} ${it.title}" } ?: "")
        return Moment("nap-$ring", title, actions = listOf(MomentAction("Done", "DONE")), expiresAt = ring + 3 * 3_600_000L)
    }

    /** Festival morning: outfit, devotional music, the India clock and a call home. Every action is his tap. */
    fun festival(
        name: String, ring: Long, outfit: String?, home: String?, callHome: String?, musicQuery: String = "Telugu devotional",
    ): Moment {
        val india = Days.at(ring, Days.IST)
        val indiaClock = AlarmRules.clock(india.hour * 60 + india.minute)
        val facts = listOfNotNull(outfit?.let { "Wear" to it }, "In India" to indiaClock)
        val actions = listOfNotNull(
            MomentAction("Play $musicQuery", "MUSIC", musicQuery),
            callHome?.let { MomentAction("Call ${home ?: "home"}", "LINK", it) },
            MomentAction("Done", "DONE"),
        )
        return Moment("festival-${Days.at(ring, ZoneId.systemDefault()).toLocalDate()}", "Happy $name", facts = facts, actions = actions, expiresAt = ring + 6 * 3_600_000L)
    }

    /** Evening, before the wish: "Alex's birthday starts at 2:30 PM your time - set an alarm?" Null if an alarm is already near it. */
    fun indiaOffer(now: ZonedDateTime, next: NextAlarm?, wishes: (LocalDate) -> List<String>): Pair<String, Long>? {
        val istTomorrow = now.withZoneSameInstant(Days.IST).toLocalDate().plusDays(1)
        val names = wishes(istTomorrow).takeIf { it.isNotEmpty() } ?: return null
        val at = istTomorrow.atStartOfDay(Days.IST)
        if (at.toInstant().toEpochMilli() <= now.toInstant().toEpochMilli()) return null
        if (next != null && abs(next.at - at.toInstant().toEpochMilli()) <= Alarms.ROUTINE_WINDOW_MIN * MIN) return null
        val local = at.withZoneSameInstant(now.zone)
        val who = names.take(2).joinToString(" and ") { first(it) } + if (names.size == 1) "'s birthday" else "'s birthdays"
        return "$who starts at ${AlarmRules.clock(local.hour * 60 + local.minute)} your time" to local.toInstant().toEpochMilli()
    }

    private const val MIN = 60_000L
}

/** One line per alarm for read_alarms. */
object AlarmWords {
    fun line(ring: Long, c: Classified, usualMinute: Int?, why: String?, zone: ZoneId): String {
        val time = AlarmRules.clock(Days.minuteOfDay(ring, zone))
        val off = if (c.kind == AlarmKind.WAKE) Alarms.offset(ring, usualMinute, zone) else null
        return when {
            off != null -> "$time, $off" + (why?.let { " - $it" } ?: "")
            c.phrase.isNotEmpty() -> "$time - ${c.phrase}"
            else -> time
        }
    }
}

// ---- late fixtures: wake, catch up, or skip ----

/** A followed fixture, as the brain sees it. Olympia's Match is adapted in the alarms package. */
data class Fixture(val id: String, val title: String, val start: Long, val lengthMs: Long)

/** What each choice means for tonight's bedtime. */
data class SportTrade(val fixture: Fixture, val wakeBed: Long, val catchUpBed: Long, val skipBed: Long, val backInBed: Long)

object SportSleep {
    private const val NIGHT_FROM = 23
    private const val NIGHT_TO = 7
    private const val HORIZON_MS = 14 * 3_600_000L
    /** Sleeping before a wake-up for the game: about this long. */
    private const val SLEEP_BEFORE_MS = 4 * 3_600_000L

    /** The next fixture that starts between 23:00 and 07:00, or overlaps sleep before an early start. */
    fun pick(fixtures: List<Fixture>, plan: Plan?, now: Long, zone: ZoneId, answered: Set<String> = emptySet()): Fixture? =
        fixtures.filter { it.id !in answered && it.start - now in 30 * 60_000L..HORIZON_MS }.filter { f ->
            val h = Days.at(f.start, zone).hour
            h >= NIGHT_FROM || h < NIGHT_TO || (plan != null && f.start >= plan.bedtime && f.start < plan.early.at)
        }.minByOrNull { it.start }

    fun trade(f: Fixture, plan: Plan?, zone: ZoneId): SportTrade {
        val z = Days.at(f.start, zone)
        val eveningOf = if (z.hour >= NIGHT_FROM) z.toLocalDate() else z.toLocalDate().minusDays(1)
        val base = plan?.bedtime ?: eveningOf.atTime(NIGHT_FROM, 0).atZone(zone).toInstant().toEpochMilli()
        return SportTrade(f, wakeBed = minOf(base, f.start - SLEEP_BEFORE_MS), catchUpBed = base, skipBed = base, backInBed = f.start + f.lengthMs)
    }

    fun body(t: SportTrade, zone: ZoneId): String {
        fun c(ms: Long) = AlarmRules.clock(Days.minuteOfDay(ms, zone))
        return "Wake: bed by ${c(t.wakeBed)}, back in bed about ${c(t.backInBed)}. Catch up after: bed by ${c(t.catchUpBed)}. Skip: bed by ${c(t.skipBed)}."
    }
}
