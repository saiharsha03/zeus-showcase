package dev.phonerecall.brain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Proposal-only actions. A write tool never runs
 * anything: it validates against real local data and parks an exact ProposedAction. Only
 * Fates.confirm, plain code with no model in its stack, executes it.
 */

/**
 * What a proposal can do. Tier 2 is "your phone, reversible"; tier 3 is "outward or hard to undo".
 * Only `autoEligible` kinds may ever go on the auto-approve list; calendar writes never do.
 */
enum class ProposalKind(val wire: String, val tier: Int, val autoEligible: Boolean, val label: String, val modelTool: Boolean = true) {
    SET_ALARM("set_alarm", 2, true, "Alarm"),
    SET_TIMER("set_timer", 2, true, "Timer"),
    ADD_REMINDER("add_reminder", 2, true, "Reminder"),
    ADD_TASK("add_task", 2, true, "New task"),
    COMPLETE_TASK("complete_task", 2, false, "Mark task done"),
    ADD_CALENDAR_EVENT("add_calendar_event", 3, false, "Calendar event"),
    CREATE_CARD("create_card", 2, true, "Card on Olympus"),

    // Phase 2b: made by the senses and the Rambler, never by a model tool call.
    /** "Is 'Amma' your mother?" Tier 1: it only changes Athena's own people graph. */
    SET_RELATION("set_relation", 1, false, "Who this is", modelTool = false),
    /** A Google Calendar birthday (Google Calendar's own Birthday type, yearly) on his primary calendar: outward, so always asked.
     *  A model's add_calendar_event or add_task that is only a birthday validates as this. */
    ADD_BIRTHDAY("add_birthday", 3, false, "Birthday in Google Calendar", modelTool = false),
    /** A Rambler expansion: "Add pasta, tomatoes, garlic, parmesan to Groceries?" */
    ADD_TO_LIST("add_to_list", 2, true, "Add to a list"),

    // Music (Spotify first). Playing and controls are tier 2 proposals and never go on the auto-approve list.
    PLAY_MUSIC("play_music", 2, false, "Play music"),
    MUSIC_CONTROL("music_control", 2, false, "Music control"),
    /** Made by drive mode only, never by a model. The one music kind he may put on auto-approve (off by default). */
    MUSIC_WHILE_DRIVING("music_while_driving", 2, true, "Music while driving", modelTool = false),

    // Hestia (smart home through Google Home). A model calls home_control; the validator turns a light into a
    // LIGHTS step, the one home kind he may put on auto-approve (off by default). Plugs and switches stay
    // HOME_CONTROL, never auto. Locks, garage doors, cameras and security systems are refused outright.
    HOME_CONTROL("home_control", 2, false, "Home control"),
    LIGHTS("lights", 2, true, "Lights", modelTool = false),

    // Calls (Rhythms). Outward, so always asked: a tap on the call nudge's button is his confirm.
    PLACE_CALL("place_call", 3, false, "Call"),
    /** Horae: something to tell a person next time he calls them. Kept on the phone only. */
    BOOKMARK_FOR("bookmark_for", 2, false, "Thing to tell"),
    /** The fatigue meter's one meta-proposal: "Add reminders to auto-approve?". Made by Fates only, never automatic. */
    ADD_AUTO_APPROVE("add_auto_approve", 2, false, "Auto-approve a kind", modelTool = false),
    /** Moments (cooking, laundry, tea, the meter): a countdown under an hour. Made by plain code only; the one
     *  timer kind meant for the auto-approve list (off by default). An hour or longer validates as SET_TIMER. */
    SHORT_TIMER("short_timer", 2, true, "Timers under 1 hour", modelTool = false),
    /** Athena's own scheduled runs (Managed Agents deployments "Zeus · ..."): create, change, pause or delete one.
     *  Only her mind's propose_schedule makes it; confirming asks for his fingerprint. */
    SET_SCHEDULE("propose_schedule", 3, false, "Athena's schedule", modelTool = false),
    /** Athena's trip list (her mind's propose_trips) when she isn't sure, or it changes a trip he confirmed or edited.
     *  A sure one applies on its own with Undo and never reaches Fates. It changes Argo's own trips, nothing outside. */
    SET_TRIPS("set_trips", 2, false, "Trips", modelTool = false),
    /** One feature's model (models/Models.kt): the card Athena posts when a newer or cheaper model is out.
     *  Made by plain code only (her sync); confirming asks for his fingerprint; Undo puts the old model back. */
    SWITCH_MODEL("switch_model", 3, false, "Model for a feature", modelTool = false),
    /** A spawned task (mind/Tasks.kt) may spend more than its 25 cents. Made by plain code only (her spawn_task); it spends money, so it is never automatic. */
    TASK_BUDGET("task_budget", 3, false, "A task's budget", modelTool = false),
    /** "You usually ... Want me to do it for you?" (shortcuts/): Yes makes a learned shortcut, Undo removes it. Made by the phone's pattern pass only. */
    LEARN_SHORTCUT("learn_shortcut", 2, false, "Learned shortcut", modelTool = false),
    /** A home routine (hestia/Routines.kt) Athena drafted from his words, or a change to or deletion of one. Her
     *  propose_routine makes it; his Confirm makes it live; Undo puts the old one back. Never automatic. */
    SET_ROUTINE("set_routine", 2, false, "Home routine", modelTool = false),
    /** One of Athena's ideas from his week: a rule (becomes a learned shortcut) or a code change (a drafted Prometheus request he sends). */
    IDEA("idea", 2, false, "An idea from Athena", modelTool = false),
    /** A change to Zeus that Prometheus assessed as feasible (prometheus/Assess.kt). Made by plain code only, never
     *  automatic; Confirm sends the normal build request, and merge and release still take his fingerprint. */
    BUILD_APP("build_app", 3, false, "Build an app change", modelTool = false);

    companion object {
        fun of(wire: String): ProposalKind? = entries.firstOrNull { it.wire == wire }
        /** Kinds a model may call as tools (Delphi). The phase 2b kinds are made by plain code only. */
        fun tool(wire: String): ProposalKind? = of(wire)?.takeIf { it.modelTool }
        val autoCandidates: List<ProposalKind> get() = entries.filter { it.autoEligible && it.tier == 2 }
    }
}

enum class ProposalStatus { PENDING, RUNNING, CONFIRMED, REJECTED, EXPIRED, FAILED, UNDONE }

/**
 * One action. `payload` is the exact, normalized arguments. `needs` names a choice still missing
 * ("calendar", "calendar_permission", "google_calendar_auth"): the card shows a picker and Confirm waits for it.
 */
data class Step(
    val kind: ProposalKind,
    val payload: Map<String, String>,
    val summary: String,
    val selected: Boolean = true,
    val needs: String? = null,
    val result: String? = null,
    val undo: Map<String, String>? = null,
)

data class Proposal(
    val id: Long = 0,
    val createdAt: Long,
    val expiresAt: Long,
    val status: ProposalStatus,
    val title: String,
    val steps: List<Step>,
    val conversationId: Long? = null,
    val proposer: String = "Athena",
    val auto: Boolean = false,
    val outcome: String? = null,
    val decidedAt: Long? = null,
) {
    val tier: Int get() = steps.maxOf { it.kind.tier }
    val needs: String? get() = steps.firstOrNull { it.selected && it.needs != null }?.needs
    val undoable: Boolean get() = status == ProposalStatus.CONFIRMED && steps.any { it.undo != null }
}

/** A calendar Athena may write to. */
data class CalendarChoice(val id: Long, val name: String, val account: String)

/** A birthday event already on his Google Calendar: its event id, its title, and its day ("MM-DD"). */
data class SavedBirthday(val eventId: String, val title: String, val date: String)

/** What Confirm is still waiting for, in his words. */
fun needText(needs: String): String = when (needs) {
    "calendar_permission" -> "Allow calendar writes first"
    "google_calendar_auth" -> "Allow Google Calendar first"
    else -> "Choose a $needs first"
}

