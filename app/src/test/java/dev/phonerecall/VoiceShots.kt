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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.assist.AssistLine
import dev.phonerecall.assist.AssistSheet
import dev.phonerecall.assist.ScreenContext
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Step
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import dev.phonerecall.voice.LiveLedger
import dev.phonerecall.voice.LiveOffer
import dev.phonerecall.voice.LivePhase
import dev.phonerecall.voice.LiveSpendContent
import dev.phonerecall.voice.LiveUi
import dev.phonerecall.voice.SheetVoice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** The voice sheet and Live bar to PNG (app/build/shots/voice-*.png), on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoiceShots {
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
    private val timer = Proposal(
        id = 31, createdAt = now, expiresAt = now + 3_600_000, status = ProposalStatus.PENDING, title = "Timer for 10 minutes: Tea",
        steps = listOf(Step(ProposalKind.SET_TIMER, mapOf("seconds" to "600", "label" to "Tea"), "Timer for 10 minutes: Tea")),
    )

    private val liveUi = LiveUi(
        LivePhase.SPEAKING, seconds = 134, leftSeconds = 27 * 60, level = 0.12f,
        heard = "should I drive to Boston or take the train",
        said = "The train is about four hours and you'd skip the I-90 traffic on Friday. Want me to put a reminder on a card to book it tonight?",
    )

    @Composable private fun Sheet(dark: Boolean, talk: SheetVoice, lines: List<AssistLine>) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            AssistSheet(
                lines = lines, proposals = mapOf(31L to timer), working = null,
                screen = ScreenContext("com.google.android.apps.maps", "Maps", "Boston", false),
                useScreen = false, onUseScreen = {}, onAsk = {}, cb = FatesCallbacks(), onClose = {}, voice = false, talk = talk,
            )
        }
    }

    private val quickLines = listOf(
        AssistLine(true, "set a timer for 10 minutes for tea"),
        AssistLine(false, timer.title, proposalId = 31),
        AssistLine(false, "On a card for you: Timer for 10 minutes: Tea."),
        AssistLine(true, "let's talk"),
    )

    private fun live(dark: Boolean) = compose.setContent { Sheet(dark, SheetVoice(live = liveUi), quickLines) }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun liveCoverDark() { live(true); save("voice-live-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun liveCoverLight() { live(false); save("voice-live-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi") fun liveInnerDark() { live(true); save("voice-live-inner-dark") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi") fun liveInnerLight() { live(false); save("voice-live-inner-light") }

    private fun offer(dark: Boolean) = compose.setContent { Sheet(dark, SheetVoice(offer = LiveOffer(30, 30)), quickLines) }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun offerCoverDark() { offer(true); save("voice-offer-cover-dark") }
    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi") fun offerCoverLight() { offer(false); save("voice-offer-cover-light") }
    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi") fun offerInnerDark() { offer(true); save("voice-offer-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun drivingCoverDark() { compose.setContent { Sheet(true, SheetVoice(live = liveUi, driving = true), quickLines) }; save("voice-driving-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h860dp-xhdpi")
    fun endedCoverLight() {
        compose.setContent { Sheet(false, SheetVoice(live = LiveUi(LivePhase.ENDED, seconds = 312, problem = "Today's Live minutes are used.")), quickLines) }
        save("voice-ended-cover-light")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h500dp-xhdpi")
    fun themisCoverDark() {
        val d = LocalDate.now()
        compose.setContent {
            AthenaTheme(true) {
                Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
                    Column(Modifier.padding(horizontal = Space.gutterNarrow, vertical = Space.l)) { LiveSpendContent(LiveLedger(d.toString()).plus(14 * 60, 5 * 60, d)) {} }
                }
            }
        }
        save("voice-themis-cover-dark")
    }
}
