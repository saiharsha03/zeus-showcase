package dev.phonerecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.phonerecall.odyssey.DriveEvent
import dev.phonerecall.odyssey.DriveEventKind
import dev.phonerecall.odyssey.DriveReport
import dev.phonerecall.odyssey.DriveRoute
import dev.phonerecall.odyssey.Fx
import dev.phonerecall.odyssey.OdysseyCallbacks
import dev.phonerecall.odyssey.OdysseyContent
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.OpenStay
import dev.phonerecall.odyssey.Perms
import dev.phonerecall.odyssey.PhoneUse
import dev.phonerecall.odyssey.Route
import dev.phonerecall.odyssey.RoadWords
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import java.time.LocalDate
import java.time.ZonedDateTime

/** Fixed Odyssey screens for the screenshot test: a Saturday with a car wash, Costco and the temple. */
object OdysseyPreviews {
    val today: LocalDate = LocalDate.of(2026, 10, 17)
    val now: ZonedDateTime = ZonedDateTime.of(today.atTime(15, 40), Fx.zone)
    private val lists = listOf(1L to "Groceries", 2L to "Hardware")

    val data: OdysseyData by lazy {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(Fx.zone))
        var d = Fx.officeWeeks(5, today.minusWeeks(5).with(java.time.DayOfWeek.MONDAY), driveMin = { if (it.dayOfWeek == java.time.DayOfWeek.TUESDAY) 28 else 21 })
        // Last Saturday at Costco, so today's is the second visit.
        d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(today.minusDays(7), 12), Fx.at(today.minusDays(7), 12, 40), hints = listOf("You spent $61.20 at COSTCO WHSE #1234"), street = "Maple Ave")
        d = Fx.stay(d, Fx.HOME_LAT, Fx.HOME_LNG, Fx.at(today.minusDays(1), 17, 30), Fx.at(today, 9, 50))
        d = Fx.stay(d, 42.9801, -85.6102, Fx.at(today, 10, 2), Fx.at(today, 10, 16), hints = listOf("Purchase of $14.00 at DELTA SONIC CAR WASH"), street = "Birch Dr")
        d = Fx.stay(d, Fx.TEMPLE_LAT, Fx.TEMPLE_LNG, Fx.at(today, 10, 35), Fx.at(today, 11, 50), poi = "Riverside Cultural Center", street = "Ridge Rd")
        d = Fx.stay(d, Fx.COSTCO_LAT, Fx.COSTCO_LNG, Fx.at(today, 12, 5), Fx.at(today, 12, 55), hints = listOf("You spent $82.14 at COSTCO WHSE #1234"), street = "Maple Ave")
        val ms = now.toInstant().toEpochMilli()
        val drives = listOf(
            DriveReport("d1", Fx.at(today, 12, 55), Fx.at(today, 13, 18), 14_200, 92,
                listOf(DriveEvent(DriveEventKind.HARSH_BRAKE, Fx.at(today, 13, 4), 42.98, -85.64, 0.41, 46, "Maple Ave"), DriveEvent(DriveEventKind.SHARP_TURN, Fx.at(today, 13, 12), 42.96, -85.66, 0.38, 31, "Elm St")),
                PhoneUse(1.4, 0.8, 1, mapOf("com.whatsapp" to 0.9), mapOf("com.google.android.apps.maps" to 21.0)), 84),
            DriveReport("d2", Fx.at(today, 9, 50), Fx.at(today, 10, 2), 6_100, 64, emptyList(), PhoneUse(), 100),
            DriveReport("d3", Fx.at(today.minusDays(3), 8, 20), Fx.at(today.minusDays(3), 8, 49), 13_100, 104,
                listOf(DriveEvent(DriveEventKind.HARSH_BRAKE, Fx.at(today.minusDays(3), 8, 31), 42.98001, -85.64, 0.45, 50, "Maple Ave")), PhoneUse(), 92),
            DriveReport("d4", Fx.at(today.minusDays(9), 8, 20), Fx.at(today.minusDays(9), 8, 44), 13_100, 98, emptyList(), PhoneUse(), 81),
        )
        val check = RoadWords.check("event:dinner", "event", "Dinner at Jordan's", Fx.at(today, 18, 30), listOf(Route(26 * 60, 7 * 60, 17_000, listOf("Road work, +6 min"))), emptyList(), ms - 10 * Fx.MIN, 42.90, -85.55, null)
        val named = drives.map { r -> routes[r.id]?.let { onRoute(r, it) } ?: r }.map { r ->
            if (r.id == "d1") r.copy(from = "Maple Ave, Grand Rapids", to = "Oak Ave, Grand Rapids", weather = "48°F, Cloudy", weatherCode = 3) else r
        }
        val car = dev.phonerecall.odyssey.CarData(
            lease = dev.phonerecall.odyssey.Lease(start = "2026-03-01", termMonths = 36, startOdometer = 12, car = "2026 Example Sedan"),
            fillups = listOf(
                dev.phonerecall.odyssey.FillUp("f1", Fx.at(today.minusDays(30), 18), "Costco", 4_210, 11.8, 3.57, odometer = 6_820, state = "logged"),
                dev.phonerecall.odyssey.FillUp("f2", Fx.at(today.minusDays(9), 18), "Costco", 4_005, 11.9, 3.37, odometer = 7_191, state = "logged"),
            ),
            readings = listOf(dev.phonerecall.odyssey.Reading(Fx.at(today.minusDays(9), 18), 7_191)),
        )
        d.copy(drives = named, car = car, checks = listOf(check), stay = OpenStay(Fx.at(today, 13, 25), Fx.HOME_LAT, Fx.HOME_LNG, street = "Oak Ave"))
    }

    /** Synthetic paths for three of the drives (d2 has none: Keep routes was off). */
    val routes: Map<String, DriveRoute> by lazy {
        listOf("d1" to Fx.at(today, 12, 55), "d3" to Fx.at(today.minusDays(3), 8, 20), "d4" to Fx.at(today.minusDays(9), 8, 20))
            .associate { (id, t) -> id to dev.phonerecall.odyssey.syntheticRoute(id, t) }
    }

    /** Puts a drive's events on its path, at the point nearest their time, plus a phone pick-up on d1. */
    private fun onRoute(r: DriveReport, route: DriveRoute): DriveReport {
        val pts = route.points
        val evs = r.events.mapIndexed { i, e -> pts[pts.size * (i + 1) / (r.events.size + 2)].let { p -> e.copy(at = p.t, lat = p.lat, lng = p.lng, kmh = p.kmh) } }
        val extra = if (r.id == "d1") pts[pts.size * 4 / 5].let { p -> listOf(DriveEvent(DriveEventKind.HANDLING, p.t, p.lat, p.lng, 0.0, p.kmh, "Park Blvd")) } else emptyList()
        return r.copy(start = pts.first().t, end = route.end, events = (evs + extra).sortedBy { it.at })
    }

    @Composable private fun Page(dark: Boolean = true, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    private val perms = Perms(fine = true, background = true, activity = true, usage = false, tomtom = true, ny511 = false)

    @Composable fun Timeline() = Page { OdysseyContent(data, lists, perms, now, Space.gutterNarrow, false, OdysseyCallbacks()) }
    @Composable fun TimelineInner() = Page { OdysseyContent(data, lists, perms, now, Space.gutterWide, true, OdysseyCallbacks()) }
    @Composable fun Places() = Page { OdysseyContent(data, lists, perms, now, Space.gutterNarrow, false, OdysseyCallbacks(), startTab = 1) }
    @Composable fun Habits() = Page { OdysseyContent(data, lists, perms, now, Space.gutterNarrow, false, OdysseyCallbacks(), startTab = 2) }
    @Composable fun Logs() = Page { OdysseyContent(data, lists, perms, now, Space.gutterNarrow, false, OdysseyCallbacks(), startTab = 3) }
    @Composable fun Drives() = Page { OdysseyContent(data, lists, perms, now, Space.gutterNarrow, false, OdysseyCallbacks(), startTab = 4, routes = routes, online = false) }
    @Composable fun DriveMapReport(dark: Boolean, wide: Boolean, id: String = "d1") = Page(dark) {
        OdysseyContent(data, lists, perms, now, if (wide) Space.gutterWide else Space.gutterNarrow, wide, OdysseyCallbacks(), startTab = 4, routes = routes, online = false, openDrive = id)
    }
    @Composable fun Setup() = Page { OdysseyContent(OdysseyData(), lists, Perms(fine = true), now, Space.gutterNarrow, false, OdysseyCallbacks()) }
}
