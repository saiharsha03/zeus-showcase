package dev.phonerecall.argo

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
import java.time.ZonedDateTime

/**
 * Travel mode's one ongoing notification, updated in place through the legs, asking to be promoted to the
 * Now Bar and the lock screen (Android 16 Live Update), the way Olympia's LiveNotice does. Silent except
 * at a key moment (time to go, a platform or gate announced), and never a sound in quiet hours. It names
 * the leg and the times only: no confirmation, no address.
 */
object ArgoNotice {
    const val ID = 63_000
    private const val CHANNEL = "argo_live"
    private const val EXTRA_PROMOTE = "android.requestPromotedOngoing"
    private const val EXTRA_CHIP = "android.shortCriticalText"

    fun canNotify(c: Context) = c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Travel day", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One notification on a travel day, updated in place. Sounds only when it's time to go."
                setShowBadge(false)
            },
        )
    }

    private fun open(c: Context) = PendingIntent.getActivity(
        c, ID, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, ArgoCards.DEST)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun build(c: Context, t: LiveText, sound: Boolean): Notification {
        channel(c)
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(t.title)
            .setContentText(t.text)
            .setContentIntent(open(c))
            .setOngoing(true)
            .setOnlyAlertOnce(!sound)
            .setSilent(!sound)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addExtras(Bundle().apply {
                putBoolean(EXTRA_PROMOTE, true)
                if (t.countdownTo == null) putString(EXTRA_CHIP, t.chip.take(12))
            })
        t.progress?.let { (done, of) -> if (of > 0) b.setProgress(of, done.coerceIn(0, of), false) }
        if (t.countdownTo != null) b.setWhen(t.countdownTo).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) else b.setShowWhen(false)
        return b.build()
    }

    /** Posts or updates. `lastKey` is the stage already shown: only a new key may make a sound. */
    fun show(c: Context, t: LiveText, lastKey: String?) {
        if (!canNotify(c)) return
        val sound = t.sounds && t.key != lastKey && !dev.phonerecall.brain.QuietHours.quiet(ZonedDateTime.now())
        c.getSystemService(NotificationManager::class.java).notify(ID, build(c, t, sound))
    }

    fun clear(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(ID)
}
