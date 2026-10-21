package dev.phonerecall.shortcuts

import dev.phonerecall.brain.FakeSealer
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.mind.MapKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Athena decides what to offer; the phone gives her the evidence and keeps the hard rules. A fake Athena stands in for her. */
class ShortcutsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun day(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun kickoff(d: Int) = Seen(day(d, 15), "match:bar", "a Barcelona match starts")
    private fun paramount(d: Int, m: Int = 3) = Seen(day(d, 15, m), "open:com.cbs.app", "open Paramount+")
    private fun book() = ShortcutBook(MapKv(), FakeSealer()) { day(20, 3) }

    /** The fake Athena: offers what she judges worth it (here: seen on two or more days, or anything at the gym). */
    private fun fakeAthena(ev: List<Evidence>) = ev.filter { it.days >= 2 || it.trigger == "arrive:gym" }.map { Pick(it.trigger, it.action, "You do it ${it.days} of the last match days") }

    @Test fun theEvidenceHasCountsContextsAndLastSeen() {
        val ev = Learn.evidence(listOf(kickoff(4), kickoff(11)), listOf(paramount(4), paramount(4, 9), paramount(11), paramount(11, 40)), zone).single()
        assertEquals(3, ev.times)
        assertEquals(2, ev.days)
        assertEquals(listOf(15), ev.hours)
        assertEquals(day(11, 15), ev.lastSeen)
        // Not "right after", and never a message.
        assertTrue(Learn.evidence(listOf(kickoff(4)), listOf(paramount(4, 40)), zone).isEmpty())
        assertTrue(Learn.evidence(listOf(Seen(day(4, 17), "leave:work", "you leave")), listOf(Seen(day(4, 17, 2), "message:otw", "text otw")), zone).isEmpty())
    }

    @Test fun offersComeFromHerJudgementNotAThreshold() {
        val ev = Learn.evidence(
            listOf(kickoff(4), kickoff(11), Seen(day(12, 7), "arrive:gym", "you get to the gym")),
            listOf(paramount(4), paramount(11), Seen(day(12, 7, 1), "music:spotify:playlist:owner_1", "play owner_1")), zone,
        )
        val (ok, no) = Offer.shortcuts(fakeAthena(ev), ev, book().load())
        // Two days of Paramount and a single gym morning: both, because she chose them.
        assertEquals(setOf("match:bar>open:com.cbs.app", "arrive:gym>music:spotify:playlist:owner_1"), ok.map { it.second.key }.toSet())
        assertTrue(no.isEmpty())
        // What she makes up, or leaves without a why, doesn't stand.
        val (ok2, no2) = Offer.shortcuts(listOf(Pick("arrive:home", "open:x", "why"), Pick("match:bar", "open:com.cbs.app", "")), ev, book().load())
        assertTrue(ok2.isEmpty())
        assertEquals(2, no2.size)
    }

    @Test fun neverIsNeverOfferedAgainAndNothingSendsOrPays() {
        val b = book()
        val ev = Learn.evidence(listOf(kickoff(4), kickoff(11)), listOf(paramount(4), paramount(11)), zone)
        b.never(ev.single().key)
        val (ok, no) = Offer.shortcuts(fakeAthena(ev) + Pick("leave:work", "message:otw", "he always texts"), ev, b.load())
        assertTrue(ok.isEmpty())
        assertTrue(no.any { "Never" in it })
        assertTrue(no.any { "message" in it })
        // A rule already made isn't offered again either.
        val b2 = book()
        b2.accept(Seen(0, "match:bar", ""), Seen(0, "open:com.cbs.app", ""))
        assertTrue(Offer.shortcuts(fakeAthena(ev), ev, b2.load()).first.isEmpty())
    }

    @Test fun ideasAreFilteredAndRoutedAndANoIsRemembered() {
        val b = book()
        val ideas = listOf(
            Idea("Weekly recipe suggestions", "He cooks on Sundays"),                                         // killed: recipes
            Idea("Bills home-game traffic warning", "Sunday games"),                                         // killed
            Idea("Lights on when he gets home", "Dark by 6 PM now", "arrive:home", "you get home", "home:lamp:on", "turn on the lamp"),
            Idea("Laundry timer from the washer's sound", "He forgets the laundry", problem = "No way to know when the washer ends", acceptance = "A timer card starts when the washer chime is heard"),
            Idea("Text Amma when he lands", "He always does", "arrive:airport", "you land", "message:amma", "text Amma"),   // sends a message
            Idea("Track his runs", "He runs", problem = "Runs aren't kept"),                                  // no acceptance line
        )
        val (ok, no) = Offer.ideas(ideas, Killed.ALL, b.load())
        assertEquals(listOf("Lights on when he gets home", "Laundry timer from the washer's sound"), ok.map { it.title })
        assertTrue(ok[0].rule)
        assertFalse(ok[1].rule)
        assertEquals(4, no.size)
        // His No: never offered again, even reworded a little.
        b.decline("Laundry timer from the washer's sound")
        assertTrue(Offer.ideas(listOf(Idea("Laundry timer from washer sound", "again", problem = "p", acceptance = "a")), Killed.ALL, b.load()).first.isEmpty())
    }

    @Test fun theKilledAndDeclinedListsMatchTheBacklog() {
        // The backlog is a private doc; the public copy has none, so the check runs only where it exists.
        val backlog = java.io.File("../docs/BACKLOG.md")
        org.junit.Assume.assumeTrue("docs/BACKLOG.md is not in this copy", backlog.isFile)
        val lines = backlog.readLines()
        val line = lines.first { it.startsWith("- Killed, don't rebuild") }
        val added = line.substringAfter("additions: ").substringBefore("):").split(",").map { it.trim() }
        val items = line.substringAfter("):").trim().removeSuffix(".").split(",").map { it.trim() }
        assertEquals(added + items, Killed.ALL)
        val declined = lines[lines.indexOfFirst { it.startsWith("## Declined QoL ideas") } + 1]
        assertEquals(Regex(",\\s*(?![^()]*\\))").split(declined.trim().removeSuffix(".")).map { it.trim() }, Killed.DECLINED)
        // A backlog No counts like a No on a card.
        assertTrue(Offer.ideas(listOf(Idea("Workout cooldown timer", "after runs", problem = "p", acceptance = "a")), Killed.ALL, book().load()).first.isEmpty())
        assertTrue(Offer.ideas(listOf(Idea("Call screening for spam", "spam calls", problem = "p", acceptance = "a")), Killed.ALL, book().load()).first.isEmpty())
    }

    @Test fun aYesMakesARuleThatFiresOnItsTrigger() {
        val b = book()
        val r = b.accept(Seen(0, "arrive:gym", "you get to the gym"), Seen(0, "music:spotify:playlist:owner_1", "play owner_1", mapOf("uri" to "spotify:playlist:owner_1")))
        assertEquals(r.id, b.accept(Seen(0, "arrive:gym", ""), Seen(0, "music:spotify:playlist:owner_1", "")).id)
        assertEquals(1, b.rules().size)
        assertTrue(b.fire("arrive:home").isEmpty())
        assertEquals(listOf(r.id), b.fire("arrive:gym").map { it.id })
        assertEquals(1, b.rules().single().fired)
        // Music never runs on its own (Fates doesn't auto-approve it); an alarm may.
        assertFalse(b.setMode(r.id, Mode.AUTO))
        val alarm = b.accept(Seen(0, "arrive:home", "you get home"), Seen(0, "alarm:22:30", "set an alarm for 22:30", mapOf("hour" to "22", "minute" to "30")))
        assertTrue(b.setMode(alarm.id, Mode.AUTO))
    }

    @Test fun theBookIsSealedAndAnswersAreKeptPerRow() {
        val kv = MapKv()
        val b = ShortcutBook(kv, FakeSealer()) { day(20, 3) }
        b.accept(Seen(0, "arrive:gym", "you get to the gym"), Seen(0, "open:com.strava", "open Strava"))
        b.answer("7:0", "yes"); b.answer("7:1", "never"); b.answer("8:0", "no")
        assertEquals(mapOf(0 to "yes", 1 to "never"), b.answers(7))
        b.clearAnswers(7)
        assertTrue(b.answers(7).isEmpty())
        assertTrue(kv.map.values.none { "Strava" in it || "gym" in it })
    }

    @Test fun oneGroupedCardAndNeverAMessageOrPayment() {
        val r = dev.phonerecall.mind.Rig()
        fun step(action: String) = RawStep(ProposalKind.LEARN_SHORTCUT, mapOf("trigger" to "arrive:gym", "trigger_label" to "you get to the gym", "action" to action, "action_label" to "do it", "why" to "every time"))
        val card = r.fates.propose(listOf(step("open:com.strava"), step("music:spotify:playlist:owner_1")), "2 things", null)
        assertTrue(card is ProposeResult.Created)
        assertEquals(2, (card as ProposeResult.Created).proposal.steps.size)
        assertTrue(r.fates.propose(listOf(step("message:otw")), "x", null) is ProposeResult.Invalid)
        val idea = r.fates.propose(listOf(RawStep(ProposalKind.IDEA, mapOf("title" to "Laundry timer", "why" to "w", "problem" to "p", "acceptance" to "a"))), "x", null)
        assertNotNull((idea as ProposeResult.Created).proposal)
        assertFalse(ProposalKind.LEARN_SHORTCUT in ProposalKind.autoCandidates || ProposalKind.IDEA in ProposalKind.autoCandidates)
    }

    @Test fun herToolsAreHersAlone() {
        val r = dev.phonerecall.mind.Rig()
        assertFalse(r.tools.run(dev.phonerecall.mind.MindTools.READ_HABITS, emptyMap(), null, asCircle = false).isError)
        assertTrue(r.tools.run("propose_ideas", mapOf("ideas" to emptyList<Any>()), "t1", asCircle = true).isError)
        assertTrue(r.tools.specs(circle = true).none { it.name in dev.phonerecall.mind.MindTools.HABITS })
    }
}
