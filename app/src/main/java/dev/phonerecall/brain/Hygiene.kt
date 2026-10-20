package dev.phonerecall.brain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Task hygiene. Items can carry a "relevant until". Past it, a seasonal or event-bound item
 * moves to Lethe on its own (tier 1, logged, Undo per item). Never auto-closed: money, immigration,
 * health, anything waiting on another person, and pinned items. Deadline-bound or protected items
 * that are past due but still possible get one last-chance card instead.
 */

/** Why an item is never closed automatically. */
enum class Protection(val word: String) { MONEY("money"), IMMIGRATION("immigration"), HEALTH("health"), WAITING("waiting on someone"), PINNED("pinned"), WORKER("a worker's task") }

/** One task or list item as hygiene sees it. */
data class HygieneItem(
    val title: String,
    val note: String = "",
    val until: Long?,
    val kind: RelevanceKind?,
    val reason: String? = null,
    val due: Long? = null,
    val waitingList: Boolean = false,
    val pinned: Boolean = false,
    val workerOwned: Boolean = false,
    val lastChanceShown: Boolean = false,
)

sealed class HygieneCall {
    data object Keep : HygieneCall()
    data class ToLethe(val reason: String) : HygieneCall()
    data class LastChance(val reason: String) : HygieneCall()
}

object Hygiene {
    private val WAITING = Regex("(?i)\\b(waiting (on|for)|wait for|ask(ed)? \\p{L}+ (to|about|for)|follow up with|hear back|heard back|reply from|until \\p{L}+ (replies|responds|sends))\\b")
    private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun protections(i: HygieneItem): Set<Protection> = buildSet {
        val text = i.title + " " + i.note
        val topics = Topics.of(text)
        if (Topic.MONEY in topics) add(Protection.MONEY)
        if (Topic.IMMIGRATION in topics) add(Protection.IMMIGRATION)
        if (Topic.HEALTH in topics) add(Protection.HEALTH)
        if (i.waitingList || WAITING.containsMatchIn(text)) add(Protection.WAITING)
        if (i.pinned) add(Protection.PINNED)
        if (i.workerOwned) add(Protection.WORKER)
    }

    fun decide(i: HygieneItem, now: Long, zone: ZoneId): HygieneCall {
        val until = i.until ?: return HygieneCall.Keep
        val protectedBy = protections(i)
        if (now >= until) {
            if (protectedBy.isNotEmpty() || i.kind == RelevanceKind.DEADLINE) {
                return if (i.lastChanceShown) HygieneCall.Keep
                else HygieneCall.LastChance(if (protectedBy.isNotEmpty()) "Its window passed, but it's about ${protectedBy.first().word}, so it stays open." else "The deadline passed. It may still be possible.")
            }
            return HygieneCall.ToLethe(i.reason?.takeIf { it.isNotBlank() } ?: "Relevant until ${DAY.format(Instant.ofEpochMilli(until).atZone(zone))}")
        }
        // Overdue, but its window is still open: one last chance before it's too late.
        if (i.due != null && now > i.due && !i.lastChanceShown) {
            return HygieneCall.LastChance("Overdue, still possible until ${DAY.format(Instant.ofEpochMilli(until).atZone(zone))}.")
        }
        return HygieneCall.Keep
    }

    /** The weekly "Closed for you" line. Null when nothing closed. */
    fun closedLine(titles: List<String>): String? = when (titles.size) {
        0 -> null
        1 -> "${titles[0]} went to Lethe this week."
        2 -> "${titles[0]} and ${titles[1]} went to Lethe this week."
        else -> "${titles[0]}, ${titles[1]} and ${titles.size - 2} more went to Lethe this week."
    }
}
