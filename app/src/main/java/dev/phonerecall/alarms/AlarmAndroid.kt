package dev.phonerecall.alarms

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.AlarmKind
import dev.phonerecall.brain.AlarmMoments
import dev.phonerecall.brain.AlarmWatch
import dev.phonerecall.brain.Alarms
import dev.phonerecall.brain.BedNudges
import dev.phonerecall.brain.Bedtime
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Classified
import dev.phonerecall.brain.Days
import dev.phonerecall.brain.Fixture
import dev.phonerecall.brain.SportSleep
import dev.phonerecall.brain.SportTrade
import dev.phonerecall.brain.IntentInput
import dev.phonerecall.brain.Moment
import dev.phonerecall.brain.NextAlarm
import dev.phonerecall.brain.NudgeStep
import dev.phonerecall.brain.Plan
import dev.phonerecall.brain.Relation
import dev.phonerecall.data.CalItem
import dev.phonerecall.home.Festivals
import dev.phonerecall.mind.BackgroundReads
import dev.phonerecall.odyssey.Geo
import dev.phonerecall.odyssey.OdysseyStore
import dev.phonerecall.odyssey.RoadCheck
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * The phone side of alarm awareness: what to read, when to wake up, what to post. Every decision is in
 * AlarmIntent.kt; this file only gathers inputs and delivers. No polling: an exact alarm at bedtime, one at the
 * ring time that matters, one inexact planner alarm each evening, and a screen/unlock receiver that exists only
 * inside tonight's window.
 */

/** Names and numbers from Contacts. Needs READ_CONTACTS, which birthdays already use. */
object Phones {
    fun of(app: Context, name: String): List<String> {
        if (app.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            app.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?", arrayOf(name), null,
            )?.use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun isIndia(number: String): Boolean {
        val n = number.filter { it.isDigit() || it == '+' }
        return n.startsWith("+91") || n.startsWith("0091") || (n.length == 12 && n.startsWith("91"))
    }

    /** wa.me wants digits with the country code. */
    fun whatsapp(number: String, draft: String?): String =
        "https://wa.me/" + number.filter { it.isDigit() }.removePrefix("00") + (draft?.let { "?text=" + Uri.encode(it) } ?: "")
}

/** The app on screen right now, or null when unknown. Needs Usage access; without it nothing is held. */
fun interface ForegroundApp {
    fun current(): String?

    companion object {
        /** Games that hold the bedtime nudge (example packages). */
        val GAMES = setOf("com.example.chess", "com.example.arena")

        fun usage(app: Context) = ForegroundApp {
            runCatching {
                val usm = app.getSystemService(UsageStatsManager::class.java)
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 2 * 60_000L, now)
                val e = UsageEvents.Event()
                var last: String? = null
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = e.packageName
                    else if (e.eventType == UsageEvents.Event.ACTIVITY_PAUSED && e.packageName == last) last = null
                }
                last
            }.getOrNull()
        }
    }
}

/**
 * Hooks for other features, all null until they register. Rhythms (app habits) sets `habit`; the brief sets `onDismissed`.
 * Nothing here depends on either existing.
 */
interface HabitSignal {
    /** He is in the middle of his usual late-night habit (a game, a show). */
    fun during(): Boolean
    /** Hold the bedtime nudge for now. */
    fun shouldHold(): Boolean
    /** A line for the nudge, or null. */
    fun bedtimeLine(): String?
}

object AlarmHooks {
    @Volatile var habit: ((Context) -> HabitSignal?)? = null
    /** Called when the alarm goes off or is dismissed: (context, reason like "Dasara" or "wake-up alarm"). */
    @Volatile var onDismissed: ((Context, String) -> Unit)? = null
    /** Kairos: snow or ice before tomorrow's commute, as one line for the bedtime nudge. */
    @Volatile var hazard: ((Context) -> String?)? = null
}

