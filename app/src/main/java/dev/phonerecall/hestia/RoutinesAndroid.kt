package dev.phonerecall.hestia

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.clio.Clio
import dev.phonerecall.mind.PrefsKv
import dev.phonerecall.shortcuts.ShortcutsCore
import java.time.Instant
import java.time.ZoneId

/**
 * Home routines on the phone: Odyssey's leave and arrive, a clock at sunset and through the night, Fates to run the
 * steps as his own (validated, audited, Undo), one notification with Undo per run, and Clio for every run and skip.
 * Hestia's taps and these moments go to the learned-shortcuts evidence so Athena can suggest new routines.
 */
object HestiaRoutines {
    const val ACTION_TICK = "dev.phonerecall.ROUTINE_TICK"
    const val ACTION_UNDO = "dev.phonerecall.ROUTINE_UNDO"
    private const val CHANNEL = "home_routines"
    private const val NOTE = 47_000
    private const val PROPOSER = "Home routines"

    @Volatile private var book: RoutineBook? = null

    fun book(c: Context): RoutineBook = book ?: synchronized(this) { book ?: RoutineBook(PrefsKv(c.applicationContext, "hestia_routines")).also { book = it } }

    private val zone: ZoneId get() = ZoneId.systemDefault()

    private fun engine(c: Context): RoutineEngine {
        val app = c.applicationContext
        return RoutineEngine(book(app), { HestiaCore.get(app).snapshot }, { steps, title ->
            val core = BrainCore.get(app)
            when (val r = ShortcutsCore.quietly { core.fates.own(steps, title, PROPOSER) }) {
                is ProposeResult.Created -> listOf(r.proposal.id)
                is ProposeResult.Invalid -> { Clio.warn("hestia", "routine not run", r.reason); emptyList() }
            }
        }, { ShortcutsCore.evidence(app) })
    }

    /** From the app: Hestia's taps feed the evidence, and the clock is armed. */
    fun install(c: Context) {
        val app = c.applicationContext
        HestiaCore.tapped = { d, cmd, s -> RoutineRules.tapStep(d, cmd, s)?.let { runCatching { ShortcutsCore.acted(app, it) } } }
        Thread { runCatching { arm(app) }.onFailure { Clio.error("hestia", it) } }.start()
    }

    private fun home(c: Context): Pair<Double, Double>? = runCatching { dev.phonerecall.odyssey.OdysseyStore.load(c).home()?.let { it.lat to it.lng } }.getOrNull()

    /** Hypnos's bedtime tonight, when it has one. */
    private fun bedtime(c: Context): Long? = runCatching {
        dev.phonerecall.alarms.AlarmPlanner.view(c, java.time.ZonedDateTime.now(zone), dev.phonerecall.brain.AlarmWatch.get(c).next()).plan?.bedtime
    }.getOrNull()

    /** When the screen went off and has stayed off: UsageStats when he allowed it, else the first tick that saw it off. */
    private fun offSince(c: Context, now: Long): Long? {
        if (c.getSystemService(PowerManager::class.java)?.isInteractive != false) { book(c).update { it.copy(screenOffSeen = null) }; return null }
        val fromStats = runCatching {
            val q = c.getSystemService(UsageStatsManager::class.java)?.queryEvents(now - 12 * 3_600_000L, now) ?: return@runCatching null
            val e = UsageEvents.Event()
            var last: Pair<Int, Long>? = null
            while (q.hasNextEvent()) { q.getNextEvent(e); if (e.eventType == UsageEvents.Event.SCREEN_INTERACTIVE || e.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) last = e.eventType to e.timeStamp }
            last?.takeIf { it.first == UsageEvents.Event.SCREEN_NON_INTERACTIVE }?.second
        }.getOrNull()
        return fromStats ?: book(c).update { it.copy(screenOffSeen = it.screenOffSeen ?: now) }.screenOffSeen
    }

    private fun now(c: Context, at: Long = System.currentTimeMillis()): RoutineNow {
        val h = home(c)
        val media = runCatching { c.getSystemService(AudioManager::class.java)?.isMusicActive == true }.getOrDefault(false)
        return RoutineEngine.now(at, zone, HestiaCore.whereabouts.away()?.not(), h?.first, h?.second, offSince(c, at), media, bedtime(c))
    }

    /**
     * Something happened: Odyssey's leave or arrive ([place] "home", "work" or a place's name; a drive starting at home
     * is leaving it), the alarm dismissed, the car. Call off the main thread.
     */
    fun event(c: Context, m: Moment, place: String = "home") {
        val app = c.applicationContext
        runCatching {
            if (m == Moment.LEAVE && place == "home") ShortcutsCore.trigger(app, m.trigger, m.triggerLabel)
            done(app, engine(app).event(m, now(app), place))
            arm(app)
        }.onFailure { Clio.error("hestia", it) }
    }

