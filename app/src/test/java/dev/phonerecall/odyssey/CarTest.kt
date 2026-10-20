package dev.phonerecall.odyssey

import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardKind
import dev.phonerecall.moneta.Category
import dev.phonerecall.moneta.Txn
import dev.phonerecall.moneta.TxnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.TimeZone

/** The fuel stop, Iris's answers, the odometer photo, the lease pace, and what never leaves his screens. */
class CarTest {
    @Before fun zone() = TimeZone.setDefault(TimeZone.getTimeZone(Fx.zone))

    private val day = LocalDate.of(2026, 11, 14)
    private val stop = Fx.at(day, 10, 5)
    private val json = dev.phonerecall.mind.stateJson

    // ---- the fuel trigger ----

    @Test fun fuelPaymentsAreRecognisedAndOthersAreNot() {
        assertTrue(FuelStop.isFuelText("Example Bank: You made a $42.10 transaction with COSTCO GAS #123"))
        assertTrue(FuelStop.isFuelText("Purchase of $38.00 at SUNOCO 0412"))
        assertTrue(FuelStop.isFuelText("You spent $51.20 at Speedway"))
        assertFalse("groceries at Costco", FuelStop.isFuelText("You spent $82.14 at COSTCO WHSE #1234"))
        assertFalse("no amount", FuelStop.isFuelText("Shell Rewards: new offers this week"))
    }

    @Test fun oneFillUpPerStopWithItsAsk() {
        val car = FuelStop.open(CarData(), stop, "Costco Gas", "ask-1")!!
        assertEquals(1, car.fillups.size)
        assertEquals("fuel:${FuelStop.id(stop)}", car.asks["ask-1"])
        assertNull("the same stay twice", FuelStop.open(car, stop, "Costco Gas", "ask-2"))
    }

    @Test fun theAskTellsIrisWhereToLook() {
        val q = IrisEnvelope.fuelQuestion("Costco Gas", stop, Fx.zone)
        assertTrue(q, q.startsWith("Search the owner's email (gas receipts, card alerts) and owner.db (fillups, transactions) for the fill-up at Costco Gas around Sat Nov 14, 10:05 AM"))
        val env = json.readTree(IrisEnvelope.request("ask-1", "fuel", q))
        assertEquals(listOf("type", "id", "from", "authority", "topic", "question", "want_format"), env.fieldNames().asSequence().toList())
        assertEquals("ask.request", env["type"].asText()); assertEquals("athena", env["from"].asText()); assertEquals("read", env["authority"].asText())
        assertEquals("json", env["want_format"].asText())
        val lease = IrisEnvelope.LEASE_QUESTION
        for (w in listOf("Search the owner's email", "lease contract", "DMV", "the lessor's lease emails", "connected-car app", "owner.db", "with its source")) assertTrue(w, lease.contains(w))
    }

    // ---- the card, before and after Iris ----

    @Test fun theCardShowsAtOnceAndFillsInWhenIrisRepliesLate() {
        var d = Fx.on().copy(car = FuelStop.open(CarData(), stop, "Costco Gas", "ask-1")!!)
        val early = OdysseyCards.fuel(d, stop + 60_000)!!
        assertEquals("Fill-up at Costco Gas", early.title)
        assertEquals("Iris is finding the receipt. Odometer?", early.body)
        assertEquals(listOf(ActionRole.FUEL_ODOMETER, ActionRole.FUEL_SKIP), early.actions.map { it.role })
        // Twenty minutes later Iris answers (answer as a JSON string, the card as "Example Bank 1234").
        val reply = """{"type":"ask.reply","id":"ask-1","answer":"{\"amount\":42.1,\"gallons\":11.2,\"price_per_gal\":3.759,\"card\":\"Example Bank\",\"last4\":\"1234\"}"}"""
        val (id, answer) = IrisEnvelope.reply(reply)!!
        assertEquals("ask-1", id)
        d = d.copy(car = d.car.copy(asks = d.car.asks - id, fillups = d.car.fillups.map { IrisEnvelope.fuel(it, answer) }))
        assertEquals("$42.10 · 11.2 gal · Example Bank ••1234. Odometer?", OdysseyCards.fuel(d, stop + 20 * 60_000)!!.body)
        assertNull("gone after three days", OdysseyCards.fuel(d, stop + 4 * Fx.DAY))
    }

