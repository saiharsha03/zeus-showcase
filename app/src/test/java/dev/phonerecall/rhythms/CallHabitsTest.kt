package dev.phonerecall.rhythms

import dev.phonerecall.brain.People
import dev.phonerecall.calls.CallRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class CallHabitsTest {
    private val ny = ZoneId.of("America/New_York")
    private val ist = ZoneId.of("Asia/Kolkata")
    private val amma = People.keyOf("Amma")

    // ---- WhatsApp call notifications ----

    @Test fun parsesWhatsAppCallLines() {
        assertEquals(WaCallNote("Amma", WaCall.ONGOING, video = true, group = false), CallNotes.parse("com.whatsapp", "Amma", "Ongoing video call"))
        assertEquals(WaCallNote("Amma", WaCall.ONGOING, video = false, group = false), CallNotes.parse("com.whatsapp", "Amma", "Ongoing voice call"))
        assertEquals(WaCallNote("Family", WaCall.ONGOING, video = false, group = true), CallNotes.parse("com.whatsapp", "Family", "Ongoing group voice call"))
        assertEquals(WaCallNote("Amma", WaCall.MISSED, video = false, group = false), CallNotes.parse("com.whatsapp", "Amma", "Missed voice call"))
        assertEquals(WaCallNote("Nanna", WaCall.MISSED, video = true, group = false), CallNotes.parse("com.whatsapp", "Nanna (2)", "Missed video call"))
        assertEquals(WaCall.MISSED, CallNotes.parse("com.whatsapp", "Amma", "2 missed voice calls")?.kind)
        assertEquals(WaCall.MISSED, CallNotes.parse("com.whatsapp", "Amma", "Missed voice call at 7:02 AM")?.kind)
        assertEquals(WaCall.OUTGOING, CallNotes.parse("com.whatsapp", "Amma", "Calling…")?.kind)
        assertEquals(WaCall.OUTGOING, CallNotes.parse("com.whatsapp.w4b", "Amma", "Ringing...")?.kind)
        assertEquals(WaCall.INCOMING, CallNotes.parse("com.whatsapp", "Amma", "Incoming video call")?.kind)
    }

    @Test fun neverReadsMessages() {
        assertNull(CallNotes.parse("com.whatsapp", "Amma", "Did you eat? Missed voice call yesterday"))
        assertNull(CallNotes.parse("com.whatsapp", "Amma", "call me when you're free"))
        assertNull(CallNotes.parse("com.whatsapp", "WhatsApp", "3 missed calls"))
        assertNull(CallNotes.parse("org.telegram.messenger", "Amma", "Ongoing voice call"))
    }

    // ---- routing (ported from PhoneRecall's CallDefaults) ----

    @Test fun numbersRouteByCountry() {
        assertEquals("IN", CallRouting.country("+91 99999 00001", "US"))
        assertEquals("IN", CallRouting.country("0091 9999900001", "US"))
        assertEquals("IN", CallRouting.country("919999900001", "US"))
        assertEquals("US", CallRouting.country("+1 716-555-0142", "IN"))
        assertEquals("US", CallRouting.country("17165550142", "IN"))
        // A bare Buffalo number is read in the phone's region, never guessed as India.
        assertEquals("US", CallRouting.country("716 555 0142", "US", "+17165550142"))
        assertEquals("OTHER", CallRouting.country("+44 20 7946 0958", "US", "+442079460958"))
        assertEquals(CallRouting.VIDEO, CallRouting.method("IN"))
        assertEquals(CallRouting.PHONE, CallRouting.method("US"))
        assertEquals(CallRouting.PHONE, CallRouting.method("OTHER"))
    }

    @Test fun bareTenDigitsResolvedThroughWhatsApp() {
        assertEquals("IN" to true, CallRouting.countryFor("99999 00001", "919999900001", "US", "+19999900001"))
        assertEquals("US" to true, CallRouting.countryFor("7165550142", "17165550142", "US"))
        assertEquals("US" to false, CallRouting.countryFor("7165550142", null, "US", "+17165550142"))
        assertEquals("919999900001", CallRouting.jidDigits("919999900001@s.whatsapp.net"))
        assertNull(CallRouting.jidDigits("120363@g.us"))
    }

    @Test fun viaWordsAndNames() {
        assertEquals(CallRouting.VIDEO, CallRouting.via("video"))
        assertEquals(CallRouting.VOICE, CallRouting.via("whatsapp"))
        assertEquals(CallRouting.PHONE, CallRouting.via("phone call"))
        assertNull(CallRouting.via("auto"))
        assertEquals(1f, CallRouting.matchScore("amma", "Amma"))
        assertTrue(CallRouting.matchScore("my amma", "Amma") == 1f)
        assertTrue(CallRouting.matchScore("alex", "Alex Kumar") >= 0.9f)
        assertTrue(CallRouting.matchScore("amma", "Anand") < 0.72f)
    }

    // ---- learning and time zones ----

    private val now = ZonedDateTime.of(2026, 10, 19, 12, 0, 0, 0, ny).toInstant().toEpochMilli()
    private val since = now - 30 * 86_400_000L

    /** He calls Amma on WhatsApp at about 8 AM India time, most days. */
    private fun mornings(days: Int = 21, skip: Set<Int> = setOf(4, 9, 15)) = (1..days).filterNot { it in skip }.map { i ->
        val t = LocalDate.of(2026, 10, 19).minusDays(i.toLong()).atTime(7, 55).plusMinutes((i % 4) * 3L).atZone(ist)
        CallRecord(amma, "Amma", t.toInstant().toEpochMilli(), 900, CallSource.WHATSAPP, connected = true, video = true, country = "IN")
    }

    @Test fun learnsTheDailyCallInIndiaTime() {
        val h = CallHabits.learn(mornings(), now, since, ny).single()
        assertEquals(amma, h.key)
        assertEquals("Asia/Kolkata", h.zone)
        assertEquals(8 * 60, h.minute)
        assertTrue(h.daily)
        assertEquals("calls Amma daily ~8 AM IST", CallHabits.brainLine(h, now))
        assertEquals("Call home?", CallHabits.ask(h))
    }

    @Test fun istToLocalAcrossEdtAndEst() {
        val h = CallHabit(amma, "Amma", "Asia/Kolkata", 8 * 60)
        // Still EDT: 8:00 AM IST on Oct 20 is 10:30 PM on Oct 19 in Buffalo.
        val edt = CallHabits.upcoming(h, now).withZoneSameInstant(ny)
        assertEquals(ZonedDateTime.of(2026, 10, 19, 22, 30, 0, 0, ny), edt)
        assertEquals("Daily, about 8:00 AM IST (10:30 PM here)", CallHabits.line(h, now, ny))
        // After Nov 1 it is EST: 9:30 PM.
        val nov = ZonedDateTime.of(2026, 11, 9, 12, 0, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 11, 9, 21, 30, 0, 0, ny), CallHabits.upcoming(h, nov).withZoneSameInstant(ny))
        assertEquals("Daily, about 8:00 AM IST (9:30 PM here)", CallHabits.line(h, nov, ny))
        // And at the nudge: "It's 8:00 AM in India".
        val at = ZonedDateTime.of(2026, 11, 9, 21, 30, 0, 0, ny).toInstant().toEpochMilli()
        assertEquals("It's 8:00 AM in India", CallHabits.nowThere(h, at))
    }

    @Test fun learnsAWeeklyCall() {
        val alex = People.keyOf("Alex")
        val sundays = (0..3).map { w ->
            CallRecord(alex, "Alex", LocalDate.of(2026, 10, 18).minusWeeks(w.toLong()).atTime(10, w * 4).atZone(ny).toInstant().toEpochMilli(), 600, CallSource.CALL_LOG, outgoing = true, country = "US")
        }
        val h = CallHabits.learn(sundays, now, since, ny).single()
        assertEquals(7, h.weekday)
        assertEquals("America/New_York", h.zone)
        assertEquals(10 * 60 + 5, h.minute)
        assertEquals("calls someone every Sunday ~10:05 AM EDT", CallHabits.brainLine(h, now))
    }

    @Test fun scatteredCallsAreNotAHabit() {
        val r = (1..10).map { i -> CallRecord(amma, "Amma", LocalDate.of(2026, 10, 19).minusDays(i * 2L).atTime((i * 5) % 24, 0).atZone(ist).toInstant().toEpochMilli(), country = "IN") }
        assertTrue(CallHabits.learn(r, now, since, ny).isEmpty())
    }

    @Test fun missedCallsDoNotTeachButHisAttemptsCount() {
        val missed = mornings().map { it.copy(connected = false, outgoing = false) }
        assertTrue(CallHabits.learn(missed, now, since, ny).isEmpty())
        val tried = mornings().map { it.copy(connected = false, outgoing = true) }
        assertEquals(1, CallHabits.learn(tried, now, since, ny).size)
    }

    @Test fun alreadyCalledSuppresses() {
        val target = ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0, ist).toInstant().toEpochMilli()
        val early = CallRecord(amma, "Amma", target - 20 * 60_000L, 600)
        assertTrue(CallHabits.calledSince(listOf(early), amma, target, target + 60_000L))
        assertFalse(CallHabits.calledSince(listOf(early.copy(at = target - 3 * 3_600_000L)), amma, target, target + 60_000L))
        assertFalse(CallHabits.calledSince(listOf(early.copy(connected = false, outgoing = false)), amma, target, target + 60_000L))
        val step = Nudge.step(NudgeDay(amma, "2026-10-20"), NudgeEvent.FIRE, target, target, called = true)
        assertEquals(NudgeStage.DONE, step.day.stage)
        assertNull(step.post)
    }

    // ---- the nudge ----

    private val t0 = 1_000_000_000L

    @Test fun oneReminderThenNothing() {
        var d = NudgeDay(amma, "2026-10-20")
        var s = Nudge.step(d, NudgeEvent.FIRE, t0, t0, called = false)
        assertEquals(PostKind.FIRST, s.post); d = s.day
        assertEquals(t0 + Nudge.REMIND_MS, d.nextAt)
        s = Nudge.step(d, NudgeEvent.FIRE, d.nextAt, t0, called = false)
        assertEquals(PostKind.REMINDER, s.post); d = s.day
        assertEquals(0L, d.nextAt)
        s = Nudge.step(d, NudgeEvent.FIRE, t0 + 2 * Nudge.REMIND_MS, t0, called = false)
        assertNull(s.post)
    }

    @Test fun laterThenNotToday() {
        var d = Nudge.step(NudgeDay(amma, "d"), NudgeEvent.FIRE, t0, t0, false).day
        var s = Nudge.step(d, NudgeEvent.LATER, t0 + 60_000L, t0, false)
        assertTrue(s.cancel); d = s.day
        assertEquals(t0 + 60_000L + Nudge.LATER_MS, d.nextAt)
        s = Nudge.step(d, NudgeEvent.FIRE, d.nextAt, t0, false)
        assertEquals(PostKind.SNOOZED, s.post); d = s.day
        s = Nudge.step(d, NudgeEvent.NOT_TODAY, d.nextAt, t0, false)
        assertEquals(NudgeStage.DONE, s.day.stage)
        assertNull(Nudge.step(s.day, NudgeEvent.FIRE, t0 + 3_600_000L, t0, false).post)
    }

    @Test fun quietHoursAndDrivingWait() {
        val d = NudgeDay(amma, "d")
        val q = Nudge.step(d, NudgeEvent.FIRE, t0, t0, false, quietUntil = t0 + 3_600_000L)
        assertNull(q.post); assertEquals(t0 + 3_600_000L, q.day.nextAt)
        assertEquals(NudgeStage.DONE, Nudge.step(d, NudgeEvent.FIRE, t0, t0, false, quietUntil = t0 + 5 * 3_600_000L).day.stage)
        val drv = Nudge.step(d, NudgeEvent.FIRE, t0, t0, false, driving = true)
        assertNull(drv.post); assertEquals(t0 + Nudge.DRIVE_RECHECK_MS, drv.day.nextAt)
        // Stopped: it posts.
        assertEquals(PostKind.FIRST, Nudge.step(drv.day, NudgeEvent.FIRE, drv.day.nextAt, t0, false).post)
        // Too late in the day: nothing.
        assertNull(Nudge.step(d, NudgeEvent.FIRE, t0 + Nudge.GIVE_UP_MS + 1, t0, false).post)
    }

    @Test fun brainGetsWordsOnly() {
        val d = RhythmsData(callHabits = listOf(CallHabit(amma, "Amma", "Asia/Kolkata", 480), CallHabit(People.keyOf("Alex Kumar"), "Alex Kumar", "America/New_York", 600, 7)))
        val facts = RhythmsBrain.facts(d, now)
        assertEquals(listOf("calls Amma daily ~8 AM IST", "calls someone every Sunday ~10 AM EDT"), facts)
        assertTrue(facts.none { it.contains("Alex") || Regex("\\d{5,}").containsMatchIn(it) })
    }
}
