package dev.phonerecall.odyssey

import android.graphics.BitmapFactory
import android.util.Log
import dev.phonerecall.aeolia.WeatherNet
import java.time.Instant

/**
 * The frames the snow check reads, fetched by tile through Aeolia's WeatherNet (its allowlist, its
 * paths): IEM's latest NEXRAD mosaic as "now", HRRR forecast reflectivity every 15 minutes after that,
 * and RainViewer's nowcast frames whenever it publishes ones the allowlist accepts (today it publishes
 * past frames only, so in practice the forecast is HRRR's). Only zoom-7 tile numbers go out.
 */
object Radar {
    private const val TAG = "Zeus-Radar"
    private const val IEM = "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/"
    private const val Q = 15 * 60_000L

    fun frames(points: List<Pt>, now: Long, hours: Int = 3): List<RadarFrame> {
        val tiles = Tiles.needed(points)
        val out = ArrayList<RadarFrame>()
        fun grab(time: Long, source: String, palette: (Int) -> Int, url: (Int, Int) -> String) {
            val got = tiles.mapNotNull { (x, y) -> decode(url(x, y))?.let { (x to y) to it } }.toMap()
            if (got.isNotEmpty()) out += Tiles.frame(time, source, got, palette)
        }
        val json = dev.phonerecall.mind.stateJson
        runCatching {
            val valid = Instant.parse(json.readTree(WeatherNet.text(WeatherNet.N0Q)).path("meta").path("valid").asText()).toEpochMilli()
            grab(valid, "NOAA radar", Palette::iem) { x, y -> "${IEM}nexrad-n0q-900913/${Tiles.ZOOM}/$x/$y.png" }
        }.onFailure { Log.w(TAG, "N0Q: ${it.message}") }
        runCatching {
            val init = Instant.parse(json.readTree(WeatherNet.text(WeatherNet.HRRR)).path("model_init_utc").asText()).toEpochMilli()
            val base = (now + Q - 1) / Q * Q
            for (ahead in 0..hours * 4) {
                val t = base + ahead * Q
                val minute = Math.round((t - init) / Q.toDouble()).toInt() * 15
                if (minute < 15 || minute > 1080) continue
                val layer = "hrrr::REFD-F" + minute.toString().padStart(4, '0') + "-0"
                grab(init + minute * 60_000L, "HRRR forecast", Palette::iem) { x, y -> "$IEM$layer/${Tiles.ZOOM}/$x/$y.png" }
            }
        }.onFailure { Log.w(TAG, "HRRR: ${it.message}") }
        runCatching {
            val rv = json.readTree(WeatherNet.text(WeatherNet.RAINVIEWER))
            val host = rv.path("host").asText()
            for (f in rv.path("radar").path("nowcast")) {
                val path = f.path("path").asText()
                if (!WeatherNet.allows("$host$path/256/${Tiles.ZOOM}/0/0/2/1_1.png")) continue
                grab(f.path("time").asLong() * 1000, "RainViewer nowcast", Palette::rainviewer) { x, y -> "$host$path/256/${Tiles.ZOOM}/$x/$y/2/1_1.png" }
            }
        }.onFailure { Log.w(TAG, "RainViewer: ${it.message}") }
        return out.sortedBy { it.time }
    }

    private fun decode(url: String): IntArray? = runCatching {
        val b = WeatherNet.get(url)
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return null
        if (bmp.width != 256 || bmp.height != 256) return null
        IntArray(256 * 256).also { bmp.getPixels(it, 0, 256, 0, 0, 256, 256); bmp.recycle() }
    }.getOrNull()
}
