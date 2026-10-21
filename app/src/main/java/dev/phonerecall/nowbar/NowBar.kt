package dev.phonerecall.nowbar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.CalendarPipeline
import dev.phonerecall.data.CalendarSource
import dev.phonerecall.data.RelevanceStore
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.data.EngagementStore
import dev.phonerecall.data.AppPrefs
import dev.phonerecall.data.Prefs
import dev.phonerecall.data.TaskStore
import dev.phonerecall.home.Board
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardEngine
import dev.phonerecall.home.LiveInfo
import dev.phonerecall.home.SampleData
import java.time.ZonedDateTime
import kotlin.concurrent.thread

const val ACTION_OPEN_HOME = "dev.phonerecall.OPEN_HOME"

/**
 * The Now Bar (One UI 8) and status-bar chip: an Android 16 Live Update, i.e. an ongoing notification
 * that asks to be promoted. Shown only while the top Home card is time-sensitive (an event within 60
 * minutes, something due today, a travel or brain card); cleared otherwise.
 *
 * No foreground service. Updates happen when the app re-scores, and from exact alarms set at the next
 * moment the top card can change (an event edge, an expiry, the hour), so at most once a minute.
 *
 * The promotion flags are set as platform extras (android.requestPromotedOngoing and
 * android.shortCriticalText), exactly what NotificationCompat.setRequestPromotedOngoing writes, because
 * this build compiles against SDK 35 and that API needs 36. Older Android shows a normal ongoing notification.
 */
object NowBar {
    private const val CHANNEL = "nowbar"
    private const val ID = 61_000
    private const val EXTRA_PROMOTE = "android.requestPromotedOngoing"
    private const val EXTRA_CHIP = "android.shortCriticalText"

    @Volatile private var last: LiveInfo? = null
    @Volatile private var lastAlarm = 0L

    fun sync(c: Context, board: Board, enabled: Boolean) {
        val top = if (enabled) board.cards.firstOrNull() else null
        val live = top?.content?.live
        // A swiped-away item is already filtered out of the board (prefs.dismissed), so it cannot repost after a restart.
        if (live == null || top == null) clear(c) else post(c, live, live.key ?: top.instanceKey, top.relevantUntil ?: live.countdownTo?.plus(3_600_000L))
        if (enabled) schedule(c, board.nextChange) else cancelAlarm(c)
    }