    @Test fun whatIrisCantFindStaysAndCardNumbersAreCut() {
        val f = FillUp("f1", stop, "Speedway", cents = 3800)
        val a = json.readTree("""{"amount":null,"gallons":"10.4","card":"Visa 4111111111111111","last4":null}""")
        val out = IrisEnvelope.fuel(f, a)
        assertEquals(3800L, out.cents)
        assertEquals(10.4, out.gallons!!, 0.001)
        assertEquals("Visa", out.card)
        assertNull("not a reply", IrisEnvelope.reply("""{"type":"ask.request","id":"x"}"""))
        assertNull(IrisEnvelope.reply("Sure, looking now"))
    }

    @Test fun monetasBankAlertFillsTheAmountAndIsNotCountedTwice() {
        val alert = Txn(7, stop + 4 * 60_000, 4210, TxnKind.SPEND, "Costco Gas", Category.FUEL, bank = "Example Bank", last4 = "1234")
        val other = Txn(8, stop - 5 * Fx.H, 4210, TxnKind.SPEND, "Tops", Category.GROCERIES)
        val f = FuelStop.fromMoneta(FillUp("f1", stop, "Costco Gas"), listOf(other, alert))
        assertEquals(4210L, f.cents)
        assertEquals("Example Bank ••1234", f.card)
        assertEquals(7L, FuelStop.match(f, listOf(other, alert))!!.id)
    }

    @Test fun theWriteToIrisCarriesTheRowAndNoCardNumber() {
        val f = FillUp("f1", stop, "Costco Gas", 4210, 11.2, 3.759, "Example Bank ••1234", 12_480, "logged")
        val w = json.readTree(IrisEnvelope.fillupWrite("write-fuel-f1", f, Fx.zone))
        assertEquals("write.request", w["type"].asText()); assertEquals("low", w["authority"].asText()); assertEquals("fillups", w["table"].asText())
        assertEquals(42.1, w["row"]["amount"].asDouble(), 0.001)
        assertEquals("1234", w["row"]["last4"].asText())
        assertEquals(12_480, w["row"]["odometer"].asInt())
    }

    // ---- the odometer photo ----

    @Test fun odometerFromDashboardText() {
        assertEquals(12_480, Odometer.parse("ODO 12480 mi\nTRIP A 231.4 mi\n72°F"))
        assertEquals(12_480, Odometer.parse("TRIP A\n231.4\nODO\n12,480\nmi"))
        assertEquals(31_052, Odometer.parse("31052 mi\nRange 312 mi\n10:42"))
        assertEquals("the plain one when nothing says mi", 8_123, Odometer.parse("D\n8123\n10:42 AM"))
        assertNull("only a time and a trip meter", Odometer.parse("10:42\nTRIP B 45.2"))
        assertNull("a range is not the odometer", Odometer.parse("Range 2450 mi"))
        // Two bare numbers and no "mi": not sure, so ask him to type it.
        assertNull(Odometer.parse("1234\n5678"))
        // Below the last reading, or far above it, is not believed.
        assertNull(Odometer.parse("ODO 9800 mi", last = 12_000))
        assertEquals(12_480, Odometer.parse("ODO 12480 mi\n99999", last = 12_000))
    }

    // ---- the lease ----

    private val lease = Lease(start = "2026-03-14", termMonths = 36, startOdometer = 12, overageCents = 15)

