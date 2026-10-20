package dev.phonerecall.widgets

import dev.phonerecall.argo.ArgoData
import dev.phonerecall.argo.Leg
import dev.phonerecall.argo.LegMode
import dev.phonerecall.argo.Trip
import dev.phonerecall.argo.TripStatus
import dev.phonerecall.brief.BriefLine
import dev.phonerecall.brief.MorningBrief
import dev.phonerecall.iaso.DayStat
import dev.phonerecall.iaso.HcStatus
import dev.phonerecall.iaso.HealthReport
import dev.phonerecall.iaso.IasoState
import dev.phonerecall.iaso.SleepNight
import dev.phonerecall.iaso.WeightPoint
import dev.phonerecall.lemnos.ChatMsg
import dev.phonerecall.moneta.Category
import dev.phonerecall.moneta.Cycle
import dev.phonerecall.moneta.SubSource
import dev.phonerecall.moneta.Subscription
import dev.phonerecall.moneta.Txn
import dev.phonerecall.moneta.TxnKind
import dev.phonerecall.odyssey.DriveReport
import dev.phonerecall.odyssey.Kind
import dev.phonerecall.odyssey.KnownPlace
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.RoadCheck
import java.time.LocalDate

/** The newer sections on the same Monday morning as [WidgetFixtures], for their widget tests and shots. */
object SectionFixtures {
    private val F = WidgetFixtures
    private val ms = F.ms
    private const val MIN = 60_000L
    private const val DAY = 86_400_000L

    fun at(h: Int, m: Int = 0) = F.now.toLocalDate().atTime(h, m).atZone(F.zone).toInstant().toEpochMilli()

    val denver = Trip("t1", "Denver", startDate = "2026-12-03", endDate = "2026-12-07", status = TripStatus.PLANNED)
    val flight = Leg("l1", LegMode.FLIGHT, "Buffalo (BUF)", "Denver (DEN)", at(15, 42), at(18, 50), "XY", "123")
    val flyingToday = Trip("t2", "Denver", startDate = "2026-10-19", endDate = "2026-10-23", legs = listOf(flight), status = TripStatus.PLANNED)
    val argo = ArgoData(listOf(denver))

    val drive = DriveReport("d1", at(6, 30), at(6, 54), meters = 17_703, score = 92)
    val odyssey = OdysseyData(
        places = listOf(KnownPlace("home", "Home", kind = Kind.HOME), KnownPlace("work", "Office", kind = Kind.WORK)),
        trips = listOf(dev.phonerecall.odyssey.Trip("tr1", "home", "work", at(6, 30), at(6, 54), driveId = "d1")),
        drives = listOf(drive),
        checks = listOf(RoadCheck("commute:2026-10-19", ms - 10 * MIN, "Office", "Office", leaveBy = at(8, 50), minutes = 22, delayMinutes = 6, expiresAt = ms + 60 * MIN)),
    )

    fun spend(daysAgo: Double, cents: Long, merchant: String) =
        Txn(at = ms - (daysAgo * DAY).toLong(), cents = cents, kind = TxnKind.SPEND, merchant = merchant, category = Category.GROCERIES, bank = "Example Bank", last4 = "1234")

    val txns = listOf(
        spend(10.0, 10_000, "Fresh Mart"), spend(17.0, 10_000, "Corner Grocer"), spend(24.0, 10_000, "Costco"), spend(31.0, 10_000, "Aldi"),
        spend(2.0, 4_000, "Target"), spend(1.0, 1_500, "Uber"), spend(0.9, 1_500, "Uber"),
    )
    val netflix = Subscription("Netflix", 1_549, Cycle.MONTHLY, LocalDate.of(2026, 10, 24), SubSource.MANUAL)

    val health = IasoState(
        status = HcStatus.READY,
        report = HealthReport(
            days = listOf(DayStat(F.now.toLocalDate(), steps = 6_420)),
            sleep = listOf(SleepNight(F.now.toLocalDate(), 0, 1, asleepMin = 432)),
            weights = listOf(WeightPoint(ms - 10 * DAY, 72.8), WeightPoint(ms - 5 * DAY, 72.6), WeightPoint(ms - DAY, 72.4)),
            readAt = at(8, 25),
        ),
    )

    val brief = MorningBrief(
        date = "2026-10-19", postedAt = at(8), title = "Monday, Oct 19 · Office day", lede = "Two meetings, then Barça",
        today = listOf(BriefLine("Weather", "12° · rain at 4 PM"), BriefLine("First", "Team standup at 9:00 AM (+2 more)"), BriefLine("Leave by", "8:50 AM for Office (22 min)"), BriefLine("Wear", "Navy chinos")),
    )

    fun msg(id: Long, sender: String, text: String, minAgo: Long, kind: String = "text") = ChatMsg(id, "agora", sender, text, (ms - minAgo * MIN) / 1000.0, kind, null, null)

    val room = F.lemnos.copy(
        chat = mapOf("agora" to listOf(msg(1, "owner", "Can someone book Denver?", 30), msg(2, "iris", "Denver hotel is booked for Dec 3", 16), msg(3, "iris", "Gateway restarted", 5, kind = "notice"))),
        agoraSeen = 1,
    )
}
