package dev.phonerecall.theogony

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Personal special days (like the day the car arrived): got the car, moved to Buffalo,
 * started the job, graduated, important family dates. Kept only when Iris read it from email or owner.db, or
 * he said so. `date` is ISO, a full date (yyyy-MM-dd) or a month (yyyy-MM).
 */
data class Milestone(val label: String = "", val date: String = "", val status: String = Status.CONFIRMED, val source: String = "") {
    val full: LocalDate? get() = runCatching { LocalDate.parse(date) }.getOrNull()
}

object MilestoneText {
    private val MONTHS = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.US).lowercase() } +
        Month.entries.associateBy { it.getDisplayName(TextStyle.FULL, Locale.US).lowercase() }

    /** A date in his words or Iris's: 2024-05-20, 2024-05, May 20 2024, May 2024, 5/20/2024, 5/2024. */
    fun date(s: String): Pair<String, IntRange>? {
        Regex("\\b(19|20)\\d{2}-(0[1-9]|1[0-2])(-(0[1-9]|[12]\\d|3[01]))?\\b").find(s)?.let { return it.value to it.range }
        Regex("(?i)\\b([a-z]{3,9})\\.?\\s+(\\d{1,2})(st|nd|rd|th)?,?\\s+((?:19|20)\\d{2})\\b").find(s)?.let { m ->
            val mo = MONTHS[m.groupValues[1].lowercase()] ?: return@let
            return runCatching { LocalDate.of(m.groupValues[4].toInt(), mo, m.groupValues[2].toInt()).toString() }.getOrNull()?.let { it to m.range }
        }
        Regex("(?i)\\b([a-z]{3,9})\\.?,?\\s+((?:19|20)\\d{2})\\b").find(s)?.let { m ->
            val mo = MONTHS[m.groupValues[1].lowercase()] ?: return@let
            return "%04d-%02d".format(m.groupValues[2].toInt(), mo.value) to m.range
        }
        Regex("\\b(\\d{1,2})/(\\d{1,2})/((?:19|20)\\d{2})\\b").find(s)?.let { m ->
            return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt()).toString() }.getOrNull()?.let { it to m.range }
        }
        Regex("\\b(\\d{1,2})/((?:19|20)\\d{2})\\b").find(s)?.let { m ->
            val mo = m.groupValues[1].toInt().takeIf { it in 1..12 } ?: return null
            return "%04d-%02d".format(m.groupValues[2].toInt(), mo) to m.range
        }
        return null
    }

    /** "Got the car, May 2024; Moved to Buffalo, Mar 2025" (his answer) or "2024-05-20 | Leased the car" (Iris). */
    fun parse(text: String): List<Pair<String, String>> = text.split(';', '\n').mapNotNull { part ->
        val (d, range) = date(part) ?: return@mapNotNull null
        val label = part.removeRange(range).replace("|", " ").trim().trim(',', ':', '-', '–', ' ').replace(Regex("\\s{2,}"), " ")
        label.takeIf { it.length >= 3 }?.let { it.replaceFirstChar { c -> c.uppercase() } to d }
    }

    fun shown(m: Milestone): String {
        val f = m.full
        val ym = m.date.split('-')
        val month = ym.getOrNull(1)?.toIntOrNull()?.let { Month.of(it).getDisplayName(TextStyle.SHORT, Locale.US) }.orEmpty()
        return if (f != null) "$month ${f.dayOfMonth}, ${f.year}" else "$month ${ym[0]}"
    }

    private val PAST = Regex("(?i)^(got|moved|started|bought|leased|graduated|joined|began|met|married|landed|arrived|signed|passed|finished)\\b")

    /** "1 year since you got the car", or null when today is not its day (month-only dates have no day). */
    fun anniversary(m: Milestone, today: LocalDate): String? {
        val d = m.full ?: return null
        if (d.monthValue != today.monthValue || d.dayOfMonth != today.dayOfMonth) return null
        val years = today.year - d.year
        if (years < 1) return null
        val what = m.label.trim().trimEnd('.')
        val since = if (PAST.containsMatchIn(what)) "you " + what.replaceFirstChar { it.lowercase() } else what.replaceFirstChar { it.lowercase() }
        return "$years ${if (years == 1) "year" else "years"} since $since"
    }

    /** The milestone kinds the interview may ask about, with the words that mean Iris already knows one. */
    val KINDS = listOf(
        "Got the car" to Regex("(?i)\\b(car|lease|leased|vehicle)\\b"),
        "Moved to Buffalo" to Regex("(?i)\\b(moved|buffalo|apartment|move-in)\\b"),
        "Started the job" to Regex("(?i)\\b(job|joined|started at|work anniversary)\\b"),
        "Graduated" to Regex("(?i)\\b(graduat\\w*|degree|convocation)\\b"),
    )
}

/**
 * The read for Chronos (a quiet all-day line on the day) and Eos. Confirmed milestones only. `notify` is his
 * opt-in for a notification on the day; off unless he turns it on.
 */
class Milestones(private val all: List<Milestone>, val notify: Boolean = false) {
    fun confirmed(): List<Milestone> = all.filter { it.status == Status.CONFIRMED }
    fun on(today: LocalDate): List<String> = confirmed().mapNotNull { MilestoneText.anniversary(it, today) }

    companion object { val EMPTY = Milestones(emptyList()) }
}
