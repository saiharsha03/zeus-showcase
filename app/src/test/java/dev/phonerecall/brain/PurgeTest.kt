package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Raw notification text lives 48 hours; after that only the derived summary is left, and nothing is ever plain on disk. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PurgeTest {
    private val hour = 3_600_000L

    @Test fun rawTextIsClearedAtExactly48Hours() {
        val rig = EngineRig()
        val t0 = rig.now
        rig.engine.ingest(rig.note("com.whatsapp", "Alex", "Are we still on for the hike on Saturday?", key = "a"))
        rig.now = t0 + 10 * hour
        rig.engine.ingest(rig.note("com.whatsapp", "Jordan", "Did you take the trash out", key = "b"))
        assertEquals(2, rig.store.rawCount())

        assertEquals(0, rig.store.purge(t0 + 48 * hour - 1))
        assertEquals(2, rig.store.rawCount())

        assertEquals(1, rig.store.purge(t0 + 48 * hour))
        val rows = rig.store.notes().associateBy { it.notifKey }
        assertNull(rows.getValue("a").raw)
        assertEquals("WhatsApp, Alex: Are we still on for the hike on Saturday?", rows.getValue("a").summary)
        assertNotNull(rows.getValue("b").raw)

        assertEquals(1, rig.store.purge(t0 + 58 * hour))
        assertEquals(0, rig.store.rawCount())
        // Summaries go after 30 days.
        rig.store.purge(t0 + 31 * 24 * hour)
        assertEquals(0, rig.store.notes().size)
    }

    @Test fun nothingIsPlainOnDisk() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note("com.whatsapp", "Alex", "Meet at Oak Street at six", key = "a"))
        rig.store.readableDatabase.rawQuery("SELECT raw, summary FROM notes", null).use { c ->
            c.moveToFirst()
            assertFalse(c.getString(0).contains("Oak Street"))
            assertFalse(c.getString(1).contains("Oak Street"))
        }
        rig.store.readableDatabase.rawQuery("SELECT name FROM people", null).use { c ->
            c.moveToFirst()
            assertFalse(c.getString(0).contains("Alex"))
        }
    }

    @Test fun ingestPurgesOnTheWay() {
        val rig = EngineRig()
        val t0 = rig.now
        rig.engine.ingest(rig.note("com.whatsapp", "Alex", "old message", key = "a"))
        rig.now = t0 + 49 * hour
        rig.engine.ingest(rig.note("com.whatsapp", "Alex", "new message", key = "b"))
        assertEquals(1, rig.store.rawCount())
    }
}
