package dev.phonerecall.rhythms

import dev.phonerecall.brain.AddressTerms
import dev.phonerecall.calls.CallRouting
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class WaCall { ONGOING, INCOMING, OUTGOING, MISSED }

/** What a WhatsApp call notification says: who, and which kind of call. Nothing else is read from it. */
data class WaCallNote(val name: String, val kind: WaCall, val video: Boolean, val group: Boolean)

/**
 * WhatsApp's call notifications. Only the fixed call lines match ("Ongoing voice call", "Missed video call",
 * "Calling…"); anything else, a message above all, returns null before its words are looked at again.
 */
object CallNotes {
    val PKGS = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val ONGOING = Regex("(?i)^ongoing (group )?(voice|video) call$")
    private val INCOMING = Regex("(?i)^incoming (group )?(voice|video) call$")
    private val OUTGOING = Regex("(?i)^(calling|ringing)(…|\\.\\.\\.)?$")
    private val MISSED = Regex("(?i)^(\\d+ )?missed (group )?(voice |video )?calls?( at .{1,12})?$")
    private val COUNT = Regex("\\s*\\(\\d+\\)$")

    fun parse(pkg: String, title: String?, text: String?): WaCallNote? {
        if (pkg !in PKGS) return null
        val t = text?.trim().orEmpty()
        val (kind, m) = when {
            ONGOING.matches(t) -> WaCall.ONGOING to ONGOING.find(t)!!
            INCOMING.matches(t) -> WaCall.INCOMING to INCOMING.find(t)!!
            OUTGOING.matches(t) -> WaCall.OUTGOING to null
            MISSED.matches(t) -> WaCall.MISSED to null
            else -> return null
        }
        val name = title?.replace(COUNT, "")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("WhatsApp", true) && !MISSED.matches(it) } ?: return null
        val video = if (m != null) m.groupValues[2].equals("video", true) else t.contains("video", true)
        val group = if (m != null) m.groupValues[1].isNotBlank() else t.contains("group", true)
        return WaCallNote(name.take(60), kind, video, group)
    }
}

/** Recurring calls, in the person's zone; the nudge's rules. Pure. */
object CallHabits {
    private const val DAY = 86_400_000L
    private const val NEAR = 30
    const val MIN_SPAN_DAYS = 7
    /** "Already called" looks back this far before the usual time. */
    const val EARLY_MS = 90 * 60_000L

    private fun minuteOf(t: ZonedDateTime) = t.hour * 60 + t.minute
    private fun offset(c: Int, m: Int): Int { val d = ((m - c) % 1440 + 1440) % 1440; return if (d > 720) d - 1440 else d }
    private fun wrap(m: Int) = ((m % 1440) + 1440) % 1440
    private fun round5(m: Double) = wrap((m / 5.0).roundToInt() * 5)
    private fun median(xs: List<Int>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }

    /** He talked, or at least tried: a connected call either way, or one he placed that wasn't answered. */
    fun counts(r: CallRecord) = r.connected || r.outgoing == true

    /** The person's zone from the countries their calls showed: India when most said +91. */
    fun zoneOf(records: List<CallRecord>, local: ZoneId): ZoneId {
        val c = records.mapNotNull { it.country }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return CallRouting.zoneFor(c, local)
    }

    private data class Pt(val date: LocalDate, val minute: Int)

    private fun best(pts: List<Pt>): Pair<Int, List<Pt>>? {
        val c = pts.map { it.minute }.distinct().maxWithOrNull(compareBy<Int> { c -> pts.filter { abs(offset(c, it.minute)) <= NEAR }.map { it.date }.toSet().size }.thenByDescending { it }) ?: return null
        val cl = pts.filter { abs(offset(c, it.minute)) <= NEAR }
        return round5(c + median(cl.map { offset(c, it.minute) })) to cl
    }

