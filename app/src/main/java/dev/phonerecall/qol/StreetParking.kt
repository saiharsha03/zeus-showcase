package dev.phonerecall.qol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.horologion.Horologion
import dev.phonerecall.horologion.Item
import dev.phonerecall.horologion.Owner
import dev.phonerecall.horologion.Tab
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** From this weekday and minute until the next window starts, the car belongs on [side]. */
data class SideWindow(val side: String = "", val day: Int = 1, val minute: Int = 0)

/** His street's alternate-side rule (Themis), kept sealed in QolState. `car`: what he calls it ("the car" if blank). */
data class StreetRule(val windows: List<SideWindow> = emptyList(), val turnAround: Boolean = false, val car: String = "")

/** A reminder that is up: the flip it is for, the side, and whether he is out (then "before you sleep"). */
data class StreetReminder(val flipAt: Long, val side: String, val out: Boolean, val words: String)

/**
 * Alternate-side street parking. About 45 minutes before the side flips, when the car's last park was at Home:
 * one Now Bar chip and a quiet card, "Move the car to the EVEN side · turn it around". Out without the car: the
 * same, "before you sleep", and it stays up into the night. Any drive or park after it clears it. No street names
 * or fines anywhere; the rule is his, typed in Themis or offered from Iris's in Agora.
 */
