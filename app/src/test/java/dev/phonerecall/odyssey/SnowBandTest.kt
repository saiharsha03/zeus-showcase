package dev.phonerecall.odyssey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Lake-effect bands against synthetic radar: a strip of heavy echoes over part of the commute, on a clock. */
class SnowBandTest {
    private val day = LocalDate.of(2026, 11, 17)
    private val route = Fx.line(Pt(Fx.HOME_LAT, Fx.HOME_LNG), Pt(Fx.WORK_LAT, Fx.WORK_LNG))
    private val roads = listOf(RoadSpan(0, "Oak Ave"), RoadSpan(4, "I-190"))
    private val dep = Fx.at(day, 8, 20)
    private val drive = 22 * 60
    private val now = Fx.at(day, 7, 0)

    /** Frames every 10 minutes, 7:00 to 10:00; heavy (30 dBZ) between `south` and `north` while `on` holds. */
    private fun frames(south: Double = 42.900, north: Double = 42.930, until: Long = Fx.at(day, 10, 0), on: (Long) -> Boolean = { it in Fx.at(day, 8, 10)..Fx.at(day, 8, 40) }) =
        (0..18).map { Fx.at(day, 7, 0) + it * 10 * Fx.MIN }.filter { it <= until }.map { t ->
            RadarFrame(t, "test") { lat, _ -> if (on(t) && lat in south..north) 30 else 0 }
        }

    @Test fun aBandOnTheRouteNamesTheRoadTheWindowAndTwoWaysAround() {
        val f = frames()
        val a = SnowBand.advise(route, roads, drive, dep, f, now)!!
        assertEquals("I-190", a.road)
        assertEquals(Fx.at(day, 8, 10), a.from)
        assertEquals(Fx.at(day, 8, 40), a.to)
        // The earlier departure is clear and the next five minutes later is not; the same for the later one.
        val before = a.before!!; val after = a.after!!
        assertTrue(before < dep && after > dep)
        assertTrue(SnowBand.hits(route, drive, before, f)!!.size < SnowBand.MIN_POINTS)
        assertTrue(SnowBand.hits(route, drive, before + 5 * Fx.MIN, f)!!.size >= SnowBand.MIN_POINTS)
        assertTrue(SnowBand.hits(route, drive, after, f)!!.size < SnowBand.MIN_POINTS)
        assertTrue(SnowBand.hits(route, drive, after - 5 * Fx.MIN, f)!!.size >= SnowBand.MIN_POINTS)
        val (title, body) = SnowBand.words(a, snow = true, Fx.zone)
        assertEquals("Snow band crosses I-190 8:10–8:40", title)
        assertTrue(body, Regex("""Leave \d{1,2}:\d{2} or after \d{1,2}:\d{2}\.""").matches(body))
        assertEquals("Heavy band crosses I-190 8:10–8:40", SnowBand.words(a, snow = false, Fx.zone).first)
    }

    @Test fun clearRadarIsSilence() {
        assertNull(SnowBand.advise(route, roads, drive, dep, frames(on = { false }), now))
    }

    @Test fun aBandOffTheRouteIsSilence() {
        // South of downtown: the whole commute is north of it.
        assertNull(SnowBand.advise(route, roads, drive, dep, frames(south = 42.80, north = 42.85), now))
    }

    @Test fun oneHeavyPixelIsNotABand() {
        val one = route[route.size / 2]
        val f = (0..18).map { Fx.at(day, 7, 0) + it * 10 * Fx.MIN }.map { t -> RadarFrame(t, "test") { lat, lng -> if (lat == one.lat && lng == one.lng) 45 else 0 } }
        assertNull(SnowBand.advise(route, roads, drive, dep, f, now))
    }

    @Test fun aBandThatPassedBeforeHeLeavesIsSilence() {
        assertNull(SnowBand.advise(route, roads, drive, dep, frames(on = { it <= Fx.at(day, 7, 40) }), now))
    }

    @Test fun framesThatStopBeforeTheDriveSayNothing() {
        assertNull(SnowBand.advise(route, roads, drive, dep, frames(until = Fx.at(day, 8, 0)), now))
    }

    @Test fun noEarlierDepartureInThePastAndNoneAfterBeyondTheForecast() {
        // At 8:10 there is no time to leave earlier; the band lasts past what the frames cover.
        val a = SnowBand.advise(route, roads, drive, dep, frames(on = { it >= Fx.at(day, 8, 10) }), Fx.at(day, 8, 10))!!
        assertNull(a.before)
        assertNull(a.after)
        assertEquals("It sits on the road for the whole forecast. Allow extra time.", SnowBand.words(a, true, Fx.zone).second)
    }

    @Test fun onlyOctoberToApril() {
        assertTrue(SnowBand.season(LocalDate.of(2026, 10, 1)))
        assertTrue(SnowBand.season(LocalDate.of(2027, 1, 15)))
        assertTrue(SnowBand.season(LocalDate.of(2027, 4, 30)))
        assertFalse(SnowBand.season(LocalDate.of(2027, 5, 1)))
        assertFalse(SnowBand.season(LocalDate.of(2026, 7, 4)))
    }

