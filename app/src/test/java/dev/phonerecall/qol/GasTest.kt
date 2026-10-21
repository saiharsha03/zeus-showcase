package dev.phonerecall.qol

import dev.phonerecall.odyssey.DriveReport
import dev.phonerecall.odyssey.FillUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gas on the way: miles since the fill-up, or the car app's warning. */
class GasTest {
    private val day = 86_400_000L

    @Test fun milesSinceTheLastFillUp() {
        val fills = listOf(FillUp("f1", at = 10 * day, state = "logged"))
        val drives = listOf(DriveReport("a", 9 * day, 9 * day + 1, 50_000), DriveReport("b", 11 * day, 11 * day + 1, 160_934), DriveReport("c", 12 * day, 12 * day + 1, 321_868))
        assertEquals(300, Gas.milesSince(fills, drives))
        assertNull(Gas.milesSince(emptyList(), drives))
    }

    @Test fun lowFromMilesOrTheCar() {
        assertTrue(Gas.low(300, 0, 20 * day))
        assertFalse(Gas.low(120, 0, 20 * day))
        assertTrue(Gas.low(null, 20 * day - 3_600_000L, 20 * day))
        assertFalse(Gas.low(null, 18 * day, 20 * day))
        assertTrue(Gas.lowText("Car Connect\nLow fuel warning: refuel soon"))
        assertFalse(Gas.lowText("Your trip used 2.1 gal"))
    }
}
