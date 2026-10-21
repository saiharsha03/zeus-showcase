package dev.phonerecall.metron

import dev.phonerecall.brain.CaptureKind
import dev.phonerecall.brain.EngineRig
import dev.phonerecall.brain.SpendState
import dev.phonerecall.brain.ToolBox
import dev.phonerecall.mind.Metis
import dev.phonerecall.mind.MindTools
import dev.phonerecall.mind.Protege
import dev.phonerecall.voice.LiveLedger
import dev.phonerecall.widgets.WidgetContent
import dev.phonerecall.widgets.WidgetSafety
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
import java.time.ZonedDateTime

private val ZONE = ZoneId.of("America/New_York")
/** The fixture's clock: before every reset in it. */
private const val NOW_S = 1792880000L
private val NOW: ZonedDateTime = Instant.ofEpochSecond(NOW_S).atZone(ZONE)

private fun fixture(): UsageSnapshot =
    UsageParse.parse(MetronTest::class.java.getResource("/metron/usage.json")!!.readText(), NOW_S * 1000 - 60_000)

private fun budget(pct: Int, month: String = "2026-10"): Budget =
    Budget(100, pct * 1_000_000L, emptyList(), 0, 0, 0, pct * 1_000_000L, month)

class MetronTest {
    @Test fun parsesTheGatewaysAnswer() {
        val u = fixture()
        assertEquals(listOf("iris", "atlas"), u.agents.map { it.id })   // the one without an id is dropped
        val iris = u.agent("iris")!!
        assertEquals("Opus 5.5", iris.model)
        assertEquals(listOf("5h", "7d", "7d Opus"), iris.windows.map { it.label })
        assertEquals(83.5, iris.windows[0].usedPct!!, 0.0)
        assertEquals(1792900800L, iris.windows[0].resetsAt)
        assertNull(iris.windows[2].usedPct)                              // reset since the last reading
        assertEquals(1792879000L, iris.updated)
        assertEquals("5-hour", iris.windows[0].words)
        assertEquals("7-day Opus", iris.windows[2].words)
        assertEquals(91.0, u.agent("atlas")!!.worst!!.usedPct!!, 0.0)
    }

    @Test fun barsShiftAt80And95() {
        assertEquals(Level.OK, Level.of(79.9))
        assertEquals(Level.WARN, Level.of(80.0))
        assertEquals(Level.WARN, Level.of(94.9))
        assertEquals(Level.HOT, Level.of(95.0))
        assertEquals(Level.OK, Level.of(null))
    }

    @Test fun eachWindowAlertsOnceAndTheNextWindowAgain() {
        val u = fixture()
        val first = MetronAlerts.due(u, null, emptySet(), NOW)
        assertEquals(listOf("w:iris:5h:1792900800", "w:atlas:5h:1792890000"), first.map { it.key })
        assertEquals("Atlas at 91% of the 5-hour window", first[1].title)
        assertEquals(1792890000L * 1000, first[1].untilMs)
        val fired = MetronAlerts.remember(emptySet(), first)
        assertTrue(MetronAlerts.due(u, null, fired, NOW).isEmpty())
        // The same window climbing further stays quiet; a new window (new reset time) alerts again.
        val later = u.copy(agents = u.agents.map { a -> if (a.id != "iris") a else a.copy(windows = a.windows.map { if (it.label == "5h") it.copy(usedPct = 99.0) else it }) })
        assertTrue(MetronAlerts.due(later, null, fired, NOW).isEmpty())
        val next = u.copy(agents = u.agents.map { a -> if (a.id != "iris") a else a.copy(windows = a.windows.map { if (it.label == "5h") it.copy(resetsAt = 1792918800) else it }) })
        assertEquals(listOf("w:iris:5h:1792918800"), MetronAlerts.due(next, null, fired, NOW).map { it.key })
    }

    @Test fun theCapAlertsAt80And95OnceEach() {
        var fired = emptySet<String>()
        assertTrue(MetronAlerts.due(null, budget(79), fired, NOW).isEmpty())
        val at80 = MetronAlerts.due(null, budget(81), fired, NOW)
        assertEquals(listOf("b:2026-10:80"), at80.map { it.key })
        fired = MetronAlerts.remember(fired, at80)
        assertTrue(MetronAlerts.due(null, budget(90), fired, NOW).isEmpty())
        val at95 = MetronAlerts.due(null, budget(96), fired, NOW)
        assertEquals(listOf("b:2026-10:95"), at95.map { it.key })
        fired = MetronAlerts.remember(fired, at95)
        assertTrue(MetronAlerts.due(null, budget(99), fired, NOW).isEmpty())
        // Straight past 95%: one alert, and 80% counts as said. A new month starts over.
        val jump = MetronAlerts.due(null, budget(97), emptySet(), NOW)
        assertEquals(listOf("b:2026-10:95"), jump.map { it.key })
        assertTrue(MetronAlerts.due(null, budget(85), MetronAlerts.remember(emptySet(), jump), NOW).isEmpty())
        assertEquals(listOf("b:2026-11:80"), MetronAlerts.due(null, budget(85, "2026-11"), fired, NOW).map { it.key })
    }

