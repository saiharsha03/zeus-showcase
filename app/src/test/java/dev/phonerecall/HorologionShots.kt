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
import dev.phonerecall.horologion.HorologionCalls
import dev.phonerecall.horologion.HorologionContent
import dev.phonerecall.horologion.HorologionFixtures
import dev.phonerecall.horologion.HorologionView
import dev.phonerecall.horologion.Tab
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

/** Horologion to PNG (app/build/shots/horologion-*.png), light and dark, cover and inner. Made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HorologionShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val view = HorologionView(HorologionFixtures.items())

    @Composable private fun Page(dark: Boolean, wide: Boolean, tab: Tab, query: String = "") = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            HorologionContent(view, if (wide) Space.gutterWide else Space.gutterNarrow, wide, HorologionCalls(), tab0 = tab, query0 = query)
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun jobsCoverLight() { compose.setContent { Page(false, false, Tab.JOBS) }; save("horologion-jobs-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun jobsCoverDark() { compose.setContent { Page(true, false, Tab.JOBS) }; save("horologion-jobs-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w840dp-h1100dp-xhdpi")
    fun skillsInnerLight() { compose.setContent { Page(false, true, Tab.SKILLS) }; save("horologion-skills-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w840dp-h1100dp-xhdpi")
    fun setupInnerDark() { compose.setContent { Page(true, true, Tab.SETUP) }; save("horologion-setup-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun searchCoverDark() { compose.setContent { Page(true, false, Tab.JOBS, "hook") }; save("horologion-search-cover-dark") }
}
