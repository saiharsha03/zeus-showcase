package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.rhythms.AppRow
import dev.phonerecall.rhythms.Bookmark
import dev.phonerecall.rhythms.CallNudgeCard
import dev.phonerecall.rhythms.CallRow
import dev.phonerecall.rhythms.NudgeCardView
import dev.phonerecall.rhythms.RhythmsContent
import dev.phonerecall.rhythms.RhythmsView
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Rhythms to PNG (app/build/shots/rhythms-*.png), on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
class RhythmsShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val notes = listOf(Bookmark(1, "a", "Amma", "The new job offer"), Bookmark(2, "a", "Amma", "Diwali tickets are booked", done = true))
    private val nudge = NudgeCardView(
        "k", "Call home?", "It's 8:00 AM in India", listOf("whatsapp_video" to "WhatsApp video", "whatsapp_voice" to "Voice"),
        notes, "43°C in Pune today, a heatwave.",
    )

    private val learned = RhythmsView(
        usage = true, callLog = true, senses = true,
        calls = listOf(CallRow("a", "Amma", "Daily, about 8:00 AM IST (10:30 PM here) · usually up 6:30 AM–10:30 PM IST", notes.take(1)), CallRow("r", "Alex", "Sundays, about 10:05 AM EDT")),
        apps = listOf(
            AppRow("com.example.chess", "Chess Arena", "Most nights, 8:45–10 PM", 2),
            AppRow("com.spotify.music", "Spotify", "Weekday mornings, 8:15–9 AM", 0),
        ),
        excluded = listOf("Instagram" to "com.instagram.android"),
        home = listOf("43°C in Pune today, a heatwave."),
    )

    @Composable private fun Page(dark: Boolean = true, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Test fun page() { compose.setContent { Page { RhythmsContent(learned.copy(nudge = nudge), Space.gutterNarrow, {}) } }; save("rhythms-page") }

    @Test fun pageLight() { compose.setContent { Page(dark = false) { RhythmsContent(learned, Space.gutterNarrow, {}) } }; save("rhythms-page-light") }

    @Test fun firstRun() { compose.setContent { Page { RhythmsContent(RhythmsView(usage = true), Space.gutterNarrow, {}) } }; save("rhythms-first-run") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun nudge() {
        compose.setContent {
            Page {
                Column {
                    ScreenHeader("Olympus", Space.gutterNarrow)
                    CallNudgeCard(nudge, modifier = Modifier.padding(horizontal = Space.gutterNarrow))
                    CallNudgeCard(nudge.copy(title = "Call Alex?", line = "It's 10:05 AM in New York", ways = listOf("phone" to "Call", "whatsapp_video" to "WhatsApp video"), notes = emptyList(), weather = null), modifier = Modifier.padding(Space.gutterNarrow))
                }
            }
        }
        save("rhythms-nudge")
    }
}
