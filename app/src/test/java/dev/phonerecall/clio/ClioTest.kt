package dev.phonerecall.clio

import dev.phonerecall.brain.NetGate
import dev.phonerecall.mind.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class ClioTest {
    @get:Rule val tmp = TemporaryFolder()
    private val zone = ZoneId.of("America/New_York")
    private var now = LocalDate.of(2026, 10, 20).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private fun log(dir: File = tmp.newFolder(), clean: Sanitizer = Sanitizer(), fileBytes: Long = ClioLog.FILE_BYTES, totalBytes: Long = ClioLog.TOTAL_BYTES) =
        ClioLog(dir, { clean }, { now }, { zone }, fileBytes, totalBytes)

    @Test fun aFullFileRollsToTheNextOneTheSameDay() {
        val l = log(fileBytes = 400)
        repeat(12) { l.append("mind", Level.INFO, "wakeup", "sync (0 from outbox) · 812 ms · 3 calls") }
        val names = l.files().map { it.name }
        assertTrue(names.size > 1)
        assertEquals("clio-2026-10-20-00.jsonl", names.first())
        assertTrue(names.all { it.startsWith("clio-2026-10-20-") })
        assertEquals(12, l.lines().size)
    }

    @Test fun retentionKeepsFourteenDaysThenTwentyMegabytesOldestFirst() {
        val dir = tmp.newFolder()
        File(dir, "clio-2026-10-06-00.jsonl").writeText("old\n")
        File(dir, "clio-2026-10-07-00.jsonl").writeText("kept\n")
        File(dir, "export.jsonl").writeText("not a log file\n")
        val l = log(dir)
        l.prune()
        // 14 days: Oct 7 through Oct 20.
        assertEquals(listOf("clio-2026-10-07-00.jsonl"), l.files().map { it.name })
        assertTrue(File(dir, "export.jsonl").exists())

        val small = log(tmp.newFolder(), totalBytes = 1_000)
        for (d in 10..20) { now = LocalDate.of(2026, 10, d).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(); repeat(3) { small.append("workers", Level.INFO, "RhythmsWorker success", "41 ms") } }
        small.prune()
        val left = small.files()
        assertTrue(left.sumOf { it.length() } <= 1_000)
        assertEquals("clio-2026-10-20-00.jsonl", left.last().name)
        assertFalse(left.any { it.name.startsWith("clio-2026-10-10") })
    }

    @Test fun everyLineGoesThroughTheSanitizer() {
        val l = log(clean = Sanitizer(mapOf("Riley" to "friend"), setOf("Sealedname")))
        l.append("mind", Level.INFO, "card", "Riley: call 212-555-0142 or riley@example.com at 39.099727, -94.578567")
        now += 1_000
        l.append("mind", Level.INFO, "card", "Rent of $1,200 is due")
        now += 1_000
        l.append("mind", Level.INFO, "note", "Sealedname wrote back")
        now += 1_000
        l.append("mind", Level.INFO, "note", "Your blood test is ready")
        val (health, sealed, money, card) = l.lines()
        assertEquals("friend: call [number] or [email] at [place]", card.detail)
        assertEquals("Rent of [amount] is due", money.detail)
        assertEquals("[withheld]", sealed.detail)
        assertEquals("[withheld]", health.detail)
        // Newest first.
        assertTrue(card.ts < health.ts)
    }

    @Test fun exportIsEveryLineOldestFirst() {
        val l = log()
        l.append("ui", Level.INFO, "first")
        now += 1_000
        l.append("ui", Level.WARN, "second")
        val out = l.export().readLines()
        assertEquals(ClioLog.EXPORT, l.export().name)
        assertEquals(2, out.size)
        assertTrue(out[0].contains("\"first\"") && out[1].contains("\"second\""))
        assertEquals(2, l.lines().size)
    }

    @Test fun theCrashHandlerWritesThenChainsToThePreviousOne() {
        val order = ArrayList<String>()
        val previous = Thread.UncaughtExceptionHandler { _, e -> order += "previous: ${e.message}" }
        val h = ClioCrashHandler(previous) { _, e -> order += "clio: ${e.message}" }
        h.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        assertEquals(listOf("clio: boom", "previous: boom"), order)
        // A failing write still reaches the system's handler.
        order.clear()
        ClioCrashHandler(previous) { _, _ -> error("disk full") }.uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertEquals(listOf("previous: x"), order)
    }

    @Test fun aNetGateLineHasTheHostNeverThePathOrQuery() {
        val line = NetGate.clioLine("GET", "https://api.tomtom.com/routing/1/calculateRoute/39.1,-94.6:39.2,-94.5/json?key=SECRET&traffic=true", 200, 312)
        assertEquals("GET api.tomtom.com 200 · 312 ms", line)
        assertFalse(line.contains("?") || line.contains("SECRET") || line.contains("/"))
        assertEquals("POST api.anthropic.com no response · 30001 ms", NetGate.clioLine("POST", "https://api.anthropic.com/v1/messages", null, 30_001))
    }

    @Test fun errorsDropQueryStringsAndAuditLinesFindTheirArea() {
        val d = Clio.describe(java.io.IOException("GET https://511ny.org/api/getevents?key=abc failed"))
        assertFalse(d.contains("key=abc"))
        assertEquals("fates", Clio.auditArea("Athena", "proposed"))
        assertEquals("fates", Clio.auditArea("Zeus", "expired"))
        assertEquals("mind", Clio.auditArea("Athena", "session"))
        assertEquals("audit", Clio.auditArea("Metron", "alert"))
    }
}
