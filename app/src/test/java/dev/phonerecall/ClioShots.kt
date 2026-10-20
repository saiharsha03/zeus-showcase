package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.brain.MindState
import dev.phonerecall.clio.ClioContent
import dev.phonerecall.clio.ClioLine
import dev.phonerecall.clio.Level
import dev.phonerecall.mind.HeartbeatView
import dev.phonerecall.mind.MindRows
import dev.phonerecall.mind.Wakeup
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
import java.time.ZoneId
import java.time.ZonedDateTime

/** Clio and Themis's heartbeat row to PNG (app/build/shots/clio-*.png), light and dark. Made-up lines. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClioShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val zone = ZoneId.of("America/New_York")
    private val t = ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun ago(min: Int) = t - min * 60_000L

    private val lines = listOf(
        ClioLine(ago(1), "heartbeat", Level.INFO, "heartbeat: nothing new"),
        ClioLine(ago(2), "netgate", Level.INFO, "request", "POST api.anthropic.com 200 · 1840 ms"),
        ClioLine(ago(3), "mind", Level.INFO, "athena turn", "trigger heartbeat · acted (1 tool call) · 4 cents · 2 new notes"),
        ClioLine(ago(3), "fates", Level.INFO, "Athena: proposed", "#212 Book the oil change (tier 2)"),
        ClioLine(ago(9), "workers", Level.WARN, "LemnosSyncWorker retry", "10012 ms"),
        ClioLine(ago(9), "lemnos", Level.ERROR, "SocketTimeoutException", "timeout at Gateway.obj:71"),
        ClioLine(ago(26), "senses", Level.INFO, "triage", "WhatsApp: chat message, medium (by rules)"),
        ClioLine(ago(41), "odyssey", Level.INFO, "drive ended", "score 92 · 23 min"),
        ClioLine(ago(64), "alarms", Level.INFO, "rang", "work"),
        ClioLine(ago(64), "music", Level.ERROR, "SpotifyError", "No active device at Music.play:301"),
        ClioLine(ago(60 * 20), "crash", Level.ERROR, "crash on main", "java.lang.IllegalStateException: Expected a list\n\tat dev.phonerecall.brief.BriefScreen.Day(BriefScreen.kt:88)"),
    )

    @Composable private fun Page(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    private fun clio(dark: Boolean) {
        compose.setContent { Page(dark) { ClioContent(lines, Space.gutterNarrow, "Copied 11 lines from the last 24 hours.", {}, {}, {}, {}, zone) } }
        save("clio-page-" + if (dark) "dark" else "light")
    }

    private fun h(hour: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, hour, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun dev(at: Long, event: String, detail: String = "", area: String = "device") = ClioLine(at, area, Level.INFO, event, detail)
    private val day = listOf(
        dev(h(0) - 3_600_000L, "charging started", "22%, AC"), dev(h(6, 40), "fully charged"), dev(h(7, 5), "charging stopped", "22% to 100%, 8 h 5 min"),
        dev(h(7, 5), "overnight charge", "11:00 PM to 7:05 AM, 8 h 5 min, 22% to 100%"),
        dev(h(7, 2), "screen on"), dev(h(7, 30), "screen off"), dev(h(8, 50), "screen on"), dev(h(9, 20), "screen off"),
        dev(h(11), "screen on"), dev(h(11, 45), "screen off"), dev(h(12, 30), "screen on"), dev(h(13, 10), "screen off"),
        dev(h(8), "drive started", area = "odyssey"), dev(h(8, 25), "drive ended", "score 92 · 25 min", "odyssey"),
        dev(h(8, 30), "arrived", "Office", "places"), dev(h(12, 15), "left", "Office", "places"),
        dev(h(12, 20), "drive started", area = "odyssey"), dev(h(12, 28), "drive ended", "score 88 · 8 min", "odyssey"),
        dev(h(12, 30), "arrived", "Wegmans", "places"), dev(h(13, 5), "left", "Wegmans", "places"),
        dev(h(9), "battery", "88%, 30°C"), dev(h(13, 30), "battery", "41%, 32°C"), dev(h(8, 2), "on mobile data"), dev(h(8, 31), "on Wi-Fi"),
        dev(h(7), "alarm snoozed"), dev(h(7, 10), "alarm dismissed"), dev(h(8, 1), "car connected"), dev(h(8, 26), "car disconnected"),
        dev(h(10), "first unlock", "2026-10-19 · 6:58 AM"),
        dev(h(10), "screen time", "2026-10-20 · 3 h 10 min · 54 pickups"), dev(h(10), "first unlock", "2026-10-20 · 7:02 AM"),
        dev(h(10), "app time", "2026-10-20 · YouTube 52 · WhatsApp 31 · Chrome 24 · Spotify 18 · Gmail 12 · other 33"),
    )

    private fun dayView(dark: Boolean) {
        compose.setContent { Page(dark) { ClioContent(day, Space.gutterNarrow, null, {}, {}, {}, {}, zone, now = t, onAllowCar = {}, startOnDay = true) } }
        save("clio-day-" + if (dark) "dark" else "light")
    }

    private fun heartbeat(dark: Boolean) {
        val m = MindState(
            ready = true, setUp = true, lastSync = t - 600_000L, wakeupsToday = listOf(Wakeup(t - 120_000L, "heartbeat: nothing to do", 4200, 1)),
            heartbeat = HeartbeatView(true, "Hourly, 7 AM to 11 PM · last: 2:00 PM nothing to do"),
        )
        compose.setContent { Page(dark) { Column(Modifier.statusBarsPadding().padding(horizontal = Space.gutterNarrow)) { MindRows(m, true, {}, {}, {}) } } }
        save("clio-heartbeat-row-" + if (dark) "dark" else "light")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi") fun pageDark() = clio(true)
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi") fun pageLight() = clio(false)
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi") fun dayDark() = dayView(true)
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi") fun dayLight() = dayView(false)
    @Test @Config(sdk = [35], qualifiers = "w384dp-h700dp-xhdpi") fun heartbeatRowDark() = heartbeat(true)
    @Test @Config(sdk = [35], qualifiers = "w384dp-h700dp-xhdpi") fun heartbeatRowLight() = heartbeat(false)
}
