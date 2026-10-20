package dev.phonerecall.aeolia

import dev.phonerecall.aeolia.AeoliaRoutes.Route
import dev.phonerecall.brain.BlockedHostException
import dev.phonerecall.brain.NetGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** NetGate's weather hosts, WeatherNet's paths, and what the WebView may load. */
class WeatherNetTest {
    private val tiles = listOf(
        "https://tilecache.rainviewer.com/v2/radar/a1b2c3d4e5f6/256/5/9/11/2/1_1.png",
        "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913/6/17/23.png",
        "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/nexrad-n0q-900913-m30m/6/17/23.png",
        "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/hrrr::REFD-F0060-0/6/17/23.png",
        "https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/6/23/17",
    )

    @Test fun everyUrlTheAppBuildsIsAllowed() {
        val urls = listOf(
            WeatherNet.forecastUrl(42.8864, -78.8784), WeatherNet.airUrl(42.8864, -78.8784), WeatherNet.alertsUrl(42.8864, -78.8784),
            WeatherNet.searchUrl("Buffalo, NY"), WeatherNet.N0Q, WeatherNet.HRRR, WeatherNet.RAINVIEWER,
        ) + tiles
        for (u in urls) assertTrue(u, WeatherNet.allows(u))
    }

    @Test fun netGateHasExactlyTheWeatherHostsAndNoSubdomains() {
        assertEquals(8, NetGate.WEATHER_HOSTS.size)
        for (h in NetGate.WEATHER_HOSTS) assertTrue(h, NetGate.allows("https://$h/"))
        for (u in listOf(
            "https://api.open-meteo.com.evil.example/v1/forecast",
            "https://evil.api.open-meteo.com/v1/forecast",
            "https://open-meteo.com/v1/forecast",
            "https://weather.gov/alerts/active",
            "https://www.weather.gov/alerts",
            "https://calendar.google.com/calendar/ical/en.usa%23holiday%40group.v.calendar.google.com/public/basic.ics",
            "https://tile.openstreetmap.org/6/17/23.png",
        )) assertFalse(u, NetGate.allows(u))
    }

    @Test fun refusesAnythingOffTheWeatherPaths() {
        for (u in listOf(
            "http://api.open-meteo.com/v1/forecast?latitude=1&longitude=2",       // cleartext
            "https://api.open-meteo.com:8443/v1/forecast",                         // port
            "https://user:pw@api.open-meteo.com/v1/forecast",                      // user info
            "https://api.open-meteo.com/v1/archive",                               // a path BetweenWeather never calls
            "https://api.weather.gov/points/42.88,-78.87",
            "https://api.weather.gov/alerts/active#x",                             // fragment
            "https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.json?x=1",
            "https://mesonet.agron.iastate.edu/cgi-bin/request/asos.py",
            "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/../../etc/6/17/23.png",
            "https://tilecache.rainviewer.com/v2/satellite/abc/256/5/9/11/0/0_0.png",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/6/23/17",
            "wss://api.weather.gov/alerts/active",
            "file:///data/data/dev.phonerecall/shared_prefs/aeolia.xml",
        )) assertFalse(u, WeatherNet.allows(u))
    }

    @Test fun requireThrowsAndNetGateLogsTheBlock() {
        val seen = mutableListOf<String>()
        val stop = NetGate.onBlocked { seen += it.url }
        try {
            WeatherNet.require("https://example.com/collect?id=1")
            fail("should block")
        } catch (_: BlockedHostException) {
        } finally { stop() }
        assertEquals(listOf("https://example.com/collect"), seen)
        try { WeatherNet.require("https://api.weather.gov/points/1,2"); fail("wrong path should block") } catch (_: BlockedHostException) {}
    }

    @Test fun webViewServesItsOwnAssetsOnly() {
        assertEquals(Route.Asset("aeolia/index.html", "text/html"), AeoliaRoutes.route(AeoliaRoutes.PAGE, "GET"))
        assertEquals(Route.Asset("aeolia/radar.js", "application/javascript"), AeoliaRoutes.route("https://aeolia.zeus.local/radar.js", "GET"))
        assertEquals(Route.Asset("aeolia/samsung/chars/rain-night.mp4", "video/mp4"), AeoliaRoutes.route("https://aeolia.zeus.local/samsung/chars/rain-night.mp4", "GET"))
        assertEquals(Route.Asset("aeolia/samsung/rain-ground.webp", "image/webp"), AeoliaRoutes.route("https://aeolia.zeus.local/samsung/rain-ground.webp", "GET"))
        for (u in listOf(
            "https://aeolia.zeus.local/../shared_prefs/aeolia.xml",
            "https://aeolia.zeus.local/samsung/chars/../../index.html.mp4",
            "https://aeolia.zeus.local/lemnos/term.html",
            "https://aeolia.zeus.local/third-party-notices.txt",
            "https://aeolia.zeus.local:444/radar.js",
            "http://aeolia.zeus.local/radar.js",
            "https://aeolia.zeus.local.evil.example/radar.js",
        )) assertEquals(u, Route.Block, AeoliaRoutes.route(u, "GET"))
    }

    @Test fun webViewFetchesOnlyAllowlistedWeatherGets() {
        for (u in tiles + WeatherNet.RAINVIEWER) assertEquals(u, Route.Fetch, AeoliaRoutes.route(u, "GET"))
        assertEquals(Route.Block, AeoliaRoutes.route(tiles[0], "POST"))
        assertEquals(Route.Block, AeoliaRoutes.route(AeoliaRoutes.PAGE, "POST"))
        for (u in listOf(
            "https://fonts.googleapis.com/css2?family=Roboto",
            "https://www.google-analytics.com/collect",
            "https://api.anthropic.com/v1/messages",   // allowed by NetGate for the brain, never for the page
            "https://site.api.espn.com/apis/site/v2/sports",
            "https://calendar.google.com/calendar/ical/en.usa%23holiday%40group.v.calendar.google.com/public/basic.ics",
        )) assertEquals(u, Route.Block, AeoliaRoutes.route(u, "GET"))
    }

    /** Every script and video the bundled page names exists and is routed as an asset. */
    @Test fun everyBundledFileThePageLoadsIsServed() {
        val dir = File("src/main/assets/aeolia")
        val html = File(dir, "index.html").readText()
        val scripts = Regex("<script src=\"([^\"]+)\"").findAll(html).map { it.groupValues[1] }.toList()
        assertTrue(scripts.size > 10)
        assertTrue("zeus-theme.js" in scripts)
        // The Samsung scene pack is local-only (not in the public repo); when it is present its files must be served too.
        val videos = File(dir, "samsung/chars").listFiles().orEmpty().map { "samsung/chars/" + it.name }
        val layers = File(dir, "samsung").listFiles().orEmpty().filter { it.isFile }.map { "samsung/" + it.name }
        for (f in scripts + videos + layers + "fonts/space_grotesk.ttf") {
            assertTrue(f, File(dir, f).isFile)
            assertTrue(f, AeoliaRoutes.route(AeoliaRoutes.PAGE + f, "GET") is Route.Asset)
        }
    }
}