/** The real local data a proposal is checked against, at proposal time and again at confirm. */
interface LocalState {
    val zone: ZoneId
    /** The task's title if it exists and is still open; null otherwise. */
    fun openTaskTitle(id: Long): String?
    fun writableCalendars(): List<CalendarChoice>
    fun canWriteCalendar(): Boolean
    fun canNotify(): Boolean
    /** He has allowed Zeus to add events to his Google Calendar (the calendar.events scope, for birthdays). */
    fun canSaveBirthdays(): Boolean = false
    /** Birthday events on his Google Calendar for [name]. Throws when Google Calendar can't be asked. */
    fun savedBirthdays(name: String): List<SavedBirthday> = emptyList()
    /** Open items on a list, by section name, for duplicate checks. Empty when it doesn't exist yet. */
    fun listItems(section: String): List<String> = emptyList()
    /** Where a person lives, by the name he used; null when it is his own zone or unknown. */
    fun personZone(name: String): ZoneId? = null
    /** Drive mode says he is in the car right now. */
    fun driving(): Boolean = false
    /** Hestia's devices as Google Home reports them now; null when Hestia isn't on this phone. */
    fun home(): dev.phonerecall.hestia.HomeSnapshot? = null
    /** The one contact [who] names (or [contactId]), resolved on the phone; null when none or several match. */
    fun callTarget(who: String, contactId: Long?): dev.phonerecall.calls.CallTarget? = null
    /** Athena's schedules as the phone has them; null when her mind isn't set up here. */
    fun schedules(): List<dev.phonerecall.mind.SchedJob>? = null
}

sealed class Check {
    data class Ok(val step: Step) : Check()
    data class Bad(val reason: String) : Check()
}

/** Validates and normalizes one step's arguments. Pure: the same inputs give the same answer. */
object Validate {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val LISTS = setOf("INBOX", "NEXT", "WAITING", "SCHEDULED", "SOMEDAY")
    private val ISO_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val DAY_ONLY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

    fun step(kind: ProposalKind, raw: Map<String, String>, state: LocalState, now: Long): Check = try {
        when (kind) {
            ProposalKind.SET_ALARM -> alarm(raw)
            ProposalKind.SET_TIMER, ProposalKind.SHORT_TIMER -> timer(raw, kind)
            ProposalKind.ADD_REMINDER -> reminder(raw, state, now)
            ProposalKind.ADD_TASK -> task(raw, state)
            ProposalKind.COMPLETE_TASK -> complete(raw, state)
            ProposalKind.ADD_CALENDAR_EVENT -> event(raw, state, now)
            ProposalKind.CREATE_CARD -> card(raw)
            ProposalKind.SET_RELATION -> relation(raw)
            ProposalKind.ADD_BIRTHDAY -> birthday(raw, state)
            ProposalKind.ADD_TO_LIST -> list(raw, state)
            ProposalKind.PLAY_MUSIC -> play(raw, ProposalKind.PLAY_MUSIC)
            ProposalKind.MUSIC_CONTROL -> control(raw)
            ProposalKind.MUSIC_WHILE_DRIVING -> {
                if (!state.driving()) throw Invalid("Not driving right now")
                play(raw, ProposalKind.MUSIC_WHILE_DRIVING)
            }
            ProposalKind.HOME_CONTROL, ProposalKind.LIGHTS -> home(raw, state)
            ProposalKind.PLACE_CALL -> call(raw, state)
            ProposalKind.BOOKMARK_FOR -> {
                val person = text(raw, "person", 40)!!
                val note = text(raw, "note", 200)!!
                Check.Ok(Step(ProposalKind.BOOKMARK_FOR, mapOf("person" to person, "note" to note), "Tell $person: $note"))
            }
            ProposalKind.ADD_AUTO_APPROVE -> autoKind(raw)
            ProposalKind.SET_SCHEDULE -> schedule(raw, state)
            ProposalKind.SET_TRIPS -> {
                val plan = text(raw, "plan", 40)!!.also { if (!it.matches(Regex("[a-z0-9]{4,40}"))) throw Invalid("Not a trip proposal") }
                val trips = text(raw, "trips", 240)!!
                val p = linkedMapOf("plan" to plan, "trips" to trips)
                text(raw, "why", 200, required = false)?.let { p["why"] = it }
                Check.Ok(Step(kind, p, trips))
            }
            ProposalKind.SWITCH_MODEL -> switchModel(raw)
            ProposalKind.SET_ROUTINE -> {
                val home = state.home() ?: throw Invalid("Hestia isn't set up")
                val sentence = text(raw, "sentence", 400)!!
                val delete = text(raw, "delete", 40, required = false)
                if (delete != null) Check.Ok(Step(kind, mapOf("delete" to delete, "sentence" to sentence), sentence))
                else {
                    val json = raw["routine"] ?: throw Invalid("routine is missing")
                    val r = runCatching { dev.phonerecall.mind.stateJson.readValue(json, dev.phonerecall.hestia.Routine::class.java) }.getOrNull() ?: throw Invalid("Not a routine")
                    dev.phonerecall.hestia.RoutineSpec.recheck(r, home, emptySet())?.let { throw Invalid(it) }
                    Check.Ok(Step(kind, mapOf("routine" to json, "sentence" to sentence), sentence))
                }
            }
            ProposalKind.LEARN_SHORTCUT -> {
                val trigger = text(raw, "trigger", 120)!!
                val action = text(raw, "action", 160)!!
                if (!dev.phonerecall.shortcuts.Learn.allowed(action)) throw Invalid("A shortcut never sends a message or pays; those stay one tap for you")
                val p = raw.filterKeys { it.startsWith("a.") }.mapValues { it.value.take(200) } + mapOf(
                    "trigger" to trigger, "action" to action,
                    "trigger_label" to (text(raw, "trigger_label", 80, required = false) ?: trigger), "action_label" to (text(raw, "action_label", 80, required = false) ?: action),
                )
                Check.Ok(Step(kind, p + (text(raw, "why", 140, required = false)?.let { mapOf("why" to it) } ?: emptyMap()), "${p["action_label"]} when ${p["trigger_label"]}"))
            }
            ProposalKind.IDEA -> {
                val title = text(raw, "title", 80)!!
                val why = text(raw, "why", 160)!!
                val action = text(raw, "action", 160, required = false)
                val p = linkedMapOf("title" to title, "why" to why)
                if (action != null) {
                    if (!dev.phonerecall.shortcuts.Learn.allowed(action)) throw Invalid("An idea never sends a message or pays on its own")
                    p["action"] = action; p["trigger"] = text(raw, "trigger", 120)!!
                    p["trigger_label"] = text(raw, "trigger_label", 80, required = false) ?: p.getValue("trigger"); p["action_label"] = text(raw, "action_label", 80, required = false) ?: action
                } else { p["problem"] = text(raw, "problem", 800)!!; p["acceptance"] = text(raw, "acceptance", 400)!! }
                Check.Ok(Step(kind, p, title))
            }
            ProposalKind.TASK_BUDGET -> {
                val task = text(raw, "task", 20)!!.also { if (!it.matches(Regex("task-[a-f0-9]{1,12}"))) throw Invalid("Not a task") }
                val cents = int(raw, "cents", 26..dev.phonerecall.mind.Tasks.MAX_CENTS.toInt())
                val title = text(raw, "title", 80, required = false) ?: task
                Check.Ok(Step(kind, mapOf("task" to task, "cents" to "$cents", "title" to title), "$title: up to $cents cents"))
            }
            ProposalKind.BUILD_APP -> build(raw)
        }
    } catch (e: Invalid) { Check.Bad(e.message ?: "Invalid") }

    /** Prometheus's assessment as a Build card: only a feasible (yes or partly) one, with its approach shown. */
    private fun build(raw: Map<String, String>): Check {
        val id = text(raw, "assessment", 40)!!.also { if (!it.matches(Regex("a[0-9a-f]{12}"))) throw Invalid("Not a Prometheus assessment") }
        val feasible = text(raw, "feasible", 10)!!.also { if (it != "yes" && it != "partly") throw Invalid("Prometheus didn't find it feasible") }
        val title = text(raw, "title", 80)!!
        val p = linkedMapOf("assessment" to id, "title" to title, "need" to text(raw, "need", 1200)!!, "feasible" to feasible)
        for ((k, max) in listOf("approach" to 600, "files" to 400, "risk" to 300, "needs" to 300, "done_when" to 400)) text(raw, k, max, required = false)?.let { p[k] = it }
        return Check.Ok(Step(ProposalKind.BUILD_APP, p, "Build: $title"))
    }

