package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import dev.phonerecall.moneta.Cycle
import dev.phonerecall.moneta.SubSource
import dev.phonerecall.moneta.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Subscriptions renewing tomorrow: Keep or Manage. */
class RenewalTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 10, 0, 0, 0, ZoneId.of("America/New_York"))
    private fun sub(name: String, next: LocalDate?) = Subscription(name, 1599, Cycle.MONTHLY, next, SubSource.DETECTED)

    @Test fun onlyTomorrows() {
        val subs = listOf(sub("Netflix", LocalDate.of(2026, 10, 21)), sub("Gym", LocalDate.of(2026, 10, 25)), sub("Hulu", LocalDate.of(2026, 10, 21)), sub("Old", null))
        assertEquals(listOf("Hulu", "Netflix"), Renewals.tomorrow(subs, now.toLocalDate()))
    }

    @Test fun keepOrManage() {
        val c = MomentRules.pick(MomentContext(now, qol = QolSignals(renewals = listOf("Netflix"))), MomentsPrefs())!!
        assertEquals(MomentKind.RENEWAL, c.kind)
        assertEquals("Netflix renews tomorrow", c.content.title)
        assertEquals(listOf("Keep", "Manage", "Never"), c.content.actions.map { it.label })
        assertEquals("https://www.netflix.com/youraccount", c.opens["manage"])
        // No amount anywhere on the card.
        assertEquals(false, c.content.toString().contains("15.99"))
        val two = Renewals.card(MomentContext(now, qol = QolSignals(renewals = listOf("Hulu", "Netflix"))))!!
        assertEquals("2 renew tomorrow", two.content.title)
        assertEquals(Renewals.PLAY, two.opens["manage"])
        assertEquals(Renewals.PLAY, Renewals.manage("Some Gym"))
        assertNull(Renewals.card(MomentContext(now.withHour(23), qol = QolSignals(renewals = listOf("Netflix")))))
    }
}
