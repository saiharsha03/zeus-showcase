package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.FESTIVAL_WORDS
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Two clocks: where he is (the phone's zone) and the second one (India by default, a Themis setting).
 * Everything goes through java.time zone rules, so daylight saving moves by itself; no offset is
 * ever written down here.
 */
class TwoClocks(val home: ZoneId, val second: ZoneId = secondZone) {
    /** Someone's local time at [at], with whether it is a bad hour to reach them and the next good one. */
    data class Reach(val zone: ZoneId, val local: String, val late: Boolean, val next: ZonedDateTime?, val nextText: String?)

    private fun clock(at: Instant, zone: ZoneId) = "${DAY_TIME.format(at.atZone(zone))} in ${label(zone)} (${ABBR.format(at.atZone(zone))})"

    /** "Now: Mon 2:30 PM in Buffalo (EDT) · Tue 12:00 AM in India (IST)" */
    fun contextLine(now: Instant): String = "Now: ${clock(now, home)} · ${clock(now, second)}" +
        (trip?.takeIf { it.first != home && it.first != second }?.let { (z, place) -> " · ${clock(now, z)}, trip${place?.let { " to $it" }.orEmpty()}" }.orEmpty())

    /** The time in the other zone only, like "Tue 12:00 AM IST". */
    fun inZone(at: Instant, zone: ZoneId): String = "${DAY_TIME.format(at.atZone(zone))} ${ABBR.format(at.atZone(zone))}"

    /** An event time shown in both zones, or null when the zones agree. */
    fun both(at: Instant, other: ZoneId = second): String? =
        if (home.rules.getOffset(at) == other.rules.getOffset(at)) null else "${inZone(at, home)} · ${inZone(at, other)}"

    /**
     * What a call or message at [at] means for someone living in [theirs]: their clock, and if it falls
     * between 22:00 and 07:00 there, the next 08:00 for them (also in his zone).
     */
    fun reach(at: Instant, theirs: ZoneId): Reach {
        val t = at.atZone(theirs)
        val late = t.hour >= LATE_FROM || t.hour < EARLY_TO
        val next = if (!late) null else {
            val day = if (t.hour >= LATE_FROM) t.toLocalDate().plusDays(1) else t.toLocalDate()
            day.atTime(NEXT_GOOD, 0).atZone(theirs)
        }
        val text = next?.let { "${TIME.format(it)} there, ${DAY_TIME.format(it.withZoneSameInstant(home))} for you" }
        return Reach(theirs, "${DAY_TIME.format(t)} ${ABBR.format(t)}", late, next, text)
    }

    /** When [date] starts in [zone], in his own zone: "starts 2:30 PM your time" (with the weekday if it is another day). */
    fun startsAt(date: LocalDate, zone: ZoneId): String {
        val s = date.atStartOfDay(zone).withZoneSameInstant(home)
        val day = if (s.toLocalDate() == date) "" else "${WEEKDAY.format(s)} "
        return "starts $day${TIME.format(s)} your time"
    }

    private val festival = Regex("(?i)\\b(${FESTIVAL_WORDS})\\b")

    /** Is this event on India's clock: its own zone, an IST place or title, or an Indian festival? */
    fun india(e: CalItem): Boolean = e.timeZone == INDIA.id || e.place?.let { INDIA_WORDS.containsMatchIn(it) } == true || INDIA_WORDS.containsMatchIn(e.title) ||
        (e.allDay && festival.containsMatchIn(e.title))

    /** An all-day birthday or festival dated in IST: when that day starts for him ("starts 2:30 PM your time"). */
    fun dayStart(e: CalItem): String? {
        if (!e.allDay || !india(e)) return null
        return startsAt(Instant.ofEpochMilli(e.begin).atZone(home).toLocalDate(), INDIA)
    }

    /** A timed India event in both clocks, or null. */
    fun bothFor(e: CalItem): String? =
        if (e.allDay || !india(e)) null else both(Instant.ofEpochMilli(e.begin), INDIA)

    fun label(zone: ZoneId): String = LABELS[zone.id] ?: city(zone)

    private fun city(zone: ZoneId) = zone.id.substringAfterLast('/').replace('_', ' ')

    companion object {
        private val INDIA_WORDS = Regex("\\b(IST|Pune)\\b")
        val INDIA: ZoneId = ZoneId.of("Asia/Kolkata")

        /** The second clock. Set from the Themis choice at start; India until he changes it. */
        @Volatile var secondZone: ZoneId = INDIA

        /** Argo: the destination's zone (and place) during a trip, when it differs from home. */
        @Volatile var trip: Pair<ZoneId, String?>? = null

        const val LATE_FROM = 22
        const val EARLY_TO = 7
        const val NEXT_GOOD = 8

        private val LABELS = mapOf(
            "America/New_York" to "Buffalo", "Asia/Kolkata" to "India", "Europe/London" to "London",
            "Asia/Dubai" to "Dubai", "Asia/Singapore" to "Singapore", "Australia/Sydney" to "Sydney",
        )
        /** The choices in Themis: stored zone id, shown name. */
        val CHOICES = listOf("Asia/Kolkata" to "India", "Europe/London" to "London", "Asia/Dubai" to "Dubai", "Asia/Singapore" to "Singapore", "Australia/Sydney" to "Sydney")

        private val DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
        private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US)
        private val ABBR = DateTimeFormatter.ofPattern("zzz", Locale.US)

        fun now(home: ZoneId = ZoneId.systemDefault()) = TwoClocks(home, secondZone)

        fun zoneOrNull(id: String?): ZoneId? = id?.let { runCatching { ZoneId.of(it) }.getOrNull() }

        /** +91 numbers and India WhatsApp ids (919876543210@s.whatsapp.net) live on India's clock. */
        fun isIndia(raw: String?): Boolean {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return false
            val id = s.substringBefore('@', "").takeIf { s.contains("@s.whatsapp.net") || s.contains("@c.us") }
            if (id != null) return id.filter { it.isDigit() }.let { it.startsWith("91") && it.length == 12 }
            val plus = s.replace(Regex("[\\s().-]"), "")
            return plus.startsWith("+91") && plus.drop(1).all { it.isDigit() } && plus.length == 13 || plus.startsWith("0091") && plus.length == 14
        }
    }
}
