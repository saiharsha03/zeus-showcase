package dev.phonerecall.brain

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskStore
import java.time.LocalDate
import java.time.ZoneId

/**
 * The hands. Runs one confirmed, revalidated step with plain Android calls; nothing here knows a
 * model exists. Throws on failure so Fates records it.
 */
class AndroidExecutor(
    private val context: Context,
    private val db: BrainDb,
    private val tasks: TaskStore,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Phase 2b: the people graph and the lists. */
    private val hands: SenseHands? = null,
    /** Spotify (or the fallback player). */
    private val music: dev.phonerecall.music.MusicHands? = null,
    /** Hestia (lights and plugs through Google Home). */
    private val home: dev.phonerecall.hestia.HomeHands? = null,
    /** Athena's schedules (the book her mind deploys from). */
    private val schedules: ((Map<String, String>) -> String)? = null,
    /** Argo: a confirmed trip list from Athena. */
    private val trips: ((Map<String, String>) -> String)? = null,
    /** Prometheus: a confirmed Build card becomes the normal build request. */
    private val builds: ((Map<String, String>) -> String)? = null,
    /** A spawned task may spend more (mind/Tasks.kt). */
    private val taskBudget: ((Map<String, String>) -> String)? = null,
) : StepExecutor {

    /** What he did through Zeus feeds the learned shortcuts (music, alarms, Hestia), once it worked. */
    override fun run(step: Step): ExecResult = exec(step).also { runCatching { dev.phonerecall.shortcuts.ShortcutsCore.acted(context, step) } }

    private fun exec(step: Step): ExecResult {
        val p = step.payload
        return when (step.kind) {
            ProposalKind.SET_ALARM -> {
                start(Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_HOUR, p.getValue("hour").toInt())
                    putExtra(AlarmClock.EXTRA_MINUTES, p.getValue("minute").toInt())
                    p["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                })
                AlarmWatch.get(context).zeusSet(p.getValue("hour").toInt(), p.getValue("minute").toInt(), p["label"], p["days"].orEmpty())
                ExecResult(step.summary, mapOf("hour" to p.getValue("hour"), "minute" to p.getValue("minute")))
            }
            ProposalKind.SET_TIMER, ProposalKind.SHORT_TIMER -> {
                start(Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, p.getValue("seconds").toInt())
                    p["label"]?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                })
                // A running timer can't be cancelled by another app, so there is no undo.
                ExecResult(step.summary)
            }
            ProposalKind.ADD_REMINDER -> {
                val at = Validate.epoch(p.getValue("at"), zone())
                val id = db.addReminder(at, p.getValue("text"))
                Reminders.arm(context, id, at)
                ExecResult(step.summary, mapOf("reminder_id" to "$id"))
            }
            ProposalKind.ADD_TASK -> {
                val due = p["due"]
                val hasTime = due != null && due.length > 10
                val dueMs = due?.let { if (hasTime) Validate.epoch(it, zone()) else LocalDate.parse(it).atStartOfDay(zone()).toInstant().toEpochMilli() }
                val followUp = p["follow_up"]?.let { LocalDate.parse(it).atStartOfDay(zone()).toInstant().toEpochMilli() }
                val id = tasks.add(
                    p.getValue("title"), TaskList.valueOf(p["list"] ?: "INBOX"), due = dueMs, dueHasTime = hasTime,
                    context = dev.phonerecall.data.TaskContext.of(p["context"]), waitingOn = p["waiting_on"], followUp = followUp,
                )
                ExecResult(step.summary, mapOf("task_id" to "$id"))
            }
            ProposalKind.COMPLETE_TASK -> {
                val id = p.getValue("task_id").toLong()
                tasks.complete(id, System.currentTimeMillis())
                ExecResult(step.summary, mapOf("task_id" to "$id"))
            }
            ProposalKind.ADD_CALENDAR_EVENT -> {
                val allDay = p["all_day"] == "true"
                val z = if (allDay) ZoneId.of("UTC") else zone()
                fun ms(s: String) = if (allDay) LocalDate.parse(s).atStartOfDay(z).toInstant().toEpochMilli() else Validate.epoch(s, z)
                val values = ContentValues().apply {
                    put(CalendarContract.Events.CALENDAR_ID, p.getValue("calendar_id").toLong())
                    put(CalendarContract.Events.TITLE, p.getValue("title"))
                    put(CalendarContract.Events.DTSTART, ms(p.getValue("start")))
                    put(CalendarContract.Events.DTEND, ms(p.getValue("end")))
                    put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                    put(CalendarContract.Events.EVENT_TIMEZONE, z.id)
                }
                val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values) ?: error("The calendar did not accept the event")
                // Calendar writes are tier 3: confirmed by hand, never auto-approved, so no undo is offered.
                ExecResult(step.summary + " (event ${uri.lastPathSegment})")
            }
            ProposalKind.CREATE_CARD -> {
                val now = System.currentTimeMillis()
                val id = db.addCard(p.getValue("title"), p["body"].orEmpty(), now, now + p.getValue("hours").toLong() * 3_600_000L)
                ExecResult(step.summary, mapOf("card_id" to "$id"))
            }
            ProposalKind.SET_RELATION -> {
                val h = hands ?: error("People are not available")
                val before = h.setRelation(p.getValue("person"), Relation.valueOf(p.getValue("relation")))
                ExecResult(step.summary, mapOf("person" to p.getValue("person"), "before" to before.orEmpty()))
            }
            ProposalKind.ADD_BIRTHDAY -> {
                // Google Calendar's own Birthday type (events.insert, eventType birthday), never a plain yearly event or a contact.
                val undo = birthdays().save(p)
                hands?.birthdayAdded(p.getValue("name"))
                ExecResult(step.summary, undo)
            }
            ProposalKind.ADD_TO_LIST -> {
                val h = hands ?: error("Lists are not available")
                val ids = h.addToList(p.getValue("section"), p.getValue("items").split(',').map { it.trim() }.filter { it.isNotEmpty() })
                ExecResult(step.summary, mapOf("item_ids" to ids.joinToString(",")))
            }
            ProposalKind.PLAY_MUSIC, ProposalKind.MUSIC_WHILE_DRIVING -> (music ?: error("Music is not set up")).play(p)
            ProposalKind.MUSIC_CONTROL -> (music ?: error("Music is not set up")).control(p.getValue("action"))
            ProposalKind.HOME_CONTROL, ProposalKind.LIGHTS -> (home ?: error("Hestia is not set up")).control(p)
            // A call can't be taken back, so there is no undo.
            ProposalKind.PLACE_CALL -> dev.phonerecall.calls.Calls.place(context, p)
            ProposalKind.BOOKMARK_FOR -> ExecResult(step.summary, mapOf("bookmark_id" to "${dev.phonerecall.rhythms.Rhythms.bookmark(context, p.getValue("person"), p.getValue("note"))}"))
            ProposalKind.SET_SCHEDULE -> ExecResult((schedules ?: error("Athena's mind isn't set up")).invoke(p))
            ProposalKind.SET_TRIPS -> ExecResult((trips ?: error("Argo isn't set up")).invoke(p))
            ProposalKind.TASK_BUDGET -> ExecResult((taskBudget ?: error("Athena's mind isn't set up")).invoke(p))
            ProposalKind.LEARN_SHORTCUT -> dev.phonerecall.shortcuts.ShortcutsCore.accept(context, p).let { (words, id) -> ExecResult(words, mapOf("rule" to id)) }
            ProposalKind.SET_ROUTINE -> dev.phonerecall.hestia.HestiaRoutines.apply(context, p).let { (words, undo) -> ExecResult(words, undo) }
            ProposalKind.IDEA -> if (p["action"] != null) dev.phonerecall.shortcuts.ShortcutsCore.accept(context, p).let { (words, id) -> ExecResult(words, mapOf("rule" to id)) }
                else ExecResult(dev.phonerecall.shortcuts.ShortcutsCore.draft(context, p))
            // A request can't be unsent; stopping it is Prometheus's Stop.
            ProposalKind.BUILD_APP -> ExecResult((builds ?: error("Prometheus isn't set up")).invoke(p))
            ProposalKind.SWITCH_MODEL -> {
                val f = dev.phonerecall.models.Features.of(p.getValue("feature")) ?: error("Not a feature with a model")
                val before = dev.phonerecall.models.ModelChoice.book.set(f, p.getValue("model"))
                ExecResult("${f.label} now uses ${p.getValue("model")}", mapOf("feature" to f.key, "before" to before))
            }
            ProposalKind.ADD_AUTO_APPROVE -> {
                val k = ProposalKind.of(p.getValue("kind"))?.takeIf { it in ProposalKind.autoCandidates } ?: error("That kind can never be automatic")
                val added = (hands ?: error("Settings are not available")).setAuto(k, true)
                ExecResult("${k.label} now runs without asking", if (added) mapOf("kind" to k.wire) else null)
            }
        }
    }

    private fun birthdays() = dev.phonerecall.data.CalendarBirthdays(dev.phonerecall.music.GatedHttp, dev.phonerecall.data.GoogleCalendarAuth(context), zone)

    override fun undo(step: Step): Boolean {
        val u = step.undo ?: return false
        return when (step.kind) {
            ProposalKind.ADD_TASK -> { tasks.delete(u.getValue("task_id").toLong()); true }
            ProposalKind.COMPLETE_TASK -> { tasks.uncomplete(u.getValue("task_id").toLong()); true }
            ProposalKind.CREATE_CARD -> { db.deleteCard(u.getValue("card_id").toLong()); true }
            ProposalKind.ADD_REMINDER -> { val id = u.getValue("reminder_id").toLong(); Reminders.cancel(context, id); db.deleteReminder(id); true }
            ProposalKind.SET_RELATION -> { hands?.restoreRelation(u.getValue("person"), Relation.of(u["before"])); hands != null }
            ProposalKind.ADD_BIRTHDAY -> birthdays().undo(u)
            ProposalKind.ADD_TO_LIST -> { hands?.removeItems(u.getValue("item_ids").split(',').mapNotNull { it.toLongOrNull() }); hands != null }
            // "Playing X" with Undo: undo pauses; a skip goes back; a pause resumes.
            ProposalKind.PLAY_MUSIC, ProposalKind.MUSIC_WHILE_DRIVING, ProposalKind.MUSIC_CONTROL -> music?.let { it.control(u.getValue("action")); true } ?: false
            // Puts the device back the way it was (on or off, the old brightness or white).
            ProposalKind.HOME_CONTROL, ProposalKind.LIGHTS -> home?.restore(u) ?: false
            ProposalKind.BOOKMARK_FOR -> { dev.phonerecall.rhythms.Rhythms.removeBookmark(context, u.getValue("bookmark_id").toLong()); true }
            ProposalKind.ADD_AUTO_APPROVE -> ProposalKind.of(u.getValue("kind"))?.let { hands?.setAuto(it, false); hands != null } ?: false
            ProposalKind.SWITCH_MODEL -> dev.phonerecall.models.Features.of(u.getValue("feature"))?.let { dev.phonerecall.models.ModelChoice.book.set(it, u.getValue("before")); true } ?: false
            ProposalKind.LEARN_SHORTCUT, ProposalKind.IDEA -> { dev.phonerecall.shortcuts.ShortcutsCore.undo(context, u.getValue("rule")); true }
            ProposalKind.SET_ROUTINE -> dev.phonerecall.hestia.HestiaRoutines.restore(context, u)
            ProposalKind.SET_ALARM -> {
                // Asks the clock app to dismiss the matching alarm; some clock apps show their own picker.
                start(Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
                    putExtra(AlarmClock.EXTRA_HOUR, u.getValue("hour").toInt())
                    putExtra(AlarmClock.EXTRA_MINUTES, u.getValue("minute").toInt())
                })
                AlarmWatch.get(context).zeusForget(u.getValue("hour").toInt(), u.getValue("minute").toInt())
                true
            }
            else -> false
        }
    }

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(context.packageManager) == null) error("No clock app takes this")
        context.startActivity(i)
    }
}

