package dev.phonerecall.setup

import dev.phonerecall.mind.MindTools
import dev.phonerecall.mind.Rig
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.TestProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Made-up results for the report's shots and the summary. */
object ShakeFixtures {
    const val NOW = 1_792_000_000_000L

    private val fails = mapOf(
        "spotify" to "Spotify isn't connected",
        "iris-reply" to "Asked; no answer in 85 s",
        "tomtom" to "No TomTom key in Calypso",
    )
    private val skips = mapOf("scheduled" to "No scheduled run in the last day (last: daily-plan)", "chronos-event" to "Calendar writes aren't allowed")

    val results: List<ShakeResult> = Shakedown.CHECKS.filter { !it.manual }.map { c ->
        when (c.id) {
            in fails -> ShakeResult(c.id, Verdict.FAIL, fails.getValue(c.id), 1_200)
            in skips -> ShakeResult(c.id, Verdict.SKIP, skips.getValue(c.id))
            "tool-calendar" -> ShakeResult(c.id, Verdict.PASS, "Answered (812 characters)", claim = "pass: 3 events today")
            "notify-high" -> ShakeResult(c.id, Verdict.PASS, "Posted on Moneta: possible fraud. Did you see it? Answer below.")
            else -> ShakeResult(c.id, Verdict.PASS, "Fine", 300)
        }
    }

    val report = ShakeReport(
        "s1", NOW, 212_000, results,
        mapOf("notify-normal" to true, "notify-high" to true, "notify-birthday" to false, "notify-nowbar" to null),
        "44 of 49 passed; Spotify isn't connected, TomTom has no key, and Iris didn't answer in 85 s.", 23,
    )

    val before = report.copy(id = "s0", at = NOW - 86_400_000L, results = results.map { r ->
        when (r.id) { "spotify" -> r.copy(verdict = Verdict.PASS, detail = "Now playing"); "hestia" -> r.copy(verdict = Verdict.FAIL, detail = "Still connecting"); else -> r }
    })
}

class ShakedownTest {
    private fun step(id: String, block: suspend () -> String) = ShakeStep(id, run = block)
    private suspend fun run(steps: List<ShakeStep>, spent: () -> Long = { 0 }, clock: () -> Long = System::currentTimeMillis, cleanup: suspend () -> List<String> = { emptyList() }) =
        ShakeRunner.run(steps, spent, cleanup, clock)

    @Test fun runsInOrderAndTimesOutEachStep() = runBlocking {
        val order = ArrayList<String>()
        val out = run(listOf(
            step("a") { order += "a"; "fine" },
            ShakeStep("b", timeoutMs = 50) { order += "b"; delay(5_000); "late" },
            step("c") { order += "c"; throw TestFailed("broke") },
            step("d") { order += "d"; throw TestSkipped("not here") },
            step("e") { order += "e"; error("boom") },
        ))
        assertEquals(listOf("a", "b", "c", "d", "e"), order)
        assertEquals(listOf("a", "b", "c", "d", "e"), out.results.map { it.id })
        assertEquals(listOf(Verdict.PASS, Verdict.FAIL, Verdict.FAIL, Verdict.SKIP, Verdict.FAIL), out.results.map { it.verdict })
        assertEquals("No answer in 0 s", out.results[1].detail)
        assertEquals("broke", out.results[2].detail)
        assertEquals("boom", out.results[4].detail)
    }

    @Test fun pastTheDeadlineEveryLaterStepIsSkipped() = runBlocking {
        var now = 0L
        val out = ShakeRunner.run(
            listOf(step("a") { now += 4 * 60_000L; "ok" }, step("b") { now += 2 * 60_000L; "ok" }, step("c") { "never" }),
            { 0 }, { emptyList() }, { now },
        )
        assertEquals(listOf(Verdict.PASS, Verdict.PASS, Verdict.SKIP), out.results.map { it.verdict })
        assertEquals("Out of time after 5 minutes", out.aborted)
    }

