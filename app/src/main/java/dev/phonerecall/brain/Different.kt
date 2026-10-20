package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the alarm app knows: tomorrow's first alarm and the learned usual for that weekday, as minutes after midnight. */
interface AlarmSignal {
    fun tomorrowMinutes(): Int? = null
    fun usualMinutes(): Int? = null
}

object NoAlarmSignal : AlarmSignal

/**
 * The "what's different" digest: words only, no coordinates, amounts or health. Each line says what
 * differs from his usual and nothing more; Athena decides whether to ask why. Empty when nothing differs.
 */
object Different {
    /** Set by the app at start; the alarms branch swaps in the real one. */
    @Volatile var alarm: AlarmSignal = NoAlarmSignal

    /** Builds the digest on the phone (set by BrainCore); null in tests and before start. */
    @Volatile var provider: ((ZonedDateTime) -> List<String>)? = null

    class Inputs(
        val now: ZonedDateTime,
        val clocks: TwoClocks,
        val alarm: AlarmSignal = NoAlarmSignal,
        /** Relevant events from now to about two weeks out. */
        val events: List<CalItem> = emptyList(),
        val weatherNext: String? = null,
        val weatherAlerts: List<String> = emptyList(),
        /** Odyssey's derived facts (its facts() only). */
        val odyssey: List<String> = emptyList(),
    )

    private val HM = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val AWAY = Regex("(?i)away from|unusual|not your usual|outside your usual")

    private fun hm(minutes: Int) = HM.format(java.time.LocalTime.of(minutes / 60, minutes % 60))

    fun lines(i: Inputs): List<String> {
        val zone = i.now.zone
        val tomorrow = i.now.toLocalDate().plusDays(1)
        val out = ArrayList<String>()

        val a = i.alarm.tomorrowMinutes()
        val usual = i.alarm.usualMinutes()
        if (a != null && usual != null && kotlin.math.abs(a - usual) >= 60) {
            out += "Tomorrow's first alarm is ${hm(a)}, his usual on a ${DAY.format(tomorrow)} is ${hm(usual)}."
        }

        fun day(e: CalItem) = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
        val soon = i.events.filter { day(it) in i.now.toLocalDate()..tomorrow }
        for (e in soon.filter { it.allDay }) {
            val start = i.clocks.dayStart(e) ?: continue
            val when_ = if (day(e) == tomorrow) "tomorrow" else "today"
            out += "${e.title.take(60)} is $when_ in India (it $start)."
        }
        for (e in soon.filter { it.category == EventCategory.TRAVEL }) {
            out += "Travel ${if (day(e) == tomorrow) "tomorrow" else "today"}: ${e.title.take(60)}."
        }

        val timed = i.events.filter { !it.allDay && it.category != EventCategory.OOO && it.category != EventCategory.TRAVEL }
        val perDay = timed.groupBy { day(it) }.mapValues { it.value.size }
        val tomorrowCount = perDay[tomorrow] ?: 0
        val others = (0L..13L).map { i.now.toLocalDate().plusDays(it) }.filter { it != tomorrow }.map { perDay[it] ?: 0 }
        val avg = if (others.isEmpty()) 0.0 else others.average()
        if (tomorrowCount >= 4 && tomorrowCount >= avg + 3) out += "Tomorrow has $tomorrowCount events, a busier day than most (about ${Math.round(avg)} on a usual day)."

        i.weatherNext?.takeIf { it.isNotBlank() }?.let { out += "Weather: $it." }
        if (i.weatherAlerts.isNotEmpty()) out += "Weather alert: ${i.weatherAlerts.joinToString(", ")}."

        out += i.odyssey.filter { AWAY.containsMatchIn(it) }.take(2)
        return out
    }

    /** The digest as one short block, or null when nothing differs. */
    fun block(lines: List<String>): String? = lines.takeIf { it.isNotEmpty() }?.joinToString(" ")?.let { "Different from usual: $it" }

    fun current(now: ZonedDateTime): List<String> = runCatching { provider?.invoke(now) }.getOrNull().orEmpty()
}
