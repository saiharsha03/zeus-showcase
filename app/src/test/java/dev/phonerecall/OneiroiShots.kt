package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.phonerecall.oneiroi.DreamNote
import dev.phonerecall.oneiroi.Fact
import dev.phonerecall.oneiroi.FactLine
import dev.phonerecall.oneiroi.MemoryAnswer
import dev.phonerecall.oneiroi.MemoryAnswerCard
import dev.phonerecall.oneiroi.MemoryCare
import dev.phonerecall.oneiroi.MemoryCareCalls
import dev.phonerecall.oneiroi.MemoryCareContent
import dev.phonerecall.oneiroi.Nudge
import dev.phonerecall.oneiroi.Ref
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.Type
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Oneiroi to PNG (app/build/shots/oneiroi-*.png): Memory care and the /memory answer card, on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OneiroiShots {
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

    private val state = MemoryCare(
        loaded = true,
        live = listOf(
            Fact(14, "car", "car:model", "His car is a Mazda CX-5, called M3", "confirmed", "Delphi", now - 2 * h, supersedes = 6),
            Fact(9, "car", "car:service", "Oil change due every 5,000 miles", "observed", "long job"),
            Fact(12, "home", "home:city", "He lives in Buffalo, NY", "confirmed", "memory /profile/owner.md"),
            Fact(15, "money", "money:fuel", "monthly fuel = sum(Moneta Fuel, month)", "inferred", "Delphi", kind = "derived"),
            Fact(11, "routine", "routine:office", "Office Tuesday to Thursday, remote otherwise", "confirmed", "memory /profile/owner.md"),
            Fact(16, "patterns", "patterns:1", "Books the car service the week after a long drive", "inferred", "dream", kind = "lesson"),
        ),
        replaced = 5,
        refs = listOf(Ref("M3", "his Mazda"), Ref("Amma", "his mother"), Ref("the den", "his home office")),
        nudges = listOf(
            Nudge("p1", Nudge.DROPPED, "Athena said she'd check flight prices tonight and get back to you. Nothing came of it.", "I'll check prices tonight and get back to you.", now - 30 * h),
            Nudge("f1", Nudge.BACKFILL, "Remember that his gym is closed on Sundays.", "Remember that my gym is closed on Sundays.", now - 20 * h),
        ),
        dreams = listOf(DreamNote(now - 5 * h, listOf("#3 was a repeat of #12", "Merged #6, #8 into #14", "#7 was stale; now #9", "New pattern #16: Books the car service the week after a long drive"), 1_870)),
        irisQueued = 2,
        reflected = 23,
        lastSweep = now - 26 * h,
        spentTonightMicros = 1_870,
    )

    @Composable private fun Page(dark: Boolean, wide: Boolean) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            MemoryCareContent(state, if (wide) Space.gutterWide else Space.gutterNarrow, wide, MemoryCareCalls())
        }
    }

    private val answer = MemoryAnswer(
        "What I know about M3",
        listOf(
            FactLine(14, "His car is a Mazda CX-5, called M3", "confirmed", "Delphi"),
            FactLine(9, "Oil change due every 5,000 miles", "observed", "long job"),
            FactLine(16, "Books the car service the week after a long drive", "inferred", "dream"),
        ),
        "Say \"#14 is wrong, it's ...\" to fix one, or \"forget #14\".",
    )

    @Composable private fun Answer(dark: Boolean, wide: Boolean) = AthenaTheme(dark) {
        val c = Athena.colors
        val g = if (wide) Space.gutterWide else Space.gutterNarrow
        Box(Modifier.fillMaxSize().background(c.surface)) {
            Column(Modifier.padding(horizontal = g, vertical = Space.xl), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Line("What do you know about M3?", Type.body, c.ink, Modifier.clip(RoundedCornerShape(Radius.blockSmall)).background(c.raised).padding(horizontal = Space.m, vertical = Space.s))
                }
                MemoryAnswerCard(answer, Modifier.widthIn(max = 620.dp))
            }
        }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2400dp-xhdpi")
    fun careCoverDark() { compose.setContent { Page(true, false) }; save("oneiroi-care-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2400dp-xhdpi")
    fun careCoverLight() { compose.setContent { Page(false, false) }; save("oneiroi-care-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun careInnerDark() { compose.setContent { Page(true, true) }; save("oneiroi-care-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi")
    fun careInnerLight() { compose.setContent { Page(false, true) }; save("oneiroi-care-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h800dp-xhdpi")
    fun answerCoverDark() { compose.setContent { Answer(true, false) }; save("oneiroi-answer-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h800dp-xhdpi")
    fun answerCoverLight() { compose.setContent { Answer(false, false) }; save("oneiroi-answer-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h800dp-xhdpi")
    fun answerInnerDark() { compose.setContent { Answer(true, true) }; save("oneiroi-answer-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h800dp-xhdpi")
    fun answerInnerLight() { compose.setContent { Answer(false, true) }; save("oneiroi-answer-inner-light") }
}
