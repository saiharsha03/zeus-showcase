package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.qol.SideWindow
import dev.phonerecall.qol.StreetParkingForm
import dev.phonerecall.qol.StreetRule
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

/** Themis → Kairos → Street parking, empty and filled, Fold cover and inner (app/build/shots/street-parking-*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreetParkingShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val rule = StreetRule(listOf(SideWindow("north", 2, 8 * 60), SideWindow("south", 5, 8 * 60)), turnAround = true, car = "the hatchback")

    private fun form(existing: StreetRule?) {
        compose.setContent {
            AthenaTheme(true) {
                Box(Modifier.fillMaxSize().background(Athena.colors.surface).padding(Space.l)) { StreetParkingForm(existing, {}, {}) }
            }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w452dp-h900dp-xhdpi")
    fun coverEmpty() { form(null); save("street-parking-cover-empty") }

    @Test @Config(sdk = [35], qualifiers = "w452dp-h900dp-xhdpi")
    fun coverFilled() { form(rule); save("street-parking-cover-filled") }

    @Test @Config(sdk = [35], qualifiers = "w1128dp-h900dp-xhdpi")
    fun innerFilled() { form(rule); save("street-parking-inner-filled") }
}