    /** From a main-thread receiver. */
    fun eventAsync(c: Context, m: Moment, place: String = "home") { val app = c.applicationContext; Thread { event(app, m, place) }.start() }

    /** The clock: sunset and bedtime go to the evidence once a day, then the dusk and asleep routines look. */
    fun tick(c: Context) {
        val app = c.applicationContext
        runCatching {
            val n = now(app)
            val today = Instant.ofEpochMilli(n.at).atZone(zone).toLocalDate().toString()
            val d = book(app).load()
            if (n.sunset != null && n.at >= n.sunset && d.sunsetSeen != today) {
                book(app).update { it.copy(sunsetSeen = today) }
                ShortcutsCore.trigger(app, Moment.DUSK.trigger, Moment.DUSK.triggerLabel)
            }
            val night = RoutineRules.nightStart(d.routine("asleep") ?: Routine(moment = Moment.ASLEEP), n)
            val nightOf = Instant.ofEpochMilli(night).atZone(zone).toLocalDate().toString()
            if (n.at >= night && n.home == true && d.bedtimeSeen != nightOf) {
                book(app).update { it.copy(bedtimeSeen = nightOf) }
                ShortcutsCore.trigger(app, Moment.ASLEEP.trigger, Moment.ASLEEP.triggerLabel)
            }
            done(app, engine(app).tick(n))
        }.onFailure { Clio.error("hestia", it) }
        runCatching { arm(app) }
    }

    private fun done(c: Context, out: List<RoutineOutcome>) {
        for (o in out) {
            if (o.ids.isNotEmpty()) {
                Clio.info("hestia", "routine ran", "${o.routine.name}: ${o.words}")
                notify(c, o)
            } else if (o.skipped != null && o.skipped != "turned off") Clio.info("hestia", "routine skipped", "${o.routine.name}: ${o.skipped}")
        }
        if (out.any { it.ids.isNotEmpty() }) BrainCore.get(c).changed()
    }

