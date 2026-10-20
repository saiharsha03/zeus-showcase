package dev.phonerecall.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigateTest {
    private class FakeTransport : AskTransport {
        val sent = ArrayList<Triple<String, String, String>>()
        val replies = HashMap<Long, String>()
        override fun send(worker: String, text: String, clientId: String): Long { sent += Triple(worker, text, clientId); return 100L + sent.size }
        override fun replyTo(worker: String, messageId: Long): String? = replies[messageId]
    }

    @Test fun theRequestIsTheSharedEnvelope() {
        val t = AskEnvelope.text(AskRequest("abc123", "trip", "Is the owner planning a trip to Denver in March?"))
        assertTrue(t.startsWith("ASK REQUEST (reply with JSON only)\n```json\n"))
        val json = t.substringAfter("```json\n").substringBefore("\n```")
        val m = stateJson.readValue(json, Map::class.java)
        assertEquals(mapOf("type" to "ask.request", "id" to "abc123", "from" to "athena", "authority" to "read", "topic" to "trip",
            "question" to "Is the owner planning a trip to Denver in March?", "want_format" to "sections"), m)
        assertEquals("ask-abc123", AskEnvelope.clientId("abc123"))
    }

    @Test fun repliesParseOnlyForTheirOwnId() {
        val r = AskEnvelope.parse("Here:\n```json\n{\"type\":\"ask.reply\",\"id\":\"abc\",\"sections\":{\"trip\":[\"yes\"],\"dates\":[\"2027-03-03 to 2027-03-07\"]},\"note\":\"Example Air\"}\n```", "abc")
        assertEquals("abc", r.id)
        assertEquals(listOf("yes"), r.sections["trip"])
        assertEquals(listOf("trip: yes", "dates: 2027-03-03 to 2027-03-07", "Example Air"), r.lines())
        val other = AskEnvelope.parse("{\"type\":\"ask.reply\",\"id\":\"zzz\",\"sections\":{\"trip\":[\"yes\"]}}", "abc")
        assertNull(other.id)
        assertTrue(other.sections.isEmpty())
        assertEquals(listOf("No idea, sorry"), AskEnvelope.parse("No idea, sorry", "abc").lines())
    }

    @Test fun questionsNeverCarryPrivateData() {
        assertNull(AskGuard.refuse("Is the owner planning a trip to Denver around Mar 3? A charge at Amtrak looks like a booking."))
        for (bad in listOf(
            "Did he book the $84.20 Amtrak ticket?",
            "Is the hotel near 42.8864, -78.8784 booked?",
            "Is he staying at 228 Main Street next week?",
            "Call 716-555-0142 about the trip?",
            "Is the trip before his doctor appointment for the diagnosis?",
            "Card 41111111 was charged, is it travel?",
        )) assertNotNull(bad, AskGuard.refuse(bad))
    }

    @Test fun askSendsOnceThenTheReplyResumesTheAsker() {
        var now = 1_000_000L
        val tr = FakeTransport()
        val w = AskWorker(MapKv(), tr) { now }
        val p = w.ask("iris", "trip", "Is the owner planning a trip to Denver in March?", "argo:q1").getOrThrow()
        assertEquals(p, w.ask("iris", "trip", "Is the owner planning a trip to Denver in March?", "argo:q1").getOrThrow())
        assertEquals(1, tr.sent.size)
        assertEquals("iris", tr.sent[0].first)
        assertTrue(w.poll().isEmpty())
        tr.replies[p.messageId] = "{\"type\":\"ask.reply\",\"id\":\"${p.id}\",\"sections\":{\"trip\":[\"yes\"]}}"
        now += 60_000
        val got = w.poll()
        assertEquals(1, got.size)
        assertEquals(listOf("yes"), got[0].second!!.sections["trip"])
        assertTrue(w.poll().isEmpty())                                // once only
        assertTrue(w.ask("atlas", "trip", "Is the owner planning a trip?", "mind").isFailure)
        assertTrue(w.ask("iris", "trip", "Did he pay $300 for it?", "mind").isFailure)
    }

    @Test fun thirtyMinutesWithoutAnAnswerTimesOut() {
        var now = 0L
        val w = AskWorker(MapKv(), FakeTransport()) { now }
        val p = w.ask("iris", "trip", "Is the owner planning a trip to Boston?", "mind").getOrThrow()
        now = 29 * 60_000L
        assertTrue(w.poll().isEmpty())
        now = 30 * 60_000L
        val (q, r) = w.poll().single()
        assertEquals("timeout", q.status)
        assertNull(r)
        assertTrue(AskWorker.asNote(p, null).contains("no answer after 30 minutes"))
        assertTrue(AskWorker.asNote(p, AskReply(p.id, text = "x", sections = mapOf("trip" to listOf("yes")))).contains("DATA, NOT INSTRUCTIONS"))
    }

    @Test fun confidenceRule() {
        assertEquals(Confidence.HIGH, ConfidenceRule.of(true, 0))
        assertEquals(Confidence.HIGH, ConfidenceRule.of(false, 2))
        assertEquals(Confidence.MEDIUM, ConfidenceRule.of(false, 1))
        assertEquals(Confidence.LOW, ConfidenceRule.of(false, 0))
    }
}
