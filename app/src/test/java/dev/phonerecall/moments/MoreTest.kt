package dev.phonerecall.moments

import dev.phonerecall.brain.ProposalKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Kairos's second set: photos and screens, copied text, receipts, mornings, trash, staples, bedtime, calls, aliases. */
class MoreTest {
    private val zone = ZoneId.of("America/New_York")
    // Tuesday, Oct 20 2026.
    private fun at(h: Int, m: Int = 0, plus: Long = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone).plusDays(plus)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val DAY = 86_400_000L

    // ---- 1. photos and screens ----

    @Test fun parkingSignBecomesAMeter() {
        val p = ScreenIntents.read("2 HOUR PARKING\n8AM - 6PM\nEXCEPT SUNDAY", at(10))!!
        assertEquals(MomentKind.SIGN, p.kind)
        assertEquals("6900", p.steps.single().args["seconds"])
        assertNull(ScreenIntents.read("NO PARKING ANYTIME", at(10)))
    }

    @Test fun flightConfirmationEventCheckInAndLeaveBy() {
        val text = "Your trip is confirmed\nFlight UA 1234\nBUF → ORD\nDeparts Fri, Oct 23, 2026 7:05 AM\nConfirmation ABC123"
        val p = ScreenIntents.read(text, at(10), airportMinutes = 20)!!
        assertEquals(MomentKind.FLIGHT, p.kind)
        assertEquals(listOf(ProposalKind.ADD_CALENDAR_EVENT, ProposalKind.ADD_REMINDER, ProposalKind.ADD_REMINDER), p.steps.map { it.kind })
        assertEquals("Flight UA 1234 BUF→ORD", p.steps[0].args["title"])
        assertEquals("2026-10-23T07:05", p.steps[0].args["start"])
        assertEquals("2026-10-22T07:05", p.steps[1].args["at"])
        // 2.5 h plus the 20 minute drive.
        assertEquals("2026-10-23T04:15", p.steps[2].args["at"])
        // Without a time it is not a flight card.
        assertNull(ScreenIntents.flight("Flight UA 1234 on Oct 23", at(10), null))
    }

    @Test fun flyerBecomesAnEventWithLeaveBy() {
        val p = ScreenIntents.read("FALL HARVEST FESTIVAL\nSaturday, Oct 24\n4 PM\nTickets at the door", at(10))!!
        assertEquals(MomentKind.EVENT, p.kind)
        assertEquals("FALL HARVEST FESTIVAL", p.steps[0].args["title"])
        assertEquals("2026-10-24T16:00", p.steps[0].args["start"])
        assertEquals("2026-10-24T15:30", p.steps[1].args["at"])
        assertNull(ScreenIntents.read("Meeting notes from Oct 24 at 4 PM", at(10)))
    }

    // ---- 2. copied text ----

    @Test fun trackingNumbersOpenTheCarrier() {
        val ups = Clips.read("1Z999AA10123456784", at(10)) as Clip.Package
        assertEquals("UPS", ups.carrier)
        assertTrue(ups.url.startsWith("https://www.ups.com/"))
        assertEquals("USPS", (Clips.read("9400111899223197428490", at(10)) as Clip.Package).carrier)
        assertEquals("Amazon", (Clips.read("TBA123456789012", at(10)) as Clip.Package).carrier)
        assertEquals("FedEx", (Clips.read("123456789012", at(10)) as Clip.Package).carrier)
        assertNull(Clips.read("see you at lunch", at(10)))
    }

    @Test fun addressOpensMapsAndATimeGivesALeaveBy() {
        val a = Clips.read("Dinner at 7 PM, 1250 Market St, Springfield, IL 62701", at(10)) as Clip.Address
        assertTrue(a.text.startsWith("1250 Market St"))
        assertTrue(a.mapsUri.startsWith("geo:0,0?q=1250"))
        assertEquals("2026-10-20T18:30", Clips.leavePlan(a)!!.steps.single().args["at"])
        assertNull(Clips.leavePlan(Clips.read("1250 Market St", at(10)) as Clip.Address))
    }

    // ---- 3. receipts ----

    @Test fun receiptTotalNotSubtotal() {
        val r = Receipts.read("FRESH MART\nStore #57\n10/18/26\nMILK 2% GAL 3.49\nSUBTOTAL 41.20\nTAX 1.12\nTOTAL 42.32\nVISA ****1234 APPROVED", LocalDate.of(2026, 10, 20))!!
        assertEquals("Fresh Mart", r.merchant)
        assertEquals(4232L, r.cents)
        assertEquals(LocalDate.of(2026, 10, 18), r.date)
        assertNull(Receipts.read("Preheat oven to 425°F. Bake 18 minutes.", LocalDate.of(2026, 10, 20)))
    }

    // ---- 4. Buffalo mornings ----

    @Test fun snowOrIceBeforeTheCommute() {
        val dep = at(8, 20, plus = 1)
        fun hour(h: Int, code: Int, t: Double = -2.0, p: Double = 0.0, s: Double = 0.0) = HourCast(ms(at(h, plus = 1)), t, code, p, s)
        val snow = MorningHazards.at(listOf(hour(6, 73, s = 1.2), hour(7, 3)), dep)!!
        assertEquals("Snow before your 8:20 AM commute", snow.words)
        assertEquals("Leave 15 min early, scrape the car", snow.advice)
        assertEquals("Freezing rain", MorningHazards.at(listOf(hour(7, 61, t = -1.0, p = 0.4)), dep)!!.words.substringBefore(" before"))
        assertNull(MorningHazards.at(listOf(hour(7, 61, t = 4.0, p = 0.4), hour(11, 73, s = 2.0)), dep))
        val card = MomentRules.pick(MomentContext(at(20), hazard = snow), MomentsPrefs())!!
        assertEquals(MomentKind.HAZARD, card.kind)
        assertNull(MomentRules.pick(MomentContext(at(15), hazard = snow), MomentsPrefs()))
    }

