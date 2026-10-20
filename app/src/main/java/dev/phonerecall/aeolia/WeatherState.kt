package dev.phonerecall.aeolia

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** One active NWS alert, as the card, the notification and the widgets read it. */
@Immutable
data class WeatherAlert(
    val id: String,
    val event: String,
    /** NWS severity: Extreme, Severe, Moderate, Minor or Unknown. */
    val severity: String,
    val urgency: String = "Unknown",
    val headline: String = "",
    val description: String = "",
    val instruction: String = "",
    val area: String = "",
    val onset: Long? = null,
    val ends: Long,
    /** Earlier versions of this alert (an Update or a Cancel names them). */
    val references: List<String> = emptyList(),
) {
    val severe: Boolean get() = severity == "Extreme" || severity == "Severe"
    val rank: Int get() = when (severity) { "Extreme" -> 4; "Severe" -> 3; "Moderate" -> 2; "Minor" -> 1; else -> 0 }
}

@Immutable data class Hour(val at: Long, val tempC: Double?, val code: Int, val rainChance: Int?, val precipMm: Double, val snowCm: Double, val gustKmh: Double?)
@Immutable data class Day(val date: LocalDate, val highC: Double?, val lowC: Double?, val code: Int, val rainChance: Int?)

/** Open-Meteo's forecast, the parts Zeus uses outside the page. */
@Immutable
data class Forecast(
    val at: Long,
    val offsetSeconds: Int,
    val tempC: Double?,
    val feelsC: Double?,
    val code: Int,
    val isDay: Boolean,
    val windKmh: Double?,
    val hours: List<Hour>,
    val days: List<Day>,
)

/**
 * Aeolia's weather as the rest of Zeus reads it: the Olympus card, the alert notifications, and later
 * the home-screen widgets (WeatherStore.load(context) or WeatherStore.state). Temperatures are Celsius;
 * [f] converts. Written by the page's bridge and by the background check.
 */
@Immutable
data class WeatherState(
    val place: String = "",
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val country: String = "",
    val tempC: Double? = null,
    val feelsC: Double? = null,
    val code: Int = -1,
    val isDay: Boolean = true,
    val highC: Double? = null,
    val lowC: Double? = null,
    val rainChance: Int? = null,
    /** The next notable change in the next 12 hours, e.g. "Rain from 3 PM", or null when it stays as is. */
    val next: String? = null,
    val nextAt: Long? = null,
    val alerts: List<WeatherAlert> = emptyList(),
    /** False when NWS could not be checked (no alerts is not the same as unknown). */
    val alertsChecked: Boolean = false,
    val aqi: Int? = null,
    /** Nimbus's line on screen (Nano), for the widgets. */
    val quip: String? = null,
    val updatedAt: Long = 0L,
    val alertsAt: Long = 0L,
    val loaded: Boolean = false,
) {
    val hasData: Boolean get() = updatedAt > 0 && tempC != null
    fun activeAlerts(now: Long): List<WeatherAlert> = alerts.filter { it.ends > now }.sortedByDescending { it.rank }

    companion object {
        fun f(c: Double) = (c * 9 / 5 + 32).roundToInt()
        fun withForecast(s: WeatherState, fc: Forecast, now: Long): WeatherState {
            val today = fc.days.firstOrNull { it.date == LocalDateTime.ofEpochSecond(now / 1000, 0, ZoneOffset.ofTotalSeconds(fc.offsetSeconds)).toLocalDate() } ?: fc.days.firstOrNull()
            val change = NextChange.of(fc, now)
            return s.copy(
                tempC = fc.tempC, feelsC = fc.feelsC, code = fc.code, isDay = fc.isDay,
                highC = today?.highC, lowC = today?.lowC, rainChance = today?.rainChance,
                next = change?.first, nextAt = change?.second, updatedAt = now, loaded = true,
            )
        }
    }
}

