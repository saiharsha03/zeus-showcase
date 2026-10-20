package dev.phonerecall.rhythms

import dev.phonerecall.brain.AppKind
import dev.phonerecall.brain.AppKinds
import dev.phonerecall.brain.Topic
import dev.phonerecall.brain.Topics
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** One UsageEvents event: ACTIVITY_RESUMED (1), ACTIVITY_PAUSED (2), ACTIVITY_STOPPED (23), SCREEN_NON_INTERACTIVE (16). */
data class UEvent(val pkg: String, val type: Int, val at: Long)

/**
 * What other parts of Zeus may ask about his app habits. The alarms branch reads [bedtimeLine] for the
 * bedtime nudge; proposals that aren't urgent wait out a strong habit window ([shouldHold]).
 */
interface HabitSignal {
    fun habits(): List<AppHabit>
    /** The strong habit whose window [at] falls in, if any. */
    fun during(at: ZonedDateTime): AppHabit?
    /** "Skip Chess Arena tonight?" when tomorrow starts early and a strong evening habit is due tonight; else null. */
    fun bedtimeLine(night: LocalDate, earlyStart: Boolean): String?
    /** A non-urgent proposal at [at] should wait: it overlaps a strong habit window. */
    fun shouldHold(at: ZonedDateTime, urgent: Boolean): Boolean = !urgent && during(at) != null
}

/** Learns app habits from sessions, finds skipped ones, and says them in words. Pure. */
object Habits {
    const val DAY = 86_400_000L
    const val WINDOW_DAYS = 28
    /** Learning waits for two weeks of data, then uses up to four. */
    const val MIN_SPAN_DAYS = 14
    const val MIN_PER_WEEK = 3.0
    const val STRONG = 0.7
    private const val NEAR = 45
    /** A session before 4 AM belongs to the night before. */
    private const val NIGHT_EDGE = 240

    // ---- sessions ----