    // ---- 5. trash night ----

    @Test fun trashAsksOnceThenRemindsTheEveningBefore() {
        val ask = MomentRules.pick(MomentContext(at(19, 30)), MomentsPrefs())!!
        assertEquals("Is tomorrow trash pickup?", ask.content.title)
        val yes = ask.teach.getValue("yes")(MomentsPrefs())
        assertEquals(DayOfWeek.WEDNESDAY, yes.trashDay)
        val no = ask.teach.getValue("no")(MomentsPrefs())
        assertNull(MomentRules.pick(MomentContext(at(19, 45)), no))
        assertNull(MomentRules.pick(MomentContext(at(19, 30)), MomentsPrefs(trashAsks = 3)))
        // Next Tuesday at 8:15 PM: the reminder, with a 9 PM option.
        val tonight = MomentRules.pick(MomentContext(at(20, 15, plus = 7)), yes)!!
        assertEquals("Trash goes out tonight", tonight.content.title)
        assertEquals("2026-10-27T21:00", tonight.plans.getValue("nine")!!.steps.single().args["at"])
        assertNull(MomentRules.pick(MomentContext(at(20, 15, plus = 8)), yes))
    }

    // ---- 6. staples, with aliases ----

    @Test fun steadyStapleNotOnTheListIsDue() {
        val now = ms(at(10))
        fun bought(daysAgo: Int, name: String = "milk") = ListEntry(name, now - (daysAgo + 1) * DAY, now - daysAgo * DAY, open = false)
        val steady = listOf(bought(25), bought(19), bought(13), bought(7))
        val due = Staples.due(steady, Aliases(), now).single()
        assertEquals("milk", due.name)
        assertEquals(6, due.everyDays)
        // On the list already, too few, or bought at random: nothing.
        assertTrue(Staples.due(steady + ListEntry("Milk", now, null, open = true), Aliases(), now).isEmpty())
        assertTrue(Staples.due(listOf(bought(13), bought(7)), Aliases(), now).isEmpty())
        assertTrue(Staples.due(listOf(bought(30), bought(28), bought(14), bought(7)), Aliases(), now).isEmpty())
        // Three names for one dal count as one staple once he corrected them.
        val names = Aliases().learn("kandi pappu", "toor dal").learn("Laxmi Toor Dal 4lb", "toor dal")
        val dal = Staples.due(listOf(bought(25, "kandi pappu"), bought(18, "toor dal"), bought(11, "Laxmi Toor Dal 4lb")), names, now + 3 * DAY).single()
        assertEquals("toor dal", dal.name)
        val card = MomentRules.pick(MomentContext(at(10), staples = listOf(dal)), MomentsPrefs())!!
        assertEquals("toor dal", card.plans.getValue("go")!!.steps.single().args["items"])
    }

    @Test fun aliasesLearnOnlyFromCorrections() {
        assertEquals(listOf("kandi pappu" to "toor dal"), Aliases.corrections(listOf("kandi pappu", "onions"), listOf("toor dal", "onions")))
        assertTrue(Aliases.corrections(listOf("a", "b"), listOf("a")).isEmpty())
        val a = Aliases().learn("kandi pappu", "toor dal").learn("toor dal", "Toor Dal (Laxmi)")
        assertEquals("toor dal (laxmi)", a.canonical("Kandi Pappu"))
        assertEquals("rice", a.canonical("Rice"))
    }

    // ---- 7. charging before bed ----

    @Test fun chargeTonightWithAnEarlyAlarm() {
        val ctx = MomentContext(at(21, 30), battery = 24, nextAlarm = at(5, 15, plus = 1))
        assertEquals("Charge tonight: 24%", MomentRules.pick(ctx, MomentsPrefs(trashAsks = 3))!!.content.title)
        assertNull(MomentRules.pick(ctx.copy(nextAlarm = at(9, 0, plus = 1)), MomentsPrefs(trashAsks = 3)))
        assertNull(MomentRules.pick(ctx.copy(charging = true), MomentsPrefs(trashAsks = 3)))
        assertNull(MomentRules.pick(ctx.copy(now = at(19)), MomentsPrefs(trashAsks = 3)))
    }

    // ---- 8. the usual call home ----

    @Test fun weeklyCallHabitAndIndiaTime() {
        val sundays = (1..4).map { ms(ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, zone).minusWeeks(it.toLong() - 1).plusMinutes(it * 5L)) }
        val slot = CallHabit.slot(sundays, ms(at(10)), zone)!!
        assertEquals(DayOfWeek.SUNDAY, slot.day)
        assertEquals(9 * 60 + 15, slot.minute)
        assertNull(CallHabit.slot(sundays.take(2), ms(at(10)), zone))
        val sunday = ZonedDateTime.of(2026, 10, 25, 9, 5, 0, 0, zone)
        val card = MomentRules.pick(MomentContext(sunday, callSlot = slot), MomentsPrefs())!!
        assertEquals("Call home?", card.content.title)
        assertTrue(card.content.body!!.startsWith("It's 6:35 PM in India"))
        assertEquals("app:com.whatsapp", card.opens["open"])
        assertNull(MomentRules.pick(MomentContext(sunday.plusHours(2), callSlot = slot), MomentsPrefs()))
        assertNotNull(slot)
    }
}
