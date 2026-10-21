package dev.phonerecall.qol

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.CallSlot
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The India clock before a call home. A call to family in India from a Zeus card late at night there asks first:
 * "11:40 PM IST, probably asleep" with Call anyway or Remind 9 AM IST. And when a meeting sits on his usual call-home
 * time, a card says so before it.
 */
object IndiaClock {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private const val ID = 61_570

    /** 10:30 PM to 6:30 AM in India. */
    fun late(now: ZonedDateTime): Boolean = now.withZoneSameInstant(IST).toLocalTime().let { it >= LocalTime.of(22, 30) || it < LocalTime.of(6, 30) }

    fun words(now: ZonedDateTime) = "${TIME.format(now.withZoneSameInstant(IST))} IST, probably asleep"

    /** The next 9:00 AM in India, as an instant. */
    fun nineIst(now: ZonedDateTime): ZonedDateTime {
        val ist = now.withZoneSameInstant(IST)
        val nine = ist.toLocalDate().atTime(9, 0).atZone(IST)
        return if (nine.isAfter(ist)) nine else nine.plusDays(1)
    }

    /** A timed event over his usual call-home time today (Kairos's call slot), in the 3 hours before it. */
    fun clash(slot: CallSlot?, events: List<CalEvent>, now: ZonedDateTime): CalEvent? {
        if (slot == null || now.dayOfWeek != slot.day) return null
        val at = now.toLocalDate().atTime(slot.minute / 60, slot.minute % 60).atZone(now.zone)
        val ms = now.toInstant().toEpochMilli()
        val t = at.toInstant().toEpochMilli()
        if (t - ms !in 0..3 * 3_600_000L) return null
        return events.firstOrNull { it.begin <= t + 15 * 60_000L && it.end > t }
    }

    fun clashCard(ctx: MomentContext): MomentCard? {
        val e = ctx.qol.clash ?: return null
        val s = ctx.callSlot ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.CALL_CLASH
        val key = "${ctx.now.toLocalDate()}:${e.begin}"
        val usual = TIME.format(LocalTime.of(s.minute / 60, s.minute % 60))
        return MomentCard(
            k, key,
            CardContent(
                title = "${e.title} is on your call-home time",
                body = "You usually call around $usual. It's ${TIME.format(ctx.now.withZoneSameInstant(IST))} in India now.",
                actions = listOf(
                    CardAction("WhatsApp now", ActionRole.MOMENT_GO, QolRules.payload(k, key, "open")),
                    CardAction("Got it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")),
                    QolRules.never(k, key),
                ),
            ),
            fit = 18f, plans = emptyMap(), opens = mapOf("open" to "app:com.whatsapp"),
        )
    }

    // ---- the phone: Horae's call buttons ask first when it's late there ----

    /** True when [key]'s habit is someone in India and it is late there: the ask went up instead of the call. */
    fun askFirst(c: Context, key: String, via: String): Boolean {
        if (MomentKind.CALL_CLASH in dev.phonerecall.moments.MomentsCore.load(c).never) return false
        val h = dev.phonerecall.rhythms.RhythmsStore.load(c).callHabits.firstOrNull { it.key == key } ?: return false
        val now = ZonedDateTime.now()
        if (h.zone != IST.id || !late(now)) return false
        val nm = QolCore.alertChannel(c) ?: return false
        val anyway = PendingIntent.getActivity(c, ID, Intent(c, dev.phonerecall.rhythms.CallActionActivity::class.java).putExtra("key", key).putExtra("via", via).putExtra("force", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val remind = PendingIntent.getBroadcast(c, ID + 1, Intent(c, QolReceiver::class.java).setAction(QolReceiver.INDIA_REMIND).putExtra("key", key).putExtra("via", via), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, NotificationCompat.Builder(c, "qol.alert").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(words(now)).setContentText("Call anyway, or a reminder at 9 AM their time?")
            .addAction(0, "Call anyway", anyway).addAction(0, "Remind 9 AM IST", remind).setAutoCancel(true).setTimeoutAfter(30 * 60_000L).build())
        Clio.info(QolCore.AREA, "late call to India held", words(now).substringBefore(" IST"))
        return true
    }

    /** "Remind 9 AM IST": one alarm, then a notification whose button places the call. */
    fun remind(c: Context, key: String, via: String) {
        c.getSystemService(android.app.NotificationManager::class.java)?.cancel(ID)
        val at = nineIst(ZonedDateTime.now()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(c, ID + 2, Intent(c, QolReceiver::class.java).setAction(QolReceiver.INDIA_DUE).putExtra("key", key).putExtra("via", via), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val am = c.getSystemService(AlarmManager::class.java)
        runCatching { if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
        Clio.info(QolCore.AREA, "call home reminder set", "9 AM IST")
    }

    fun due(c: Context, key: String, via: String) {
        val nm = QolCore.alertChannel(c) ?: return
        val call = PendingIntent.getActivity(c, ID + 3, Intent(c, dev.phonerecall.rhythms.CallActionActivity::class.java).putExtra("key", key).putExtra("via", via).putExtra("force", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(ID, NotificationCompat.Builder(c, "qol.alert").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Call home?").setContentText("It's 9 AM in India").addAction(0, "Call", call).setAutoCancel(true).build())
        Clio.info(QolCore.AREA, "call home reminder")
    }
}
