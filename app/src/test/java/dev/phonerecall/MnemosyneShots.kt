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
import dev.phonerecall.mnemosyne.LastRun
import dev.phonerecall.mnemosyne.MnCalls
import dev.phonerecall.mnemosyne.MnState
import dev.phonerecall.mnemosyne.MnemosyneContent
import dev.phonerecall.mnemosyne.Phrase
import dev.phonerecall.mnemosyne.PhraseContent
import dev.phonerecall.mnemosyne.PhraseStep
import dev.phonerecall.mnemosyne.RemoteBackup
import dev.phonerecall.mnemosyne.RestoreCalls
import dev.phonerecall.mnemosyne.RestoreContent
import dev.phonerecall.mnemosyne.RestoreStep
import dev.phonerecall.mnemosyne.RestoreUi
import dev.phonerecall.mnemosyne.Restored
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

/** Mnemosyne to PNG (app/build/shots/mnemosyne-*.png), on made-up data. The phrase is a made-up one too. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MnemosyneShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val now = System.currentTimeMillis()
    private val h = 3_600_000L
    private val words = Phrase.encode(ByteArray(32) { (it * 37 + 11).toByte() })
    private val remote = listOf(
        RemoteBackup("20261020T030412Z-1a2b3c4d", "nightly", 48_300_000, now - 7 * h),
        RemoteBackup("20261019T030355Z-5e6f7a8b", "nightly", 48_100_000, now - 31 * h),
        RemoteBackup("20261018T172210Z-9c0d1e2f", "manual", 47_900_000, now - 41 * h),
        RemoteBackup("20261018T030301Z-3a4b5c6d", "nightly", 47_850_000, now - 55 * h),
    )
    private val main = MnState(
        setUp = true, paired = true, last = LastRun(now - 7 * h, true, 48_300_000, remote[0].id, null, "nightly"),
        calypso = true, calypsoTotal = 7, calypsoEscrowed = 5, remote = remote,
        nextRun = dev.phonerecall.mnemosyne.Mnemosyne.nextRun().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    @Composable private fun Frame(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    private fun g(wide: Boolean) = if (wide) Space.gutterWide else Space.gutterNarrow

    private fun page(dark: Boolean, wide: Boolean, s: MnState = main) = compose.setContent { Frame(dark) { MnemosyneContent(g(wide), wide, s, MnCalls()) } }
    private fun phrase(dark: Boolean, wide: Boolean) = compose.setContent { Frame(dark) { PhraseContent(g(wide), wide, words, PhraseStep.SHOW, listOf(4, 11, 19)) } }
    private fun restore(dark: Boolean, wide: Boolean, r: RestoreUi) = compose.setContent { Frame(dark) { RestoreContent(g(wide), wide, r, RestoreCalls()) } }
    private val typing = RestoreUi(RestoreStep.PHRASE, remote, picked = remote[0], words = words.take(14).joinToString(" "))

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2000dp-xhdpi") fun pageCoverDark() { page(true, false); save("mnemosyne-page-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h2000dp-xhdpi") fun pageCoverLight() { page(false, false); save("mnemosyne-page-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun pageInnerDark() { page(true, true); save("mnemosyne-page-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun pageInnerLight() { page(false, true); save("mnemosyne-page-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1600dp-xhdpi") fun setupCoverLight() { page(false, false, MnState(paired = true)); save("mnemosyne-setup-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h2200dp-xhdpi") fun restoredCoverDark() {
        page(true, false, main.copy(restored = Restored(now - 60_000, remote[0].id, 14, 9, 212, 5, 2, emptyList()))); save("mnemosyne-restored-cover-dark")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2000dp-xhdpi") fun phraseCoverDark() { phrase(true, false); save("mnemosyne-phrase-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h2000dp-xhdpi") fun phraseCoverLight() { phrase(false, false); save("mnemosyne-phrase-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun phraseInnerDark() { phrase(true, true); save("mnemosyne-phrase-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun phraseInnerLight() { phrase(false, true); save("mnemosyne-phrase-inner-light") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi") fun checkCoverDark() {
        compose.setContent { Frame(true) { PhraseContent(g(false), false, words, PhraseStep.CHECK, listOf(4, 11, 19), listOf(words[3], "", "")) } }; save("mnemosyne-check-cover-dark")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1600dp-xhdpi") fun restoreCoverDark() { restore(true, false, typing); save("mnemosyne-restore-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1600dp-xhdpi") fun restoreCoverLight() { restore(false, false, typing); save("mnemosyne-restore-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun restoreInnerDark() { restore(true, true, typing); save("mnemosyne-restore-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun restoreInnerLight() { restore(false, true, typing); save("mnemosyne-restore-inner-light") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1600dp-xhdpi") fun restorePickCoverDark() { restore(true, false, RestoreUi(RestoreStep.PICK, remote)); save("mnemosyne-restore-pick-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1600dp-xhdpi") fun restoreDoneCoverLight() {
        restore(false, false, RestoreUi(RestoreStep.DONE, done = Restored(now, remote[0].id, 14, 9, 212, 5, 2, emptyList()))); save("mnemosyne-restore-done-cover-light")
    }
}
