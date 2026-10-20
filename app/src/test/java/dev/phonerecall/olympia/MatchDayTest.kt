package dev.phonerecall.olympia

import dev.phonerecall.olympia.MatchDay.HOUR
import dev.phonerecall.olympia.MatchDay.MIN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MatchDayTest {
    private val t0 = 1_800_000_000_000L
    private fun soccer(id: String, start: Long, phase: Phase = Phase.PRE) = Match(id, Sport.SOCCER, "esp.1", "LALIGA", start, phase, "Barcelona v Real Madrid",
        home = Side("83", "Barcelona", "BAR", if (phase == Phase.PRE) null else "2", true), away = Side("86", "Real Madrid", "RMA", if (phase == Phase.PRE) null else "1", false), teams = setOf("bar"))
    private fun nfl(start: Long, phase: Phase = Phase.PRE) = Match("n1", Sport.NFL, "nfl", "NFL", start, phase, "Baltimore Ravens at Buffalo Bills",
        home = Side("2", "Bills", "BUF", "24", true), away = Side("33", "Ravens", "BAL", "17", false), teams = setOf("buf"))

    @Test fun liveWindowRunsFromTheLeadInAndStopsAtFullTime() {
        val m = soccer("a", t0)
        assertFalse(MatchDay.live(m, t0 - 6 * MIN))
        assertTrue(MatchDay.live(m, t0 - 5 * MIN))
        assertTrue(MatchDay.live(m, t0 + 2 * HOUR))
        // The feed never said it started: the expected length is the backstop.
        assertFalse(MatchDay.live(m, t0 + 3 * HOUR))
        // The feed says it is on (extra time, a delay): keep going past the expected length.
        assertTrue(MatchDay.live(m.copy(phase = Phase.LIVE), t0 + 3 * HOUR))
        // Full time or postponed: stop at once.
        assertFalse(MatchDay.live(m.copy(phase = Phase.DONE), t0 + 50 * MIN))
        assertFalse(MatchDay.live(m.copy(phase = Phase.OFF), t0))
    }

    @Test fun cadencePerSport() {
        assertEquals(45_000L, MatchDay.cadence(Sport.SOCCER))
        assertEquals(45_000L, MatchDay.cadence(Sport.NFL))
        assertEquals(120_000L, MatchDay.cadence(Sport.CRICKET))
        assertEquals(60_000L, MatchDay.cadence(Sport.F1))
        val m = soccer("a", t0)
        assertEquals(t0, MatchDay.due(m, null, t0))
        assertEquals(t0 + 45_000L, MatchDay.due(m, t0, t0 + 10_000L))
    }

    @Test fun nextWakeIsTheLineupLookThenTheLeadIn() {
        val m = soccer("a", t0)
        assertEquals(t0 - 60 * MIN, MatchDay.nextWake(listOf(m), t0 - 3 * HOUR))
        assertEquals(t0 - 5 * MIN, MatchDay.nextWake(listOf(m), t0 - 30 * MIN))
        assertNull(MatchDay.nextWake(listOf(m), t0))
        // The NFL has no lineup look: straight to the lead-in.
        assertEquals(t0 - 5 * MIN, MatchDay.nextWake(listOf(nfl(t0)), t0 - 3 * HOUR))
        // Finished matches never wake anything.
        assertNull(MatchDay.nextWake(listOf(soccer("b", t0 + HOUR, Phase.DONE)), t0))
        assertTrue(MatchDay.lineupsDue(m, t0 - 40 * MIN))
        assertFalse(MatchDay.lineupsDue(m, t0 - 2 * MIN))
        assertFalse(MatchDay.lineupsDue(nfl(t0), t0 - 40 * MIN))
    }

    @Test fun headlineIsLiveFirstThenNext() {
        val later = soccer("later", t0 + 5 * HOUR)
        val live = nfl(t0 - HOUR, Phase.LIVE)
        assertEquals("n1", MatchDay.headline(listOf(later, live), t0)!!.id)
        assertEquals("later", MatchDay.headline(listOf(later, live.copy(phase = Phase.DONE)), t0)!!.id)
        assertNull(MatchDay.headline(emptyList(), t0))
    }

    @Test fun resultsOnlyReadsOnceAroundTheEnd() {
        val m = soccer("a", t0)
        assertEquals(t0 + 115 * MIN, ResultCheck.next(listOf(m), t0))
        assertTrue(ResultCheck.pending(listOf(m), t0 + 2 * HOUR).isNotEmpty())
        assertEquals(t0 + 2 * HOUR + 20 * MIN, ResultCheck.next(listOf(m), t0 + 2 * HOUR))
        assertTrue(ResultCheck.pending(listOf(m.copy(phase = Phase.DONE)), t0 + 2 * HOUR).isEmpty())
    }
}

