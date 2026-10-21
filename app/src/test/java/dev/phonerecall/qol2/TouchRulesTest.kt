package dev.phonerecall.qol2

import dev.phonerecall.moneta.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class TouchRulesTest {
    private val zone = ZoneId.of("America/New_York")
    /** Tuesday 2026-10-20, 2:00 PM in Buffalo. */
    private val now = ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, zone)
    private val ms = now.toInstant().toEpochMilli()
    private val min = 60_000L
    private val hour = 3_600_000L
    private fun ctx(at: ZonedDateTime = now) = TouchContext(now = at)
    private fun charge(cat: Category, minsAgo: Long, cents: Long = 4_000, merchant: String = "Shop") = Charge("r$minsAgo", merchant, cat.name, ms - minsAgo * min, cents)
    private fun hours(from: Long, n: Int, code: Int = 0, temp: Double = 15.0) = (0 until n).map { Hour(from + it * hour, temp, code, 0.0) }

    @Test fun tipShowsThreeChipsAfterARestaurantCharge() {
        val c = TouchRules.tip(ctx().copy(charges = listOf(charge(Category.DINING, 20, 5_000, "Taste of India"))))!!
        assertEquals("18% = $9.00", c.content.lead)
        assertEquals(listOf("15%", "18%", "20%", "Done"), c.content.actions.map { it.label })
        assertEquals(TouchDo.Copy("10.00"), c.does["t20"])
        assertNull(TouchRules.tip(ctx().copy(charges = listOf(charge(Category.DINING, 120)))))
    }

    @Test fun anUncategorizedChargeGetsThreeGuessesFromTheMerchantFirst() {
        val c = charge(Category.OTHER, 60, merchant = "Sample Pizza")
        val card = TouchRules.fixCategory(ctx().copy(charges = listOf(c, charge(Category.GROCERIES, 900), charge(Category.GROCERIES, 1000))))!!
        val cats = card.does.values.filterIsInstance<TouchDo.Categorize>().map { it.category }
        assertEquals(listOf("DINING", "GROCERIES", "SHOPPING"), cats)
        assertEquals("What was Sample Pizza?", card.content.title)
    }

    @Test fun leavingAStoreAfterPayingThereOffersToClearItsList() {
        val v = StoreVisit("Wegmans", "Groceries", ms - 50 * min, ms - 10 * min, open = 4)
        val card = TouchRules.clearList(ctx().copy(visits = listOf(v), charges = listOf(charge(Category.GROCERIES, 15, merchant = "WEGMANS #57"))))!!
        assertEquals("Clear the Groceries list?", card.content.title)
        assertEquals(TouchDo.ClearList("Groceries"), card.does["clear"])
        // No charge during the visit: nothing to clear.
        assertNull(TouchRules.clearList(ctx().copy(visits = listOf(v))))
    }

    @Test fun restockAndReceiptCards() {
        val s = StoreStaple("Costco", "eggs", 14, ms - 15 * 86_400_000L)
        assertEquals(TouchDo.AddToList("Costco", "eggs"), TouchRules.restock(ctx().copy(staples = listOf(s)))!!.does["add"])
        val r = TouchRules.receipt(ctx().copy(charges = listOf(charge(Category.GROCERIES, 30, merchant = "Wegmans"))))!!
        assertEquals(TouchDo.SaveReceipt("Wegmans 2026-10-20"), r.does["save"])
    }

    @Test fun aChargeAfterACancellationIsFlaggedWithItsProof() {
        val p = TouchPrefs(proofs = listOf(Proof("Netflix", ms - 10 * 86_400_000L, "Your membership has been cancelled", "Gmail")))
        val card = TouchRules.cancelProof(ctx().copy(charges = listOf(charge(Category.SUBSCRIPTIONS, 60, 1_549, "NETFLIX.COM"))), p)!!
        assertEquals("Netflix charged after you cancelled", card.content.title)
        assertNull(TouchRules.cancelProof(ctx(), p))
    }

    @Test fun cancellationNoticesAreReadButNotOrderCancellations() {
        assertEquals("Netflix", TouchParse.cancellation("com.google.android.gm", "Gmail", "Netflix", "Your Netflix membership has been cancelled."))
        assertEquals("Hulu", TouchParse.cancellation("com.google.android.gm", "Gmail", "Hulu <no-reply@hulu.com>", "We've processed your request. Your subscription was cancelled"))
        assertNull(TouchParse.cancellation("com.amazon.mShop.android.shopping", "Amazon", "Order cancelled", "Your order has been cancelled"))
        assertNull(TouchParse.cancellation("com.google.android.gm", "Gmail", "Netflix", "Your plan renews on Nov 2"))
    }

    @Test fun garageShowsTheEntryTimeAndElapsed() {
        val c = ctx().copy(staySince = ms - 80 * min, stayName = "Main Place Parking Ramp", garage = TouchParse.garage("Main Place Parking Ramp"))
        val card = TouchRules.garage(c)!!
        assertEquals("1 h 20 min", card.content.facts.first { it.label == "So far" }.value)
        assertNull(TouchRules.garage(c.copy(garage = TouchParse.garage("Wegmans"))))
    }

    @Test fun carRenewalComesThirtyDaysBefore() {
        val p = TouchPrefs(registration = "2026-11-12")
        val card = TouchRules.carRenewal(ctx(), p)!!
        assertEquals(TouchDo.Open(TouchRules.DMV_REGISTRATION), card.does["dmv"])
        assertNull(TouchRules.carRenewal(ctx(), TouchPrefs(registration = "2026-12-30")))
    }

    @Test fun carWashOnlyOnADryMildFreeDayAtMostWeekly() {
        val dry = ctx().copy(hours = hours(ms - hour, 40))
        assertNotNull(TouchRules.carWash(dry, TouchPrefs()))
        assertNull(TouchRules.carWash(dry.copy(hours = hours(ms - hour, 40).mapIndexed { i, h -> if (i == 20) h.copy(code = 61) else h }), TouchPrefs()))
        assertNull(TouchRules.carWash(dry, TouchPrefs(shownAt = mapOf(Touch.CAR_WASH to ms - 2 * 86_400_000L))))
    }

    @Test fun layoverNamesTheGateAndTheBoardingCountdown() {
        val a = FlightLeg("Buffalo (BUF)", "Chicago (ORD)", ms - 3 * hour, ms - 20 * min, "UA 100", "")
        val b = FlightLeg("Chicago (ORD)", "Delhi (DEL)", ms + 2 * hour, ms + 16 * hour, "AI 126", "Gate C12")
        val card = TouchRules.layover(ctx().copy(legs = listOf(b, a)))!!
        assertEquals("Gate C12, boards in 1 h 30 min", card.content.title)
        assertEquals(ms + 90 * min, card.content.live!!.countdownTo)
    }

    @Test fun ticketChipAtTheStart() {
        val e = DayEvent("e1", "Bills vs Jets", ms + 30 * min, ms + 4 * hour, false, TouchParse.ticketLinks("Tickets: https://www.ticketmaster.com/x/123"))
        val card = TouchRules.ticket(ctx().copy(events = listOf(e)))!!
        assertEquals(TouchDo.Open("https://www.ticketmaster.com/x/123"), card.does["ticket"])
        assertEquals("Ticket", card.content.live!!.chip)
    }

    @Test fun weatherTouches() {
        val dep = now.plusMinutes(20)
        val hot = ctx().copy(atHome = true, departure = dep, hours = hours(ms, 10, temp = 30.0))
        assertEquals("Hot out: 86°F by 2 PM", TouchRules.hotDay(hot)!!.content.title)
        val walk = ctx().copy(hours = hours(now.withMinute(0).toInstant().toEpochMilli(), 2) + hours(ms + 2 * hour, 3, code = 61))
        assertEquals("Walk now, rain at 4 PM", TouchRules.walkWindow(walk)!!.content.title)
        // A meeting now: no walk.
        assertNull(TouchRules.walkWindow(walk.copy(events = listOf(DayEvent("m", "1:1", ms - 10 * min, ms + 20 * min, false)))))
    }

    @Test fun matchModeHoldsAlertsUntilFullTimeAndCanBeTurnedOff() {
        val m = Followed("m1", "BAR vs RMA", ms + 4 * min, live = false, done = false)
        val c = ctx().copy(matches = listOf(m))
        assertNotNull(TouchRules.matchMode(c))
        val f = TouchRules.focus(c, TouchPrefs())!!
        assertEquals("match", f.why)
        assertEquals(m.start + 3 * hour, f.until)
        assertEquals("off", TouchRules.focus(c, TouchPrefs(focus = Focus("off", 0, "off:m1")))!!.why)
        assertNull(TouchRules.focus(c, TouchPrefs(off = setOf(Touch.MATCH_MODE))))
    }

    @Test fun officeFocusUntilHeTurnsItOff() {
        val c = ctx().copy(atOffice = true, officeDay = true)
        assertEquals("office", TouchRules.focus(c, TouchPrefs())!!.why)
        assertNull(TouchRules.focus(c, TouchPrefs(focusOffDay = now.toLocalDate().toEpochDay())))
        assertEquals("Lunch out around 2:10 PM?", TouchRules.lunch(c.copy(lunchAt = 14 * 60 + 10))!!.content.title)
    }

    @Test fun quickTimerIdleChipsAndTheRunningCountdown() {
        val idle = TouchRules.quickTimer(ctx(), TouchPrefs())!!
        assertEquals(listOf("5 min", "10 min", "30 min", "Laundry 45"), idle.content.actions.map { it.label })
        val run = TouchRules.quickTimer(ctx(), TouchPrefs(running = listOf(RunningTimer("t1", "Laundry", ms + 32 * min))))!!
        assertEquals("Laundry: 32 min left", run.content.title)
        assertEquals(ms + 32 * min, run.content.live!!.countdownTo)
    }

    @Test fun bedtimeAfterTenThirtyBeforeAnEarlyAlarm() {
        val late = now.withHour(22).withMinute(45)
        val c = ctx(late).copy(nextAlarm = late.plusHours(7))
        assertNotNull(TouchRules.bedtime(c))
        assertNull(TouchRules.bedtime(c.copy(nextAlarm = late.plusHours(9))))
        // Bedtime mode is allowed in quiet hours.
        assertTrue(TouchRules.open(Touch.BEDTIME_MODE, TouchPrefs(), ctx(now.withHour(23))))
        assertFalse(TouchRules.open(Touch.TIP, TouchPrefs(), ctx(now.withHour(23))))
    }

    @Test fun plugInOffersTheChargerPlug() {
        val c = ctx().copy(atHome = true, staySince = ms - 5 * min, battery = 22, plugs = listOf(Plug("p1", "Desk plug", false)))
        assertEquals(TouchDo.Plug("p1", "Desk plug"), TouchRules.plugIn(c, TouchPrefs(charger = "p1"))!!.does["plug"])
        assertEquals("Charger plug?", TouchRules.plugIn(c, TouchPrefs())!!.content.actions.first().label)
        assertNull(TouchRules.plugIn(c.copy(charging = true), TouchPrefs()))
    }

    @Test fun resumeOnTheFoldAfterADriveWithMusic() {
        val p = TouchPrefs(driveEndedAt = ms - 5 * min, musicAtDriveEnd = true)
        assertEquals("Resume owner_1?", TouchRules.resumeMusic(ctx().copy(innerScreen = true), p)!!.content.title)
        assertNull(TouchRules.resumeMusic(ctx().copy(innerScreen = false), p))
    }

    @Test fun borrowedLinesParseAndComeBackWhenDue() {
        val b = TouchRules.parseBorrowed("Jamie has my controller till Nov 2", LocalDate.of(2026, 10, 20), 1L)!!
        assertEquals("Jamie" to "controller", b.who to b.what)
        assertEquals("2026-11-02", b.due)
        assertEquals("Alex" to "drill", TouchRules.parseBorrowed("lent Alex my drill", LocalDate.of(2026, 10, 20), 1L)!!.let { it.who to it.what })
        assertNull(TouchRules.parseBorrowed("buy milk", LocalDate.of(2026, 10, 20), 1L))
        val due = b.copy(due = "2026-10-20", since = ms - 18 * 86_400_000L)
        assertEquals("Jamie has your controller", TouchRules.borrowed(ctx(), TouchPrefs(borrowed = listOf(due)))!!.content.title)
        assertNull(TouchRules.borrowed(ctx(), TouchPrefs(borrowed = listOf(due.copy(returned = true)))))
    }

    @Test fun readLaterAtHisLeisureHours() {
        val p = TouchPrefs(links = listOf(SavedLink("l1", "https://www.theverge.com/a", "A story", ms - 86_400_000L)))
        assertNull(TouchRules.readLater(ctx(), p))
        assertNotNull(TouchRules.readLater(ctx(now.withHour(20)), p))
        // Learned: he reads at 2 PM on Tuesdays.
        val learned = p.copy(readHours = List(5) { 24 + 14 })
        assertNotNull(TouchRules.readLater(ctx(), learned))
    }

    @Test fun dayFolderAndAppointmentPacket() {
        val p = TouchPrefs(pins = listOf(Pin("p1", "Lease PDF", "content://x/1", date = "2026-10-20"), Pin("p2", "Referral", "content://x/2", event = "e1")))
        assertEquals("Lease PDF", TouchRules.dayFolder(ctx(), p)!!.content.actions.first().label)
        val e = DayEvent("e1", "Dentist", ms + hour, ms + 2 * hour, false)
        assertEquals(TouchDo.OpenPin("content://x/2"), TouchRules.packet(ctx().copy(events = listOf(e)), p)!!.does["p0"])
    }

    @Test fun healthLightDayAndAudio() {
        assertEquals("Samsung Health hasn't synced since 7:00 AM", TouchRules.healthSync(ctx().copy(stepsSyncedAt = ms - 7 * hour))!!.content.title)
        assertNull(TouchRules.healthSync(ctx().copy(stepsSyncedAt = ms - hour)))
        assertNotNull(TouchRules.lightDay(ctx(now.withHour(8)).copy(shortNight = true)))
        // No numbers on the light-day card.
        assertFalse(TouchRules.lightDay(ctx(now.withHour(8)).copy(shortNight = true))!!.content.let { "${it.title} ${it.body}" }.any { it.isDigit() })
        assertNotNull(TouchRules.audioDrop(ctx().copy(audioDropAt = ms - 2 * min)))
        assertNull(TouchRules.audioDrop(ctx().copy(audioDropAt = ms - 2 * min, musicNow = true)))
    }

    @Test fun tripPhotosAfterATripEnds() {
        val e = Ended("trip:t1", "New York", ms - 4 * 86_400_000L, ms - 20 * hour)
        val card = TouchRules.tripPhotos(ctx().copy(ended = listOf(e)))!!
        assertEquals(TouchDo.PickPhotos(e.from, e.to), card.does["pick"])
    }

    @Test fun quietKeepsOnlyTimersNotesAndConnections() {
        val c = ctx().copy(quiet = true, charges = listOf(charge(Category.DINING, 10)))
        val cards = TouchRules.cards(c, TouchPrefs())
        assertTrue(cards.isNotEmpty())
        assertTrue(cards.all { it.touch == Touch.QUICK_TIMER })
        // Handled and switched-off touches stay away.
        val loud = ctx().copy(charges = listOf(charge(Category.DINING, 10)))
        assertTrue(TouchRules.cards(loud, TouchPrefs()).any { it.touch == Touch.TIP })
        assertFalse(TouchRules.cards(loud, TouchPrefs(off = setOf(Touch.TIP))).any { it.touch == Touch.TIP })
        assertFalse(TouchRules.cards(loud, TouchPrefs(handled = mapOf(Touch.TIP to "r10"))).any { it.touch == Touch.TIP })
    }
}
