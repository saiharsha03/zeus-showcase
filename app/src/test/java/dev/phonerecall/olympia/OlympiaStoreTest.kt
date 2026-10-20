package dev.phonerecall.olympia

import android.content.Context
import org.robolectric.RuntimeEnvironment
import dev.phonerecall.data.Relevance
import dev.phonerecall.data.RelevanceConfig
import dev.phonerecall.data.Rule
import dev.phonerecall.data.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OlympiaStoreTest {
    private val now = Instant.parse("2026-10-19T20:00:00Z").toEpochMilli()
    private fun read(name: String) = javaClass.classLoader!!.getResource("olympia/$name")!!.readText(Charsets.UTF_8)

    private val fixtures = Espn.schedule(read("soccer_schedule_fixtures.json"), Sport.SOCCER).second
    private val f1 = Jolpica.schedule(read("jolpica_schedule.json"), now)

    @Test fun practiceAndSwitchedOffCompetitionsAreLeftOut() {
        val s = OlympiaSnapshot(matches = fixtures + f1)
        assertTrue(s.followed.none { it.isPractice })
        assertTrue(s.followed.any { it.session == "Qualifying" })
        assertTrue(s.copy(settings = s.settings.copy(practice = true)).followed.any { it.isPractice })
        val noCl = s.copy(settings = s.settings.copy(competitions = s.settings.competitions - Competition.CHAMPIONS))
        assertTrue(noCl.followed.none { it.league == "uefa.champions" })
        val unfollowed = s.copy(settings = s.settings.copy(followed = s.settings.followed - "bar"))
        // Barça against another team he follows (a Champions League night in England) stays.
        assertTrue(unfollowed.followed.none { it.teams == setOf("bar") })
        assertTrue(unfollowed.followed.any { "bar" in it.teams && it.teams.size > 1 })
    }

    @Test fun snapshotSurvivesTheRoundTrip() {
        val live = Espn.soccerLive(read("soccer_scoreboard_final.json"), "401882842", null)!!.copy(lineups = Espn.lineups(read("soccer_summary_final.json")))
        val s = OlympiaSnapshot(
            settings = OlympiaSettings(followed = setOf("bar", Teams.F1), alerts = Alerts.RESULTS, practice = true),
            matches = fixtures.take(3) + f1.take(2), teams = mapOf("bar" to TeamInfo("bar", "1st in Spanish LALIGA", "9-0-0")),
            live = mapOf(live.match.id to live), podium = "Singapore Grand Prix" to listOf("Verstappen", "Leclerc", "Antonelli"), refreshedAt = now,
        )
        val back = Codec.snapshot(Codec.json(s))!!
        assertEquals(s.settings, back.settings)
        assertEquals(s.matches, back.matches)
        assertEquals(s.teams, back.teams)
        assertEquals(s.live, back.live)
        assertEquals(s.podium, back.podium)
        assertNull(Codec.snapshot(""))
    }

    @Test fun seenMomentsPerMatch() {
        val c = RuntimeEnvironment.getApplication() as Context
        assertNull(OlympiaStore.seen(c, "m1"))
        OlympiaStore.markSeen(c, "m1", listOf("a", "b"))
        assertEquals(setOf("a", "b"), OlympiaStore.seen(c, "m1"))
    }

    @Test fun chronosEntriesRespectHisRules() {
        val s = OlympiaSnapshot(matches = fixtures + f1)
        val items = OlympiaCalendar.items(s, now, now + 9 * 86_400_000L, now)
        assertTrue(items.any { it.title == "Barcelona v Real Madrid" })
        assertTrue(items.any { it.title == "United States Grand Prix qualifying" })
        // F1 practice is hidden by default.
        assertTrue(items.none { it.title.contains("practice") })
        assertTrue(items.all { it.calendarId == OlympiaCalendar.ID && it.category == dev.phonerecall.data.EventCategory.SPORT })
        // The Olympia calendar shows by default (they are his teams); his own rules still win.
        val cfg = RelevanceConfig(rules = Relevance.seeds)
        assertTrue(Relevance.relevant("Barcelona v Real Madrid", OlympiaCalendar.INFO, cfg))
        val mine = cfg.copy(rules = listOf(Rule("mine", "alavés", RuleAction.HIDE)) + cfg.rules)
        assertFalse(Relevance.relevant("Barcelona v Alavés", OlympiaCalendar.INFO, mine))
        // A match on now is left to the live card on Olympus.
        val clasico = fixtures.first { it.id == "401882832" }
        val during = clasico.start + 30 * MatchDay.MIN
        assertTrue(OlympiaCalendar.items(s, during - MatchDay.HOUR, during + MatchDay.HOUR, during, exceptLive = true).none { it.title == clasico.title })
    }

    @Test fun widgetPicksLiveThenResultThenNext() {
        val zone = ZoneId.of("America/New_York")
        val clasico = fixtures.first { it.id == "401882832" }
        val base = OlympiaSnapshot(matches = fixtures)
        val before = LiveWidget.pick(base, Instant.ofEpochMilli(clasico.start - 5 * 86_400_000L).atZone(zone))
        assertTrue(before.kicker.startsWith("Next"))
        val liveMatch = clasico.copy(phase = Phase.LIVE, clock = "67'", home = clasico.home!!.copy(score = "2"), away = clasico.away!!.copy(score = "1"))
        val live = base.copy(live = mapOf(clasico.id to Live(liveMatch, listOf(Moment("g", MomentKind.GOAL, "Goal, BAR: Lewandowski 64'", "64'")))))
        val during = LiveWidget.pick(live, Instant.ofEpochMilli(clasico.start + 70 * MatchDay.MIN).atZone(zone))
        assertEquals("Live · LALIGA", during.kicker)
        assertEquals("Barcelona 2–1 Real Madrid", during.title)
        assertEquals("67' · Goal, BAR: Lewandowski 64'", during.line)
        val done = live.copy(live = mapOf(clasico.id to Live(liveMatch.copy(phase = Phase.DONE), listOf(Moment("ft", MomentKind.FULL_TIME, "Full time, BAR 2–1 RMA", "FT")))))
        val after = LiveWidget.pick(done, Instant.ofEpochMilli(clasico.start + 3 * MatchDay.HOUR).atZone(zone))
        assertEquals("Full time, BAR 2–1 RMA", after.title)
        // The Olympus card follows the same order.
        assertEquals("Live", OlympiaCards.card(live, Instant.ofEpochMilli(clasico.start + 70 * MatchDay.MIN).atZone(zone))!!.content.status)
        assertEquals("Final", OlympiaCards.card(done, Instant.ofEpochMilli(clasico.start + 3 * MatchDay.HOUR).atZone(zone))!!.content.status)
        assertNull(OlympiaCards.card(base, Instant.ofEpochMilli(clasico.start - 5 * 86_400_000L).atZone(zone)))
    }

    @Test fun refreshUsesOnlyTheAllowlistedHostsAndKeepsOldRowsOnFailure() {
        val c = RuntimeEnvironment.getApplication() as Context
        OlympiaStore.update(c) { OlympiaSnapshot(settings = OlympiaSettings(followed = setOf("bar", "buf", "pbks", Teams.F1))) }
        val asked = ArrayList<String>()
        val files = mapOf(
            Urls.soccerFixtures("83") to "soccer_schedule_fixtures.json", Urls.soccerResults("83") to "soccer_schedule_results.json",
            Urls.nflSchedule("2") to "nfl_schedule.json", Urls.iplSeason(2026) to "cricket_season.json",
            Urls.f1Schedule() to "jolpica_schedule.json", Urls.f1LastResults() to "jolpica_last_results.json",
        )
        val s = Sync(c) { url -> asked += url; dev.phonerecall.brain.NetGate.check(url); read(files[url] ?: throw java.io.IOException("no sample")) }.refresh(now)
        assertTrue(asked.all { dev.phonerecall.brain.NetGate.allows(it) })
        assertEquals("1st in AFC East", s.teams["buf"]!!.standing)
        assertEquals("1st in Spanish LALIGA", s.teams["bar"]!!.standing)
        assertEquals("7-6", s.teams["pbks"]!!.record)
        assertTrue(s.matches.any { "pbks" in it.teams } && s.matches.any { it.sport == Sport.F1 })
        assertEquals(now, s.refreshedAt)
        // Next refresh fails for everything: the rows stay.
        val again = Sync(c) { throw java.io.IOException("offline") }.refresh(now + 1)
        assertEquals(s.matches.size, again.matches.size)
        assertEquals(now, again.refreshedAt)
    }
}