    @Test fun paceFromReadingsPlusGpsMiles() {
        val today = LocalDate.of(2026, 10, 20)
        val readings = listOf(Reading(Fx.at(LocalDate.of(2026, 10, 1), 9), 6_500))
        // 600 GPS miles since that reading.
        val drives = listOf(DriveReport("d1", Fx.at(LocalDate.of(2026, 10, 5), 9), Fx.at(LocalDate.of(2026, 10, 5), 10), (600 * 1609.344).toInt(), 0))
        val p = LeasePace.pace(lease, readings, drives, today, Fx.zone)!!
        assertEquals(8, p.month)
        assertEquals(7_088, p.miles)
        assertTrue(p.perYear.toString(), p.perYear in 11_500..12_000)
        assertTrue(p.ahead)
        assertEquals(p.over.toLong() * 15, p.overCents)
        val (title, body) = LeasePace.words(p)
        assertEquals("Month 8/36 · 7,088 mi", title)
        assertTrue(body, Regex("""Pace 11,\d00/yr → ~5,\d00 over \(~\$\d{3}\)""").matches(body))
        // Without an overage rate: miles only.
        assertFalse(LeasePace.words(LeasePace.pace(lease.copy(overageCents = null), readings, drives, today, Fx.zone)!!).second.contains("$"))
        assertNull("no reading yet", LeasePace.pace(lease, emptyList(), drives, today, Fx.zone))
        assertNull("no term yet", LeasePace.pace(lease.copy(termMonths = null), readings, drives, today, Fx.zone))
    }

    @Test fun withinTheAllowanceSaysSo() {
        val p = LeasePace.pace(lease, listOf(Reading(Fx.at(LocalDate.of(2026, 9, 14), 9), 5_000)), emptyList(), LocalDate.of(2026, 9, 14), Fx.zone)!!
        assertFalse(p.ahead)
        assertEquals(0, p.over)
        assertTrue(LeasePace.words(p).second.endsWith("within the 10,000 a year"))
    }

    @Test fun mpgAndFuelCostAMile() {
        fun f(id: String, d: Int, odo: Int, gal: Double, cents: Long) = FillUp(id, Fx.at(LocalDate.of(2026, 10, d), 9), "Costco Gas", cents, gal, null, null, odo, "logged")
        val fills = listOf(f("a", 1, 10_000, 10.0, 4000), f("b", 8, 10_300, 10.0, 4000), f("c", 15, 10_620, 10.0, 4000), FillUp("d", 0, "x", state = "open"))
        val (last, avg) = LeasePace.mpg(fills)!!
        assertEquals(32.0, last, 0.01)
        assertEquals(30.0, avg!!, 0.01)
        assertEquals(8000.0 / 620, LeasePace.centsPerMile(fills)!!, 0.01)
    }

    @Test fun irisFillsTheLeaseFirstAndHisOwnFieldsWin() {
        val a = json.readTree("""{"start_date":null,"term_months":36,"annual_miles":10000,"start_odometer":12,"overage_per_mile":0.15,"dealer":"Example Motors","lessor":"Example Finance",
            "car":"2026 Example Crossover","signed_date":"2026-03-12","delivery_dates":[{"date":"2026-03-14","source":"carmaker app welcome email"},{"date":"2026-03-15","source":"dealer paperwork"}],
            "sources":{"termMonths":"lease agreement"}}""")
        val l = IrisEnvelope.lease(Lease(termMonths = 39), a)
        assertEquals("his own term stays", 39, l.termMonths)
        assertFalse("termMonths" in l.fromIris)
        assertEquals("2026-03-14", l.delivered)
        assertEquals("the lease runs from delivery when the contract date is missing", "2026-03-14", l.start)
        assertEquals(15, l.overageCents)
        assertEquals("Example Finance / Example Motors", l.lessor)
        assertTrue(l.fromIris.containsAll(listOf("start", "allowance", "startOdometer", "overageCents", "car", "signed", "delivered")))
        assertEquals("carmaker app welcome email", l.sources["delivered"])
        assertTrue(l.missing().isEmpty())
        // A second answer can update what came from Iris.
        assertEquals(40, IrisEnvelope.lease(l.copy(fromIris = l.fromIris + "termMonths"), json.readTree("""{"term_months":40}""")).termMonths)
    }

