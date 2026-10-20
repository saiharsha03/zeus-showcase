package dev.phonerecall.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SkipLearnTest {
    private val day = 86_400_000L

    private fun seen(uri: String?, pos: Long, at: Long, dur: Long = 200_000L, paused: Boolean = false) = Seen(uri, "Song $uri", "Artist", dur, pos, paused, at)
    private fun t(n: Int) = "spotify:track:$n"

    // ---- detector ----

    @Test fun changingEarlyIsASkip() {
        val d = SkipDetector()
        d.context("spotify:playlist:p1")
        assertNull(d.see(seen(t(1), 0, 0)))
        val o = d.see(seen(t(2), 0, 20_000))!!
        assertEquals(t(1), o.uri)
        assertTrue(o.skipped)
        assertEquals("spotify:playlist:p1", o.context)
    }

    @Test fun halfwayOrTheEndIsAPlay() {
        val d = SkipDetector()
        d.see(seen(t(1), 0, 0))
        assertFalse(d.see(seen(t(2), 0, 100_000))!!.skipped)
        // Position reported near the end, then the next track: it ended by itself.
        d.see(seen(t(2), 198_500, 100_500))
        assertFalse(d.see(seen(t(3), 0, 101_000))!!.skipped)
    }

    @Test fun pausedStoppedAndRepeatedStatesTeachNothing() {
        val d = SkipDetector()
        d.see(seen(t(1), 0, 0))
        assertNull(d.see(seen(t(1), 5_000, 5_000, paused = true)))
        // Paused at 5 s, then he picks another song: not a skip.
        assertNull(d.see(seen(t(2), 0, 60_000)))
        // Stopped (no track), then a new one: nothing to judge.
        assertNull(d.see(seen(null, 0, 61_000)))
        assertNull(d.see(seen(t(3), 0, 62_000)))
        // A podcast or ad is not a song.
        assertNull(d.see(seen("spotify:episode:x", 0, 63_000)))
    }

    @Test fun unknownLengthUsesThirtySeconds() {
        val d = SkipDetector()
        d.see(seen(t(1), 0, 0, dur = 0))
        assertTrue(d.see(seen(t(2), 0, 10_000, dur = 0))!!.skipped)
        assertFalse(d.see(seen(t(3), 0, 50_000))!!.skipped)
    }

    @Test fun changesZeusMadeAreNotLearned() {
        val d = SkipDetector()
        d.see(seen(t(1), 0, 0))
        d.ours(1_000)
        assertNull(d.see(seen(t(2), 0, 2_000)))
        // Only the one change: his own next skip counts.
        assertTrue(d.see(seen(t(3), 0, 5_000))!!.skipped)
    }

    // ---- rule ----

    @Test fun usuallySkippedIsThreeOfTheLastFour() {
        assertFalse(SkipRules.usuallySkipped(listOf(true, true)))
        assertTrue(SkipRules.usuallySkipped(listOf(true, true, true)))
        assertFalse(SkipRules.usuallySkipped(listOf(true, true, false)))
        assertTrue(SkipRules.usuallySkipped(listOf(false, true, true, false, true, true)))
        assertFalse(SkipRules.usuallySkipped(listOf(true, true, true, false, false, true)))
    }

    private fun outcome(skipped: Boolean, n: Int = 1) = Outcome(t(n), "Song", "Artist", skipped, "spotify:playlist:p1")

    @Test fun recordKeepsCountsAndTheLastSix() {
        var s: TrackStat? = null
        repeat(8) { s = SkipRules.record(s, outcome(it % 2 == 0), it * 1_000L) }
        assertEquals(4, s!!.plays)
        assertEquals(4, s!!.skips)
        assertEquals(6, s!!.last.size)
        assertEquals(7_000L, s!!.lastSeen)
        assertEquals("spotify:playlist:p1", s!!.context)
    }

    // ---- second chance ----

    @Test fun oneComebackAfterThirtyDays() {
        var s: TrackStat? = null
        repeat(3) { s = SkipRules.record(s, outcome(true), 0) }
        assertTrue(SkipRules.out(s, 29 * day))
        assertFalse(SkipRules.out(s, 30 * day))
        // Skipped on its comeback: out for good.
        s = SkipRules.record(s, outcome(true), 30 * day)
        assertTrue(s!!.comebackUsed)
        assertTrue(SkipRules.out(s, 400 * day))
    }

    @Test fun playedOnItsComebackItStartsOver() {
        var s: TrackStat? = null
        repeat(3) { s = SkipRules.record(s, outcome(true), 0) }
        s = SkipRules.record(s, outcome(false), 31 * day)
        assertEquals(listOf(false), s!!.last)
        assertNull(s!!.outSince)
        assertFalse(SkipRules.out(s, 32 * day))
    }

    // ---- uris selection ----

    @Test fun pickLeavesOutUsuallySkippedAndSplitsAtTheLimit() {
        val tracks = (1..150).map(::t) + "spotify:local:a:b:c:1" + t(1)
        var bad: TrackStat? = null
        repeat(3) { bad = SkipRules.record(bad, outcome(true, 7), 0) }
        val p = SkipRules.pick(tracks, mapOf(t(7) to bad!!), day, random = Random(1))
        assertEquals(1, p.leftOut)
        assertEquals(100, p.uris.size)
        assertEquals(49, p.rest.size)
        assertFalse(t(7) in p.uris + p.rest)
        assertEquals((1..150).map(::t).toSet() - t(7), (p.uris + p.rest).toSet())
        assertTrue(p.uris != (1..150).map(::t).filter { it != t(7) }.take(100))
    }

    @Test fun bookPersistsAndBringBackForgets() {
        var blob: String? = null
        val store = object : SkipStore { override fun load() = blob; override fun save(json: String) { blob = json } }
        val book = SkipBook(store)
        repeat(3) { book.record(outcome(true, 7), 0) }
        book.record(outcome(false, 8), 0)
        val again = SkipBook(store)
        assertEquals(listOf(t(7)), again.out(day).map { it.uri })
        assertEquals(3, again.get(t(7))!!.skips)
        again.forget(t(7))
        assertTrue(SkipBook(store).out(day).isEmpty())
    }

    // ---- Web API ----

    @Test fun playlistTracksPagesAndPlayUrisSendsTheList() {
        val calls = ArrayList<Triple<String, String, String?>>()
        val web = SpotifyWeb({ m, url, _, body ->
            calls += Triple(m, url, body)
            when {
                "/tracks" in url && "offset=0" in url -> 200 to """{"items":[${(1..100).joinToString(",") { """{"track":{"name":"S$it","uri":"spotify:track:$it","artists":[{"name":"A"}]}}""" }}]}"""
                "/tracks" in url -> 200 to """{"items":[{"track":{"name":"S101","uri":"spotify:track:101","artists":[{"name":"A"}]}}]}"""
                "/devices" in url -> 200 to """{"devices":[{"id":"ph","name":"Phone","type":"Smartphone","is_active":false}]}"""
                else -> 204 to ""
            }
        }) { "at1" }
        assertEquals(101, web.playlistTracks("spotify:playlist:p1").size)
        web.playUris(listOf(t(1), t(2)))
        val play = calls.last()
        assertEquals("PUT", play.first)
        assertEquals("https://api.spotify.com/v1/me/player/play?device_id=ph", play.second)
        assertEquals("""{"uris":["spotify:track:1","spotify:track:2"]}""", play.third)
        assertTrue(calls.all { it.second.startsWith("https://api.spotify.com/v1/") })
    }

    @Test fun nonPremiumPlayUrisThrows() {
        val web = SpotifyWeb({ _, url, _, _ -> if ("/devices" in url) 200 to """{"devices":[]}""" else 403 to """{"error":{"status":403}}""" }) { "at1" }
        try { web.playUris(listOf(t(1))); org.junit.Assert.fail("should throw") } catch (_: SpotifyError) { }
    }
}
