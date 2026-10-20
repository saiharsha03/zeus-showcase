package dev.phonerecall.tasks

import dev.phonerecall.brain.CueReader
import dev.phonerecall.data.Project
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskContext
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** Where an Inbox item can go, as GTD's closed choices. The first three are "not actionable". */
enum class Clarify(val label: String, val actionable: Boolean, val hint: String) {
    SOMEDAY("Someday", false, "Not now. Keep it for later."),
    REFERENCE("Reference", false, "Worth keeping. It goes to your Reference list."),
    TRASH("Trash", false, "Not needed. Delete it."),
    DO_NOW("Do it now", true, "Under two minutes. Do it, and it is done."),
    WAITING("Waiting for", true, "Someone else does it. Check back on a day."),
    SCHEDULED("Scheduled", true, "It has a date."),
    PROJECT("Project", true, "Many steps. Make it a project with a first next action."),
    NEXT("Next", true, "One step. Pick where it can be done."),
}

/** Athena's pre-pick for one item: local rules only, no model. */
data class Suggestion(
    val choice: Clarify,
    val context: TaskContext? = null,
    val who: String? = null,
    /** Scheduled: the day. Waiting for: the day to check back. */
    val date: LocalDate? = null,
)

/** What the phone knows about right now, for the contexts that fit. Null means not known. */
data class Situation(
    val officeDay: Boolean = false,
    val atOffice: Boolean? = null,
    val atHome: Boolean? = null,
    val driving: Boolean = false,
    val hour: Int = 12,
)

/** Praxis as GTD: clarify suggestions, contexts that fit now, follow-ups, stuck projects and the weekly review. Pure. */
object Gtd {
    private val REFERENCE = Regex("(?i)\\b(password|wi-?fi|gate code|door code|address|phone number|account number|policy number|confirmation (number|code)|serial|recipe|license plate|size is|sizes?:)\\b")
    private val SOMEDAY = Regex("(?i)\\b(someday|some day|maybe|one day|ideas?|wish ?list|bucket list|learn|explore)\\b|\\?\\s*$")
    private val WAITING = Regex("(?i)\\b(waiting (on|for)|wait for|hear back|heard back|reply from|response from|refund|reimburse\\w*|delivery|package|shipment|asked \\p{L}+ (to|about|for)|follow up with)\\b")
    private val WHO = Regex("\\b(?:from|on|with|asked|for)\\s+(\\p{Lu}[\\p{L}'.-]+(?:\\s\\p{Lu}[\\p{L}'.-]+)?)")
    /** Something he does himself ("Drop off the package"), not a wait. */
    private val ACTS = Regex("(?i)^(drop off|pick up|return|buy|call|text|email|book|order|schedule|send|pay)\\b")
    private val PROJECT = Regex("(?i)\\b(plan|organi[sz]e|trip|move|moving|renovat\\w*|set up|launch|prepare|overhaul|party|wedding|vacation|redo|research)\\b")
    private val QUICK = Regex("(?i)^(text|reply|forward|rsvp|confirm|thank|share|approve|accept|unsubscribe|like|send|pay)\\b")
    private val DATE_WORDS = Regex("(?i)\\b(today|tonight|tomorrow|tmrw|next week|this weekend|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")

    private val CONTEXT_RULES = listOf(
        TaskContext.CALLS to Regex("(?i)^(call|phone|ring|dial)\\b|\\bcall (back|him|her|them|\\p{Lu})"),
        TaskContext.ERRANDS to Regex("(?i)\\b(buy|pick up|pickup|drop off|return|grocer\\w*|costco|walmart|target|wegmans|pharmacy|post office|ship|car wash|oil change|bank|gas|fuel|store)\\b"),
        TaskContext.OFFICE to Regex("(?i)\\b(office|desk|badge|printer|print|whiteboard|coworkers?|at work)\\b"),
        TaskContext.HOME to Regex("(?i)\\b(laundry|clean|vacuum|dishes|cook|fridge|kitchen|bathroom|bedroom|closet|plants|trash|garage|tidy|iron|furnace|thermostat)\\b"),
        TaskContext.COMPUTER to Regex("(?i)\\b(email|e-mail|online|website|update|file|submit|apply|order|book|write|draft|review|download|install|code|spreadsheet|form|portal|renew|slack|teams)\\b"),
    )

