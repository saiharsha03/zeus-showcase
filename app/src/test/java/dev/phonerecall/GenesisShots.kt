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
import dev.phonerecall.fresh.GenesisCalls
import dev.phonerecall.fresh.GenesisSetupContent
import dev.phonerecall.fresh.GenesisSetupView
import dev.phonerecall.fresh.GenesisSplash
import dev.phonerecall.fresh.Planning
import dev.phonerecall.fresh.SetupRow
import dev.phonerecall.setup.Check
import dev.phonerecall.setup.Fix
import dev.phonerecall.setup.Status
import dev.phonerecall.setup.Telete
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

/** Genesis to PNG (app/build/shots/genesis-*.png), on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GenesisShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun row(id: String, group: String, label: String, why: String, status: Status, detail: String = "", fix: String = "Allow", skipped: Boolean = false) =
        SetupRow(Check(id, group, label, why, status, detail, Fix.Open("HOME"), fix), skipped)

    private val rows = listOf(
        row("location", Telete.PHONE, "Location", "Odyssey's places and drives, and the weather where you are", Status.OK),
        row("nearby", Telete.PHONE, "Nearby devices", "Notices the car's Bluetooth connecting, for drives and Clio", Status.MISSING, "Not allowed yet"),
        row("listener", Telete.PHONE, "Notification senses", "Cards, birthdays, Moneta's spending and who to answer first", Status.MISSING, "Notification access is off", "Open"),
        row("battery", Telete.PHONE, "Unrestricted background", "Heartbeat, syncs and Odyssey keep running when the screen is off", Status.MISSING, "Set Battery to Unrestricted", "Open", skipped = true),
        row("key-anthropic", Telete.KEYS, "Anthropic", "Athena's mind and chat", Status.OK),
        row("key-tomtom", Telete.KEYS, "TomTom", "Traffic and leave-by times", Status.OK),
        row("lemnos", Telete.LINKS, "Lemnos paired", "The server: Iris, Atlas, Agora, backups", Status.OK),
        row("spotify", Telete.LINKS, "Spotify", "Music by context and while driving", Status.MISSING, "Not connected", "Connect"),
        row("phrase", Telete.DATA, "A new recovery phrase", "The old phrase was exposed. A new one locks every backup from now on", Status.MISSING, "Still the old phrase", "Change"),
        row("alexandria", Telete.DATA, "Alexandria", "Search over your notes, chats and documents, by words and meaning", Status.MISSING, "Not indexed yet", "Index now"),
        row("home-office", Telete.PLACES, "Home and office", "Commute checks and leave-by times need both", Status.MISSING, "Neither is marked yet", "Mark"),
    )
    private val view = GenesisSetupView(rows, rows.count { it.left }, irisAsked = true)

    @Composable private fun Page(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi") fun setupCoverDark() { compose.setContent { Page(true) { GenesisSetupContent(view, Space.gutterNarrow, false, GenesisCalls()) } }; save("genesis-setup-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1700dp-xhdpi") fun setupCoverLight() { compose.setContent { Page(false) { GenesisSetupContent(view, Space.gutterNarrow, false, GenesisCalls()) } }; save("genesis-setup-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun setupInnerDark() { compose.setContent { Page(true) { GenesisSetupContent(view, Space.gutterWide, true, GenesisCalls()) } }; save("genesis-setup-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h800dp-xhdpi") fun planningCoverDark() { compose.setContent { Page(true) { Planning(Space.gutterNarrow) } }; save("genesis-planning-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h800dp-xhdpi") fun splashCoverDark() { compose.setContent { Page(true) { GenesisSplash(animate = false) {} } }; save("genesis-splash-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h840dp-xhdpi") fun splashInnerDark() { compose.setContent { Page(true) { GenesisSplash(animate = false) {} } }; save("genesis-splash-inner-dark") }
}
