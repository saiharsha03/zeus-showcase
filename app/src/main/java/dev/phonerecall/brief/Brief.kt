package dev.phonerecall.brief

import dev.phonerecall.brain.ProposalKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Eos (plain name "Morning brief"), Athena's morning brief: assembled on the phone when he dismisses the alarm (or first unlocks after 5 AM), posted
 * once as one notification and shown as the top card on Olympus. Works with no model at all; the mind may only
 * add a lede line. Iris's part arrives over the Zeus channel and is added when it comes (docs/BRIEF.md).
 */
data class BriefLine(val label: String = "", val text: String = "")

/** A proposal the brief offers. Filed through Fates, so it is validated and confirmed like any other. */
data class BriefProposal(val title: String = "", val kind: String = "", val args: Map<String, String> = emptyMap())

data class MorningBrief(
    val date: String = "",
    val postedAt: Long = 0,
    val title: String = "",
    val lede: String? = null,
    val today: List<BriefLine> = emptyList(),
    val yesterday: List<BriefLine> = emptyList(),
    val iris: List<BriefLine> = emptyList(),
    /** Asked and not answered yet: the card says so, and her part is added when it arrives. */
    val irisPending: Boolean = false,
    val proposals: List<BriefProposal> = emptyList(),
)

/** Interfaces the sibling branches fill in. Every one is optional: null means "not known", never an error. */
data class AlarmInfo(val at: Long, val reason: String? = null, val unusual: Boolean = false)

fun interface AlarmWatch {
    /** Today's first alarm, or null when none is set (or the alarms feature isn't there). */
    fun today(date: LocalDate): AlarmInfo?
}

fun interface WakeTime {
    /** The wake time learned for this date, or null. */
    fun learned(date: LocalDate): LocalTime?
}

object BriefHooks {
    @Volatile var alarms: AlarmWatch? = null
    @Volatile var wake: WakeTime? = null
}

/** Something dated in India: a festival or a birthday. Its day starts at midnight IST, which is the afternoon before here. */
data class Festive(val name: String, val istDate: LocalDate, val birthday: Boolean = false)

data class TimedItem(val title: String, val at: Long, val place: String? = null)

data class LeaveBy(val destination: String, val at: Long, val minutes: Int)

/** Everything the brief can use. Any of it may be missing. */
data class BriefSources(
    val now: ZonedDateTime,
    val dayWord: String? = null,
    val weather: String? = null,
    val weatherNext: String? = null,
    val weatherAlert: String? = null,
    val events: List<TimedItem> = emptyList(),
    val leaveBy: LeaveBy? = null,
    val outfit: String? = null,
    val festive: List<Festive> = emptyList(),
    /** One line per birthday on today (brain/BirthdayBook): "Sam's birthday (India, ends 2:30 PM)". */
    val birthdays: List<String> = emptyList(),
    val matches: List<TimedItem> = emptyList(),
    val alarm: AlarmInfo? = null,
    val screen: ScreenDay? = null,
    /** Iaso's one derived line ("slept 7h; steps on track"), only when he shares it. */
    val health: String? = null,
    val iris: IrisSection? = null,
    val irisAsked: Boolean = false,
    val lede: String? = null,
    /** Argo's trip lines and the day's milestones. */
    val trip: List<String> = emptyList(),
    val milestones: List<String> = emptyList(),
    /** Praxis: "Weekly review due", once per review cycle. */
    val review: String? = null,
)