    /** The context a next action reads like, or @anywhere. An explicit "@calls" in the title wins. */
    fun contextOf(title: String): TaskContext {
        Regex("@(\\w+)").findAll(title).firstNotNullOfOrNull { TaskContext.of(it.groupValues[1]) }?.let { return it }
        return CONTEXT_RULES.firstOrNull { it.second.containsMatchIn(title) }?.first ?: TaskContext.ANYWHERE
    }

    /** Rules first, in GTD's order. A due date it already has makes it Scheduled. */
    fun suggest(title: String, today: LocalDate, due: LocalDate? = null): Suggestion {
        if (due != null) return Suggestion(Clarify.SCHEDULED, date = due)
        val cues = CueReader.read(title)
        if (REFERENCE.containsMatchIn(title)) return Suggestion(Clarify.REFERENCE)
        if ((WAITING.containsMatchIn(title) || cues.delivery) && !ACTS.containsMatchIn(title.trim())) {
            return Suggestion(Clarify.WAITING, who = WHO.find(title)?.groupValues?.get(1), date = today.plusDays(3))
        }
        dateOf(title, today, cues.deadline)?.let { return Suggestion(Clarify.SCHEDULED, contextOf(title), date = it) }
        if (SOMEDAY.containsMatchIn(title)) return Suggestion(Clarify.SOMEDAY)
        if (PROJECT.containsMatchIn(title)) return Suggestion(Clarify.PROJECT)
        val words = title.trim().split(Regex("\\s+")).size
        if (QUICK.containsMatchIn(title.trim()) && words <= 6) return Suggestion(Clarify.DO_NOW)
        return Suggestion(Clarify.NEXT, contextOf(title))
    }

    /** "tomorrow", "by 5 pm", "Friday", "next week" as a day. */
    fun dateOf(title: String, today: LocalDate, deadline: String? = null): LocalDate? {
        val w = DATE_WORDS.find(title)?.value?.lowercase()
        return when {
            w == "tomorrow" || w == "tmrw" || deadline == "tomorrow" -> today.plusDays(1)
            w == "today" || w == "tonight" || deadline != null -> today
            w == "next week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
            w == "this weekend" -> today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
            w != null -> today.with(TemporalAdjusters.next(DayOfWeek.valueOf(w.uppercase())))
            else -> null
        }
    }

    /**
     * The contexts that fit right now, or null when nothing is known (then all show, with chips).
     * Driving: errands and anywhere only, never calls. At the office, or an office day's working hours:
     * office, computer and calls. At home: home, computer and calls. Out and about: errands and calls.
     */
    fun contextsNow(s: Situation): Set<TaskContext>? = when {
        s.driving -> setOf(TaskContext.ERRANDS, TaskContext.ANYWHERE)
        s.atOffice == true -> setOf(TaskContext.OFFICE, TaskContext.COMPUTER, TaskContext.CALLS, TaskContext.ANYWHERE)
        s.atHome == true -> setOf(TaskContext.HOME, TaskContext.COMPUTER, TaskContext.CALLS, TaskContext.ANYWHERE)
        s.atHome == false -> setOf(TaskContext.ERRANDS, TaskContext.CALLS, TaskContext.ANYWHERE)
        s.officeDay && s.hour in 8..17 -> setOf(TaskContext.OFFICE, TaskContext.COMPUTER, TaskContext.CALLS, TaskContext.ANYWHERE)
        else -> null
    }

