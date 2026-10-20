package dev.phonerecall.argo

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.widgets.WidgetSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

object ArgoFixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")
    fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, z: ZoneId = zone) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, z).toInstant().toEpochMilli()
    val now = at(2027, 2, 9, 9)

    /** All-day events are kept at UTC midnight, as Android does. */
    fun utc(y: Int, mo: Int, d: Int) = ZonedDateTime.of(y, mo, d, 0, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    fun ev(id: Long, title: String, begin: Long, end: Long, allDay: Boolean = false, cat: EventCategory = EventCategory.TRAVEL, place: String? = null, cal: Long = 1) =
        CalItem(id, cal, title, begin, end, allDay, cat, "Personal", place, null, false)

    /** Items as evidence (Iris's as her feed, the rest as one calendar), then trips rebuilt from it. */
    fun evidence(items: List<TripItem>, feed: String = "cal:1"): List<Ev> = items.mapNotNull { Evidence.of(it, if (it.source == "iris") "iris" else feed, 0.9) }
    fun build(items: List<TripItem>, trips: List<Trip> = emptyList()): List<Trip> = Rebuild.run(ArgoData(trips = trips, evidence = evidence(items)), now, zone).trips

    /** Boston Mar 4-8 (synthetic): train there 8:10 to 4:45 PM, Example Hotel, dinner with Jordan, train back. */
    val train = Leg("iris:t1", LegMode.TRAIN, "Buffalo Exchange St", "South Station", at(2027, 3, 4, 8, 10), at(2027, 3, 4, 16, 45), "Amtrak", "Lake Shore 448", doc = "calypso-item-01", source = "iris")
    val back = Leg("iris:t2", LegMode.TRAIN, "South Station", "Buffalo Exchange St", at(2027, 3, 8, 15, 10), at(2027, 3, 8, 23, 5), "Amtrak", "Lake Shore 449", returning = true, source = "iris")
    val hotel = Stay("iris:h1", "Example Hotel", "120 W 2nd St, Boston, MA 02116", at(2027, 3, 4, 15), at(2027, 3, 8, 11), "calypso-item-02", "iris")
    val dinner = TripPlan("iris:p1", "Dinner with Jordan", at(2027, 3, 5, 19), "North End", listOf("Jordan"), "iris")
    val boston = Trip("t-bos", "Boston", zone = "America/New_York", legs = listOf(train, back), stays = listOf(hotel), plans = listOf(dinner), people = listOf("Jordan"), status = TripStatus.PLANNED, docs = listOf("calypso-item-01", "calypso-item-02"))
}

