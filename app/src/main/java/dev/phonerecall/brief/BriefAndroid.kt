package dev.phonerecall.brief

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.aeolia.Sky
import dev.phonerecall.aeolia.WeatherState
import dev.phonerecall.aeolia.WeatherStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.FESTIVAL_WORDS
import dev.phonerecall.lemnos.ApprovalRequest
import dev.phonerecall.lemnos.Gateway
import dev.phonerecall.lemnos.JobPolicy
import dev.phonerecall.lemnos.LemnosHub
import dev.phonerecall.mind.stateJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** `irisSlot`: when Iris's Telegram morning briefing fires ("07:15"), as the owner set it; null = not set, use what she told us. */
data class BriefSettings(val on: Boolean = true, val iris: Boolean = true, val speak: Boolean = false, val irisSlot: String? = null)

/** The brief's own small file: settings, today's brief, Iris's request and part, the mind's lede, yesterday's screen. */
object BriefStore {
    private const val FILE = "brief"
    private val _state = MutableStateFlow<MorningBrief?>(null)
    val state: StateFlow<MorningBrief?> = _state.asStateFlow()
    private val _settings = MutableStateFlow(BriefSettings())
    val settingsFlow: StateFlow<BriefSettings> = _settings.asStateFlow()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun <T> read(c: Context, key: String, type: Class<T>): T? = sp(c).getString(key, null)?.let { runCatching { stateJson.readValue(it, type) }.getOrNull() }
    private fun write(c: Context, key: String, v: Any?) { sp(c).edit().apply { if (v == null) remove(key) else putString(key, stateJson.writeValueAsString(v)) }.apply() }

    fun settings(c: Context): BriefSettings = (read(c, "settings", BriefSettings::class.java) ?: BriefSettings()).also { _settings.value = it }
    fun setSettings(c: Context, s: BriefSettings) { write(c, "settings", s); _settings.value = s }

    fun load(c: Context): MorningBrief? = read(c, "brief", MorningBrief::class.java).also { _state.value = it }
    fun save(c: Context, b: MorningBrief) {
        write(c, "brief", b); _state.value = b
        runCatching { dev.phonerecall.widgets.WidgetBus.section(c, dev.phonerecall.widgets.WKind.BRIEF) }
    }

    fun lastPosted(c: Context): String? = sp(c).getString("posted", null)
    fun markPosted(c: Context, date: LocalDate) = sp(c).edit().putString("posted", date.toString()).apply()

    fun requested(c: Context): String? = sp(c).getString("iris.asked", null)
    fun markRequested(c: Context, date: LocalDate) = sp(c).edit().putString("iris.asked", date.toString()).apply()

    fun iris(c: Context, date: LocalDate): IrisSection? = if (sp(c).getString("iris.date", null) == date.toString()) read(c, "iris", IrisSection::class.java) else null
    fun setIris(c: Context, date: LocalDate, s: IrisSection) { sp(c).edit().putString("iris.date", date.toString()).apply(); write(c, "iris", s) }

    fun lede(c: Context, date: LocalDate): String? = sp(c).getString("lede.$date", null)
    fun setLede(c: Context, date: LocalDate, line: String) {
        sp(c).edit().apply { sp(c).all.keys.filter { it.startsWith("lede.") }.forEach { remove(it) } }.putString("lede.$date", line.take(140)).apply()
    }

    /** Her slot as she last told us in a reply (kept across days). */
    fun irisTold(c: Context): String? = sp(c).getString("iris.slot", null)
    fun setIrisTold(c: Context, slot: String) = sp(c).edit().putString("iris.slot", slot).apply()

    /** The owner's setting wins; else what she said; else unknown. */
    fun irisSlot(c: Context): java.time.LocalTime? =
        (settings(c).irisSlot ?: irisTold(c))?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }

    fun screen(c: Context): ScreenDay? = read(c, "screen", ScreenDay::class.java)
    fun setScreen(c: Context, d: ScreenDay) = write(c, "screen", d)
}

