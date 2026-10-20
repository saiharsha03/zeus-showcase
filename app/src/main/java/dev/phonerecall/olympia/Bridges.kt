package dev.phonerecall.olympia

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalKind
import dev.phonerecall.data.CalendarInfo
import dev.phonerecall.data.EventCategory
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.home.Fmt
import dev.phonerecall.ui.Tone
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/*
 * Where Olympia meets the rest of Zeus: Chronos entries, the Olympus card, the home-screen widget.
 */

/** Olympia as a calendar in Chronos: its own row in Themis' calendar relevance, shown by default. */
object OlympiaCalendar {
    const val ID = -7L
    val INFO = CalendarInfo(ID, "Olympia", "olympia", CalKind.FOLLOWED, 0)

    /** Followed fixtures in [from, to) as calendar items. `exceptLive` leaves out what the live card already shows. */
    fun items(s: OlympiaSnapshot, from: Long, to: Long, now: Long = System.currentTimeMillis(), exceptLive: Boolean = false): List<CalItem> =
        s.followed.filter { it.phase != Phase.OFF && it.start < to && it.start + ResultCheck.typical(it) > from }
            .filter { !(exceptLive && MatchDay.live(it, now)) }
            .map { m ->
                CalItem(
                    eventId = -(m.id.hashCode().toLong() and 0x7fffffffL) - 1, calendarId = ID, title = m.title,
                    begin = m.start, end = m.start + ResultCheck.typical(m), allDay = false, category = EventCategory.SPORT,
                    calendarName = "Olympia", place = null, notes = m.leagueName, recurring = false,
                )
            }
}

/** The Olympus card: a live match, lineups that just came out, or a result from the last 12 hours. */
data class SportsCard(val content: CardContent, val fit: Float, val updatedAt: Long?)

object OlympiaCards {
    private val OPEN = CardAction("Open Olympia", ActionRole.OPEN_OLYMPIA)

    fun card(s: OlympiaSnapshot, now: ZonedDateTime): SportsCard? {
        val ms = now.toInstant().toEpochMilli()
        val matches = s.followed
        MatchDay.liveNow(matches, ms).filter { it.phase == Phase.LIVE }.maxByOrNull { it.start }?.let { m ->
            val l = s.live[m.id] ?: Live(m)
            val w = NoticeWords.of(l)
            return SportsCard(
                CardContent(
                    title = w.title,
                    body = (if (m.sport == Sport.F1) w.text else listOfNotNull(m.clock.ifEmpty { null }, l.situation).joinToString(" · ")).ifEmpty { null },
                    facts = listOfNotNull(l.lastKey?.let { Fact("Last", it.text) }),
                    status = "Live", tone = Tone.ACTIVE, actions = listOf(OPEN),
                ),
                fit = 30f, updatedAt = l.updatedAt,
            )
        }
        matches.filter { it.phase == Phase.PRE && it.start > ms && it.start - ms <= 75 * MatchDay.MIN }.minByOrNull { it.start }?.let { m ->
            val lu = s.live[m.id]?.lineups.orEmpty()
            if (lu.isNotEmpty()) {
                return SportsCard(
                    CardContent(
                        title = m.title,
                        body = "Lineups are out. " + NoticeWords.before(m, now).text + ".",
                        facts = lu.map { Fact(it.team, it.starters.joinToString(", ")) },
                        status = "Soon", tone = Tone.ACTIVE, actions = listOf(OPEN),
                    ),
                    fit = 18f, updatedAt = s.live[m.id]?.updatedAt,
                )
            }
        }
        matches.filter { it.phase == Phase.DONE && ms - (it.start + ResultCheck.typical(it)) in 0..12 * MatchDay.HOUR }.maxByOrNull { it.start }?.let { m ->
            return SportsCard(
                CardContent(title = resultLine(m, s), body = m.leagueName, status = "Final", tone = Tone.QUIET, actions = listOf(OPEN)),
                fit = 6f, updatedAt = m.start + ResultCheck.typical(m),
            )
        }
        return null
    }

    fun resultLine(m: Match, s: OlympiaSnapshot): String {
        s.live[m.id]?.moments?.lastOrNull { it.final }?.let { return it.text }
        return when (m.sport) {
            Sport.CRICKET -> m.result ?: m.scoreLine()
            Sport.F1 -> m.result?.let { "$it wins the ${m.title}" } ?: m.title
            else -> m.scoreLine()
        }
    }
}

/** The home-screen widget. Updated by the service while a match is on and after each refresh; never polled. */
class LiveWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = update(context)

    data class View(val kicker: String, val title: String, val line: String, val foot: String)

    companion object {
        /** Live first; else a result from the last 12 hours; else the next fixture; else a quiet line. */
        fun pick(s: OlympiaSnapshot, now: ZonedDateTime): View {
            val ms = now.toInstant().toEpochMilli()
            val matches = s.followed
            val next = matches.filter { it.phase == Phase.PRE && it.start > ms }.minByOrNull { it.start }
            val nextLine = next?.let { "Next: ${it.title}, ${whenText(it, now)}" }.orEmpty()
            MatchDay.liveNow(matches, ms).filter { it.phase == Phase.LIVE }.maxByOrNull { it.start }?.let { m ->
                val l = s.live[m.id] ?: Live(m)
                val w = NoticeWords.of(l)
                return View("Live · ${m.leagueName}", w.title, listOfNotNull(w.text.ifEmpty { null }, l.lastKey?.text?.takeIf { m.sport != Sport.SOCCER }).joinToString("\n"), nextLine.takeIf { next?.id != m.id }.orEmpty())
            }
            matches.filter { it.phase == Phase.DONE && ms - (it.start + ResultCheck.typical(it)) in 0..12 * MatchDay.HOUR }.maxByOrNull { it.start }?.let { m ->
                return View("Final · ${m.leagueName}", OlympiaCards.resultLine(m, s), "", nextLine)
            }
            if (next != null) return View("Next · ${next.leagueName}", next.title, whenText(next, now), "")
            return View("Olympia", "No matches ahead", "Fixtures refresh twice a day.", "")
        }

        fun whenText(m: Match, now: ZonedDateTime): String {
            val t = Instant.ofEpochMilli(m.start).atZone(now.zone)
            val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
            val day = when (days) { 0L -> "today"; 1L -> "tomorrow"; else -> Fmt.shortDate(t) }
            return "$day, ${Fmt.time(t)}"
        }

        fun update(c: Context) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c, LiveWidget::class.java))
            if (ids.isEmpty()) return
            val v = runCatching { pick(OlympiaStore.load(c), ZonedDateTime.now()) }.getOrElse { View("Olympia", "Scores unavailable", "", "") }
            manager.updateAppWidget(ids, views(c, v))
        }

        fun views(c: Context, v: View): RemoteViews {
            val open = PendingIntent.getActivity(
                c, 46, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "OLYMPIA"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return RemoteViews(c.packageName, R.layout.widget_live).apply {
                setTextViewText(R.id.live_kicker, v.kicker)
                setTextViewText(R.id.live_title, v.title)
                setTextViewText(R.id.live_line, v.line)
                setTextViewText(R.id.live_foot, v.foot)
                setOnClickPendingIntent(R.id.live_root, open)
            }
        }
    }
}
