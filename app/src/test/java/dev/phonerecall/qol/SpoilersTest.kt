package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Spoiler-safe results: hidden until he taps. */
class SpoilersTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun onlyMatchesHeCouldNotHaveWatchedEnd() {
        assertTrue(Spoilers.unseen(ms(at(1, 30)), zone, emptyList(), emptyList()))
        assertTrue(Spoilers.unseen(ms(at(15)), zone, listOf(ms(at(14, 40))..ms(at(15, 20))), emptyList()))
        assertTrue(Spoilers.unseen(ms(at(15)), zone, emptyList(), listOf(ms(at(14))..ms(at(15, 30)))))
        assertFalse(Spoilers.unseen(ms(at(15)), zone, emptyList(), emptyList()))
    }

    @Test fun scoreHiddenUntilShowScore() {
        val r = SpoilerResult("m1", "Home FC vs Away FC", "Home FC 2-1 Away FC", "Sample League", ms(at(1, 30)))
        val c = MomentRules.pick(MomentContext(at(8), qol = QolSignals(spoiler = r)), MomentsPrefs())!!
        assertEquals(MomentKind.SPOILER_SAFE, c.kind)
        assertEquals("Result ready: Home FC vs Away FC", c.content.title)
        assertFalse(c.content.toString().contains("2-1"))
        assertEquals(listOf("Show score", "Highlights", "Got it"), c.content.actions.map { it.label })
        assertEquals("https://www.youtube.com/results?search_query=Home+FC+vs+Away+FC+highlights", c.opens["hl"])
        val shown = Spoilers.card(MomentContext(at(8), qol = QolSignals(spoiler = r.copy(revealed = true))))!!
        assertEquals("Home FC 2-1 Away FC", shown.content.title)
        assertEquals(listOf("Highlights", "Got it"), shown.content.actions.map { it.label })
    }
}
