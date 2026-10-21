package dev.phonerecall.qol

import android.content.Context
import dev.phonerecall.clio.Clio
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.moments.MomentKind
import java.time.ZonedDateTime

/**
 * "Later" on a message card: the card goes away and comes back, with its reply chips, after the meeting he is in,
 * when he gets home, or at 6 PM (two hours from now in the evening). One chip, the fitting choice named on it.
 */
object Later {
    enum class When(val label: String) { MEETING("After the meeting"), HOME("When home"), SIX("Later, 6 PM"), TWO("In 2 hours") }

    fun pick(inMeetingUntil: Long?, atHome: Boolean?, now: ZonedDateTime): When = when {
        inMeetingUntil != null -> When.MEETING
        atHome == false -> When.HOME
        now.hour < 18 -> When.SIX
        else -> When.TWO
    }

    /** When the card is back; HOME is held up to 12 hours or until he arrives home. */
    fun until(w: When, inMeetingUntil: Long?, now: ZonedDateTime): Long = when (w) {
        When.MEETING -> (inMeetingUntil ?: now.toInstant().toEpochMilli()) + 2 * 60_000L
        When.HOME -> now.plusHours(12).toInstant().toEpochMilli()
        When.SIX -> now.toLocalDate().atTime(18, 0).atZone(now.zone).toInstant().toEpochMilli()
        When.TWO -> now.plusHours(2).toInstant().toEpochMilli()
    }

    /** The chip for a message card's instance key. */
    fun chip(instance: String, w: When) = CardAction(w.label, ActionRole.MOMENT_GO, QolRules.payload(MomentKind.MESSAGE_LATER, instance, w.name))

    /** What fits right now, from the calendar and Odyssey. */
    fun now(c: Context): Pair<When, Long?> {
        val now = ZonedDateTime.now()
        val ms = now.toInstant().toEpochMilli()
        val meeting = QolCore.events(c, ms).filter { it.begin <= ms && it.end > ms }.maxOfOrNull { it.end }
        val home = dev.phonerecall.moments.Signals.atHome(dev.phonerecall.odyssey.Odyssey.state(c))
        return pick(meeting, home, now) to meeting
    }

    /** His tap: hide the card until then (Home's dismissals), and for "when home", keep it for the arrival. */
    fun snooze(c: Context, instance: String, option: String) {
        val w = runCatching { When.valueOf(option) }.getOrNull() ?: return
        val now = ZonedDateTime.now()
        val meeting = if (w == When.MEETING) now(c).second else null
        dev.phonerecall.data.Dismissals.add(c, instance, until(w, meeting, now), now.toInstant().toEpochMilli())
        if (w == When.HOME) QolCore.update(c) { it.copy(laterHome = (it.laterHome + instance).takeLast(20)) }
        Clio.info(QolCore.AREA, "message later", w.label)
    }

    /** Home again: every "when home" card comes back. */
    fun arrivedHome(c: Context) {
        val list = QolCore.load(c).laterHome
        if (list.isEmpty()) return
        for (i in list) dev.phonerecall.data.Dismissals.remove(c, i)
        QolCore.update(c) { it.copy(laterHome = emptyList()) }
        Clio.info(QolCore.AREA, "messages back", "${list.size} at home")
    }
}