    /** One of Athena's own schedules, checked against the ones that exist (cron, name, the cap of 12). */
    private fun schedule(raw: Map<String, String>, state: LocalState): Check {
        val jobs = state.schedules() ?: throw Invalid("Athena's schedules aren't set up on this phone")
        val c = dev.phonerecall.mind.SchedChange(
            raw["action"]?.trim()?.lowercase().orEmpty(), raw["name"]?.trim().orEmpty(), text(raw, "schedule", 60, required = false),
            text(raw, "prompt", 2000, required = false), text(raw, "why", 200, required = false), trial = raw["test_run"]?.trim() == "true",
        )
        dev.phonerecall.mind.ScheduleBook.check(c, jobs)?.let { throw Invalid(it) }
        val name = dev.phonerecall.mind.ScheduleBook.bare(c.name)
        val p = linkedMapOf("action" to c.action, "name" to name)
        c.cron?.let { p["schedule"] = it }; c.prompt?.let { p["prompt"] = it }; c.why?.let { p["why"] = it }; if (c.trial) p["test_run"] = "true"
        val words = c.cron?.let { dev.phonerecall.mind.Cron.words(it) }
        val summary = when (c.action) {
            "create" -> "New Athena schedule $name: $words"
            "update" -> "Change Athena's $name" + (words?.let { " to $it" } ?: "")
            "pause" -> "Pause Athena's $name"
            "resume" -> "Resume Athena's $name"
            else -> "Delete Athena's $name"
        }
        return Check.Ok(Step(ProposalKind.SET_SCHEDULE, p, summary))
    }

    private class Invalid(msg: String) : Exception(msg)

    private fun text(raw: Map<String, String>, key: String, max: Int, required: Boolean = true): String? {
        val v = raw[key]?.trim()?.replace('—', '-')?.takeIf { it.isNotEmpty() }
        if (v == null) { if (required) throw Invalid("$key is missing"); return null }
        if (v.length > max) throw Invalid("$key is longer than $max characters")
        return v
    }

    private fun int(raw: Map<String, String>, key: String, range: IntRange, default: Int? = null): Int {
        val s = raw[key]?.trim()?.takeIf { it.isNotEmpty() } ?: return default ?: throw Invalid("$key is missing")
        val n = s.toDoubleOrNull()?.takeIf { it == Math.floor(it) }?.toInt() ?: throw Invalid("$key must be a whole number")
        if (n !in range) throw Invalid("$key must be between ${range.first} and ${range.last}")
        return n
    }

    private fun bool(raw: Map<String, String>, key: String) = raw[key]?.trim()?.lowercase() == "true"

    /** "2026-10-20T08:00", "2026-10-20 08:00:00" or with an offset; local times are in the phone's zone. */
    fun dateTime(s: String, zone: ZoneId): ZonedDateTime {
        val t = s.trim().replace(' ', 'T')
        return runCatching { OffsetDateTime.parse(t).atZoneSameInstant(zone) }.getOrNull()
            ?: runCatching { LocalDateTime.parse(t).atZone(zone) }.getOrNull()
            ?: runCatching { LocalDate.parse(t).atStartOfDay(zone) }.getOrNull()
            ?: throw Invalid("\"$s\" is not a date and time like 2026-10-20T08:00")
    }

    private fun alarm(raw: Map<String, String>): Check {
        val h = int(raw, "hour", 0..23)
        val m = int(raw, "minute", 0..59, default = 0)
        val label = text(raw, "label", 60, required = false)
        val at = TIME.format(LocalDateTime.of(2000, 1, 1, h, m))
        val p = mapOf("hour" to "$h", "minute" to "$m") + (label?.let { mapOf("label" to it) } ?: emptyMap())
        return Check.Ok(Step(ProposalKind.SET_ALARM, p, "Alarm at $at" + (label?.let { ", $it" } ?: "")))
    }

    private fun timer(raw: Map<String, String>, asked: ProposalKind): Check {
        val seconds = when {
            raw["seconds"] != null -> int(raw, "seconds", 1..86_400)
            raw["minutes"] != null -> int(raw, "minutes", 1..1_440) * 60
            else -> throw Invalid("seconds is missing")
        }
        val label = text(raw, "label", 60, required = false)
        val p = mapOf("seconds" to "$seconds") + (label?.let { mapOf("label" to it) } ?: emptyMap())
        // A short timer stays short: an hour or more is an ordinary timer, never auto-approved as a short one.
        val kind = if (asked == ProposalKind.SHORT_TIMER && seconds < 3600) ProposalKind.SHORT_TIMER else ProposalKind.SET_TIMER
        return Check.Ok(Step(kind, p, "Timer for ${length(seconds)}" + (label?.let { ", $it" } ?: "")))
    }

    fun length(seconds: Int): String = when {
        seconds < 60 -> "$seconds s"
        seconds % 3600 == 0 -> "${seconds / 3600} h"
        seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "${seconds / 60} min ${seconds % 60} s"
    }

    private fun reminder(raw: Map<String, String>, state: LocalState, now: Long): Check {
        val at = dateTime(text(raw, "at", 40)!!, state.zone)
        val ms = at.toInstant().toEpochMilli()
        if (ms < now + 30_000) throw Invalid("That time has already passed")
        if (ms > now + 366L * 86_400_000) throw Invalid("Reminders can be at most a year ahead")
        if (!state.canNotify()) throw Invalid("Notifications are off for Zeus, so a reminder could not show")
        val what = text(raw, "text", 200)!!
        val p = hashMapOf("at" to ISO_LOCAL.format(at), "text" to what)
        val note = withWho(raw, state, at, p)
        return Check.Ok(Step(ProposalKind.ADD_REMINDER, p, "Remind you ${DAY_TIME.format(at)}: $what${note?.let { " ($it)" }.orEmpty()}"))
    }

    /**
     * "with": who a call, message or meeting involves. When they live on another clock, keeps their zone
     * in the payload and returns a line for the summary: their local time, and a later time if it is the
     * middle of the night there.
     */
    private fun withWho(raw: Map<String, String>, state: LocalState, at: java.time.ZonedDateTime, p: HashMap<String, String>): String? {
        val who = text(raw, "with", 60, required = false) ?: return null
        p["with"] = who
        val zone = state.personZone(who)?.takeIf { it.rules.getOffset(at.toInstant()) != at.zone.rules.getOffset(at.toInstant()) } ?: return null
        p["with_zone"] = zone.id
        val r = TwoClocks(state.zone).reach(at.toInstant(), zone)
        return "${r.local} for $who" + (r.nextText?.let { ", late there. Better ${it}" } ?: "")
    }