object BriefCore {
    private const val TAG = "Zeus-Brief"
    private const val CHANNEL = "brief"
    private const val NOTIFY_ID = 7401

    // ---- entry points ----

    /** For the alarms feature: call when he dismisses the morning alarm. */
    fun onAlarmDismissed(c: Context, reason: String? = null) = BriefWork.post(c, viaAlarm = true, reason = reason)

    /** The fallback: first unlock after 5 AM (registered while the notification listener runs). */
    fun onUnlock(c: Context) {
        val now = ZonedDateTime.now()
        if (now.hour < 5 || now.hour >= 12 || BriefStore.lastPosted(c) == now.toLocalDate().toString()) return
        BriefWork.post(c, viaAlarm = false, reason = null)
    }

    // ---- sources ----

    fun prettyLabel(c: Context, pkg: String): String = runCatching {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.split('.').lastOrNull { it.length > 2 && it !in setOf("android", "app", "apps", "mobile") }?.replaceFirstChar { it.uppercase() } ?: pkg }

    /** Yesterday's screen time from UsageStats, only with Usage access (the same grant Odyssey uses). */
    fun screenDay(c: Context, today: LocalDate, zone: ZoneId): ScreenDay? {
        if (!dev.phonerecall.odyssey.Odyssey.canUsage(c)) return null
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val (from, to) = ScreenTime.window(today, zone)
        val list = ArrayList<UsageEvent>()
        val ev = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType in setOf(ScreenTime.RESUMED, ScreenTime.PAUSED, ScreenTime.STOPPED, ScreenTime.KEYGUARD_HIDDEN)) list += UsageEvent(e.packageName ?: "", e.eventType, e.timeStamp)
        }
        return ScreenTime.aggregate(list, today, zone, c.packageName) { prettyLabel(c, it) }.also { BriefStore.setScreen(c, it) }
    }

    fun gather(c: Context): BriefSources {
        val (inputs0, _) = dev.phonerecall.nowbar.NowBar.boardFromDisk(c)
        val now = inputs0.now
        val zone = now.zone
        val today = now.toLocalDate()
        val nowMs = now.toInstant().toEpochMilli()
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val w = runCatching { WeatherStore.load(c) }.getOrNull()?.takeIf { it.hasData && nowMs - it.updatedAt < 12 * 3_600_000L }
        val inputs = inputs0.copy(weather = w, venus = runCatching { dev.phonerecall.closet.VenusStore(c).load() }.getOrDefault(inputs0.venus))
        val weatherLine = w?.let { s ->
            listOfNotNull(s.tempC?.let { "${WeatherState.f(it)}°" }, Sky.words(s.code).takeIf { s.code >= 0 },
                listOfNotNull(s.highC?.let { "H ${WeatherState.f(it)}°" }, s.lowC?.let { "L ${WeatherState.f(it)}°" }).joinToString(" ").ifEmpty { null },
            ).joinToString(", ")
        }
        val alert = w?.activeAlerts(nowMs)?.firstOrNull { it.severe || it.severity == "Moderate" }?.event

        val cal = inputs.calendar.events
        val events = cal.filter { !it.allDay && it.begin in start until end && it.category != EventCategory.BIRTHDAY }.map { TimedItem(it.title, it.begin, it.place) }
        val festiveWords = Regex("(?i)\\b($FESTIVAL_WORDS)\\b")
        val festive = dev.phonerecall.home.Festivals.all.map { Festive(it.name, it.date) } +
            cal.filter { it.allDay && it.begin < end + 86_400_000L && it.end > start }.mapNotNull { e ->
                val d = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
                when {
                    e.category == EventCategory.HOLIDAY && festiveWords.containsMatchIn(e.title) -> Festive(e.title, d)
                    else -> null
                }
            }

        val roads = runCatching { dev.phonerecall.odyssey.OdysseyStore.load(c) }.getOrNull()
        val leave = roads?.takeIf { it.settings.on }?.checks?.filter { it.expiresAt > nowMs && it.leaveBy in start until end }?.minByOrNull { it.leaveBy }
            ?.let { LeaveBy(it.destination.ifBlank { it.title }, it.leaveBy, it.minutes) }
        val outfit = runCatching { dev.phonerecall.home.CardEngine.wardrobe(inputs, morningOnly = false) }.getOrNull()
            ?.let { o -> (listOf(o.title) + o.facts.take(3).map { it.value }).distinct().joinToString(", ") }
        val matches = runCatching { dev.phonerecall.olympia.OlympiaCalendar.items(dev.phonerecall.olympia.OlympiaStore.load(c), start, end, nowMs) }.getOrDefault(emptyList())
            .map { TimedItem(it.title, it.begin) }

        val screen = runCatching { screenDay(c, today, zone) }.onFailure { Log.w(TAG, "usage: ${it.message}") }.getOrNull()
        // Birthdays come from the merged book (one per person, her clock), not from the calendar alone.
        val birthdays = runCatching { dev.phonerecall.brain.BirthdayDay.briefLines(c, cal, now) }.getOrDefault(emptyList())
        return BriefSources(
            now = now,
            birthdays = birthdays,
            dayWord = inputs.availability[today]?.type?.label,
            weather = weatherLine, weatherNext = w?.next, weatherAlert = alert,
            events = events, leaveBy = leave, outfit = outfit, festive = festive, matches = matches,
            alarm = runCatching { BriefHooks.alarms?.today(today) }.getOrNull(),
            screen = screen,
            // Iaso's one derived line, only when he turned "Share with Athena" on. Shown here; never sent anywhere.
            health = runCatching { dev.phonerecall.iaso.IasoBrain.current(now) }.getOrNull(),
            iris = BriefStore.iris(c, today),
            irisAsked = BriefStore.requested(c) == today.toString(),
            lede = BriefStore.lede(c, today),
            trip = runCatching { dev.phonerecall.argo.ArgoBrief.lines(dev.phonerecall.argo.ArgoStore.load(c), nowMs, zone) }.getOrDefault(emptyList()),
            milestones = runCatching { dev.phonerecall.data.Milestones.on(today, zone) }.getOrDefault(emptyList()),
            review = runCatching { reviewOwed(c, now) }.getOrNull()?.let { "Weekly review due" },
        )
    }

    /** The Sunday whose weekly review is owed and not yet mentioned in a brief, or null. */
    private fun reviewOwed(c: Context, now: ZonedDateTime): java.time.LocalDate? {
        val store = dev.phonerecall.brain.BrainCore.get(c).tasks
        val owed = dev.phonerecall.tasks.Gtd.reviewOwed(now, store.meta(dev.phonerecall.data.TaskStore.LAST_REVIEW)?.toLongOrNull()) ?: return null
        return owed.takeIf { store.meta(dev.phonerecall.data.TaskStore.REVIEW_MENTIONED) != it.toString() }
    }

    // ---- posting ----

    /** Returns what happened, for the log. Called on a worker thread. */
    fun post(c: Context, viaAlarm: Boolean, reason: String?): BriefGate.Verdict {
        val s = BriefStore.settings(c)
        val now = ZonedDateTime.now()
        val alarm = runCatching { BriefHooks.alarms?.today(now.toLocalDate()) }.getOrNull()
        val driving = runCatching { dev.phonerecall.music.MusicCore.get(c).driving }.getOrDefault(false)
        val v = BriefGate.check(s.on, BriefStore.lastPosted(c), now, driving, viaAlarm, alarm, BriefStore.irisSlot(c))
        if (v != BriefGate.Verdict.POST) return v
        if (s.iris) runCatching { pollIris(c) }
        var src = gather(c)
        if (reason != null && src.alarm == null) src = src.copy(alarm = AlarmInfo(src.now.toInstant().toEpochMilli(), reason, unusual = true))
        val b = BriefAssembler.build(src)
        // Eos mentions an owed weekly review once per cycle.
        if (src.review != null) runCatching { reviewOwed(c, src.now)?.let { dev.phonerecall.brain.BrainCore.get(c).tasks.setMeta(dev.phonerecall.data.TaskStore.REVIEW_MENTIONED, it.toString()) } }
        BriefStore.markPosted(c, src.now.toLocalDate())
        BriefStore.save(c, b)
        notify(c, b, silent = BriefGate.silent(src.now))
        file(c, b)
        runCatching { dev.phonerecall.brain.BrainCore.get(c).changed() }
        if (s.speak && viaAlarm && !BriefGate.silent(src.now)) speak(c, BriefAssembler.speech(b))
        return v
    }

    /** Her part arrived after the brief went out: the same notification and card, rebuilt, without a sound. */
    fun refresh(c: Context) {
        val today = LocalDate.now()
        if (BriefStore.lastPosted(c) != today.toString()) return
        val old = BriefStore.load(c) ?: return
        val b = BriefAssembler.build(gather(c)).copy(postedAt = old.postedAt, proposals = old.proposals)
        BriefStore.save(c, b)
        notify(c, b, silent = true)
        runCatching { dev.phonerecall.brain.BrainCore.get(c).changed() }
    }

    private fun file(c: Context, b: MorningBrief) {
        val fates = runCatching { dev.phonerecall.brain.BrainCore.get(c).fates }.getOrNull() ?: return
        for (p in b.proposals) {
            val kind = ProposalKind.of(p.kind) ?: continue
            runCatching { fates.propose(listOf(RawStep(kind, p.args)), p.title, null, "Athena (brief)") }
        }
    }

    private fun notify(c: Context, b: MorningBrief, silent: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Morning brief", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Once a day, after your alarm" })
        val (title, text) = BriefAssembler.notification(b, runCatching { dev.phonerecall.data.Prefs(c).load().plainNames }.getOrDefault(false))
        val open = PendingIntent.getActivity(c, NOTIFY_ID, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(dev.phonerecall.ui.Place.BRIEF.plain).setContentText("Unlock to read").build()
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(b.lede ?: b.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setOnlyAlertOnce(true)
            .setSilent(silent)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFY_ID, n) }
    }

    private fun speak(c: Context, text: String) {
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        var ok = false
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c.applicationContext) { status -> ok = status == TextToSpeech.SUCCESS; ready.countDown() }
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || !ok) return
            tts.language = Locale.US
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("Deprecated in Java") override fun onError(id: String?) = done.countDown()
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brief")
            done.await(90, TimeUnit.SECONDS)
        } finally { tts.shutdown() }
    }

    // ---- Iris ----

    /** Sends the day's request (an unsigned read-only job). Throws on a network failure so the worker retries. */
    fun requestIris(c: Context, date: LocalDate = LocalDate.now()) {
        if (!BriefStore.settings(c).iris || !LemnosHub.get(c).paired) return
        if (BriefStore.requested(c) == date.toString()) return
        val eosAt = runCatching { BriefHooks.alarms?.today(date)?.at?.let { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() } ?: BriefHooks.wake?.learned(date) }.getOrNull()
        val payload = BriefIris.request(date, ZoneId.systemDefault(), eosAt?.withSecond(0)?.withNano(0), BriefStore.irisSlot(c))
        val req = ApprovalRequest(BriefIris.jobId(date), BriefIris.KIND, "iris", payload)
        Gateway.submitJob(JobPolicy.Outgoing(req, BriefIris.title(date), null))
        BriefStore.markRequested(c, date)
        BriefWork.poll(c, delayMin = 25)
    }

    /** Looks for her answer to today's job. True when a new part was filed. */
    fun pollIris(c: Context): Boolean {
        val today = LocalDate.now()
        if (BriefStore.requested(c) != today.toString() || BriefStore.iris(c, today) != null || !LemnosHub.get(c).paired) return false
        val job = Gateway.jobs().firstOrNull { it.jobId == BriefIris.jobId(today) } ?: return false
        if (job.status != "answered") return false
        val section = BriefIris.parse(job.result, System.currentTimeMillis()) ?: IrisSection(listOf(BriefLine("Note", "Iris answered outside the brief format; it's in ${dev.phonerecall.ui.Workers.IRIS}'s room")), System.currentTimeMillis())
        BriefStore.setIris(c, today, section)
        section.slot?.let { BriefStore.setIrisTold(c, it) }
        refresh(c)
        return true
    }

    // ---- the request alarm ----

    fun schedule(c: Context) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        fun at(d: LocalDate) = d.atTime(BriefGate.requestAt(d, runCatching { BriefHooks.wake?.learned(d) }.getOrNull())).atZone(zone)
        val next = at(now.toLocalDate()).takeIf { it.isAfter(now) } ?: at(now.toLocalDate().plusDays(1))
        val am = c.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(c, NOTIFY_ID, Intent(c, BriefReceiver::class.java).setAction(BriefReceiver.ACTION_REQUEST), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val t = next.toInstant().toEpochMilli()
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
    }
}

