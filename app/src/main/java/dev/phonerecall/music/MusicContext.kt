package dev.phonerecall.music

import dev.phonerecall.data.DayType
import dev.phonerecall.data.FESTIVAL_WORDS
import java.time.ZonedDateTime

/** What a context card offers. `key` is one per context per day, so a card is offered once. */
data class Suggestion(val key: String, val title: String, val query: String, val reason: String)

/**
 * Context music: festival mornings get devotional music, office mornings get focus, evenings
 * get something calm. Pure: it only picks. The pick becomes a play_music proposal he taps; nothing
 * here plays anything. Driving music is drive mode's own kind (music_while_driving), not a suggestion.
 */
object MusicContext {
    private val festival = Regex("\\b(${FESTIVAL_WORDS})\\b", RegexOption.IGNORE_CASE)

    /** The festival in these calendar titles, if any (Dasara, Diwali, Sankranti...). */
    fun festivalIn(titles: List<String>): String? = titles.firstOrNull { festival.containsMatchIn(it) }

    fun suggest(now: ZonedDateTime, festivalToday: String?, dayType: DayType?, offered: Set<String>, devotional: String = "Telugu devotional"): Suggestion? {
        val day = now.toLocalDate()
        val h = now.hour
        val pick = when {
            festivalToday != null && h in 5..12 ->
                Suggestion("festival-$day", "Devotional music for $festivalToday", devotional, "$festivalToday today")
            dayType == DayType.WORKDAY && h in 8..10 ->
                Suggestion("focus-$day", "Focus music for the office", "Deep Focus", "Office morning")
            dayType == DayType.REMOTE && h in 9..11 ->
                Suggestion("focus-$day", "Focus music while you work", "Deep Focus", "Remote day")
            h in 19..21 ->
                Suggestion("evening-$day", "Something calm for the evening", "Evening chill", "Evening")
            else -> null
        }
        return pick?.takeIf { it.key !in offered }
    }
}