object StreetParking {
    const val LEAD_MS = 45 * 60_000L
    const val OUT_MS = 6 * 3_600_000L
    private val DAYS = mapOf("mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6, "sun" to 7)
    private val WINDOW = Regex("(?i)\\b(\\w+)\\s+side\\b[^\\n,;]*?\\b(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\.?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?m\\b")
    private val SHORT = Regex("(?i)\\b(\\w+)\\s+(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\.?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*([ap])\\.?m\\b")
    private val TURN = Regex("(?i)turn (it )?around|facing|direction of travel")
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * "east side Tue 8 AM–Fri 8 AM, west side Fri 8 AM–Tue 8 AM" (a colleague's sentence) or "east Tue 8 AM, west Fri 8 AM"
     * (his, in Themis): each side from its first time. Two sides at least, else null.
     */
    fun parse(text: String, car: String = ""): StreetRule? {
        val hits = WINDOW.findAll(text).toList().ifEmpty { SHORT.findAll(text).toList() }
        val windows = hits.mapNotNull { m ->
            val h = m.groupValues[3].toInt().takeIf { it in 1..12 } ?: return@mapNotNull null
            val min = m.groupValues[4].ifEmpty { "0" }.toInt()
            val hour = h % 12 + if (m.groupValues[5].lowercase() == "p") 12 else 0
            SideWindow(m.groupValues[1].lowercase(), DAYS.getValue(m.groupValues[2].lowercase().take(3)), hour * 60 + min)
        }.distinctBy { it.day * 1440 + it.minute }
        if (windows.map { it.side }.distinct().size < 2) return null
        return StreetRule(windows, TURN.containsMatchIn(text), car)
    }

    /** The rule back in the words [parse] reads, for the Themis field. */
    fun words(r: StreetRule): String = r.windows.joinToString(", ") { w ->
        "${w.side} ${DayOfWeek.of(w.day).getDisplayName(java.time.format.TextStyle.SHORT, Locale.US)} ${TIME.format(java.time.LocalTime.of(w.minute / 60, w.minute % 60))}"
    }

    private fun at(w: SideWindow, base: ZonedDateTime): ZonedDateTime =
        base.with(TemporalAdjusters.nextOrSame(DayOfWeek.of(w.day))).toLocalDate().atTime(w.minute / 60, w.minute % 60).atZone(base.zone)

    /** Every flip from a week back to a week ahead, in order. */
    fun flips(r: StreetRule, now: ZonedDateTime): List<Pair<ZonedDateTime, String>> =
        listOf(now.minusWeeks(1), now).flatMap { b -> r.windows.map { at(it, b) to it.side } }.plus(r.windows.map { at(it, now.plusWeeks(1)) to it.side })
            .distinctBy { it.first }.sortedBy { it.first }

    fun nextFlip(r: StreetRule, now: ZonedDateTime) = flips(r, now).firstOrNull { it.first.isAfter(now) }

    fun phrase(r: StreetRule, side: String, out: Boolean) =
        "Move ${r.car.ifBlank { "the car" }} to the ${side.uppercase()} side" + (if (out) " before you sleep" else "") + (if (r.turnAround) " · turn it around" else "")

    /**
     * Up from 45 minutes before a flip until it (until 6 hours after when he is out without the car), only when the
     * car was last parked at Home and it hasn't moved since the reminder began.
     */
    fun reminder(r: StreetRule?, now: ZonedDateTime, carHome: Boolean, out: Boolean, moved: Long): StreetReminder? {
        if (r == null || r.windows.size < 2 || !carHome) return null
        val ms = now.toInstant().toEpochMilli()
        val (f, side) = flips(r, now).firstOrNull { (t, _) -> val fm = t.toInstant().toEpochMilli(); ms in (fm - LEAD_MS) until (fm + if (out) OUT_MS else 0L) } ?: return null
        val fm = f.toInstant().toEpochMilli()
        if (moved >= fm - LEAD_MS) return null
        return StreetReminder(fm, side, out, phrase(r, side, out))
    }

    fun card(ctx: MomentContext): MomentCard? {
        val s = ctx.qol.street ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.STREET_PARKING
        val key = "${s.flipAt}"
        return MomentCard(
            k, key,
            CardContent(
                title = s.words, body = "The side flips at ${QolRules.clock(s.flipAt, ctx.now)}.",
                actions = listOf(CardAction("Done", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")), QolRules.never(k, key)),
            ),
            fit = 26f, plans = emptyMap(),
        )
    }

    /** Iris's rule, offered once: "Use Iris's parking rule for Home?" */
    fun offer(ctx: MomentContext): MomentCard? {
        val r = ctx.qol.streetOffer ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.STREET_SEED
        val key = words(r)
        return MomentCard(
            k, key,
            CardContent(
                title = "Use Iris's parking rule for Home?", body = key + if (r.turnAround) ", turn it around." else ".",
                actions = listOf(CardAction("Use it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "yes")), CardAction("No", ActionRole.MOMENT_GO, QolRules.payload(k, key, "no"))),
            ),
            fit = 8f, plans = emptyMap(),
        )
    }

    // ---- the phone ----

    private const val CHIP = 61_540

    /** An Agora post from Iris that states a parking rule: offered once, if he has none yet. */
    fun fromIris(c: Context, text: String) {
        if (!Regex("(?i)alternate.side|street parking|side of the street").containsMatchIn(text)) return
        val r = parse(text) ?: return
        val st = QolCore.load(c)
        if (st.street != null || st.streetOffer != null || st.streetOfferDone) return
        QolCore.update(c) { it.copy(streetOffer = r) }
        Clio.info(QolCore.AREA, "street parking rule offered", "from Iris")
        dev.phonerecall.brain.BrainCore.get(c).changed()
    }

    /** Themis: his rule (null clears it). */
    fun save(c: Context, r: StreetRule?) {
        QolCore.update(c) { it.copy(street = r, streetOffer = null, streetOfferDone = true) }
        Clio.info(QolCore.AREA, if (r == null) "street parking rule cleared" else "street parking rule saved", r?.let { "${it.windows.size} flips" }.orEmpty())
        arm(c)
    }

    fun tapped(c: Context, kind: MomentKind, option: String) {
        when (kind) {
            MomentKind.STREET_SEED -> if (option == "yes") save(c, QolCore.load(c).streetOffer) else QolCore.update(c) { it.copy(streetOffer = null, streetOfferDone = true) }
            MomentKind.STREET_PARKING -> done(c)
            else -> {}
        }
    }

    fun done(c: Context) {
        QolCore.update(c) { it.copy(carMoved = System.currentTimeMillis()) }
        c.getSystemService(android.app.NotificationManager::class.java)?.cancel(CHIP)
        Clio.info(QolCore.AREA, "street parking done")
    }

    /** The car moved (a drive, a park): any reminder is answered. */
    fun moved(c: Context) = c.getSystemService(android.app.NotificationManager::class.java)?.cancel(CHIP)

    fun now(c: Context, now: ZonedDateTime): StreetReminder? {
        if (MomentKind.STREET_PARKING in MomentsCore.load(c).never) return null
        val st = QolCore.load(c)
        val out = dev.phonerecall.moments.Signals.atHome(dev.phonerecall.odyssey.Odyssey.state(c)) == false
        return reminder(st.street, now, st.carHome, out, st.carMoved)
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, CHIP, Intent(c, QolReceiver::class.java).setAction(QolReceiver.STREET), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** One alarm, 45 minutes before the next flip. Boot, an update and saving the rule re-arm it. */
    fun arm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val r = QolCore.load(c).street
        val next = r?.let { nextFlip(it, ZonedDateTime.now().plusMinutes(45)) }
        if (next == null) { am.cancel(pending(c)); return }
        val at = next.first.toInstant().toEpochMilli() - LEAD_MS
        runCatching { if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c)) }
    }

    /** The alarm: one chip per flip, then the next alarm. */
    fun fire(c: Context) {
        val s = now(c, ZonedDateTime.now())
        if (s != null && QolCore.load(c).streetChip != s.flipAt && !Quiet.onCall(c)) {
            QolCore.update(c) { it.copy(streetChip = s.flipAt) }
            chip(c, s)
            Clio.info(QolCore.AREA, "street parking reminder", if (s.out) "out, before you sleep" else "at home")
            dev.phonerecall.brain.BrainCore.get(c).changed()
        }
        arm(c)
    }

    private fun chip(c: Context, s: StreetReminder) {
        val nm = QolCore.channel(c) ?: return
        val done = PendingIntent.getBroadcast(c, CHIP + 1, Intent(c, QolReceiver::class.java).setAction(QolReceiver.STREET_DONE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(CHIP, NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(s.words).setContentText("The side flips at ${QolRules.clock(s.flipAt, ZonedDateTime.now())}")
            .addAction(0, "Done", done).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(s.flipAt - System.currentTimeMillis() + if (s.out) OUT_MS else 15 * 60_000L)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", "Move ${s.side.uppercase()}".take(12)) })
            .build())
    }

    /** Horologion: "Street parking (Zeus)", the next reminder. */
    fun horologion(c: Context, zone: ZoneId): List<Item> {
        val r = QolCore.load(c).street ?: return emptyList()
        val next = nextFlip(r, ZonedDateTime.now(zone).plusMinutes(45))
        return listOf(Item(
            "qol-street", Owner.ATHENA, Tab.JOBS, "On the phone", "Street parking (Zeus)",
            "45 minutes before the side flips, when the car is parked at Home: one chip and a card (Themis, Kairos)", "This phone (an exact alarm)",
            listOfNotNull(words(r), next?.let { "next ${Horologion.at(it.first.toInstant().toEpochMilli() - LEAD_MS, zone)}" }).joinToString(" · "),
        ))
    }
}
