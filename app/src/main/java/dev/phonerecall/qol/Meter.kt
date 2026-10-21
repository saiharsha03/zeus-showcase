package dev.phonerecall.qol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio

/**
 * The parking meter from the parked-car card: "1h meter" or "2h meter" starts a Now Bar countdown and one nudge
 * 10 minutes before it runs out (with Navigate to car). On the phone only, so it runs on his tap; Stop undoes it.
 */
object Meter {
    private const val ID = 61_550
    const val NUDGE_BEFORE_MS = 10 * 60_000L

    /** When the nudge goes: 10 minutes before, or at once for a meter shorter than that. */
    fun nudgeAt(start: Long, minutes: Int): Long = maxOf(start, start + minutes * 60_000L - NUDGE_BEFORE_MS)

    fun start(c: Context, minutes: Int) {
        val now = System.currentTimeMillis()
        val until = now + minutes * 60_000L
        QolCore.update(c) { it.copy(meterUntil = until) }
        countdown(c, until)
        val am = c.getSystemService(AlarmManager::class.java)
        runCatching { if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nudgeAt(now, minutes), pending(c)) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nudgeAt(now, minutes), pending(c)) }
        Clio.info(QolCore.AREA, "meter started", "$minutes min")
    }

    fun stop(c: Context) {
        QolCore.update(c) { it.copy(meterUntil = 0) }
        c.getSystemService(AlarmManager::class.java)?.cancel(pending(c))
        c.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID)
        Clio.info(QolCore.AREA, "meter stopped")
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(c, ID, Intent(c, QolReceiver::class.java).setAction(QolReceiver.METER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun stopIntent(c: Context) = PendingIntent.getBroadcast(c, ID + 1, Intent(c, QolReceiver::class.java).setAction(QolReceiver.METER_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun walk(c: Context): PendingIntent? = QolCore.load(c).car?.let { car ->
        Opens.intent(c, QolRules.walkTo(car.lat, car.lng))?.let { PendingIntent.getActivity(c, ID + 2, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
    }

    /** The Now Bar countdown (the chip shows the live time left). */
    private fun countdown(c: Context, until: Long) {
        val nm = QolCore.channel(c) ?: return
        val b = NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Parking meter").setContentText("Runs out at ${QolRules.clock(until, java.time.ZonedDateTime.now())}")
            .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(until - System.currentTimeMillis() + 60_000L)
            .addAction(0, "Stop", stopIntent(c))
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        walk(c)?.let { b.addAction(0, "Navigate to car", it) }
        nm.notify(ID, b.build())
    }

    /** The alarm: one nudge, unless the meter was stopped or he already drove off. */
    fun nudge(c: Context) {
        val until = QolCore.load(c).meterUntil
        if (until <= System.currentTimeMillis()) return
        val nm = QolCore.alertChannel(c) ?: return
        val b = NotificationCompat.Builder(c, "qol.alert").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Meter runs out at ${QolRules.clock(until, java.time.ZonedDateTime.now())}").setContentText("About 10 minutes left")
            .setWhen(until).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
            .setOngoing(true).setOnlyAlertOnce(false).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(until - System.currentTimeMillis() + 60_000L)
            .addAction(0, "Stop", stopIntent(c))
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        walk(c)?.let { b.addAction(0, "Navigate to car", it) }
        nm.notify(ID, b.build())
        Clio.info(QolCore.AREA, "meter nudge")
    }
}
