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
import dev.phonerecall.hestia.FakeHomeBackend
import dev.phonerecall.hestia.HestiaCalls
import dev.phonerecall.hestia.HestiaContent
import dev.phonerecall.hestia.HomeSnapshot
import dev.phonerecall.hestia.Link
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

/** Hestia to PNG (app/build/shots/hestia-*.png), on the fake home. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HestiaShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun Page(dark: Boolean, s: HomeSnapshot, gutter: Dp, wide: Boolean, away: Boolean? = true, open: String? = null, notice: String? = null) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { HestiaContent(s, away, gutter, wide, notice, HestiaCalls(), startOpen = open) }
    }

    private val home = FakeHomeBackend.sample()

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1800dp-xhdpi")
    fun coverDark() { compose.setContent { Page(true, home, Space.gutterNarrow, false, open = "d1") }; save("hestia-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1800dp-xhdpi")
    fun coverLight() { compose.setContent { Page(false, home, Space.gutterNarrow, false, open = "d1") }; save("hestia-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun innerDark() { compose.setContent { Page(true, home, Space.gutterWide, true, away = false, open = "d4") }; save("hestia-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun innerLight() { compose.setContent { Page(false, home, Space.gutterWide, true, away = false, notice = "Kettle plug is offline") }; save("hestia-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h900dp-xhdpi")
    fun notConnectedDark() { compose.setContent { Page(true, HomeSnapshot(Link.NeedsPermission), Space.gutterNarrow, false) }; save("hestia-notconnected-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h900dp-xhdpi")
    fun notConnectedLight() {
        compose.setContent { Page(false, HomeSnapshot(Link.NotConnected("Google Home permissions aren't available for Zeus on this phone. The app may not be registered in the Google Home Developer Console yet, or your account isn't a test user")), Space.gutterNarrow, false) }
        save("hestia-notconnected-light")
    }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun notConnectedInner() { compose.setContent { Page(true, HomeSnapshot(Link.NeedsPermission), Space.gutterWide, true) }; save("hestia-notconnected-inner") }
}
