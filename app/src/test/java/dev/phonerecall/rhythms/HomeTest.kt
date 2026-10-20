package dev.phonerecall.rhythms

import dev.phonerecall.brain.People
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Horae's family additions: the India anchor across DST, Amma's awake window, things to tell, weather at home. */
class HomeTest {
    private val ny = ZoneId.of("America/New_York")
    private val ist = ZoneId.of("Asia/Kolkata")
    private val amma = People.keyOf("Amma")
    private val habit = CallHabit(amma, "Amma", "Asia/Kolkata", 8 * 60)

    @Test fun anchoredInIndiaAcrossTheNovemberSwitch() {
        // Stored as 08:00 Asia/Kolkata, never as a Buffalo time, and it survives the sealed store's JSON.
        val back = dev.phonerecall.mind.stateJson.readValue(dev.phonerecall.mind.stateJson.writeValueAsBytes(RhythmsData(callHabits = listOf(habit))), RhythmsData::class.java)
        assertEquals("Asia/Kolkata", back.callHabits.single().zone)
        assertEquals(480, back.callHabits.single().minute)
        // US clocks fall back on Sun Nov 1, 2026: the Buffalo reminder moves from 10:30 to 9:30 PM by itself.
        val sat = ZonedDateTime.of(2026, 10, 31, 12, 0, 0, 0, ny).toInstant().toEpochMilli()
        val sun = ZonedDateTime.of(2026, 11, 1, 12, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 31, 22, 30, 0, 0, ny), CallHabits.upcoming(habit, sat).withZoneSameInstant(ny))
        assertEquals(ZonedDateTime.of(2026, 11, 1, 21, 30, 0, 0, ny), CallHabits.upcoming(habit, sun).withZoneSameInstant(ny))
        // Both are 8:00 AM in India.
        assertEquals(8, CallHabits.upcoming(habit, sat).hour)
        assertEquals(8, CallHabits.upcoming(habit, sun).hour)
        // And in March, when the US springs forward (Mar 14, 2027), back to 10:30 PM.
        val mar = ZonedDateTime.of(2027, 3, 15, 12, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2027, 3, 15, 22, 30, 0, 0, ny), CallHabits.upcoming(habit, mar).withZoneSameInstant(ny))
    }

    private val now = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ny).toInstant().toEpochMilli()

    /** Amma's messages: 6:30 AM to 10:15 PM India time, on 14 days. */
    private fun ammaTimes() = (1..14).flatMap { d ->
        val day = LocalDate.of(2026, 10, 20).minusDays(d.toLong())
        listOf(6 * 60 + 35, 8 * 60, 11 * 60 + 10, 13 * 60, 17 * 60 + 40, 20 * 60 + 5, 22 * 60 + 10).map { m -> day.atStartOfDay(ist).plusMinutes(m.toLong()).toInstant().toEpochMilli() }
    }

    @Test fun learnsWhenAmmaIsUp() {
        val w = Awake.learn(ammaTimes(), ist, now)!!
        assertEquals(AwakeWindow(6 * 60 + 30, 22 * 60 + 30), w)
        assertEquals("usually up 6:30 AM–10:30 PM IST", Awake.line(w, "IST"))
        assertTrue(Awake.asleep(w, ZonedDateTime.of(2026, 10, 21, 2, 0, 0, 0, ist)))
        assertTrue(Awake.asleep(w, ZonedDateTime.of(2026, 10, 21, 23, 0, 0, 0, ist)))
        assertFalse(Awake.asleep(w, ZonedDateTime.of(2026, 10, 21, 7, 0, 0, 0, ist)))
        // One 2 AM message doesn't wake her up.
        assertEquals(w, Awake.learn(ammaTimes() + LocalDate.of(2026, 10, 15).atTime(2, 10).atZone(ist).toInstant().toEpochMilli(), ist, now))
        // Too little to say.
        assertNull(Awake.learn(ammaTimes().take(10), ist, now))
    }

    @Test fun neverNudgesInTheirSleep() {
        val s = Nudge.step(NudgeDay(amma, "d"), NudgeEvent.FIRE, 1_000L, 1_000L, called = false, asleep = true)
        assertNull(s.post)
        assertEquals(NudgeStage.DONE, s.day.stage)
        assertEquals("asleep", s.day.outcome)
    }

    @Test fun thingsToTellHome() {
        var d = RhythmsData(callHabits = listOf(habit))
        val (d1, id) = Bookmarks.add(d, "amma", "The new job offer", 5L)
        d = d1
        assertEquals(amma, d.bookmarks.single().key)
        assertEquals("Amma", d.bookmarks.single().person)
        d = Bookmarks.add(d, "Amma", "Diwali tickets booked", 6L).first
        assertEquals(id + 1, d.bookmarks.last().id)
        val at = ZonedDateTime.of(2026, 10, 20, 21, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(listOf("Tell them: The new job offer · Diwali tickets booked"), Rhythms.extras(d.copy(settings = d.settings.copy(homeWeather = false)), habit, at))
        // Ticked off: no longer shown before the next call.
        d = d.copy(bookmarks = d.bookmarks.map { if (it.id == id) it.copy(done = true) else it })
        assertEquals(listOf("Diwali tickets booked"), Bookmarks.open(d, amma).map { it.note })
        // Someone he doesn't call yet gets their own list.
        assertEquals(People.keyOf("Alex"), Bookmarks.add(d, "Alex", "Cricket on Sunday", 7L).first.bookmarks.last().key)
    }

    private val forecast = """
        {"daily":{"time":["2026-05-20","2026-05-21","2026-05-22"],
          "temperature_2m_max":[43.4,39.0,41.9],
          "precipitation_sum":[0.0,82.3,12.0]}}
    """.trimIndent()

    @Test fun onlyExtremesAtHome() {
        val a = HomeWeather.extremes(forecast)
        assertEquals(listOf(HomeAlert("2026-05-20", "heat", 43.4), HomeAlert("2026-05-21", "rain", 82.3)), a)
        val today = LocalDate.of(2026, 5, 20)
        assertEquals("Check on home" to "43°C in Pune today, a heatwave.", HomeWeather.words(a[0], "Pune", today))
        assertEquals("Check on home" to "Heavy rain in Pune tomorrow, 82 mm.", HomeWeather.words(a[1], "Pune", today))
        assertTrue(HomeWeather.extremes("""{"daily":{"time":["2026-10-20"],"temperature_2m_max":[31.0],"precipitation_sum":[4.0]}}""").isEmpty())
        assertTrue(HomeWeather.extremes("not json").isEmpty())
        // The URL passes Aeolia's door (NetGate host and the forecast path).
        assertTrue(dev.phonerecall.aeolia.WeatherNet.allows(HomeWeather.url(18.5204, 73.8567)))
    }

    @Test fun weatherAndNotesOnTheNudge() {
        val d = RhythmsData(callHabits = listOf(habit), homeAlerts = listOf(HomeAlert("2026-10-21", "rain", 90.0)))
        val at = ZonedDateTime.of(2026, 10, 20, 21, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(listOf("Heavy rain in Pune today, 90 mm."), Rhythms.extras(d, habit, at))
        val withAwake = RhythmsBrain.facts(d.copy(seen = mapOf(amma to ammaTimes())), now)
        assertEquals(listOf("calls Amma daily ~8 AM IST", "Amma usually up 6:30 AM–10:30 PM IST"), withAwake)
        assertNotNull(withAwake)
    }
}