/** Plain words for WMO weather codes, as the page's describe(). */
object Sky {
    fun words(c: Int): String = when {
        c < 0 -> "Unknown"
        c == 0 -> "Clear"
        c < 3 -> "Mostly clear"
        c == 3 -> "Cloudy"
        c < 50 -> "Fog"
        c < 60 -> "Drizzle"
        c < 70 -> "Rain"
        c < 80 -> "Snow"
        c < 85 -> "Rain showers"
        c < 90 -> "Snow showers"
        else -> "Thunderstorms"
    }

    enum class Kind { DRY, RAIN, SNOW, STORM }
    fun kind(c: Int): Kind = when {
        c >= 95 -> Kind.STORM
        c in 71..77 || c in 85..86 -> Kind.SNOW
        c in 51..67 || c in 80..82 -> Kind.RAIN
        else -> Kind.DRY
    }
}

/** The one change worth a line on Olympus: rain or snow starting or stopping, storms, strong gusts, a big drop. */
object NextChange {
    private const val WINDOW = 12

    fun of(fc: Forecast, now: Long): Pair<String, Long>? {
        val ahead = fc.hours.filter { it.at > now }.take(WINDOW)
        if (ahead.isEmpty()) return null
        val nowKind = wet(fc.hours.lastOrNull { it.at <= now } ?: ahead.first(), fc.code)
        for (h in ahead) {
            val k = wet(h, h.code)
            if (k != nowKind) {
                val words = when (k) { Sky.Kind.RAIN -> "Rain"; Sky.Kind.SNOW -> "Snow"; Sky.Kind.STORM -> "Storms"; Sky.Kind.DRY -> "Dry" }
                return "$words from ${clock(h.at, fc.offsetSeconds)}" to h.at
            }
            if ((h.gustKmh ?: 0.0) >= 50.0) return "Gusts to ${h.gustKmh!!.roundToInt()} km/h at ${clock(h.at, fc.offsetSeconds)}" to h.at
        }
        val start = fc.tempC ?: return null
        val coldest = ahead.filter { it.tempC != null }.minByOrNull { it.tempC!! } ?: return null
        if (start - coldest.tempC!! >= 6.0) {
            return "Down to ${WeatherState.f(coldest.tempC)}°F / ${coldest.tempC.roundToInt()}°C by ${clock(coldest.at, fc.offsetSeconds)}" to coldest.at
        }
        return null
    }

    /** An hour counts as wet when its code says so, or rain is likely and measurable. */
    private fun wet(h: Hour, code: Int): Sky.Kind {
        val k = Sky.kind(code)
        if (k != Sky.Kind.DRY) return k
        return if ((h.rainChance ?: 0) >= 60 && h.precipMm >= 0.3) (if (h.snowCm > 0.0) Sky.Kind.SNOW else Sky.Kind.RAIN) else Sky.Kind.DRY
    }

    fun clock(at: Long, offsetSeconds: Int): String {
        val t = LocalDateTime.ofEpochSecond(at / 1000, 0, ZoneOffset.ofTotalSeconds(offsetSeconds))
        val h = t.hour % 12
        return (if (h == 0) 12 else h).toString() + (if (t.minute != 0) ":%02d".format(t.minute) else "") + if (t.hour < 12) " AM" else " PM"
    }
}

/*
 * Parsers for the exact JSON Aeolia fetches (samples in app/src/test/resources/aeolia). Pure: a string
 * in, a model out. Anything missing is skipped, never thrown.
 */
object WeatherParse {
    private fun JSONObject.num(name: String): Double? = if (has(name) && !isNull(name)) optDouble(name).takeIf { !it.isNaN() } else null
    private fun JSONArray?.num(i: Int): Double? = if (this == null || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }
    private fun JSONObject.str(name: String): String = if (has(name) && !isNull(name)) optString(name) else ""

    /** Open-Meteo writes local times without an offset ("2026-10-19T21:30") plus utc_offset_seconds. */
    private fun local(s: String, offset: Int): Long? = runCatching {
        LocalDateTime.parse(s).toInstant(ZoneOffset.ofTotalSeconds(offset)).toEpochMilli()
    }.getOrNull()

