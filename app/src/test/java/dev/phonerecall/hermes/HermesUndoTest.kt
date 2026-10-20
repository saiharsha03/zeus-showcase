package dev.phonerecall.hermes

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Undo puts the phone back the way it was. */
@RunWith(RobolectricTestRunner::class)
class HermesUndoTest {
    private val app = RuntimeEnvironment.getApplication()
    private val am = app.getSystemService(AudioManager::class.java)

    @Test fun volumeUndoRestoresTheLevel() {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, 3, 0)
        val o = Hermes.volume(app, VolumeCmd("set", 100))
        assertEquals(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), am.getStreamVolume(AudioManager.STREAM_MUSIC))
        assertEquals("Media volume 100%", o.title)
        Hermes.undo(app, o, VolumeCmd("set", 100))
        assertEquals(3, am.getStreamVolume(AudioManager.STREAM_MUSIC))
    }

    @Test fun reversibleCommandsCarryUndo() {
        assertNotNull(Hermes.volume(app, VolumeCmd("down")).undo)
    }

    @Test fun correctionsPersist() {
        val s = HermesStore(app)
        s.learn("app", "teams", "Microsoft Teams")
        assertEquals("Microsoft Teams", Corrections.lookup(HermesStore(app).book(), "app", "Teams"))
    }
}
