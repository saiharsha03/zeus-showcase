package dev.phonerecall.brain

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmIntentTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 19)           // EDT
    private val tomorrow = today.plusDays(1)
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private var id = 0L
    private fun ev(d: LocalDate, h: Int, m: Int, mins: Int, title: String = "Event", cat: EventCategory = EventCategory.WORK, place: String? = null, allDay: Boolean = false) =
        CalItem(++id, 1, title, ms(d, h, m), ms(d, h, m) + mins * 60_000L, allDay, cat, "Work", place, null, false)
    private fun alarm(d: LocalDate, h: Int, m: Int = 0) = NextAlarm(ms(d, h, m), "Clock")

    // ---- early start and bedtime ----

    @Test fun bedtimeIsEarlyStartMinusSevenFiftyAndUsesIasoGoal() {
        assertEquals(dev.phonerecall.iaso.Goals.SLEEP_MIN, Bedtime.SLEEP_MIN)
        val p = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 5), emptyList())!!
        assertEquals(ms(today, 21, 10), p.bedtime)           // 5:00 - 7h50m
        assertEquals(EarlySource.ALARM, p.early.source)
        assertEquals(ms(tomorrow, 1, 0), p.windowEnd)         // window closes four hours before
    }

    @Test fun flightOnlyStartsTwoAndAHalfHoursBefore() {
        val p = Bedtime.plan(tomorrow, zone, null, listOf(ev(tomorrow, 8, 10, 120, "Flight to SFO", EventCategory.TRAVEL)))!!
        assertEquals(EarlySource.FLIGHT, p.early.source)
        assertEquals(ms(tomorrow, 5, 40), p.early.at)
        assertEquals(listOf("Flight 8:10 AM"), p.reasons)
    }

    @Test fun earliestOfAlarmFlightAndFirstLocatedEventWins() {
        val events = listOf(ev(tomorrow, 8, 10, 60, "Flight", EventCategory.TRAVEL), ev(tomorrow, 7, 30, 60, "Standup", place = "HQ"))
        val leave = { e: CalItem -> if (e.title == "Standup") ms(tomorrow, 6, 50) else null }
        val p = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 6, 30), events, leaveBy = leave)!!
        assertEquals(ms(tomorrow, 5, 40), p.early.at)         // the flight
        val p2 = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 6, 30), listOf(ev(tomorrow, 7, 30, 60, "Standup", place = "HQ")), leaveBy = leave)!!
        assertEquals(ms(tomorrow, 6, 30), p2.early.at)
        assertEquals(EarlySource.EVENT, Bedtime.plan(tomorrow, zone, null, listOf(ev(tomorrow, 7, 30, 60, "Standup", place = "HQ")), leaveBy = leave)!!.early.source)
    }

    @Test fun busyDayAloneIsNotAnEarlyStartButGivesTheReason() {
        val meetings = (0 until 6).map { ev(tomorrow, 10 + it, 0, 50) }
        assertNull(Bedtime.plan(tomorrow, zone, null, meetings))
        val p = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 5), meetings)!!
        assertTrue(p.reasons.contains("6 meetings tomorrow"))
    }

    @Test fun noEarlyStartMeansNoPlan() {
        assertNull(Bedtime.plan(tomorrow, zone, null, emptyList()))
        assertNull(Bedtime.plan(tomorrow, zone, alarm(tomorrow, 10, 30), listOf(ev(tomorrow, 14, 0, 60, "Flight", EventCategory.TRAVEL))))
        assertNull(Bedtime.plan(tomorrow, zone, NextAlarm(ms(today, 23, 0), null), emptyList())) // tonight's alarm is not tomorrow's
    }

    // ---- the nudge: one, then one follow-up ----

    private val plan = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 5), listOf(ev(tomorrow, 8, 10, 60, "Flight", EventCategory.TRAVEL)))!!
    private val late = ms(today, 23, 0)

    @Test fun oneNudgeThenOneFollowUpThenNothing() {
        var s = NudgeState()
        assertEquals(NudgeStep.FIRST, BedNudges.decide(true, plan, s, late, awake = true))
        s = BedNudges.after(plan, s, NudgeStep.FIRST, late)
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, late + 30 * 60_000L, awake = true))      // too soon
        assertEquals(NudgeStep.FOLLOW_UP, BedNudges.decide(true, plan, s, late + 60 * 60_000L, awake = true))
        s = BedNudges.after(plan, s, NudgeStep.FOLLOW_UP, late + 60 * 60_000L)
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, late + 120 * 60_000L, awake = true))
    }

    @Test fun snoozeMovesTheFollowUpButAddsNothing() {
        var s = BedNudges.after(plan, NudgeState(), NudgeStep.FIRST, late)
        s = BedNudges.snooze(plan, s, late + 5 * 60_000L)
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, late + 20 * 60_000L, awake = true))
        assertEquals(NudgeStep.FOLLOW_UP, BedNudges.decide(true, plan, s, late + 35 * 60_000L, awake = true))
    }

    @Test fun suppressions() {
        val s = NudgeState()
        assertEquals(NudgeStep.NONE, BedNudges.decide(false, plan, s, late, awake = true))                       // Themis toggle off
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, null, s, late, awake = true))                        // no early start
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, late, awake = false))                       // asleep
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, late, awake = true, napping = true))
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, ms(today, 20, 0), awake = true))            // before bedtime
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, s, ms(tomorrow, 1, 30), awake = true))         // past the window
        assertEquals(NudgeStep.NONE, BedNudges.decide(true, plan, BedNudges.notTonight(plan, s), late, awake = true))
        // Not tonight lasts one night: tomorrow's plan nudges again.
        val next = Bedtime.plan(tomorrow.plusDays(1), zone, alarm(tomorrow.plusDays(1), 5), emptyList())!!
        assertEquals(NudgeStep.FIRST, BedNudges.decide(true, next, BedNudges.notTonight(plan, s), ms(tomorrow, 23, 0), awake = true))
    }

    @Test fun wordsAreQuietAndPlain() {
        val (t, reason) = BedNudges.text(plan, NudgeStep.FIRST, late, zone)
        assertEquals("Early day: alarm 5:00 AM. Sleep now for 6h.", t)
        assertEquals("Flight 8:10 AM", reason)
        assertEquals("4h left before your 5:00 AM alarm", BedNudges.text(plan, NudgeStep.FOLLOW_UP, ms(tomorrow, 1, 0), zone).first)
        val (head, body) = BedNudges.card(plan, zone)
        assertEquals("Early day tomorrow", head)
        assertEquals("alarm 5:00 AM, Flight 8:10 AM - sleep by 9:10 PM", body)
    }

    // ---- intent ----

    private fun input(ring: Long, setAt: Long? = null, atHome: Boolean? = null, events: List<CalItem> = emptyList(), leave: List<Pair<CalItem?, Long>> = emptyList(), seen: Int = 1, routine: String? = null, label: String? = null, wishes: (LocalDate) -> List<String> = { emptyList() }) =
        IntentInput(ring, setAt, zone, label, atHome, events, leave, seen, routine, wishes)

    @Test fun napNeedsAfternoonRingSetJustBeforeAndNotAway() {
        val nap = ms(today, 15, 0)
        val c = Alarms.classify(input(nap, setAt = nap - 40 * 60_000L, atHome = true))
        assertEquals(AlarmKind.NAP, c.kind)
        assertEquals("probably a nap (set 40 min before, at home)", c.phrase)
        assertEquals(AlarmKind.NAP, Alarms.classify(input(nap, setAt = nap - 40 * 60_000L, atHome = null)).kind)
        assertEquals(AlarmKind.UNKNOWN, Alarms.classify(input(nap, setAt = nap - 40 * 60_000L, atHome = false)).kind)
        assertEquals(AlarmKind.UNKNOWN, Alarms.classify(input(nap, setAt = nap - 5 * 3_600_000L, atHome = true)).kind)
        assertEquals(AlarmKind.UNKNOWN, Alarms.classify(input(ms(today, 20, 0), setAt = ms(today, 19, 30), atHome = true)).kind)
    }

    @Test fun leaveBeatsNapWhenAnEventFollows() {
        val nap = ms(today, 15, 0)
        val meeting = ev(today, 16, 0, 30, "Pickup at school", place = "School")
        val c = Alarms.classify(input(nap, setAt = nap - 40 * 60_000L, atHome = true, events = listOf(meeting), leave = listOf(meeting to ms(today, 15, 20))))
        assertEquals(AlarmKind.LEAVE, c.kind)
        assertEquals(meeting.title, c.event?.title)
        assertEquals(ms(today, 15, 20), c.leaveBy)
        // An event 3 hours on is not "getting ready".
        assertEquals(AlarmKind.NAP, Alarms.classify(input(nap, setAt = nap - 40 * 60_000L, atHome = true, events = listOf(ev(today, 18, 0, 30)))).kind)
        // Odyssey's leave-by near the ring counts even without a close start.
        assertEquals(AlarmKind.LEAVE, Alarms.classify(input(nap, leave = listOf(null to nap + 10 * 60_000L))).kind)
    }

    @Test fun routineAfterThreeSightingsAndTheQuestionIsAskedOnce() {
        val ring = ms(today, 15, 0)
        val seenRings = (1..3).map { ms(today.minusDays(7L * it), 15, if (it == 2) 5 else 0) }
        assertEquals(4, Alarms.timesSeen(ring, seenRings, zone))
        assertEquals(1, Alarms.timesSeen(ring, listOf(ms(today.minusDays(1), 15, 0)), zone))   // other weekday
        val c = Alarms.classify(input(ring, seen = 4))
        assertEquals(AlarmKind.ROUTINE, c.kind)
        assertTrue(Alarms.shouldAsk(c, ring, zone, 4, answeredNear = false, askedNear = false))
        assertFalse(Alarms.shouldAsk(c, ring, zone, 4, answeredNear = false, askedNear = true))
        assertFalse(Alarms.shouldAsk(c, ring, zone, 4, answeredNear = true, askedNear = false))
        assertEquals("Medicine (your usual)", Alarms.classify(input(ring, seen = 4, routine = "Medicine")).phrase)
        assertEquals("Pickup", Alarms.nearMinute(15 * 60 + 8, mapOf(15 * 60 to "Pickup")))
        assertNull(Alarms.nearMinute(15 * 60 + 30, mapOf(15 * 60 to "Pickup")))
        // A morning alarm is a wake alarm, never asked about.
        val wake = ms(today, 6, 45)
        assertEquals(AlarmKind.WAKE, Alarms.classify(input(wake, seen = 9)).kind)
        assertFalse(Alarms.shouldAsk(Alarms.classify(input(wake, seen = 9)), wake, zone, 9, false, false))
        // A one-off 4 PM alarm nothing explains is asked about the first time, once.
        val four = ms(today, 16, 0)
        val once = Alarms.classify(input(four, seen = 1))
        assertEquals(AlarmKind.UNKNOWN, once.kind)
        assertTrue(Alarms.shouldAsk(once, four, zone, 1, answeredNear = false, askedNear = false))
        assertFalse(Alarms.shouldAsk(once, four, zone, 1, answeredNear = false, askedNear = true))
    }

    @Test fun aZeusLabelWins() {
        val c = Alarms.classify(input(ms(today, 15, 0), setAt = ms(today, 14, 30), atHome = true, label = "Gym"))
        assertEquals(AlarmKind.LABEL, c.kind)
        assertEquals("Gym (set by Zeus)", c.phrase)
    }

    @Test fun unknownIsUnknown() {
        assertEquals(AlarmKind.UNKNOWN, Alarms.classify(input(ms(today, 21, 15))).kind)
    }

    // ---- midnight in India ----

    @Test fun twoThirtyEdtIsMidnightInIndia() {
        assertEquals(LocalDate.of(2026, 10, 20), Alarms.indiaMidnight(ms(today, 14, 30)))
    }

    @Test fun oneThirtyEstIsMidnightInIndiaToo() {
        val winter = LocalDate.of(2026, 12, 14)      // EST
        assertEquals(LocalDate.of(2026, 12, 15), Alarms.indiaMidnight(ms(winter, 13, 30)))
        assertNull(Alarms.indiaMidnight(ms(winter, 14, 30)))   // the summer time is an hour off in winter
    }

    @Test fun windowIsTenMinutesEitherSide() {
        assertNotNull(Alarms.indiaMidnight(ms(today, 14, 20)))
        assertNotNull(Alarms.indiaMidnight(ms(today, 14, 40)))
        assertNull(Alarms.indiaMidnight(ms(today, 14, 41)))
        assertNull(Alarms.indiaMidnight(ms(today, 14, 19)))
        // Just before midnight IST belongs to the date that is starting.
        assertEquals(LocalDate.of(2026, 10, 20), Alarms.indiaMidnight(ms(today, 14, 25)))
    }

    @Test fun midnightWishBeatsNapAndLeaveAndRoutine() {
        val ring = ms(today, 14, 30)
        val wishes = { d: LocalDate -> if (d == LocalDate.of(2026, 10, 20)) listOf("Alex Kumar") else emptyList() }
        val c = Alarms.classify(input(ring, setAt = ring - 30 * 60_000L, atHome = true, events = listOf(ev(today, 15, 0, 30)), seen = 5, wishes = wishes))
        assertEquals(AlarmKind.INDIA_MIDNIGHT, c.kind)
        assertEquals("midnight in India (Alex Kumar)", c.phrase)
        assertEquals(listOf("Alex Kumar"), c.people)
        assertEquals("midnight in India", Alarms.classify(input(ring)).phrase)
        assertEquals("2:30 PM - midnight in India (Alex Kumar)", AlarmWords.line(ring, c, null, null, zone))
    }

    @Test fun indiaMomentHasWhatsAppDraftAndNeverAuto() {
        val ring = ms(today, 14, 30)
        val c = Classified(AlarmKind.INDIA_MIDNIGHT, "x", people = listOf("Alex Kumar"))
        val m = AlarmMoments.india(c, ring, "https://wa.me/919999999999?text=Happy%20birthday%20Alex!", "tel:+919999999999")
        assertEquals("It's midnight in India - wish Alex a happy birthday", m.title)
        assertEquals("Draft: Happy birthday Alex!", m.body)
        assertEquals(listOf("WhatsApp", "Call", "Done"), m.actions.map { it.label })
        assertTrue(m.notify)
        assertEquals("Midnight in India", AlarmMoments.india(Classified(AlarmKind.INDIA_MIDNIGHT, "x"), ring, null, null).title)
    }

    @Test fun eveningOfferForTomorrowsIndiaBirthday() {
        val now = ZonedDateTime.of(2026, 10, 19, 21, 0, 0, 0, zone)         // 06:30 IST on Oct 20
        val wishes = { d: LocalDate -> if (d == LocalDate.of(2026, 10, 21)) listOf("Alex Kumar") else emptyList() }
        val (text, at) = AlarmMoments.indiaOffer(now, null, wishes)!!
        assertEquals("Alex's birthday starts at 2:30 PM your time", text)
        assertEquals(ms(tomorrow, 14, 30), at)
        assertNull(AlarmMoments.indiaOffer(now, NextAlarm(at + 5 * 60_000L, null), wishes))   // an alarm is already near it
        assertNull(AlarmMoments.indiaOffer(now, null) { emptyList() })
    }

    // ---- unusual alarms ----

    private val usual = 7 * 60

    @Test fun earlierThanUsualNeedsAnHour() {
        assertEquals("2 h earlier than usual", Alarms.offset(ms(tomorrow, 5), usual, zone))
        assertEquals("1 h 30 min later than usual", Alarms.offset(ms(tomorrow, 8, 30), usual, zone))
        assertNull(Alarms.offset(ms(tomorrow, 6, 15), usual, zone))
        assertNull(Alarms.offset(ms(tomorrow, 5), null, zone))
    }

    @Test fun reasonPriorityFestivalThenFlightThenPlaceThenBusy() {
        val flight = ev(tomorrow, 8, 10, 60, "Flight", EventCategory.TRAVEL)
        val placed = ev(tomorrow, 7, 30, 30, "Standup", place = "HQ")
        val ring = ms(tomorrow, 5)
        assertEquals("Dasara", Alarms.unusualReason(ring, usual, zone, "Dasara", flight, placed, "6 meetings tomorrow"))
        assertEquals("Flight 8:10 AM", Alarms.unusualReason(ring, usual, zone, null, flight, placed, "6 meetings tomorrow"))
        assertEquals("Standup at HQ", Alarms.unusualReason(ring, usual, zone, null, null, placed, "6 meetings tomorrow"))
        assertEquals("6 meetings tomorrow", Alarms.unusualReason(ring, usual, zone, null, null, null, "6 meetings tomorrow"))
    }

    @Test fun noReasonFoundSaysNothing() {
        assertNull(Alarms.unusualReason(ms(tomorrow, 5), usual, zone, null, null, null, null))
        assertNull(Alarms.unusualReason(ms(tomorrow, 6, 45), usual, zone, "Dasara", null, null, null))   // not unusual
        val p = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 5), emptyList(), usualMinute = usual)!!
        assertNull(p.why)
        assertEquals("5:00 AM, 2 h earlier than usual", AlarmWords.line(ms(tomorrow, 5), Classified(AlarmKind.WAKE, "wake-up alarm"), usual, p.why, zone))
    }

    @Test fun dasaraShowsInWordsAndInTheNudge() {
        val p = Bedtime.plan(tomorrow, zone, alarm(tomorrow, 5), emptyList(), festival = "Dasara", usualMinute = usual)!!
        assertEquals("Dasara", p.why)
        assertEquals("5:00 AM, 2 h earlier than usual - Dasara", AlarmWords.line(ms(tomorrow, 5), Classified(AlarmKind.WAKE, "wake-up alarm"), usual, p.why, zone))
        assertEquals("Dasara tomorrow, alarm 5:00 AM. Sleep now for 6h.", BedNudges.text(p, NudgeStep.FIRST, late, zone).first)
        assertEquals("Dasara tomorrow", BedNudges.card(p, zone).first)
    }

    @Test fun festivalIsFoundInCalendarTitlesOrTheList() {
        val dasara = ev(tomorrow, 0, 0, 0, "Dasara", EventCategory.HOLIDAY, allDay = true).copy(begin = tomorrow.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        assertEquals("Dasara", Bedtime.festival(tomorrow, listOf(dasara), zone, null))
        assertEquals("Dussehra", Bedtime.festival(tomorrow, emptyList(), zone, "Dussehra"))
        assertNull(Bedtime.festival(tomorrow, listOf(ev(tomorrow, 10, 0, 30, "Standup")), zone, null))
    }

    @Test fun festivalBundleHasOutfitMusicIndiaClockAndCall() {
        val ring = ms(tomorrow, 5)            // 14:30 IST
        val m = AlarmMoments.festival("Dasara", ring, "Blue linen shirt", "Amma", "https://wa.me/919999999999")
        assertEquals("Happy Dasara", m.title)
        assertEquals(listOf("Wear" to "Blue linen shirt", "In India" to "2:30 PM"), m.facts)
        assertEquals(listOf("Play Telugu devotional", "Call Amma", "Done"), m.actions.map { it.label })
        assertEquals("MUSIC", m.actions[0].role)
        // Without an outfit or a number the bundle drops those lines instead of inventing them.
        val bare = AlarmMoments.festival("Dasara", ring, null, null, null)
        assertEquals(listOf("In India" to "2:30 PM"), bare.facts)
        assertEquals(listOf("Play Telugu devotional", "Done"), bare.actions.map { it.label })
    }

    @Test fun napAndLeaveMoments() {
        val ring = ms(today, 15, 0)
        val call = ev(today, 15, 0, 30, "call")
        assertEquals("Napped ~40 min - next: 3:00 PM call", AlarmMoments.nap(ring - 40 * 60_000L, ring, zone, call).title)
        assertEquals("Napped", AlarmMoments.nap(null, ring, zone, null).title)
        val e = ev(today, 15, 30, 60, "Pickup", place = "School")
        val l = AlarmMoments.leave(Classified(AlarmKind.LEAVE, "x", event = e, leaveBy = ms(today, 15, 10)), ring, zone, "geo:0,0?q=School")
        assertEquals(listOf("Starts" to "3:30 PM", "Leave by" to "3:10 PM", "At" to "School"), l.facts)
        assertEquals("Open in Maps", l.actions.first().label)
    }

    // ---- a game in the foreground ----

    @Test fun aGameHoldsTheNudgeForFifteenMinutesAtMost() {
        var s = NudgeState()
        assertEquals(NudgeStep.HOLD, BedNudges.decide(true, plan, s, late, awake = true, gaming = true))
        s = BedNudges.after(plan, s, NudgeStep.HOLD, late)
        assertEquals(late, s.heldSince)
        assertEquals(NudgeStep.HOLD, BedNudges.decide(true, plan, s, late + 10 * 60_000L, awake = true, gaming = true))
        assertEquals(NudgeStep.FIRST, BedNudges.decide(true, plan, s, late + 15 * 60_000L, awake = true, gaming = true))   // timed out
        assertEquals(NudgeStep.FIRST, BedNudges.decide(true, plan, s, late + 5 * 60_000L, awake = true, gaming = false))   // he left the game
        // Holding never counts as a nudge: the one-plus-one rule is untouched.
        assertEquals(0, s.sent)
        // No game, no hold.
        assertEquals(NudgeStep.FIRST, BedNudges.decide(true, plan, NudgeState(), late, awake = true, gaming = false))
    }

    @Test fun oneMoreMatchLineIsQuietAndOnlyAfterAGame() {
        assertEquals("One more match? Done about 11:25 PM, leaving 5h 35m before 5:00 AM", BedNudges.oneMoreMatch(plan, late, zone))
        assertTrue(BedNudges.text(plan, NudgeStep.FIRST, late, zone, afterGame = true).second!!.contains("One more match?"))
        assertFalse(BedNudges.text(plan, NudgeStep.FIRST, late, zone).second!!.contains("One more match?"))
        assertFalse(BedNudges.text(plan, NudgeStep.FOLLOW_UP, late, zone, afterGame = true).second.orEmpty().contains("One more match?"))
        assertTrue("com.example.chess" in dev.phonerecall.alarms.ForegroundApp.GAMES)
    }

    // ---- sport against sleep ----

    private val now9pm = ms(today, 21, 0)
    private val gp = Fixture("f1-jp", "Japan GP Race", ms(tomorrow, 1, 0), 2 * 3_600_000L)

    @Test fun aNightFixtureGetsWakeCatchUpOrSkipWithTheirBedtimes() {
        val picked = SportSleep.pick(listOf(gp), plan, now9pm, zone)
        assertEquals(gp, picked)
        val t = SportSleep.trade(gp, plan, zone)
        assertEquals(ms(today, 21, 0), t.wakeBed)         // an hour of nothing: up at 1:00 means bed by 9:00
        assertEquals(plan.bedtime, t.catchUpBed)
        assertEquals(plan.bedtime, t.skipBed)
        assertEquals("Wake: bed by 9:00 PM, back in bed about 3:00 AM. Catch up after: bed by 9:10 PM. Skip: bed by 9:10 PM.", SportSleep.body(t, zone))
    }

    @Test fun withoutAnEarlyStartTheNormalBedtimeIsElevenPm() {
        val t = SportSleep.trade(gp, null, zone)
        assertEquals(ms(today, 21, 0), t.wakeBed)
        assertEquals(ms(today, 23, 0), t.skipBed)
        val late = Fixture("x", "Bills at Chiefs", ms(today, 23, 30), 3 * 3_600_000L)
        assertEquals(ms(today, 19, 30), SportSleep.trade(late, null, zone).wakeBed)
        assertEquals(ms(today, 23, 0), SportSleep.trade(late, null, zone).catchUpBed)
    }

    @Test fun daytimeAnsweredTooSoonAndTooFarFixturesAreNotOffered() {
        val day = Fixture("d", "Barcelona v Real", ms(tomorrow, 14, 0), 2 * 3_600_000L)
        assertNull(SportSleep.pick(listOf(day), null, now9pm, zone))
        assertNull(SportSleep.pick(listOf(gp), plan, now9pm, zone, answered = setOf("f1-jp")))
        assertNull(SportSleep.pick(listOf(gp), plan, gp.start - 10 * 60_000L, zone))
        assertNull(SportSleep.pick(listOf(gp.copy(start = ms(tomorrow.plusDays(2), 1, 0))), plan, now9pm, zone))
    }

    @Test fun aFixtureOverlappingSleepBeforeAnEarlyStartCountsEvenAtTheEdgeOfTheNight() {
        val sixAm = Fixture("e", "Early kickoff", ms(tomorrow, 4, 30), 2 * 3_600_000L)    // 4:30 AM is in the night anyway
        assertEquals(sixAm, SportSleep.pick(listOf(sixAm), plan, now9pm, zone))
        val flightPlan = Bedtime.plan(tomorrow, zone, null, listOf(ev(tomorrow, 9, 30, 60, "Flight", EventCategory.TRAVEL)))!!   // up at 7:00
        val seven = Fixture("m", "Morning match", ms(tomorrow, 6, 45), 2 * 3_600_000L)
        assertNotNull(SportSleep.pick(listOf(seven), flightPlan, ms(today, 22, 0), zone))
        assertNull(SportSleep.pick(listOf(seven.copy(start = ms(tomorrow, 8, 0))), flightPlan, ms(today, 22, 0), zone))
    }
}