    /** Foreground stretches from raw events: a resume opens one, its pause or stop, another app or the screen going off closes it. */
    fun sessions(events: List<UEvent>, minMs: Long = 120_000L, gapMs: Long = 180_000L): List<Session> {
        val raw = ArrayList<Session>()
        var open: Pair<String, Long>? = null
        fun close(at: Long) { open?.let { (p, s) -> if (at > s) raw += Session(p, s, at) }; open = null }
        for (e in events.sortedBy { it.at }) when (e.type) {
            1 -> { if (open?.first != e.pkg) { close(e.at); open = e.pkg to e.at } }
            2, 23 -> if (open?.first == e.pkg) close(e.at)
            16 -> close(e.at)
        }
        // Still open at the end: leave it for the next scan.
        val merged = ArrayList<Session>()
        for (s in raw.sortedBy { it.start }) {
            val last = merged.lastOrNull()
            if (last != null && last.pkg == s.pkg && s.start - last.end <= gapMs) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, s.end))
            else merged += s
        }
        return merged.filter { it.end - it.start >= minMs }
    }

    // ---- learning ----

    private fun minuteOf(t: ZonedDateTime) = t.hour * 60 + t.minute
    /** Signed minutes from [c] to [m] on the clock, -720 to 720. */
    private fun offset(c: Int, m: Int): Int { val d = ((m - c) % 1440 + 1440) % 1440; return if (d > 720) d - 1440 else d }
    private fun wrap(m: Int) = ((m % 1440) + 1440) % 1440
    private fun round5(m: Double) = wrap((m / 5.0).roundToInt() * 5)
    private fun median(xs: List<Int>): Double { val s = xs.sorted(); return if (s.size % 2 == 1) s[s.size / 2].toDouble() else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0 }

    /** The evening a session belongs to: one that starts before 4 AM counts for the night before. */
    fun eveningOf(at: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(at).atZone(zone).minusMinutes(NIGHT_EDGE.toLong()).toLocalDate()

    /**
     * Habits from the last four weeks: apps used on at least 3 days a week, with a time window that held on
     * at least half of its weekdays. [since] is when Rhythms first saw data.
     */
    fun learn(
        sessions: List<Session>, now: Long, zone: ZoneId, since: Long,
        label: (String) -> String = { it }, game: (String) -> Boolean = { false }, excluded: (String) -> Boolean = { false },
    ): List<AppHabit> {
        val from = maxOf(now - WINDOW_DAYS * DAY, since)
        val spanDays = ((now - from) / DAY).toInt()
        if (spanDays < MIN_SPAN_DAYS) return emptyList()
        val firstDay = eveningOf(from, zone)
        val lastDay = eveningOf(now, zone)
        val span = generateSequence(firstDay) { it.plusDays(1) }.takeWhile { !it.isAfter(lastDay) }.toList()
        val out = ArrayList<AppHabit>()
        for ((pkg, list) in sessions.filter { it.start >= from && it.start <= now }.groupBy { it.pkg }) {
            if (excluded(pkg)) continue
            val days = list.map { eveningOf(it.start, zone) }.toSet()
            val perWeek = days.size / (spanDays / 7.0)
            if (perWeek < MIN_PER_WEEK) continue
            val starts = list.map { it to minuteOf(Instant.ofEpochMilli(it.start).atZone(zone)) }
            // The clock time with the most days near it.
            val center = starts.map { it.second }.distinct().maxWithOrNull(
                compareBy<Int> { c -> starts.filter { abs(offset(c, it.second)) <= NEAR }.map { eveningOf(it.first.start, zone) }.toSet().size }.thenByDescending { it },
            ) ?: continue
            val cluster = starts.filter { abs(offset(center, it.second)) <= NEAR }
            val clusterDays = cluster.map { eveningOf(it.first.start, zone) }.toSet()
            val byWeekday = clusterDays.groupingBy { it.dayOfWeek }.eachCount()
            val weekdays = DayOfWeek.entries.filter { w ->
                val n = byWeekday[w] ?: 0
                val seen = span.count { it.dayOfWeek == w }
                n >= 2 && n >= 0.5 * seen
            }
            if (weekdays.size < 2) continue
            val applicable = span.count { it.dayOfWeek in weekdays }
            val hit = clusterDays.count { it.dayOfWeek in weekdays }
            val strength = if (applicable == 0) 0.0 else hit.toDouble() / applicable
            if (strength < 0.5) continue
            val startMin = round5(center + median(cluster.map { offset(center, it.second) }))
            // The night's end: the last session in the window each evening, then the median of those.
            val ends = cluster.groupBy { eveningOf(it.first.start, zone) }.values.map { day ->
                val last = day.maxOf { it.first.end }
                offset(center, minuteOf(Instant.ofEpochMilli(last).atZone(zone))).let { if (it < offset(center, startMin)) it + 1440 else it }
            }
            val endMin = round5(center + median(ends))
            out += AppHabit(
                pkg, label(pkg), game(pkg), weekdays.map { it.value }, startMin, if (endMin == startMin) wrap(startMin + 30) else endMin,
                (strength * 100).roundToInt() / 100.0, (perWeek * 10).roundToInt() / 10.0, now,
            )
        }
        return out.sortedByDescending { it.strength }
    }

    /** The habit's window on evening [day], in [zone]. */
    fun windowOn(h: AppHabit, day: LocalDate, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime> {
        val startDay = if (h.startMin < NIGHT_EDGE) day.plusDays(1) else day
        val start = startDay.atStartOfDay(zone).plusMinutes(h.startMin.toLong())
        val length = ((h.endMin - h.startMin) % 1440 + 1440) % 1440
        return start to start.plusMinutes(if (length == 0) 60L else length.toLong())
    }

    /** Usual sessions that didn't happen, for evenings in [from, to] whose window is already over. Never notified. */
    fun skips(h: AppHabit, sessions: List<Session>, from: LocalDate, now: Long, zone: ZoneId): List<Skip> {
        val mine = sessions.filter { it.pkg == h.pkg }
        val today = eveningOf(now, zone)
        return generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .filter { it.dayOfWeek.value in h.days }
            .filter { d ->
                val (s, e) = windowOn(h, d, zone)
                val a = s.minusMinutes(30).toInstant().toEpochMilli(); val b = e.toInstant().toEpochMilli()
                b < now && mine.none { it.end > a && it.start < b }
            }
            .map { Skip(h.pkg, it.toString()) }.toList()
    }

    // ---- words ----

    private fun clock(m: Int): Pair<String, String> {
        val h = m / 60; val min = m % 60
        val h12 = if (h % 12 == 0) 12 else h % 12
        return (if (min == 0) "$h12" else "$h12:%02d".format(min)) to (if (h < 12) "AM" else "PM")
    }

    /** "8:45–10 PM", "11:30 PM–12:30 AM". */
    fun range(startMin: Int, endMin: Int): String {
        val (a, am) = clock(wrap(startMin)); val (b, bm) = clock(wrap(endMin))
        return if (am == bm) "$a–$b $bm" else "$a $am–$b $bm"
    }

    fun time(m: Int): String = clock(wrap(m)).let { "${it.first} ${it.second}" }

    /** "most nights", "weeknights", "weekend mornings", "Mon, Wed and Fri evenings". */
    fun days(days: List<Int>, startMin: Int): String {
        val night = startMin >= 20 * 60 || startMin < NIGHT_EDGE
        val part = when { night -> "nights"; startMin < 12 * 60 -> "mornings"; startMin < 17 * 60 -> "afternoons"; else -> "evenings" }
        val set = days.toSet()
        val week = (1..5).toSet(); val end = setOf(6, 7)
        return when {
            set.size == 7 -> "every ${part.removeSuffix("s")}"
            set == week -> if (night) "weeknights" else "weekday $part"
            set == end -> "weekend $part"
            set.size >= 5 -> "most $part"
            else -> {
                val names = days.sorted().map { DayOfWeek.of(it).name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
                (if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()) + " $part"
            }
        }
    }

    /** For the Rhythms page: "Most nights, 8:45–10 PM". */
    fun line(h: AppHabit): String = days(h.days, h.startMin).replaceFirstChar(Char::uppercase) + ", " + range(h.startMin, h.endMin)

    /** For Athena and the digest: "usually games most nights ~8:45–10 PM (Chess Arena)". */
    fun brainLine(h: AppHabit): String =
        if (h.game) "usually games ${days(h.days, h.startMin)} ~${range(h.startMin, h.endMin)} (${h.label})"
        else "usually on ${h.label} ${days(h.days, h.startMin)} ~${range(h.startMin, h.endMin)}"

    /** The habits as a [HabitSignal], in [zone]. */
    fun signal(list: List<AppHabit>, zone: ZoneId): HabitSignal = object : HabitSignal {
        override fun habits() = list
        override fun during(at: ZonedDateTime): AppHabit? {
            val t = at.withZoneSameInstant(zone)
            return list.filter { it.strong }.firstOrNull { h ->
                listOf(t.toLocalDate().minusDays(1), t.toLocalDate()).any { d ->
                    d.dayOfWeek.value in h.days && windowOn(h, d, zone).let { (s, e) -> !t.isBefore(s) && t.isBefore(e) }
                }
            }
        }
        override fun bedtimeLine(night: LocalDate, earlyStart: Boolean): String? {
            if (!earlyStart) return null
            val h = list.filter { it.strong && night.dayOfWeek.value in it.days && (it.startMin >= 18 * 60 || it.startMin < NIGHT_EDGE) }.maxByOrNull { it.strength } ?: return null
            return "Skip ${h.label} tonight?"
        }
    }
}

/**
 * Apps Rhythms never learns, by default: banking and money (Moneta's), password managers and
 * authenticators, health, dating and immigration. He can let one in or keep any other one out.
 */
object AppExclusions {
    val HEALTH = setOf(
        "com.samsung.android.shealth", "com.google.android.apps.fitness", "com.google.android.apps.healthdata", "com.fitbit.FitbitMobile",
        "com.myfitnesspal.android", "com.epic.mychart.android", "com.teladoc.members", "com.goodrx", "com.zocdoc.android",
        "org.iggymedia.periodtracker", "com.clue.android", "com.headspace.android", "com.calm.android", "com.betterhelp", "com.talkspace.talkspaceapp",
        "com.cvs.launchers.cvs", "com.walgreens.android.Walgreens", "com.samsung.android.wear.shealth",
    )
    private val SYSTEM = setOf(
        "dev.phonerecall", "com.android.systemui", "com.sec.android.app.launcher", "com.google.android.apps.nexuslauncher",
        "com.android.launcher3", "com.google.android.permissioncontroller", "com.android.settings", "com.google.android.apps.wellbeing",
    )

    /** The category word that keeps [pkg] out by default, or null when it may be learned. */
    fun category(pkg: String, label: String): String? {
        if (pkg in SYSTEM) return "system"
        when (AppKinds.of(pkg, label)) {
            AppKind.BANK -> return "money"
            AppKind.PASSWORDS, AppKind.AUTH -> return "security"
            AppKind.DATING -> return "dating"
            else -> {}
        }
        if (pkg in HEALTH) return "health"
        val t = Topics.of(label)
        return when {
            Topic.MONEY in t -> "money"
            Topic.HEALTH in t -> "health"
            Topic.IMMIGRATION in t -> "immigration"
            Topic.DATING in t -> "dating"
            else -> null
        }
    }

    fun excluded(pkg: String, label: String, s: RhythmsSettings): Boolean =
        pkg in s.excluded || (category(pkg, label) != null && pkg !in s.included)
}
