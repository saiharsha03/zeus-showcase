package dev.phonerecall.qol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media muted at work: arrive, unplug, headphones, leave, Undo. */
class WorkMuteTest {
    private val none = MuteState()

    @Test fun arriveMutesAndSavesTheLevel() {
        val s = WorkMute.arrive(none, on = true, volume = 9, headphones = false)
        assertEquals(0, s.volume)
        assertEquals(MuteState(atWork = true, saved = 9), s.state)
    }

    @Test fun headphonesAreNeverTouched() {
        val s = WorkMute.arrive(none, on = true, volume = 9, headphones = true)
        assertNull(s.volume)
        assertTrue(s.state.atWork)
        assertNull(WorkMute.deviceGone(s.state, on = true, volume = 6, headphones = true).volume)
    }

    @Test fun unpluggingAtWorkGoesBackToZero() {
        val inWithPhones = WorkMute.arrive(none, on = true, volume = 9, headphones = true).state
        val out = WorkMute.deviceGone(inWithPhones, on = true, volume = 7, headphones = false)
        assertEquals(0, out.volume)
        assertEquals(7, out.state.saved)
        // Muted already, then a speaker drops: still 0, the first level kept.
        val again = WorkMute.deviceGone(out.state.copy(), on = true, volume = 4, headphones = false)
        assertEquals(0, again.volume)
        assertEquals(7, again.state.saved)
    }

    @Test fun leavingRestores() {
        val muted = WorkMute.arrive(none, on = true, volume = 9, headphones = false).state
        val left = WorkMute.leave(muted, headphones = false)
        assertEquals(9, left.volume)
        assertEquals(none, left.state)
        // Headphones in on the way out: the speaker's level comes back when they go.
        val later = WorkMute.leave(muted, headphones = true)
        assertNull(later.volume)
        assertEquals(9, WorkMute.deviceGone(later.state, on = true, volume = 0, headphones = false).volume)
    }

    @Test fun undoRestoresAndStopsForcing() {
        val muted = WorkMute.arrive(none, on = true, volume = 9, headphones = false).state
        val u = WorkMute.undo(muted)
        assertEquals(9, u.volume)
        assertTrue(u.state.undone)
        assertNull(WorkMute.deviceGone(u.state, on = true, volume = 8, headphones = false).volume)
        assertFalse(WorkMute.leave(u.state, headphones = false).state.undone)
        assertNull(WorkMute.leave(u.state, headphones = false).volume)
    }

    @Test fun offOrAlreadySilentDoesNothing() {
        assertNull(WorkMute.arrive(none, on = false, volume = 9, headphones = false).volume)
        val silent = WorkMute.arrive(none, on = true, volume = 0, headphones = false)
        assertNull(silent.volume)
        assertNull(WorkMute.leave(silent.state, headphones = false).volume)
    }
}