    @Test fun oneCardForWhatIsStillMissingThenThePace() {
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 2).atTime(9, 0), Fx.zone)
        val ms = now.toInstant().toEpochMilli()
        val asked = Fx.on().copy(car = CarData(lease = Lease(start = "2026-03-14", fromIris = setOf("start")), leaseAsked = ms - 3_600_000, leaseAnswered = ms - 60_000))
        val setup = OdysseyCards.lease(asked, now)!!
        assertEquals("Iris found 1 detail in your email. Still missing: term, odometer at delivery, overage per mile.", setup.body)
        assertEquals(ActionRole.CAR_SETUP, setup.actions.first().role)
        assertNull("before Iris has looked", OdysseyCards.lease(asked.copy(car = asked.car.copy(leaseAnswered = 0)), now))
        assertNull("Not now", OdysseyCards.lease(asked.copy(car = asked.car.copy(dismissed = setOf("lease-setup"))), now))
        val full = Fx.on().copy(car = CarData(lease = lease, readings = listOf(Reading(ms - Fx.DAY, 7_000)), leaseAsked = 1, leaseAnswered = 1))
        assertEquals("Month 7/36 · 6,988 mi", OdysseyCards.lease(full, now)!!.title)
        assertNull("dismissed for the month", OdysseyCards.lease(full.copy(car = full.car.copy(dismissed = setOf("lease:2026-10"))), now))
        // Mid-month only when over pace: 6,988 mi in 202 days is over, so it still shows on the 20th.
        assertNotNull(OdysseyCards.lease(full, now.withDayOfMonth(20)))
    }

    @Test fun deliveryAndSigningAreMilestonesInChronos() {
        val d = Fx.on().copy(car = CarData(lease = Lease(car = "2026 Example Crossover", signed = "2026-03-12", delivered = "2026-03-14", sources = mapOf("delivered" to "carmaker app welcome email"))))
        val ms = CarMilestones.of(d)
        assertEquals(listOf("2026 Example Crossover delivered", "2026 Example Crossover lease signed"), ms.map { it.title })
        val from = Fx.at(LocalDate.of(2027, 3, 10), 0)
        val items = dev.phonerecall.data.Milestones.items(ms, from, from + 9 * Fx.DAY, Fx.zone)
        assertEquals(listOf("2026 Example Crossover lease signed, 1 year", "2026 Example Crossover delivered, 1 year"), items.sortedBy { it.begin }.map { it.title })
        assertTrue(items.all { it.allDay && it.calendarId == dev.phonerecall.data.Milestones.ID })
    }

    // ---- privacy ----

    @Test fun noAmountsReachTheBrainOrTheWidgets() {
        val ms = Fx.at(LocalDate.of(2026, 10, 20), 9)
        val fills = listOf(FillUp("a", ms - 20 * Fx.DAY, "Costco Gas", 4210, 11.2, 3.759, "Example Bank ••1234", 6_000, "logged"))
        val d = Fx.officeWeeks(2).copy(car = CarData(lease = lease, fillups = fills, readings = listOf(Reading(ms - Fx.DAY, 7_000))))
        val facts = OdysseyBrain.facts(d, ms, Fx.zone)
        val line = facts.single { it.startsWith("Car lease") }
        assertTrue(line, Regex("""Car lease: on pace ~[\d.]+k mi/yr vs 10k allowed \(month 8 of 36\)""").matches(line))
        for (f in facts) assertFalse(f, f.contains("$") || f.contains("••") || f.contains("1234"))
        for (k in listOf(CardKind.FUEL, CardKind.LEASE)) assertFalse("$k on a widget", k in dev.phonerecall.widgets.WidgetSafety.TODAY_KINDS)
        // The cards carry no Now Bar line, so nothing reaches the lock screen.
        val now = ZonedDateTime.of(LocalDate.of(2026, 10, 2).atTime(9, 0), Fx.zone)
        val open = d.copy(car = FuelStop.open(d.car, now.toInstant().toEpochMilli(), "Costco Gas", "ask-9")!!)
        assertNull(OdysseyCards.fuel(open, now.toInstant().toEpochMilli() + 60_000)!!.live)
        assertNull(OdysseyCards.lease(open, now)?.live)
    }

    @Test fun carDataSurvivesTheSealedStore() {
        val d = Fx.on().copy(car = CarData(lease = lease.copy(fromIris = setOf("start"), sources = mapOf("start" to "lease agreement")), fillups = listOf(FillUp("f1", stop, "Costco Gas", 4210, 11.2)),
            readings = listOf(Reading(stop, 12_480, "dash")), asks = mapOf("ask-1" to "fuel:f1"), dismissed = setOf("lease-setup")))
        val s = JceSealer()
        assertEquals(d.car, OdysseyStore.decode(s, OdysseyStore.encode(s, d))!!.car)
    }
}
