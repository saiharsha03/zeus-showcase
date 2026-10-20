package dev.phonerecall.iaso

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeightTrendTest {
    private val day = 86_400_000L
    private val t0 = NOW.toInstant().toEpochMilli() - 27 * day

    @Test fun slopeOfASteadyLossIsItsWeeklyRate() {
        // 80 kg falling 0.1 kg a day, with the usual water bounce on alternate days.
        val pts = (0..27).map { WeightPoint(t0 + it * day, 80.0 - 0.1 * it + if (it % 2 == 0) 0.3 else -0.3) }
        assertEquals(-0.7, WeightTrend.weeklySlope(pts, NOW.toInstant().toEpochMilli())!!, 0.05)
        assertEquals("Down 0.7 kg a week", WeightTrend.words(-0.7))
        assertEquals("Steady", WeightTrend.words(0.04))
        assertEquals("Up 0.2 kg a week", WeightTrend.words(0.18))
    }

    @Test fun tooFewReadingsGiveNoSlope() {
        val now = NOW.toInstant().toEpochMilli()
        assertNull(WeightTrend.weeklySlope(listOf(WeightPoint(now - day, 80.0), WeightPoint(now, 79.0)), now))
        // Three readings inside two days aren't a trend either.
        assertNull(WeightTrend.weeklySlope((0..2).map { WeightPoint(now - it * 20 * 3_600_000L, 80.0 + it) }, now))
        // Readings older than the window don't count.
        assertNull(WeightTrend.weeklySlope((0..5).map { WeightPoint(now - (60 + it) * day, 80.0) }, now))
        assertNull(WeightTrend.words(null))
    }

    @Test fun smoothingMovesAQuarterOfTheWayFromTheFirstThreesMean() {
        val s = WeightTrend.smooth(listOf(WeightPoint(0, 80.0), WeightPoint(1, 81.0), WeightPoint(2, 81.0)))
        assertEquals(listOf(80.5, 80.63, 80.72), s.map { Math.round(it.kg * 100) / 100.0 })
        assertEquals(emptyList<WeightPoint>(), WeightTrend.smooth(emptyList()))
    }

    @Test fun mergeDropsLocalReadingsHealthConnectAlreadyHas() {
        val hc = listOf(WeightPoint(1_000_000, 65.0, mine = true), WeightPoint(5_000_000, 65.4))
        val local = listOf(WeightPoint(1_020_000, 65.02, mine = true), WeightPoint(9_000_000, 64.9, mine = true))
        assertEquals(listOf(65.0, 65.4, 64.9), WeightTrend.merge(hc, local).map { it.kg })
        assertEquals(listOf(64.9), WeightTrend.merge(emptyList(), local.drop(1)).map { it.kg })
    }

    @Test fun dailyKeepsTheMorningReading() {
        val morning = NOW.toLocalDate().atTime(7, 0).atZone(NY).toInstant().toEpochMilli()
        val pts = listOf(WeightPoint(morning + 12 * 3_600_000L, 66.1), WeightPoint(morning, 65.3), WeightPoint(morning - day, 65.5))
        assertEquals(listOf(65.5, 65.3), WeightTrend.daily(pts, NY).map { it.kg })
    }

    @Test fun kilogramsInAndOut() {
        // What he types is kilograms: 65,5 or 65.5; a pound-sized number is not a believable weight in kg.
        assertEquals(65.5, WeightTrend.parse("65,5")!!, 0.0)
        assertEquals(65.5, WeightTrend.parse(" 65.5 ")!!, 0.0)
        assertNull(WeightTrend.parse("16"))
        assertNull(WeightTrend.parse("320"))
        assertNull(WeightTrend.parse("abc"))
        assertEquals("65.6 kg", WeightTrend.format(65.5747))
        assertEquals("80.0 kg", WeightTrend.format(80.0))
    }

    @Test fun anOldPoundsSettingIsIgnoredAndWeightsStayInKilograms() {
        val saved = IasoCodec.decode("""{"meals":[],"w":[{"at":1,"kg":65.4}],"wa":{},"lb":true,"sh":false,"n":1}""")
        assertEquals(listOf(65.4), saved.weights.map { it.kg })
        assertEquals(false, IasoCodec.encode(saved).contains("\"lb\""))
    }
}
