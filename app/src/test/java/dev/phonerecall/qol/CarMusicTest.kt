package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The car connects: play, resume, or leave the music alone. */
class CarMusicTest {
    private fun d(on: Boolean = true, call: Boolean = false, playing: Boolean = false, recent: Boolean = false, paused: Boolean = false) =
        CarMusic.decide(on, call, playing, recent, paused)

    @Test fun playsOnlyWhenNothingIs() {
        assertEquals(CarPlay.PLAY, d())
        assertEquals(CarPlay.RESUME, d(paused = true))
        assertEquals(CarPlay.PLAYING, d(playing = true, paused = true))
    }

    @Test fun neverOnACallOrWhenOffOrTwicePerDrive() {
        assertEquals(CarPlay.ON_CALL, d(call = true))
        assertEquals(CarPlay.OFF, d(on = false, call = true))
        assertEquals(CarPlay.RECENT, d(recent = true))
    }

    @Test fun theSwitchIsInThemisButNeverACard() {
        assertTrue(MomentKind.CAR_MUSIC.context)
        val now = ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneId.of("America/New_York"))
        assertEquals(null, MomentRules.pick(MomentContext(now, driving = true), MomentsPrefs())?.kind)
    }
}
