package dev.phonerecall.rhythms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HabitsTest {
    private val ny = ZoneId.of("America/New_York")
    private val chess = "com.example.chess"
    private val today = LocalDate.of(2026, 10, 19)
    private val now = today.atTime(12, 0).atZone(ny).toInstant().toEpochMilli()
    private val since = today.minusDays(29).atStartOfDay(ny).toInstant().toEpochMilli()

    private fun at(d: LocalDate, h: Int, m: Int) = d.atTime(h, 0).plusMinutes(m.toLong()).atZone(ny).toInstant().toEpochMilli()

    /** A game most nights, 8:45 to about 10, missing every fifth night. */
    private fun nights(skip: (Int) -> Boolean = { it % 5 == 0 }) = (1..28).filterNot(skip).map { i ->
        val d = today.minusDays(i.toLong())
        Session(chess, at(d, 20, 40 + i % 10), at(d, 21, 55 + i % 10))
    }

    private fun learn(sessions: List<Session>, excluded: (String) -> Boolean = { false }) =
        Habits.learn(sessions, now, ny, since, label = { if (it == chess) "Chess Arena" else it }, game = { it == chess }, excluded = excluded)

    @Test fun learnsTheNightlyGame() {
        val h = learn(nights()).single()
        assertEquals(chess, h.pkg)
        assertEquals(7, h.days.size)
        assertEquals(20 * 60 + 45, h.startMin)
        assertEquals(22 * 60, h.endMin)
        assertTrue(h.strong)
        assertEquals("Every night, 8:45–10 PM", Habits.line(h))
        assertEquals("usually games every night ~8:45–10 PM (Chess Arena)", Habits.brainLine(h))
    }

    @Test fun pastMidnightBelongsToTheNightBefore() {
        val s = (1..28).map { i -> val d = today.minusDays(i.toLong()); Session(chess, at(d.plusDays(1), 0, 15), at(d.plusDays(1), 1, 0)) }
        val h = learn(s).single()
        assertEquals(15, h.startMin)
        val (start, _) = Habits.windowOn(h, LocalDate.of(2026, 10, 16), ny)
        assertEquals(LocalDate.of(2026, 10, 17), start.toLocalDate())
    }

    @Test fun twiceAWeekIsNotAHabit() {
        val s = (1..28).filter { it % 7 == 0 || it % 7 == 3 }.map { i -> val d = today.minusDays(i.toLong()); Session(chess, at(d, 21, 45), at(d, 23, 0)) }
        assertTrue(learn(s).isEmpty())
    }

    @Test fun waitsForTwoWeeks() {
        val young = now - 10 * Habits.DAY
        assertTrue(Habits.learn(nights(), now, ny, young).isEmpty())
    }

    @Test fun excludedAppsAreNeverLearned() {
        assertTrue(learn(nights(), excluded = { it == chess }).isEmpty())
        val chase = nights().map { it.copy(pkg = "com.chase.sig.android") }
        val s = RhythmsSettings()
        assertTrue(learn(chase, excluded = { AppExclusions.excluded(it, "Chase", s) }).isEmpty())
    }

    @Test fun categories() {
        assertEquals("money", AppExclusions.category("com.chase.sig.android", "Chase"))
        assertEquals("money", AppExclusions.category("com.phonepe.app", "PhonePe"))
        assertEquals("money", AppExclusions.category("com.example.ledger", "Bill pay"))
        assertEquals("security", AppExclusions.category("com.x8bit.bitwarden", "Bitwarden"))
        assertEquals("health", AppExclusions.category("com.samsung.android.shealth", "Samsung Health"))
        assertEquals("health", AppExclusions.category("com.example.rx", "City Pharmacy"))
        assertEquals("dating", AppExclusions.category("co.hinge.app", "Hinge"))
        assertEquals("immigration", AppExclusions.category("gov.example.case", "USCIS Case Status"))
        assertEquals("system", AppExclusions.category("dev.phonerecall", "Zeus"))
        assertNull(AppExclusions.category(chess, "Chess Arena"))
        val s = RhythmsSettings(excluded = listOf(chess), included = listOf("co.hinge.app"))
        assertTrue(AppExclusions.excluded(chess, "Chess Arena", s))
        assertFalse(AppExclusions.excluded("co.hinge.app", "Hinge", s))
    }

    @Test fun skippedNightsAreFoundQuietly() {
        val sessions = nights { false }.filterNot { Habits.eveningOf(it.start, ny) == today.minusDays(2) }
        val h = learn(sessions).single()
        val skips = Habits.skips(h, sessions, today.minusDays(7), now, ny)
        assertEquals(listOf(Skip(chess, today.minusDays(2).toString())), skips)
        // Tonight's window hasn't happened yet, so it isn't a skip.
        assertTrue(skips.none { it.date == today.toString() })
    }

    @Test fun signalForBedtimeAndHolds() {
        val h = learn(nights()).single()
        val sig = Habits.signal(listOf(h), ny)
        assertNotNull(sig.during(ZonedDateTime.of(2026, 10, 19, 21, 30, 0, 0, ny)))
        assertNull(sig.during(ZonedDateTime.of(2026, 10, 19, 18, 0, 0, 0, ny)))
        assertTrue(sig.shouldHold(ZonedDateTime.of(2026, 10, 19, 21, 30, 0, 0, ny), urgent = false))
        assertFalse(sig.shouldHold(ZonedDateTime.of(2026, 10, 19, 21, 30, 0, 0, ny), urgent = true))
        assertEquals("Skip Chess Arena tonight?", sig.bedtimeLine(today, earlyStart = true))
        assertNull(sig.bedtimeLine(today, earlyStart = false))
    }

    @Test fun sessionsFromUsageEvents() {
        val t = at(today, 21, 0)
        val s = Habits.sessions(listOf(
            UEvent(chess, 1, t), UEvent(chess, 2, t + 30 * 60_000L),
            // Back within three minutes: the same session.
            UEvent(chess, 1, t + 32 * 60_000L), UEvent("com.whatsapp", 1, t + 50 * 60_000L),
            UEvent("com.whatsapp", 2, t + 50 * 60_000L + 30_000L),
            UEvent("com.spotify.music", 1, t + 60 * 60_000L), UEvent("x", 16, t + 70 * 60_000L),
        ))
        assertEquals(listOf(Session(chess, t, t + 50 * 60_000L), Session("com.spotify.music", t + 60 * 60_000L, t + 70 * 60_000L)), s)
    }

    @Test fun words() {
        assertEquals("11:30 PM–12:30 AM", Habits.range(23 * 60 + 30, 30))
        assertEquals("weeknights", Habits.days(listOf(1, 2, 3, 4, 5), 22 * 60))
        assertEquals("weekend mornings", Habits.days(listOf(6, 7), 9 * 60))
        assertEquals("most nights", Habits.days(listOf(1, 2, 3, 4, 5, 6), 21 * 60 + 45))
        assertEquals("Mon, Wed and Fri evenings", Habits.days(listOf(5, 1, 3), 18 * 60))
    }
}
