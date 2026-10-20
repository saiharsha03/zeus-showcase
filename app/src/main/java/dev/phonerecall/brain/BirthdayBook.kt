package dev.phonerecall.brain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Where Zeus saw a birthday. Google Calendar's birthday events reach the phone through its calendar sync. */
enum class BirthdayFrom(val word: String) { CONTACTS("Contacts"), CALENDAR("Calendar"), WHATSAPP("WhatsApp wishes") }

/** One sighting: [name]'s birthday on [date], her own calendar date. With [india], her day runs on India's clock. */
data class BirthdaySeen(val name: String, val date: LocalDate, val from: BirthdayFrom, val india: Boolean = false, val thread: String? = null)

/** One person's birthday, however many sources saw it. Her day runs from [starts] to [ends] (epoch ms). */
data class DayBirthday(
    val key: String,
    val name: String,
    val date: LocalDate,
    val india: Boolean,
    val from: Set<BirthdayFrom>,
    val threads: Set<String>,
    val starts: Long,
    val ends: Long,
) {
    /** One person, one day: what "done" and "pushed" are kept under. */
    val id: String get() = "$key@$date"
}

/** What Olympus, the brief and the widget show: the birthdays whose day is on now, and every one already started (shown, done or over). */
data class BirthdayBoard(val active: List<DayBirthday> = emptyList(), val covered: Set<String> = emptySet())

/**
 * Every birthday, whatever the source (Contacts, his calendars, WhatsApp wishes), merged to one person per day
 * before anything shows. Her day is the deadline: midnight to midnight in India for India people, in his own
 * zone otherwise. At most one push per birthday: India at the start of her day (his afternoon the day before),
 * everyone else at 9 AM his time on the day; never in his quiet hours (11 PM to 7 AM), where it moves to 7 AM,
 * or to the card only when her day is over by then. Pure: the Android side ([BirthdayDay]) feeds and acts on it.
 */
object BirthdayBook {
    val IST: ZoneId = TwoClocks.INDIA
    const val PUSH_HOUR = 9
    const val QUIET_FROM = 23
    const val QUIET_TO = 7
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** The same person across sources: the first name, lower case, letters only ("Sam Garu" and "sam" agree). */
    fun keyOf(name: String): String = name.trim().split(Regex("\\s+")).first().lowercase().filter { it.isLetter() }

    /** The name in a birthday event's title ("Sam's birthday", "Birthday: Alex", "Mom bday"). */
    fun nameOf(title: String): String =
        Validate.birthdayOf(title) ?: title.replace(Regex("(?i)['’]s\\s+birthday|\\s+birthday|birthday:?\\s*|\\s*b-?day"), "").replace(Regex("[^\\p{L}' -]"), "").trim()

    /** Her day, start and end, as epoch ms. */
    fun window(date: LocalDate, india: Boolean, home: ZoneId): Pair<Long, Long> {
        val z = if (india) IST else home
        return date.atStartOfDay(z).toInstant().toEpochMilli() to date.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
    }

    /**
     * One entry per person and day. A WhatsApp wish dated a day off from what Contacts or a calendar say (the
     * wishes come on her day, which for India starts in his afternoon) joins that entry instead of making another.
     */
    fun merge(seen: List<BirthdaySeen>, home: ZoneId): List<DayBirthday> {
        val out = ArrayList<DayBirthday>()
        for ((key, all) in seen.filter { keyOf(it.name).isNotEmpty() }.groupBy { keyOf(it.name) }) {
            val (wa, dated) = all.partition { it.from == BirthdayFrom.WHATSAPP }
            val days = dated.groupBy { it.date }.toMutableMap()
            for (w in wa) {
                val near = days.keys.firstOrNull { it == w.date } ?: days.keys.firstOrNull { kotlin.math.abs(it.toEpochDay() - w.date.toEpochDay()) <= 1 }
                days[near ?: w.date] = days[near ?: w.date].orEmpty() + w
            }
            for ((date, s) in days) {
                val india = s.any { it.india }
                val name = s.minByOrNull { it.from.ordinal }!!.name.trim()
                val (a, b) = window(date, india, home)
                out += DayBirthday(key, name, date, india, s.map { it.from }.toSet(), s.mapNotNull { it.thread }.toSet(), a, b)
            }
        }
        return out.sortedBy { it.starts }
    }

