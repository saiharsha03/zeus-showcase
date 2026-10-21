package dev.phonerecall.prometheus

import dev.phonerecall.clio.ClioLine
import dev.phonerecall.clio.Level
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.MindEvent
import dev.phonerecall.mind.Rig
import dev.phonerecall.mind.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClioEvidenceTest {
    private val now = 1_792_000_000_000L
    private val hour = 3_600_000L
    private val clean = Sanitizer(mapOf("Alex" to "friend"), setOf("Vault"))

    private val crash = """
        java.lang.IllegalStateException: Alex's number is 716-555-0142
        	at dev.phonerecall.mind.Mind.drive(Mind.kt:412)
        	at android.os.Handler.dispatchMessage(Handler.java:106)
        	at java.lang.Object.wait(Native method)
        Caused by: java.io.IOException: sent to alex@example.com
        	... 12 more
    """.trimIndent()

    private val anr = """
        user request after error: Input dispatching timed out
        "main" prio=5 tid=1 Blocked
        | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72a3d2f0 self=0xb400
        at dev.phonerecall.odyssey.OdysseyStore.load(Store.kt:27)
        - waiting to lock <0x0f1e2d3c> (a java.lang.Object) held by thread 23
    """.trimIndent()

    private val stall = "150 samples. Ours: Sanitizer.line:59 80%; MindTools.specs:127 10%\nMost common (40x):\nSanitizer.line:59\nRegex.replace:-2"

    private fun line(ts: Long, area: String, event: String, detail: String = "", level: Level = Level.ERROR) = ClioLine(ts, area, level, event, detail)

    // ---- the attachment ----

    @Test fun framesKeepCodeAndDropEveryMessage() {
        assertEquals(
            listOf(
                "java.lang.IllegalStateException",
                "at dev.phonerecall.mind.Mind.drive(Mind.kt:412)",
                "at android.os.Handler.dispatchMessage(Handler.java:106)",
                "at java.lang.Object.wait(Native method)",
                "Caused by: java.io.IOException",
                "... 12 more",
            ).joinToString("\n"),
            ClioEvidence.frames(crash),
        )
        assertEquals(
            listOf("\"main\" prio=5 tid=1 Blocked", "at dev.phonerecall.odyssey.OdysseyStore.load(Store.kt:27)", "- waiting to lock <0x0f1e2d3c> (a java.lang.Object) held by thread 23").joinToString("\n"),
            ClioEvidence.frames(anr),
        )
        assertEquals(stall, ClioEvidence.frames(stall))
        assertEquals("", ClioEvidence.frames("Amma said hi\nsee you at 5"))
    }

    @Test fun attachmentIsTheLast48HoursOfCrashAndUiOnlySanitized() {
        val lines = listOf(
            line(now - hour, "crash", "crash on main", crash),
            line(now - 2 * hour, "ui", "main stalled 1200 ms", stall, Level.WARN),
            line(now - 3 * hour, "mind", "turn failed", "Alex asked about Vault"),
            line(now - 49 * hour, "crash", "ANR", anr),
            line(now - 4 * hour, "crash", "crash on Vault worker", crash),
        )
        val a = ClioEvidence.attachment(lines, now, clean)
        assertEquals(2, a.size)                                                  // mind is not a stack; 49 h is too old; Vault takes the whole line
        assertTrue(a[0].startsWith("${java.time.Instant.ofEpochMilli(now - hour)} crash error crash on main\n"))
        assertTrue(a[1].contains("ui warn main stalled 1200 ms"))
        val all = a.joinToString("\n")
        for (bad in listOf("Alex", "716", "example.com", "number", "Input dispatching")) assertFalse(bad, all.contains(bad))
    }

    @Test fun anEntrySentFromClioGoesFirstEvenWhenOld() {
        val old = line(now - 10 * 24 * hour, "crash", "ANR", anr)
        val a = ClioEvidence.attachment(listOf(line(now - hour, "ui", "main stalled 900 ms", stall, Level.WARN), old), now, clean, pinned = old)
        assertEquals(2, a.size)
        assertTrue(a[0].contains("crash error ANR"))
        assertEquals(1, ClioEvidence.attachment(listOf(old), now, clean, pinned = old).size)        // never twice
    }

    @Test fun attachmentIsCapped() {
        val many = (1..40).map { line(now - it * 60_000L, "crash", "crash on main", crash) }
        assertEquals(ClioEvidence.ENTRIES, ClioEvidence.attachment(many, now, clean).size)
    }

    @Test fun theRequestCarriesTheStacksAsData() {
        val api = FakeForgeApi()
        Forge(api, MapKv()).start(CloudChange(id = "c1", problem = "Fix the freeze", nonce = "n1"), "tok", emptyList(), 500, listOf("2026-10-20T10:00:00Z ui warn main stalled\nSanitizer.line:59"))
        assertTrue(api.sent.any { it.contains("<stack>\n2026-10-20T10:00:00Z ui warn main stalled\nSanitizer.line:59\n</stack>") })
    }

    // ---- read_clio ----

    private val open = CloudChange(id = "c2", stage = CloudStage.WORKING, sessionId = "sesn_1")
    private val book = listOf(
        line(now - hour, "netgate", "request", "api.github.com", Level.INFO),
        line(now - 2 * hour, "mind", "turn failed", "budget", Level.WARN),
        line(now - 3 * hour, "crash", "crash on main", crash),
        line(now - 4 * hour, "places", "visit started", "Near Elm St"),
        line(now - 5 * hour, "mind", "Alex wrote", "call him", Level.WARN),
        line(now - 3 * 24 * hour, "mind", "old warning", "", Level.WARN),
    )
    private fun read(from: Long) = book.filter { it.ts >= from }

    @Test fun readClioFiltersAndSanitizes() {
        val warn = ClioEvidence.answer(open, mapOf("level" to "warn"), now, clean, ::read)
        assertFalse(warn.error)
        assertEquals(3, warn.lines)                                              // not info, not places, not older than 48 h
        assertFalse(warn.text.contains("Alex"))
        assertTrue(warn.text.contains("mind warn friend wrote"))
        assertFalse(warn.text.contains("716"))                                   // the crash's message is gone, its frames are there
        assertTrue(warn.text.contains("at dev.phonerecall.mind.Mind.drive(Mind.kt:412)"))

        assertEquals(1, ClioEvidence.answer(open, mapOf("area" to "netgate"), now, clean, ::read).lines)
        assertEquals(1, ClioEvidence.answer(open, mapOf("query" to "BUDGET"), now, clean, ::read).lines)
        // A query only sees sanitized text, so it can't probe for a scrubbed name.
        assertEquals(0, ClioEvidence.answer(open, mapOf("query" to "Alex"), now, clean, ::read).lines)
        assertEquals(4, ClioEvidence.answer(open, mapOf("from" to java.time.Instant.ofEpochMilli(now - 4 * 24 * hour).toString(), "to" to java.time.Instant.ofEpochMilli(now - 90 * 60_000L).toString()), now, clean, ::read).lines)
        assertEquals(1, ClioEvidence.answer(open, mapOf("limit" to 1), now, clean, ::read).lines)
        assertTrue(ClioEvidence.answer(open, mapOf("area" to "places"), now, clean, ::read).error)
        assertTrue(ClioEvidence.answer(open, mapOf("from" to "yesterday"), now, clean, ::read).error)
        assertTrue(ClioEvidence.answer(open, mapOf("level" to "debug"), now, clean, ::read).error)
    }

    @Test fun readClioIsCappedAndBounded() {
        val q = ClioEvidence.query(mapOf("limit" to 10_000, "from" to "2020-01-01", "to" to "2099-01-01"), now)
        assertEquals(ClioEvidence.LIMIT, q.limit)
        assertEquals(now - ClioEvidence.KEEP_MS, q.from)
        assertEquals(now, q.to)
        val big = (1..400).map { line(now - it * 1000L, "workers", "w", "x".repeat(230), Level.INFO) }
        val a = ClioEvidence.answer(open, mapOf("limit" to 500), now, clean) { big }
        assertTrue(a.text.length <= ClioEvidence.CHARS + 200)
        assertTrue(a.lines < 400)
        assertTrue(a.text.endsWith("narrow the filter)"))
    }

    @Test fun readClioIsRefusedOnceTheRequestIsDone() {
        for (stage in listOf(CloudStage.MERGED, CloudStage.CLOSED, CloudStage.CANCELLED, CloudStage.FAILED, CloudStage.RELEASING, CloudStage.INSTALLED)) {
            val a = ClioEvidence.answer(open.copy(stage = stage), emptyMap(), now, clean, ::read)
            assertTrue(stage.name, a.error && a.text.startsWith("Refused"))
        }
        assertFalse(ClioEvidence.answer(open.copy(stage = CloudStage.PR_OPEN), emptyMap(), now, clean, ::read).error)
    }

    @Test fun forgeAnswersOnlyPendingCallsAndRefusesUnknownTools() {
        val api = FakeForgeApi()
        val forge = Forge(api, MapKv())
        val events = listOf(
            MindEvent.CustomToolUse("evt_1", 1, ClioEvidence.TOOL, mapOf("area" to "netgate")),
            MindEvent.ToolResultSent("evt_2", 2, "evt_1"),
            MindEvent.CustomToolUse("evt_3", 3, ClioEvidence.TOOL, mapOf("level" to "error")),
            MindEvent.CustomToolUse("evt_4", 4, "send_sms", emptyMap()),
        )
        val sent = forge.serve(open, events, now, clean, ::read)
        assertEquals(listOf("evt_3", "evt_4"), api.results.map { it.first })
        assertFalse(api.results[0].third)
        assertTrue(api.results[1].third)
        assertEquals(2, sent.size)
        forge.serve(open.copy(stage = CloudStage.MERGED), events.take(3), now, clean, ::read)
        assertTrue(api.results.last().third && api.results.last().second.startsWith("Refused"))
        assertTrue(forge.serve(open.copy(sessionId = null), events, now, clean, ::read).isEmpty())
    }

    // ---- the tool lives on Prometheus only ----

    @Test fun readClioIsRegisteredOnlyForPrometheus() {
        val api = FakeForgeApi()
        Forge(api, MapKv()).agent()
        assertEquals(listOf(ClioEvidence.TOOL), api.specs.single().tools.map { it.name })
        val mind = Rig().tools
        assertFalse(mind.specs(circle = false).any { it.name == ClioEvidence.TOOL })
        assertFalse(mind.specs(circle = true).any { it.name == ClioEvidence.TOOL })
        assertEquals(false, ClioEvidence.SPEC.schema["additionalProperties"])
    }

    @Test fun toolsChangeTheAgentsHash() {
        val base = CodeAgentSpec("n", "m", "d", "s")
        assertFalse(base.hash == base.copy(tools = listOf(ClioEvidence.SPEC)).hash)
    }

    // ---- Send to Prometheus from Clio ----

    @Test fun sendToPrometheusPrefillsFromTheEntry() {
        assertEquals("Fix: crash on main at Mind.drive:412", ClioEvidence.prefill(line(now, "crash", "crash on main", crash)))
        assertEquals("Fix: ANR at OdysseyStore.load:27", ClioEvidence.prefill(line(now, "crash", "ANR", anr)))
        assertEquals("Fix: main stalled 1200 ms at Sanitizer.line:59", ClioEvidence.prefill(line(now, "ui", "main stalled 1200 ms", stall)))
        assertEquals("Fix: ANR", ClioEvidence.prefill(line(now, "crash", "ANR", "no trace")))
    }

    @Test fun onlyStackEntriesCanBeSentAndTheDraftIsTakenOnce() {
        PrometheusDraft.take()
        PrometheusDraft.send(line(now, "mind", "turn failed"))
        assertNull(PrometheusDraft.take())
        val l = line(now, "ui", "main stalled 900 ms", stall, Level.WARN)
        PrometheusDraft.send(l)
        assertEquals(l, PrometheusDraft.take())
        assertNull(PrometheusDraft.take())
    }
}