/** The brief's background work: one unique queue per kind. */
object BriefWork {
    fun post(c: Context, viaAlarm: Boolean, reason: String?, delayMin: Long = 0) = enqueue(c, "brief.post", workDataOf("mode" to "post", "alarm" to viaAlarm, "reason" to reason), delayMin, network = false)
    fun request(c: Context) = enqueue(c, "brief.request", workDataOf("mode" to "request"), 0, network = true)
    fun poll(c: Context, delayMin: Long) = enqueue(c, "brief.poll", workDataOf("mode" to "poll"), delayMin, network = true)

    private fun enqueue(c: Context, name: String, data: androidx.work.Data, delayMin: Long, network: Boolean) {
        val b = OneTimeWorkRequestBuilder<BriefWorker>().setInputData(data).setInitialDelay(delayMin, TimeUnit.MINUTES)
        if (network) b.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        WorkManager.getInstance(c).enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, b.build())
    }
}

class BriefWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val c = applicationContext
        return try {
            when (inputData.getString("mode")) {
                "request" -> BriefCore.requestIris(c)
                "poll" -> BriefCore.pollIris(c)
                "post" -> {
                    val alarm = inputData.getBoolean("alarm", false)
                    // Driving: hold until he's stopped, checking every ten minutes.
                    when (BriefCore.post(c, alarm, inputData.getString("reason"))) {
                        BriefGate.Verdict.HOLD_DRIVING -> BriefWork.post(c, alarm, inputData.getString("reason"), delayMin = 10)
                        // Too close to Iris's Telegram briefing: land 30 minutes after it instead.
                        BriefGate.Verdict.NEAR_IRIS -> BriefWork.post(c, alarm, inputData.getString("reason"),
                            delayMin = BriefGate.irisHold(java.time.ZonedDateTime.now(), BriefStore.irisSlot(c)).coerceAtLeast(1))
                        else -> {}
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w("Zeus-Brief", "${inputData.getString("mode")}: ${e.message}")
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }
}

/** The daily request alarm, and re-arming it after a restart or an update. */
class BriefReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action == ACTION_REQUEST) BriefWork.request(app)
        runCatching { BriefCore.schedule(app) }
    }

    companion object { const val ACTION_REQUEST = "dev.phonerecall.BRIEF_REQUEST" }
}

/** Registered at runtime by the notification listener (USER_PRESENT can't be declared in the manifest). */
class BriefUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) BriefCore.onUnlock(context.applicationContext)
    }
}

