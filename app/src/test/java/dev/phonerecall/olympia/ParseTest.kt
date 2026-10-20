package dev.phonerecall.olympia

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Every parser against JSON fetched from the real endpoints on 2026-10-19 (app/src/test/resources/olympia). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ParseTest {
    private fun read(name: String): String =
        javaClass.classLoader!!.getResource("olympia/$name")!!.readText(Charsets.UTF_8)

    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun soccerFixturesAcrossCompetitions() {
        val (info, matches) = Espn.schedule(read("soccer_schedule_fixtures.json"), Sport.SOCCER)
        assertEquals("bar", info!!.key)
        assertEquals("1st in Spanish LALIGA", info.standing)
        assertEquals(emptyList<Match>(), matches.filter { it.phase != Phase.PRE || "bar" !in it.teams })
        assertTrue(matches.any { it.league == "uefa.champions" })
        val clasico = matches.first { it.id == "401882832" }
        assertEquals("Barcelona v Real Madrid", clasico.title)
        assertEquals(at("2026-10-25T20:00:00Z"), clasico.start)
        assertEquals("BAR", clasico.home!!.abbr)
        assertNull(clasico.score)
    }

    @Test fun soccerResultsReadScoreObjects() {
        val (_, matches) = Espn.schedule(read("soccer_schedule_results.json"), Sport.SOCCER)
        val betis = matches.first { it.id == "401882846" }
        assertEquals(Phase.DONE, betis.phase)
        assertEquals("2–5", betis.score)
        assertEquals("BET 2–5 BAR", betis.scoreLine())
    }

    @Test fun soccerLiveKeepsGoalsAndRedsOnly() {
        val l = Espn.soccerLive(read("soccer_scoreboard_final.json"), "401882842", null)!!
        assertEquals(Phase.DONE, l.match.phase)
        assertEquals("esp.1", l.match.league)
        val kinds = l.moments.map { it.kind }
        assertTrue(MomentKind.GOAL in kinds)
        assertTrue(MomentKind.RED_CARD in kinds)
        assertEquals(MomentKind.FULL_TIME, kinds.last())
        assertTrue(l.moments.none { it.text.contains("Yellow", true) })
        val goal = l.moments.first { it.kind == MomentKind.GOAL }
        assertEquals("Goal, RAY: Álvaro García 11'", goal.text)
        assertEquals("Red card, GET: Djené 28'", l.moments.first { it.kind == MomentKind.RED_CARD }.text)
        // Ids are stable: the same feed twice gives the same ids.
        assertEquals(l.moments.map { it.id }, Espn.soccerLive(read("soccer_scoreboard_final.json"), "401882842", l)!!.moments.map { it.id })
    }

    @Test fun lineupsFromTheSummary() {
        val lu = Espn.lineups(read("soccer_summary_final.json"))
        assertEquals(listOf("BET", "BAR"), lu.map { it.team })
        val bar = lu[1]
        assertEquals("4-2-3-1", bar.formation)
        assertEquals(11, bar.starters.size)
        assertTrue("Lamine Yamal" in bar.starters)
        assertTrue(bar.bench.isNotEmpty())
        // Cricket summaries carry the playing XI the same way.
        assertEquals(2, Espn.lineups(read("cricket_summary_final.json")).size)
    }

    @Test fun nflScheduleAndStanding() {
        val (info, games) = Espn.schedule(read("nfl_schedule.json"), Sport.NFL)
        assertEquals("buf", info!!.key)
        assertEquals("1st in AFC East", info.standing)
        assertEquals("5-1", info.record)
        val ravens = games.first { it.id == "401873026" }
        assertEquals(Phase.PRE, ravens.phase)
        assertEquals(setOf("buf"), ravens.teams)
        assertEquals("nfl", ravens.league)
        val lv = games.first { it.id == "401873005" }
        assertEquals("BUF 33–30 LV", lv.scoreLine())
    }

    @Test fun nflLiveClockAndDownAndDistance() {
        val l = Espn.nflLive(read("nfl_scoreboard_week_live.json"), "401873008", null)!!
        assertEquals(Phase.LIVE, l.match.phase)
        assertEquals("Q1 14:29", l.match.clock)
        assertEquals("1st & 10 at WSH 34", l.situation)
        assertEquals("WSH 0–0 SF", l.match.scoreLine())
        // Later in the drive: goal to go at a timeout has no down, so no line.
        val later = Espn.nflLive(read("nfl_scoreboard_live.json"), "401873008", l)!!
        assertEquals("Q1 10:53", later.match.clock)
        assertNull(later.situation)
        assertTrue(Espn.scoreMoved(l, later))
    }

    @Test fun nflScoringPlaysBecomeMoments() {
        val base = Live(Espn.schedule(read("nfl_schedule.json"), Sport.NFL).second.first { it.id == "401873005" })
        val l = Espn.nflScoring(read("nfl_summary_final.json"), base)
        assertEquals(13, l.moments.size)
        assertEquals(6, l.moments.count { it.kind == MomentKind.TOUCHDOWN })
        assertEquals(7, l.moments.count { it.kind == MomentKind.FIELD_GOAL })
        val shakir = l.moments.first { it.text.contains("Khalil Shakir") }
        assertEquals("Touchdown BUF: Khalil Shakir 9 Yd pass from Josh Allen (Tyler Bass Kick) (13–7)", shakir.text)
        assertEquals("Q2 7:13", shakir.clock)
        // Merging again adds nothing.
        assertEquals(13, Espn.nflScoring(read("nfl_summary_final.json"), l).moments.size)
        // The live game's summary has its first touchdown.
        val live = Espn.nflScoring(read("nfl_summary_live.json"), Live(base.match.copy(id = "401873008")))
        assertEquals(listOf(MomentKind.TOUCHDOWN), live.moments.map { it.kind })
    }

    @Test fun iplSeasonForPunjabKings() {
        val season = Espn.scoreboard(read("cricket_season.json"), Sport.CRICKET)
        assertEquals(74, season.size)
        val pbks = season.filter { "pbks" in it.teams }
        assertEquals(14, pbks.size)
        assertEquals("PBKS won by 3 wkts (5b rem)", pbks.first().result)
        assertEquals("8048", pbks.first().league)
    }

    @Test fun cricketFinalWicketsAndResult() {
        val l = Espn.cricketLive(read("cricket_season.json"), "1535465", null)!!
        assertEquals(Phase.DONE, l.match.phase)
        assertEquals(13, l.moments.count { it.kind == MomentKind.WICKET })
        assertTrue(l.moments.any { it.id == "inn:1" })
        assertEquals("RCB won by 5 wkts (12b rem)", l.moments.last().text)
        assertNull(l.situation)
    }

    @Test fun cricketChaseLine() {
        // The final, rewound to 140/3 after 16.2 overs of the chase.
        val d = JSONObject(read("cricket_season.json"))
        val events = d.getJSONArray("events")
        val e = (0 until events.length()).map { events.getJSONObject(it) }.first { it.getString("id") == "1535465" }
        val comp = e.getJSONArray("competitions").getJSONObject(0)
        comp.getJSONObject("status").getJSONObject("type").put("state", "in")
        val rcb = (0 until comp.getJSONArray("competitors").length()).map { comp.getJSONArray("competitors").getJSONObject(it) }.first { it.getJSONObject("team").getString("abbreviation") == "RCB" }
        rcb.put("score", "140/3 (16.2/20 ov, target 156)")
        rcb.getJSONArray("linescores").getJSONObject(1).put("runs", 140).put("wickets", 3).put("overs", 16.2)
        val l = Espn.cricketLive(d.toString(), "1535465", null)!!
        assertEquals(Phase.LIVE, l.match.phase)
        assertEquals("16.2 ov", l.match.clock)
        assertEquals("need 16 off 22", l.situation)
        assertEquals(98 to 120, l.progress)
    }

    @Test fun cricketFiftiesFromTheBattingCard() {
        val base = Espn.cricketLive(read("cricket_season.json"), "1535465", null)!!
        val l = Espn.cricketCards(read("cricket_summary_final.json"), base)
        val kohli = l.moments.first { it.kind == MomentKind.FIFTY && it.text.contains("Kohli") }
        assertEquals("Fifty, V Kohli 75 (42)", kohli.text)
        assertEquals(MomentKind.FULL_TIME, l.moments.last().kind)
    }

    @Test fun f1WeekendFromEspn() {
        val sessions = Espn.f1Sessions(read("f1_scoreboard_upcoming.json"))
        assertEquals(listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race"), sessions.map { it.session })
        assertTrue(sessions.all { it.phase == Phase.PRE })
        val done = Espn.f1Sessions(read("f1_scoreboard_final.json"))
        assertEquals("Verstappen", done.first { it.session == "Race" }.result)
        assertTrue(done.any { it.session == "Sprint" })
    }

    @Test fun f1LeadChangeAndFinish() {
        val race = Jolpica.schedule(read("jolpica_schedule.json"), 0L).first { it.session == "Race" }
        // Rewind Singapore to lap 34 with Verstappen in front; last poll had Leclerc leading.
        val d = JSONObject(read("f1_scoreboard_final.json"))
        val comps = d.getJSONArray("events").getJSONObject(0).getJSONArray("competitions")
        val raceComp = (0 until comps.length()).map { comps.getJSONObject(it) }.first { it.getJSONObject("type").getString("abbreviation") == "Race" }
        raceComp.getJSONObject("status").put("period", 34).getJSONObject("type").put("state", "in")
        val prev = Live(race.copy(phase = Phase.LIVE), positions = listOf("Leclerc", "Verstappen"))
        val l = Espn.f1Live(d.toString(), "Race", prev, laps = 62)!!
        assertEquals("Lap 34/62", l.match.clock)
        assertEquals(34 to 62, l.progress)
        assertEquals("Verstappen", l.positions.first())
        assertEquals("Verstappen takes the lead from Leclerc, lap 34", l.moments.single { it.kind == MomentKind.LEAD }.text)
        // Same leader next poll: no new moment.
        assertEquals(1, Espn.f1Live(d.toString(), "Race", l, laps = 62)!!.moments.size)
        val fin = Espn.f1Live(read("f1_scoreboard_final.json"), "Race", l, laps = 62)!!
        assertEquals(Phase.DONE, fin.match.phase)
        assertEquals(MomentKind.FINISH, fin.moments.last().kind)
        assertTrue(fin.moments.last().text.startsWith("Verstappen wins the"))
        val quali = Espn.f1Live(read("f1_scoreboard_final.json"), "Qualifying", null, null)!!
        assertEquals(MomentKind.POLE, quali.moments.single().kind)
    }

    @Test fun f1WeekendFilterSkipsOtherWeekends() {
        assertNull(Espn.f1Live(read("f1_scoreboard_final.json"), "Race", null, null, near = at("2026-10-25T20:00:00Z")))
        assertNotNull(Espn.f1Live(read("f1_scoreboard_upcoming.json"), "Race", null, null, near = at("2026-10-25T20:00:00Z")))
    }

    @Test fun jolpicaCalendarPodiumAndLaps() {
        val now = at("2026-10-19T20:00:00Z")
        val s = Jolpica.schedule(read("jolpica_schedule.json"), now)
        val usgp = s.filter { it.id.startsWith("f1:2026:18:") }
        assertEquals(listOf("Practice 1", "Practice 2", "Practice 3", "Qualifying", "Race"), usgp.map { it.session })
        assertEquals("United States Grand Prix", usgp.last().title)
        assertEquals("United States Grand Prix qualifying", usgp[3].title)
        assertEquals(at("2026-10-25T20:00:00Z"), usgp.last().start)
        assertEquals("americas", usgp.last().circuit)
        assertTrue(usgp.first().isPractice)
        assertTrue(s.filter { it.start < now - 4 * 3_600_000L }.all { it.phase == Phase.DONE })
        assertEquals("Singapore Grand Prix" to listOf("Verstappen", "Leclerc", "Antonelli"), Jolpica.podium(read("jolpica_last_results.json")))
        assertEquals(56, Jolpica.laps(read("jolpica_circuit_laps.json")))
    }

    @Test fun brokenJsonGivesNothingNotACrash() {
        assertTrue(Espn.scoreboard("{\"events\":[{\"id\":\"1\"}]}", Sport.SOCCER).isEmpty())
        assertNull(Espn.soccerLive("{}", "1", null))
        assertTrue(Espn.lineups("{\"rosters\":[{\"roster\":[]}]}").isEmpty())
    }
}