class MomentsTest {
    private val goal = Moment("g1", MomentKind.GOAL, "Goal, BAR: Lewandowski 64'", "64'")
    private val ht = Moment("ht", MomentKind.HALF_TIME, "Half time", "HT")
    private val ft = Moment("ft", MomentKind.FULL_TIME, "Full time, BAR 2–1 SEV", "FT")
    private val fg = Moment("sp:1", MomentKind.FIELD_GOAL, "Field goal BUF", "Q1 10:12")

    @Test fun firstLookNotifiesNothing() {
        assertTrue(Moments.fresh(listOf(goal, ft), seen = null).isEmpty())
    }

    @Test fun onlyNewKeyMomentsNotify() {
        assertEquals(listOf(goal), Moments.fresh(listOf(ht, goal, fg), seen = setOf("ht")))
        assertTrue(Moments.fresh(listOf(ht, goal), seen = setOf("ht", "g1")).isEmpty())
        assertEquals(listOf(ft), Moments.fresh(listOf(goal, ft), seen = setOf("g1")))
    }

    @Test fun theSettingDecidesWhatSounds() {
        assertEquals(listOf(goal, ft), Moments.alerting(listOf(goal, ft), Alerts.KEY))
        assertEquals(listOf(ft), Moments.alerting(listOf(goal, ft), Alerts.RESULTS))
        assertTrue(Moments.alerting(listOf(goal, ft), Alerts.QUIET).isEmpty())
        assertTrue(Moments.alerting(listOf(goal, ft), Alerts.OFF).isEmpty())
    }
}

class NoticeWordsTest {
    private val now = ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, ZoneId.of("America/New_York"))

    @Test fun soccerShowsTheScoreTheMinuteAndTheLastGoal() {
        val m = Match("1", Sport.SOCCER, "esp.1", "LALIGA", 0, Phase.LIVE, "Barcelona v Sevilla",
            home = Side("83", "Barcelona", "BAR", "2", true), away = Side("243", "Sevilla", "SEV", "1", false), clock = "67'")
        val w = NoticeWords.of(Live(m, listOf(Moment("g", MomentKind.GOAL, "Goal, BAR: Lewandowski 64'", "64'"))))
        assertEquals("Barcelona 2–1 Sevilla", w.title)
        assertEquals("67' · Goal, BAR: Lewandowski 64'", w.text)
        assertEquals("BAR 2–1", w.chip)
    }

    @Test fun nflPutsTheAwaySideFirstWithDownAndDistance() {
        val m = Match("2", Sport.NFL, "nfl", "NFL", 0, Phase.LIVE, "Buffalo Bills at Kansas City Chiefs",
            home = Side("12", "Chiefs", "KC", "17", true), away = Side("2", "Bills", "BUF", "24", false), clock = "Q3 4:12")
        val w = NoticeWords.of(Live(m, situation = "2nd & 7 at KC 35"))
        assertEquals("BUF 24–17 KC", w.title)
        assertEquals("Q3 4:12 · 2nd & 7 at KC 35", w.text)
    }

    @Test fun f1ShowsTheLapAndTheTopTwo() {
        val m = Match("3", Sport.F1, "f1", "F1", 0, Phase.LIVE, "United States Grand Prix", session = "Race", clock = "Lap 34/56")
        val w = NoticeWords.of(Live(m, positions = listOf("Verstappen", "Norris", "Leclerc"), progress = 34 to 56))
        assertEquals("Lap 34/56 · P1 Verstappen · P2 Norris", w.text)
        assertEquals("P1 VER", w.chip)
        assertEquals(34 to 56, w.progress)
    }

    @Test fun cricketShowsTheChase() {
        val m = Match("4", Sport.CRICKET, "8048", "IPL", 0, Phase.LIVE, "Punjab Kings v Royal Challengers Bengaluru",
            home = Side("335973", "PBKS", "PBKS", "180/6", true), away = Side("335970", "RCB", "RCB", "142/3 (16.2/20 ov, target 181)", false), clock = "16.2 ov")
        val w = NoticeWords.of(Live(m, situation = "need 39 off 22", progress = 98 to 120))
        assertEquals("PBKS 180/6 v RCB 142/3", w.title)
        assertEquals("16.2 ov · need 39 off 22", w.text)
        assertEquals("RCB 142/3", w.chip)
    }

    @Test fun beforeTheStart() {
        val m = Match("5", Sport.SOCCER, "esp.1", "LALIGA", now.withHour(16).toInstant().toEpochMilli(), Phase.PRE, "Barcelona v Real Madrid")
        assertEquals("Kickoff 4:00 PM", NoticeWords.before(m, now).text)
    }
}
