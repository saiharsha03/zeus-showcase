package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Family groups at night: held, then one morning card. */
class FamilyNightTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int) = ZonedDateTime.of(2026, 10, 20, h, 0, 0, 0, zone)

    @Test fun heldOnlyForFamilyGroupsWhileHeSleeps() {
        assertTrue(FamilyNight.hold(true, "Family 🏠", at(2)))
        assertTrue(FamilyNight.hold(true, "Cousins", at(23)))
        assertFalse(FamilyNight.hold(true, "Family 🏠", at(14)))
        assertFalse(FamilyNight.hold(true, "Office pals", at(2)))
        assertFalse(FamilyNight.hold(false, "Amma", at(2)))
    }

    @Test fun theMorningCard() {
        val notes = listOf(FamilyNote("Family", "Amma", "Did you eat?", 1), FamilyNote("Family", "Alex", "Photos from the wedding", 2), FamilyNote("Cousins", null, "Hi", 3))
        assertEquals("Family: 2 messages (Amma, Alex). Cousins: 1 message.", FamilyNight.summary(notes))
        val c = MomentRules.pick(MomentContext(at(8), qol = QolSignals(family = FamilyDigest(FamilyNight.summary(notes), "Amma asked if you ate; Alex shared wedding photos."))), MomentsPrefs())!!
        assertEquals(MomentKind.FAMILY_DIGEST, c.kind)
        assertEquals("Amma asked if you ate; Alex shared wedding photos.", c.content.lead)
        assertEquals(listOf("Open chat", "Got it", "Never"), c.content.actions.map { it.label })
        assertNull(FamilyNight.card(MomentContext(at(13), qol = QolSignals(family = FamilyDigest("x")))))
        assertTrue(FamilyNight.prompt(notes).contains("[Family] Amma: Did you eat?"))
    }
}
