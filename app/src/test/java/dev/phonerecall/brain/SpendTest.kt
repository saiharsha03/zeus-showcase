package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class SpendTest {
    private val oct = YearMonth.of(2026, 10)

    @Test fun costFromUsageAndPriceTable() {
        // Sonnet 5.5: $2 in, $10 out, $0.20 cache read, cache write 1.25x input.
        val micros = Pricing.costMicros(BrainModel.SONNET, TokenUse(input = 1_000, output = 500, cacheRead = 10_000, cacheWrite = 2_000))
        // 1000*2 + 500*10 + 10000*0.2 + 2000*2.5 = 2000 + 5000 + 2000 + 5000
        assertEquals(14_000L, micros)
        // Opus 5.5: $4 / $20.
        assertEquals(1_000_000L * 4 + 100_000L * 20, Pricing.costMicros(BrainModel.OPUS, TokenUse(1_000_000, 100_000)))
        // Haiku 5.5 switches to $0.50 / $2.50 above 100K prompt tokens.
        assertEquals(5_000L + 5_000L, Pricing.costMicros(BrainModel.HAIKU, TokenUse(50_000, 10_000)))
        assertEquals(60_000L + 25_000L, Pricing.costMicros(BrainModel.HAIKU, TokenUse(120_000, 10_000)))
    }

    @Test fun capBlocksOnceReached() {
        var s = SpendState(oct.toString(), 0, capUsd = 20)
        assertTrue(s.allows(oct))
        s = s.plus(19_999_999, oct)
        assertTrue(s.allows(oct))
        s = s.plus(1, oct)
        assertFalse(s.allows(oct))
        assertEquals(20.0, s.spentUsd, 1e-9)
    }

    @Test fun newMonthStartsAtZero() {
        val s = SpendState(oct.toString(), 25_000_000, capUsd = 20)
        assertFalse(s.allows(oct))
        assertTrue(s.allows(oct.plusMonths(1)))
        assertEquals(1_000L, s.plus(1_000, oct.plusMonths(1)).spentMicros)
    }

    @Test fun defaultCapIsOneHundredDollarsForAllOfAthena() {
        assertEquals(100, SpendState(oct.toString(), 0).capUsd)
    }

    @Test fun theMindsSpendCountsTowardTheSameCap() {
        var s = SpendState(oct.toString(), 0, capUsd = 100)
        s = s.plus(40_000_000, oct).plusMind(60_000_000, 1_800, oct)
        assertFalse(s.allows(oct))
        assertEquals(60_000_000L, s.mindMicros)
        assertEquals(40_000_000L, s.messagesMicros)
        assertEquals(1_800L, s.activeSeconds)
        assertEquals(0L, s.current(oct.plusMonths(1)).mindMicros)
    }

    @Test fun engineRefusesWhenTheCapIsReached() {
        val guard = object : SpendGuard {
            override fun check() = throw SpendCapReached(20)
            override fun record(model: BrainModel, use: TokenUse) = Unit
        }
        var called = false
        val engine = AthenaEngine({ _, _ -> called = true; error("no") }, ToolBox(EngineTest.EmptyReads, Fates(MemoryStore(), FakeState(), FakeExecutor(), { _, _, _ -> })), guard)
        val e = runCatching { engine.run(mutableListOf(), BrainModel.SONNET, 1L, object : TurnSink {}) }.exceptionOrNull()
        assertTrue(e is SpendCapReached)
        assertFalse(called)
        assertEquals("This month's cap of $20 is reached. Raise it in Themis to keep going.", AthenaEngine.plainError(e!!))
    }
}
