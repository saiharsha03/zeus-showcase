package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import dev.phonerecall.qol.QolSignals
import dev.phonerecall.qol.StreetParking
import dev.phonerecall.qol.StreetParkingForm
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

/** Street parking to PNG (app/build/shots/qol-street-*.png): the Themis rule dialog and the Kairos card, cover and inner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QolShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val zone = ZoneId.of("America/New_York")
    // A synthetic rule: north from Tuesday 9 AM, south from Friday 9 AM.
    private val rule = StreetParking.parse("north Tue 9 AM, south Fri 9 AM", car = "the hatchback")!!.copy(turnAround = true)
    private val now = ZonedDateTime.of(2026, 10, 23, 8, 20, 0, 0, zone)
    private val card get() = MomentRules.pick(
        MomentContext(now, qol = QolSignals(street = StreetParking.reminder(rule, now, carHome = true, out = false, moved = 0))), MomentsPrefs(trashAsks = 3),
    )!!

    @Composable private fun Form(dark: Boolean) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface).padding(Space.l), contentAlignment = Alignment.TopCenter) { StreetParkingForm(rule, {}, {}) }
    }

    @Test @Config(sdk = [35], qualifiers = "w452dp-h900dp-xhdpi")
    fun dialogCoverDark() { compose.setContent { Form(true) }; save("qol-street-dialog-w452-dark") }

    @Test @Config(sdk = [35], qualifiers = "w452dp-h900dp-xhdpi")
    fun dialogCoverLight() { compose.setContent { Form(false) }; save("qol-street-dialog-w452-light") }

    @Test @Config(sdk = [35], qualifiers = "w1128dp-h900dp-xhdpi")
    fun dialogInnerDark() { compose.setContent { Form(true) }; save("qol-street-dialog-w1128-dark") }

    @Test @Config(sdk = [35], qualifiers = "w1128dp-h900dp-xhdpi")
    fun dialogInnerLight() { compose.setContent { Form(false) }; save("qol-street-dialog-w1128-light") }

    @Test @Config(sdk = [35], qualifiers = "w452dp-h1400dp-xhdpi")
    fun cardCoverDark() { compose.setContent { dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = false, moment = card, at = now) }; save("qol-street-card-w452-dark") }

    @Test @Config(sdk = [35], qualifiers = "w452dp-h1400dp-xhdpi")
    fun cardCoverLight() { compose.setContent { dev.phonerecall.home.PreviewBoard(1, Space.gutterNarrow, sample = false, dark = false, moment = card, at = now) }; save("qol-street-card-w452-light") }

    @Test @Config(sdk = [35], qualifiers = "w1128dp-h1100dp-xhdpi")
    fun cardInnerDark() { compose.setContent { dev.phonerecall.home.PreviewBoard(2, Space.gutterWide, sample = false, moment = card, at = now) }; save("qol-street-card-w1128-dark") }

    @Test @Config(sdk = [35], qualifiers = "w1128dp-h1100dp-xhdpi")
    fun cardInnerLight() { compose.setContent { dev.phonerecall.home.PreviewBoard(2, Space.gutterWide, sample = false, dark = false, moment = card, at = now) }; save("qol-street-card-w1128-light") }
}
