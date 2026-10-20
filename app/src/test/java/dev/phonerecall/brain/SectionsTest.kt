package dev.phonerecall.brain

import dev.phonerecall.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionsTest {
    private var now = 1_792_000_000_000L
    private val day = 86_400_000L
    private val p = MemorySections()
    private val log = ArrayList<String>()
    private val reg = SectionRegistry(p, { a, b, c -> log += "$a $b $c" }) { now }

    private fun SectionResult.section() = (this as SectionResult.Done).section
    private fun SectionResult.change() = (this as SectionResult.Done).changeId!!

    @Test fun athenaCreatesLogsAndCanBeUndone() {
        val r = reg.create("groceries ", Actor.ATHENA)
        val s = r.section()
        assertEquals("Groceries", s.name)
        assertEquals(listOf("Athena section Made Groceries"), log)
        assertEquals(listOf(s.id), reg.visible().map { it.id })
        // Creating it again is the same section, not a second one.
        assertEquals(s.id, reg.create("Groceries", Actor.ATHENA).section().id)
        assertEquals(1, p.rows.size)

        reg.undo(r.change())
        assertTrue(reg.visible().isEmpty())
        assertEquals(1, p.rows.size)                      // hidden, never deleted
        assertEquals(Actor.OWNER, p.rows.getValue(s.id).hiddenBy)
        assertTrue(p.log.getValue(r.change()).undone)
        // His undo stands: Athena can't bring it back.
        assertTrue(reg.create("Groceries", Actor.ATHENA) is SectionResult.Refused)
        // He can.
        assertFalse(reg.create("Groceries", Actor.OWNER).section().hidden)
    }

    @Test fun undoRestoresARenameAndAHide() {
        val s = reg.create("Shopping", Actor.ATHENA).section()
        val rename = reg.rename(s.id, "Errands", Actor.ATHENA).change()
        assertEquals("Errands", reg.get(s.id)!!.name)
        reg.undo(rename)
        assertEquals("Shopping", reg.get(s.id)!!.name)
        val hide = reg.hide(s.id, Actor.ATHENA).change()
        reg.undo(hide)
        assertFalse(reg.get(s.id)!!.hidden)
        assertTrue(reg.undo(hide) is SectionResult.Refused)   // once only
    }

    @Test fun hisPinsAndNamesWin() {
        val s = reg.create("Groceries", Actor.ATHENA).section()
        reg.pin(s.id, true)
        assertTrue(reg.rename(s.id, "Food", Actor.ATHENA) is SectionResult.Refused)
        assertTrue(reg.hide(s.id, Actor.ATHENA) is SectionResult.Refused)
        reg.pin(s.id, false)
        reg.rename(s.id, "Costco run", Actor.OWNER)
        assertTrue(reg.rename(s.id, "Groceries", Actor.ATHENA) is SectionResult.Refused)
        // He hid it: Athena can't show it.
        reg.hide(s.id, Actor.OWNER)
        assertTrue(reg.show(s.id, Actor.ATHENA) is SectionResult.Refused)
    }

    @Test fun athenaReordersAroundHisPins() {
        val a = reg.create("A", Actor.ATHENA).section()
        val b = reg.create("B", Actor.ATHENA).section()
        val c = reg.create("C", Actor.ATHENA).section()
        reg.pin(a.id, true)
        reg.reorder(listOf(c.id, b.id, a.id), Actor.ATHENA)
        // A is pinned: first, untouched. B and C swapped.
        assertEquals(listOf("A", "C", "B"), reg.visible().map { it.name })
        assertEquals(0, reg.get(a.id)!!.order)
    }

    @Test fun emptySectionsHideAfter14DaysNeverDeleted() {
        val empty = reg.create("Camping", Actor.ATHENA).section()
        val full = reg.create("Groceries", Actor.ATHENA).section()
        val pinned = reg.create("Books", Actor.OWNER).section()
        reg.pin(pinned.id, true)
        p.openItems[full.id] = 2
        now += 13 * day
        assertTrue(reg.autoHide().isEmpty())
        now += day
        assertEquals(listOf("Camping"), reg.autoHide().map { it.name })
        assertEquals(3, p.rows.size)
        assertTrue(reg.get(empty.id)!!.hidden)
        assertEquals("auto-hide", p.log.values.last().op)
        // An auto-hide is Athena's, so a new item lets her bring it back.
        assertFalse((reg.create("Camping", Actor.ATHENA) as SectionResult.Done).section.hidden)
    }

    @Test fun theCoverBarStaysAtFourAndMore() {
        assertEquals(SectionRegistry.COVER_BAR_SLOTS, Dest.entries.count { it.onCoverBar })
    }
}