object BriefAssembler {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val day = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)

    private fun at(ms: Long, zone: ZoneId) = time.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun build(s: BriefSources): MorningBrief {
        val zone = s.now.zone
        val nowMs = s.now.toInstant().toEpochMilli()
        val today = ArrayList<BriefLine>()
        val w = listOfNotNull(s.weather, s.weatherNext).joinToString(" · ")
        if (w.isNotEmpty()) today += BriefLine("Weather", w)
        s.weatherAlert?.let { today += BriefLine("Alert", it) }
        val ahead = s.events.filter { it.at >= nowMs - 15 * 60_000L }.sortedBy { it.at }
        ahead.firstOrNull()?.let { e ->
            val more = if (ahead.size > 1) " (+${ahead.size - 1} more)" else ""
            today += BriefLine("First", "${e.title} at ${at(e.at, zone)}$more")
        }
        s.leaveBy?.takeIf { it.at > nowMs - 30 * 60_000L }?.let { today += BriefLine("Leave by", "${at(it.at, zone)} for ${it.destination} (${it.minutes} min)") }
        s.outfit?.let { today += BriefLine("Wear", it) }
        for (f in festive(s)) today += f
        for (b in s.birthdays.take(3)) today += BriefLine("Birthday", b)
        for (m in s.milestones.take(2)) today += BriefLine("Milestone", m)
        for (t in s.trip.take(2)) today += BriefLine("Trip", t)
        for (m in s.matches.filter { it.at > nowMs - 2 * 3_600_000L }.sortedBy { it.at }.take(2)) today += BriefLine("Match", "${m.title} at ${at(m.at, zone)}")
        s.review?.let { today += BriefLine("Review", it) }
        s.alarm?.takeIf { it.unusual && !it.reason.isNullOrBlank() }?.let { today += BriefLine("Alarm", "${at(it.at, zone)}: ${it.reason}") }

        val yesterday = ArrayList<BriefLine>()
        s.screen?.takeIf { it.totalMin > 0 }?.let { d ->
            yesterday += BriefLine("Screen", ScreenTime.line(d))
            ScreenTime.lateLine(d, zone)?.let { yesterday += BriefLine("Last night", it) }
        }
        s.health?.takeIf { it.isNotBlank() }?.let { yesterday += BriefLine("Body", it.replaceFirstChar { c -> c.uppercase() }) }

        return MorningBrief(
            date = s.now.toLocalDate().toString(),
            postedAt = nowMs,
            title = s.dayWord?.let { "${day.format(s.now)} · $it" } ?: day.format(s.now),
            lede = s.lede?.trim()?.takeIf { it.isNotEmpty() }?.take(140) ?: lede(s, ahead),
            today = today,
            yesterday = yesterday,
            iris = s.iris?.lines.orEmpty(),
            irisPending = s.irisAsked && s.iris == null,
            proposals = proposals(s, ahead),
        )
    }

    /** The on-device opener: plain and short, never a model's. */
    private fun lede(s: BriefSources, ahead: List<TimedItem>): String {
        val zone = s.now.zone
        val first = ahead.firstOrNull()
        return when {
            s.weatherAlert != null -> "A weather alert is out. Details below."
            s.leaveBy != null -> "Leave by ${at(s.leaveBy.at, zone)} for ${s.leaveBy.destination}."
            first != null -> "${first.title} is first, at ${at(first.at, zone)}."
            else -> "Nothing on the calendar today."
        }
    }

    /** Festivals and birthdays dated in India: today's there, or tomorrow's there that starts later today here. */
    fun festive(s: BriefSources): List<BriefLine> {
        val zone = s.now.zone
        val nowMs = s.now.toInstant().toEpochMilli()
        val endToday = s.now.toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return s.festive.distinctBy { it.name.lowercase() to it.istDate }.mapNotNull { f ->
            val start = f.istDate.atStartOfDay(IST).toInstant().toEpochMilli()
            val end = f.istDate.plusDays(1).atStartOfDay(IST).toInstant().toEpochMilli()
            if (end <= nowMs || start >= endToday) return@mapNotNull null
            val local = Instant.ofEpochMilli(start).atZone(zone)
            val whenText = when {
                start <= nowMs -> "in India since ${time.format(local)}" + (if (local.toLocalDate().isBefore(s.now.toLocalDate())) " yesterday" else "") + " your time"
                else -> "in India from ${time.format(local)} today your time"
            }
            BriefLine(if (f.birthday) "Birthday" else "Festival", "${f.name}, $whenText")
        }.take(3)
    }

    /** Up to three: leave-by (a timer when it's close, else a reminder), a call home on a festival or birthday, a match. */
    fun proposals(s: BriefSources, ahead: List<TimedItem>): List<BriefProposal> {
        val zone = s.now.zone
        val nowMs = s.now.toInstant().toEpochMilli()
        val out = ArrayList<BriefProposal>()
        s.leaveBy?.let { l ->
            val mins = ChronoUnit.MINUTES.between(s.now.toInstant(), Instant.ofEpochMilli(l.at))
            when {
                mins in 5..90 -> out += BriefProposal("Timer to leave for ${l.destination}", ProposalKind.SET_TIMER.wire, mapOf("minutes" to "$mins", "label" to "Leave for ${l.destination}"))
                mins > 90 -> out += BriefProposal("Reminder to leave for ${l.destination}", ProposalKind.ADD_REMINDER.wire, mapOf("at" to iso(l.at - 10 * 60_000L, zone), "text" to "Leave for ${l.destination} by ${at(l.at, zone)}"))
            }
        }
        if (s.festive.any { val st = it.istDate.atStartOfDay(IST).toInstant().toEpochMilli(); st <= nowMs + 12 * 3_600_000L && it.istDate.plusDays(1).atStartOfDay(IST).toInstant().toEpochMilli() > nowMs }) {
            callHomeAt(s.now)?.let { t ->
                val why = s.festive.first().name
                out += BriefProposal("Call home ($why)", ProposalKind.ADD_REMINDER.wire, mapOf("at" to iso(t, zone), "text" to "Call home: $why"))
            }
        }
        s.matches.filter { it.at - 10 * 60_000L > nowMs + 60_000L }.minByOrNull { it.at }?.let { m ->
            out += BriefProposal("Reminder before ${m.title}", ProposalKind.ADD_REMINDER.wire, mapOf("at" to iso(m.at - 10 * 60_000L, zone), "text" to "${m.title} starts at ${at(m.at, zone)}"))
        }
        return out.take(3)
    }

    /** A sensible time to call India: the next half hour from now, no earlier than 7:30 AM here and before 10 PM there. */
    fun callHomeAt(now: ZonedDateTime): Long? {
        val base = now.plusMinutes(30).truncatedTo(ChronoUnit.MINUTES)
        var t = base.truncatedTo(ChronoUnit.HOURS).plusMinutes(when { base.minute == 0 -> 0L; base.minute <= 30 -> 30L; else -> 60L })
        val earliest = now.toLocalDate().atTime(7, 30).atZone(now.zone)
        if (t.isBefore(earliest)) t = earliest
        val there = t.withZoneSameInstant(IST)
        return if (there.hour in 8..21) t.toInstant().toEpochMilli() else null
    }

    private fun iso(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString()

    // ---- what goes out ----

    /** The notification: a one-line summary, then every section as plain lines. */
    fun notification(b: MorningBrief, plain: Boolean = false): Pair<String, String> {
        val big = buildString {
            b.lede?.let { appendLine(it) }
            fun section(name: String, lines: List<BriefLine>) {
                if (lines.isEmpty()) return
                appendLine(); appendLine(name)
                lines.forEach { appendLine("${it.label}: ${it.text}") }
            }
            section("Today", b.today)
            section("Yesterday", b.yesterday)
            if (b.iris.isNotEmpty()) section("From Iris", b.iris) else if (b.irisPending) { appendLine(); appendLine("From Iris: asked, not back yet") }
            if (b.proposals.isNotEmpty()) { appendLine(); appendLine("Suggested: " + b.proposals.joinToString("; ") { it.title }) }
        }.trim()
        val name = if (plain) dev.phonerecall.ui.Place.BRIEF.plain else dev.phonerecall.ui.Place.BRIEF.myth
        return "$name · ${b.title.substringBefore(",")}" to big
    }

    /** For the read-out: the lede and Today only, as sentences. Names and Iris's part stay on the screen. */
    fun speech(b: MorningBrief): String = buildString {
        append("Good morning. ")
        b.lede?.let { append(it.trimEnd('.')).append(". ") }
        for (l in b.today.filter { it.label in setOf("Weather", "First", "Leave by", "Wear", "Match", "Festival") }) append("${l.label}: ${l.text}. ")
    }.replace(" · ", ", ").trim()
}

