package dev.phonerecall.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HeartbeatGateTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    private fun skip(t: ZonedDateTime, on: Boolean = true, spent: Long = 1_000, cap: Long = 10_000, day: Long = 0, lastReal: Long? = null, changed: Boolean = true) =
        HeartbeatGate.skip(t, on, spent, cap, day, lastReal) { changed }

    @Test fun quietHoursHaveNoBeats() {
        assertEquals("quiet hours", skip(at(6, 59)))
        assertEquals("quiet hours", skip(at(23, 0)))
        assertEquals("quiet hours", skip(at(2)))
        assertNull(skip(at(7)))
        assertNull(skip(at(22, 59)))
        assertEquals("off", skip(at(12), on = false))
    }

    @Test fun nothingChangedIsSilentUntilThreeHoursPass() {
        val noon = at(12)
        assertEquals("nothing new", skip(noon, lastReal = ms(noon.minusMinutes(170)), changed = false))
        assertNull(skip(noon, lastReal = ms(noon.minusHours(3)), changed = false))
        assertNull(skip(noon, lastReal = ms(noon.minusMinutes(30)), changed = true))
        // The very first beat runs even with nothing new.
        assertNull(skip(noon, lastReal = null, changed = false))
    }

    @Test fun budgetSkipsAtNinetyPercentOfTheMonthOrSixtyCentsToday() {
        assertEquals("month at 90% of the cap", skip(at(12), spent = 9_000, cap = 10_000))
        assertNull(skip(at(12), spent = 8_999, cap = 10_000))
        assertEquals("today's heartbeat budget used", skip(at(12), day = 60))
        assertNull(skip(at(12), day = 59))
    }

    @Test fun theDigestIsOnlyBuiltOnceTheCheapChecksPass() {
        var built = 0
        HeartbeatGate.skip(at(3), true, 0, 10_000, 0, null) { built++; true }
        HeartbeatGate.skip(at(12), true, 9_500, 10_000, 0, null) { built++; true }
        assertEquals(0, built)
    }

    @Test fun newNotesCountAsAChangeEvenWithTheSameState() {
        val d = HeartbeatDigest(calendar = listOf("2:00 PM: Standup"), jobs = listOf("job-1: tires (started)"))
        assertFalse(d.changedSince(d.fingerprint()))
        assertTrue(d.copy(notes = listOf("Arrived at Wegmans")).changedSince(d.fingerprint()))
        assertTrue(d.copy(calendar = listOf("3:00 PM: Dentist")).changedSince(d.fingerprint()))
    }
}

class HeartbeatTurnTest {
    private val digest = HeartbeatDigest(notes = listOf("2:05 PM: Iris answered about the oil change"), calendar = listOf("4:00 PM: Standup"))

    @Test fun heartbeatOkIsSilent() {
        val r = Rig()
        r.api.reply = "HEARTBEAT_OK"
        val res = r.mind.heartbeat(digest, r.now) { r.ledger.spent }
        assertTrue(res.silent)
        assertEquals("nothing to do", res.outcome)
        assertEquals(12, res.cents)
        assertTrue(r.sink.cards.isEmpty())
        assertTrue(r.store.all().isEmpty())
        // Not in her log either.
        assertTrue(r.mind.log().none { it.contains("HEARTBEAT_OK") })
        val sent = r.api.events.values.flatten().filterIsInstance<MindEvent.UserMessage>().single().text
        assertTrue(sent.startsWith("[Zeus] Heartbeat [hb"))
        assertTrue(sent.contains("reply exactly HEARTBEAT_OK") && sent.contains("Iris answered about the oil change"))
    }

    @Test fun okVariantsAreSilentButAToolCallIsNot() {
        assertTrue(HeartbeatPrompt.silent(listOf(" HEARTBEAT_OK. "), 0))
        assertTrue(HeartbeatPrompt.silent(listOf("`HEARTBEAT_OK`"), 0))
        assertTrue(HeartbeatPrompt.silent(emptyList(), 0))
        assertFalse(HeartbeatPrompt.silent(listOf("HEARTBEAT_OK"), 1))
        assertFalse(HeartbeatPrompt.silent(listOf("Standup moved; I proposed a reminder."), 0))
    }

    @Test fun somethingWorthDoingGoesThroughProposalsOnly() {
        val r = Rig()
        r.api.script = { "add_task" to mapOf("title" to "Book the oil change") }
        val res = r.mind.heartbeat(digest, r.now) { r.ledger.spent }
        assertFalse(res.silent)
        assertEquals("acted (1 tool call)", res.outcome)
        // A pending proposal, nothing executed.
        assertEquals(1, r.store.all().size)
        assertTrue(r.exec.ran.isEmpty())
    }

    @Test fun storeTracksTodaysSpendAndTheThemisLine() {
        val kv = MapKv()
        val s = HeartbeatStore(kv)
        val zone = ZoneId.of("America/New_York")
        val t = ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertTrue(s.on)
        assertEquals("Hourly, 7 AM to 11 PM · last: not yet", s.view(zone).line)
        s.real(t, "fp", 7, zone); s.real(t + 3_600_000L, "fp2", 5, zone); s.beat(t + 3_600_000L, "nothing to do")
        assertEquals(12, s.dayCents(zone, t))
        assertEquals(0, s.dayCents(zone, t + 86_400_000L))
        assertEquals("fp2", s.fingerprint)
        assertEquals("Hourly, 7 AM to 11 PM · last: 3:00 PM nothing to do", s.view(zone).line)
        s.on = false
        assertFalse(s.view(zone).on)
    }

    @Test fun notesSinceTheLastBeatAreKeptPastTheBatch() {
        val kv = MapKv()
        HeartbeatNotes.add(kv, MindNote(100, "Arrived at Wegmans", key = "place"))
        HeartbeatNotes.add(kv, MindNote(200, "Left Wegmans", key = "place"))
        HeartbeatNotes.add(kv, MindNote(300, "Trip over (Argo)"))
        assertEquals(listOf("Left Wegmans", "Trip over (Argo)"), HeartbeatNotes.since(kv, 0).map { it.text })
        assertEquals(listOf("Trip over (Argo)"), HeartbeatNotes.since(kv, 200).map { it.text })
    }
}