    @Test fun cleanupRunsAfterFailuresCancelsAndCrashes() = runBlocking {
        var cleaned = 0
        val cleanup: suspend () -> List<String> = { cleaned++; listOf("A test card is still on Olympus") }
        val out = run(listOf(step("a") { throw IllegalStateException("x") }), cleanup = cleanup)
        assertEquals(1, cleaned)
        assertEquals(listOf("A test card is still on Olympus"), out.cleanup)

        // onResult throws: the run stops, the cleanup still ran.
        try { ShakeRunner.run(listOf(step("a") { "ok" }), { 0 }, cleanup) { error("ui gone") }; fail() } catch (e: IllegalStateException) { assertEquals("ui gone", e.message) }
        assertEquals(2, cleaned)

        // Stopped by him: the coroutine is cancelled mid-step.
        val job = async { run(listOf(step("slow") { delay(60_000); "x" }, step("b") { "never" }), cleanup = cleanup) }
        yield(); delay(20)
        job.cancel()
        try { job.await(); fail() } catch (e: CancellationException) { }
        assertEquals(3, cleaned)

        // A cleanup that throws is reported, not raised.
        val bad = run(listOf(step("a") { "ok" }), cleanup = { error("disk") })
        assertEquals(listOf("Cleanup failed: disk"), bad.cleanup)
    }

    @Test fun theCapStopsTheRunAndSkipsStepsThatWouldPassIt() = runBlocking {
        var spent = 0L
        val out = run(listOf(
            ShakeStep("a") { spent += 30; "ok" },
            ShakeStep("b", estimateCents = 25) { spent += 25; "would pass" },
            ShakeStep("c") { spent += 25; "ok" },
            ShakeStep("d") { "never" },
            ShakeStep("e") { "never" },
        ), spent = { spent })
        assertEquals(listOf(Verdict.PASS, Verdict.SKIP, Verdict.PASS, Verdict.SKIP, Verdict.SKIP), out.results.map { it.verdict })
        assertEquals("Skipped: it would pass the $0.50 cap", out.results[1].detail)
        assertEquals("Stopped at the $0.50 cap", out.aborted)
        assertEquals("Stopped at the $0.50 cap", out.results[3].detail)
        assertEquals(55L, out.spentCents)
    }

    @Test fun reportCheckIsValidated() {
        fun v(m: Map<String, Any?>, running: Boolean = true) = Shakedown.validate(m, running)
        assertEquals("No shakedown is running.", v(mapOf("id" to "tool-now", "status" to "pass"), running = false).exceptionOrNull()?.message)
        assertTrue(v(mapOf("id" to "spotify", "status" to "pass")).exceptionOrNull()!!.message!!.startsWith("Unknown check id \"spotify\""))
        assertTrue(v(mapOf("id" to "nope", "status" to "pass")).isFailure)
        assertEquals("status must be pass, fail or skip", v(mapOf("id" to "tool-now", "status" to "ok")).exceptionOrNull()?.message)
        val c = v(mapOf("id" to " tool-calendar ", "status" to "FAIL", "detail" to "\n" + "x".repeat(300) + "\nsecond line")).getOrThrow()
        assertEquals(ShakeClaim("tool-calendar", Verdict.FAIL, "x".repeat(200)), c)
        assertEquals("", v(mapOf("id" to "mind-turn", "status" to "skip")).getOrThrow().detail)
    }

    @Test fun thePhonesVerdictWinsOverHerWord() {
        val phone = listOf(ShakeResult("tool-now", Verdict.FAIL, "She didn't call now"), ShakeResult("spotify", Verdict.PASS, "Now playing"))
        val claims = mapOf("tool-now" to ShakeClaim("tool-now", Verdict.PASS, "all good"), "ask-iris" to ShakeClaim("ask-iris", Verdict.PASS, "asked"))
        val m = Shakedown.merge(phone, claims).associateBy { it.id }
        assertEquals(Verdict.FAIL, m.getValue("tool-now").verdict)
        assertEquals("pass: all good", m.getValue("tool-now").claim)
        assertEquals(Verdict.PASS, m.getValue("ask-iris").verdict)
        assertEquals("Her word only: asked", m.getValue("ask-iris").detail)
        // In the checklist's order, nothing made up for checks that didn't report.
        assertEquals(listOf("tool-now", "ask-iris", "spotify"), Shakedown.merge(phone, claims).map { it.id })
    }