    @Test fun theNoteCarriesWordsAndNoCoordinates() {
        val a = SnowBand.advise(route, roads, drive, dep, frames(), now)!!
        val n = SnowBand.note("snow:commute:$day", a, true, "Office", dep, 22, "HRRR forecast", now, Fx.zone)
        assertEquals("snow", n.kind)
        assertEquals(listOf("Your drive" to "8:20, 22 min to Office", "From" to "HRRR forecast"), n.facts.map { it.label to it.value })
        assertTrue(n.expiresAt > a.to)
        val text = n.title + n.body + n.facts.joinToString { it.value }
        assertFalse(text, Regex("""-?\d{1,3}\.\d{3,}""").containsMatchIn(text))
    }

    // ---- tiles and colours ----

    @Test fun tileMathAndARouteNeedsOneTile() {
        assertEquals(Tiles.Px(1, 1, 0, 0), Tiles.at(0.0, 0.0, 1))
        val p = Tiles.at(Fx.HOME_LAT, Fx.HOME_LNG)
        assertEquals(33, p.x)
        assertEquals(47, p.y)
        assertEquals(setOf(33 to 47), Tiles.needed(route))
    }

    @Test fun aSyntheticTileReadsBackAsDbz() {
        val tile = IntArray(256 * 256)
        val p = Tiles.at(Fx.HOME_LAT, Fx.HOME_LNG)
        tile[p.py * 256 + p.px] = 0xff01c501.toInt()
        val f = Tiles.frame(0L, "test", mapOf((p.x to p.y) to tile), Palette::iem)
        assertEquals(25, f.dbz(Fx.HOME_LAT, Fx.HOME_LNG))
        assertEquals(0, f.dbz(Fx.WORK_LAT, Fx.WORK_LNG))
        assertNull("a point off the fetched tiles is no data", f.dbz(40.7, -74.0))
    }

    @Test fun paletteReadsTheRampAndIgnoresClutter() {
        assertEquals(0, Palette.iem(0x00000000))
        assertEquals(30, Palette.iem(0xff008e00.toInt()))
        assertEquals(35, Palette.iem(0xfffdf802.toInt()))
        assertEquals(60, Palette.iem(0xfff800fd.toInt()))
        assertEquals("a weak grey-blue return", 0, Palette.iem(0xffb0b6b4.toInt()))
        assertEquals(10, Palette.rainviewer(0xff88ddee.toInt()))
        assertEquals(35, Palette.rainviewer(0xffffee00.toInt()))
    }

    @Test fun radarUrlsAreTilesOnTheAllowlist() {
        val (x, y) = Tiles.needed(route).single()
        for (u in listOf(
            "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/7/$x/$y.png",
            "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/hrrr::REFD-F0060-0/7/$x/$y.png",
            "https://tilecache.rainviewer.com/v2/radar/a1b2c3/256/7/$x/$y/2/1_1.png",
        )) assertTrue(u, dev.phonerecall.aeolia.WeatherNet.allows(u))
    }

    // ---- TomTom's shape ----

    @Test fun parsesThePolylineAndRoadNumbersAndThinsThem() {
        val pts = (0..40).joinToString(",") { """{"latitude":${42.955 - it * 0.0017},"longitude":-85.69}""" }
        val json = """{"routes":[{"summary":{"lengthInMeters":7700,"travelTimeInSeconds":1320,"trafficDelayInSeconds":60},
            "legs":[{"points":[$pts]}],
            "guidance":{"instructions":[{"pointIndex":0,"street":"Oak Ave"},{"pointIndex":12,"roadNumbers":["I-190"],"street":"Niagara Thruway"},{"pointIndex":40,"street":""}]}}]}"""
        val r = TomTom.parse(json).single()
        assertEquals(41, r.points.size)
        assertEquals(listOf(RoadSpan(0, "Oak Ave"), RoadSpan(12, "I-190")), r.roads)
        assertEquals("I-190", TomTom.roadAt(r.roads, 30))
        val (thin, spans) = TomTom.thin(r.points, r.roads)
        assertTrue(thin.size in 3 until r.points.size)
        assertEquals(r.points.first(), thin.first()); assertEquals(r.points.last(), thin.last())
        assertEquals("the I-190 span still starts at its own point", r.points[12], thin[spans[1].from])
    }

    @Test fun notesAndShapesSurviveTheSealedStore() {
        val a = SnowBand.advise(route, roads, drive, dep, frames(), now)!!
        val d = Fx.on().note(SnowBand.note("snow:x", a, true, "Office", dep, 22, "HRRR forecast", now, Fx.zone), now)
            .copy(shapes = listOf(RouteShape("k1>k2", now, drive, route, roads)))
        val s = JceSealer()
        val back = OdysseyStore.decode(s, OdysseyStore.encode(s, d))!!
        assertEquals(d.notes, back.notes)
        assertEquals(d.shapes, back.shapes)
        assertNotNull(back.shape("k1", "k2"))
    }
}
