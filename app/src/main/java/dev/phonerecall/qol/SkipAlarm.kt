package dev.phonerecall.qol

import dev.phonerecall.data.DayType
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Tomorrow's alarm on a day off: when it rings, who set it ("Zeus", "Clock"), and why the day is off. */
data class OffAlarm(val at: Long, val app: String?, val why: String)

/**
 * "Skip tomorrow's alarm?" the evening before a holiday or a day out of office with nothing before noon. Zeus's own
 * alarm opens the clock's dismiss screen for that time; any other opens the alarm list. He turns it off there.
 */
object SkipAlarm {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun check(alarmAt: Long?, app: String?, tomorrow: DayType?, reason: String?, morningEvents: Int, now: ZonedDateTime): OffAlarm? {
        if (alarmAt == null || tomorrow == null || morningEvents > 0) return null
        if (tomorrow != DayType.HOLIDAY && tomorrow != DayType.OOO) return null
        val t = Instant.ofEpochMilli(alarmAt).atZone(now.zone)
        if (t.toLocalDate() != now.toLocalDate().plusDays(1) || t.hour >= 12) return null
        return OffAlarm(alarmAt, app, reason ?: tomorrow.label)
    }

    /** The clock's dismiss screen for this time, or its alarm list. */
    fun target(a: OffAlarm, zone: java.time.ZoneId): String {
        val t = Instant.ofEpochMilli(a.at).atZone(zone)
        return if (a.app == "Zeus") "${Opens.DISMISS}${t.hour}:${t.minute}" else Opens.ALARMS
    }

    fun card(ctx: MomentContext): MomentCard? {
        val a = ctx.qol.offAlarm ?: return null
        if (ctx.qol.busy || ctx.now.hour !in 19..22) return null
        val k = MomentKind.SKIP_ALARM
        val key = "${a.at}"
        val at = TIME.format(Instant.ofEpochMilli(a.at).atZone(ctx.now.zone))
        return MomentCard(
            k, key,
            CardContent(
                title = "Skip tomorrow's $at alarm?", body = "${a.why} and nothing before noon.",
                actions = listOf(
                    CardAction("Turn it off", ActionRole.MOMENT_GO, QolRules.payload(k, key, "off")),
                    CardAction("Keep it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "keep")),
                ),
            ),
            fit = 16f, plans = emptyMap(), opens = mapOf("off" to target(a, ctx.now.zone)),
        )
    }
}
