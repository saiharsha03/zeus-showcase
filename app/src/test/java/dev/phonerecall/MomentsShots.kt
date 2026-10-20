package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import dev.phonerecall.brain.Audit
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.moments.CookParser
import dev.phonerecall.moments.LightOn
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentPlans
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.SnapCalls
import dev.phonerecall.moments.SnapContent
import dev.phonerecall.moments.SnapState
import dev.phonerecall.moments.found
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Moments to PNG (app/build/shots/moments-*.png): the snap-box result, the cooking plan card, the leaving-home card. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MomentsShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val box = """
        AIR FRYER: Preheat air fryer to 400°F. Cook 10-12 minutes, shaking basket halfway through.
        CONVENTIONAL OVEN: Preheat oven to 425°F. Spread frozen fries in a single layer on a
        baking sheet. Bake 18-22 minutes, turning once. Let stand 2 minutes.
        Ore-Ida Golden Crinkles French Fried Potatoes
    """.trimIndent()

    private val snap get() = found(CookParser.parse(box))

    private val proposed: SnapState get() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 10, 20, 18, 40, 0, 0, zone).toInstant().toEpochMilli()
        val fates = Fates(MemoryStore(), FakeState(), FakeExecutor(), Audit { _, _, _ -> }, clock = { now })
        val plan = MomentPlans.cook(CookParser.parse(box).best!!, "Fries")
        return SnapState.Proposed((fates.propose(plan.steps, plan.title, null, proposer = "Kairos") as ProposeResult.Created).proposal)
    }

    private val leaving get() = MomentRules.pick(
        MomentContext(
            ZonedDateTime.of(2026, 10, 20, 8, 0, 0, 0, ZoneId.of("America/New_York")), odysseyOn = true, atHome = true,
            departure = ZonedDateTime.of(2026, 10, 20, 8, 20, 0, 0, ZoneId.of("America/New_York")),
            lightsOn = listOf(LightOn("l1", "Kitchen"), LightOn("l2", "Porch")), rainLater = "Rain from 4 PM",
        ),
        dev.phonerecall.moments.MomentsPrefs(),
    )!!

    @Composable private fun Snap(dark: Boolean, s: SnapState, gutter: Dp) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { SnapContent(s, gutter, SnapCalls()) }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun snapCoverDark() { compose.setContent { Snap(true, snap, Space.gutterNarrow) }; save("moments-snap-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun snapCoverLight() { compose.setContent { Snap(false, snap, Space.gutterNarrow) }; save("moments-snap-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun snapInnerDark() { compose.setContent { Snap(true, snap, Space.gutterWide) }; save("moments-snap-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun snapInnerLight() { compose.setContent { Snap(false, snap, Space.gutterWide) }; save("moments-snap-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun planCoverDark() { compose.setContent { Snap(true, proposed, Space.gutterNarrow) }; save("moments-plan-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun planCoverLight() { compose.setContent { Snap(false, proposed, Space.gutterNarrow) }; save("moments-plan-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun planInnerDark() { compose.setContent { Snap(true, proposed, Space.gutterWide) }; save("moments-plan-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun planInnerLight() { compose.setContent { Snap(false, proposed, Space.gutterWide) }; save("moments-plan-inner-light") }

    private val cooking get() = MomentRules.pick(
        MomentContext(
            ZonedDateTime.of(2026, 10, 20, 18, 30, 0, 0, ZoneId.of("America/New_York")), odysseyOn = true, atHome = true,
            purchase = dev.phonerecall.moments.Purchase("digiorno", ZonedDateTime.of(2026, 10, 18, 12, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()),
        ),
        dev.phonerecall.moments.MomentsPrefs(),
    )!!

    private val receipt = SnapState.Receipt(dev.phonerecall.moments.ReceiptDraft("Fresh Mart", 4232, java.time.LocalDate.of(2026, 10, 18)))

    @Test @Config(sdk = [35], qualifiers = "w384dp-h900dp-xhdpi")
    fun receiptCoverDark() { compose.setContent { Snap(true, receipt, Space.gutterNarrow) }; save("moments-receipt-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun receiptInnerLight() { compose.setContent { Snap(false, receipt, Space.gutterWide) }; save("moments-receipt-inner-light") }

    private val hazard get() = MomentRules.pick(
        MomentContext(
            ZonedDateTime.of(2026, 10, 20, 20, 0, 0, 0, ZoneId.of("America/New_York")),
            hazard = dev.phonerecall.moments.MorningHazard("Snow before your 8:20 AM commute", 15, ZonedDateTime.of(2026, 10, 21, 8, 20, 0, 0, ZoneId.of("America/New_York"))),
        ),
        dev.phonerecall.moments.MomentsPrefs(),
    )!!

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun hazardCoverLight() { compose.setContent { dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = false, dark = false, moment = hazard) }; save("moments-hazard-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun cookingCoverDark() { compose.setContent { dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = false, moment = cooking) }; save("moments-cooking-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun leavingCoverDark() { compose.setContent { dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = false, moment = leaving) }; save("moments-leaving-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun leavingCoverLight() { compose.setContent { dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = false, dark = false, moment = leaving) }; save("moments-leaving-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun leavingInnerDark() { compose.setContent { dev.phonerecall.home.PreviewBoard(2, Space.gutterWide, sample = false, moment = leaving) }; save("moments-leaving-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun leavingInnerLight() { compose.setContent { dev.phonerecall.home.PreviewBoard(2, Space.gutterWide, sample = false, dark = false, moment = leaving) }; save("moments-leaving-inner-light") }
}
