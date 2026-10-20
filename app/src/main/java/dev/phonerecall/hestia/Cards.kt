package dev.phonerecall.hestia

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.ui.Tone
import java.time.ZonedDateTime

/** Hestia's Olympus card and how well it fits now. */
data class HearthCard(val content: CardContent, val fit: Float, val updatedAt: Long)

/** Pure: the snapshot, Odyssey's away (null when unknown) and the time in, one card or nothing out. Read only. */
object HestiaCards {
    private val OPEN = CardAction("Open Hestia", ActionRole.OPEN_HESTIA)

    fun card(s: HomeSnapshot, away: Boolean?, now: ZonedDateTime): HearthCard? {
        if (s.link != Link.Connected) return null
        val on = s.lightsOn
        if (on.isEmpty()) return null
        val late = now.hour >= 23 || now.hour < 5
        if (away != true && !late) return null
        val n = on.size
        val lights = if (n == 1) "1 light" else "$n lights"
        val facts = on.groupBy { s.room(it.roomId)?.name ?: "No room" }.entries.take(3).map { (room, ds) -> Fact(room, ds.joinToString(", ") { it.name }) }
        return if (away == true) HearthCard(
            CardContent(title = "$lights on, you're away", facts = facts, status = "Away", tone = Tone.ACTIVE, actions = listOf(OPEN)),
            fit = 20f, updatedAt = s.updatedAt,
        ) else HearthCard(
            CardContent(title = "$lights still on", facts = facts, actions = listOf(OPEN)),
            fit = 6f, updatedAt = s.updatedAt,
        )
    }
}
