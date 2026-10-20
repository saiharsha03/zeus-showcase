package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

class TriageTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 19, h, m, 0, 0, ny)

    @Test fun quietHoursLetOnlyReplyNowThrough() {
        assertTrue(QuietHours.quiet(at(23)))
        assertTrue(QuietHours.quiet(at(6, 59)))
        assertFalse(QuietHours.quiet(at(7)))
        assertTrue(QuietHours.breaksThrough(Tier.REPLY_NOW, Importance.HIGH, at(2)))
        assertFalse(QuietHours.breaksThrough(Tier.TODAY, Importance.HIGH, at(2)))
        assertFalse(QuietHours.breaksThrough(Tier.REPLY_NOW, Importance.MEDIUM, at(2)))
        // Outside quiet hours the source app already rings; Zeus doesn't add a second ping.
        assertFalse(QuietHours.breaksThrough(Tier.REPLY_NOW, Importance.HIGH, at(15)))
    }

    @Test fun haikuAtMostOncePer15MinutesAndOnlyWhenSomethingWaits() {
        val t = 1_792_000_000_000L
        assertFalse(TriageBatch.due(0, null, t))
        assertTrue(TriageBatch.due(1, null, t))
        assertFalse(TriageBatch.due(3, t, t + 14 * 60_000L))
        assertTrue(TriageBatch.due(3, t, t + 15 * 60_000L))
        assertEquals(60_000L, TriageBatch.delay(t, t + 14 * 60_000L))
        assertEquals(0L, TriageBatch.delay(null, t))
    }

    @Test fun requestsBecomeTasks() {
        assertEquals("Send the report", Requests.title("can you send the report by EOD"))
        assertEquals("Send the slides over", Requests.title("Hey, could you send the slides over by 3pm?"))
        assertEquals("Call the landlord", Requests.title("please call the landlord tomorrow"))
        assertNull(Requests.title("can you?"))
        assertEquals("2026-10-19T17:00", Requests.due("eod", at(15)))
        assertEquals("2026-10-19T21:00", Requests.due("eod", at(18)))
        assertEquals("2026-10-20", Requests.due("tomorrow", at(15)))
        assertEquals("2026-10-19T15:00", Requests.due(CueReader.read("by 3pm").deadline, at(9)))
        assertNull(Requests.due(null, at(9)))
    }

    @Test fun rulesFirst() {
        val amma = Person("k", "Amma", Relation.MOTHER)
        assertEquals(Importance.HIGH, Rules.triage(AppKind.DIALER, Who("Amma", missedCall = true), amma, Cues(), false).importance)
        assertEquals(Importance.HIGH, Rules.triage(AppKind.CHAT, Who("Amma"), amma, Cues(), false).importance)
        assertEquals(Importance.NOISE, Rules.triage(AppKind.OTHER, null, null, CueReader.read("50% off everything, today only"), false).importance)
        assertEquals(Importance.MEDIUM, Rules.triage(AppKind.OTHER, null, null, CueReader.read("Your package was delivered"), false).importance)
        assertEquals(Importance.HIGH, Rules.triage(AppKind.OTHER, null, null, CueReader.read("New sign-in on Windows"), false).importance)
        val muted = Person("k", "Group bot", pinned = Tier.MUTED)
        assertEquals(Importance.NOISE, Rules.triage(AppKind.CHAT, Who("Group bot"), muted, Cues(), false).importance)
        // A stranger asking something: the rules can't tell, and it looks worth a model.
        val v = Rules.triage(AppKind.SMS, Who("+1 716 555 0100"), null, CueReader.read("Can you call me back about the lease?"), false)
        assertTrue(v.needsModel && v.looksImportant)
    }

    @Test fun parsesModelVerdicts() {
        val v = TriagePrompt.parse("""Here: [{"i":1,"importance":"high","card":"wants a reply soon"},{"i":2,"importance":"noise"},{"i":"x"}]""")
        assertEquals(listOf(ModelVerdict(1, Importance.HIGH, "wants a reply soon"), ModelVerdict(2, Importance.NOISE, null)), v)
        assertTrue(TriagePrompt.parse("not json").isEmpty())
    }
}

