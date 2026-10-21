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
import dev.phonerecall.settings.StubMeaning
import dev.phonerecall.settings.ThemisList
import dev.phonerecall.settings.ThemisRegistry
import dev.phonerecall.settings.SettingRow
import dev.phonerecall.setup.Telete
import dev.phonerecall.setup.TeleteCalls
import dev.phonerecall.setup.TeleteContent
import dev.phonerecall.setup.TeleteFixtures
import dev.phonerecall.setup.TeleteView
import dev.phonerecall.setup.TestResult
import dev.phonerecall.setup.ShakeCalls
import dev.phonerecall.setup.ShakeFixtures
import dev.phonerecall.setup.ShakeView
import dev.phonerecall.setup.ShakedownContent
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.Tone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Telete, the shakedown report and Themis search to PNG (app/build/shots/telete-*.png, shakedown-*.png, themis-*.png), light and dark. Made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeleteShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val tests = listOf(
        "athena" to "Athena", "iris" to "Iris", "alexandria" to "Alexandria", "spotify" to "Spotify", "hestia" to "Hestia",
        "odyssey-fix" to "Odyssey", "weather" to "Aeolia", "tomtom" to "TomTom", "moneta" to "Moneta", "hermes" to "Hermes",
    )
    private val view = TeleteView(
        checks = Telete.checks(TeleteFixtures.partway, TeleteFixtures.NOW),
        tests = tests,
        testWhat = mapOf(
            "athena" to "Sends Anthropic a one-word ping with your key and shows the reply", "iris" to "Asks Iris one harmless read-only question",
            "alexandria" to "Searches the index for \"flight\"", "spotify" to "Reads what is playing now", "hestia" to "Lists the devices Google Home shares",
            "odyssey-fix" to "Gets one location fix", "weather" to "Fetches the forecast for home", "tomtom" to "Times the drive from home to the office",
            "moneta" to "Parses a sample bank notification, offline", "hermes" to "Parses \"set alarm 6:30\" without setting it",
        ),
        results = mapOf(
            "athena" to TestResult(true, "She said: Hello!", 1_400, 0),
            "alexandria" to TestResult(true, "3 found, first from Document", 220, 0),
            "tomtom" to TestResult(false, "No TomTom key in Calypso", 3, 0),
            "moneta" to TestResult(true, "$47.82 at Whole Foods, groceries", 4, 0),
        ),
        running = setOf("iris"),
        checking = true,
    )

    @Composable private fun Page(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2400dp-xhdpi")
    fun teleteCoverLight() { compose.setContent { Page(false) { TeleteContent(view, Space.gutterNarrow, false, TeleteCalls()) } }; save("telete-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2400dp-xhdpi")
    fun teleteCoverDark() { compose.setContent { Page(true) { TeleteContent(view, Space.gutterNarrow, false, TeleteCalls()) } }; save("telete-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w840dp-h1100dp-xhdpi")
    fun teleteReadyInnerDark() {
        val ready = TeleteView(Telete.checks(TeleteFixtures.ready, TeleteFixtures.NOW), tests.take(3))
        compose.setContent { Page(true) { TeleteContent(ready, Space.gutterWide, true, TeleteCalls()) } }; save("telete-ready-inner-dark")
    }

    /** Stand-in rows: the label and description from the registry, as Themis draws a row. */
    private val row: @Composable (String) -> Unit = { key ->
        val e = ThemisRegistry.entries.first { it.row == key }
        SettingRow(e.label, e.about, onClick = {}) { if (key == "battery") StatusWord("Allow", Tone.ACTIVE) }
    }

    @Composable private fun Themis(query: String, meaning: Boolean) =
        ThemisList(Space.gutterNarrow, query, {}, row, if (meaning) StubMeaning.vectors else emptyMap()) { StubMeaning.embed(it) }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun themisSearchLight() { compose.setContent { Page(false) { Themis("battery", meaning = false) } }; save("themis-search-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun themisMeaningDark() { compose.setContent { Page(true) { Themis("where I park", meaning = true) } }; save("themis-search-meaning-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun themisSectionsLight() { compose.setContent { Page(false) { Themis("", meaning = false) } }; save("themis-sections-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun themisSectionsDark() { compose.setContent { Page(true) { Themis("", meaning = false) } }; save("themis-sections-dark") }

    private val shake = ShakeView(report = ShakeFixtures.report, previous = ShakeFixtures.before, history = listOf(ShakeFixtures.report, ShakeFixtures.before), zone = java.time.ZoneId.of("America/New_York"))

    @Test @Config(sdk = [35], qualifiers = "w384dp-h4800dp-xhdpi")
    fun shakedownReportLight() { compose.setContent { Page(false) { ShakedownContent(shake, Space.gutterNarrow, ShakeCalls()) } }; save("shakedown-report-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h4800dp-xhdpi")
    fun shakedownReportDark() { compose.setContent { Page(true) { ShakedownContent(shake, Space.gutterNarrow, ShakeCalls()) } }; save("shakedown-report-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun shakedownRunningDark() {
        val running = ShakeView(running = true, stage = "Forecast for home", elapsedMs = 64_000, live = ShakeFixtures.results.take(12))
        compose.setContent { Page(true) { ShakedownContent(running, Space.gutterNarrow, ShakeCalls()) } }; save("shakedown-running-dark")
    }
}
