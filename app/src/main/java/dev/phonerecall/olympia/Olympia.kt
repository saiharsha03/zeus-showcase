package dev.phonerecall.olympia

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.brain.QuietHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Olympia's wiring. Fixtures refresh twice a day (WorkManager, on any network). One exact alarm is set
 * for the next lineup look or lead-in. At the lead-in the alarm starts [LiveService], which polls while
 * a followed match is on and stops itself after full time. With "Results only" or "Off" no service
 * runs: an alarm at the expected end reads the result once.
 */
object Olympia {
    private const val WORK = "olympia-fixtures"
    private const val ALARM = 62_100

    /** From the app's start: load, keep the refresh job, refresh now if stale, set the alarm. */
    fun start(c: Context) {
        val app = c.applicationContext
        thread(name = "olympia-start") {
            val s = OlympiaStore.load(app)
            val wm = WorkManager.getInstance(app)
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<FixturesWorker>(12, TimeUnit.HOURS).setConstraints(net).build(),
            )
            if (System.currentTimeMillis() - s.refreshedAt > 6 * MatchDay.HOUR) refreshSoon(app)
            arm(app)
        }
    }

    fun refreshSoon(c: Context) {
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(c).enqueueUniqueWork(
            "$WORK-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<FixturesWorker>().setConstraints(net).build(),
        )
    }

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(
        c, ALARM, Intent(c, MatchDayReceiver::class.java).setAction(MatchDayReceiver.WAKE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Sets the one alarm for the next wake, or starts the live service now if a match is on. */
    fun arm(c: Context, now: Long = System.currentTimeMillis()) {
        val s = OlympiaStore.load(c)
        val am = c.getSystemService(AlarmManager::class.java)
        if (s.settings.alerts == Alerts.OFF && s.settings.followed.isEmpty()) { am.cancel(alarmIntent(c)); return }
        val matches = s.followed
        val streaming = s.settings.alerts == Alerts.KEY || s.settings.alerts == Alerts.QUIET
        if (streaming && MatchDay.liveNow(matches, now).isNotEmpty()) LiveService.start(c)
        val at = if (streaming) MatchDay.nextWake(matches, now) else ResultCheck.next(matches, now)
        if (at == null) { am.cancel(alarmIntent(c)); return }
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(c))
    }

    /** After a settings change: re-arm, and stop the service if it should no longer run. */
    fun settingsChanged(c: Context) {
        thread(name = "olympia-settings") {
            val s = OlympiaStore.load(c)
            if (s.settings.alerts == Alerts.RESULTS || s.settings.alerts == Alerts.OFF) LiveService.stop(c)
            refreshSoon(c)
            arm(c)
            LiveWidget.update(c)
        }
    }
}

/** With "Results only" or "Off": read each match's result once, around its usual end. */
object ResultCheck {
    fun typical(m: Match): Long = when (m.sport) {
        Sport.SOCCER -> 115 * MatchDay.MIN
        Sport.NFL -> 3 * MatchDay.HOUR + 15 * MatchDay.MIN
        Sport.CRICKET -> 3 * MatchDay.HOUR + 45 * MatchDay.MIN
        Sport.F1 -> if (m.session == "Race") 2 * MatchDay.HOUR else 70 * MatchDay.MIN
    }

    /** Pending results to read: started, not known to be over, within the backstop. */
    fun pending(matches: List<Match>, now: Long): List<Match> =
        matches.filter { it.phase != Phase.DONE && it.phase != Phase.OFF && now >= it.start + typical(it) && now < it.start + MatchDay.length(it) + MatchDay.HOUR }

    fun next(matches: List<Match>, now: Long): Long? {
        if (pending(matches, now).isNotEmpty()) return now + 20 * MatchDay.MIN
        return matches.filter { it.phase == Phase.PRE }.map { it.start + typical(it) }.filter { it > now }.minOrNull()
    }
}

class FixturesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val c = applicationContext
        val s = runCatching { Sync(c).refresh() }.getOrNull() ?: return Result.retry()
        Olympia.arm(c)
        LiveWidget.update(c)
        return if (s.refreshedAt > 0) Result.success() else Result.retry()
    }
}