/** Robolectric for org.json (Iris's trip.info). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArgoTest {
    private val F = ArgoFixtures
    private val zone = F.zone

    // ---- capture ----

    @Test fun whenParsesMonthsRangesAndIsoDates() {
        val today = LocalDate.of(2027, 2, 9)
        assertEquals(YearMonth.of(2027, 3), WhenParse.parse("in March", today).month)
        assertNull(WhenParse.parse("in March", today).start)
        assertEquals(YearMonth.of(2028, 1), WhenParse.parse("January", today).month)
        WhenParse.parse("Mar 4-8", today).let { assertEquals(LocalDate.of(2027, 3, 4), it.start); assertEquals(LocalDate.of(2027, 3, 8), it.end) }
        WhenParse.parse("December 30 to Jan 2", today).let { assertEquals(LocalDate.of(2027, 12, 30), it.start); assertEquals(LocalDate.of(2028, 1, 2), it.end) }
        WhenParse.parse("2027-03-04 to 2027-03-08", today).let { assertEquals(LocalDate.of(2027, 3, 8), it.end) }
        WhenParse.parse("4-8 March", today).let { assertEquals(LocalDate.of(2027, 3, 4), it.start) }
        assertEquals(YearMonth.of(2027, 3), WhenParse.parse("next month", today).month)
    }

    @Test fun citiesKnowAliasesAirportsAndHome() {
        assertEquals("New York", Cities.find("Flight to NYC"))
        assertEquals("New York", Cities.find("B6 1234 BUF to JFK"))
        assertEquals("Denver", Cities.find("Trip to Denver"))
        assertEquals("Boston", Cities.find("Amtrak to South Station"))
        assertTrue(Cities.same("nyc", "New York City"))
        assertNull(Cities.find("Team sync"))
    }

    @Test fun calendarTravelEventsMakeOneTripWithTheWayBack() {
        val events = listOf(
            F.ev(1, "Flight to Boston", F.at(2027, 3, 4, 8, 10), F.at(2027, 3, 4, 9, 35)),
            F.ev(2, "Hotel check-in", F.at(2027, 3, 4, 15), F.at(2027, 3, 8, 11), place = "Example Hotel, Boston"),
            F.ev(3, "Theater: Example Show", F.at(2027, 3, 6, 19), F.at(2027, 3, 6, 22), cat = EventCategory.PERSONAL, place = "Back Bay"),
            F.ev(4, "Train to Buffalo", F.at(2027, 3, 8, 15, 10), F.at(2027, 3, 8, 23, 5)),
            F.ev(5, "Team sync", F.at(2027, 3, 2, 9), F.at(2027, 3, 2, 10), cat = EventCategory.WORK),
        )
        val trips = F.build(CalendarCapture.items(events, zone))
        assertEquals(1, trips.size)
        val t = trips.single()
        assertEquals("Boston", t.place)
        assertEquals(TripStatus.PLANNED, t.status)
        assertEquals(LegMode.FLIGHT, t.outbound?.mode)
        assertEquals(LegMode.TRAIN, t.back?.mode)
        assertEquals(1, t.stays.size)
        assertEquals(listOf("Theater: Example Show"), t.plans.map { it.title })
        assertEquals(LocalDate.of(2027, 3, 4) to LocalDate.of(2027, 3, 8), t.days(zone))
        // Reading the calendar again changes nothing.
        assertEquals(trips, F.build(CalendarCapture.items(events, zone), trips))
    }

    @Test fun irisTripInfoIsParsedMergedUpdatedAndCancelled() {
        val flight = """{"id":"0123456789abcdef01234567","kind":"flight","place":"Boston","tz":"America/New_York","from":"Buffalo (BUF)","to":"Boston (BOS)",
            "depart":"2027-03-04T08:10","arrive":"2027-03-04T09:35","carrier":"JetBlue","number":"B6 1234","calypso_id":"abcdef123456"}"""
        val item = IrisInfo.parse(flight, zone) as TripItem.LegItem
        assertEquals("Boston", item.place)
        assertEquals(F.at(2027, 3, 4, 8, 10), item.leg.departAt)
        assertEquals("abcdef123456", item.leg.doc)
        // The calendar already had the flight: one leg, Iris's details, Iris's id.
        val cal = F.evidence(CalendarCapture.items(listOf(F.ev(1, "Flight to Boston", F.at(2027, 3, 4, 8), F.at(2027, 3, 4, 9, 30))), zone))
        fun trips(ev: List<Ev>) = Rebuild.run(ArgoData(evidence = ev), F.now, zone).trips
        var ev = Evidence.iris(cal, listOf(item))
        assertEquals(1, trips(ev).single().legs.size)
        assertEquals("B6 1234", trips(ev).single().legs.single().number)
        assertEquals("iris:0123456789abcdef01234567", trips(ev).single().legs.single().id)
        assertEquals(F.at(2027, 3, 4, 8, 10), trips(ev).single().legs.single().departAt)     // Iris's time over the calendar's
        assertEquals(listOf("abcdef123456"), trips(ev).single().docs)
        // A gate later: the same leg, updated in place.
        val gate = IrisInfo.parse(flight.replace("0123456789abcdef01234567", "fedcba9876543210fedcba98").replace("\"carrier\"", "\"platform\":\"Gate 4\",\"carrier\""), zone)!!
        ev = Evidence.iris(ev, listOf(gate))
        assertEquals("Gate 4", trips(ev).single().legs.single().platform)
        // Cancelling it (by the latest id) drops Iris's copies; the calendar's own event is still a flight.
        val cancel = IrisInfo.parse("""{"id":"aaaaaaaaaaaaaaaaaaaaaaaa","kind":"cancel","place":"Boston","ref":"${trips(ev).single().legs.single().id.removePrefix("iris:")}"}""", zone)!!
        val left = trips(Evidence.iris(ev, listOf(cancel))).single().legs.single()
        assertTrue(left.id.startsWith("cal:"))
        assertEquals("", left.number)
    }

    @Test fun irisSendsEveryTravelBookingAsAPlan() {
        val ferry = IrisInfo.parse("""{"id":"0123456789abcdef01234567","kind":"event","place":"Boston","tz":"America/New_York","title":"Harbor islands ferry","venue":"Example Harbor Cruises","at":"2027-03-18T10:00","status":"booked"}""", zone) as TripItem.PlanItem
        assertEquals("Boston", ferry.place)
        assertEquals("event" to "Example Harbor Cruises", ferry.plan.kind to ferry.plan.place)
        assertEquals(F.at(2027, 3, 18, 10), ferry.plan.at)
        val car = IrisInfo.parse("""{"id":"0123456789abcdef01234568","kind":"car","place":"Denver","tz":"America/Denver","title":"Hertz rental","at":"2027-03-03T10:00","until":"2027-03-07T09:00"}""", zone) as TripItem.PlanItem
        assertEquals(F.at(2027, 3, 7, 9, z = ZoneId.of("America/Denver")), car.plan.end)
        assertTrue((IrisInfo.parse("""{"id":"0123456789abcdef01234569","kind":"event","place":"Boston","title":"Harbor islands ferry","at":"2027-03-19T10:00","status":"changed"}""", zone) as TripItem.PlanItem).changed)
        val cancel = IrisInfo.parse("""{"id":"0123456789abcdef0123456a","kind":"event","place":"Boston","title":"Harbor islands ferry","status":"cancelled"}""", zone) as TripItem.Cancel
        assertEquals("Harbor islands ferry", cancel.match!!.title)
        val ev = Evidence.iris(emptyList(), listOf(ferry))
        assertTrue(Evidence.iris(ev, listOf(cancel)).isEmpty())
    }

    @Test fun irisInfoWithAConfirmationNumberIsRefused() {
        assertNull(IrisInfo.parse("""{"id":"0123456789abcdef01234567","kind":"flight","place":"Boston","depart":"2027-03-04T08:10","number":"PNR QX7Z2B"}""", zone))
        assertNull(IrisInfo.parse("""{"id":"0123456789abcdef01234567","kind":"hotel","place":"Boston","name":"Example 12345678","check_in":"2027-03-04"}""", zone))
        assertNull(IrisInfo.parse("""{"id":"0123456789abcdef01234567","kind":"cruise","place":"Boston"}""", zone))
    }

    @Test fun delphiNoteMakesAVagueTripThenDatesFillIt() {
        val (one, t) = Trips.note(emptyList(), "Boston", "in March", "seeing Jordan", F.now, zone)
        assertEquals(TripStatus.IDEA, t.status)
        assertEquals("2027-03", t.month)
        assertEquals("in March", t.vague)
        assertEquals("Boston in March", "${t.place} ${Travel.countdown(t, F.now, zone)}")
        assertEquals("Sometime in March", Travel.dates(t, zone))
        val (two, t2) = Trips.note(one, "Boston", "Mar 4-8", null, F.now, zone)
        assertEquals(1, two.size)
        assertEquals(t.id, t2.id)
        assertEquals(TripStatus.PLANNED, t2.status)
        assertNull(t2.month)
        assertEquals(listOf("seeing Jordan"), t2.notes)
        assertEquals("2027-03-04", t2.saidStart)
        // A booking later in March joins the same trip, which keeps his notes.
        val withTrain = F.build(listOf(TripItem.LegItem("Boston", F.train, "iris")), two)
        assertEquals(1, withTrain.size)
        assertEquals(t.id, withTrain.single().id)
        assertEquals(listOf("seeing Jordan"), withTrain.single().notes)
    }

    @Test fun dismissedTripsAreNotMadeAgainFromTheCalendar() {
        val events = listOf(F.ev(1, "Flight to Denver", F.at(2027, 2, 22, 7), F.at(2027, 2, 22, 10)))
        val trips = F.build(CalendarCapture.items(events, zone)).map { it.copy(status = TripStatus.DISMISSED) }
        assertEquals(trips, F.build(CalendarCapture.items(events, zone), trips))
    }

    // ---- what's missing ----

    @Test fun aVagueTripIsMissingDatesTransportAndAStay() {
        val t = Trips.note(emptyList(), "Boston", "March", null, F.now, zone).second
        assertEquals(listOf(Gap.DATES, Gap.TRANSPORT, Gap.STAY), Gaps.of(t, null, F.now, zone).map { it.gap })
    }

    @Test fun aBookedTripMissesNothingUntilTheWeatherTurns() {
        assertTrue(Gaps.of(F.boston, null, F.now, zone).isEmpty())
        assertEquals(listOf(Gap.BACK), Gaps.of(F.boston.copy(legs = listOf(F.train)), null, F.now, zone).map { it.gap })
        assertEquals(listOf(Gap.STAY), Gaps.of(F.boston.copy(stays = emptyList()), null, F.now, zone).map { it.gap })
        val snow = DestWeather(F.now, 2.0, 3, listOf(DestDay("2027-03-05", 1.0, -3.0, 73, 80)))
        assertEquals("Snow on Fri, Mar 5 in Boston", Gaps.of(F.boston, snow, F.now, zone).single().text)
        assertTrue(Gaps.of(F.boston, DestWeather(F.now, days = listOf(DestDay("2027-03-12", code = 73))), F.now, zone).isEmpty())
    }

    @Test fun anEarlyStartWithNoAlarmIsMissing() {
        val early = F.boston.copy(legs = listOf(F.train.copy(departAt = F.at(2027, 3, 4, 6, 30)), F.back))
        val eve = F.at(2027, 3, 3, 21)
        assertEquals("No alarm before you leave at 5:45 AM", Gaps.of(early, null, eve, zone, AlarmWatch { null }).single().text)
        assertTrue(Gaps.of(early, null, eve, zone, AlarmWatch { F.at(2027, 3, 4, 5) }).isEmpty())
        assertTrue(Gaps.of(early, null, eve, zone, null).isEmpty())
    }

    @Test fun countdownCardShowsOnlyWhatIsMissingAndOffersIris() {
        val t = F.boston.copy(stays = emptyList())
        val (card, home) = ArgoCards.countdown(t, null, F.at(2027, 2, 11, 9), zone)
        assertEquals("Boston in 3 weeks", card!!.title)
        assertEquals(listOf("No place to stay yet"), card.facts.map { it.value })
        assertTrue(card.body!!.endsWith("4 nights. Ask Iris to look for places to stay?"))
        assertEquals("ask:t-bos:STAY", card.actions.first().payload)
        assertTrue(home.fit > 0)
        // Once asked, the card says Iris is looking and stops asking.
        val (asked, _) = ArgoCards.countdown(t.copy(askedIris = listOf("STAY")), null, F.at(2027, 2, 11, 9), zone)
        assertEquals("Iris is looking", asked!!.facts.single().status)
        assertTrue(asked.actions.none { it.payload?.startsWith("ask:") == true })
    }

    // ---- travel mode ----

    @Test fun phasesFollowTheLegs() {
        val t = F.boston
        fun phase(ms: Long) = Travel.moment(t, ms, zone).phase
        assertEquals(Phase.BEFORE, phase(F.now))
        assertEquals(Phase.BEFORE, phase(F.at(2027, 3, 4, 4)))       // before the three hours ahead of leave-by
        assertEquals(Phase.TRAVEL, phase(F.at(2027, 3, 4, 5)))
        assertEquals(Phase.TRAVEL, phase(F.at(2027, 3, 4, 17)))       // half an hour after arrival
        assertEquals(Phase.AWAY, phase(F.at(2027, 3, 4, 18)))
        assertEquals(Phase.AWAY, phase(F.at(2027, 3, 6, 12)))
        assertEquals(Phase.TRAVEL, phase(F.at(2027, 3, 8, 14)))       // the way back
        assertEquals(Phase.AFTER, phase(F.at(2027, 3, 9, 9)))
        assertEquals(Phase.TRAVEL, Travel.current(listOf(F.boston), F.at(2027, 3, 4, 7), zone)!!.second.phase)
    }

    @Test fun liveUpdateTextSequence() {
        val t = F.boston.copy(legs = listOf(F.train.copy(platform = ""), F.back))
        val l = t.legs.first()
        fun live(ms: Long, leg: Leg = l) = LiveWords.of(t, leg, ms, zone)
        live(F.at(2027, 3, 4, 7)).let { assertEquals("Leave in 25 min", it.title); assertEquals(Stage.LEAVE, it.stage); assertEquals(F.at(2027, 3, 4, 7, 25), it.countdownTo); assertFalse(it.sounds) }
        assertEquals("Leave at 7:25 AM", live(F.at(2027, 3, 4, 5)).title)
        live(F.at(2027, 3, 4, 7, 40)).let { assertEquals("Departs 8:10 AM", it.title); assertTrue(it.sounds) }
        val gate = live(F.at(2027, 3, 4, 7, 50), l.copy(platform = "Platform 5"))
        assertEquals("Departs 8:10 AM · Platform 5", gate.title)
        assertFalse(gate.key == live(F.at(2027, 3, 4, 7, 40)).key)            // a platform is a new key moment
        live(F.at(2027, 3, 4, 12)).let { assertEquals("Arrive 4:45 PM South Station", it.title); assertEquals(Stage.ON_WAY, it.stage); assertNotNull(it.progress) }
        live(F.at(2027, 3, 4, 16, 50)).let { assertEquals("Arrived South Station", it.title); assertEquals("Example Hotel · check-in 3:00 PM", it.text) }
        assertEquals("Home", live(F.at(2027, 3, 8, 23, 10), F.back).title)
    }

    @Test fun arrivalIsShownInTheDestinationZone() {
        val la = F.boston.copy(place = "Los Angeles", zone = "America/Los_Angeles")
        val leg = Leg("x", LegMode.FLIGHT, "BUF", "LAX", F.at(2027, 3, 4, 8), F.at(2027, 3, 4, 11, 30, ZoneId.of("America/Los_Angeles")))
        assertEquals("Arrive 11:30 AM LAX", LiveWords.of(la, leg, F.at(2027, 3, 4, 9), zone).title)
    }

    @Test fun awayDaysAreWorkingDaysTheTripCovers() {
        val trips = listOf(F.boston)
        assertFalse(Travel.awayOn(trips, LocalDate.of(2027, 3, 3), zone))
        assertTrue(Travel.awayOn(trips, LocalDate.of(2027, 3, 4), zone))       // leaves 8:10
        assertTrue(Travel.awayOn(trips, LocalDate.of(2027, 3, 5), zone))
        assertTrue(Travel.awayOn(trips, LocalDate.of(2027, 3, 8), zone))       // back 3:10 PM: 7 h away
        val evening = F.boston.copy(legs = listOf(F.train.copy(departAt = F.at(2027, 3, 4, 17), arriveAt = F.at(2027, 3, 4, 23))))
        assertFalse(Travel.awayOn(listOf(evening), LocalDate.of(2027, 3, 4), zone))
        assertFalse(Travel.awayOn(listOf(Trips.note(emptyList(), "Boston", "March", null, F.now, zone).second), LocalDate.of(2027, 3, 4), zone))
    }

    @Test fun travelDayAndTripModeCards() {
        val (card, home) = ArgoCards.travel(F.boston, F.train, F.at(2027, 3, 4, 7), zone)
        assertEquals("Leave in 25 min", card.title)
        assertEquals("Travel day", home.kicker)
        assertNotNull(home.widget)
        assertTrue(card.actions.any { it.payload == "vault" })
        val (away, h2) = ArgoCards.away(F.boston, null, F.at(2027, 3, 5, 17), zone)
        assertEquals("Boston, day 2 of 5", away.title)
        assertTrue(away.facts.any { it.value == "Dinner with Jordan, North End" && it.status == "Leave the hotel by 6:30 PM" })
        assertTrue(away.facts.any { it.label == "Stay" && it.value.startsWith("Example Hotel") })
        assertEquals("maps:120 W 2nd St, Boston, MA 02116", away.actions.first().payload)
        assertEquals("Trip mode", h2.kicker)
    }

    // ---- widget safety ----

    @Test fun tripWidgetsAreSafeAndCarryNoConfirmation() {
        val views = listOf(
            ArgoCards.travel(F.boston, F.train.copy(platform = "Platform 5"), F.at(2027, 3, 4, 7, 50), zone).second.widget!!,
            ArgoCards.away(F.boston, null, F.at(2027, 3, 5, 17), zone).second.widget!!,
        )
        for (v in views) {
            assertTrue(WidgetSafety.texts(v).joinToString(), WidgetSafety.isSafe(v))
            assertEquals(v, WidgetSafety.seal(v))
            assertTrue(WidgetSafety.texts(v).none { it.contains("calypso-item") || it.contains("QX7Z2B") })
        }
        val travel = WidgetSafety.seal(views[0])
        assertTrue(travel.groups.flatMap { it.rows }.any { it.text == ArgoCards.CONFIRMATION && it.dest == "VAULT" })
        assertTrue(travel.groups.flatMap { it.rows }.any { it.meta == "120 W 2nd St, Boston, MA 02116" })
        // Any other Calypso line is still dropped.
        assertNull(WidgetSafety.clean("Confirmation QX7Z2B in Calypso"))
    }

    // ---- Moneta ----

    @Test fun monetaTagsChargesInsideTheTripOnly() {
        val trips = listOf(F.boston)
        assertEquals("Boston", ArgoMoney.tripAt(trips, F.at(2027, 3, 5, 20), zone))
        assertEquals("Boston", ArgoMoney.tripAt(trips, F.at(2027, 3, 8, 22), zone))
        assertNull(ArgoMoney.tripAt(trips, F.at(2027, 3, 3, 20), zone))
        assertNull(ArgoMoney.tripAt(trips, F.at(2027, 3, 9, 9), zone))
        assertNull(ArgoMoney.tripAt(listOf(F.boston.copy(status = TripStatus.DISMISSED)), F.at(2027, 3, 5, 20), zone))
    }

    // ---- investigations: hints, Iris, one question ----

    private val bosHint = Inquiry("q1", "note:boston:2027-03-04", "notification", "Boston", "2027-03-04", "2027-03-04", hint = "A notification mentioned Boston and Thu, Mar 4", createdAt = F.now, updatedAt = F.now)
    private val today = LocalDate.of(2027, 2, 9)

    @Test fun aBookingChargeStartsAnInvestigationNotATrip() {
        val charges = listOf(F.at(2027, 2, 8, 12) to "AMTRAK .COM", F.at(2027, 2, 8, 13) to "Whole Foods", F.at(2026, 10, 1, 9) to "JetBlue")
        val hints = Hints.fromCharges(charges, F.now, zone)
        assertEquals(1, hints.size)
        assertEquals("A charge at AMTRAK .COM on Mon, Feb 8 looks like a travel booking", hints.single().hint)
        val fresh = Hints.fresh(hints, ArgoData(), zone)
        assertEquals(InqStatus.OPEN, fresh.single().status)
        assertTrue(Hints.fresh(hints, ArgoData(inquiries = fresh), zone).isEmpty())          // the same hint never twice
        assertTrue(Hints.fresh(hints, ArgoData(trips = listOf(F.boston)), zone).isEmpty())   // an open trip explains it
        // The question to Iris names the merchant and the day, never an amount.
        val q = Inquiries.question(fresh.single())
        assertNull(dev.phonerecall.mind.AskGuard.refuse(q))
        assertFalse(q.contains("$"))
    }

    @Test fun aNotificationKeepsOnlyTheCityAndTheDate() {
        val h = Hints.fromNotification("JetBlue: Your flight to Boston on Mar 4 is confirmed. Code QX7Z2B", F.now, zone)!!
        assertEquals("Boston", h.place)
        assertEquals("2027-03-04", h.start)
        assertFalse(h.hint.contains("QX7Z2B") || h.hint.contains("JetBlue"))
        assertNull(Hints.fromNotification("Dinner tonight?", F.now, zone))
        assertNull(Hints.fromNotification("Snow in Buffalo on Mar 4", F.now, zone))
    }

    @Test fun anEventInAnotherCityAloneIsAHint() {
        val items = CalendarCapture.items(listOf(F.ev(9, "Hold", F.at(2027, 3, 5, 10), F.at(2027, 3, 5, 11), cat = EventCategory.PERSONAL, place = "Back Bay")), zone)
        assertTrue(F.build(items).isEmpty())
        val h = Hints.fromCalendar(items, emptyList(), F.now, zone).single()
        assertEquals("Boston", h.place)
        assertEquals("2027-03-05", h.start)
        // One open investigation per place.
        assertEquals(1, Hints.fresh(listOf(h, bosHint), ArgoData(), zone).size)
    }

    @Test fun irisConfirmingRecordsTheTrip() {
        val reply = dev.phonerecall.mind.AskReply("a1", mapOf("trip" to listOf("yes"), "place" to listOf("Boston"), "dates" to listOf("2027-03-04 to 2027-03-08"), "bookings" to listOf("Amtrak Lake Shore 448, Mar 4")))
        val f = Inquiries.conclude(bosHint.copy(askId = "a1"), reply, emptyList(), today)
        assertEquals(dev.phonerecall.mind.Confidence.HIGH, f.confidence)
        assertEquals(LocalDate.of(2027, 3, 8), f.end)
        val d = Inquiries.apply(ArgoData(inquiries = listOf(bosHint)), bosHint, f, F.now, zone)
        val t = d.trips.single()
        assertEquals("Boston", t.place)
        assertEquals(TripStatus.PLANNED, t.status)
        assertTrue("athena" in t.sources)
        assertEquals(InqStatus.DONE, d.inquiries.single().status)
        assertEquals(t.id, d.inquiries.single().tripId)
        assertNull(ArgoCards.home(d, F.now, zone)!!.offer)                                   // no question card
    }

    @Test fun noAnswerInThirtyMinutesBecomesOneQuestion() {
        val asked = bosHint.copy(status = InqStatus.ASKED, askId = "a1")
        val f = Inquiries.conclude(asked, null, emptyList(), today)
        assertEquals(dev.phonerecall.mind.Confidence.LOW, f.confidence)
        assertTrue(f.evidence.contains("Iris didn't answer in 30 minutes"))
        val d = Inquiries.apply(ArgoData(inquiries = listOf(asked)), asked, f, F.now, zone)
        assertEquals(InqStatus.QUESTION, d.inquiries.single().status)
        assertTrue(d.trips.isEmpty())
        val card = ArgoCards.home(d, F.now, zone)!!.offer!!
        assertEquals("Looks like Boston early March. Right?", card.title)
        assertEquals(listOf("q:q1:yes", "q:q1:no"), card.actions.map { it.payload })
        // Yes records it; the question is closed.
        val yes = Inquiries.answer(d, "q1", true, F.now, zone)
        assertEquals("Boston", yes.trips.single().place)
        assertEquals("yes", yes.inquiries.single().answer)
        assertNull(ArgoCards.home(yes, F.now, zone)?.offer)
    }

    @Test fun memoryAloneIsMediumAndStillOnlyAsks() {
        val f = Inquiries.conclude(bosHint, null, listOf("Parnassus: thinking of Boston with Jordan in March", "Groceries"), today)
        assertEquals(dev.phonerecall.mind.Confidence.MEDIUM, f.confidence)
        assertEquals(Inquiries.Decision.ASK_HIM, Inquiries.decide(bosHint, f, ArgoData(inquiries = listOf(bosHint)), F.now))
    }

    @Test fun neverMoreThanOneQuestionPerPlaceAndNoNagging() {
        val open = bosHint.copy(id = "q0", key = "k0", status = InqStatus.QUESTION)
        val f = Inquiries.conclude(bosHint, null, emptyList(), today)
        assertEquals(Inquiries.Decision.DROP, Inquiries.decide(bosHint, f, ArgoData(inquiries = listOf(open, bosHint)), F.now))
        val answeredNo = open.copy(status = InqStatus.DONE, answer = "no", updatedAt = F.now - 5 * 86_400_000L)
        assertEquals(Inquiries.Decision.DROP, Inquiries.decide(bosHint, f, ArgoData(inquiries = listOf(answeredNo, bosHint)), F.now))
        val longAgo = answeredNo.copy(updatedAt = F.now - 40 * 86_400_000L)
        assertEquals(Inquiries.Decision.ASK_HIM, Inquiries.decide(bosHint, f, ArgoData(inquiries = listOf(longAgo, bosHint)), F.now))
        // Iris saying no with nothing else: dropped quietly.
        val no = Inquiries.conclude(bosHint, dev.phonerecall.mind.AskReply("a", mapOf("trip" to listOf("no"))), emptyList(), today)
        assertEquals(Inquiries.Decision.DROP, Inquiries.decide(bosHint, no, ArgoData(inquiries = listOf(bosHint)), F.now))
    }

    // ---- the day after ----

    @Test fun theFirstThingBackAndALightMorning() {
        val back = F.back.arriveAt   // Mon Mar 8, 11:05 PM
        val events = listOf(
            F.ev(20, "Standup", F.at(2027, 3, 9, 9), F.at(2027, 3, 9, 9, 15), cat = EventCategory.WORK),
            F.ev(21, "1:1 with Taylor", F.at(2027, 3, 9, 14), F.at(2027, 3, 9, 14, 30), cat = EventCategory.WORK),
            F.ev(22, "Sam's birthday", F.at(2027, 3, 9, 0), F.at(2027, 3, 10, 0), allDay = true, cat = EventCategory.BIRTHDAY),
        )
        val first = DayAfter.first(events, back)!!
        assertEquals("First thing back: 9:00 AM Standup, Tue", DayAfter.words(first, zone))
        assertEquals(F.at(2027, 3, 9, 8) to F.at(2027, 3, 9, 9), DayAfter.recovery(back, first, zone))
        assertEquals(mapOf("title" to "Recovery morning (light)", "start" to "2027-03-09T08:00", "end" to "2027-03-09T09:00"), DayAfter.eventArgs(DayAfter.recovery(back, first, zone)!!, zone))
        assertNull(DayAfter.recovery(back, first.copy(begin = F.at(2027, 3, 9, 8, 30)), zone))   // no room
        assertNull(DayAfter.recovery(F.at(2027, 3, 8, 10), null, zone))                       // back in the morning
        assertNull(DayAfter.returnAt(F.boston.copy(legs = listOf(F.train))))
        val card = ArgoCards.travel(F.boston.copy(firstBack = DayAfter.words(first, zone)), F.back, F.at(2027, 3, 8, 14), zone).first
        assertTrue(card.facts.any { it.label == "Next" && it.value == "9:00 AM Standup, Tue" })
    }

    // ---- committed vs discretionary ----

    @Test fun committedAndDiscretionary() {
        val t = F.boston.copy(legs = listOf(F.train.copy(cents = 6_400, paid = true), F.back.copy(cents = 7_100, paid = false)), stays = listOf(F.hotel.copy(cents = 84_000, paid = true)), budgetCents = 150_000)
        val m = TripLedger.of(t, emptyList(), zone)
        assertEquals(2, m.paid); assertEquals(1, m.due)
        assertEquals(97_500, m.committedCents); assertEquals(7_100, m.dueCents)
        assertEquals(52_500L, m.discretionaryCents)
        assertEquals("3 bookings (2 paid, 1 awaiting payment). Budget set", m.words())
        assertFalse(m.words().contains("$"))
        assertEquals("$975", TripLedger.dollars(m.committedCents))
        // Without Iris's prices, Moneta's booking charges before the trip count as paid.
        val fromMoneta = TripLedger.of(F.boston, listOf(Triple(F.at(2027, 2, 8, 12), "AMTRAK .COM", 13_500L), Triple(F.at(2027, 2, 8, 13), "Whole Foods", 4_000L), Triple(F.at(2026, 1, 1, 9), "Hilton", 20_000L)), zone)
        assertEquals(1, fromMoneta.paid); assertEquals(13_500, fromMoneta.committedCents); assertNull(fromMoneta.discretionaryCents)
        // Iris's price travels on trip.info.
        val priced = IrisInfo.parse("""{"id":"0123456789abcdef01234567","kind":"hotel","place":"Boston","name":"Example Hotel","check_in":"2027-03-04T15:00","price":840,"paid":false}""", zone) as TripItem.StayItem
        assertEquals(84_000L, priced.stay.cents); assertEquals(false, priced.stay.paid)
    }

    // ---- privacy ----

    @Test fun theBrainGetsCityNamesAndWordsOnly() {
        val words = TripWords.plans(listOf(F.boston.copy(lat = 42.3601, lng = -71.0589)), F.now, zone)
        assertTrue(words.contains("Boston, Thu, Mar 4 to Mon, Mar 8, 4 nights"))
        assertTrue(words.contains("train there, train back"))
        for (bad in listOf("Example Hotel", "Example St", "42.36", "South Station", "calypso", "Lake Shore 448", "North End")) assertFalse(bad, words.contains(bad))
        assertTrue(TripWords.recap(F.boston, zone).startsWith("Boston, Thu, Mar 4 to Mon, Mar 8, 4 nights. Train there and back"))
        assertEquals(listOf("Boston in 3 weeks, all set."), ArgoBrief.lines(ArgoData(trips = listOf(F.boston)), F.at(2027, 2, 11, 7), zone))
    }

    @Test fun hestiaAwayPlanTurnsOffOnlyLightsThatAreOn() {
        val on = dev.phonerecall.hestia.HomeDevice("l1", "Lamp", dev.phonerecall.hestia.DeviceKind.LIGHT, null, "s", traits = setOf(dev.phonerecall.hestia.Trait.ON_OFF), on = true)
        val steps = ArgoAway.steps(listOf(on))
        assertEquals(1, steps.size)
        assertEquals(mapOf("device_id" to "l1", "action" to "off"), steps.single().args)
        assertTrue(ArgoAway.steps(emptyList()).isEmpty())
    }

    @Test fun archivedTripsKeepTheirRecapAndLeaveTravelMode() {
        val done = F.boston.copy(status = TripStatus.ARCHIVED, recap = TripWords.recap(F.boston, zone))
        assertNull(Travel.current(listOf(done), F.at(2027, 3, 6, 12), zone))
        val home = ArgoCards.home(ArgoData(trips = listOf(done)), F.at(2027, 3, 9, 9), zone)!!
        assertEquals("Back from Boston", home.card!!.title)
        assertNull(home.widget)
        assertNull(ArgoCards.home(ArgoData(trips = listOf(done)), F.at(2027, 3, 11, 9), zone))
    }

    // ---- connections and the trips Argo missed ----

    private fun fl(id: String, from: String, to: String, d: Long, a: Long, ret: Boolean = false) = Leg(id, LegMode.FLIGHT, from, to, d, a, "United", returning = ret, source = "iris")

    /** BUF → ORD → DEN → PHX in April with 50-minute layovers, home the same way two weeks later. */
    private val outLegs = listOf(
        "Chicago" to fl("iris:o1", "Buffalo (BUF)", "Chicago (ORD)", F.at(2027, 4, 9, 6), F.at(2027, 4, 9, 7, 10)),
        "Denver" to fl("iris:o2", "Chicago (ORD)", "Denver (DEN)", F.at(2027, 4, 9, 8), F.at(2027, 4, 9, 12)),
        "Phoenix" to fl("iris:o3", "Denver (DEN)", "Phoenix (PHX)", F.at(2027, 4, 9, 12, 50), F.at(2027, 4, 9, 15, 30)),
    )
    private val homeLegs = listOf(
        "Denver" to fl("iris:r1", "Phoenix (PHX)", "Denver (DEN)", F.at(2027, 4, 24, 9), F.at(2027, 4, 24, 11, 20), ret = true),
        "Chicago" to fl("iris:r2", "Denver (DEN)", "Chicago (ORD)", F.at(2027, 4, 24, 12, 10), F.at(2027, 4, 24, 17, 40), ret = true),
        "Phoenix" to fl("iris:r3", "Chicago (ORD)", "Buffalo (BUF)", F.at(2027, 4, 24, 18, 30), F.at(2027, 4, 24, 21, 15), ret = true),
    )

    @Test fun connectingFlightsAreOneTripToTheLastStop() {
        // Iris names each leg's own city: Chicago, Denver, Phoenix.
        val trips = F.build((outLegs + homeLegs).map { (p, l) -> TripItem.LegItem(p, l, "iris") })
        val t = trips.single()
        assertEquals("Phoenix", t.place)
        assertEquals(listOf("Chicago", "Denver"), t.layovers)
        assertEquals(listOf("Phoenix"), t.stops.map { it.place })
        assertEquals(6, t.legs.size)
        assertEquals("iris:o1", t.outbound?.id)
        assertEquals("iris:r1", t.back?.id)
        assertEquals("iris:r3", t.landing?.id)
        assertEquals(listOf("iris:r1", "iris:r2", "iris:r3"), t.legs.filter { it.returning }.sortedBy { it.departAt }.map { it.id })
        assertEquals(LocalDate.of(2027, 4, 9) to LocalDate.of(2027, 4, 24), t.days(zone))
        assertEquals(F.at(2027, 4, 24, 21, 15), t.window(zone)!!.second)
        assertEquals(F.at(2027, 4, 24, 21, 15), DayAfter.returnAt(t))
        // A notification about the Denver layover is the same trip, not a new question.
        assertTrue(Hints.fresh(listOfNotNull(Hints.fromNotification("United: ORD to DEN Apr 9", F.now, zone)), ArgoData(trips = trips), zone).isEmpty())
    }

    @Test fun connectingFlightsFromTheCalendarAreOneTrip() {
        val events = listOf(
            F.ev(1, "Flight BUF → ORD", F.at(2027, 4, 9, 6), F.at(2027, 4, 9, 7, 10)),
            F.ev(2, "Flight to Denver", F.at(2027, 4, 9, 8), F.at(2027, 4, 9, 12)),
            F.ev(3, "Flight DEN → PHX", F.at(2027, 4, 9, 12, 50), F.at(2027, 4, 9, 15, 30)),
            F.ev(4, "Flight to Denver", F.at(2027, 4, 24, 9), F.at(2027, 4, 24, 11, 20)),
            F.ev(5, "Flight to Chicago", F.at(2027, 4, 24, 12, 10), F.at(2027, 4, 24, 17, 40)),
            F.ev(6, "Flight to Buffalo", F.at(2027, 4, 24, 18, 30), F.at(2027, 4, 24, 21, 15)),
        )
        val trips = F.build(CalendarCapture.items(events, zone))
        val t = trips.single()
        assertEquals("Phoenix", t.place)
        assertEquals(6, t.legs.size)
        assertEquals(3, t.legs.count { it.returning })
        assertEquals(LocalDate.of(2027, 4, 9) to LocalDate.of(2027, 4, 24), t.days(zone))
        // Reading the calendar again changes nothing.
        assertEquals(trips, F.build(CalendarCapture.items(events, zone), trips))
    }

    @Test fun tripsAlreadySplitAreMendedOnTheNextCapture() {
        // The old split trips held Iris's legs: the migration makes them evidence again and rebuilds one trip.
        val split = outLegs.map { (p, l) -> Trip("t-$p", p, legs = listOf(l), status = TripStatus.PLANNED, lat = 41.9, seen = listOf(l.id), sources = listOf("iris")) }
        val t = Migrate.run(ArgoData(trips = split), F.now, zone).trips.single()
        assertEquals("Phoenix", t.place)
        assertEquals("t-Phoenix", t.id)
        assertEquals(3, t.legs.size)
        assertEquals(listOf("Chicago", "Denver"), t.layovers)
    }

    @Test fun aRoundTripIsOneTripAndTwoTripsWeeksApartStayTwo() {
        val bos = listOf(
            TripItem.LegItem("Boston", fl("iris:n1", "Buffalo (BUF)", "Boston (BOS)", F.at(2027, 3, 4, 8, 10), F.at(2027, 3, 4, 9, 35)), "iris"),
            TripItem.LegItem("Boston", fl("iris:n2", "Boston (BOS)", "Buffalo (BUF)", F.at(2027, 3, 8, 15), F.at(2027, 3, 8, 16, 30), ret = true), "iris"),
        )
        val one = F.build(bos).single()
        assertEquals("Boston", one.place)
        assertEquals("iris:n2", one.back?.id)
        assertEquals(1, one.legs.count { it.returning })
        val chicago = listOf(
            TripItem.LegItem("Chicago", fl("iris:c1", "Buffalo (BUF)", "Chicago (ORD)", F.at(2027, 4, 1, 7), F.at(2027, 4, 1, 8)), "iris"),
            TripItem.LegItem("Chicago", fl("iris:c2", "Chicago (ORD)", "Buffalo (BUF)", F.at(2027, 4, 4, 18), F.at(2027, 4, 4, 21), ret = true), "iris"),
        )
        val two = F.build(bos + chicago)
        assertEquals(listOf("Boston", "Chicago"), two.map { it.place })
        assertEquals(listOf(2, 2), two.map { it.legs.size })
        // A day trip in one journey: everything after the longest stop is the way back.
        val day = F.build(CalendarCapture.items(listOf(
            F.ev(1, "Flight to Boston", F.at(2027, 3, 12, 6), F.at(2027, 3, 12, 7, 30)),
            F.ev(2, "Flight to Buffalo", F.at(2027, 3, 12, 19), F.at(2027, 3, 12, 20, 30)),
        ), zone)).single()
        assertEquals("Boston", day.place)
        assertTrue(day.layovers.isEmpty())
        assertEquals(F.at(2027, 3, 12, 19), day.back?.departAt)
    }

    @Test fun aStopOverADayIsAStopOnTheSameTrip() {
        // Three days in Chicago, then on to Las Vegas and home: one trip to Las Vegas, with Chicago as a stop.
        val iris = listOf(
            TripItem.LegItem("Chicago", fl("iris:s1", "Buffalo (BUF)", "Chicago (ORD)", F.at(2027, 2, 21, 7), F.at(2027, 2, 21, 8)), "iris"),
            TripItem.LegItem("Las Vegas", fl("iris:s2", "Chicago (ORD)", "Las Vegas (LAS)", F.at(2027, 2, 24, 9), F.at(2027, 2, 24, 12)), "iris"),
            TripItem.LegItem("Las Vegas", fl("iris:s3", "Las Vegas (LAS)", "Buffalo (BUF)", F.at(2027, 3, 1, 9), F.at(2027, 3, 1, 17), ret = true), "iris"),
        )
        val t = F.build(iris).single()
        assertEquals("Las Vegas", t.place)
        assertEquals(listOf("Chicago", "Las Vegas"), t.stops.map { it.place })
        assertEquals(LocalDate.of(2027, 2, 21) to LocalDate.of(2027, 3, 1), t.days(zone))
        // A one-way with no way back yet stays one open trip, to where it ends.
        val cal = CalendarCapture.items(listOf(
            F.ev(1, "Flight to Chicago", F.at(2027, 2, 21, 7), F.at(2027, 2, 21, 8)),
            F.ev(2, "Flight to Las Vegas", F.at(2027, 2, 24, 9), F.at(2027, 2, 24, 12)),
        ), zone)
        val open = F.build(cal).single()
        assertEquals("Las Vegas", open.place)
        assertNull(open.back)
    }

    @Test fun citiesFindTheDestinationAndNewYorksNames() {
        assertEquals("Phoenix", Cities.find("Flight BUF → PHX"))
        assertEquals("Nashville", Cities.find("Flight from Buffalo to Nashville"))
        assertEquals("Nashville", Cities.find("AA 512 BUF-BNA"))
        assertEquals("Denver", Cities.find("Flight from Chicago to Denver"))
        assertEquals("Boulder", Cities.find("Stay at Example Hotel Boulder"))
        assertEquals("New York", Cities.find("Trip to NY"))
        assertEquals("New York", Cities.find("NY trip with Jordan"))
        assertEquals("New York", Cities.find("Amtrak to Moynihan Train Hall"))
        assertEquals(Cities.HOME, Cities.find("123 Main St, Buffalo, NY 14202"))
        assertEquals("Rochester", Cities.find("Lunch in Rochester NY"))
        assertEquals(Cities.HOME, Cities.find("Train to Buffalo"))
        assertEquals("Spokane", Cities.city("Spokane (GEG)"))
        assertNull(Cities.find("Seafood night"))
    }

    @Test fun nonFlightTripsFromTheCalendarAreKept() {
        // An all-day block over days, an overnight hotel not filed as travel, a carrier without the word "flight".
        val block = F.build(CalendarCapture.items(listOf(F.ev(1, "Boston", F.utc(2027, 3, 4), F.utc(2027, 3, 9), allDay = true, cat = EventCategory.PERSONAL)), zone))
        assertEquals(LocalDate.of(2027, 3, 4) to LocalDate.of(2027, 3, 8), block.single().days(zone))
        val hotel = F.build(CalendarCapture.items(listOf(F.ev(2, "Example Hotel stay", F.at(2027, 3, 4, 15), F.at(2027, 3, 8, 11), cat = EventCategory.PERSONAL, place = "Example Hotel, Back Bay")), zone))
        assertEquals("Boston", hotel.single().place)
        assertEquals(1, hotel.single().stays.size)
        val jet = CalendarCapture.items(listOf(F.ev(3, "JetBlue to Boston", F.at(2027, 3, 4, 8), F.at(2027, 3, 4, 9, 30), cat = EventCategory.PERSONAL)), zone).single() as TripItem.LegItem
        assertEquals(LegMode.FLIGHT, jet.leg.mode)
        assertEquals("Boston", jet.place)
        // A late dinner in another city is still only a hint.
        assertTrue(CalendarCapture.items(listOf(F.ev(4, "Dinner in Back Bay", F.at(2027, 3, 5, 19), F.at(2027, 3, 6, 1), cat = EventCategory.PERSONAL)), zone).single() is TripItem.PlanItem)
    }

    @Test fun irisIsAskedWeeklyForEveryBookedTrip() {
        assertNull(dev.phonerecall.mind.AskGuard.refuse(Sweep.question()))
        assertTrue(Sweep.due(ArgoData(), F.now))
        assertFalse(Sweep.due(ArgoData(sweptAt = F.now - 86_400_000L), F.now))
        val dismissed = Trip("t-pit", "Pittsburgh", startDate = "2027-03-23", endDate = "2027-03-25", status = TripStatus.DISMISSED)
        val reply = dev.phonerecall.mind.AskReply("x", mapOf("trips" to listOf("Boston, 2027-03-04 to 2027-03-08", "Pittsburgh, 2027-03-23 to 2027-03-25", "none")))
        val d = Sweep.apply(ArgoData(trips = listOf(dismissed)), reply, F.now, zone)
        assertEquals(2, d.trips.size)
        val bos = d.trips.first { it.place == "Boston" }
        assertEquals(TripStatus.PLANNED, bos.status)
        assertEquals(listOf("iris"), bos.sources)
        assertEquals(LocalDate.of(2027, 3, 4) to LocalDate.of(2027, 3, 8), bos.days(zone))
        // Asked again, the same trip is updated, not doubled.
        assertEquals(2, Sweep.apply(d, reply, F.now, zone).trips.size)
        assertEquals(d, Sweep.apply(d, null, F.now, zone))
    }
}
