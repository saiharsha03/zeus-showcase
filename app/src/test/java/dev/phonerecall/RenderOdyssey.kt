package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders Odyssey's screens to PNG (app/build/shots/odyssey-*.png), cover and inner. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderOdyssey {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun timeline() { compose.setContent { OdysseyPreviews.Timeline() }; save("odyssey-timeline") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun timelineInner() { compose.setContent { OdysseyPreviews.TimelineInner() }; save("odyssey-timeline-inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun places() { compose.setContent { OdysseyPreviews.Places() }; save("odyssey-places") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun habits() { compose.setContent { OdysseyPreviews.Habits() }; save("odyssey-habits") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h900dp-xhdpi")
    fun logs() { compose.setContent { OdysseyPreviews.Logs() }; save("odyssey-logs") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1300dp-xhdpi")
    fun drives() { compose.setContent { OdysseyPreviews.Drives() }; save("odyssey-drives") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h3000dp-xhdpi")
    fun driveMapDark() { compose.setContent { OdysseyPreviews.DriveMapReport(dark = true, wide = false) }; save("odyssey-drive-map-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h3000dp-xhdpi")
    fun driveMapLight() { compose.setContent { OdysseyPreviews.DriveMapReport(dark = false, wide = false) }; save("odyssey-drive-map-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h2600dp-xhdpi")
    fun driveMapWideDark() { compose.setContent { OdysseyPreviews.DriveMapReport(dark = true, wide = true, id = "d3") }; save("odyssey-drive-map-wide-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h2600dp-xhdpi")
    fun driveMapWideLight() { compose.setContent { OdysseyPreviews.DriveMapReport(dark = false, wide = true, id = "d3") }; save("odyssey-drive-map-wide-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h900dp-xhdpi")
    fun setup() { compose.setContent { OdysseyPreviews.Setup() }; save("odyssey-setup") }
}
