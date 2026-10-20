package dev.phonerecall.mind

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nothing that must stay on the phone reaches the memory store or a session. */
class SanitizerTest {
    private val s = Sanitizer(people = mapOf("Amma" to "family (mother)", "Taylor" to "manager", "Alex Kumar" to "friend"), sealed = setOf("Riley"))

    @Test fun namesBecomeLabels() {
        assertEquals("Missed call from family (mother).", s.line("Missed call from Amma."))
        assertEquals("manager asked about the report", s.line("Taylor asked about the report"))
        assertEquals("friend's birthday is today", s.line("Alex Kumar's birthday is today"))
    }

    @Test fun calypsoContentIsDroppedWhole() {
        assertNull(s.line("Riley sent you a message"))
        assertEquals("kept line", s.clean("Riley called\nkept line"))
    }

    @Test fun numbersEmailsAndAddressesAreStripped() {
        val out = s.line("Call 716-555-0142 or +1 (716) 555 0199, mail owner@example.com, at 123 Main Street")!!
        assertFalse(out.contains("555"))
        assertFalse(out.contains("@"))
        assertFalse(out.contains("123 Main"))
        assertTrue(out.contains("[number]") && out.contains("[email]") && out.contains("[address]"))
    }

    @Test fun moneyAmountsAreStripped() {
        val out = s.line("Rent of $1,450.00 and Rs 2000 and 35 dollars")!!
        assertFalse(Regex("\\d").containsMatchIn(out))
        assertTrue(out.contains("[amount]"))
    }

    @Test fun healthImmigrationAndDatingLinesAreDropped() {
        assertNull(s.line("Dentist appointment Thursday"))
        assertNull(s.line("Your visa petition was received"))
        assertNull(s.line("New match on Hinge"))
    }

    @Test fun locationTrailsAndCoordinatesNeverLeave() {
        assertNull(s.line("Location history: Walmart, then Costco"))
        val out = s.line("Pinned 39.7392, -104.9903 as home")!!
        assertFalse(out.contains("39.73"))
    }

    @Test fun toolResultsAreCleanedPerValueAndDatesSurvive() {
        val out = s.json("""{"events":[{"title":"Dentist","start":"2026-10-20T08:00"},{"title":"1:1 with Taylor","start":"2026-10-26T10:00"}]}""")
        assertTrue(out.contains("\"[withheld]\""))
        assertTrue(out.contains("2026-10-20T08:00") && out.contains("2026-10-26T10:00"))
        assertTrue(out.contains("1:1 with manager"))
    }

    @Test fun ordinaryLinesPassUnchanged() {
        assertEquals("Groceries: tomatoes, garlic, parmesan", s.line("Groceries: tomatoes, garlic, parmesan"))
        assertEquals("Office day tomorrow, team meeting at 10:00 AM.", s.line("Office day tomorrow, team meeting at 10:00 AM."))
    }

    @Test fun theMindSanitizesEverySnapshotBeforeUpload() {
        val r = Rig()
        r.context.snapshotText = "# Snapshot\n- Amma called twice\n- Riley: dinner?\n- Pay rent $1,450\n- Doctor at 4\n- Groceries: milk"
        r.mind.ensure()
        r.mind.syncMemory()
        val up = r.api.memories.getValue("/snapshot/today.md").content!!
        assertFalse(up.contains("Amma"))
        assertFalse(up.contains("Riley"))
        assertFalse(up.contains("1,450"))
        assertFalse(up.contains("Doctor"))
        assertTrue(up.contains("family (mother) called twice"))
        assertTrue(up.contains("Groceries: milk"))
        // Every file the phone writes went through the same filter.
        assertTrue(r.api.memories.values.none { f -> f.content.orEmpty().contains("Amma") || f.content.orEmpty().contains("Riley") })
    }

    @Test fun batchesAreSanitizedBeforeTheySend() {
        val r = Rig()
        r.mind.batcher.offer(MindNote(r.now, "Message from Amma at 716-555-0142", high = true))
        r.mind.flush()
        val sent = r.api.events.values.flatten().filterIsInstance<MindEvent.UserMessage>().single().text
        assertFalse(sent.contains("Amma"))
        assertFalse(sent.contains("555"))
        assertTrue(sent.contains("family (mother)"))
    }
}