    private fun task(raw: Map<String, String>, state: LocalState): Check {
        val title = text(raw, "title", 200)!!
        val context = raw["context"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            dev.phonerecall.data.TaskContext.of(it) ?: throw Invalid("context must be one of office, home, errands, calls, computer, anywhere")
        }
        val waitingOn = text(raw, "waiting_on", 60, required = false)
        val followUp = raw["follow_up"]?.trim()?.takeIf { it.isNotEmpty() }?.let { dateTime(it, state.zone).toLocalDate() }
        // Who it waits on, or a day to check back, makes it a Waiting for unless a list was given.
        val given = raw["list"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val list = (given ?: if (waitingOn != null || followUp != null) "WAITING" else "INBOX").let { if (it == "WAITING_FOR") "WAITING" else it }
        if (list !in LISTS) throw Invalid("list must be one of inbox, next, waiting, scheduled, someday")
        val due = raw["due"]?.trim()?.takeIf { it.isNotEmpty() }
        // "Sam's birthday" with a day is a birthday, not a task. "Buy Sam's birthday gift" stays a task.
        if (due != null) birthdayOf(title)?.let { return birthday(mapOf("name" to it, "date" to dateTime(due, state.zone).toLocalDate().toString()), state) }
        val p = HashMap<String, String>().apply { put("title", title); put("list", list) }
        var when_ = ""
        if (due != null) {
            val hasTime = due.contains('T') || due.contains(' ')
            val t = dateTime(due, state.zone)
            p["due"] = if (hasTime) ISO_LOCAL.format(t) else t.toLocalDate().toString()
            when_ = ", due " + if (hasTime) DAY_TIME.format(t) else DAY.format(t)
        }
        context?.let { p["context"] = it.name.lowercase(); when_ += ", ${it.label}" }
        waitingOn?.let { p["waiting_on"] = it; when_ += ", waiting on $it" }
        followUp?.let { p["follow_up"] = it.toString(); when_ += ", check back " + DAY.format(it) }
        return Check.Ok(Step(ProposalKind.ADD_TASK, p, "Add task: $title$when_"))
    }

    private fun complete(raw: Map<String, String>, state: LocalState): Check {
        val id = raw["task_id"]?.trim()?.toDoubleOrNull()?.toLong() ?: throw Invalid("task_id is missing")
        val title = state.openTaskTitle(id) ?: throw Invalid("Task $id is not open (done, deleted or never existed)")
        return Check.Ok(Step(ProposalKind.COMPLETE_TASK, mapOf("task_id" to "$id", "title" to title), "Mark done: $title"))
    }

    private fun event(raw: Map<String, String>, state: LocalState, now: Long): Check {
        val title = text(raw, "title", 200)!!
        val allDay = bool(raw, "all_day")
        val start = dateTime(text(raw, "start", 40)!!, state.zone).let { if (allDay) it.toLocalDate().atStartOfDay(state.zone) else it }
        // A birthday is a Google Calendar birthday, never a plain yearly event. A birthday party stays an event.
        birthdayOf(title)?.let { return birthday(mapOf("name" to it, "date" to start.toLocalDate().toString()), state) }
        val end = raw["end"]?.trim()?.takeIf { it.isNotEmpty() }?.let { dateTime(it, state.zone) }
            ?: if (allDay) start.plusDays(1) else start.plusHours(1)
        if (!end.isAfter(start)) throw Invalid("end must be after start")
        if (Duration.between(start, end).toDays() > 14) throw Invalid("Events can be at most 14 days long")
        if (end.toInstant().toEpochMilli() < now) throw Invalid("That event would already be over")
        val p = HashMap<String, String>().apply {
            put("title", title); put("all_day", "$allDay")
            put("start", if (allDay) start.toLocalDate().toString() else ISO_LOCAL.format(start))
            put("end", if (allDay) end.toLocalDate().toString() else ISO_LOCAL.format(end))
        }
        val whenText = if (allDay) DAY.format(start) else DAY_TIME.format(start)
        val note = if (allDay) null else withWho(raw, state, start, p)
        val needs = calendarChoice(raw, state, p)
        return Check.Ok(Step(ProposalKind.ADD_CALENDAR_EVENT, p, "Add to calendar: $title, $whenText${note?.let { " ($it)" }.orEmpty()}", needs = needs))
    }

    /** Puts the chosen calendar into `p`, or says what is still missing: the permission or the pick. */
    private fun calendarChoice(raw: Map<String, String>, state: LocalState, p: HashMap<String, String>): String? {
        if (!state.canWriteCalendar()) {
            raw["calendar_id"]?.trim()?.toDoubleOrNull()?.toLong()?.let { p["calendar_id"] = "$it" }
            return "calendar_permission"
        }
        val writable = state.writableCalendars()
        val asked = raw["calendar_id"]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.toLong()
        return when {
            asked != null -> {
                val cal = writable.firstOrNull { it.id == asked } ?: throw Invalid("Calendar $asked is not one Zeus can write to")
                p["calendar_id"] = "${cal.id}"; p["calendar"] = cal.name
                null
            }
            writable.isEmpty() -> throw Invalid("There is no Google calendar on this phone that Zeus can write to")
            else -> dev.phonerecall.data.MyAccounts.defaultCalendar(writable)?.let { cal -> p["calendar_id"] = "${cal.id}"; p["calendar"] = cal.name; null } ?: "calendar"
        }
    }

    private fun relation(raw: Map<String, String>): Check {
        val person = text(raw, "person", 40)!!
        val term = text(raw, "term", 40)!!
        val r = Relation.of(raw["relation"]) ?: throw Invalid("relation must be one of ${Relation.entries.joinToString(", ") { it.word }}")
        return Check.Ok(Step(ProposalKind.SET_RELATION, mapOf("person" to person, "term" to term, "relation" to r.name), "“$term” is your ${r.word}"))
    }

    /** "10-19", "2026-10-19" or "--10-19": the month and day of a yearly birthday. */
    fun monthDay(s: String): Pair<Int, Int>? {
        val parts = s.trim().trimStart('-').split('-').filter { it.isNotEmpty() }
        val (m, d) = when (parts.size) { 3 -> parts[1] to parts[2]; 2 -> parts[0] to parts[1]; else -> return null }
        val mi = m.toIntOrNull() ?: return null
        val di = d.toIntOrNull() ?: return null
        return if (runCatching { java.time.MonthDay.of(mi, di) }.isSuccess) mi to di else null
    }

    /** "Month day" for a birthday's "MM-DD", like Oct 20. */
    private fun dayText(date: String): String =
        monthDay(date)?.let { (m, d) -> DAY_ONLY.format(java.time.MonthDay.of(m, d).atYear(2000)) } ?: date

    private const val BDAY = "(?:birthday|bday|b'day|b’day|b-day)"
    private val BIRTHDAY_TITLES = listOf(
        Regex("^(.+?)(?:'s|’s|'|’)\\s+$BDAY$", RegexOption.IGNORE_CASE),
        Regex("^$BDAY\\s*(?:of|for|:|-)?\\s+(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(.+?)\\s+$BDAY$", RegexOption.IGNORE_CASE),
    )
    private val NAME_WORD = Regex("\\p{L}[\\p{L}.'’-]*")
    private val NOT_A_NAME = setOf(
        "happy", "my", "his", "her", "their", "our", "your", "the", "a", "plan", "buy", "get", "order", "call", "wish",
        "party", "gift", "cake", "celebrate", "book", "send", "text", "remind", "dinner", "lunch",
    )

    /** The name when a title is only someone's birthday ("Sam's birthday", "Mom bday", "Birthday of Alex"); null otherwise. */
    fun birthdayOf(title: String): String? {
        val t = title.trim().replace(Regex("[^\\p{L}']+$"), "")
        for (r in BIRTHDAY_TITLES) {
            val name = r.matchEntire(t)?.groupValues?.get(1)?.trim() ?: continue
            val words = name.split(Regex("\\s+"))
            if (name.length > 40 || words.size > 3 || words.any { it.lowercase() in NOT_A_NAME || !NAME_WORD.matches(it) }) return null
            return name
        }
        return null
    }

    /**
     * A Google Calendar birthday: Google Calendar's own Birthday type on his primary calendar, a name and a day,
     * every year. No contact is made or changed. A birthday already saved on that day is nothing to do; on another
     * day, the card asks to replace it.
     */
    private fun birthday(raw: Map<String, String>, state: LocalState): Check {
        val name = text(raw, "name", 40)!!
        val (m, d) = monthDay(text(raw, "date", 12)!!) ?: throw Invalid("date must be a month and day like 10-19")
        val date = "%02d-%02d".format(m, d)
        val day = dayText(date)
        val p = HashMap<String, String>().apply {
            put("name", name); put("date", date)
            raw["source"]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("source", it.take(60)) }
            raw["thread"]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("thread", it) }
            raw["phrase"]?.trim()?.takeIf { it.isNotEmpty() }?.let { put("phrase", it) }
        }
        val save = "Save $name's birthday ($day) in Google Calendar. It repeats every year."
        if (!state.canSaveBirthdays()) return Check.Ok(Step(ProposalKind.ADD_BIRTHDAY, p, save, needs = "google_calendar_auth"))
        val saved = try { state.savedBirthdays(name) } catch (e: Exception) { throw Invalid("Couldn't check Google Calendar for $name's birthday: ${e.message}") }
        if (saved.any { it.date == date }) throw Invalid("Already saved: $name's birthday ($day) is in Google Calendar")
        val old = saved.firstOrNull() ?: return Check.Ok(Step(ProposalKind.ADD_BIRTHDAY, p, save))
        p["old_event_id"] = old.eventId; p["old_date"] = old.date
        return Check.Ok(Step(ProposalKind.ADD_BIRTHDAY, p, "$name's birthday is saved as ${dayText(old.date)} in Google Calendar. Replace it with $day? It repeats every year."))
    }

    private fun list(raw: Map<String, String>, state: LocalState): Check {
        val section = text(raw, "section", 32)!!
        val have = state.listItems(section).map { it.trim().lowercase() }.toSet()
        val asked = text(raw, "items", 600)!!.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        if (asked.isEmpty()) throw Invalid("items is missing")
        if (asked.size > 20) throw Invalid("At most 20 items at once")
        val fresh = asked.filter { it.lowercase() !in have }
        if (fresh.isEmpty()) throw Invalid("Everything is already on $section")
        return Check.Ok(Step(ProposalKind.ADD_TO_LIST, mapOf("section" to section, "items" to fresh.joinToString(", ")), "Add ${fresh.joinToString(", ")} to $section"))
    }

    /** spotify:playlist|album|artist|track|show|episode: plus the 22-character base62 id. */
    private val SPOTIFY_URI = Regex("^spotify:(playlist|album|artist|track|show|episode):[A-Za-z0-9]{22}$")
    private val ACTIONS = mapOf("pause" to "Pause the music", "resume" to "Resume the music", "next" to "Skip to the next track", "previous" to "Back to the previous track")

    private fun play(raw: Map<String, String>, kind: ProposalKind): Check {
        val uri = text(raw, "uri", 80, required = false)?.also { if (!SPOTIFY_URI.matches(it)) throw Invalid("uri must be a Spotify URI like spotify:playlist:37i9dQZF1DXcBWIGoYBM5M") }
        val query = text(raw, "query", 120, required = false)
        if (uri == null && query == null) throw Invalid("Give a uri or a query")
        val name = text(raw, "name", 80, required = false) ?: query ?: "that"
        val p = HashMap<String, String>().apply {
            uri?.let { put("uri", it) }; query?.let { put("query", it) }; put("name", name)
            if (bool(raw, "shuffle")) put("shuffle", "true")
            text(raw, "reason", 80, required = false)?.let { put("reason", it) }
        }
        val prefix = if (kind == ProposalKind.MUSIC_WHILE_DRIVING) "While driving: play" else "Play"
        return Check.Ok(Step(kind, p, "$prefix $name"))
    }

    private fun control(raw: Map<String, String>): Check {
        val a = raw["action"]?.trim()?.lowercase().let { if (it == "skip") "next" else if (it == "play") "resume" else it }
        val summary = ACTIONS[a] ?: throw Invalid("action must be pause, resume, next or previous")
        return Check.Ok(Step(ProposalKind.MUSIC_CONTROL, mapOf("action" to a!!), summary))
    }

    /** One device, one action. The kind comes from the device, never from the caller. */
    private fun home(raw: Map<String, String>, state: LocalState): Check {
        val s = state.home() ?: throw Invalid("Hestia isn't set up on this phone")
        return when (val c = dev.phonerecall.hestia.HomeRules.check(raw, s)) {
            is dev.phonerecall.hestia.ControlCheck.Bad -> throw Invalid(c.reason)
            is dev.phonerecall.hestia.ControlCheck.Ok -> Check.Ok(Step(if (c.light) ProposalKind.LIGHTS else ProposalKind.HOME_CONTROL, c.payload, c.summary))
        }
    }

    /**
     * One contact, one way: WhatsApp video by default for India, a phone call otherwise (CallRouting). Falls
     * back to what the contact has. The summary keeps the words he or the model used; the card shows the name.
     */
    private fun call(raw: Map<String, String>, state: LocalState): Check {
        val who = text(raw, "who", 60)!!
        val t = state.callTarget(who, raw["contact_id"]?.trim()?.toLongOrNull())
            ?: throw Invalid("No single contact called \"$who\" (Zeus needs Contacts, and one clear match)")
        val R = dev.phonerecall.calls.CallRouting
        var via = R.via(raw["via"]) ?: t.auto
        if (via == R.VIDEO && !t.video) via = R.VOICE
        if (via == R.VOICE && !t.voice) via = if (t.video) R.VIDEO else if (t.hasNumber) R.PHONE else throw Invalid("$who isn't on WhatsApp and has no number")
        if (via == R.PHONE && !t.hasNumber) via = if (t.video) R.VIDEO else if (t.voice) R.VOICE else throw Invalid("$who has no number")
        if (via == R.VIDEO && state.driving()) throw Invalid("No video calls while driving")
        val how = when (via) { R.VIDEO -> "WhatsApp video call"; R.VOICE -> "WhatsApp call"; else -> "Call" }
        val p = HashMap<String, String>().apply {
            put("who", who); put("name", t.name); put("contact_id", "${t.contactId}"); put("via", via)
            t.there?.let { put("there", it) }; t.awake?.let { put("awake", it + if (t.asleep) "; asleep now" else "") }
        }
        // Horae's words reach Delphi in the title, so it knows not to press a call in their night.
        val night = if (t.asleep) " (${t.there ?: "their night"}, usually asleep)" else ""
        return Check.Ok(Step(ProposalKind.PLACE_CALL, p, "$how $who$night"))
    }

    /** Only a kind that may ever be automatic: auto-eligible and tier 2. Money, messages, deletes and the server are none of them. */
    private fun autoKind(raw: Map<String, String>): Check {
        val k = ProposalKind.of(raw["kind"]?.trim().orEmpty()) ?: throw Invalid("kind is missing")
        if (k !in ProposalKind.autoCandidates) throw Invalid("${k.label} can never be automatic")
        val p = mapOf("kind" to k.wire) + (text(raw, "why", 120, required = false)?.let { mapOf("why" to it) } ?: emptyMap())
        return Check.Ok(Step(ProposalKind.ADD_AUTO_APPROVE, p, "Run ${k.label.lowercase()} proposals without asking"))
    }

    /** A model the feature can use (Picker.options: listed, not deprecated, fits what it needs) and isn't using. */
    private fun switchModel(raw: Map<String, String>): Check {
        val f = dev.phonerecall.models.Features.of(raw["feature"]?.trim().orEmpty()) ?: throw Invalid("Not a feature with a model")
        val m = raw["model"]?.trim().orEmpty()
        val book = dev.phonerecall.models.ModelChoice.book
        if (m !in book.options(f)) throw Invalid("$m can't run ${f.label}")
        if (m == book.of(f)) throw Invalid("${f.label} already uses $m")
        val p = linkedMapOf("feature" to f.key, "model" to m)
        text(raw, "why", 200, required = false)?.let { p["why"] = it }
        return Check.Ok(Step(ProposalKind.SWITCH_MODEL, p, "${f.label}: switch to $m"))
    }

    private fun card(raw: Map<String, String>): Check {
        val title = text(raw, "title", 60)!!
        val body = text(raw, "body", 280, required = false).orEmpty()
        val hours = int(raw, "hours", 1..72, default = 12)
        return Check.Ok(Step(ProposalKind.CREATE_CARD, mapOf("title" to title, "body" to body, "hours" to "$hours"), "Put a card on Olympus: $title"))
    }

    /** How long a proposal may wait for him. Never longer than the moment it is about. */
    fun ttl(steps: List<Step>, now: Long, zone: ZoneId): Long = steps.minOf { s ->
        when (s.kind) {
            ProposalKind.SET_TIMER, ProposalKind.SHORT_TIMER -> now + 30 * 60_000L
            ProposalKind.SET_ALARM -> now + 12 * 3_600_000L
            ProposalKind.ADD_REMINDER -> s.payload["at"]?.let { dateTime(it, zone).toInstant().toEpochMilli() } ?: now
            ProposalKind.ADD_CALENDAR_EVENT -> minOf(now + 7 * 86_400_000L, s.payload["end"]?.let { dateTime(it, zone).toInstant().toEpochMilli() } ?: now)
            ProposalKind.SET_RELATION -> now + 7 * 86_400_000L
            ProposalKind.ADD_BIRTHDAY, ProposalKind.ADD_TO_LIST -> now + 3 * 86_400_000L
            // Music is about this moment: a stale "play" should not start an hour later.
            ProposalKind.MUSIC_CONTROL, ProposalKind.MUSIC_WHILE_DRIVING -> now + 10 * 60_000L
            // "Turn off the lamp" is about now too.
            ProposalKind.HOME_CONTROL, ProposalKind.LIGHTS -> now + 10 * 60_000L
            ProposalKind.PLAY_MUSIC -> now + 2 * 3_600_000L
            ProposalKind.PLACE_CALL -> now + 30 * 60_000L
            ProposalKind.ADD_AUTO_APPROVE -> now + 7 * 86_400_000L
            ProposalKind.SET_SCHEDULE, ProposalKind.SET_TRIPS -> now + 3 * 86_400_000L
            ProposalKind.SWITCH_MODEL, ProposalKind.BUILD_APP -> now + 7 * 86_400_000L
            // A task paused at its budget waits a day for this answer (Tasks.BUDGET_WAIT_MS).
            ProposalKind.TASK_BUDGET -> now + 24 * 3_600_000L
            else -> now + 24 * 3_600_000L
        }
    }

    /** Their local time on a card, and the heads-up when it is late there. */
    private fun theirs(p: Map<String, String>, key: String, zone: ZoneId): List<Pair<String, String>> {
        val their = TwoClocks.zoneOrNull(p["with_zone"]) ?: return emptyList()
        val at = runCatching { dateTime(p[key]!!, zone).toInstant() }.getOrNull() ?: return emptyList()
        val r = TwoClocks(zone).reach(at, their)
        return listOfNotNull("For ${p["with"]}" to r.local, r.nextText?.let { "Late there" to "Better $it" })
    }

    /** Labelled values for the card: LABEL then value. */
    fun facts(s: Step, zone: ZoneId): List<Pair<String, String>> {
        val p = s.payload
        fun dt(key: String) = p[key]?.let { v -> runCatching { if (v.length == 10) DAY.format(LocalDate.parse(v)) else DAY_TIME.format(dateTime(v, zone)) }.getOrDefault(v) }
        return when (s.kind) {
            ProposalKind.SET_ALARM -> listOfNotNull("Time" to TIME.format(LocalDateTime.of(2000, 1, 1, p["hour"]!!.toInt(), p["minute"]!!.toInt())), p["label"]?.let { "Label" to it })
            ProposalKind.SET_TIMER, ProposalKind.SHORT_TIMER -> listOfNotNull("Length" to length(p["seconds"]!!.toInt()), p["label"]?.let { "Label" to it })
            ProposalKind.ADD_REMINDER -> listOf("When" to dt("at")!!, "Says" to p["text"]!!) + theirs(p, "at", zone)
            ProposalKind.ADD_TASK -> listOfNotNull(
                "Task" to p["title"]!!, "List" to (runCatching { dev.phonerecall.data.TaskList.valueOf(p["list"]!!).label }.getOrNull() ?: p["list"]!!.lowercase().replaceFirstChar { it.uppercase() }),
                dt("due")?.let { "Due" to it },
                dev.phonerecall.data.TaskContext.of(p["context"])?.let { "Context" to it.label },
                p["waiting_on"]?.let { "Waiting on" to it }, dt("follow_up")?.let { "Check back" to it },
            )
            ProposalKind.COMPLETE_TASK -> listOf("Task" to p["title"]!!)
            ProposalKind.ADD_CALENDAR_EVENT -> listOfNotNull(
                "Event" to p["title"]!!, "Starts" to dt("start")!!, "Ends" to dt("end")!!,
                "Calendar" to (p["calendar"] ?: "Not chosen yet"),
            ) + theirs(p, "start", zone)
            ProposalKind.CREATE_CARD -> listOfNotNull("Title" to p["title"]!!, p["body"]?.takeIf { it.isNotEmpty() }?.let { "Says" to it }, "Stays" to "${p["hours"]} h")
            ProposalKind.SET_RELATION -> listOf("Saved as" to p["term"]!!, "Relation" to (Relation.of(p["relation"])?.word ?: p["relation"]!!))
            ProposalKind.ADD_BIRTHDAY -> listOfNotNull(
                "Person" to p["name"]!!,
                "Date" to (dayText(p["date"]!!) + ", every year"),
                p["old_date"]?.let { "Saved now" to dayText(it) },
                p["source"]?.let { "Seen in" to it },
                "Calendar" to "Google Calendar, Birthdays",
                "Account" to dev.phonerecall.data.MyAccounts.PRIMARY,
            )
            ProposalKind.ADD_TO_LIST -> listOf("List" to p["section"]!!, "Items" to p["items"]!!)
            ProposalKind.PLAY_MUSIC, ProposalKind.MUSIC_WHILE_DRIVING -> listOfNotNull(
                // A search shows its words (the title already has the name); a URI shows its name.
                "Play" to (if (p["uri"] == null) p["query"] ?: p["name"]!! else p["name"]!!), p["reason"]?.let { "Why" to it }, (if (p["shuffle"] == "true") "Shuffle" to "On" else null),
            )
            ProposalKind.MUSIC_CONTROL -> listOf("Do" to (ACTIONS[p["action"]] ?: p["action"]!!))
            ProposalKind.HOME_CONTROL, ProposalKind.LIGHTS -> listOfNotNull(
                "Device" to p["device"]!!, p["room"]?.let { "Room" to it },
                "Do" to when (p["action"]) {
                    "on" -> "Turn on"; "off" -> "Turn off"; "brightness" -> "Brightness ${p["value"]}%"
                    "color_temp" -> "White ${p["value"]} K"; else -> "Color ${p["value"]}"
                },
            )
            ProposalKind.PLACE_CALL -> listOfNotNull(
                "Who" to p["name"]!!,
                "How" to when (p["via"]) { "whatsapp_video" -> "WhatsApp video"; "whatsapp_voice" -> "WhatsApp voice"; else -> "Phone call" },
                p["there"]?.let { "There" to it }, p["awake"]?.let { "Awake" to it.replaceFirstChar(Char::uppercase) },
            )
            ProposalKind.BOOKMARK_FOR -> listOf("For" to p["person"]!!, "Tell" to p["note"]!!)
            ProposalKind.ADD_AUTO_APPROVE -> listOfNotNull(
                "Kind" to (ProposalKind.of(p["kind"]!!)?.label ?: p["kind"]!!), p["why"]?.let { "Why" to it },
                "Then" to "Runs without asking and shows as done automatically, with Undo. Themis can turn it off.",
            )
            ProposalKind.SET_SCHEDULE -> listOfNotNull(
                "Schedule" to dev.phonerecall.mind.Schedules.PREFIX + p["name"]!!,
                p["schedule"]?.let { "When" to "${dev.phonerecall.mind.Cron.words(it)}, ${zone.id}" },
                p["prompt"]?.let { "Each run" to it }, p["why"]?.let { "Why" to it },
                if (p["test_run"] == "true") "First" to "One test run now; its result goes to Agora, not to you" else null,
                "Then" to "Applied on Athena's next sync. Horologion shows it.",
            )
            ProposalKind.SWITCH_MODEL -> listOfNotNull(
                "Feature" to (dev.phonerecall.models.Features.of(p["feature"]!!)?.label ?: p["feature"]!!),
                "Now" to dev.phonerecall.models.ModelChoice.book.of(dev.phonerecall.models.Features.of(p["feature"]!!) ?: dev.phonerecall.models.Features.ATHENA),
                "Switch to" to p["model"]!!, p["why"]?.let { "Why" to it },
                "Then" to "Agents get a new version on Athena's next sync; other features use it on their next call. Undo puts the old one back.",
            )
            ProposalKind.BUILD_APP -> listOfNotNull(
                "Build" to p["title"]!!, "Need" to p["need"]!!, "Feasible" to p["feasible"]!!,
                p["approach"]?.let { "Approach" to it }, p["files"]?.let { "Files" to it }, p["risk"]?.let { "Risk" to it },
                p["needs"]?.let { "Needs you" to it }, p["done_when"]?.let { "Done when" to it },
                "Then" to "Prometheus builds it on a branch and opens a PR (up to $${dev.phonerecall.prometheus.Forge.BUDGET_CENTS / 100}). Merge and release still need your fingerprint.",
            )
            ProposalKind.SET_TRIPS -> listOfNotNull(
                "Trips" to p["trips"]!!.removePrefix("Trips: "), p["why"]?.let { "Why" to it },
                "Then" to "Argo keeps these trips. Later bookings join them; one that doesn't fit asks you first.",
            )
            ProposalKind.IDEA -> listOfNotNull(
                "Idea" to p["title"]!!, "Why" to p["why"]!!,
                if (p["action"] != null) "Then" to "A learned shortcut: ${p["action_label"]} when ${p["trigger_label"]}" else "Then" to "A request drafted for Prometheus; you read it and send it",
            )
            ProposalKind.LEARN_SHORTCUT -> listOfNotNull(
                "When" to p["trigger_label"]!!, "Then" to p["action_label"]!!, p["why"]?.let { "Why" to it },
                "How" to "A one-tap card each time. Horologion can make it run on its own (alarms and lights only) or delete it.",
            )
            ProposalKind.SET_ROUTINE -> listOf(
                (if (p["delete"] != null) "Delete" else "Routine") to p["sentence"]!!,
                "How" to if (p["delete"] != null) "Gone from Hestia; Undo brings it back." else "Runs on its own with an Undo each time. Change it by telling Athena; turn it off or delete it in Hestia.",
            )
            ProposalKind.TASK_BUDGET -> listOf(
                "Task" to p["title"]!!, "Up to" to "${p["cents"]} cents (it started with ${dev.phonerecall.mind.Tasks.DEFAULT_CENTS})",
                "Then" to "Its session may spend that much; if it paused at its budget, it carries on. Horologion can cancel it.",
            )
        }
    }

    fun epoch(s: String, zone: ZoneId): Long = dateTime(s, zone).toInstant().toEpochMilli()
    fun instantText(ms: Long, zone: ZoneId): String = DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(zone))
}

