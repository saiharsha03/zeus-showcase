package dev.phonerecall

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.argo.ArgoCallbacks
import dev.phonerecall.argo.ArgoCards
import dev.phonerecall.argo.ArgoContent
import dev.phonerecall.argo.ArgoData
import dev.phonerecall.argo.ArgoFixtures
import dev.phonerecall.argo.ArgoNotice
import dev.phonerecall.argo.DestDay
import dev.phonerecall.argo.DestWeather
import dev.phonerecall.argo.LiveWords
import dev.phonerecall.argo.Trips
import dev.phonerecall.home.PreviewBoard
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space
import dev.phonerecall.widgets.WView
import dev.phonerecall.widgets.WidgetViews
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZonedDateTime

/** Argo to PNG (app/build/shots/argo-*.png): the countdown card, travel-mode Olympus, the trip widget, the Live Update, the screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArgoShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val F = ArgoFixtures
    private val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun z(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(F.zone)

    // The vague trip he told Athena about, now with the train booked but no hotel, three weeks out.
    private val idea = Trips.note(emptyList(), "Boston", "Mar 4-8", "seeing Jordan", F.now, F.zone).second.copy(legs = listOf(F.train, F.back), people = listOf("Jordan"))
    private val snow = DestWeather(F.at(2027, 3, 5, 7), 3.0, 3, listOf(DestDay("2027-03-05", 4.0, -2.0, 73, 80), DestDay("2027-03-06", 8.0, 2.0, 3, 10)))
    private val countdownAt = F.at(2027, 2, 11, 8, 20)
    private val travelAt = F.at(2027, 3, 4, 7, 50)
    private val awayAt = F.at(2027, 3, 5, 17, 10)
    private val gate = F.boston.copy(legs = listOf(F.train.copy(platform = "Platform 5"), F.back))

    private fun countdown() = ArgoCards.home(ArgoData(trips = listOf(idea), weather = mapOf(idea.id to snow)), countdownAt, F.zone)
    private fun travel() = ArgoCards.home(ArgoData(trips = listOf(gate)), travelAt, F.zone)
    private fun away() = ArgoCards.home(ArgoData(trips = listOf(F.boston), weather = mapOf(F.boston.id to snow)), awayAt, F.zone)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun countdownDark() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, trip = countdown(), at = z(countdownAt)) }; save("argo-countdown-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun countdownLight() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, dark = false, trip = countdown(), at = z(countdownAt)) }; save("argo-countdown-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun travelCoverDark() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, trip = travel(), at = z(travelAt)) }; save("argo-travel-olympus-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun travelCoverLight() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, dark = false, trip = travel(), at = z(travelAt)) }; save("argo-travel-olympus-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun travelInnerDark() { compose.setContent { PreviewBoard(2, Space.gutterWide, sample = true, trip = travel(), at = z(travelAt)) }; save("argo-travel-olympus-inner-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun tripModeDark() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = true, trip = away(), at = z(awayAt)) }; save("argo-tripmode-cover-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1300dp-xhdpi")
    fun tripModeInnerLight() { compose.setContent { PreviewBoard(2, Space.gutterWide, sample = true, dark = false, trip = away(), at = z(awayAt)) }; save("argo-tripmode-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun screenDark() {
        compose.setContent { AthenaTheme(true) { Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { ArgoContent(ArgoData(trips = listOf(idea), weather = mapOf(idea.id to snow)), countdownAt, F.zone, Space.gutterNarrow, false, ArgoCallbacks()) } } }
        save("argo-screen-dark")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun screenLight() {
        compose.setContent { AthenaTheme(false) { Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { ArgoContent(ArgoData(trips = listOf(gate)), travelAt, F.zone, Space.gutterNarrow, false, ArgoCallbacks()) } } }
        save("argo-screen-light")
    }

    private val question = dev.phonerecall.argo.Inquiry("q1", "k", "moneta", "Boston", "2027-03-05", "2027-03-05", hint = "A charge at AMTRAK .COM on Mon, Feb 8 looks like a travel booking",
        status = dev.phonerecall.argo.InqStatus.QUESTION, evidence = listOf("A charge at AMTRAK .COM on Mon, Feb 8 looks like a travel booking", "Iris didn't answer in 30 minutes"))

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun questionDark() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, trip = ArgoCards.home(ArgoData(inquiries = listOf(question)), countdownAt, F.zone), at = z(countdownAt)) }; save("argo-question-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun screenMoneyLight() {
        val priced = F.boston.copy(legs = listOf(F.train.copy(cents = 6_400, paid = true), F.back.copy(cents = 7_100, paid = false)), stays = listOf(F.hotel.copy(cents = 84_000, paid = true)), budgetCents = 150_000,
            firstBack = "First thing back: 9:00 AM Standup, Tue")
        compose.setContent { AthenaTheme(false) { Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { ArgoContent(ArgoData(trips = listOf(priced), inquiries = listOf(question.copy(id = "q2", place = "Pittsburgh", status = dev.phonerecall.argo.InqStatus.ASKED, hint = "Something on his calendar in Pittsburgh on Sat, Mar 27"))), countdownAt, F.zone, Space.gutterNarrow, false, ArgoCallbacks(), amounts = true) } } }
        save("argo-screen-money-light")
    }

    // ---- the trip widget and the Live Update, drawn as the launcher and the shade would ----

    private fun render(night: Boolean, wDp: Int, hDp: Int, view: (android.content.Context, FrameLayout) -> View, name: String, panel: Boolean = false) {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        val c = RuntimeEnvironment.getApplication()
        val d = c.resources.displayMetrics.density
        val host = FrameLayout(c)
        host.addView(view(c, host), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt(); val pad = (14 * d).toInt()
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (night) 0xFF2E2A26.toInt() else 0xFFC9C1B5.toInt())
        canvas.translate(pad.toFloat(), pad.toFloat())
        // The shade's card behind a notification template (the templates draw no background of their own).
        if (panel) canvas.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 24 * d, 24 * d, android.graphics.Paint().apply { color = if (night) 0xFF2B2A2E.toInt() else 0xFFF3F1F6.toInt() })
        host.draw(canvas)
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun widget(v: WView, night: Boolean, wDp: Int, hDp: Int, name: String) =
        render(night, wDp, hDp, { c, host -> WidgetViews.views(c, v, WidgetViews.pick(v.kind, wDp.toFloat(), hDp.toFloat()), plain = false).apply(c, host) }, name)

    @Test @Config(sdk = [35], qualifiers = "w800dp-h1400dp-xhdpi")
    fun widgets() {
        val t = travel()!!.widget!!
        val a = away()!!.widget!!
        for (night in listOf(true, false)) {
            val s = if (night) "dark" else "light"
            widget(t, night, 340, 300, "argo-widget-travel-$s")
            widget(a, night, 340, 300, "argo-widget-away-$s")
            widget(t, night, 660, 300, "argo-widget-travel-inner-$s")
        }
    }

    private fun notice(n: Notification, night: Boolean, wDp: Int, name: String) = render(night, wDp, 150, { c, host ->
        val b = Notification.Builder.recoverBuilder(c, n)
        (b.createBigContentView() ?: b.createContentView()).apply(c, host)
    }, name, panel = true)

    @Test @Config(sdk = [35], qualifiers = "w800dp-h1400dp-xhdpi")
    fun liveUpdate() {
        val c = RuntimeEnvironment.getApplication()
        val steps = listOf(
            "leave" to LiveWords.of(F.boston, F.train, F.at(2027, 3, 4, 7), F.zone),
            "depart" to LiveWords.of(gate, gate.legs.first(), travelAt, F.zone),
            "onway" to LiveWords.of(F.boston, F.train, F.at(2027, 3, 4, 12), F.zone),
        )
        for ((step, text) in steps) for (night in listOf(true, false)) {
            val s = if (night) "dark" else "light"
            val n = ArgoNotice.build(c, text, sound = false)
            notice(n, night, 384, "argo-live-$step-cover-$s")
            if (step == "depart") notice(n, night, 780, "argo-live-$step-inner-$s")
        }
    }
}