object AlarmPlanner {
    /** Swappable so tests can say a game is open. */
    @Volatile var foreground: ForegroundApp? = null
    const val ACT_PLAN = "dev.phonerecall.ALARM_PLAN"
    const val ACT_BEDTIME = "dev.phonerecall.ALARM_BEDTIME"
    const val ACT_RECHECK = "dev.phonerecall.ALARM_RECHECK"
    const val ACT_RING = "dev.phonerecall.ALARM_RING"
    const val ACT_SNOOZE = "dev.phonerecall.ALARM_SNOOZE"
    const val ACT_NOT_TONIGHT = "dev.phonerecall.ALARM_NOT_TONIGHT"
    private const val CHANNEL = "alarm_awareness"
    private const val REQ = 41_000
    private const val NOTE_NUDGE = 41_100
    private const val NOTE_MOMENT = 41_101
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val lock = Any()

    class Gathered(val events: List<CalItem>, val checks: List<RoadCheck>, val atHome: Boolean?)

    fun gather(app: Context, from: Long, to: Long): Gathered {
        val events = runCatching { BackgroundReads(app, BrainCore.get(app)).relevantEvents(from, to) }.getOrDefault(emptyList())
        val d = runCatching { OdysseyStore.load(app) }.getOrNull()
        val home = d?.home(); val stay = d?.stay
        val atHome = if (d == null || !d.settings.on || home == null || stay == null) null else Geo.meters(home.lat, home.lng, stay.lat, stay.lng) < 200
        return Gathered(events, d?.checks.orEmpty(), atHome)
    }

    /** Odyssey's leave-by for an event: its event check expires at the event's start. */
    private fun leaveBy(g: Gathered, e: CalItem): Long? =
        g.checks.firstOrNull { it.reason == "event" && kotlin.math.abs(it.expiresAt - e.begin) < 60_000L && it.leaveBy > 0 }?.leaveBy

    /** Birthdays on an IST date, every source merged (brain/BirthdayBook), not yet wished. With `onlyIndia`, just India people. */
    fun wishes(app: Context, onlyIndia: Boolean): (LocalDate) -> List<String> = { d ->
        val all = runCatching { dev.phonerecall.brain.BirthdayDay.on(app, d) }.getOrDefault(emptyList())
        val india = all.filter { it.india }.map { it.name }
        if (onlyIndia || india.isNotEmpty()) india else all.map { it.name }
    }

    // ---- what the next alarm is ----

    fun classify(app: Context, next: NextAlarm, watch: AlarmWatch = AlarmWatch.get(app)): Classified {
        val g = gather(app, next.at, next.at + 3 * 3_600_000L)
        val m = Days.minuteOfDay(next.at, zone)
        val label = watch.zeusLabelAt(m)
        return Alarms.classify(
            IntentInput(
                ring = next.at, setAt = watch.setAtOf(next.at), zone = zone, label = label, atHome = g.atHome, events = g.events,
                leaveBys = g.events.mapNotNull { e -> leaveBy(g, e)?.let { e to it } }, timesSeen = Alarms.timesSeen(next.at, watch.seenRings(), zone),
                routineLabel = Alarms.nearMinute(m, watch.routineAnswers()), wishes = wishes(app, false),
            ),
        )
    }

    class View(val plan: Plan?, val offer: Pair<String, Long>?, val ask: Int?, val sport: SportTrade? = null)
    private var memo: Triple<Long, Long?, View>? = null

