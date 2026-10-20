package dev.phonerecall.olympia

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.home.Fmt
import java.time.ZonedDateTime

/** What the live notification says, worked out from a match. Pure, so the wording is tested. */
data class NoticeText(val chip: String, val title: String, val text: String, val big: String?, val progress: Pair<Int, Int>?)

object NoticeWords {
    fun of(l: Live): NoticeText {
        val m = l.match
        val last = l.lastKey?.text
        return when (m.sport) {
            Sport.SOCCER -> NoticeText(
                chip = m.home?.let { h -> "${h.abbr} ${m.score ?: ""}".trim() } ?: m.clock,
                title = if (m.score != null) "${m.home?.name} ${m.score} ${m.away?.name}" else m.title,
                text = listOfNotNull(m.clock.ifEmpty { null }, last).joinToString(" · "),
                big = null, progress = null,
            )
            Sport.NFL -> NoticeText(
                chip = m.scoreLine().substringBeforeLast(' ').take(12),
                title = m.scoreLine(),
                text = listOfNotNull(m.clock.ifEmpty { null }, l.situation).joinToString(" · "),
                big = last, progress = null,
            )
            Sport.CRICKET -> {
                val (a, b) = m.ordered()
                val bat = listOfNotNull(a, b).lastOrNull { !it.score.isNullOrEmpty() }
                NoticeText(
                    chip = bat?.let { "${it.abbr} ${it.score?.substringBefore(' ')}" }?.take(12) ?: "IPL",
                    title = listOfNotNull(a, b).joinToString(" v ") { s -> s.abbr + (s.score?.substringBefore(" (")?.let { " $it" } ?: "") },
                    text = listOfNotNull(m.clock.ifEmpty { null }, l.situation).joinToString(" · ").ifEmpty { m.detail },
                    big = last, progress = l.progress,
                )
            }
            Sport.F1 -> NoticeText(
                chip = l.positions.firstOrNull()?.let { "P1 ${it.take(3).uppercase()}" } ?: "F1",
                title = m.title,
                text = (listOf(m.clock.ifEmpty { m.session.orEmpty() }) + l.positions.take(2).mapIndexed { i, d -> "P${i + 1} $d" }).filter { it.isNotEmpty() }.joinToString(" · "),
                big = last, progress = l.progress,
            )
        }
    }

    /** The lead-in line before the start: "Barcelona v Real Madrid", "Kickoff 4:00 PM". */
    fun before(m: Match, now: ZonedDateTime): NoticeText = NoticeText(
        chip = if (m.sport == Sport.F1) "F1" else (m.home?.abbr ?: m.leagueName).take(12),
        title = m.title,
        text = (when (m.sport) { Sport.SOCCER -> "Kickoff"; Sport.NFL -> "Kickoff"; Sport.CRICKET -> "First ball"; Sport.F1 -> "Lights out" }) + " " + Fmt.time(m.start, now),
        big = null, progress = null,
    )
}

/**
 * The one live notification, updated in place (same id), asking to be promoted to the Now Bar and the
 * lock screen (Android 16 Live Update), set as platform extras the way NowBar does, because this build
 * compiles against SDK 35. It is also the foreground service's notification. Silent on every update
 * except a key moment, and never a sound in quiet hours.
 */
object LiveNotice {
    const val ID = 62_000
    private const val CHANNEL = "olympia_live"
    private const val RESULTS = "olympia_results"
    private const val EXTRA_PROMOTE = "android.requestPromotedOngoing"
    private const val EXTRA_CHIP = "android.shortCriticalText"

    fun canNotify(c: Context) = c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Live scores", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One notification per match day, updated in place. Sounds only for key moments."
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(RESULTS, "Results", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Full time for your teams"
                setShowBadge(false)
            },
        )
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, ID, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "OLYMPIA")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** `sound` is true only for an update that carries a new key moment. */
    fun build(c: Context, t: NoticeText, sound: Boolean, countdownTo: Long? = null): Notification {
        channels(c)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(t.title)
            .setContentText(t.text)
            .setContentIntent(open(c))
            .setOngoing(true)
            .setOnlyAlertOnce(!sound)
            .setSilent(!sound)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply {
                putBoolean(EXTRA_PROMOTE, true)
                if (countdownTo == null) putString(EXTRA_CHIP, t.chip.take(12))
            })
        t.big?.let { b.setStyle(NotificationCompat.BigTextStyle().bigText(listOf(t.text, it).filter { s -> s.isNotEmpty() }.joinToString("\n"))) }
        t.progress?.let { (done, of) -> if (of > 0) b.setProgress(of, done.coerceIn(0, of), false) }
        if (countdownTo != null) b.setWhen(countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        return b.build()
    }

    fun post(c: Context, n: Notification) {
        if (!canNotify(c) || dev.phonerecall.brain.AlarmWatch.get(c).napQuiet()) return
        c.getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    fun clear(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID)

    /** Full time: a normal notification that stays after the live one goes. */
    fun result(c: Context, m: Match, line: String, silent: Boolean = false) {
        if (!canNotify(c)) return
        channels(c)
        val n = NotificationCompat.Builder(c, RESULTS)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(line)
            .setContentText(m.leagueName)
            .setContentIntent(open(c))
            .setAutoCancel(true)
            .setSilent(silent || dev.phonerecall.brain.QuietHours.quiet(ZonedDateTime.now()))
            .build()
        // A result is nice to have: over the day's budget it waits for the morning brief.
        dev.phonerecall.brain.BrainCore.get(c).attention.notify(dev.phonerecall.brain.Urgency.NICE, "result:${m.id}", line) {
            c.getSystemService(NotificationManager::class.java).notify(ID + 1 + (m.id.hashCode() and 0xfff), n)
        }
    }
}
