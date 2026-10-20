package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs
import kotlin.math.sin

/** The drive map's path: thinned as it comes, simplified, sealed in its own file for 90 days, fitted to the map. */
class TrackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = Fx.at(java.time.LocalDate.of(2026, 10, 17), 12, 55)

    @Test fun aPointIsKeptEvery25MetresOr10SecondsAndOnlyWhenGpsIsGood() {
        val crawl = TrackRecorder()
        // 1 Hz at ~2 m a second: the 10 s rule keeps one in ten.
        repeat(61) { crawl.add(t0 + it * 1000L, 42.95 + it * 0.000018, -85.69, 2.0, 8f) }
        assertEquals(7, crawl.size)
        val fast = TrackRecorder()
        // 1 Hz at ~30 m a second: every fix is 25 m on.
        repeat(20) { fast.add(t0 + it * 1000L, 42.95 + it * 0.00027, -85.69, 30.0, 8f) }
        assertEquals(20, fast.size)
        val rough = TrackRecorder()
        rough.add(t0, 42.95, -85.69, 10.0, 30f)
        rough.add(t0 + 20_000, 42.96, -85.69, 10.0, 45f)
        assertEquals("30 m and worse are dropped", 0, rough.size)
        rough.add(t0 + 40_000, 42.97, -85.69, 12.5, 29f)
        assertEquals(TrackPoint(t0 + 40_000, 42.97, -85.69, 45), rough.finish().single())
    }

    @Test fun simplifyFitsInTheCapKeepsTheEndsAndLeavesShortPathsAlone() {
        val wiggly = (0 until 6000).map { i -> TrackPoint(t0 + i * 1000L, 42.9 + i * 0.0002, -85.7 + 0.003 * sin(i / 7.0), 50) }
        val out = Track.simplify(wiggly, Track.MAX_POINTS)
        assertTrue(out.size.toString(), out.size in 2..Track.MAX_POINTS)
        assertEquals(wiggly.first(), out.first()); assertEquals(wiggly.last(), out.last())
        val straight = (0 until 3000).map { i -> TrackPoint(t0 + i * 1000L, 42.9 + i * 0.0001, -85.7, 50) }
        assertEquals(listOf(straight.first(), straight.last()), Track.simplify(straight, 1500))
        val short = wiggly.take(40)
        assertEquals(short, Track.simplify(short, Track.MAX_POINTS))
        val route = syntheticRoute("d1", t0)
        assertTrue(route.points.size in 50..Track.MAX_POINTS)
    }

    @Test fun routesAreSealedAndReadBack() {
        val sealer = JceSealer()
        val files = RouteFiles(tmp.root, sealer)
        val r = syntheticRoute("d1", t0)
        files.save(r)
        val raw = tmp.root.resolve("d1.sealed").readBytes()
        val text = String(raw, Charsets.ISO_8859_1)
        assertFalse("no coordinates in the clear", text.contains("42.9") || text.contains("85.6") || text.contains("points"))
        assertEquals(r, files.load("d1"))
        assertNull("another key opens nothing", RouteFiles(tmp.root, JceSealer()).load("d1"))
        assertTrue(RouteFiles(tmp.root, JceSealer()).all().isEmpty())
        assertNull("ids can't climb out of the folder", files.load("../odyssey"))
        files.save(r.copy(id = "../x"))
        assertFalse(tmp.root.parentFile!!.resolve("x.sealed").exists())
    }

    @Test fun routesGoAfter90DaysOrWhenDeletedAndNothingElseMoves() {
        val files = RouteFiles(tmp.root, JceSealer())
        val day = 86_400_000L
        val now = t0 + 200 * day
        files.save(DriveRoute("old", now - 90 * day, listOf(TrackPoint(1, 42.9, -85.7, 10))))
        files.save(DriveRoute("recent", now - 89 * day, listOf(TrackPoint(1, 42.9, -85.7, 10))))
        files.save(DriveRoute("today", now - 1000, listOf(TrackPoint(1, 42.9, -85.7, 10))))
        tmp.root.resolve("other.txt").writeText("kept")
        assertEquals(listOf("old"), files.prune(now))
        assertEquals(setOf("recent", "today"), files.all().map { it.id }.toSet())
        assertTrue(files.delete("today"))
        assertFalse(files.delete("today"))
        assertEquals(listOf("recent"), files.all().map { it.id })
        assertEquals("kept", tmp.root.resolve("other.txt").readText())
        // The drive reports live in odyssey.sealed; a route's 90 days never touch them.
        val d = OdysseyData(drives = listOf(DriveReport("old", end = now - 90 * day)))
        assertEquals(1, d.drives.size)
    }

    /** Bands are mph (12.5, 50, 75) over speeds stored in km/h. */
    @Test fun speedBands() {
        assertEquals(SpeedBand.SLOW, Track.band(0)); assertEquals(SpeedBand.SLOW, Track.band(20))
        assertEquals(SpeedBand.STEADY, Track.band(21)); assertEquals(SpeedBand.STEADY, Track.band(80))
        assertEquals(SpeedBand.FAST, Track.band(81)); assertEquals(SpeedBand.FAST, Track.band(120))
        assertEquals(SpeedBand.OVER, Track.band(121))
        assertEquals("10 mph over a 30 mph road", SpeedBand.OVER, Track.band(65, limitKmh = 48))
        assertEquals(SpeedBand.FAST, Track.band(125, limitKmh = 113))
    }

    @Test fun theMapFitsTheRouteInsideItsPadding() {
        val r = syntheticRoute("d1", t0)
        val b = Track.bounds(r.points.map { it.lat to it.lng })!!
        val tile = 256f * 2
        for ((w, h) in listOf(704f to 520f, 1360f to 520f, 300f to 900f)) {
            val v = Track.fit(b, w, h, 56f, tile)
            val xy = r.points.map { Track.project(v, it.lat, it.lng, w, h, tile) }
            assertTrue("$w x $h", xy.all { (x, y) -> x >= 55f && x <= w - 55f && y >= 55f && y <= h - 55f })
            // Tight on at least one axis.
            val spanX = xy.maxOf { it.first } - xy.minOf { it.first }; val spanY = xy.maxOf { it.second } - xy.minOf { it.second }
            assertTrue("$w x $h: $spanX $spanY", abs(spanX - (w - 112f)) < 2f || abs(spanY - (h - 112f)) < 2f)
        }
        val one = Track.fit(Bounds(42.95, -85.69, 42.95, -85.69), 700f, 500f, 56f, tile)
        assertEquals(16.0, one.zoom, 0.0)
        val (x, y) = Track.project(one, 42.95, -85.69, 700f, 500f, tile)
        assertEquals(350f, x, 0.5f); assertEquals(250f, y, 0.5f)
        assertNull(Track.bounds(emptyList()))
    }

    @Test fun tilesCoverTheViewAndTheScaleBarIsRound() {
        val v = MapView(12.4, Track.mercX(-85.66), Track.mercY(42.98))
        val tiles = Track.tilesFor(v, 704f, 520f, 512f)
        assertTrue(tiles.all { it.first == 12 })
        assertTrue(tiles.size in 2..9)
        val urls = tiles.map { (z, x, y) -> MapTiles.url(z, x, y, dark = true) }
        assertTrue(urls.all { dev.phonerecall.aeolia.WeatherNet.allows(it) })
        assertTrue(MapTiles.url(3, -1, 2, dark = false).endsWith("World_Light_Gray_Base/MapServer/tile/3/2/7"))
        val mpp = Track.metersPerPx(v, 42.98, 512f)
        val (m, label) = Track.scale(mpp, 192f)
        assertTrue(m / mpp <= 192)
        assertEquals("1 mi", label)
        assertEquals("500 ft", Track.scale(0.5, 400f).second)
    }

    @Test fun speedSeriesForTheChart() {
        val r = syntheticRoute("d1", t0)
        val s = Track.speedSeries(r.points)
        assertTrue(s.size in 2..120)
        assertEquals(s.sortedBy { it.first }, s)
        val pts = listOf(TrackPoint(0, 42.9, -85.7, 36), TrackPoint(100_000, 42.909, -85.7, 36))
        assertEquals(36, Track.averageKmh(pts))
        assertEquals(pts.map { it.t to it.kmh }, Track.speedSeries(pts))
    }
}
