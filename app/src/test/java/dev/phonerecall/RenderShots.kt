package dev.phonerecall

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import dev.phonerecall.home.PreviewBoard
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the Home board to PNG (app/build/shots) so the shapes can be looked at without a device. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        // Draw the window's root view in software; Robolectric has no hardware frames to capture.
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h3200dp-xhdpi")
    fun cover() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true) }; save("cover") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun inner() { compose.setContent { PreviewBoard(2, Space.gutterWide, sample = true) }; save("inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun delphi() { compose.setContent { BrainPreviews.Delphi() }; save("delphi") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h760dp-xhdpi")
    fun delphiEmpty() { compose.setContent { BrainPreviews.DelphiEmpty() }; save("delphi-empty") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun fates() { compose.setContent { BrainPreviews.Fates() }; save("fates") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h760dp-xhdpi")
    fun calypso() { compose.setContent { BrainPreviews.Calypso() }; save("calypso") }

    // ---- phase 2b ----

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1250dp-xhdpi")
    fun parnassus() { compose.setContent { SensesPreviews.Parnassus() }; save("parnassus") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun section() { compose.setContent { SensesPreviews.Groceries() }; save("section-groceries") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1250dp-xhdpi")
    fun people() { compose.setContent { SensesPreviews.PeopleScreen() }; save("people") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h760dp-xhdpi")
    fun lethe() { compose.setContent { SensesPreviews.Lethe() }; save("lethe") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2300dp-xhdpi")
    fun notificationCards() { compose.setContent { SensesPreviews.NotificationCards() }; save("notification-cards") }

    // ---- phase 2c ----

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun alexandria() { compose.setContent { MindPreviews.Alexandria() }; save("alexandria") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1350dp-xhdpi")
    fun places() { compose.setContent { MindPreviews.Places() }; save("places") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun assistOverlay() { compose.setContent { MindPreviews.AssistOverlay() }; save("assist-overlay") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1300dp-xhdpi")
    fun themisSpend() { compose.setContent { MindPreviews.ThemisSpend() }; save("themis-spend") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun longJobs() { compose.setContent { MindPreviews.LongJobs() }; save("long-jobs") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1150dp-xhdpi")
    fun pantheon() { compose.setContent { MindPreviews.Pantheon() }; save("pantheon") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun remembered() { compose.setContent { MindPreviews.Remembered() }; save("remembered") }

    // ---- phase 3 ----

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosPairing() { compose.setContent { LemnosPreviews.Pairing() }; save("lemnos-pairing") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosTerminal() { compose.setContent { LemnosPreviews.TerminalCover() }; save("lemnos-terminal-cover") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun lemnosTerminalInner() { compose.setContent { LemnosPreviews.TerminalInner() }; save("lemnos-terminal-inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosDesktopCover() { compose.setContent { LemnosPreviews.DesktopCover() }; save("lemnos-desktop-cover") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun lemnosDesktopInner() { compose.setContent { LemnosPreviews.DesktopInner() }; save("lemnos-desktop-inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosDesktopOff() { compose.setContent { LemnosPreviews.DesktopOff() }; save("lemnos-desktop-off") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosFiles() { compose.setContent { LemnosPreviews.Files() }; save("lemnos-files") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosFileOpen() { compose.setContent { LemnosPreviews.FileOpen() }; save("lemnos-file-open") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosServices() { compose.setContent { LemnosPreviews.Services() }; save("lemnos-services") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosUnit() { compose.setContent { LemnosPreviews.Unit() }; save("lemnos-unit") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun lemnosHetzner() { compose.setContent { LemnosPreviews.HetznerTab() }; save("lemnos-hetzner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun pantheonLive() { compose.setContent { LemnosPreviews.PantheonCover() }; save("pantheon-live") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun pantheonChat() { compose.setContent { LemnosPreviews.Chat() }; save("pantheon-chat") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun pantheonSlash() { compose.setContent { LemnosPreviews.ChatSlash() }; save("pantheon-slash") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1000dp-xhdpi")
    fun pantheonInner() { compose.setContent { LemnosPreviews.PantheonInner() }; save("pantheon-inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun cerberus() { compose.setContent { LemnosPreviews.Cerberus() }; save("cerberus") }

    // ---- music ----

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun musicOlympus() { compose.setContent { MusicPreviews.Olympus() }; save("music-olympus") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1300dp-xhdpi")
    fun themisMusic() { compose.setContent { MusicPreviews.ThemisMusic() }; save("themis-music") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun themisMusicSignedOut() { compose.setContent { MusicPreviews.ThemisMusicSignedOut() }; save("themis-music-signed-out") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h3200dp-xhdpi")
    fun coverLight() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, dark = false) }; save("cover-light") }

    // ---- Olympia ----

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1900dp-xhdpi")
    fun olympia() { compose.setContent { OlympiaPreviews.Screen() }; save("olympia") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun olympiaInner() { compose.setContent { OlympiaPreviews.Screen(wide = true) }; save("olympia-inner") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1900dp-xhdpi")
    fun olympiaLight() { compose.setContent { OlympiaPreviews.Light() }; save("olympia-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun olympiaTeam() { compose.setContent { OlympiaPreviews.Team() }; save("olympia-team") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2250dp-xhdpi")
    fun olympiaSettings() { compose.setContent { OlympiaPreviews.Settings() }; save("olympia-settings") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun olympiaHomeCard() { compose.setContent { OlympiaPreviews.HomeCard() }; save("olympia-home-card") }

    // ---- Aeolia ----

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun aeoliaHomeCards() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, weather = AeoliaPreviews.state()) }; save("aeolia-olympus") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun aeoliaHomeCardsLight() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, dark = false, weather = AeoliaPreviews.state()) }; save("aeolia-olympus-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun aeoliaHomeCardQuiet() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, weather = AeoliaPreviews.state().copy(alerts = emptyList())) }; save("aeolia-olympus-quiet") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun olympiaWidget() {
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        // 3 x 2 cells on the Fold's cover screen: about 250 x 150 dp.
        OlympiaPreviews.widget(compose.activity, File(dir, "olympia-widget.png"), 500, 300)
    }
}