/** The one alarm: lineup looks, lead-ins, result reads. Also re-arms after a restart or an update. */
class MatchDayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val c = context.applicationContext
        thread(name = "olympia-wake") {
            try {
                val now = System.currentTimeMillis()
                val s = OlympiaStore.load(c)
                val sync = Sync(c)
                for (m in s.followed.filter { MatchDay.lineupsDue(it, now) }) runCatching { sync.lineups(m) }
                if (s.settings.alerts == Alerts.RESULTS || s.settings.alerts == Alerts.OFF) {
                    for (m in ResultCheck.pending(s.followed, now)) runCatching {
                        val (live, _) = sync.poll(m, 0) ?: return@runCatching
                        if (live.match.phase == Phase.DONE && s.settings.alerts == Alerts.RESULTS) {
                            LiveNotice.result(c, live.match, live.moments.lastOrNull { it.final }?.text ?: live.match.scoreLine())
                        }
                    }
                }
                Olympia.arm(c, now)
                LiveWidget.update(c)
            } finally { pending.finish() }
        }
    }

    companion object { const val WAKE = "dev.phonerecall.OLYMPIA_WAKE" }
}

/**
 * Match day's foreground service (dataSync): polls each live followed match at its sport's pace,
 * keeps the one notification and the widget current, and stops when nothing is on.
 */
class LiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        val now = System.currentTimeMillis()
        val s = OlympiaStore.load(this)
        val first = MatchDay.headline(s.followed, now)
        val text = first?.let { m -> s.live[m.id]?.takeIf { m.phase == Phase.LIVE }?.let(NoticeWords::of) ?: NoticeWords.before(m, ZonedDateTime.now()) }
            ?: NoticeText("Olympia", "Match day", "Waiting for kickoff", null, null)
        val n = LiveNotice.build(this, text, sound = false, countdownTo = first?.start?.takeIf { it > now })
        ServiceCompat.startForeground(this, LiveNotice.ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_NOT_STICKY
    }

    private suspend fun run() {
        val sync = Sync(this)
        val last = HashMap<String, Long>()
        val polls = HashMap<String, Int>()
        val done = HashSet<String>()
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val s = OlympiaStore.load(this)
            if (s.settings.alerts != Alerts.KEY && s.settings.alerts != Alerts.QUIET) break
            val on = MatchDay.liveNow(s.followed, now).filter { it.id !in done }
            if (on.isEmpty()) break
            val sounds = ArrayList<Moment>()
            for (m in on) {
                if (MatchDay.due(m, last[m.id], now) > now) continue
                val n = polls[m.id] ?: 0
                val r = runCatching { sync.poll(m, n) }.getOrNull()
                last[m.id] = now
                polls[m.id] = n + 1
                if (r == null) continue
                val (live, fresh) = r
                // Full time is its own notification, which stays after the live one goes.
                sounds += Moments.alerting(fresh, s.settings.alerts).filter { !it.final }
                fresh.lastOrNull { it.final }?.let { LiveNotice.result(this, live.match, it.text, silent = s.settings.alerts == Alerts.QUIET) }
                if (live.match.phase == Phase.DONE || live.match.phase == Phase.OFF) done += m.id
            }
            val state = OlympiaStore.load(this)
            val head = MatchDay.headline(state.followed.filter { it.id !in done }, now)
            val live = head?.let { state.live[it.id] }
            val quiet = QuietHours.quiet(ZonedDateTime.now())
            val text = when {
                head == null -> null
                live != null && live.match.phase == Phase.LIVE -> NoticeWords.of(live)
                else -> NoticeWords.before(head, ZonedDateTime.now())
            }
            if (text != null) {
                val withMoment = sounds.lastOrNull()?.let { text.copy(text = listOf(text.text.substringBefore(" · "), it.text).filter { s -> s.isNotEmpty() }.joinToString(" · ")) } ?: text
                LiveNotice.post(this, LiveNotice.build(this, withMoment, sound = sounds.isNotEmpty() && !quiet, countdownTo = head?.start?.takeIf { it > now }))
            }
            LiveWidget.update(this)
            val next = MatchDay.liveNow(state.followed, now).filter { it.id !in done }.minOfOrNull { MatchDay.due(it, last[it.id], now) } ?: break
            delay((next - System.currentTimeMillis()).coerceIn(5_000L, 60_000L))
        }
        finish()
    }

    private fun finish() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        LiveNotice.clear(this)
        val app = applicationContext
        thread(name = "olympia-rearm") { Olympia.arm(app); LiveWidget.update(app) }
        stopSelf()
    }

    /** Android 15 caps dataSync services at six hours a day; stop cleanly if a day ever gets there. */
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val STOP = "dev.phonerecall.OLYMPIA_STOP"
        fun start(c: Context) { runCatching { c.startForegroundService(Intent(c, LiveService::class.java)) } }
        fun stop(c: Context) { runCatching { c.startService(Intent(c, LiveService::class.java).setAction(STOP)) } }
    }
}
