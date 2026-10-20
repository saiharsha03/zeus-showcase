package dev.phonerecall.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LiveLedgerTest {
    private val mon = LocalDate.of(2026, 10, 19)

    @Test fun capIsThirtyMinutesADayByDefault() {
        val l = LiveLedger(mon.toString())
        assertEquals(30, l.capMinutes)
        assertEquals(1800, l.leftSeconds(mon))
        val used = l.plus(25 * 60, 300, mon)
        assertEquals(300, used.leftSeconds(mon))
        assertEquals(0, used.plus(10 * 60, 0, mon).leftSeconds(mon))
        assertEquals(VoiceRouter.live(LiveGate(true, true, used.plus(10 * 60, 0, mon).leftSeconds(mon))).javaClass, Route.LiveBlocked("").javaClass)
        // A new day starts at zero; the month's spend carries on.
        val tue = used.on(mon.plusDays(1))
        assertEquals(1800, tue.leftSeconds(mon.plusDays(1)))
        assertEquals(25 * 60L, tue.inSeconds)
        // A raised cap gives more today.
        assertEquals(35 * 60L, used.copy(capMinutes = 60).leftSeconds(mon))
    }

    @Test fun confirmIsOncePerDay() {
        val l = LiveLedger(mon.toString()).confirm(mon)
        assertTrue(l.confirmedToday(mon))
        assertFalse(l.confirmedToday(mon.plusDays(1)))
    }

    @Test fun spendAtPaidRatesAndANewMonthStartsAtZero() {
        // 10 min open, 4 min spoken: 10 x $0.005 + 4 x $0.018 = $0.122
        val l = LiveLedger(mon.toString()).plus(600, 240, mon)
        assertEquals(122_000, l.spendMicros)
        val nov = l.on(LocalDate.of(2026, 11, 1))
        assertEquals(0, nov.spendMicros)
        assertEquals("2026-11", nov.month)
    }
}