/** Where proposals live. `casStatus` is the one atomic step that makes "executes once" true. */
interface ProposalStore {
    fun insert(p: Proposal): Long
    fun get(id: Long): Proposal?
    fun save(p: Proposal)
    fun casStatus(id: Long, from: ProposalStatus, to: ProposalStatus): Boolean
    fun all(): List<Proposal>
}

/** Runs a confirmed step. Plain Android calls, no model anywhere below this. */
interface StepExecutor {
    /** Does the step; throws on failure. Returns a short note and, when it can be undone, what undo needs. */
    fun run(step: Step): ExecResult
    fun undo(step: Step): Boolean
}

data class ExecResult(val note: String, val undo: Map<String, String>? = null)

fun interface Audit {
    fun log(actor: String, action: String, detail: String)
}

/** What happened when he (or the auto-approve list) acted on a proposal. */
sealed class Decision {
    abstract val proposal: Proposal?
    data class Done(override val proposal: Proposal) : Decision()
    data class Failed(override val proposal: Proposal, val reason: String) : Decision()
    data class Expired(override val proposal: Proposal) : Decision()
    data class Rejected(override val proposal: Proposal) : Decision()
    data class Undone(override val proposal: Proposal) : Decision()
    data class Edited(override val proposal: Proposal) : Decision()
    /** Nothing ran: not pending, a choice is missing, or no step is selected. */
    data class Refused(override val proposal: Proposal?, val reason: String) : Decision()
}

