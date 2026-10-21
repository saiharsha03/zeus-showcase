package dev.phonerecall.qol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import dev.phonerecall.moments.MomentsPrefs
import java.time.DayOfWeek
import java.time.ZonedDateTime

/**
 * Bins out: the evening before trash pickup (Kairos's trash day, set in Themis or learned from its ask), at his
 * time (7 PM unless he changes it), a Now Bar chip "Bins out" with Done. Kairos's card says the same on Home;
 * Done on either clears both.
 */
object Bins {
    private const val ID = 61_560

    /** The next evening the chip goes up, from [now] on. */
    fun next(p: MomentsPrefs, now: ZonedDateTime): ZonedDateTime? {
        val day = p.trashDay ?: return null
        return (0..7L).map { now.toLocalDate().plusDays(it) }.filter { it.plusDays(1).dayOfWeek == day }
            .map { it.atTime(p.trashMinute / 60, p.trashMinute % 60).atZone(now.zone) }
            .firstOrNull { it.isAfter(now) }
    }

    /** Kairos's key for tonight's trash card, so Done on the chip is Done on the card. */
    fun key(now: ZonedDateTime) = "${now.toLocalDate()}"

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, ID, Intent(c, QolReceiver::class.java).setAction(QolReceiver.BINS), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Pickup defaults to Thursday: set once when nothing was set, then Themis (or Kairos's ask) changes it. */
    val PRESET: DayOfWeek = DayOfWeek.THURSDAY

    fun preset(p: MomentsPrefs): MomentsPrefs = if (p.trashDay == null && !p.trashPreset) p.copy(trashDay = PRESET, trashPreset = true) else p

    fun arm(c: Context) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        if (MomentsCore.load(c).let { preset(it) != it }) { MomentsCore.update(c, ::preset); Clio.info(QolCore.AREA, "bins day preset", PRESET.name) }
        val p = MomentsCore.load(c)
        val at = next(p, ZonedDateTime.now())?.takeIf { MomentKind.TRASH !in p.never }
        if (at == null) { am.cancel(pending(c)); return }
        val t = at.toInstant().toEpochMilli()
        runCatching { if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(c)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pending(c)) }
    }

    /** Themis: the pickup day (null forgets it) and the reminder time, minutes after midnight the evening before. */
    fun set(c: Context, day: DayOfWeek?, minute: Int) {
        MomentsCore.update(c) { it.copy(trashDay = day, trashMinute = minute) }
        Clio.info(QolCore.AREA, "bins day set", "${day ?: "none"} ${minute / 60}:${"%02d".format(minute % 60)}")
        arm(c)
    }

    fun fire(c: Context) {
        val now = ZonedDateTime.now()
        val p = MomentsCore.load(c)
        if (p.trashDay == now.toLocalDate().plusDays(1).dayOfWeek && MomentKind.TRASH !in p.never && p.handled[MomentKind.TRASH] != key(now) && !Quiet.onCall(c)) {
            chip(c)
            Clio.info(QolCore.AREA, "bins out chip")
        }
        arm(c)
    }

    fun done(c: Context) {
        val now = ZonedDateTime.now()
        MomentsCore.update(c) { it.took(MomentKind.TRASH, key(now)) }
        c.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID)
        Clio.info(QolCore.AREA, "bins out done")
        dev.phonerecall.brain.BrainCore.get(c).changed()
    }

    private fun chip(c: Context) {
        val nm = QolCore.channel(c) ?: return
        val done = PendingIntent.getBroadcast(c, ID + 1, Intent(c, QolReceiver::class.java).setAction(QolReceiver.BINS_DONE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Bins out").setContentText("Pickup is tomorrow morning")
            .addAction(0, "Done", done).setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setTimeoutAfter(6 * 3_600_000L)
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", "Bins out") })
            .build())
    }
}
