package dev.phonerecall.qol

import dev.phonerecall.brain.Replies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Home in N min" when someone asks where he is while he drives. */
class HomeEtaTest {
    @Test fun whoIsAsking() {
        assertTrue(HomeEta.asks("where are you?"))
        assertTrue(HomeEta.asks("When will you be home"))
        assertTrue(HomeEta.asks("how far are you"))
        assertTrue(HomeEta.asks("kab aaoge?"))
        assertFalse(HomeEta.asks("see you tomorrow"))
        assertFalse(HomeEta.asks(null))
    }

    @Test fun minutesRoundedUpToFive() {
        // 10 km: 13 km of road at 40 km/h is ~20 min.
        assertEquals(20, HomeEta.minutes(10_000.0))
        assertEquals(5, HomeEta.minutes(800.0))
        assertNull(HomeEta.minutes(150_000.0))
        assertEquals("Home in 20 min", HomeEta.words(20))
    }

    @Test fun itLeadsTheShortChips() {
        val chips = Replies.chips(null, listOf("Home in 20 min") + emptyList(), missedCall = false)
        assertEquals("Home in 20 min", chips.first())
    }
}