    /** One inexact alarm: the next sunset, tonight's bedtime, or every 15 minutes through the night. */
    fun arm(c: Context) {
        val app = c.applicationContext
        val at = System.currentTimeMillis()
        val h = home(app)
        val next = RoutineEngine.nextTick(book(app).load(), at, zone, h?.first, h?.second, bedtime(app))
        val pi = PendingIntent.getBroadcast(app, NOTE, Intent(app, RoutineReceiver::class.java).setAction(ACTION_TICK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        app.getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    private fun notify(c: Context, o: RoutineOutcome) {
        if (c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Home routines", NotificationManager.IMPORTANCE_LOW).apply { description = "Lights and plugs Hestia switched on its own, with Undo" })
        val id = NOTE + 1 + (o.routine.id.hashCode() and 0xff)
        val undo = PendingIntent.getBroadcast(
            c, id, Intent(c, RoutineReceiver::class.java).setAction(ACTION_UNDO).putExtra("routine", o.routine.id).putExtra("ids", o.ids.toLongArray()).putExtra("note", id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let { PendingIntent.getActivity(c, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        nm.notify(id, NotificationCompat.Builder(c, CHANNEL).setSmallIcon(dev.phonerecall.R.drawable.ic_owl)
            .setContentTitle(o.routine.name).setContentText(o.words?.replaceFirstChar { it.uppercase() })
            .setContentIntent(open).addAction(0, "Undo", undo).setAutoCancel(true).setTimeoutAfter(2 * 3_600_000L).build())
    }

    /** Undo of one run: Fates puts every device back as it was. */
    fun undo(c: Context, routineId: String, ids: List<Long>) {
        val core = BrainCore.get(c)
        for (id in ids) core.fates.undo(id)
        book(c).edit(routineId) { it.copy(lastIds = emptyList(), lastWords = it.lastWords?.let { w -> "$w (undone)" }) }
        Clio.info("hestia", "routine undone", book(c).load().routine(routineId)?.name.orEmpty())
        core.changed()
    }

    // ---- Athena's suggestions ----

    fun owns(id: String) = id.startsWith("hr-")

    /**
     * His Yes on a suggestion: a routine when it is one (a light or switch after leaving, arriving, sunset or bedtime).
     * Null when it isn't routine-shaped; throws when it is but may not be (a lock, a "Never automate" device).
     */
    fun adopt(c: Context, trigger: String, action: String, label: String): Pair<String, String>? {
        if (Moment.of(trigger) == null || !action.startsWith("home:")) return null
        val b = book(c)
        val id = "hr-" + dev.phonerecall.mind.sha(trigger + action).take(8)
        val r = RoutineRules.adopt(trigger, action, label, HestiaCore.get(c).snapshot, b.load().never, id)
            ?: throw HomeError("A home routine only switches lights, plugs and switches you haven't marked Never automate")
        b.add(r)
        Clio.info("hestia", "routine learned", r.name)
        runCatching { arm(c) }
        return "New home routine: ${r.name}" to r.id
    }

    fun remove(c: Context, id: String) { book(c).remove(id); Clio.info("hestia", "routine removed") }

    /** For read_habits: what the routines already do, so she doesn't offer them twice. */
    fun summaries(c: Context): List<String> = runCatching {
        val d = book(c).load()
        val s = HestiaCore.get(c).snapshot
        d.routines.filter { it.on }.map { "${it.name}: " + RoutineRules.sentence(it, s, emptyList(), d.never) }
    }.getOrDefault(emptyList())

    // ---- by chat: read_routines and propose_routine ----

    /** read_routines: every routine as she would write it back (to change one), its sentence, runs; the devices never automated; what WHEN, IF and THEN can be. */
    fun readJson(c: Context): String {
        val d = book(c).load()
        val s = HestiaCore.get(c).snapshot
        return dev.phonerecall.mind.stateJson.writeValueAsString(RoutineTool.read(d, s))
    }

    /** propose_routine: her draft, checked on the phone, as ONE card with the routine in a sentence. */
    fun propose(c: Context, input: Map<String, Any?>): String {
        val b = book(c)
        val s = HestiaCore.get(c).snapshot
        val step = when (val r = RoutineSpec.check(input, s, b.load(), { "rt-" + java.util.UUID.randomUUID().toString().take(6) })) {
            is SpecCheck.Bad -> { Clio.info("hestia", "routine refused", r.assess ?: "plain reason"); return "Not proposed: ${r.reason}" }
            is SpecCheck.Delete -> dev.phonerecall.brain.RawStep(dev.phonerecall.brain.ProposalKind.SET_ROUTINE, mapOf("delete" to r.routine.id, "sentence" to r.sentence))
            is SpecCheck.Ok -> RoutineTool.step(r)
        }
        val core = BrainCore.get(c)
        val title = step.args.getValue("sentence").let { if (step.args["delete"] != null) it else if (input["id"] != null) "Change: $it" else it }
        return when (val p = core.fates.propose(listOf(step), title.take(80), null, "Athena", onRequest = true)) {
            is ProposeResult.Created -> { core.changed(); "One card for the owner: \"${step.args["sentence"]}\" It is live only after he confirms. Tell him that in one line." }
            is ProposeResult.Invalid -> "Not proposed: ${p.reason}"
        }
    }

    /** His Confirm on a SET_ROUTINE card: the routine goes live (or goes). Undo gets the old one back. */
    fun apply(c: Context, p: Map<String, String>): Pair<String, Map<String, String>> {
        val b = book(c)
        val d = b.load()
        p["delete"]?.let { id ->
            val old = d.routine(id) ?: error("That routine is already gone")
            b.remove(id)
            Clio.info("hestia", "routine deleted", old.name)
            return "Deleted ${old.name}" to mapOf("id" to id, "before" to dev.phonerecall.mind.stateJson.writeValueAsString(old))
        }
        val r = dev.phonerecall.mind.stateJson.readValue(p.getValue("routine"), Routine::class.java)
        RoutineSpec.recheck(r, HestiaCore.get(c).snapshot, d.never)?.let { error(it) }
        val old = d.routine(r.id)
        b.put(r)
        Clio.info("hestia", if (old == null) "routine made" else "routine changed", r.name)
        runCatching { arm(c) }
        return "Live: ${r.name}" to (mapOf("id" to r.id) + (old?.let { mapOf("before" to dev.phonerecall.mind.stateJson.writeValueAsString(it)) } ?: emptyMap()))
    }

    fun restore(c: Context, u: Map<String, String>): Boolean = runCatching {
        val b = book(c)
        val before = u["before"]?.let { dev.phonerecall.mind.stateJson.readValue(it, Routine::class.java) }
        if (before != null) b.update { d -> d.copy(routines = if (d.routine(before.id) != null) d.routines.map { if (it.id == before.id) before else it } else d.routines + before) }
        else b.remove(u.getValue("id"))
        runCatching { arm(c) }
        true
    }.getOrDefault(false)

    /** The lights he usually switches at this routine's moment (for the Hestia screen's words). */
    fun learned(c: Context, r: Routine): List<String> = runCatching { RoutineRules.learned(r, ShortcutsCore.evidence(c)) }.getOrDefault(emptyList())
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    HestiaRoutines.ACTION_TICK -> HestiaRoutines.tick(context)
                    HestiaRoutines.ACTION_UNDO -> {
                        HestiaRoutines.undo(context, intent.getStringExtra("routine").orEmpty(), intent.getLongArrayExtra("ids")?.toList().orEmpty())
                        context.getSystemService(NotificationManager::class.java)?.cancel(intent.getIntExtra("note", 0))
                    }
                }
            } catch (e: Exception) { Clio.error("hestia", e) } finally { pending.finish() }
        }.start()
    }
}