/** Notification cards, the request proposal, quiet hours and the background batch, end to end with fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SenseFlowTest {
    @Test fun ammaCalledTwice() {
        val rig = EngineRig()
        rig.store.savePerson(Person(People.keyOf("Amma"), "Amma", Relation.MOTHER, confirmed = true, asked = true))
        rig.engine.ingest(rig.note("com.google.android.dialer", "Missed call", "Amma", key = "c1"))
        rig.now += 20 * 60_000L
        rig.engine.ingest(rig.note("com.google.android.dialer", "Missed call", "Amma", key = "c2"))
        val card = rig.store.cards(rig.now).single()
        assertEquals("Amma called twice", card.title)
        assertTrue(rig.effects.nudges.isEmpty())          // 3 PM: not quiet hours
    }

    @Test fun replyNowBreaksThroughQuietHoursOthersWait() {
        val rig = EngineRig(ZonedDateTime.of(2026, 10, 19, 23, 30, 0, 0, ZoneId.of("America/New_York")))
        rig.store.savePerson(Person(People.keyOf("Amma"), "Amma", Relation.MOTHER, confirmed = true, asked = true))
        rig.store.savePerson(Person(People.keyOf("Alex"), "Alex", Relation.FRIEND, confirmed = true, asked = true))
        rig.engine.ingest(rig.note("com.google.android.dialer", "Missed call", "Amma", key = "c1"))
        rig.engine.ingest(rig.note("com.whatsapp", "Alex", "you up?", key = "r1"))
        assertEquals(listOf("Amma called"), rig.effects.nudges)
    }

    @Test fun managerAsksForTheReport() {
        val rig = EngineRig()
        rig.store.savePerson(Person(People.keyOf("Taylor"), "Taylor", Relation.MANAGER, confirmed = true, asked = true, work = true))
        rig.engine.ingest(rig.note("com.microsoft.teams", "Taylor", "can you send the report by EOD", key = "t1"))
        val p = rig.pending().single()
        val step = p.steps.single()
        assertEquals(ProposalKind.ADD_TASK, step.kind)
        assertEquals("Send the report", step.payload["title"])
        assertEquals("2026-10-19T17:00", step.payload["due"])
        assertEquals("Taylor: can you send the report by EOD", p.title)
        // The same ask again doesn't make a second card.
        rig.engine.ingest(rig.note("com.microsoft.teams", "Taylor", "can you send the report by EOD", key = "t2").copy(at = rig.now + 1))
        assertEquals(1, rig.pending().size)
    }

    @Test fun backgroundBatchSendsDerivedLinesOnly() {
        val rig = EngineRig()
        rig.cloud = FakeCloud { """[{"i":1,"importance":"medium","card":"asks about the lease"}]""" }
        rig.engine.ingest(rig.note("com.google.android.apps.messaging", "+1 716 555 0100", "Can you call me back about the lease? Thanks, Maria", key = "s1"))
        assertEquals(listOf(0L), rig.effects.triage)       // scheduled, nothing sent yet
        assertTrue(rig.cloud!!.prompts.isEmpty())
        assertEquals(1, rig.engine.triageBatch())
        val sent = rig.cloud!!.prompts.single()
        assertFalse(sent, sent.contains("lease"))
        assertFalse(sent, sent.contains("Maria"))
        assertFalse(sent, sent.contains("716"))
        assertTrue(sent, sent.contains("cues: question, request"))
        assertEquals("Asks about the lease", rig.store.cards(rig.now).single().title)
        // Within 15 minutes, no second call even if more arrives.
        rig.engine.ingest(rig.note("com.google.android.apps.messaging", "+1 716 555 0199", "Could you confirm the time?", key = "s2"))
        assertEquals(0, rig.engine.triageBatch())
        assertEquals(1, rig.cloud!!.prompts.size)
        rig.now += 15 * 60_000L
        assertEquals(1, rig.engine.triageBatch())
    }

    @Test fun nanoDecidesInTheForeground() {
        val rig = EngineRig()
        rig.effects.onScreen = true
        rig.local.answer = { """[{"i":1,"importance":"noise"}]""" }
        rig.engine.ingest(rig.note("com.google.android.apps.messaging", "+1 716 555 0100", "Can you call me back?", key = "s1"))
        assertEquals("nano", rig.store.notes().single().by)
        assertTrue(rig.effects.triage.isEmpty())
    }
}
