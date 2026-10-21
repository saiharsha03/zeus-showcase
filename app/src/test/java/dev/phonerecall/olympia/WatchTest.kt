package dev.phonerecall.olympia

import dev.phonerecall.home.ActionRole
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
import java.time.ZoneId

/** Where to watch, against ESPN JSON fetched 2026-10-20 (ucl_scoreboard_broadcasts, nfl_schedule_broadcasts, the two finals). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WatchTest {
    private fun read(name: String) = javaClass.classLoader!!.getResource("olympia/$name")!!.readText(Charsets.UTF_8)
    private val zone = ZoneId.of("America/New_York")
    private val ucl = Espn.scoreboard(read("ucl_scoreboard_broadcasts.json"), Sport.SOCCER)
    private val bills = Espn.schedule(read("nfl_schedule_broadcasts.json"), Sport.NFL).second
    private val PARAMOUNT = "com.cbs.app"
    private val YTTV = "com.google.android.apps.youtube.unplugged"
    private val ESPN = "com.espn.score_center"
    private val DISNEY = "com.disney.disneyplus"

    private fun bare(league: String, sport: Sport = Sport.SOCCER, id: String = "1") =
        Match(id, sport, league, "", 0L, Phase.PRE, "A v B")

    @Test fun broadcastersComeFromTheFeedFirst() {
        // Scoreboards list `names`; schedules nest `media.shortName`.
        val psv = ucl.first { it.id == "401915398" }
        assertEquals(listOf("CBSSN", "Paramount+"), psv.broadcasts)
        assertEquals("league-phase", psv.stage)
        // Streaming first; a cable network (CBSSN via YouTube TV) goes last.
        assertEquals(listOf(Service.PARAMOUNT, Service.YOUTUBE_TV), Broadcasters.services(psv))
        assertEquals(listOf(Service.PRIME), Broadcasters.services(bills.first { it.id == "401872932" }))
        assertEquals(listOf(Service.FOX), Broadcasters.services(bills.first { it.id == "401872953" }))
        assertEquals(listOf(Service.PARAMOUNT), Broadcasters.services(bills.first { it.id == "401872660" }))
        assertEquals(listOf(Service.ESPN), Broadcasters.services(bills.first { it.id == "401872994" }))
        // The feed wins over the table: a Champions League game ESPN says is on CBS.
        assertEquals(listOf(Service.PARAMOUNT), Broadcasters.services(bare("uefa.champions").copy(broadcasts = listOf("CBS"))))
        assertEquals(listOf(Service.PEACOCK), Broadcasters.services(bare("eng.1").copy(broadcasts = listOf("NBC"))))
    }

    @Test fun theTableCoversFixturesWithoutABroadcaster() {
        assertEquals(listOf(Service.PARAMOUNT), Broadcasters.services(bare("uefa.champions")))
        assertEquals(listOf(Service.ESPN), Broadcasters.services(bare("esp.1")))
        assertEquals(listOf(Service.PEACOCK, Service.YOUTUBE_TV), Broadcasters.services(bare("eng.1")))
        assertEquals(listOf(Service.APPLE_TV), Broadcasters.services(bare("f1", Sport.F1, "f1:2026:20:race")))
        assertEquals(listOf(Service.WILLOW, Service.HOTSTAR), Broadcasters.services(bare(Teams.IPL, Sport.CRICKET)))
        assertEquals(listOf(Service.HOTSTAR, Service.WILLOW), Broadcasters.services(bare(Teams.IPL, Sport.CRICKET), india = true))
        assertEquals(emptyList<Service>(), Broadcasters.services(bare("nfl", Sport.NFL)))
        // Names ESPN uses that no app streams are ignored, so the table still answers.
        assertEquals(listOf(Service.ESPN), Broadcasters.services(bare("esp.1").copy(broadcasts = listOf("Some Local TV"))))
    }

    @Test fun onlyInstalledAppsAndTheMostUsedWins() {
        val psv = ucl.first { it.id == "401915398" }
        val both = setOf(PARAMOUNT, YTTV)::contains
        assertEquals(PARAMOUNT, Watch.choose(psv, both, emptyMap())!!.pkg)
        assertEquals(YTTV, Watch.choose(psv, both, mapOf(PARAMOUNT to 60_000L, YTTV to 9_000_000L))!!.pkg)
        assertEquals(PARAMOUNT, Watch.choose(psv, both, mapOf(PARAMOUNT to 9_000_000L, YTTV to 60_000L))!!.pkg)
        assertEquals(YTTV, Watch.choose(psv, setOf(YTTV)::contains, emptyMap())!!.pkg)
        assertNull(Watch.choose(psv, { false }, emptyMap()))
        // LaLiga: the ESPN app or Disney+'s ESPN tile, whichever he opens more.
        val laliga = bare("esp.1", id = "401882832")
        assertEquals("Watch on Disney+", Watch.choose(laliga, setOf(DISNEY)::contains, emptyMap())!!.line)
        assertEquals(DISNEY, Watch.choose(laliga, setOf(ESPN, DISNEY)::contains, mapOf(DISNEY to 5L, ESPN to 1L))!!.pkg)
        assertEquals("Watch on ESPN", Watch.choose(laliga, setOf(ESPN, DISNEY)::contains, emptyMap())!!.line)
    }

    @Test fun exactPageOnlyWhereItIsKnownElseTheLauncher() {
        val laliga = bare("esp.1", id = "401882832")
        val espn = Watch.choose(laliga, setOf(ESPN)::contains, emptyMap())!!
        assertEquals(listOf(WatchStep.Page("https://www.espn.com/soccer/match/_/gameId/401882832", ESPN), WatchStep.Launch(ESPN)), Watch.steps(espn))
        val nfl = Watch.choose(bills.first { it.id == "401872994" }, setOf(ESPN)::contains, emptyMap())!!
        assertEquals("https://www.espn.com/nfl/game/_/gameId/401872994", nfl.exact)
        // Disney+, Paramount+ and the rest: no event links are known, so the app opens.
        assertEquals(listOf(WatchStep.Launch(DISNEY)), Watch.steps(Watch.choose(laliga, setOf(DISNEY)::contains, emptyMap())!!))
        assertEquals(listOf(WatchStep.Launch(PARAMOUNT)), Watch.steps(Watch.choose(ucl.first(), setOf(PARAMOUNT)::contains, emptyMap())!!))
        // A Jolpica F1 id is not an ESPN game id.
        assertNull(Watch.page(bare("f1", Sport.F1, "f1:2026:20:race"), ESPN))
        assertNull(Watch.page(bare(Teams.IPL, Sport.CRICKET, "1527689"), ESPN))
    }

    @Test fun watchCardFromHalfAnHourBeforeAndTheChipUntilTheLeadIn() {
        val m = ucl.first { it.id == "401915403" }.copy(teams = setOf("bar"), phase = Phase.PRE, home = null, away = null)
        val s = OlympiaSnapshot(matches = listOf(m))
        val at = { before: Long -> Instant.ofEpochMilli(m.start - before).atZone(zone) }
        val pick: (Match) -> WatchChoice? = { Watch.choose(it, setOf(PARAMOUNT)::contains, emptyMap()) }
        assertFalse(Watch.due(m, m.start - 31 * MatchDay.MIN))
        assertTrue(Watch.due(m, m.start - 30 * MatchDay.MIN))
        assertFalse(Watch.due(m, m.start))
        assertEquals(m.start - Watch.BEFORE, Watch.nextWake(listOf(m), m.start - 2 * MatchDay.HOUR))
        assertNull(OlympiaCards.card(s, at(31 * MatchDay.MIN), pick))
        val card = OlympiaCards.card(s, at(20 * MatchDay.MIN), pick)!!.content
        assertEquals("Soon", card.status)
        assertTrue(card.body!!.endsWith("· Watch on Paramount+"))
        assertEquals(ActionRole.WATCH, card.actions.first().role)
        assertEquals(m.id, card.actions.first().payload)
        assertEquals(m.id, card.live!!.watch)
        assertEquals(m.start, card.live!!.countdownTo)
        // From the lead-in the live notice carries the chip.
        assertNull(OlympiaCards.card(s, at(3 * MatchDay.MIN), pick)!!.content.live)
        // In a game: the card waits on Home, no chip over the game.
        val gaming = OlympiaCards.card(s, at(20 * MatchDay.MIN), pick) { WatchQuiet(gaming = true) }!!.content
        assertNull(gaming.live)
        assertEquals(ActionRole.WATCH, gaming.actions.first().role)
        // No app for it on the phone: no Watch button, no "Watch on".
        val none = OlympiaCards.card(s, at(20 * MatchDay.MIN), watch = { null })!!.content
        assertTrue(none.actions.none { it.role == ActionRole.WATCH })
        assertFalse(none.body!!.contains("Watch on"))
        assertNull(none.live!!.watch)
        // During the match the live card has the same Watch action.
        val live = s.copy(live = mapOf(m.id to Live(m.copy(phase = Phase.LIVE, clock = "12'"))))
        assertEquals(ActionRole.WATCH, OlympiaCards.card(live, Instant.ofEpochMilli(m.start + 12 * MatchDay.MIN).atZone(zone), pick)!!.content.actions.first().role)
    }

    @Test fun quietRules() {
        assertTrue(WatchQuiet().buzz && WatchQuiet().promote)
        assertFalse(WatchQuiet(inCall = true).buzz)
        assertTrue(WatchQuiet(inCall = true).promote)
        assertFalse(WatchQuiet(driving = true).buzz)
        assertTrue(WatchQuiet(gaming = true).buzz)
        assertFalse(WatchQuiet(gaming = true).promote)
    }

    @Test fun bigMatchesOnlyInSportsHeFollowsAndOnlyWithTheSwitchOn() {
        val sb = Espn.scoreboard(read("nfl_scoreboard_superbowl.json"), Sport.NFL).single().copy(phase = Phase.PRE)
        val final = Espn.scoreboard(read("ucl_scoreboard_final.json"), Sport.SOCCER).single().copy(phase = Phase.PRE)
        assertEquals("post-season", sb.stage)
        assertEquals("final", final.stage)
        assertTrue(BigMatch.isBig(sb))
        assertTrue(BigMatch.isBig(final))
        assertFalse(BigMatch.isBig(ucl.first()))
        val clasico = Match("c", Sport.SOCCER, "esp.1", "LALIGA", 0L, Phase.PRE, "Real Madrid v Barcelona",
            home = Side("86", "Real Madrid", "RMA", null, true), away = Side("83", "Barcelona", "BAR", null, false))
        assertTrue(BigMatch.isBig(clasico))
        val st = OlympiaSettings()
        assertEquals(listOf(sb, final), BigMatch.pick(listOf(sb, final, ucl.first()), st))
        // Following no NFL team, or the NFL switch off: no Super Bowl card.
        assertEquals(listOf(final), BigMatch.pick(listOf(sb, final), st.copy(followed = st.followed - "buf")))
        assertEquals(listOf(final), BigMatch.pick(listOf(sb, final), st.copy(big = setOf(Sport.SOCCER))))
        // A Clásico he follows has its own card, so it isn't a big-match card too.
        assertEquals(emptyList<Match>(), BigMatch.pick(listOf(clasico.copy(teams = setOf("bar"))), st))
        assertEquals(listOf(clasico), BigMatch.pick(listOf(clasico), st.copy(followed = st.followed - "bar")))
        // Switched-off competition.
        assertEquals(emptyList<Match>(), BigMatch.pick(listOf(final), st.copy(competitions = st.competitions - Competition.CHAMPIONS)))
    }

    @Test fun bigMatchCardIsQuietAndTonightOnly() {
        val t = Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()
        val big = Match("c", Sport.SOCCER, "esp.1", "LALIGA", t, Phase.PRE, "Real Madrid v Atlético", home = Side("86", "Real Madrid", "RMA", null, true), away = Side("1068", "Atlético", "ATM", null, false))
        val s = OlympiaSnapshot(big = listOf(big))
        val morning = Instant.ofEpochMilli(t - 6 * MatchDay.HOUR).atZone(zone)
        val card = OlympiaCards.card(s, morning, watch = { Watch.choose(it, setOf(ESPN)::contains, emptyMap()) })!!.content
        assertEquals("Tonight", card.status)
        assertTrue(card.body!!.startsWith("Big match tonight · LALIGA"))
        assertNull(card.live)
        assertEquals(ActionRole.WATCH, card.actions.first().role)
        assertNull(BigMatch.tonight(listOf(big), Instant.ofEpochMilli(t - 26 * MatchDay.HOUR).atZone(zone)))
        assertNull(BigMatch.tonight(listOf(big), Instant.ofEpochMilli(t + MatchDay.MIN).atZone(zone)))
        assertNotNull(BigMatch.tonight(listOf(big), Instant.ofEpochMilli(t - MatchDay.HOUR).atZone(zone)))
    }

    @Test fun broadcastsBigMatchesAndSwitchesSurviveTheRoundTrip() {
        val psv = ucl.first { it.id == "401915398" }
        val s = OlympiaSnapshot(settings = OlympiaSettings(big = setOf(Sport.NFL)), matches = listOf(psv), big = listOf(psv))
        val back = Codec.snapshot(Codec.json(s))!!
        assertEquals(psv.broadcasts, back.matches.single().broadcasts)
        assertEquals("league-phase", back.matches.single().stage)
        assertEquals(listOf(psv.id), back.big.map { it.id })
        assertEquals(setOf(Sport.NFL), back.settings.big)
        // Older snapshots without the switch get the default.
        assertEquals(OlympiaSettings().big, Codec.snapshot("""{"fo":["bar"]}""")!!.settings.big)
    }
}
