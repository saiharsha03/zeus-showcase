package dev.phonerecall.qol

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.phonerecall.R
import dev.phonerecall.clio.Clio
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A ride arriving or a delivery on the way, read from the app's own notification. */
data class LiveTrip(val app: String, val pkg: String, val ride: Boolean, val etaAt: Long?, val car: String? = null, val plate: String? = null, val pin: String? = null)

/**
 * Delivery and ride on the cover screen: Uber, Lyft, Uber Eats, DoorDash and Instacart's "on the way" and
 * "arriving" become one Live Update chip with the ETA countdown, the car and plate and PIN when the app gave
 * them, and a tap that opens the app. It times out a few minutes after the ETA.
 */
object LiveTrips {
    private const val ID = 61_590
    private val APPS = mapOf(
        "com.ubercab" to ("Uber" to true), "me.lyft.android" to ("Lyft" to true),
        "com.ubercab.eats" to ("Uber Eats" to false), "com.dd.doordash" to ("DoorDash" to false), "com.instacart.client" to ("Instacart" to false),
    )
    private val MOVING = Regex("""(?i)\b(arriving|on (the|its|their) way|en route|heading (to|your way)|min(ute)?s? away|almost there|nearby|picking up your order|out for delivery|is here|has arrived)\b""")
    private val MIN = Regex("""(?i)\b(\d{1,2})\s*(?:min|mins|minutes)\b""")
    private val CLOCK = Regex("""(?i)\b(?:by|eta|arriv\w*( at)?)\s+(\d{1,2}):(\d{2})\s*([ap])\.?m\b""")
    private val PIN = Regex("""(?i)\bpin\s*(?:is|:)?\s*(\d{4})\b""")
    private val PLATE = Regex("""\b(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,8}\b""")
    private val COLORS = Regex("""(?i)\b(black|white|gray|grey|silver|blue|red|green|gold|brown|beige|maroon|orange|yellow)\b[^\n·•,]{0,30}""")

    fun parse(pkg: String, title: String, text: String, at: Long, zone: ZoneId): LiveTrip? {
        val (app, ride) = APPS[pkg] ?: return null
        val all = "$title\n$text"
        if (!MOVING.containsMatchIn(all)) return null
        val eta = MIN.find(all)?.groupValues?.get(1)?.toLongOrNull()?.let { at + it * 60_000L }
            ?: CLOCK.find(all)?.let { m ->
                val h = m.groupValues[2].toInt() % 12 + if (m.groupValues[4].lowercase() == "p") 12 else 0
                val now = java.time.Instant.ofEpochMilli(at).atZone(zone)
                var t = now.toLocalDate().atTime(LocalTime.of(h, m.groupValues[3].toInt())).atZone(zone)
                if (t.isBefore(now.minusHours(1))) t = t.plusDays(1)
                t.toInstant().toEpochMilli()
            }
        return LiveTrip(
            app, pkg, ride, eta,
            car = if (ride) COLORS.find(all)?.value?.trim()?.trimEnd('.', ' ') else null,
            plate = if (ride) PLATE.find(text)?.value else null,
            pin = PIN.find(all)?.groupValues?.get(1),
        )
    }

    fun title(t: LiveTrip, now: Long): String {
        val mins = t.etaAt?.let { ((it - now + 59_999) / 60_000L).coerceAtLeast(0) }
        val what = if (t.ride) "${t.app} arriving" else "${t.app} on the way"
        return if (mins == null) what else if (mins == 0L) "${t.app} is here" else "$what · $mins min"
    }

    fun details(t: LiveTrip): String = listOfNotNull(t.car, t.plate, t.pin?.let { "PIN $it" }).joinToString(" · ")

    /** Short enough for the status-bar chip: "Uber 4 min". */
    fun chip(t: LiveTrip, now: Long): String = (t.app.substringBefore(' ') + (t.etaAt?.let { " ${((it - now + 59_999) / 60_000L).coerceAtLeast(0)}m" } ?: "")).take(12)

    fun post(c: Context, n: dev.phonerecall.brain.Incoming) {
        if (MomentKind.LIVE_TRIP in MomentsCore.load(c).never) return
        val t = parse(n.pkg, n.title, n.text, n.at, ZonedDateTime.now().zone) ?: return
        val nm = QolCore.channel(c) ?: return
        val now = System.currentTimeMillis()
        val open = c.packageManager.getLaunchIntentForPackage(t.pkg)?.let { PendingIntent.getActivity(c, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
        val b = NotificationCompat.Builder(c, "qol").setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(title(t, now)).setContentText(details(t).ifEmpty { if (t.ride) "Track the ride" else "Track the order" })
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setCategory(if (t.ride) NotificationCompat.CATEGORY_TRANSPORT else NotificationCompat.CATEGORY_PROGRESS)
            .setTimeoutAfter(((t.etaAt ?: (now + 30 * 60_000L)) - now + 10 * 60_000L).coerceAtLeast(5 * 60_000L))
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true); if (t.etaAt == null) putString("android.shortCriticalText", chip(t, now)) })
        t.etaAt?.let { b.setWhen(it).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
        open?.let { b.setContentIntent(it).addAction(0, if (t.ride) "Open ${t.app}" else "Track", it) }
        nm.notify(ID, b.build())
        Clio.info(QolCore.AREA, if (t.ride) "ride arriving" else "delivery on the way", t.app)
    }
}
