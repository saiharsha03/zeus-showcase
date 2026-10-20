package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.hermes.CallCmd
import dev.phonerecall.hermes.Choice
import dev.phonerecall.hermes.HermesSheet
import dev.phonerecall.hermes.HermesTurn
import dev.phonerecall.hermes.AlarmCmd
import dev.phonerecall.hermes.OpenCmd
import dev.phonerecall.hermes.Outcome
import dev.phonerecall.ui.AthenaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Hermes's command sheet to PNG (app/build/shots/hermes-*.png), light and dark, on made-up replies. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HermesShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val alarm = HermesTurn("wake me at 6:30", AlarmCmd(6, 30), Outcome("Alarm set for 6:30 AM", undo = {}))
    private val call = HermesTurn(
        "call amma", CallCmd("amma", "auto"),
        Outcome("WhatsApp video call Amma?", "WhatsApp video, India number. 1:30 AM IST there", confirm = Choice("Call") { Outcome("") },
            choices = listOf(Choice("Phone call instead") { Outcome("") })),
    )
    private val which = HermesTurn("open google", OpenCmd("google"), Outcome("Which one?", choices = listOf(Choice("Google Maps", "App") { Outcome("") }, Choice("Google Meet", "App") { Outcome("") })))

    @Composable private fun Sheet(dark: Boolean, turn: HermesTurn?) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            HermesSheet(turn, busy = false, onSubmit = {}, onChoice = {}, onUndo = {}, onAllow = {}, onDismiss = {}, onClose = {}, voice = false)
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun undoDark() { compose.setContent { Sheet(true, alarm) }; save("hermes-undo-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun undoLight() { compose.setContent { Sheet(false, alarm) }; save("hermes-undo-light") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun callDark() { compose.setContent { Sheet(true, call) }; save("hermes-call-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun callLight() { compose.setContent { Sheet(false, call) }; save("hermes-call-light") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun whichLight() { compose.setContent { Sheet(false, which) }; save("hermes-which-light") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun emptyDark() { compose.setContent { Sheet(true, null) }; save("hermes-empty-dark") }
}