    private fun canNotify(c: Context) =
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(c: Context, live: LiveInfo, instanceKey: String, until: Long?) {
        if (!canNotify(c)) return
        if (live == last) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Now Bar", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "The top Home card while it is time-sensitive"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            c, ID, Intent(c, MainActivity::class.java).setAction(ACTION_OPEN_HOME).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val extras = Bundle().apply {
            putBoolean(EXTRA_PROMOTE, true)
            // With a countdown, leave the chip to the system so it shows the live time left.
            if (live.countdownTo == null) putString(EXTRA_CHIP, live.chip.take(12))
        }
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(live.title)
            .setContentText(live.text)
            .setContentIntent(open)
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    c, ID + 1,
                    Intent(c, NowBarReceiver::class.java).setAction(NowBarReceiver.ACTION_DISMISS)
                        .putExtra(NowBarReceiver.EXTRA_KEY, instanceKey).putExtra(NowBarReceiver.EXTRA_UNTIL, until ?: 0L),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(if (live.countdownTo != null) NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_owl)
                    .setContentTitle(if (live.countdownTo != null) "Event soon" else "Athena").build(),
            )
            .addExtras(extras)
        if (live.sample) b.setSubText("Sample")
        // A match's chip: Watch opens the app carrying it (installed only), else nothing extra.
        runCatching { dev.phonerecall.olympia.WatchApps.pending(c, live.watch) }.getOrNull()?.let { b.addAction(0, "Watch", it) }
        if (live.countdownTo != null) {
            b.setWhen(live.countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            // Gone by itself shortly after the start if no update arrives.
            b.setTimeoutAfter((live.countdownTo - System.currentTimeMillis() + 60_000L).coerceAtLeast(60_000L))
        }
        nm.notify(ID, b.build())
        last = live
    }

    /** Always cancels: after a restart the process does not know what was last posted. */
    fun clear(c: Context) {
        c.getSystemService(NotificationManager::class.java).cancel(ID)
        last = null
    }

    private fun alarmIntent(c: Context) = PendingIntent.getBroadcast(
        c, ID, Intent(c, NowBarReceiver::class.java).setAction(NowBarReceiver.ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun schedule(c: Context, at: Long) {
        // Never more often than once a minute.
        val t = at.coerceAtLeast(System.currentTimeMillis() + 60_000L)
        if (t == lastAlarm) return
        val am = c.getSystemService(AlarmManager::class.java)
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, alarmIntent(c))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, alarmIntent(c))
        lastAlarm = t
    }

    private fun cancelAlarm(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))
        lastAlarm = 0L
    }

    /** Rebuilds the board from disk (the app may not be running) and syncs. Call off the main thread. */
    fun refreshFromDisk(c: Context) {
        val p = Prefs(c).load()
        if (!p.nowBar) { clear(c); cancelAlarm(c); return }
        val (_, board) = boardFromDisk(c, p)
        lastAlarm = 0L
        sync(c, board, true)
    }

    /** The board and its inputs from the phone's own stores. Also what the home-screen widgets rebuild from. */
    fun boardFromDisk(c: Context, p: AppPrefs = Prefs(c).load()): Pair<BoardInputs, Board> {
        val now = ZonedDateTime.now()
        // The same curated calendar the app shows: relevance rules, contact birthdays, availability.
        val source = CalendarSource(c)
        val calendars = source.calendars().associateBy { it.id }
        dev.phonerecall.data.DayOverrides.load(c)
        val cur = CalendarPipeline.curate(source.read(calendars), calendars, RelevanceStore(c).load(), BirthdaySource(c).read())
        val inputs = BoardInputs(
            now = now, calendar = cur.snapshot, availability = cur.availability, sample = p.sample, prefs = p.home,
            learned = EngagementStore(c).load(), tasks = TaskStore(c).load(),
            closet = ClosetSource(c).load(),
            generated = if (p.sample) SampleData.generated(now) else emptyList(), generatedAreSamples = true,
            morning = dev.phonerecall.brief.BriefStore.state.value ?: runCatching { dev.phonerecall.brief.BriefStore.load(c) }.getOrNull(),
            trip = runCatching { dev.phonerecall.argo.Argo.home(c, now.toInstant().toEpochMilli(), now.zone) }.getOrNull(),
            sports = runCatching {
                dev.phonerecall.olympia.OlympiaCards.card(dev.phonerecall.olympia.OlympiaStore.load(c), now, dev.phonerecall.olympia.WatchApps.chooser(c)) { dev.phonerecall.olympia.WatchApps.quiet(c) }
            }.getOrNull(),
            touches = if (p.sample) emptyList() else dev.phonerecall.qol2.Touches.cards(c, now, cur.snapshot.events, cur.availability[now.toLocalDate()]?.type == dev.phonerecall.data.DayType.WORKDAY),
        )
        return inputs to CardEngine.build(inputs)
    }
}

/** Wakes at the scheduled edge, after a reboot, or after an update, and re-syncs the Now Bar. */
class NowBarReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DISMISS) {
            val key = intent.getStringExtra(EXTRA_KEY) ?: return
            val pending = goAsync()
            val app = context.applicationContext
            thread(name = "nowbar-dismiss") {
                try {
                    // The same instance as the Home card: swiping either one clears both.
                    dev.phonerecall.data.Dismissals.add(app, key, intent.getLongExtra(EXTRA_UNTIL, 0L).takeIf { it > 0 }, System.currentTimeMillis())
                    runCatching { NowBar.refreshFromDisk(app) }
                    runCatching { dev.phonerecall.widgets.WidgetBus.refreshFromDisk(app, force = true) }
                } finally { pending.finish() }
            }
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        thread(name = "nowbar") {
            try {
                runCatching { NowBar.refreshFromDisk(app) }
                // The same edges, restarts and updates redraw the home-screen widgets (a no-op with none placed).
                runCatching { dev.phonerecall.widgets.WidgetBus.refreshFromDisk(app, force = true) }
            } finally { pending.finish() }
        }
    }

    companion object {
        const val ACTION_TICK = "dev.phonerecall.NOWBAR_TICK"
        const val ACTION_DISMISS = "dev.phonerecall.NOWBAR_DISMISS"
        const val EXTRA_KEY = "key"
        const val EXTRA_UNTIL = "until"
    }
}
