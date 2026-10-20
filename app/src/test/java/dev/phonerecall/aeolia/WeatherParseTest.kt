package dev.phonerecall.aeolia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate

/** Every parser against JSON fetched from the real endpoints on 2026-10-19 (app/src/test/resources/aeolia). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeatherParseTest {
    private fun read(name: String): String = javaClass.classLoader!!.getResource("aeolia/$name")!!.readText(Charsets.UTF_8)
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test fun openMeteoForecastForBuffalo() {
        val fc = WeatherParse.forecast(read("forecast_buffalo.json"))!!
        assertEquals(-14400, fc.offsetSeconds)
        assertEquals(at("2026-10-20T01:30:00Z"), fc.at)  // "2026-10-19T21:30" at UTC-4
        assertEquals(11.0, fc.tempC!!, 0.01)
        assertEquals(9.1, fc.feelsC!!, 0.01)
        assertEquals(0, fc.code)
        assertEquals(false, fc.isDay)
        assertEquals(408, fc.hours.size)          // 24 past hours + 10 days
        assertEquals(at("2026-10-19T01:00:00Z"), fc.hours.first().at)
        assertEquals(10, fc.days.size)
        assertEquals(LocalDate.of(2026, 10, 19), fc.days.first().date)
        assertTrue(fc.days.all { it.highC != null && it.lowC != null && it.highC!! >= it.lowC!! })
    }

    @Test fun stateFromTheForecast() {
        val fc = WeatherParse.forecast(read("forecast_buffalo.json"))!!
        val s = WeatherState.withForecast(WeatherState(place = "Buffalo"), fc, fc.at)
        assertEquals(52, WeatherState.f(s.tempC!!))
        assertNotNull(s.highC)
        assertEquals(fc.days.first().highC, s.highC)
        assertTrue(s.hasData)
    }

    @Test fun nwsActiveAlertsKeepActualPublicUnexpired() {
        val now = at("2026-10-20T01:30:00Z")
        val alerts = WeatherParse.alerts(read("alerts_severe.json"), now)
        assertEquals(12, alerts.size)
        assertTrue(alerts.all { it.severe && it.ends > now })
        val storm = alerts.first { it.event == "Severe Thunderstorm Warning" }
        assertEquals(at("2026-10-20T01:45:00Z"), storm.ends)  // 19:45 at UTC-6
        assertEquals("Dona Ana, NM", storm.area)
        assertTrue(storm.references.isNotEmpty())
        // Fifteen minutes later that warning is over; the rest remain.
        assertEquals(11, WeatherParse.alerts(read("alerts_severe.json"), now + 16 * 60_000L).size)
        // A flood warning with no "ends" lasts until "expires".
        val flood = alerts.first { it.area == "Brantley, GA" }
        assertEquals(at("2026-10-20T13:45:00Z"), flood.ends)
    }

    @Test fun quietPointAndBrokenInput() {
        assertEquals(emptyList<WeatherAlert>(), WeatherParse.alerts(read("alerts_buffalo.json"), at("2026-10-20T01:30:00Z")))
        assertEquals(emptyList<WeatherAlert>(), WeatherParse.alerts("not json", 0))
        assertNull(WeatherParse.forecast("{}"))
        assertNull(WeatherParse.aqi("{}"))
    }

    @Test fun airQuality() = assertEquals(38, WeatherParse.aqi(read("air_buffalo.json")))

    @Test fun cancelsTestsAndExpiredAreDropped() {
        val json = """{"features":[
          {"properties":{"id":"a","event":"Tornado Warning","severity":"Extreme","status":"Test","messageType":"Alert","scope":"Public","expires":"2030-01-01T00:00:00Z"}},
          {"properties":{"id":"b","event":"Tornado Warning","severity":"Extreme","status":"Actual","messageType":"Cancel","scope":"Public","expires":"2030-01-01T00:00:00Z"}},
          {"properties":{"id":"c","event":"Wind Advisory","severity":"Moderate","status":"Actual","messageType":"Alert","scope":"Public","expires":"2020-01-01T00:00:00Z"}},
          {"properties":{"id":"d","event":"Wind Advisory","severity":"Moderate","status":"Actual","messageType":"Alert","scope":"Restricted","expires":"2030-01-01T00:00:00Z"}},
          {"properties":{"id":"e","event":"Frost Advisory","severity":"Minor","status":"Actual","messageType":"Alert","scope":"Public","expires":"2030-01-01T00:00:00Z"}}
        ]}"""
        assertEquals(listOf("e"), WeatherParse.alerts(json, at("2026-10-20T00:00:00Z")).map { it.id })
    }

    @Test fun stateSurvivesTheStore() {
        val now = at("2026-10-20T01:30:00Z")
        val fc = WeatherParse.forecast(read("forecast_buffalo.json"))!!
        val s = WeatherState.withForecast(WeatherState(place = "Buffalo", lat = 42.8864, lon = -78.8784, country = "US"), fc, now)
            .copy(alerts = WeatherParse.alerts(read("alerts_severe.json"), now).take(2), alertsChecked = true, aqi = 38, quip = "Clear. The sky is suspiciously calm.")
        val back = WeatherStore.Codec.state(WeatherStore.Codec.json(s))!!
        assertEquals(s, back.copy(loaded = s.loaded))  // "loaded" is the store's own flag, not stored
        assertEquals(WeatherState(), WeatherStore.Codec.state(WeatherStore.Codec.json(WeatherState())))
    }
}
