package dev.phonerecall.rhythms

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CallLog
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.People
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.QuietHours
import dev.phonerecall.brain.RawStep
import dev.phonerecall.calls.CallRouting
import dev.phonerecall.calls.Calls
import dev.phonerecall.mind.MindNote
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Rhythms on the phone: reads Usage access and the call log, hears WhatsApp's call notifications through
 * the senses listener, learns (Habits, CallHabits), and keeps one exact alarm for the next call nudge.
 */
object Rhythms {
    private const val TAG = "Zeus-Rhythms"
    const val CHANNEL = "rhythms_calls"
    const val ACTION_WAKE = "dev.phonerecall.RHYTHMS_WAKE"
    const val ACTION_LATER = "dev.phonerecall.RHYTHMS_LATER"
    const val ACTION_NOT_TODAY = "dev.phonerecall.RHYTHMS_NOT_TODAY"
    private const val DAY = 86_400_000L
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "zeus-rhythms").apply { isDaemon = true } }

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun canUsage(c: Context) = dev.phonerecall.odyssey.Odyssey.canUsage(c)
    fun canCallLog(c: Context) = granted(c, Manifest.permission.READ_CALL_LOG)
    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** For the alarms branch and proposals: his app habits as a [HabitSignal]. */
    fun signal(c: Context): HabitSignal = Habits.signal(RhythmsStore.load(c).habits, zone())

    fun settings(c: Context, change: (RhythmsSettings) -> RhythmsSettings) {
        RhythmsStore.update(c) { it.copy(settings = change(it.settings)) }
        io.execute { runCatching { refresh(c.applicationContext) } }
    }

    fun refreshSoon(c: Context) { RhythmsWork.schedule(c); io.execute { runCatching { refresh(c.applicationContext) } } }

    /** "Forget": the habit goes, and nothing before now is learned from again. */
    fun forget(c: Context, key: String) {
        val now = System.currentTimeMillis()
        RhythmsStore.update(c) { d ->
            d.copy(
                forgot = d.forgot + (key to now),
                habits = d.habits.filter { it.pkg != key }, sessions = d.sessions.filter { it.pkg != key }, skips = d.skips.filter { it.pkg != key },
                callHabits = d.callHabits.filter { it.key != key }, calls = d.calls.filter { it.key != key }, nudges = d.nudges.filter { it.key != key },
            )
        }
        cancel(c, key)
        arm(c)
    }

    /** Keeps an app out (or lets a default-excluded one in). */
    fun exclude(c: Context, pkg: String, out: Boolean) {
        settings(c) { s -> if (out) s.copy(excluded = (s.excluded + pkg).distinct(), included = s.included - pkg) else s.copy(excluded = s.excluded - pkg, included = (s.included + pkg).distinct()) }
        if (out) forget(c, pkg)
    }

    // ---- the senses listener ----

    private val MESSAGES = Regex("\\s*\\(\\d+ (new )?messages?\\)$")

    /**
     * WhatsApp only. Call lines become call records (ongoing ones too, which the senses skip). Any 1:1
     * notification from someone he calls in India adds its time, and only its time, to their awake window.
     * Message text is never read here.
     */
    fun notification(c: Context, sbn: StatusBarNotification) {
        if (sbn.packageName !in CallNotes.PKGS) return
        val no = sbn.notification ?: return
        val ex = no.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val n = CallNotes.parse(sbn.packageName, title, ex.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val at = sbn.postTime
        val app = c.applicationContext
        if (n != null) {
            if (n.kind != WaCall.INCOMING) io.execute { runCatching { record(app, n, at) }.onFailure { android.util.Log.w(TAG, "call note: ${it.javaClass.simpleName}") } }
            // Their call means they were up; his own outgoing call doesn't.
            if (n.kind != WaCall.OUTGOING && !n.group) io.execute { runCatching { seen(app, n.name, at) } }
            return
        }
        if (ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || no.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val name = title?.replace(MESSAGES, "")?.trim()?.takeIf { it.isNotEmpty() && !it.equals("WhatsApp", true) } ?: return
        io.execute { runCatching { seen(app, name, at) } }
    }

    /** Only for people he calls who are in India; anyone else's times are never kept. */
    private fun seen(c: Context, name: String, at: Long) {
        val key = People.keyOf(name)
        val d = RhythmsStore.load(c)
        if (!d.settings.on) return
        val india = d.callHabits.any { it.key == key && it.zone == CallRouting.INDIA.id } || d.calls.any { it.key == key && it.country == "IN" }
        if (!india) return
        val list = d.seen[key].orEmpty()
        if (list.any { kotlin.math.abs(it - at) < 5 * 60_000L }) return
        RhythmsStore.update(c) { it.copy(seen = it.seen + (key to (list + at).filter { t -> t > at - Habits.WINDOW_DAYS * DAY }.takeLast(600))) }
    }

    /** When [key] is usually up, in their zone; null until there are enough times. */
    fun awake(d: RhythmsData, key: String, zone: ZoneId, now: Long): AwakeWindow? = d.seen[key]?.let { Awake.learn(it, zone, now) }

    private fun record(c: Context, n: WaCallNote, at: Long) {
        val d = RhythmsStore.load(c)
        if (!d.settings.on) return
        val key = People.keyOf(n.name)
        val connected = n.kind == WaCall.ONGOING
        val outgoing = when (n.kind) { WaCall.OUTGOING -> true; WaCall.MISSED -> false; else -> null }
        // WhatsApp re-posts an ongoing call every few seconds: one record per call.
        if (d.calls.any { it.key == key && it.source == CallSource.WHATSAPP && it.connected == connected && it.outgoing == outgoing && kotlin.math.abs(it.at - at) < 20 * 60_000L }) return
        val country = Calls.countryOfName(c, n.name)?.first
        val r = CallRecord(key, n.name, at, 0, CallSource.WHATSAPP, connected, outgoing, n.video, country)
        RhythmsStore.update(c) { it.copy(calls = it.calls + r, since = if (it.since == 0L) at else it.since) }
        if (CallHabits.counts(r)) settle(c, key, NudgeEvent.CALLED)
    }

    // ---- learning ----

    @Synchronized
    fun refresh(c: Context, now: Long = System.currentTimeMillis()) {
        val start = RhythmsStore.load(c)
        if (!start.settings.on) { arm(c); return }
        val pm = c.packageManager
        val labels = HashMap<String, Pair<String, Boolean>>()
        fun info(pkg: String) = labels.getOrPut(pkg) {
            runCatching { pm.getApplicationInfo(pkg, 0).let { it.loadLabel(pm).toString() to (it.category == ApplicationInfo.CATEGORY_GAME) } }
                .getOrDefault(pkg.substringAfterLast('.') to false)
        }
        val fresh = if (canUsage(c)) scanUsage(c, maxOf(start.scannedTo - 3_600_000L, now - 7 * DAY), now) else emptyList()
        val log = if (canCallLog(c)) readCallLog(c, now - Habits.WINDOW_DAYS * DAY) else null
        val zone = zone()
        val d = RhythmsStore.update(c) { d0 ->
            val cut = now - Habits.WINDOW_DAYS * DAY
            fun kept(key: String, at: Long) = at > (d0.forgot[key] ?: 0L)
            val sessions = (d0.sessions + fresh.filter { s -> d0.sessions.none { it.pkg == s.pkg && it.end > s.start && it.start < s.end } })
                .filter { it.start >= cut && kept(it.pkg, it.start) && !AppExclusions.excluded(it.pkg, info(it.pkg).first, d0.settings) }
            val calls = ((log ?: d0.calls.filter { it.source == CallSource.CALL_LOG }) + d0.calls.filter { it.source == CallSource.WHATSAPP })
                .filter { it.at >= cut && kept(it.key, it.at) }
            val since = listOfNotNull(d0.since.takeIf { it > 0 }, sessions.minOfOrNull { it.start }, calls.minOfOrNull { it.at }, if (canUsage(c) || log != null) now else null).minOrNull() ?: 0L
            val habits = Habits.learn(sessions, now, zone, since, label = { info(it).first }, game = { info(it).second })
            val skips = (d0.skips + habits.flatMap { h -> Habits.skips(h, sessions, LocalDate.now(zone).minusDays(14), now, zone) })
                .distinct().filter { s -> habits.any { it.pkg == s.pkg } && runCatching { LocalDate.parse(s.date).isAfter(LocalDate.now(zone).minusDays(60)) }.getOrDefault(false) }
            val ids = HashMap<String, Long?>()
            val callHabits = CallHabits.learn(calls, now, since, zone) { key ->
                ids.getOrPut(key) { d0.callHabits.firstOrNull { it.key == key }?.contactId ?: calls.lastOrNull { it.key == key }?.let { Calls.countryOfName(c, it.name)?.second } }
            }
            d0.copy(
                sessions = sessions.takeLast(6000), habits = habits, skips = skips, calls = calls.takeLast(3000), callHabits = callHabits,
                scannedTo = if (fresh.isNotEmpty() || canUsage(c)) now else d0.scannedTo, since = since,
                nudges = d0.nudges.filter { runCatching { LocalDate.parse(it.date).isAfter(LocalDate.now(zone).minusDays(7)) }.getOrDefault(false) },
            )
        }
        arm(c, now)
        homeWeather(c, d, now)
        shareFacts(c, d, now)
    }

    /** Every 6 hours at most: Open-Meteo for home; a heatwave or heavy rain becomes a "Check on home" card. */
    private fun homeWeather(c: Context, d: RhythmsData, now: Long) {
        val s = d.settings
        if (!s.homeWeather || (d.ran["home-weather"] ?: 0L) > now - 6 * 3_600_000L) return
        val alerts = runCatching { HomeWeather.extremes(dev.phonerecall.aeolia.WeatherNet.text(HomeWeather.url(s.homeLat, s.homeLon))) }.getOrNull() ?: return
        val fresh = alerts.filter { it !in d.homeAlerts }
        RhythmsStore.update(c) { it.copy(homeAlerts = alerts, ran = it.ran + ("home-weather" to now)) }
        if (fresh.isEmpty()) return
        val today = Instant.ofEpochMilli(now).atZone(CallRouting.INDIA).toLocalDate()
        runCatching {
            val brain = BrainCore.get(c)
            for (a in fresh) {
                val (title, body) = HomeWeather.words(a, s.homeCity, today)
                val until = LocalDate.parse(a.date).plusDays(1).atStartOfDay(CallRouting.INDIA).toInstant().toEpochMilli()
                brain.store.upsertCard(dev.phonerecall.brain.SenseCard("horae:home:${a.date}:${a.kind}", title, body, "ATHENA", now, until))
            }
            brain.changed()
        }
    }

    private fun scanUsage(c: Context, from: Long, to: Long): List<Session> = runCatching {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val q = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val out = ArrayList<UEvent>()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            if (e.eventType == 1 || e.eventType == 2 || e.eventType == 23 || e.eventType == 16) out += UEvent(e.packageName.orEmpty(), e.eventType, e.timeStamp)
        }
        Habits.sessions(out)
    }.getOrDefault(emptyList())

    /** The phone's call log since [from]. Names and countries only: the number is read for its country and dropped. */
    private fun readCallLog(c: Context, from: Long): List<CallRecord>? = runCatching {
        val out = ArrayList<CallRecord>()
        c.contentResolver.query(
            CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION),
            "${CallLog.Calls.DATE} >= ?", arrayOf("$from"), "${CallLog.Calls.DATE} ASC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val number = cur.getString(1).orEmpty()
                val name = cur.getString(0)?.takeIf { it.isNotBlank() }
                if (name == null && number.isBlank()) continue
                val type = cur.getInt(2); val seconds = cur.getInt(4)
                out += CallRecord(
                    People.keyOf(name ?: number), name ?: "Unsaved number", cur.getLong(3), seconds, CallSource.CALL_LOG,
                    connected = seconds > 0 && type in setOf(CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE),
                    outgoing = type == CallLog.Calls.OUTGOING_TYPE, country = number.takeIf { it.isNotBlank() }?.let { Calls.countryOf(c, it, null) },
                )
            }
        }
        out
    }.getOrNull()

    /** Once a day: words only, to Athena and the digest. */
    private fun shareFacts(c: Context, d: RhythmsData, now: Long) {
        val day = LocalDate.now(zone()).toString()
        if (d.ran["facts:$day"] != null) return
        RhythmsStore.update(c) { it.copy(ran = (it.ran + ("facts:$day" to now)).filterValues { t -> t > now - 7 * DAY }) }
        val facts = RhythmsBrain.facts(d, now)
        if (facts.isEmpty()) return
        runCatching { BrainCore.get(c).mindHost.offer(MindNote(now, "Habits (learned on the phone): " + facts.joinToString("; ") + ".", key = "rhythms")) }
    }

    // ---- the call nudge ----

    private fun dayOf(d: RhythmsData, h: CallHabit, target: java.time.ZonedDateTime) =
        d.nudges.firstOrNull { it.key == h.key && it.date == target.toLocalDate().toString() } ?: NudgeDay(h.key, target.toLocalDate().toString())

    /** The one exact alarm: the earliest pending nudge step or usual call time. */
    fun arm(c: Context, now: Long = System.currentTimeMillis()) {
        val d = RhythmsStore.load(c)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 46_000, Intent(c, RhythmsReceiver::class.java).setAction(ACTION_WAKE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = if (!d.settings.on || !d.settings.nudges) null else d.callHabits.mapNotNull { h ->
            val pending = d.nudges.filter { it.key == h.key && it.stage != NudgeStage.DONE && it.nextAt > now }.minOfOrNull { it.nextAt }
            val up = CallHabits.upcoming(h, now)
            val upMs = up.toInstant().toEpochMilli().takeIf { dayOf(d, h, up).stage == NudgeStage.NONE }
            listOfNotNull(pending, upMs).minOrNull()
        }.minOrNull()
        if (next == null) { am.cancel(pi); return }
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /** The alarm went off: fresh call log first, then each due nudge takes one step. */
    fun wake(c: Context, now: Long = System.currentTimeMillis()) {
        if (canCallLog(c)) readCallLog(c, now - DAY)?.let { log ->
            RhythmsStore.update(c) { d -> d.copy(calls = d.calls.filter { it.source == CallSource.WHATSAPP || it.at < now - DAY } + log.filter { it.at > (d.forgot[it.key] ?: 0L) }) }
        }
        val d = RhythmsStore.load(c)
        if (!d.settings.on || !d.settings.nudges) { arm(c, now); return }
        val local = Instant.ofEpochMilli(now).atZone(zone())
        val quietUntil = if (QuietHours.quiet(local)) local.toLocalDate().let { if (local.hour >= 7) it.plusDays(1) else it }.atTime(LocalTime.of(7, 0)).atZone(zone()).toInstant().toEpochMilli() else null
        val driving = dev.phonerecall.music.MusicCore.get(c).driving || dev.phonerecall.odyssey.OdysseyStore.load(c).driving
        for (h in d.callHabits) {
            val target = CallHabits.latest(h, now + 60_000L)
            val t = target.toInstant().toEpochMilli()
            val asleep = awake(d, h.key, CallHabits.zone(h), now)?.let { Awake.asleep(it, Instant.ofEpochMilli(now).atZone(CallHabits.zone(h))) } ?: false
            val day = dayOf(RhythmsStore.load(c), h, target)
            val due = (day.stage == NudgeStage.NONE && now <= t + Nudge.GIVE_UP_MS) || (day.stage == NudgeStage.POSTED && day.nextAt in 1..(now + 60_000L))
            if (!due) continue
            val called = CallHabits.calledSince(RhythmsStore.load(c).calls, h.key, t, now)
            val step = Nudge.step(day, NudgeEvent.FIRE, now, t, called, quietUntil, driving, asleep)
            save(c, step.day)
            if (step.cancel) cancel(c, h.key)
            step.post?.let { post(c, h, it, now) }
        }
        arm(c, now)
    }

    private fun save(c: Context, day: NudgeDay) = RhythmsStore.update(c) { d -> d.copy(nudges = d.nudges.filter { !(it.key == day.key && it.date == day.date) } + day) }

    /** Later, Not today, or a call that happened: the live day for [key] takes the step. */
    fun settle(c: Context, key: String, event: NudgeEvent, now: Long = System.currentTimeMillis()) {
        val d = RhythmsStore.load(c)
        val h = d.callHabits.firstOrNull { it.key == key } ?: return
        val target = CallHabits.latest(h, now + 60_000L)
        val day = dayOf(d, h, target)
        if (event == NudgeEvent.CALLED && day.stage == NudgeStage.NONE && now < target.toInstant().toEpochMilli() - CallHabits.EARLY_MS) return
        val step = Nudge.step(day, event, now, target.toInstant().toEpochMilli(), event == NudgeEvent.CALLED)
        save(c, step.day)
        if (step.cancel) cancel(c, key)
        arm(c, now)
    }

    /** The nudge for [key] that is up right now, for the in-app card. */
    fun live(d: RhythmsData, now: Long): Pair<CallHabit, NudgeDay>? = d.callHabits.firstNotNullOfOrNull { h ->
        val day = dayOf(d, h, CallHabits.latest(h, now + 60_000L))
        if (day.stage == NudgeStage.POSTED && !day.snoozed) h to day else null
    }

    /** The lines under the nudge: what to tell them, and a weather extreme at home. */
    fun extras(d: RhythmsData, h: CallHabit, now: Long): List<String> {
        val notes = Bookmarks.open(d, h.key).map { it.note }
        val today = Instant.ofEpochMilli(now).atZone(CallRouting.INDIA).toLocalDate()
        val weather = if (h.zone == CallRouting.INDIA.id && d.settings.homeWeather) d.homeAlerts.filter { it.date >= today.toString() }.minByOrNull { it.date }?.let { HomeWeather.words(it, d.settings.homeCity, today).second } else null
        return listOfNotNull(if (notes.isEmpty()) null else "Tell them: " + notes.joinToString(" · "), weather)
    }

    // ---- things to tell ----

    fun bookmark(c: Context, person: String, note: String): Long {
        var id = 0L
        RhythmsStore.update(c) { d -> Bookmarks.add(d, person, note, System.currentTimeMillis()).also { id = it.second }.first }
        return id
    }
    fun removeBookmark(c: Context, id: Long) { RhythmsStore.update(c) { d -> d.copy(bookmarks = d.bookmarks.filter { it.id != id }) } }
    /** Ticked off after the call. Kept a week, then gone. */
    fun tick(c: Context, id: Long) {
        val now = System.currentTimeMillis()
        RhythmsStore.update(c) { d -> d.copy(bookmarks = d.bookmarks.map { if (it.id == id) it.copy(done = !it.done, at = now) else it }.filter { !it.done || it.at > now - 7 * DAY }) }
    }

    /** The ways this habit's nudge offers, best first: WhatsApp for India (video, then voice), else a phone call first. */
    fun ways(d: RhythmsData, h: CallHabit): List<String> {
        val pref = h.contactId?.let { d.callVia["$it"] }
        val india = h.zone == CallRouting.INDIA.id
        val base = if (india) listOf(CallRouting.VIDEO, CallRouting.VOICE) else listOf(CallRouting.PHONE, CallRouting.VIDEO)
        return if (pref != null && pref in base) listOf(pref) + (base - pref) else base
    }

    fun wayLabel(via: String) = when (via) { CallRouting.VIDEO -> "WhatsApp video"; CallRouting.VOICE -> "Voice"; else -> "Call" }

    private fun notifId(key: String) = 47_000 + (key.hashCode() and 0x3ff)

    private fun cancel(c: Context, key: String) { c.getSystemService(NotificationManager::class.java)?.cancel(notifId(key)) }

    private fun post(c: Context, h: CallHabit, kind: PostKind, now: Long) {
        if (!granted(c, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Call nudges (Horae)", NotificationManager.IMPORTANCE_HIGH))
        val d = RhythmsStore.load(c)
        val date = dayOf(d, h, CallHabits.latest(h, now + 60_000L)).date
        fun broadcast(action: String, code: Int) = PendingIntent.getBroadcast(
            c, code, Intent(c, RhythmsReceiver::class.java).setAction(action).putExtra("key", h.key).putExtra("date", date),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val line = (if (kind == PostKind.REMINDER) "Still time. " else "") + CallHabits.nowThere(h, now)
        val more = extras(d, h, now)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(CallHabits.ask(h))
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText((listOf(line) + more).joinToString("\n")))
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // Swiping it away is "Not today"; it never comes back that day.
            .setDeleteIntent(broadcast(ACTION_NOT_TODAY, notifId(h.key) + 2000))
        // The call buttons open a tiny activity: the tap is his confirm, and an activity may start WhatsApp.
        for ((i, via) in ways(d, h).withIndex()) {
            val pi = PendingIntent.getActivity(
                c, notifId(h.key) + 3000 + i, Intent(c, CallActionActivity::class.java).putExtra("key", h.key).putExtra("via", via).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(0, wayLabel(via), pi)
        }
        b.addAction(0, "Later", broadcast(ACTION_LATER, notifId(h.key) + 1000))
        nm.notify(notifId(h.key), b.build())
    }

    /**
     * Places the call through Fates: a PLACE_CALL proposal from Rhythms, confirmed by this tap. Returns null
     * when it started, else why not.
     */
    fun placeCall(c: Context, key: String, via: String): String? {
        val d = RhythmsStore.load(c)
        val h = d.callHabits.firstOrNull { it.key == key } ?: return "That call habit is gone"
        val brain = BrainCore.get(c)
        val args = mapOf("who" to h.name, "via" to via) + (h.contactId?.let { mapOf("contact_id" to "$it") } ?: emptyMap())
        val reason = when (val r = brain.fates.propose(listOf(RawStep(ProposalKind.PLACE_CALL, args)), null, null, "Horae")) {
            is ProposeResult.Invalid -> r.reason.substringAfter(": ")
            is ProposeResult.Created -> when (val dd = brain.fates.confirm(r.proposal.id, brain.fates.bind(r.proposal))) {
                is Decision.Done -> null
                is Decision.Failed -> dd.reason
                is Decision.Refused -> dd.reason
                else -> "Not done"
            }
        }
        if (reason == null) {
            h.contactId?.let { id -> if (via != ways(d, h).first()) Calls.rememberVia(c, id, via) }
            settle(c, key, NudgeEvent.CALLED)
        }
        return reason
    }
}

/** Words only, for the brain. Never a number, a JID or a real name (a family word like "Amma" is kept). */
object RhythmsBrain {
    fun facts(d: RhythmsData, now: Long): List<String> {
        if (!d.settings.on) return emptyList()
        return d.habits.filter { it.strong }.map { Habits.brainLine(it) } + d.callHabits.flatMap { h ->
            val w = d.seen[h.key]?.let { Awake.learn(it, CallHabits.zone(h), now) }
            listOfNotNull(CallHabits.brainLine(h, now), w?.let { "${CallHabits.term(h.name) ?: "they"} ${Awake.line(it, CallHabits.abbr(CallHabits.zone(h), CallHabits.upcoming(h, now)))}" })
        }
    }
}

/** The nudge's call buttons. No UI: proposes, confirms (his tap), starts WhatsApp or the dialer, finishes. */
class CallActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("key"); val via = intent.getStringExtra("via")
        // Late at night in India: ask first (qol/IndiaClock), unless this is his "Call anyway".
        val held = key != null && via != null && !intent.getBooleanExtra("force", false) && runCatching { dev.phonerecall.qol.IndiaClock.askFirst(this, key, via) }.getOrDefault(false)
        if (key != null && via != null && !held) Rhythms.placeCall(this, key, via)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        finish()
    }
}

class RhythmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    Rhythms.ACTION_WAKE -> Rhythms.wake(app)
                    Rhythms.ACTION_LATER -> intent.getStringExtra("key")?.let { Rhythms.settle(app, it, NudgeEvent.LATER) }
                    Rhythms.ACTION_NOT_TODAY -> intent.getStringExtra("key")?.let { Rhythms.settle(app, it, NudgeEvent.NOT_TODAY) }
                    // Boot or update: alarms were cleared.
                    else -> { RhythmsWork.schedule(app); Rhythms.arm(app); dev.phonerecall.brain.BirthdayDay.arm(app) }
                }
            } finally { pending.finish() }
        }.start()
    }
}

/** Every 6 hours, battery permitting: read new usage events and the call log, relearn, re-arm. No network. */
class RhythmsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        runCatching { Rhythms.refresh(applicationContext) }
        // Birthdays: the one push per birthday is armed from here too, so it holds with Zeus closed.
        runCatching { dev.phonerecall.brain.BirthdayDay.arm(applicationContext) }
        return Result.success()
    }
}

object RhythmsWork {
    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "zeus-rhythms", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RhythmsWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build(),
        )
    }
}
