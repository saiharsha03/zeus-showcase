package dev.phonerecall.aeolia

import dev.phonerecall.data.AvailabilityModel
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardEngine
import dev.phonerecall.home.CardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Which NWS alerts reach the phone, the next-change line, and the Olympus cards. */
class AlertRuleTest {
    private val zone = ZoneId.of("America/New_York")
    private val night = ZonedDateTime.of(2026, 10, 20, 2, 30, 0, 0, zone)   // quiet hours
    private val morning = night.withHour(8)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private fun alert(id: String, severity: String, ends: ZonedDateTime = night.plusHours(12), refs: List<String> = emptyList()) =
        WeatherAlert(id, "$severity thing", severity, ends = ms(ends), references = refs, headline = "$severity thing until noon")

    @Test fun severeAndExtremeBreakThroughQuietHours() {
        assertTrue(AlertRule.notify(alert("a", "Extreme"), night, emptySet(), enabled = true))
        assertTrue(AlertRule.notify(alert("b", "Severe"), night, emptySet(), enabled = true))
    }

    @Test fun moderateWaitsForMorningAndMinorNeverRings() {
        assertFalse(AlertRule.notify(alert("c", "Moderate"), night, emptySet(), enabled = true))
        assertTrue(AlertRule.notify(alert("c", "Moderate"), morning, emptySet(), enabled = true))
        assertFalse(AlertRule.notify(alert("d", "Minor"), morning, emptySet(), enabled = true))
        assertFalse(AlertRule.notify(alert("e", "Unknown"), morning, emptySet(), enabled = true))
    }

    @Test fun onceEachIncludingUpdatesAndNeverWhenOffOrOver() {
        assertFalse(AlertRule.notify(alert("a", "Severe"), night, setOf("a"), enabled = true))
        // NWS re-issues an alert with a new id that references the old one.
        assertFalse(AlertRule.notify(alert("a2", "Severe", refs = listOf("a")), night, setOf("a"), enabled = true))
        assertEquals(setOf("a", "a2"), AlertRule.markPosted(listOf(alert("a2", "Severe", refs = listOf("a")), alert("z", "Severe")), setOf("a")))
        assertFalse(AlertRule.notify(alert("f", "Extreme"), night, emptySet(), enabled = false))
        assertFalse(AlertRule.notify(alert("g", "Extreme", ends = night.minusMinutes(1)), night, emptySet(), enabled = true))
    }

    private fun fc(now: ZonedDateTime, code: Int, hours: List<Hour>, temp: Double = 12.0) =
        Forecast(ms(now), now.offset.totalSeconds, temp, temp - 2, code, true, 10.0, hours, emptyList())
    private fun hour(now: ZonedDateTime, plus: Long, code: Int, temp: Double = 12.0, chance: Int = 0, mm: Double = 0.0, gust: Double = 20.0) =
        Hour(ms(now.plusHours(plus)), temp, code, chance, mm, 0.0, gust)

    @Test fun nextChangeNamesTheFirstShift() {
        val t = morning.withMinute(0)
        val rain = fc(t, 2, (0L..6).map { hour(t, it, if (it >= 3) 61 else 2) })
        assertEquals("Rain from 11 AM", NextChange.of(rain, ms(t))!!.first)
        val stops = fc(t, 63, (0L..6).map { hour(t, it, if (it >= 2) 3 else 63) })
        assertEquals("Dry from 10 AM", NextChange.of(stops, ms(t))!!.first)
        val windy = fc(t, 3, (0L..6).map { hour(t, it, 3, gust = if (it == 4L) 62.0 else 20.0) })
        assertEquals("Gusts to 62 km/h at 12 PM", NextChange.of(windy, ms(t))!!.first)
        val drop = fc(t, 1, (0L..12).map { hour(t, it, 1, temp = 12.0 - it) })
        assertEquals("Down to 32°F / 0°C by 8 PM", NextChange.of(drop, ms(t))!!.first)
        assertNull(NextChange.of(fc(t, 1, (0L..12).map { hour(t, it, 1) }), ms(t)))
    }

    private fun board(w: WeatherState, now: ZonedDateTime) = CardEngine.build(
        BoardInputs(
            now, CalendarSnapshot(allowed = true, events = emptyList(), loaded = true), AvailabilityModel.range(now.toLocalDate(), 9, emptyList(), now.zone),
            false, HomePrefs(), Learned(), TaskSnapshot(loaded = true), ClosetSnapshot(loaded = true), emptyList(), false, weather = w,
        ),
    )

    @Test fun severeAlertTopsOlympusAndTheWeatherCardShows() {
        val t = morning
        val w = WeatherState(place = "Buffalo", tempC = 3.0, feelsC = -1.0, code = 73, highC = 4.0, lowC = -2.0, next = "Dry from 2 PM", nextAt = ms(t.plusHours(6)),
            updatedAt = ms(t.minusMinutes(10)), alerts = listOf(alert("ws", "Severe", ends = t.plusHours(20))), alertsChecked = true, alertsAt = ms(t.minusMinutes(10)), loaded = true)
        val cards = board(w, t).cards
        assertEquals(CardKind.WEATHER_ALERT, cards.first().kind)
        val weather = cards.first { it.kind == CardKind.WEATHER }
        assertEquals("Dry from 2 PM", weather.content.title)
        assertEquals("37°", weather.content.stat!!.value)
        // No alerts and a stale forecast: no alert card, and the weather card says nothing stale.
        val stale = board(w.copy(alerts = emptyList(), updatedAt = ms(t.minusHours(7))), t).cards
        assertTrue(stale.none { it.kind == CardKind.WEATHER_ALERT })
        assertTrue(stale.first { it.kind == CardKind.WEATHER }.content.placeholder)
    }

    @Test fun nanoLinesMustUseOnlyTheFacts() {
        val facts = "Clear. Now 52°F (11°C), feels like 48°F. High 55°F, low 41°F. 10% chance of rain."
        assertTrue(NanoQuip.grounded("52 degrees and clear. The sky is suspiciously calm.", facts))
        assertFalse(NanoQuip.grounded("60 degrees by noon, apparently.", facts))
        assertEquals(listOf("Clear and cold.", "The sky is phoning it in, like you."), NanoQuip.lines("1. \"Clear and cold.\"\n- The sky is phoning it in, like you.\n\n"))
        assertEquals("The sky is phoning it in, like you.", NanoQuip.pick(listOf("Clear and cold.", "The sky is phoning it in, like you.", "It is 99 out."), facts))
        assertNull(NanoQuip.pick(listOf("It is 99 out."), facts))
        assertEquals("hot", NanoQuip.normalize("savage"))
    }
}
