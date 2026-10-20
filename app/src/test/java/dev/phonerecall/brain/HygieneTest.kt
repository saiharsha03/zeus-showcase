package dev.phonerecall.brain

import dev.phonerecall.data.TaskList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

class HygieneTest {
    private val zone = ZoneId.of("America/New_York")
    private val day = 86_400_000L
    private val now = 1_792_000_000_000L
    private fun past(title: String, kind: RelevanceKind = RelevanceKind.SEASONAL, note: String = "", waiting: Boolean = false, pinned: Boolean = false, worker: Boolean = false, shown: Boolean = false) =
        HygieneItem(title, note, until = now - day, kind = kind, reason = "Window ended", waitingList = waiting, pinned = pinned, workerOwned = worker, lastChanceShown = shown)

    @Test fun seasonalAndEventItemsGoToLethe() {
        assertEquals(HygieneCall.ToLethe("Window ended"), Hygiene.decide(past("Fall foliage drive to Allegheny"), now, zone))
        assertEquals(HygieneCall.ToLethe("Window ended"), Hygiene.decide(past("Inter Miami away dates", RelevanceKind.EVENT), now, zone))
        assertEquals(HygieneCall.Keep, Hygiene.decide(past("Fall foliage drive").copy(until = now + day), now, zone))
        assertEquals(HygieneCall.Keep, Hygiene.decide(past("No window").copy(until = null), now, zone))
    }

    @Test fun neverAutoClosed() {
        val protectedOnes = listOf(
            past("Pay the rent"),                                 // money
            past("Check the refund from Amazon"),                 // money
            past("Renew the visa before it expires"),                    // immigration
            past("Book the passport appointment"),                // immigration
            past("Dentist cleaning"),                             // health
            past("Pick up the prescription"),                     // health
            past("Hear back from the landlord"),                  // waiting on someone
            past("Report numbers", note = "waiting on Taylor"),       // waiting on someone
            past("Sofa delivery", waiting = true),                // the Waiting list
            past("Fall foliage drive", pinned = true),            // pinned
            past("Rotate the backup key", worker = true),         // a worker's task
        )
        for (i in protectedOnes) {
            val call = Hygiene.decide(i, now, zone)
            assertTrue("${i.title} must not be closed: $call", call is HygieneCall.LastChance)
        }
        // The last chance comes once; after that it simply stays open.
        assertEquals(HygieneCall.Keep, Hygiene.decide(past("Pay the rent", shown = true), now, zone))
    }

    @Test fun deadlinesGetALastChanceNotLethe() {
        assertTrue(Hygiene.decide(past("Return the green scarf", RelevanceKind.DEADLINE), now, zone) is HygieneCall.LastChance)
        // Overdue but the window is still open: one last-chance card.
        val overdue = HygieneItem("Return the green scarf", until = now + 5 * day, kind = RelevanceKind.DEADLINE, due = now - day)
        assertTrue(Hygiene.decide(overdue, now, zone) is HygieneCall.LastChance)
        assertEquals(HygieneCall.Keep, Hygiene.decide(overdue.copy(lastChanceShown = true), now, zone))
    }

    @Test fun theWeeklyLine() {
        assertEquals(null, Hygiene.closedLine(emptyList()))
        assertEquals("Fall foliage drive went to Lethe this week.", Hygiene.closedLine(listOf("Fall foliage drive")))
        assertEquals("A, B and 2 more went to Lethe this week.", Hygiene.closedLine(listOf("A", "B", "C", "D")))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HygieneRunTest {
    @Test fun closesToLetheWithUndoAndKeepsProtectedOpen() {
        val rig = EngineRig()
        val foliage = rig.tasks.addTask("Fall foliage drive")
        val rent = rig.tasks.addTask("Pay the rent")
        val sofa = rig.tasks.addTask("Sofa delivery", TaskList.WAITING)
        for (id in listOf(foliage, rent, sofa)) rig.store.setTaskRelevance(TaskRelevance(id, rig.now - 1, RelevanceKind.SEASONAL, "Foliage season ended", false, false))
        rig.engine.hygiene()

        assertEquals(TaskList.DONE, rig.tasks.rows.getValue(foliage).list)
        assertEquals(TaskList.INBOX, rig.tasks.rows.getValue(rent).list)
        assertEquals(TaskList.WAITING, rig.tasks.rows.getValue(sofa).list)
        val row = rig.store.lethe().single()
        assertEquals("Fall foliage drive", row.title)
        assertEquals("Foliage season ended", row.reason)
        val cards = rig.store.cards(rig.now).map { it.title }
        assertTrue(cards.toString(), "Last chance: Pay the rent" in cards)
        assertTrue(cards.toString(), "Closed for you" in cards)

        rig.engine.restore(row.id)
        assertEquals(TaskList.INBOX, rig.tasks.rows.getValue(foliage).list)
        rig.engine.hygiene()
        // Restored items lose their window, so they are not closed again.
        assertEquals(TaskList.INBOX, rig.tasks.rows.getValue(foliage).list)
    }
}