    fun forecast(json: String): Forecast? = runCatching {
        val o = JSONObject(json)
        val off = o.optInt("utc_offset_seconds", 0)
        val c = o.getJSONObject("current")
        val h = o.optJSONObject("hourly")
        val hours = if (h == null) emptyList() else {
            val time = h.getJSONArray("time")
            (0 until time.length()).mapNotNull { i ->
                val at = local(time.getString(i), off) ?: return@mapNotNull null
                Hour(
                    at, h.optJSONArray("temperature_2m").num(i), h.optJSONArray("weather_code").num(i)?.toInt() ?: 0,
                    h.optJSONArray("precipitation_probability").num(i)?.roundToInt(), h.optJSONArray("precipitation").num(i) ?: 0.0,
                    h.optJSONArray("snowfall").num(i) ?: 0.0, h.optJSONArray("wind_gusts_10m").num(i),
                )
            }
        }
        val d = o.optJSONObject("daily")
        val days = if (d == null) emptyList() else {
            val time = d.getJSONArray("time")
            (0 until time.length()).mapNotNull { i ->
                val date = runCatching { LocalDate.parse(time.getString(i)) }.getOrNull() ?: return@mapNotNull null
                Day(date, d.optJSONArray("temperature_2m_max").num(i), d.optJSONArray("temperature_2m_min").num(i),
                    d.optJSONArray("weather_code").num(i)?.toInt() ?: 0, d.optJSONArray("precipitation_probability_max").num(i)?.roundToInt())
            }
        }
        Forecast(
            at = local(c.getString("time"), off) ?: 0L, offsetSeconds = off,
            tempC = c.num("temperature_2m"), feelsC = c.num("apparent_temperature"),
            code = c.num("weather_code")?.toInt() ?: -1, isDay = c.optInt("is_day", 1) == 1, windKmh = c.num("wind_speed_10m"),
            hours = hours, days = days,
        )
    }.getOrNull()

    /** Active, actual, public alerts only, as the page's activeNwsAlerts(); most severe first. */
    fun alerts(json: String, now: Long): List<WeatherAlert> = runCatching {
        val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
        (0 until features.length()).mapNotNull { i ->
            val p = features.optJSONObject(i)?.optJSONObject("properties") ?: return@mapNotNull null
            if (p.str("status") != "Actual" || p.str("messageType") == "Cancel" || p.str("scope") != "Public") return@mapNotNull null
            val end = iso(p.str("ends")) ?: iso(p.str("expires")) ?: return@mapNotNull null
            if (end <= now) return@mapNotNull null
            val refs = p.optJSONArray("references")
            WeatherAlert(
                id = p.str("id").ifEmpty { return@mapNotNull null }, event = p.str("event"), severity = p.str("severity").ifEmpty { "Unknown" },
                urgency = p.str("urgency").ifEmpty { "Unknown" }, headline = p.str("headline"), description = p.str("description"),
                instruction = p.str("instruction"), area = p.str("areaDesc"), onset = iso(p.str("onset")), ends = end,
                references = if (refs == null) emptyList() else (0 until refs.length()).mapNotNull { refs.optJSONObject(it)?.str("identifier")?.ifEmpty { null } },
            )
        }.sortedByDescending { it.rank }
    }.getOrDefault(emptyList())

    fun aqi(json: String): Int? = runCatching { JSONObject(json).getJSONObject("current").num("us_aqi")?.roundToInt() }.getOrNull()

    private fun iso(s: String): Long? = if (s.isEmpty()) null else runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
}

/**
 * Aeolia's state on the phone: SharedPreferences "aeolia". "state" is [WeatherState] as JSON (what other
 * parts of Zeus read); "forecast", "alerts" and "air" are the raw responses the page renders from;
 * "selection" and "ui" are the page's own place and preferences. One process-wide flow.
 */
object WeatherStore {
    const val FILE = "aeolia"
    private val _state = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = _state.asStateFlow()
    private val lock = Any()

    fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(c: Context): WeatherState = synchronized(lock) {
        if (_state.value.loaded) return _state.value
        val s = runCatching { Codec.state(prefs(c).getString("state", null)) }.getOrNull() ?: WeatherState()
        val loaded = s.copy(loaded = true)
        _state.value = loaded
        loaded
    }

    fun update(c: Context, change: (WeatherState) -> WeatherState): WeatherState = synchronized(lock) {
        val next = change(load(c)).copy(loaded = true)
        _state.value = next
        prefs(c).edit().putString("state", Codec.json(next)).apply()
        next
    }

    /** For tests and previews. */
    internal fun set(s: WeatherState) { _state.value = s }

    object Codec {
        fun json(s: WeatherState): String = JSONObject().apply {
            put("place", s.place); put("lat", s.lat.takeIf { it.isFinite() } ?: JSONObject.NULL); put("lon", s.lon.takeIf { it.isFinite() } ?: JSONObject.NULL)
            put("country", s.country); put("tempC", s.tempC ?: JSONObject.NULL); put("feelsC", s.feelsC ?: JSONObject.NULL)
            put("code", s.code); put("isDay", s.isDay); put("highC", s.highC ?: JSONObject.NULL); put("lowC", s.lowC ?: JSONObject.NULL)
            put("rainChance", s.rainChance ?: JSONObject.NULL); put("next", s.next ?: JSONObject.NULL); put("nextAt", s.nextAt ?: JSONObject.NULL)
            put("aqi", s.aqi ?: JSONObject.NULL); put("quip", s.quip ?: JSONObject.NULL); put("updatedAt", s.updatedAt); put("alertsAt", s.alertsAt)
            put("alertsChecked", s.alertsChecked)
            put("alerts", JSONArray().apply {
                for (a in s.alerts) put(JSONObject().apply {
                    put("id", a.id); put("event", a.event); put("severity", a.severity); put("urgency", a.urgency); put("headline", a.headline)
                    put("description", a.description); put("instruction", a.instruction); put("area", a.area)
                    put("onset", a.onset ?: JSONObject.NULL); put("ends", a.ends); put("references", JSONArray(a.references))
                })
            })
        }.toString()

        fun state(json: String?): WeatherState? {
            if (json == null) return null
            val o = JSONObject(json)
            fun d(n: String) = if (o.isNull(n)) null else o.optDouble(n).takeIf { !it.isNaN() }
            fun l(n: String) = if (o.isNull(n)) null else o.optLong(n)
            val alerts = o.optJSONArray("alerts")
            return WeatherState(
                place = o.optString("place"), lat = d("lat") ?: Double.NaN, lon = d("lon") ?: Double.NaN, country = o.optString("country"),
                tempC = d("tempC"), feelsC = d("feelsC"), code = o.optInt("code", -1), isDay = o.optBoolean("isDay", true),
                highC = d("highC"), lowC = d("lowC"), rainChance = if (o.isNull("rainChance")) null else o.optInt("rainChance"),
                next = if (o.isNull("next")) null else o.optString("next"), nextAt = l("nextAt"),
                aqi = if (o.isNull("aqi")) null else o.optInt("aqi"), quip = if (o.isNull("quip")) null else o.optString("quip"),
                updatedAt = o.optLong("updatedAt"), alertsAt = o.optLong("alertsAt"), alertsChecked = o.optBoolean("alertsChecked"),
                alerts = if (alerts == null) emptyList() else (0 until alerts.length()).map { i ->
                    val a = alerts.getJSONObject(i)
                    val refs = a.optJSONArray("references")
                    WeatherAlert(
                        a.getString("id"), a.optString("event"), a.optString("severity"), a.optString("urgency"), a.optString("headline"),
                        a.optString("description"), a.optString("instruction"), a.optString("area"),
                        if (a.isNull("onset")) null else a.optLong("onset"), a.getLong("ends"),
                        if (refs == null) emptyList() else (0 until refs.length()).map { refs.getString(it) },
                    )
                },
            )
        }
    }
}
