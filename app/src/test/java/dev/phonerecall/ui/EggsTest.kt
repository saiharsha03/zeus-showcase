package dev.phonerecall.ui

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class EggsTest {
    @Test fun konamiNeedsUpUpDownDownInOrder() {
        var h = emptyList<Boolean>()
        var hit = false
        for (up in listOf(false, true, true, false, false)) Eggs.swipe(h, up).let { h = it.first; hit = it.second }
        assertTrue(hit)
        assertTrue(h.isEmpty())
        h = emptyList()
        for (up in listOf(true, false, true, false)) Eggs.swipe(h, up).let { h = it.first; hit = it.second }
        assertFalse(hit)
    }

    @Test fun atticNumerals() {
        assertEquals("Ι", Eggs.attic(1))
        assertEquals("ΠΙΙΙ", Eggs.attic(8))
        assertEquals("ΔΠΙΙΙΙ", Eggs.attic(19))
        assertEquals("ΧΧΔΔΠΙ", Eggs.attic(2026))
        assertEquals("ΔΠΙΙΙΙ October ΧΧΔΔΠΙ", Eggs.atticDate(LocalDate.of(2026, 10, 19)))
    }

    @Test fun metisNeedsThreeQuickTaps() {
        var n = Eggs.metisTaps(0, 0, 10_000)
        n = Eggs.metisTaps(n, 10_000, 10_400)
        n = Eggs.metisTaps(n, 10_400, 10_900)
        assertEquals(3, n)
        assertEquals(1, Eggs.metisTaps(2, 10_000, 13_000))
    }

    @Test fun delphiEggs() {
        assertNotNull(Eggs.annabethWink("Ask Annabeth to plan the Toronto trip"))
        assertNull(Eggs.annabethWink("Annabeth Chase books"))
        assertNotNull(Eggs.oracle("Who are you?", used = false))
        assertNull(Eggs.oracle("Who are you?", used = true))
        assertNull(Eggs.oracle("who are you going with on Friday", used = false))
        assertTrue(Eggs.ATHENA_LINES.none { it.contains('—') })
        assertEquals(Eggs.athenaLine(0), Eggs.athenaLine(Eggs.ATHENA_LINES.size))
    }

    @Test fun laurelOnlyOnHisBirthday() {
        val zone = ZoneId.of("America/New_York")
        val d = LocalDate.of(2026, 5, 4)
        val s = d.atStartOfDay(zone).toInstant().toEpochMilli()
        fun ev(t: String) = CalItem(1, 1, t, s, s + 86_400_000L, true, EventCategory.BIRTHDAY, "Birthdays", null, null, false)
        assertTrue(Eggs.birthdayToday(listOf(ev("The owner's birthday")), d, zone))
        assertTrue(Eggs.birthdayToday(listOf(ev("My birthday")), d, zone))
        assertFalse(Eggs.birthdayToday(listOf(ev("Alex's birthday")), d, zone))
        assertFalse(Eggs.birthdayToday(listOf(ev("The owner's birthday")), d.plusDays(1), zone))
    }

    @Test fun everyPlaceWithAMottoHasAnEpithet() {
        assertEquals(Place.entries.toSet(), Eggs.EPITHETS.keys)
        assertTrue(Eggs.EPITHETS.values.none { it.contains('—') || it.contains('!') })
    }
}
