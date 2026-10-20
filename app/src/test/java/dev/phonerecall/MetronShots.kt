package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Dp
import dev.phonerecall.brain.SpendState
import dev.phonerecall.home.SampleData
import dev.phonerecall.lemnos.TaskActions
import dev.phonerecall.lemnos.liveItems
import dev.phonerecall.metron.AgentUsage
import dev.phonerecall.metron.MetronBudget
import dev.phonerecall.metron.MetronContent
import dev.phonerecall.metron.UsageSnapshot
import dev.phonerecall.metron.UsageWindow
import dev.phonerecall.mind.Metis
import dev.phonerecall.mind.PantheonContent
import dev.phonerecall.mind.Protege
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import dev.phonerecall.voice.LiveLedger
import dev.phonerecall.widgets.WidgetContent
import dev.phonerecall.widgets.WidgetFixtures
import dev.phonerecall.widgets.WidgetViews
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZonedDateTime

/** Metron to PNG (app/build/shots/metron-*.png): Pantheon's cards with bars, the Metron page, the widget. Made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MetronShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val now = ZonedDateTime.now()
    private val s = now.toEpochSecond()
    private val usage = UsageSnapshot(listOf(
        AgentUsage("iris", "Iris", "Opus 5.5", listOf(UsageWindow("5h", 83.5, s + 2 * 3600 + 900), UsageWindow("7d", 46.0, s + 3 * 86_400), UsageWindow("7d Opus", 21.0, s + 3 * 86_400)), s - 90),
        AgentUsage("atlas", "Atlas", "gpt-6.1-sol", listOf(UsageWindow("5h", 96.0, s + 40 * 60), UsageWindow("7d", 55.0, s + 5 * 86_400)), s - 300),
    ), System.currentTimeMillis() - 60_000)
    private val circle = mapOf(Metis.THREAD to 420L, Protege.PERSEUS.agentName to 610L, Protege.ODYSSEUS.agentName to 230L, Protege.ANNABETH.agentName to 900L, Protege.DIOMEDES.agentName to 35L)
    private val spend = SpendState(java.time.YearMonth.from(now).toString(), spentMicros = 61_400_000, mindMicros = 38_100_000, activeSeconds = 9_840, advisorMicros = 2_200_000)
    private val budget = MetronBudget.of(spend, circle, LiveLedger(now.toLocalDate().toString(), inSeconds = 74 * 60, outSeconds = 31 * 60), now)

    @Composable private fun Page(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Composable private fun Pantheon(gutter: Dp) =
        PantheonContent(gutter, circle, SampleData.workers, live = { liveItems(WidgetFixtures.lemnos, null, {}, TaskActions(), null, usage = usage) })

    @Composable private fun Metron(gutter: Dp, wide: Boolean) = MetronContent(usage, budget, true, wide, gutter, now, {}, {})

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun pantheonCoverDark() { compose.setContent { Page(true) { Pantheon(Space.gutterNarrow) } }; save("metron-pantheon-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun pantheonCoverLight() { compose.setContent { Page(false) { Pantheon(Space.gutterNarrow) } }; save("metron-pantheon-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1900dp-xhdpi")
    fun metronCoverDark() { compose.setContent { Page(true) { Metron(Space.gutterNarrow, false) } }; save("metron-page-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1900dp-xhdpi")
    fun metronCoverLight() { compose.setContent { Page(false) { Metron(Space.gutterNarrow, false) } }; save("metron-page-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1250dp-xhdpi")
    fun metronInnerDark() { compose.setContent { Page(true) { Metron(Space.gutterWide, true) } }; save("metron-page-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1250dp-xhdpi")
    fun metronInnerLight() { compose.setContent { Page(false) { Metron(Space.gutterWide, true) } }; save("metron-page-inner-light") }

    /** The widget, applied as a launcher would, at two sizes, light and dark. */
    @Test @Config(sdk = [35], qualifiers = "w800dp-h1400dp-xhdpi")
    fun widget() {
        val v = WidgetContent.metron(usage, budget, now)
        for ((w, h) in listOf(170 to 110, 250 to 300)) for (night in listOf(false, true)) {
            RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val c = RuntimeEnvironment.getApplication()
            val d = c.resources.displayMetrics.density
            val rv = WidgetViews.views(c, v, WidgetViews.pick(v.kind, w.toFloat(), h.toFloat()), plain = false)
            val host = FrameLayout(c)
            host.addView(rv.apply(c, host), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val pw = (w * d).toInt(); val ph = (h * d).toInt(); val pad = (14 * d).toInt()
            host.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ph, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, pw, ph)
            val bmp = Bitmap.createBitmap(pw + 2 * pad, ph + 2 * pad, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(if (night) 0xFF2E2A26.toInt() else 0xFFC9C1B5.toInt())
            canvas.translate(pad.toFloat(), pad.toFloat())
            host.draw(canvas)
            File(dir, "metron-widget-${w}x$h-${if (night) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