data class RawStep(val kind: ProposalKind, val args: Map<String, String>)

sealed class ProposeResult {
    data class Created(val proposal: Proposal, val decision: Decision? = null) : ProposeResult()
    data class Invalid(val reason: String) : ProposeResult()
}

/**
 * The Fates: every proposal's life. propose -> pending -> (confirm | reject | expire). Confirm is the
 * only path to execution: it requires PENDING and a [Binding] to what he saw, claims the row atomically
 * (PENDING to RUNNING), revalidates against current state, runs, and records the outcome. Calls are
 * serialized. Every decision also feeds the fatigue [meter].
 */
class Fates(
    private val store: ProposalStore,
    private val state: LocalState,
    private val executor: StepExecutor,
    private val audit: Audit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val meter: FatesMeter = MemoryMeter(),
) {
    /** Kinds the owner put on the auto-approve list. Empty by default; ineligible kinds are ignored. */
    @Volatile var autoApprove: Set<ProposalKind> = emptySet()

    private val lock = Any()

    fun eligibleForAuto(p: Proposal, list: Set<ProposalKind> = autoApprove): Boolean =
        p.needs == null && p.steps.filter { it.selected }.let { s -> s.isNotEmpty() && s.all { it.kind.autoEligible && it.kind.tier == 2 && it.kind in list } }

    /**
     * `onRequest`: he asked for this (Delphi, the assistant), so a quiet kind is still allowed. Everything
     * Athena offers on her own (the senses, music, the mind) is held back while its kind is quiet.
     */
    fun propose(raw: List<RawStep>, title: String?, conversationId: Long?, proposer: String = "Athena", onRequest: Boolean = conversationId != null): ProposeResult =
        synchronized(lock) { create(raw, title, conversationId, proposer, onRequest, own = false) }

    /**
     * Hermes: a command he typed or said himself ("wake me at 6"), or his one tap on a call's confirm. It is
     * validated like any proposal, then runs at once as his and is kept here, so Undo and the audit work the
     * same. Never used for anything Athena or the senses suggest; those stay proposals.
     */
    fun own(raw: List<RawStep>, title: String?, proposer: String = "Hermes"): ProposeResult =
        synchronized(lock) { create(raw, title, null, proposer, onRequest = true, own = true) }

    private fun create(raw: List<RawStep>, title: String?, conversationId: Long?, proposer: String, onRequest: Boolean, own: Boolean): ProposeResult {
        if (raw.isEmpty()) return ProposeResult.Invalid("No steps")
        if (raw.size > 8) return ProposeResult.Invalid("At most 8 steps in one plan")
        val now = clock()
        if (!onRequest) for (k in raw.map { it.kind }.distinct()) {
            val until = quietUntil(k, now) ?: continue
            audit.log("Fates", "held back", "${k.label}: quiet after ${Fatigue.QUIET_REJECTS} rejections")
            return ProposeResult.Invalid("${k.label} proposals are quiet until ${Validate.instantText(until, state.zone)} after ${Fatigue.QUIET_REJECTS} rejections; he can still ask for one")
        }
        val steps = raw.map { r ->
            when (val c = Validate.step(r.kind, r.args, state, now)) {
                is Check.Ok -> c.step
                is Check.Bad -> return ProposeResult.Invalid("${r.kind.wire}: ${c.reason}")
            }
        }
        val name = title?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: steps.first().summary.takeIf { steps.size == 1 } ?: "Plan with ${steps.size} steps"
        val p0 = Proposal(createdAt = now, expiresAt = Validate.ttl(steps, now, state.zone), status = ProposalStatus.PENDING, title = name, steps = steps, conversationId = conversationId, proposer = proposer)
        val p = p0.copy(id = store.insert(p0))
        audit.log(proposer, "proposed", "#${p.id} ${p.title} (tier ${p.tier})")
        val ran = when {
            own -> confirmLocked(p.id, seen = null, own = true)
            eligibleForAuto(p) -> confirmLocked(p.id, seen = null)
            else -> null
        }
        return ProposeResult.Created(store.get(p.id) ?: p, ran)
    }

    /** What a card binds when it shows him a proposal. Confirm takes it back. */
    fun bind(p: Proposal): Binding = Bindings.of(p, state.writableCalendars(), clock())

    /** His tap. `seen` is the binding the card made when it showed him this proposal. */
    fun confirm(id: Long, seen: Binding): Decision = synchronized(lock) { confirmLocked(id, seen) }

    /** `seen` is null for the auto-approve list and his own commands, which bind at the moment they run. */
    private fun confirmLocked(id: Long, seen: Binding?, own: Boolean = false): Decision {
        val auto = seen == null && !own
        val p = store.get(id) ?: return Decision.Refused(null, "No such proposal")
        if (p.status != ProposalStatus.PENDING) return Decision.Refused(p, "Already ${p.status.name.lowercase()}")
        val now = clock()
        if (now >= p.expiresAt) {
            if (store.casStatus(id, ProposalStatus.PENDING, ProposalStatus.EXPIRED)) {
                val e = p.copy(status = ProposalStatus.EXPIRED, decidedAt = now, outcome = "Expired before it was confirmed")
                store.save(e); audit.log("Zeus", "expired", "#$id ${p.title}"); signal(p, Signal.IGNORE, now)
                return Decision.Expired(e)
            }
            return Decision.Refused(store.get(id), "Changed while confirming")
        }
        val calendars = state.writableCalendars()
        val bound = Bindings.of(p, calendars, seen?.boundAt ?: now)
        if (seen != null) Bindings.check(seen, bound, p.tier, now)?.let { reason ->
            audit.log("The owner", "confirm refused", "#$id ${p.title}: $reason")
            return Decision.Refused(p, reason)
        }
        p.needs?.let { return Decision.Refused(p, needText(it)) }
        val selected = p.steps.filter { it.selected }
        if (selected.isEmpty()) return Decision.Refused(p, "Pick at least one step")
        if (!store.casStatus(id, ProposalStatus.PENDING, ProposalStatus.RUNNING)) return Decision.Refused(store.get(id), "Changed while confirming")
        val actor = if (auto) "Auto-approve" else "The owner"

        // Revalidate every selected step against the phone as it is now. Any failure: nothing runs.
        val fresh = ArrayList<Step>()
        for (s in p.steps) {
            if (!s.selected) { fresh += s; continue }
            when (val c = Validate.step(s.kind, s.payload, state, now)) {
                is Check.Ok -> {
                    c.step.needs?.let { return fail(p, actor, needText(it), now) }
                    fresh += c.step.copy(selected = true)
                }
                is Check.Bad -> return fail(p, actor, c.reason, now)
            }
        }
        // The phone moved under him (an item already on the list, a calendar now in another account): what
        // would run is not what he saw. Back to pending, with the fresh steps, for another look.
        if (Bindings.check(bound, Bindings.of(p.copy(steps = fresh), calendars, bound.boundAt), p.tier, now) != null) {
            val back = p.copy(steps = fresh)    // still PENDING: the save releases the RUNNING claim
            store.save(back)
            audit.log(actor, "confirm refused", "#$id ${p.title}: ${Bindings.CHANGED}")
            return Decision.Refused(back, Bindings.CHANGED)
        }
        val ran = ArrayList<Step>()
        var failure: String? = null
        for (s in fresh) {
            if (!s.selected || failure != null) { ran += s; continue }
            try {
                val r = executor.run(s)
                ran += s.copy(result = r.note, undo = r.undo)
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
                ran += s.copy(result = "Not done: $failure")
            }
        }
        val status = if (failure == null) ProposalStatus.CONFIRMED else ProposalStatus.FAILED
        val done = p.copy(
            status = status, steps = ran, auto = auto, decidedAt = now,
            outcome = failure?.let { "Not done: $it" } ?: if (auto) "Done automatically" else "Done",
        )
        store.save(done)
        audit.log(actor, if (failure == null) "confirmed" else "failed", "#$id ${p.title}" + (failure?.let { ": $it" } ?: ""))
        // His own commands are not answers to a suggestion, so they don't teach the fatigue meter.
        if (!auto && !own && failure == null) signal(p, Signal.CONFIRM, now)
        return if (failure == null) Decision.Done(done) else Decision.Failed(done, failure)
    }

    private fun fail(p: Proposal, actor: String, reason: String, now: Long): Decision {
        val f = p.copy(status = ProposalStatus.FAILED, decidedAt = now, outcome = "Not done: $reason")
        store.save(f)
        audit.log(actor, "failed", "#${p.id} ${p.title}: $reason")
        return Decision.Failed(f, reason)
    }

    fun reject(id: Long): Decision = synchronized(lock) {
        val p = store.get(id) ?: return Decision.Refused(null, "No such proposal")
        if (!store.casStatus(id, ProposalStatus.PENDING, ProposalStatus.REJECTED)) return Decision.Refused(p, "Already ${p.status.name.lowercase()}")
        val r = p.copy(status = ProposalStatus.REJECTED, decidedAt = clock(), outcome = "Rejected")
        store.save(r); audit.log("The owner", "rejected", "#$id ${p.title}"); signal(p, Signal.REJECT, r.decidedAt!!)
        Decision.Rejected(r)
    }

    /** Edit a pending step's arguments (or fill a missing choice). Revalidated like a new proposal. */
    fun edit(id: Long, index: Int, payload: Map<String, String>): Decision = synchronized(lock) {
        val p = store.get(id) ?: return Decision.Refused(null, "No such proposal")
        if (p.status != ProposalStatus.PENDING) return Decision.Refused(p, "Already ${p.status.name.lowercase()}")
        val old = p.steps.getOrNull(index) ?: return Decision.Refused(p, "No such step")
        val now = clock()
        return when (val c = Validate.step(old.kind, payload, state, now)) {
            is Check.Bad -> Decision.Refused(p, c.reason)
            is Check.Ok -> {
                val steps = p.steps.toMutableList().also { it[index] = c.step.copy(selected = old.selected) }
                val e = p.copy(steps = steps, title = if (steps.size == 1) c.step.summary else p.title, expiresAt = minOf(p.createdAt + 7 * 86_400_000L, Validate.ttl(steps, now, state.zone)))
                store.save(e); audit.log("The owner", "edited", "#$id ${e.title}"); signal(listOf(old.kind), Signal.EDIT, now)
                Decision.Edited(e)
            }
        }
    }

    /** Re-checks missing choices (after he grants calendar writes, say). */
    fun refresh(id: Long): Proposal? = synchronized(lock) {
        val p = store.get(id)?.takeIf { it.status == ProposalStatus.PENDING } ?: return store.get(id)
        val now = clock()
        val steps = p.steps.map { s -> (Validate.step(s.kind, s.payload, state, now) as? Check.Ok)?.step?.copy(selected = s.selected) ?: s }
        p.copy(steps = steps).also { store.save(it) }
    }

    fun toggle(id: Long, index: Int): Proposal? = synchronized(lock) {
        val p = store.get(id)?.takeIf { it.status == ProposalStatus.PENDING && index in it.steps.indices } ?: return store.get(id)
        p.copy(steps = p.steps.mapIndexed { i, s -> if (i == index) s.copy(selected = !s.selected) else s }).also { store.save(it) }
    }

    fun undo(id: Long): Decision = synchronized(lock) {
        val p = store.get(id) ?: return Decision.Refused(null, "No such proposal")
        if (!p.undoable) return Decision.Refused(p, "Nothing to undo")
        val ok = p.steps.filter { it.undo != null }.all { runCatching { executor.undo(it) }.getOrDefault(false) }
        val u = p.copy(status = ProposalStatus.UNDONE, outcome = if (ok) "Undone" else "Partly undone")
        store.save(u); audit.log("The owner", "undid", "#$id ${p.title}")
        Decision.Undone(u)
    }

    /** Marks every pending proposal past its TTL as expired. Returns those it expired. */
    fun sweep(): List<Proposal> = synchronized(lock) {
        val now = clock()
        store.all().filter { it.status == ProposalStatus.PENDING && now >= it.expiresAt }.mapNotNull { p ->
            if (!store.casStatus(p.id, ProposalStatus.PENDING, ProposalStatus.EXPIRED)) return@mapNotNull null
            p.copy(status = ProposalStatus.EXPIRED, decidedAt = now, outcome = "Expired before it was confirmed").also {
                store.save(it); audit.log("Zeus", "expired", "#${p.id} ${p.title}"); signal(p, Signal.IGNORE, now)
            }
        }
    }

    // ---- the fatigue meter ----

    private fun signal(p: Proposal, s: Signal, at: Long) = signal(p.steps.filter { it.selected }.map { it.kind }, s, at)

    private fun signal(kinds: List<ProposalKind>, s: Signal, at: Long) {
        for (k in kinds.distinct()) if (Fatigue.tracked(k)) runCatching { meter.record(KindEvent(k, s, at)) }
    }

    /** His answer to "Was this useful?" on a card of this kind. */
    fun useful(kind: ProposalKind, yes: Boolean) = synchronized(lock) {
        signal(listOf(kind), if (yes) Signal.USEFUL else Signal.NOT_USEFUL, clock())
        audit.log("The owner", if (yes) "found useful" else "found not useful", kind.label)
    }

    private fun recent(now: Long) = runCatching { meter.events(now - Fatigue.HORIZON) }.getOrDefault(emptyList())

    fun quietUntil(kind: ProposalKind, now: Long = clock()): Long? = Fatigue.quietUntil(recent(now), kind, now)

    /** The last 30 days per kind, plus when a quiet kind wakes up, for Themis → Auto-approve. */
    fun stats(now: Long = clock()): Map<ProposalKind, Pair<KindStats, Long?>> = recent(now).let { e ->
        ProposalKind.entries.filter { Fatigue.tracked(it) }.associateWith { Fatigue.stats(e, it, now) to Fatigue.quietUntil(e, it, now) }
    }

    /** Kinds he has never rated: the pool "Was this useful?" picks from. */
    fun unrated(now: Long = clock()): Set<ProposalKind> = recent(now).let { e ->
        ProposalKind.entries.filter { k -> Fatigue.tracked(k) && e.none { it.kind == k && (it.signal == Signal.USEFUL || it.signal == Signal.NOT_USEFUL) } }.toSet()
    }

    /** At most one meta-proposal a week: "Add <kind> to auto-approve?". Returns it when one was made. */
    fun offerAutoApprove(): Proposal? = synchronized(lock) {
        val now = clock()
        val e = recent(now)
        val k = Fatigue.offer(e, autoApprove, now) ?: return null
        val s = Fatigue.stats(e, k, now)
        runCatching { meter.record(KindEvent(k, Signal.OFFERED, now)) }
        val r = propose(
            listOf(RawStep(ProposalKind.ADD_AUTO_APPROVE, mapOf("kind" to k.wire, "why" to "You confirmed ${s.confirms} and turned down none in 30 days"))),
            "Add ${k.label.lowercase()} to auto-approve?", null, "Fates", onRequest = true,
        )
        (r as? ProposeResult.Created)?.proposal
    }
}
