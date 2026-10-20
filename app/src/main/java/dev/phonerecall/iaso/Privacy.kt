package dev.phonerecall.iaso

import java.time.ZonedDateTime

/**
 * The only health text that can leave Iaso for the brain, and only with "Share with Athena" on (off by
 * default). It is derived and coarse: sleep in whole hours with a word, steps as a word. Never heart rate,
 * weight, calories, meals or workouts, never a raw record. Nothing here goes to Iris, Lemnos, a widget,
 * the Now Bar or the watch (IasoPrivacyTest checks every caller). Iaso's own widget reads the state
 * directly (widgets/WidgetContent.health), not this line.
 */
object IasoBrain {
    fun line(s: IasoState, now: ZonedDateTime): String? {
        if (!s.shareWithBrain) return null
        val today = now.toLocalDate()
        val parts = buildList {
            s.report.lastNight(today)?.let { n ->
                val h = Math.round(n.asleepMin / 60.0)
                add("slept ${h}h" + if (n.asleepMin < Goals.SHORT_SLEEP_MIN) ", short" else "")
            }
            s.report.day(today)?.steps?.let { st ->
                // By the hour: 3,000 steps by noon is fine, by 9 PM it's a quiet day.
                val expected = Goals.STEPS * ((now.hour - 7).coerceIn(1, 14) / 14.0)
                add(if (st < expected * 0.5) "quiet day on foot" else if (st >= Goals.STEPS) "steps goal met" else "steps on track")
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** What Tools.now passes on: the line for the running hub, or null when Iaso isn't loaded or sharing is off. */
    fun current(now: ZonedDateTime = ZonedDateTime.now()): String? = Iaso.peek()?.state?.value?.let { line(it, now) }
}