    /**
     * Calls to the same person or group at about the same time (±30 min), learned over up to four weeks:
     * daily when it happened on at least half the days (5 at least), else weekly on a weekday it held on
     * at least three weeks.
     */
    fun learn(records: List<CallRecord>, now: Long, since: Long, local: ZoneId, contactId: (String) -> Long? = { null }): List<CallHabit> {
        val from = maxOf(now - Habits.WINDOW_DAYS * DAY, since)
        val spanDays = ((now - from) / DAY).toInt()
        if (spanDays < MIN_SPAN_DAYS) return emptyList()
        val out = ArrayList<CallHabit>()
        for ((key, list) in records.filter { counts(it) && it.at in from..now }.groupBy { it.key }) {
            val zone = zoneOf(records.filter { it.key == key }, local)
            val name = list.maxBy { it.at }.name
            val pts = list.map { Instant.ofEpochMilli(it.at).atZone(zone).let { t -> Pt(t.toLocalDate(), minuteOf(t)) } }
            val (minute, cl) = best(pts) ?: continue
            val days = cl.map { it.date }.toSet().size
            if (days >= maxOf(5, ceil(spanDays * 0.5).toInt())) {
                out += CallHabit(key, name, zone.id, minute, 0, (days.toDouble() / spanDays).coerceAtMost(1.0).let { (it * 100).roundToInt() / 100.0 }, contactId(key), now)
                continue
            }
            val first = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
            val last = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            for (w in DayOfWeek.entries) {
                val (m, c) = best(pts.filter { it.date.dayOfWeek == w }) ?: continue
                val weeks = c.map { it.date }.toSet().size
                val seen = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.count { it.dayOfWeek == w }
                if (weeks >= 3 && weeks >= 0.6 * seen) out += CallHabit(key, name, zone.id, m, w.value, (weeks.toDouble() / seen).let { (it * 100).roundToInt() / 100.0 }, contactId(key), now)
            }
        }
        return out.sortedBy { it.minute }
    }

    fun zone(h: CallHabit): ZoneId = runCatching { ZoneId.of(h.zone) }.getOrDefault(ZoneId.systemDefault())

    /** The usual time on the person's [date]; null when a weekly habit isn't on that day. */
    fun on(h: CallHabit, date: LocalDate): ZonedDateTime? =
        if (!h.daily && date.dayOfWeek.value != h.weekday) null else date.atStartOfDay(zone(h)).plusMinutes(h.minute.toLong())

    /** The first usual time at or after [now]. */
    fun upcoming(h: CallHabit, now: Long): ZonedDateTime {
        val d = Instant.ofEpochMilli(now).atZone(zone(h)).toLocalDate()
        return (0L..8L).firstNotNullOf { i -> on(h, d.plusDays(i))?.takeIf { it.toInstant().toEpochMilli() >= now } }
    }

    /** The last usual time at or before [now]. */
    fun latest(h: CallHabit, now: Long): ZonedDateTime {
        val d = Instant.ofEpochMilli(now).atZone(zone(h)).toLocalDate()
        return (0L..8L).firstNotNullOf { i -> on(h, d.minusDays(i))?.takeIf { it.toInstant().toEpochMilli() <= now } }
    }

    /** Whether he already called this person around today's usual time. */
    fun calledSince(records: List<CallRecord>, key: String, target: Long, now: Long): Boolean =
        records.any { it.key == key && counts(it) && it.at >= target - EARLY_MS && it.at <= now }

    // ---- words ----

    fun abbr(zone: ZoneId, at: ZonedDateTime): String =
        if (zone.id == CallRouting.INDIA.id) "IST" else at.withZoneSameInstant(zone).format(java.time.format.DateTimeFormatter.ofPattern("zzz", Locale.US))

    /** Where it is for the person: India for an India zone, else the zone's city. */
    fun place(h: CallHabit): String = if (h.zone == CallRouting.INDIA.id) "India" else h.zone.substringAfterLast('/').replace('_', ' ')

    /** "Amma" when the saved name is a family word, so the brain never gets a real name. */
    fun term(name: String): String? = AddressTerms.relationOf(name)?.second

