package dev.phonerecall.odyssey

import dev.phonerecall.data.Availability
import dev.phonerecall.data.AvailabilityModel
import dev.phonerecall.data.CalKind
import dev.phonerecall.data.CalendarInfo
import dev.phonerecall.data.CalendarPipeline
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.DayType
import dev.phonerecall.data.RelevanceConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime

/** "WFH today?": an office day, still at home well past leaving time, asked once. */
class OfficeDayTest {
    private val tue = LocalDate.of(2026, 10, 20)
    private fun at(h: Int, m: Int) = ZonedDateTime.of(tue.atTime(h, m), Fx.zone)
    private val office = Availability(tue, DayType.WORKDAY)
    private val none = CommuteStats()
    private val learned = CommuteStats(departures = mapOf(DayOfWeek.TUESDAY to 8 * 60 + 5))

    @Test fun asksAt840WhenNothingIsLearned() {
        assertFalse(OfficeDay.due(office, none, at(8, 39), atHome = true, driving = false, ran = emptyMap()))
        assertTrue(OfficeDay.due(office, none, at(8, 40), atHome = true, driving = false, ran = emptyMap()))
    }

    @Test fun orTwentyMinutesAfterHisUsualLeavingTime() {
        assertEquals(8 * 60 + 25, OfficeDay.askAt(learned, at(8, 0)))
        assertFalse(OfficeDay.due(office, learned, at(8, 24), true, false, emptyMap()))
        assertTrue(OfficeDay.due(office, learned, at(8, 25), true, false, emptyMap()))
    }

    @Test fun onlyOnAnOfficeDayAtHomeInTheMorningOnce() {
        val t = at(9, 0)
        assertFalse("remote day", OfficeDay.due(office.copy(type = DayType.REMOTE), none, t, true, false, emptyMap()))
        assertFalse("out of office", OfficeDay.due(office.copy(type = DayType.OOO), none, t, true, false, emptyMap()))
        assertFalse("no availability", OfficeDay.due(null, none, t, true, false, emptyMap()))
        assertFalse("yesterday's availability", OfficeDay.due(office.copy(date = tue.minusDays(1)), none, t, true, false, emptyMap()))
        assertFalse("left home", OfficeDay.due(office, none, t, false, false, emptyMap()))
        assertFalse("Odyssey doesn't know", OfficeDay.due(office, none, t, null, false, emptyMap()))
        assertFalse("driving", OfficeDay.due(office, none, t, true, true, emptyMap()))
        assertFalse("asked already", OfficeDay.due(office, none, t, true, false, mapOf(OfficeDay.key(tue) to 0L)))
        assertFalse("afternoon", OfficeDay.due(office, none, at(12, 0), true, false, emptyMap()))
    }

    @Test fun theCardIsAQuestionThatEndsAtNoon() {
        val n = OfficeDay.note(at(8, 45))
        assertEquals("wfh:2026-10-20", n.key)
        assertEquals("WFH today?", n.title)
        assertTrue(n.body, n.body.contains("still home at 8:45"))
        assertEquals(at(12, 0).toInstant().toEpochMilli(), n.expiresAt)
        val card = OdysseyCards.wfh(Fx.on().note(n, 0), at(9, 0).toInstant().toEpochMilli())!!
        assertEquals(listOf(dev.phonerecall.home.ActionRole.WFH_YES, dev.phonerecall.home.ActionRole.WFH_NO), card.actions.map { it.role })
        assertEquals(null, OdysseyCards.wfh(Fx.on().note(n, 0), at(12, 1).toInstant().toEpochMilli()))
    }

    @Test fun yesMakesTodayRemoteForEveryReader() {
        // Tuesday is an office day by pattern; his answer wins, the next day is untouched.
        val days = AvailabilityModel.range(tue, 3, emptyList(), Fx.zone, overrides = mapOf(tue to DayType.REMOTE))
        assertEquals(DayType.REMOTE, days.getValue(tue).type)
        assertEquals(DayType.WORKDAY, days.getValue(tue.plusDays(1)).type)
        // The pipeline every reader (Home, read_availability, the Now Bar) goes through carries it.
        val cur = CalendarPipeline.curate(CalendarSnapshot(true, emptyList(), true), mapOf(1L to CalendarInfo(1, "Me", "me", CalKind.OWN, 0)), RelevanceConfig(), emptyList(), Fx.zone, tue, overrides = mapOf(tue to DayType.REMOTE))
        assertEquals(DayType.REMOTE, cur.availability.getValue(tue).type)
    }
}