    /** Why those contexts fit, in a few words, or null when nothing is known. */
    fun whereLine(s: Situation): String? = when {
        s.driving -> "Driving, so no calls"
        s.atOffice == true -> "At the office"
        s.atHome == true -> "At home"
        s.atHome == false -> "Out"
        s.officeDay && s.hour in 8..17 -> "Office day"
        else -> null
    }

    /** A task with no context fits anywhere. */
    fun fits(t: Task, now: Set<TaskContext>?): Boolean = now == null || t.context == null || t.context in now

    /** A Waiting for whose check-back day has come, from 7 AM (not in quiet hours). */
    fun followUpDue(t: Task, now: ZonedDateTime): Boolean =
        t.list == TaskList.WAITING && t.followUp != null && t.followUp <= now.toInstant().toEpochMilli() && now.hour in 7..22

    /** The proposal's words: "Follow up with Taylor about the weekly report?" */
    fun followUpTitle(t: Task): String {
        val about = t.title.trim().take(140).let { s -> if (s.length > 1 && s[1].isLowerCase()) s.replaceFirstChar { it.lowercase() } else s }
        return "Follow up with ${t.waitingOn?.takeIf { it.isNotBlank() } ?: "them"} about $about"
    }

    /** Projects with no open Next action. */
    fun stuck(s: TaskSnapshot): List<Project> {
        val open = s.open()
        return s.projects.filter { p -> open.none { it.projectId == p.id && it.list == TaskList.NEXT } }
    }

    /** The Saturday that opens this review weekend (the review counts from Saturday). */
    private fun weekendStart(sunday: LocalDate, now: ZonedDateTime) = sunday.minusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli()

    /** Sunday afternoon to 10 PM, until he finishes the review that weekend. */
    fun reviewDue(now: ZonedDateTime, last: Long?): Boolean {
        if (now.dayOfWeek != DayOfWeek.SUNDAY || now.hour !in 13..22) return false
        return last == null || last < weekendStart(now.toLocalDate(), now)
    }

    /** The last Sunday whose review is still owed (its 1 PM has passed, no review that weekend), or null. Eos's line. */
    fun reviewOwed(now: ZonedDateTime, last: Long?): LocalDate? {
        val today = now.toLocalDate()
        var sunday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        if (sunday == today && now.hour < 13) sunday = sunday.minusWeeks(1)
        return sunday.takeIf { last == null || last < weekendStart(it, now) }
    }

    /** The review's steps, in order, with how many items each has now. */
    fun reviewSteps(s: TaskSnapshot, now: ZonedDateTime): List<Pair<ReviewStep, Int>> {
        val open = s.open().filter { !it.sample }
        val nowMs = now.toInstant().toEpochMilli()
        val weekEnd = now.toLocalDate().plusDays(8).atStartOfDay(now.zone).toInstant().toEpochMilli()
        return listOf(
            ReviewStep.INBOX to open.count { it.list == TaskList.INBOX },
            ReviewStep.STUCK to stuck(s).count { !it.sample },
            ReviewStep.WAITING to open.count { it.list == TaskList.WAITING && (it.followUp == null || it.followUp <= nowMs) },
            ReviewStep.WEEK to open.count { it.list == TaskList.SCHEDULED && it.due != null && it.due < weekEnd },
            ReviewStep.SOMEDAY to open.count { it.list == TaskList.SOMEDAY },
        )
    }

    fun day(ms: Long, now: ZonedDateTime): LocalDate = Instant.ofEpochMilli(ms).atZone(now.zone).toLocalDate()
}

/** The weekly review, one short step at a time. */
enum class ReviewStep(val label: String, val ask: String, val empty: String) {
    INBOX("Empty the Inbox", "Clarify each item.", "Inbox is empty."),
    STUCK("Stuck projects", "Each needs one next action.", "Every project has a next action."),
    WAITING("Waiting for", "Overdue, or with no day to check back.", "Nothing overdue."),
    WEEK("The week ahead", "Scheduled for the next seven days.", "Nothing scheduled this week."),
    SOMEDAY("Someday", "A quick glance. Move anything that is now.", "Someday is empty."),
}