    /** "7:00 AM". */
    fun clock(t: ZonedDateTime): String = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US))

    /** For the Rhythms page: "Daily, about 8:00 AM IST (10:30 PM here)". */
    fun line(h: CallHabit, now: Long, local: ZoneId): String {
        val t = upcoming(h, now)
        val days = if (h.daily) "Daily" else "${DayOfWeek.of(h.weekday).getDisplayName(TextStyle.FULL, Locale.US)}s"
        val here = t.withZoneSameInstant(local)
        val tail = if (zone(h).rules.getOffset(t.toInstant()) == local.rules.getOffset(t.toInstant())) "" else " (${clock(here)} here)"
        return "$days, about ${clock(t)} ${abbr(zone(h), t)}$tail"
    }

    /** For Athena: "calls Amma daily ~8 AM IST". A name that isn't a family word becomes "someone". */
    fun brainLine(h: CallHabit, now: Long): String {
        val t = upcoming(h, now)
        val time = Habits.time(h.minute)
        return "calls ${term(h.name) ?: "someone"} ${if (h.daily) "daily" else "every " + DayOfWeek.of(h.weekday).getDisplayName(TextStyle.FULL, Locale.US)} ~$time ${abbr(zone(h), t)}"
    }

    /** The nudge's first line: "Call home?" for family, else "Call <name>?". */
    fun ask(h: CallHabit): String = if (AddressTerms.relationOf(h.name)?.first?.label == "family") "Call home?" else "Call ${h.name}?"

    /** "It's 8:00 AM in India". */
    fun nowThere(h: CallHabit, now: Long): String = "It's ${clock(Instant.ofEpochMilli(now).atZone(zone(h)))} in ${place(h)}"
}

enum class NudgeEvent { FIRE, LATER, NOT_TODAY, CALLED }
enum class PostKind { FIRST, REMINDER, SNOOZED }

/** What one event does to a day's nudge: the new state, maybe a post, maybe taking the old one down. */
data class NudgeStep(val day: NudgeDay, val post: PostKind? = null, val cancel: Boolean = false)

/**
 * The call nudge's rules. At the usual time, if he hasn't called: one notification. One reminder 45 minutes
 * later if he didn't answer it, then nothing. Never while they are usually asleep. Later waits 30 minutes (twice at most); Not today ends the day.
 * Quiet hours wait for the morning; a drive waits until it is over. Three hours past the usual time it gives up.
 */
object Nudge {
    const val REMIND_MS = 45 * 60_000L
    const val LATER_MS = 30 * 60_000L
    const val DRIVE_RECHECK_MS = 10 * 60_000L
    const val GIVE_UP_MS = 3 * 3_600_000L
    const val MAX_SNOOZES = 2

    fun step(day: NudgeDay, event: NudgeEvent, now: Long, target: Long, called: Boolean, quietUntil: Long? = null, driving: Boolean = false, asleep: Boolean = false): NudgeStep {
        if (day.stage == NudgeStage.DONE) return NudgeStep(day.copy(nextAt = 0))
        fun done(why: String, cancel: Boolean = true) = NudgeStep(day.copy(stage = NudgeStage.DONE, nextAt = 0, snoozed = false, outcome = why), cancel = cancel)
        return when (event) {
            NudgeEvent.CALLED -> done("called")
            NudgeEvent.NOT_TODAY -> done("not today")
            NudgeEvent.LATER ->
                if (day.snoozes >= MAX_SNOOZES) done("not today")
                else NudgeStep(day.copy(snoozes = day.snoozes + 1, snoozed = true, nextAt = now + LATER_MS), cancel = true)
            NudgeEvent.FIRE -> when {
                called -> done("called")
                // Never a call in their sleep window.
                asleep -> done("asleep", cancel = false)
                now > target + GIVE_UP_MS -> done(if (day.stage == NudgeStage.NONE) "too late" else "no answer", cancel = false)
                driving -> if (now + DRIVE_RECHECK_MS > target + GIVE_UP_MS) done("driving", cancel = false) else NudgeStep(day.copy(nextAt = now + DRIVE_RECHECK_MS))
                quietUntil != null -> if (quietUntil > target + GIVE_UP_MS) done("quiet hours", cancel = false) else NudgeStep(day.copy(nextAt = quietUntil))
                day.stage == NudgeStage.NONE -> NudgeStep(day.copy(stage = NudgeStage.POSTED, nextAt = now + REMIND_MS), PostKind.FIRST)
                day.snoozed -> NudgeStep(day.copy(snoozed = false, nextAt = if (day.reminded) 0 else now + REMIND_MS), PostKind.SNOOZED)
                !day.reminded -> NudgeStep(day.copy(reminded = true, nextAt = 0, outcome = "reminded"), PostKind.REMINDER)
                else -> NudgeStep(day.copy(nextAt = 0))
            }
        }
    }
}