    /** What shows now: the ones whose day is on and that he hasn't wished; [BirthdayBoard.covered] holds every one already started. */
    fun board(all: List<DayBirthday>, now: Long, done: Set<String>): BirthdayBoard = BirthdayBoard(
        active = all.filter { it.starts <= now && now < it.ends && it.id !in done },
        covered = all.filter { it.starts <= now }.map { it.id }.toSet(),
    )

    /** When the one push goes out, or null when there is none (her day ends before his quiet hours do). */
    fun pushAt(b: DayBirthday, home: ZoneId, now: Long = b.starts): Long? {
        val base = if (b.india) b.starts else b.date.atTime(PUSH_HOUR, 0).atZone(home).toInstant().toEpochMilli()
        var t = Instant.ofEpochMilli(maxOf(base, now)).atZone(home)
        if (t.hour >= QUIET_FROM) t = t.toLocalDate().plusDays(1).atTime(QUIET_TO, 0).atZone(home)
        else if (t.hour < QUIET_TO) t = t.toLocalDate().atTime(QUIET_TO, 0).atZone(home)
        val at = t.toInstant().toEpochMilli()
        return at.takeIf { it < b.ends }
    }

    /** The push decision: the ones to post now, and when to look again. A birthday he wished, or one already pushed, never pushes. */
    fun due(all: List<DayBirthday>, home: ZoneId, now: Long, done: Set<String>, pushed: Set<String>): Pair<List<DayBirthday>, Long?> {
        val post = ArrayList<DayBirthday>()
        var next: Long? = null
        for (b in all) {
            if (b.id in done || b.id in pushed || b.ends <= now) continue
            val at = pushAt(b, home, now) ?: continue
            if (at <= now) post += b else next = minOf(next ?: at, at)
        }
        return post to next
    }

    /** When her day ends: "in India at 2:30 PM your time", or "at midnight tonight". */
    fun deadline(b: DayBirthday, home: ZoneId): String =
        if (b.india) "in India at ${TIME.format(Instant.ofEpochMilli(b.ends).atZone(home))} your time" else "at midnight tonight"

    /** The push's line: "Ends in India at 2:30 PM your time". */
    fun endsText(b: DayBirthday, home: ZoneId): String = "Ends ${deadline(b, home)}"

    /** The push: "Sam's birthday starts now in India", or "Sam's birthday today". */
    fun pushTitle(b: DayBirthday): String = if (b.india) "${b.name}'s birthday starts now in India" else "${b.name}'s birthday today"

    /** The brief's one line: "Sam's birthday (India, ends 2:30 PM)", "(India, starts 2:30 PM)" before it begins, or just the name's birthday. */
    fun briefLine(b: DayBirthday, home: ZoneId, now: Long): String = when {
        !b.india -> "${b.name}'s birthday"
        b.starts > now -> "${b.name}'s birthday (India, starts ${TIME.format(Instant.ofEpochMilli(b.starts).atZone(home))})"
        else -> "${b.name}'s birthday (India, ends ${TIME.format(Instant.ofEpochMilli(b.ends).atZone(home))})"
    }

    /** The ones for the brief: on now, or starting later today his time, and not wished. */
    fun forBrief(all: List<DayBirthday>, home: ZoneId, now: Long, done: Set<String>): List<DayBirthday> {
        val endToday = Instant.ofEpochMilli(now).atZone(home).toLocalDate().plusDays(1).atStartOfDay(home).toInstant().toEpochMilli()
        return all.filter { it.ends > now && it.starts < endToday && it.id !in done }
    }

    /** A message he sent on [thread], or to [name], ends that birthday's nudges: the ones it matches among [active]. */
    fun wishedBy(active: List<DayBirthday>, thread: String?, name: String?): List<DayBirthday> =
        active.filter { b -> (thread != null && thread in b.threads) || (name != null && keyOf(name) == b.key) }
}