/** When the brief may go out. Pure, so the once-a-day and quiet rules are tested. */
object BriefGate {
    enum class Verdict { POST, ALREADY, TOO_EARLY, HOLD_DRIVING, OFF, WAIT_ALARM, NEAR_IRIS }

    /** Eos and Iris's Telegram morning briefing never land within this many minutes of each other. */
    const val IRIS_GAP_MIN = 30L

    /** Minutes to hold so Eos lands 30 minutes after Iris's briefing; 0 when clear of it or her time is unknown. */
    fun irisHold(now: ZonedDateTime, irisSlot: LocalTime?): Long {
        val slot = irisSlot?.let { now.toLocalDate().atTime(it).atZone(now.zone) } ?: return 0
        val mins = java.time.Duration.between(slot, now).toMinutes()
        return if (mins > -IRIS_GAP_MIN && mins < IRIS_GAP_MIN) IRIS_GAP_MIN - mins else 0
    }

    fun check(on: Boolean, lastPosted: String?, now: ZonedDateTime, driving: Boolean, viaAlarm: Boolean, alarm: AlarmInfo?, irisSlot: LocalTime? = null): Verdict = when {
        !on -> Verdict.OFF
        lastPosted == now.toLocalDate().toString() -> Verdict.ALREADY
        !viaAlarm && now.hour < 5 -> Verdict.TOO_EARLY
        // An alarm still to ring today: the unlock fallback waits for it.
        !viaAlarm && alarm != null && alarm.at > now.toInstant().toEpochMilli() -> Verdict.WAIT_ALARM
        // Morning only: after noon there's nothing "morning" left to tell.
        now.hour >= 12 -> Verdict.ALREADY
        driving -> Verdict.HOLD_DRIVING
        irisHold(now, irisSlot) > 0 -> Verdict.NEAR_IRIS
        else -> Verdict.POST
    }

    /** Quiet hours (11 PM to 7 AM): the brief still goes out, without sound. */
    fun silent(now: ZonedDateTime): Boolean = dev.phonerecall.brain.QuietHours.quiet(now)

    /** When to ask Iris: 45 minutes before the learned wake time, else 5:30 AM. Never before 3:30 AM. */
    fun requestAt(date: LocalDate, wake: LocalTime?): LocalTime {
        val t = wake?.minusMinutes(45) ?: LocalTime.of(5, 30)
        return if (t.isBefore(LocalTime.of(3, 30)) || (wake != null && t.isAfter(wake))) LocalTime.of(3, 30) else t
    }
}
