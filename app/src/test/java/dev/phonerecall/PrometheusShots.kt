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
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.prometheus.ChangeRequest
import dev.phonerecall.prometheus.ChangeStatus
import dev.phonerecall.prometheus.PromLog
import dev.phonerecall.prometheus.PromRun
import dev.phonerecall.prometheus.PrometheusCalls
import dev.phonerecall.prometheus.PrometheusContent
import dev.phonerecall.prometheus.PrometheusState
import dev.phonerecall.prometheus.Quote
import dev.phonerecall.prometheus.Tweak
import dev.phonerecall.prometheus.TweakKind
import dev.phonerecall.prometheus.TweakStatus
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

/** Prometheus to PNG (app/build/shots/prometheus-*.png), on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PrometheusShots {
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

    private val state = PrometheusState(
        loaded = true,
        tweaks = listOf(
            Tweak(5, 3, TweakKind.CARD_WEIGHT, "MUSIC", "-5", "0", "You opened Music 6% of the 31 times it showed. Move it down.", createdAt = now),
            Tweak(4, 3, TweakKind.AUTO_APPROVE, "add_reminder", "on", "off", "You confirmed all 9 reminder proposals and turned none down.", createdAt = now),
            Tweak(6, 4, TweakKind.NOTE, "athena", "On weekday mornings, answer in one sentence.", "Keep replies short.", "Three corrections asked for shorter morning answers.", createdAt = now, source = "Sonnet 5.5"),
            Tweak(2, 1, TweakKind.CARD_WEIGHT, "BRIEF", "5", "0", "", TweakStatus.APPLIED, now - 30 * h, now - 29 * h),
        ),
        runs = listOf(PromRun(3, now - 2 * 60_000L, "tuning", 2, 0, "2 suggestions")),
        log = listOf(
            PromLog(now - 60_000L, "suggested", "#5 Card weight: Music 0 to -5"),
            PromLog(now - 61_000L, "run", "Tuning run #3 started by the owner"),
            PromLog(now - 29 * h, "approved", "#2 Card weight: Brief 0 to +5"),
            PromLog(now - 30 * h, "verified", "p8c1: same signing key as the installed app, version 1.5.1"),
        ),
        notes = listOf("Keep replies short.", "Call the office days office days, not in-person days."),
        quote = Quote(BrainModel.SONNET, 820, 1200, 13_640, now, ""),
        changes = listOf(
            ChangeRequest(
                "p8c1", "The Tasks card should show the two-minute jobs first in the morning.", ChangeStatus.READY, now - 26 * h, now - 20 * h,
                summary = "Two-minute jobs now sort ahead of dated tasks before noon. CardEngine.kt and its test.",
                filesChanged = 2, insertions = 41, deletions = 6, testsPassed = 418, testsFailed = 0, testsLine = "418 passed, 0 failed (testDebugUnitTest)",
                versionName = "1.5.1", versionCode = 131, apkSha256 = "4f2a9c11d0e7aa", apkBytes = 31_400_000,
            ),
            ChangeRequest("p2b7", "A widget for the next Fates proposal.", ChangeStatus.QUEUED, now - 3 * h, now - 3 * h),
        ),
        verified = setOf("p8c1"),
    )

    @Composable private fun Page(dark: Boolean, wide: Boolean) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            PrometheusContent(if (wide) Space.gutterWide else Space.gutterNarrow, wide, state, PrometheusCalls())
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2700dp-xhdpi")
    fun prometheusCoverDark() { compose.setContent { Page(true, false) }; save("prometheus-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2700dp-xhdpi")
    fun prometheusCoverLight() { compose.setContent { Page(false, false) }; save("prometheus-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun prometheusInnerDark() { compose.setContent { Page(true, true) }; save("prometheus-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun prometheusInnerLight() { compose.setContent { Page(false, true) }; save("prometheus-inner-light") }
}