    @Test fun theChecklistCoversEveryFeatureOnce() {
        val ids = Shakedown.CHECKS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.size in 40..55)
        assertTrue(Shakedown.READ_TOOLS.values.all { it in Shakedown.ATHENA_IDS })
        assertTrue(Shakedown.READ_TOOLS.keys.all { it in MindTools.READS || it == "read_schedules" })
        // Every Fix link goes somewhere: a Telete check that exists, or a screen.
        val telete = Telete.checks(Facts(), 0).map { it.id }.toSet()
        assertTrue(Shakedown.CHECKS.all { c -> (c.telete == null || c.telete in telete) })
        assertTrue(Shakedown.CHECKS.filter { it.by == By.PHONE && !it.notify }.count { it.telete == null && it.dest == null } <= 4)
        assertTrue(Shakedown.CHECKS.none { it.label.contains('—') })
    }

    @Test fun oneTestNotificationPerKindOfChannel() {
        val ch = TestNotify.CHANNELS
        assertEquals(listOf("lemnos.normal", "moneta.fraud", "birthdays", "nowbar"), ch.map { it.channel })
        assertEquals(listOf(3, 4, 4, 3), ch.map { it.importance })
        assertEquals(ch.size, ch.map { TestNotify.id(it) }.toSet().size)
        assertTrue(ch.all { Shakedown.check(it.check)?.notify == true })
        assertEquals(60_000L, Shakedown.NOTIFY_MS)
    }

    @Test fun liveIsInTheRunAndTheMicTestOnlyOnATap() {
        assertEquals("""{"clientContent":{"turns":[{"role":"user","parts":[{"text":"hi"}]}],"turnComplete":true}}""", dev.phonerecall.voice.LiveProtocol.text("hi"))
        assertFalse(Shakedown.check("live")!!.manual)
        assertTrue(Shakedown.check("live-mic")!!.manual)
        assertTrue(ShakeFixtures.results.none { it.id == "live-mic" })
    }

    @Test fun herPromptPassesTheSanitizerUnchanged() {
        val p = Shakedown.prompt("s1")
        assertEquals(p, Sanitizer().line(p))
        assertTrue(Shakedown.READ_TOOLS.keys.all { it in p } && Shakedown.READ_TOOLS.values.all { it in p })
        assertTrue(Shakedown.CARD_TITLE in p)
    }

    @Test fun summaryAndComparison() {
        assertEquals(
            "45 of 50 passed; Iris: Asked; no answer in 85 s; Odyssey: No TomTom key in Calypso; Spotify: Spotify isn't connected; 2 skipped.",
            Shakedown.summary(ShakeFixtures.results),
        )
        assertEquals("1 of 1 passed; Stopped by you.", Shakedown.summary(listOf(ShakeResult("a", Verdict.PASS)), "Stopped by you"))
        val ch = Shakedown.compare(ShakeFixtures.report, ShakeFixtures.before)
        assertEquals(mapOf("spotify" to Shakedown.Change.BROKE, "hestia" to Shakedown.Change.FIXED), ch)
        assertTrue(Shakedown.compare(ShakeFixtures.report, null).isEmpty())
    }

    @Test fun reportsRoundTripAsJson() {
        val j = dev.phonerecall.mind.stateJson
        val back = j.readValue(j.writeValueAsString(listOf(ShakeFixtures.report)), object : com.fasterxml.jackson.core.type.TypeReference<List<ShakeReport>>() {})
        assertEquals(listOf(ShakeFixtures.report), back)
    }

    /** report_check reaches the probe only from Athena and only during a run; writes are off while one is on. */
    @Test fun reportCheckToolAndTestMode() {
        val seen = ArrayList<String>()
        var probe: TestProbe? = null
        val r = Rig()
        val tools = MindTools(dev.phonerecall.brain.ToolBox(dev.phonerecall.brain.EngineTest.EmptyReads, r.fates), r.extras, probe = { probe }) { Sanitizer() }
        val input = mapOf<String, Any?>("id" to "tool-now", "status" to "pass")
        assertTrue(tools.run(MindTools.REPORT_CHECK, input, null, asCircle = false).isError)
        probe = object : TestProbe {
            override fun report(input: Map<String, Any?>) = "Recorded ${input["id"]}."
            override fun seen(name: String, input: Map<String, Any?>, isError: Boolean, text: String) { seen += name }
        }
        assertEquals("Recorded tool-now.", tools.run(MindTools.REPORT_CHECK, input, null, asCircle = false).text)
        assertTrue(tools.run(MindTools.REPORT_CHECK, input, "thread_1", asCircle = true).isError)
        val write = tools.run("add_task", mapOf("title" to "Book the oil change"), null, asCircle = false)
        assertTrue(write.isError && write.text.startsWith("Shakedown in progress"))
        assertTrue(r.store.all().isEmpty())
        assertTrue(tools.run(MindTools.AGORA, mapOf("text" to "hi", "room" to "group"), null, asCircle = false).isError)
        assertFalse(tools.run("read_cards", emptyMap(), null, asCircle = false).isError)
        assertEquals(listOf("read_cards"), seen)
        assertTrue(tools.specs(circle = false).any { it.name == MindTools.REPORT_CHECK })
        assertTrue(tools.specs(circle = true).none { it.name == MindTools.REPORT_CHECK })
    }
}
