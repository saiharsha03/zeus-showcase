package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import dev.phonerecall.home.PreviewOdysseyBoard
import dev.phonerecall.odyssey.CarData
import dev.phonerecall.odyssey.FillUp
import dev.phonerecall.odyssey.Fx
import dev.phonerecall.odyssey.Lease
import dev.phonerecall.odyssey.OdysseyCards
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.odyssey.OdysseyHome
import dev.phonerecall.odyssey.OfficeDay
import dev.phonerecall.odyssey.Pt
import dev.phonerecall.odyssey.RadarFrame
import dev.phonerecall.odyssey.Reading
import dev.phonerecall.odyssey.RoadSpan
import dev.phonerecall.odyssey.SnowBand
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime

/** The snow-band, "WFH today?", fill-up and lease cards to PNG (app/build/shots/roads2-*.png), built by the real rules. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderRoads2 {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "roads2-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val day = LocalDate.of(2026, 11, 2)
    private val now = ZonedDateTime.of(day.atTime(7, 15), Fx.zone)
    private val ms = now.toInstant().toEpochMilli()

    private val snow by lazy {
        val route = Fx.line(Pt(Fx.HOME_LAT, Fx.HOME_LNG), Pt(Fx.WORK_LAT, Fx.WORK_LNG))
        val frames = (0..18).map { Fx.at(day, 7) + it * 10 * Fx.MIN }.map { t -> RadarFrame(t, "t") { lat, _ -> if (t in Fx.at(day, 8, 10)..Fx.at(day, 8, 40) && lat in 42.90..42.93) 30 else 0 } }
        val dep = Fx.at(day, 8, 20)
        val a = SnowBand.advise(route, listOf(RoadSpan(0, "Oak Ave"), RoadSpan(4, "I-190")), 22 * 60, dep, frames, ms)!!
        SnowBand.note("snow:commute:$day", a, true, "Office", dep, 22, "NOAA radar and HRRR forecast", ms, Fx.zone)
    }

    private val wfh by lazy { OfficeDay.note(ZonedDateTime.of(day.atTime(8, 45), Fx.zone)).copy(expiresAt = Long.MAX_VALUE) }

    private val lease = Lease(start = "2026-03-14", termMonths = 36, startOdometer = 12, overageCents = 15, car = "2026 Example Crossover", fromIris = setOf("start", "termMonths", "startOdometer", "overageCents"))
    private fun fill(id: String, d: Int, odo: Int, gal: Double) = FillUp(id, Fx.at(LocalDate.of(2026, 10, d), 9), "Costco Gas", (gal * 376).toLong(), gal, 3.76, "Example Bank ••1234", odo, "logged")

    private fun car(answered: Boolean): CarData {
        val logged = listOf(fill("a", 5, 7_300, 10.1), fill("b", 15, 7_610, 10.4), fill("c", 26, 7_930, 10.2))
        val open = FillUp("f1", ms - 4 * Fx.MIN, "Costco Gas", askId = "ask-1").let {
            if (answered) it.copy(cents = 4210, gallons = 11.2, pricePerGal = 3.759, card = "Example Bank ••1234", answered = true) else it
        }
        return CarData(lease, logged + open, listOf(Reading(Fx.at(LocalDate.of(2026, 10, 26), 9), 7_930)), if (answered) emptyMap() else mapOf("ask-1" to "fuel:f1"), 1, 1)
    }

    private fun home(vararg kinds: String, answered: Boolean = true): OdysseyHome {
        val d = OdysseyData(notes = listOf(snow, wfh), car = car(answered))
        return OdysseyHome(
            snow = if ("snow" in kinds) OdysseyCards.note(d, "snow", ms) else null,
            wfh = if ("wfh" in kinds) OdysseyCards.wfh(d, ms) else null,
            fuel = if ("fuel" in kinds) OdysseyCards.fuel(d, ms) else null,
            lease = if ("lease" in kinds) OdysseyCards.lease(d, now) else null,
        )
    }

    private fun cover(name: String, dark: Boolean, vararg kinds: String, answered: Boolean = true) {
        compose.setContent { PreviewOdysseyBoard(home(*kinds, answered = answered), now, 1, Space.gutterNarrow, dark) }
        save(name)
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h700dp-xhdpi") fun snowDark() = cover("snow-cover-dark", true, "snow")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h700dp-xhdpi") fun snowLight() = cover("snow-cover-light", false, "snow")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h620dp-xhdpi") fun wfhDark() = cover("wfh-cover-dark", true, "wfh")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h620dp-xhdpi") fun wfhLight() = cover("wfh-cover-light", false, "wfh")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h620dp-xhdpi") fun fuelWaitingDark() = cover("fuel-waiting-cover-dark", true, "fuel", answered = false)
    @Test @Config(sdk = [35], qualifiers = "w384dp-h620dp-xhdpi") fun fuelDark() = cover("fuel-cover-dark", true, "fuel")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h620dp-xhdpi") fun fuelLight() = cover("fuel-cover-light", false, "fuel")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h700dp-xhdpi") fun leaseDark() = cover("lease-cover-dark", true, "lease")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h700dp-xhdpi") fun leaseLight() = cover("lease-cover-light", false, "lease")

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun innerDark() { compose.setContent { PreviewOdysseyBoard(home("snow", "wfh", "fuel", "lease"), now, 2, Space.gutterWide, true) }; save("inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun innerLight() { compose.setContent { PreviewOdysseyBoard(home("snow", "wfh", "fuel", "lease"), now, 2, Space.gutterWide, false) }; save("inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi")
    fun carTab() {
        val d = OdysseyData(settings = dev.phonerecall.odyssey.OdysseySettings(on = true), car = car(true))
        compose.setContent {
            dev.phonerecall.ui.AthenaTheme(true) {
                Box(Modifier.fillMaxSize().background(dev.phonerecall.ui.Athena.colors.surface)) {
                    dev.phonerecall.odyssey.OdysseyContent(d, emptyList(), dev.phonerecall.odyssey.Perms(fine = true), now, Space.gutterNarrow, false, dev.phonerecall.odyssey.OdysseyCallbacks(), startTab = 5)
                }
            }
        }
        save("car-tab-dark")
    }
}
