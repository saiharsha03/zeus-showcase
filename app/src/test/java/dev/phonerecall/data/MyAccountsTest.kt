package dev.phonerecall.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MyAccountsTest {
    @Test fun onlyHisAddressesCount() {
        assertTrue(MyAccounts.notMine("parent@example.com"))
        assertFalse(MyAccounts.notMine("owner.alt@example.com"))
        assertFalse(MyAccounts.notMine("Holidays in United States"))
        assertFalse(MyAccounts.notMine(null))
    }

    @Test fun gmailFailsClosed() {
        assertTrue(MyAccounts.gmailMine("owner@example.com"))
        assertFalse(MyAccounts.gmailMine("parent@example.com"))
        assertFalse(MyAccounts.gmailMine(null))
        assertFalse(MyAccounts.gmailMine("3 new messages"))
    }

    @Test fun whatsappTagsAreDropped() {
        assertEquals("Amma", MyAccounts.untag("[me] Amma"))
        assertEquals("Amma", MyAccounts.untag("[owner] Amma"))
        assertEquals("[work] Amma", MyAccounts.untag("[work] Amma"))
    }

    @Test fun newEventsGoToHisMainCalendar() {
        val dad = dev.phonerecall.brain.CalendarChoice(1, "parent@example.com", "parent@example.com")
        val family = dev.phonerecall.brain.CalendarChoice(2, "Family", MyAccounts.PRIMARY)
        val main = dev.phonerecall.brain.CalendarChoice(3, MyAccounts.PRIMARY, MyAccounts.PRIMARY)
        assertEquals(3L, MyAccounts.defaultCalendar(listOf(dad, family, main))?.id)
        assertEquals(2L, MyAccounts.defaultCalendar(listOf(dad, family))?.id)
        assertEquals(null, MyAccounts.defaultCalendar(listOf(dad)))
    }
}