    /** Tomorrow's plan, the India offer and the routine question, recomputed at most every ten minutes. */
    fun view(app: Context, now: ZonedDateTime, next: NextAlarm?): View = synchronized(lock) viewOf@{
        val bucket = now.toInstant().toEpochMilli() / 600_000L
        memo?.takeIf { it.first == bucket && it.second == next?.at }?.let { return@viewOf it.third }
        val watch = AlarmWatch.get(app)
        val v = runCatching {
            val date = Bedtime.target(now)
            val g = gather(app, date.atStartOfDay(zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            val usual = watch.habit(now.toInstant().toEpochMilli()).forDay(date.dayOfWeek)
            val plan = Bedtime.plan(date, zone, next, g.events, Bedtime.festival(date, g.events, zone, Festivals.on(date)?.name), usual) { leaveBy(g, it) }
            val offer = if (now.hour >= 20) AlarmMoments.indiaOffer(now, next, wishes(app, true)) else null
            val ask = next?.let { n ->
                val c = classify(app, n, watch)
                val m = Days.minuteOfDay(n.at, zone)
                m.takeIf { Alarms.shouldAsk(c, n.at, zone, Alarms.timesSeen(n.at, watch.seenRings(), zone), Alarms.nearMinute(m, watch.routineAnswers()) != null, Alarms.nearMinute(m, watch.askedRoutines()) != null) }
            }
            // Followed fixtures in Olympia that land in the night, or in sleep before an early start.
            val fixtures = dev.phonerecall.olympia.OlympiaStore.state.value.followed.filter { it.phase == dev.phonerecall.olympia.Phase.PRE }.map {
                Fixture(it.id, if (it.session != null) "${it.title} ${it.session}" else it.title, it.start, dev.phonerecall.olympia.MatchDay.length(it))
            }
            val sport = SportSleep.pick(fixtures, plan, now.toInstant().toEpochMilli(), zone, watch.answeredSports())?.let { SportSleep.trade(it, plan, zone) }
            View(plan, offer, ask, sport)
        }.getOrDefault(View(null, null, null))
        memo = Triple(bucket, next?.at, v)
        v
    }

    fun invalidate() { synchronized(lock) { memo = null } }

    // ---- alarms ----

    private fun pi(app: Context, code: Int, action: String, flags: Int, ring: Long? = null): PendingIntent? =
        PendingIntent.getBroadcast(app, REQ + code, Intent(app, AlarmReceiver::class.java).setAction(action).apply { ring?.let { putExtra("ring", it) } }, flags or PendingIntent.FLAG_IMMUTABLE)

    private fun schedule(app: Context, at: Long, code: Int, action: String, ring: Long? = null, exact: Boolean = true) {
        val am = app.getSystemService(AlarmManager::class.java)
        val p = pi(app, code, action, PendingIntent.FLAG_UPDATE_CURRENT, ring) ?: return
        if (exact && am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
    }

    private fun cancel(app: Context, code: Int, action: String) {
        pi(app, code, action, PendingIntent.FLAG_NO_CREATE)?.let { app.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    /** Re-reads everything and re-arms: tonight's bedtime, the next alarm's ring moment, and tomorrow evening's planner. */
    fun arm(app: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val ms = now.toInstant().toEpochMilli()
        val watch = AlarmWatch.get(app)
        invalidate()
        // Planner: 6 PM every day, inexact; it re-arms itself.
        val six = now.toLocalDate().atTime(18, 0).atZone(zone).let { if (it.isAfter(now)) it else it.plusDays(1) }
        schedule(app, six.toInstant().toEpochMilli(), 1, ACT_PLAN, exact = false)

        val next = watch.next()
        val v = view(app, now, next)
        val plan = v.plan
        if (plan != null && watch.nudgesOn()) {
            if (plan.bedtime > ms) schedule(app, plan.bedtime, 2, ACT_BEDTIME)
            if (ms in plan.bedtime..plan.windowEnd) { AwakeWatch.start(app); evaluate(app, awakeNow = isInteractive(app)) }
        } else cancel(app, 2, ACT_BEDTIME)

        if (next != null && next.at > ms) {
            val c = classify(app, next, watch)
            val date = Days.at(next.at, zone).toLocalDate()
            val festival = Festivals.on(date)?.name
            val actionable = c.kind == AlarmKind.INDIA_MIDNIGHT || c.kind == AlarmKind.LEAVE || c.kind == AlarmKind.NAP || (festival != null && Alarms.isWake(next.at, zone))
            if (c.kind == AlarmKind.NAP) watch.setNapUntil(next.at) else watch.setNapUntil(0L)
            if (actionable) schedule(app, next.at, 3, ACT_RING, next.at) else cancel(app, 3, ACT_RING)
        } else { cancel(app, 3, ACT_RING); watch.setNapUntil(0L) }
    }

    // ---- the bedtime nudge ----

    fun isInteractive(app: Context): Boolean = app.getSystemService(PowerManager::class.java).isInteractive

    private fun awake(app: Context, now: Long, awakeNow: Boolean) = awakeNow || now - AlarmWatch.get(app).lastUnlock() <= 10 * 60_000L

    /** Posts at most one nudge and one follow-up for tonight. `awakeNow` is true when the screen is on right now. */
    fun evaluate(app: Context, awakeNow: Boolean): Unit = synchronized(lock) {
        val watch = AlarmWatch.get(app)
        val now = System.currentTimeMillis()
        if (!watch.nudgesOn()) return@synchronized
        val z = ZonedDateTime.now()
        val v = view(app, z, watch.next())
        val plan = v.plan ?: return@synchronized
        val state = watch.nudgeState()
        val signal = runCatching { AlarmHooks.habit?.invoke(app) }.getOrNull()
        val gaming = (foreground ?: ForegroundApp.usage(app)).current()?.let { it in ForegroundApp.GAMES } == true || signal?.shouldHold() == true
        val step = BedNudges.decide(true, plan, state, now, awake(app, now, awakeNow || gaming), watch.napQuiet(now), gaming)
        if (step == NudgeStep.NONE) return@synchronized
        val after = BedNudges.after(plan, state, step, now)
        watch.saveNudgeState(after)
        if (step == NudgeStep.HOLD) {
            // Leave him to the match: look again in five minutes, and post at once if he has left it.
            schedule(app, now + 5 * 60_000L, 4, ACT_RECHECK)
            return@synchronized
        }
        val (title, reason) = BedNudges.text(plan, step, now, zone, afterGame = state.heldSince > 0)
        post(app, NOTE_NUDGE, title, listOfNotNull(reason, signal?.bedtimeLine(), runCatching { AlarmHooks.hazard?.invoke(app) }.getOrNull()).joinToString(". ").ifEmpty { null }, nudgeActions(app))
        if (step == NudgeStep.FIRST) schedule(app, after.nextAt, 4, ACT_RECHECK)
    }

    private fun nudgeActions(app: Context): List<NotificationCompat.Action> = listOfNotNull(
        // No permission games: this opens the system's Do Not Disturb settings and he sets it himself.
        NotificationCompat.Action(0, "Set Do Not Disturb", PendingIntent.getActivity(app, REQ + 20, Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)),
        pi(app, 5, ACT_SNOOZE, PendingIntent.FLAG_UPDATE_CURRENT)?.let { NotificationCompat.Action(0, "Snooze 30 min", it) },
        pi(app, 6, ACT_NOT_TONIGHT, PendingIntent.FLAG_UPDATE_CURRENT)?.let { NotificationCompat.Action(0, "Not tonight", it) },
    )

    fun snooze(app: Context): Unit = synchronized(lock) {
        val watch = AlarmWatch.get(app)
        val now = System.currentTimeMillis()
        val plan = view(app, ZonedDateTime.now(), watch.next()).plan ?: return@synchronized
        watch.saveNudgeState(BedNudges.snooze(plan, watch.nudgeState(), now))
        schedule(app, now + 30 * 60_000L, 4, ACT_RECHECK)
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_NUDGE)
    }

    fun notTonight(app: Context): Unit = synchronized(lock) {
        val watch = AlarmWatch.get(app)
        val plan = view(app, ZonedDateTime.now(), watch.next()).plan ?: return@synchronized
        watch.saveNudgeState(BedNudges.notTonight(plan, watch.nudgeState()))
        cancel(app, 4, ACT_RECHECK)
        app.getSystemService(NotificationManager::class.java).cancel(NOTE_NUDGE)
    }

    // ---- ring time ----

    /** The alarm that was due at `ring` is gone. Tells the brief why it was set, when the brief is there. */
    fun dismissed(app: Context, ring: Long) {
        val hook = AlarmHooks.onDismissed ?: return
        val watch = AlarmWatch.get(app)
        val date = Days.at(ring, zone).toLocalDate()
        val festival = Festivals.on(date)?.name?.takeIf { Alarms.isWake(ring, zone) }
        val reason = festival ?: runCatching { classify(app, NextAlarm(ring, null), watch).phrase }.getOrDefault("").ifEmpty { "alarm" }
        runCatching { hook(app, reason) }
    }

    /** The next alarm just went off (or is about to): post what it was for. */
    fun ring(app: Context, ringAt: Long) {
        val watch = AlarmWatch.get(app)
        val next = NextAlarm(ringAt, null)
        val c = classify(app, next, watch)
        dev.phonerecall.clio.Clio.info("alarms", "rang", c.kind.name.lowercase())
        val date = Days.at(ringAt, zone).toLocalDate()
        val g = gather(app, ringAt, ringAt + 4 * 3_600_000L)
        val moment: Moment? = when (c.kind) {
            AlarmKind.INDIA_MIDNIGHT -> {
                val who = c.people.firstOrNull()
                val numbers = who?.let { Phones.of(app, it) }.orEmpty()
                val number = numbers.firstOrNull { Phones.isIndia(it) } ?: numbers.firstOrNull()
                AlarmMoments.india(c, ringAt, number?.let { Phones.whatsapp(it, "Happy birthday ${who!!.substringBefore(' ')}!") }, number?.let { "tel:$it" })
            }
            AlarmKind.LEAVE -> {
                val hour = Days.at(ringAt, zone).hour
                if (hour < 10 && c.leaveBy == null) null else AlarmMoments.leave(c, ringAt, zone, c.event?.let { e ->
                    val check = g.checks.firstOrNull { it.reason == "event" && kotlin.math.abs(it.expiresAt - e.begin) < 60_000L && (it.lat != 0.0 || it.lng != 0.0) }
                    if (check != null) "geo:${check.lat},${check.lng}?q=${check.lat},${check.lng}(${Uri.encode(e.title)})" else e.place?.let { "geo:0,0?q=${Uri.encode(it)}" }
                })
            }
            AlarmKind.NAP -> AlarmMoments.nap(watch.setAtOf(ringAt), ringAt, zone, g.events.filter { !it.allDay && it.begin > ringAt }.minByOrNull { it.begin })
            else -> Festivals.on(date)?.name?.takeIf { Alarms.isWake(ringAt, zone) }?.let { festival(app, it, ringAt, date) }
        }
        watch.setNapUntil(0L)
        if (moment != null) { watch.setMoment(moment); if (moment.notify) postMoment(app, moment) }
        invalidate()
    }

    private fun festival(app: Context, name: String, ring: Long, date: LocalDate): Moment {
        val outfit = dev.phonerecall.closet.OutfitState.state.value?.takeIf { it.date == date }?.title ?: Festivals.on(date)?.outfit
        // Home is his mother or father in the people graph, when one is confirmed and in Contacts.
        val home = runCatching { BrainCore.get(app).store.people().firstOrNull { it.relation == Relation.MOTHER || it.relation == Relation.FATHER } }.getOrNull()
        val number = home?.let { Phones.of(app, it.name) }?.let { l -> l.firstOrNull { Phones.isIndia(it) } ?: l.firstOrNull() }
        return AlarmMoments.festival(name, ring, outfit, home?.name?.substringBefore(' '), number?.let { Phones.whatsapp(it, null) })
    }

    // ---- delivery ----

    private fun post(app: Context, id: Int, title: String, text: String?, actions: List<NotificationCompat.Action>) {
        if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Alarms and bedtime", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            id,
            NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_owl).setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open).setAutoCancel(true).apply { actions.forEach { addAction(it) } }.build(),
        )
    }

    private fun postMoment(app: Context, m: Moment) {
        val actions = m.actions.mapNotNull { a ->
            when (a.role) {
                "LINK" -> a.payload?.let { p ->
                    val intent = Intent(if (p.startsWith("tel:")) Intent.ACTION_DIAL else Intent.ACTION_VIEW, Uri.parse(p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    NotificationCompat.Action(0, a.label, PendingIntent.getActivity(app, REQ + 30 + (p.hashCode() and 0xff), intent, PendingIntent.FLAG_IMMUTABLE))
                }
                else -> null
            }
        }
        post(app, NOTE_MOMENT, m.title, listOfNotNull(m.body, m.facts.joinToString(", ") { "${it.first} ${it.second}" }.ifEmpty { null }).joinToString("\n").ifEmpty { null }, actions)
    }
}

/** Screen on and unlock, registered only while tonight's window is open. The process may end; the next alarm re-registers it. */
object AwakeWatch {
    private var receiver: BroadcastReceiver? = null

    @Synchronized fun start(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = System.currentTimeMillis()
                val watch = AlarmWatch.get(context)
                if (intent.action == Intent.ACTION_USER_PRESENT) watch.markUnlock(now)
                val done = goAsync()
                Thread {
                    try {
                        val plan = AlarmPlanner.view(context, ZonedDateTime.now(), watch.next()).plan
                        if (plan == null || now > plan.windowEnd) stop(context) else if (now >= plan.bedtime) AlarmPlanner.evaluate(context, awakeNow = true)
                    } finally { done.finish() }
                }.start()
            }
        }
        receiver = r
        app.applicationContext.registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }, Context.RECEIVER_NOT_EXPORTED)
    }

    @Synchronized fun stop(app: Context) {
        receiver?.let { runCatching { app.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }
}

/** NEXT_ALARM_CLOCK_CHANGED, boot, and the planner's own alarms and notification buttons. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        Thread {
            try {
                dev.phonerecall.clio.Clio.info("alarms", "broadcast", intent.action?.substringAfterLast('.').orEmpty())
                runCatching {
                    when (intent.action) {
                        "android.app.action.NEXT_ALARM_CLOCK_CHANGED" -> {
                            val watch = AlarmWatch.get(app)
                            val before = watch.lastNext()
                            val now = System.currentTimeMillis()
                            val after = watch.record(fromBroadcast = true)
                            // The value moved on within minutes of its time: it rang or was dismissed.
                            if (before != null && before != after?.at && before in now - 90 * 60_000L..now + 5 * 60_000L) AlarmPlanner.dismissed(app, before)
                            dev.phonerecall.clio.DeviceLog.alarm(before, after?.at, now)?.let { dev.phonerecall.clio.Clio.info(dev.phonerecall.clio.DeviceLog.AREA, it) }
                            watch.saveLastNext(after?.at)
                            AlarmPlanner.arm(app)
                        }
                        AlarmPlanner.ACT_PLAN, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> AlarmPlanner.arm(app)
                        AlarmPlanner.ACT_BEDTIME -> {
                            val plan = AlarmPlanner.view(app, ZonedDateTime.now(), AlarmWatch.get(app).next()).plan
                            if (plan != null) AwakeWatch.start(app)
                            AlarmPlanner.evaluate(app, awakeNow = AlarmPlanner.isInteractive(app))
                        }
                        AlarmPlanner.ACT_RECHECK -> AlarmPlanner.evaluate(app, awakeNow = AlarmPlanner.isInteractive(app))
                        AlarmPlanner.ACT_RING -> AlarmPlanner.ring(app, intent.getLongExtra("ring", System.currentTimeMillis()))
                        AlarmPlanner.ACT_SNOOZE -> AlarmPlanner.snooze(app)
                        AlarmPlanner.ACT_NOT_TONIGHT -> AlarmPlanner.notTonight(app)
                    }
                }.onFailure { dev.phonerecall.clio.Clio.error("alarms", it) }
            } finally { done.finish() }
        }.start()
    }
}
