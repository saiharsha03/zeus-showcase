package dev.phonerecall.aeolia

import dev.phonerecall.brain.BlockedHostException
import dev.phonerecall.brain.NetGate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/**
 * Aeolia's network door: NetGate's weather hosts, narrowed to the exact paths BetweenWeather calls
 * (ported from its NetworkPolicy.java). The page and the background check both come through here.
 * BetweenWeather's two Google holiday calendars are not here: Zeus has no calendar.google.com, so the
 * Holidays card uses the dates bundled in holiday-data.js.
 */
object WeatherNet {
    private val RAINVIEWER_TILE = Regex("/v2/radar/[a-f0-9]+/256/[3-7]/[0-9]+/[0-9]+/2/1_1\\.png")
    private val MESONET_TILE = Regex("/cache/tile\\.py/1\\.0\\.0/(nexrad-n0q-900913(-m[0-5][05]m)?|hrrr(::|%3A%3A)REFD-F[0-9]{4}-0)/[0-9]{1,2}/[0-9]{1,5}/[0-9]{1,5}\\.png")
    private val ARCGIS_TILE = Regex("/ArcGIS/rest/services/Canvas/World_(Dark|Light)_Gray_(Base|Reference)/MapServer/tile/[0-9]{1,2}/[0-9]{1,5}/[0-9]{1,5}")
    const val N0Q = "https://mesonet.agron.iastate.edu/data/gis/images/4326/USCOMP/n0q_0.json"
    const val HRRR = "https://mesonet.agron.iastate.edu/data/gis/images/4326/hrrr/refd_0000.json"
    const val RAINVIEWER = "https://api.rainviewer.com/public/weather-maps.json"
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** NetGate's rules (https, exact host, port 443, no user info) plus the path for that host. */
    fun allows(url: String): Boolean {
        if (!NetGate.allows(url)) return false
        val u = try { URI(url) } catch (_: Exception) { return false }
        if (u.scheme != "https" || u.rawFragment != null) return false
        val p = u.rawPath ?: return false
        val noQuery = u.rawQuery == null
        return when (u.host.lowercase()) {
            "api.open-meteo.com" -> p == "/v1/forecast"
            "geocoding-api.open-meteo.com" -> p == "/v1/search"
            "air-quality-api.open-meteo.com" -> p == "/v1/air-quality"
            "api.weather.gov" -> p == "/alerts/active" || p == "/alerts"
            "api.rainviewer.com" -> p == "/public/weather-maps.json"
            "tilecache.rainviewer.com" -> RAINVIEWER_TILE.matches(p)
            "mesonet.agron.iastate.edu" -> noQuery && (p == "/data/gis/images/4326/USCOMP/n0q_0.json" || p == "/data/gis/images/4326/hrrr/refd_0000.json" || MESONET_TILE.matches(p))
            "server.arcgisonline.com" -> noQuery && ARCGIS_TILE.matches(p)
            else -> false
        }
    }

    /** NetGate's check (which logs a block) and then the path rule. */
    fun require(url: String) {
        NetGate.check(url)
        if (!allows(url)) throw BlockedHostException(url)
    }

    /** GET with redirects followed by hand, each hop checked again. 8 MB at most. */
    fun get(start: String): ByteArray {
        var url = start
        repeat(4) {
            require(url)
            val t0 = System.currentTimeMillis()
            val c = dev.phonerecall.brain.TlsGuard.open(url)
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.setRequestProperty("User-Agent", "Zeus/aeolia (personal Android weather)")
                c.setRequestProperty("Accept", "application/json, application/geo+json, image/png, image/jpeg")
                val code = c.responseCode
                NetGate.logRequest("GET", url, code, System.currentTimeMillis() - t0)
                if (code in 300..399) {
                    url = URL(URL(url), c.getHeaderField("Location") ?: throw IOException("Redirect without a location")).toString()
                    return@repeat
                }
                if (code != 200) throw IOException("Weather service returned $code")
                c.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (out.size() + n > MAX_BYTES) throw IOException("Response is too large")
                        out.write(buf, 0, n)
                    }
                    return out.toByteArray()
                }
            } finally { c.disconnect() }
        }
        throw IOException("Too many redirects")
    }

    fun text(url: String): String = get(url).toString(Charsets.UTF_8)

    /** PNG, JPEG, or JSON (what these hosts send), from the first bytes. */
    fun mime(b: ByteArray): String = when {
        b.size > 1 && (b[0].toInt() and 0xff) == 0x89 && b[1] == 'P'.code.toByte() -> "image/png"
        b.size > 1 && (b[0].toInt() and 0xff) == 0xff && (b[1].toInt() and 0xff) == 0xd8 -> "image/jpeg"
        else -> "application/json"
    }

    fun forecastUrl(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,apparent_temperature,relative_humidity_2m,is_day,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,surface_pressure,dew_point_2m,visibility,precipitation,uv_index" +
        "&minutely_15=precipitation&forecast_minutely_15=8&past_hours=24" +
        "&hourly=is_day,temperature_2m,precipitation_probability,precipitation,rain,showers,weather_code,snowfall,visibility,wind_gusts_10m,apparent_temperature,uv_index" +
        "&daily=temperature_2m_max,temperature_2m_min,weather_code,sunrise,sunset,uv_index_max,snowfall_sum,rain_sum,showers_sum,precipitation_sum,precipitation_probability_max" +
        "&timezone=auto&forecast_days=10"

    fun airUrl(lat: Double, lon: Double) = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5,pm10&timezone=auto"
    fun alertsUrl(lat: Double, lon: Double) = "https://api.weather.gov/alerts/active?point=$lat,$lon"
    fun searchUrl(q: String) = "https://geocoding-api.open-meteo.com/v1/search?count=8&language=en&name=" + URLEncoder.encode(q, "UTF-8")
}