    @Test fun monthEndProjection() {
        // October has 31 days. Ten days gone and $30 spent: $93 at this pace.
        val oct11 = ZonedDateTime.of(2026, 10, 11, 0, 0, 0, 0, ZONE)
        assertEquals(93_000_000L, MetronBudget.projectMonthEnd(30_000_000L, oct11))
        // Two hours into the month a whole day counts as gone, so $5 projects to $155, not $1,860.
        assertEquals(155_000_000L, MetronBudget.projectMonthEnd(5_000_000L, ZonedDateTime.of(2026, 10, 1, 2, 0, 0, 0, ZONE)))
        // The last instant of the month projects what was spent.
        assertEquals(42_000_000L, MetronBudget.projectMonthEnd(42_000_000L, ZonedDateTime.of(2026, 10, 31, 23, 59, 59, 0, ZONE)) / 1000 * 1000)
    }

    @Test fun theSplitAddsUpToTheTotal() {
        val oct11 = ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, ZONE)
        val spend = SpendState("2026-10", spentMicros = 50_000_000, mindMicros = 30_000_000, activeSeconds = 5_400, advisorMicros = 5_000_000)
        val circle = mapOf(Metis.THREAD to 800L, Protege.PERSEUS.agentName to 500L, Protege.ANNABETH.agentName to 1_000L)
        val b = MetronBudget.of(spend, circle, LiveLedger("2026-10-11", inSeconds = 600, outSeconds = 300), oct11)
        val by = b.lines.associate { it.label to it.micros }
        assertEquals(15_000_000L, by["Quick calls"])
        assertEquals(12_000_000L, by["Athena's mind"])
        assertEquals(5_000_000L, by["Perseus"])
        assertEquals(10_000_000L, by["Annabeth"])
        assertEquals(8_000_000L, by["Metis"])
        assertEquals(b.spentMicros, b.lines.sumOf { it.micros })
        assertEquals(50.0, b.pct, 0.001)
        assertEquals(600L, b.liveSeconds)
        assertEquals(140_000L, b.liveMicros)
        assertEquals(5_400L, b.activeSeconds)
    }

    @Test fun routingSkipsAWorkerAbove90() {
        val u = fixture()
        assertEquals(WorkerPick.Go("iris"), UsageRouting.pick("iris", u, NOW_S))        // 83.5%: under the limit
        val wait = UsageRouting.pick("atlas", u, NOW_S)
        assertTrue(wait is WorkerPick.Wait)
        assertEquals(1792890000L, (wait as WorkerPick.Wait).untilSec)
        assertEquals("Atlas is at 91% of the 5-hour window", wait.why)
        assertEquals(WorkerPick.Instead("iris", "atlas", "Atlas is at 91% of the 5-hour window"), UsageRouting.pick("atlas", u, NOW_S, listOf("iris")))
        // Past its reset the old number no longer holds; no reading never blocks.
        assertEquals(WorkerPick.Go("atlas"), UsageRouting.pick("atlas", u, 1792890001))
        assertEquals(WorkerPick.Go("atlas"), UsageRouting.pick("atlas", null, NOW_S))
        val reached = u.copy(agents = listOf(AgentUsage("atlas", "Atlas", limitReached = true)))
        assertTrue(UsageRouting.pick("atlas", reached, NOW_S) is WorkerPick.Wait)
        assertTrue(MetronWords.held(wait, NOW)!!.startsWith("Atlas is at 91% of the 5-hour window. Held until "))
        assertNull(MetronWords.held(WorkerPick.Go("iris"), NOW))
    }

    @Test fun readUsageInWords() {
        val oct = ZonedDateTime.of(2026, 10, 11, 12, 0, 0, 0, ZONE)
        val w = MetronWords.describe(fixture(), budget(42), NOW)
        assertTrue(w, w.contains("Iris (Opus 5.5): 5-hour window 83% used, resets "))
        assertTrue(w, w.contains("7-day Opus window reset since the last reading"))
        assertTrue(w, w.contains("Atlas (gpt-6.1-sol): 5-hour window 91% used"))
        assertTrue(w, w.contains("Athena's cap: 42% of \$100 used this month"))
        assertTrue(w, w.endsWith("give it to another worker who can do it, or wait for the reset."))
        assertTrue(MetronWords.describe(null, null, oct).startsWith("The workers' plan usage hasn't been read"))
        assertFalse(w.contains('—'))
        assertTrue(ToolBox.READ_TOOLS.any { it.name() == "read_usage" })
        assertTrue("read_usage" in MindTools.READS)
    }

    @Test fun theWidgetShowsPercentagesOnlyAndPassesTheWidgetRule() {
        val v = WidgetContent.metron(fixture(), budget(42), NOW)
        assertEquals("Atlas at 91%", v.title)
        assertEquals(listOf("Iris", "Atlas", "Athena"), v.groups.map { it.label })
        assertTrue(WidgetSafety.texts(v).joinToString(), WidgetSafety.isSafe(v))
        assertFalse(WidgetSafety.texts(v).any { '$' in it })
        assertEquals("No reading yet", WidgetContent.metron(null, null, NOW).title)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MetronRoutingEngineTest {
    @Test fun aParnassusJobForABusyWorkerSaysItWaits() {
        val rig = EngineRig()
        rig.effects.hold = { if (it == "iris") "Iris is at 93% of the 5-hour window. Held until 4:10 PM." else null }
        rig.engine.capture(CaptureKind.TEXT, "did my refund come in")
        assertEquals("For Iris. Iris is at 93% of the 5-hour window. Held until 4:10 PM.", rig.store.captures().single().outcome)
        rig.engine.capture(CaptureKind.TEXT, "is the server slow?")
        assertEquals("For Atlas. Waiting for the phase 3 channel.", rig.store.captures().first { it.place == "Atlas" }.outcome)
    }
}
