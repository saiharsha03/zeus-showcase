package dev.phonerecall.qol

import dev.phonerecall.brain.QuietHours
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.moments.MomentCard
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import java.time.Instant
import java.time.ZoneId

/** A followed match that ended while he slept, drove or sat in a meeting. `score`: Olympia's result line. */
data class SpoilerResult(val id: String, val title: String, val score: String, val league: String, val end: Long, val revealed: Boolean = false)

/**
 * Spoiler-safe results: the score stays hidden behind "Show score" (and off Olympia's own result card) until he
 * taps, with Highlights as a YouTube search. Only for matches he couldn't have watched end.
 */
object Spoilers {
    /** Match ids whose score is hidden right now; Olympia's result card skips them. */
    @Volatile var hiddenIds: Set<String> = emptySet()

    fun hidden(id: String) = id in hiddenIds

    /** Ended in quiet hours, during a drive, or during a meeting. */
    fun unseen(end: Long, zone: ZoneId, drives: List<LongRange>, meetings: List<LongRange>): Boolean =
        QuietHours.quiet(Instant.ofEpochMilli(end).atZone(zone)) || drives.any { end in it } || meetings.any { end in it }

    fun highlights(title: String) = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode("$title highlights", "UTF-8")

    fun card(ctx: MomentContext): MomentCard? {
        val r = ctx.qol.spoiler ?: return null
        if (ctx.qol.busy) return null
        val k = MomentKind.SPOILER_SAFE
        val key = r.id
        val actions = listOfNotNull(
            if (!r.revealed) CardAction("Show score", ActionRole.MOMENT_GO, QolRules.payload(k, key, "show")) else null,
            CardAction("Highlights", ActionRole.MOMENT_GO, QolRules.payload(k, key, "hl")),
            CardAction("Got it", ActionRole.MOMENT_GO, QolRules.payload(k, key, "done")),
        )
        return MomentCard(
            k, key,
            CardContent(title = if (r.revealed) r.score else "Result ready: ${r.title}", body = r.league, actions = actions),
            fit = 14f, plans = emptyMap(), opens = mapOf("hl" to highlights(r.title)), keep = setOf("show", "hl"),
        )
    }
}