/** What confirmed phase 2b proposals change: the people graph and the lists. BrainCore implements it. */
interface SenseHands {
    /** Sets a confirmed relation; returns the one it replaced. */
    fun setRelation(personKey: String, relation: Relation): String?
    fun restoreRelation(personKey: String, previous: Relation?)
    /** Adds items to a list (making the section if needed); returns their ids. */
    fun addToList(section: String, items: List<String>): List<Long>
    fun removeItems(ids: List<Long>)
    fun birthdayAdded(name: String)
    /** Puts a kind on (or takes it off) the auto-approve list; true when that changed it. */
    fun setAuto(kind: ProposalKind, on: Boolean): Boolean = error("Auto-approve is not available here")
}

/** Zeus's own reminders: an exact alarm per reminder, re-armed after a restart. */
object Reminders {
    const val ACTION = "dev.phonerecall.REMINDER"
    private const val CHANNEL = "reminders"

    private fun pending(context: Context, id: Long, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, (REQ_BASE + id).toInt(), Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("id", id), flags or PendingIntent.FLAG_IMMUTABLE)

    fun arm(context: Context, id: Long, at: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = pending(context, id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    fun cancel(context: Context, id: Long) {
        pending(context, id, PendingIntent.FLAG_NO_CREATE)?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    fun post(context: Context, r: Reminder) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            (REQ_BASE + r.id).toInt(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_owl)
                .setContentTitle(r.text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .build(),
        )
    }

    private const val REQ_BASE = 40_000L
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        Thread {
            try {
                val db = BrainDb(app)
                if (intent.action == Reminders.ACTION) {
                    val id = intent.getLongExtra("id", -1)
                    db.reminder(id)?.let { Reminders.post(app, it); db.markFired(id) }
                } else {
                    // Boot or update: alarms were cleared, so arm the ones still ahead.
                    val now = System.currentTimeMillis()
                    for (r in db.upcomingReminders(now)) if (r.at > now) Reminders.arm(app, r.id, r.at) else { Reminders.post(app, r); db.markFired(r.id) }
                }
            } finally { done.finish() }
        }.start()
    }
}
