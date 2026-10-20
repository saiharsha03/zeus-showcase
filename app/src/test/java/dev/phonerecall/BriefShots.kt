package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.brief.BriefLine
import dev.phonerecall.brief.BriefProposal
import dev.phonerecall.brief.MorningBrief
import dev.phonerecall.home.PreviewBoard
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** The morning brief as Olympus's top card (app/build/shots/brief-*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BriefShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val brief = MorningBrief(
        date = LocalDate.now().toString(),
        postedAt = java.time.ZonedDateTime.now().withHour(8).withMinute(5).toInstant().toEpochMilli(),
        title = "Tuesday, October 20 · Office day",
        lede = "Leave by 8:40 AM for Office.",
        today = listOf(
            BriefLine("Weather", "48°, Cloudy, H 55° L 41° · Rain from 2 PM"),
            BriefLine("First", "Team sync at 9:00 AM (+2 more)"),
            BriefLine("Leave by", "8:40 AM for Office (22 min)"),
            BriefLine("Wear", "Office layers, navy chinos, grey quarter-zip"),
            BriefLine("Festival", "Dussehra, in India since 2:30 PM yesterday your time"),
            BriefLine("Match", "Barcelona vs Sevilla at 3:00 PM"),
        ),
        yesterday = listOf(
            BriefLine("Screen", "3 h 10 min · most in YouTube, WhatsApp, Chrome · 48 pickups"),
            BriefLine("Last night", "Reddit at 12:40 AM"),
        ),
        iris = listOf(BriefLine("Mail", "Taylor asked for the Q3 deck by Thursday"), BriefLine("Bill", "Car lease autopay on the 24th")),
        proposals = listOf(BriefProposal("Timer to leave for Office", "set_timer"), BriefProposal("Call home (Dussehra)", "add_reminder")),
    )

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun coverDark() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, dark = true, morning = brief) }; save("brief-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun coverLight() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, dark = false, morning = brief) }; save("brief-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1200dp-xhdpi")
    fun innerDark() { compose.setContent { PreviewBoard(2, Space.gutterWide, sample = true, dark = true, morning = brief) }; save("brief-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1200dp-xhdpi")
    fun innerLight() { compose.setContent { PreviewBoard(2, Space.gutterWide, sample = true, dark = false, morning = brief.copy(iris = emptyList(), irisPending = true)) }; save("brief-inner-light") }
}
