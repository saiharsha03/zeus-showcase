package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.widget.FrameLayout
import dev.phonerecall.argo.ArgoCards
import dev.phonerecall.argo.ArgoData
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.hestia.FakeHomeBackend
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardEngine
import dev.phonerecall.home.SampleData
import dev.phonerecall.music.NowPlaying
import dev.phonerecall.widgets.SectionFixtures
import dev.phonerecall.widgets.WView
import dev.phonerecall.widgets.WidgetContent
import dev.phonerecall.widgets.WidgetFixtures
import dev.phonerecall.widgets.WidgetSafety
import dev.phonerecall.widgets.WidgetViews
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every section widget at two sizes, light and dark, to app/build/shots/widget-*.png, plus one
 * contact sheet (widgets-contact.png). The RemoteViews are applied exactly as a launcher would.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h1400dp-xhdpi")
class RenderWidgets {
    private val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
    private val F = WidgetFixtures

    private fun shot(v: WView, wDp: Int, hDp: Int, night: Boolean): Bitmap {
        RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
        val c = RuntimeEnvironment.getApplication()
        val d = c.resources.displayMetrics.density
        val rv = WidgetViews.views(c, v, WidgetViews.pick(v.kind, wDp.toFloat(), hDp.toFloat()), plain = false)
        val host = FrameLayout(c)
        host.addView(rv.apply(c, host), FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt(); val pad = (14 * d).toInt()
        host.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        host.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // A plain stand-in for the wallpaper, so the widget's own edge shows.
        canvas.drawColor(if (night) 0xFF2E2A26.toInt() else 0xFFC9C1B5.toInt())
        canvas.translate(pad.toFloat(), pad.toFloat())
        host.draw(canvas)
        return bmp
    }

    private fun views(): List<Triple<String, WView, List<Pair<Int, Int>>>> {
        val now = F.now
        val root = File(System.getProperty("user.dir"), "../_data/closet")
        val closet = if (File(root, "closet-index.csv").isFile) ClosetSource.parse(root) else ClosetSnapshot(loaded = true)
        val inputs = BoardInputs(
            now, CalendarSnapshot(true, F.events, true), F.availability, true, HomePrefs(), Learned(), F.tasks, closet,
            SampleData.generated(now), true,
        )
        val board = CardEngine.build(inputs)
        val agent = WidgetContent.agentTasks(F.lemnos.tasks, now)
        return listOf(
            Triple("today", WidgetContent.today(now, board.cards, F.events, F.availability, F.tasks), listOf(340 to 520, 660 to 520)),
            Triple("chronos", WidgetContent.chronos(now, F.events, F.availability), listOf(250 to 150, 340 to 300)),
            Triple("agora", WidgetContent.agora(now, F.tasks, agent), listOf(250 to 150, 340 to 300)),
            Triple("delphi", WidgetContent.delphi(), listOf(340 to 64, 340 to 110)),
            Triple("hermes", WidgetContent.hermes(), listOf(340 to 64, 340 to 110)),
            Triple("venus", WidgetContent.venus(CardEngine.wardrobe(inputs), closet.imported), listOf(250 to 150, 340 to 300)),
            Triple("music", WidgetContent.music(NowPlaying("Clocks", "Coldplay", true)), listOf(250 to 72, 250 to 160)),
            Triple("lemnos", WidgetContent.lemnos(F.lemnos, now), listOf(170 to 110, 200 to 190)),
            Triple("cerberus", WidgetContent.cerberus(F.lemnos, now), listOf(170 to 110, 250 to 300)),
            Triple("pantheon", WidgetContent.pantheon(F.lemnos, now), listOf(170 to 110, 250 to 190)),
        ) + sections().map { (name, v) -> Triple(name, WidgetSafety.seal(v), listOf(170 to 60, 200 to 110, 250 to 190)) }
    }

    /** The newer sections, sealed as WidgetBus draws them: small, short and medium. */
    private fun sections(): List<Pair<String, WView>> {
        val S = SectionFixtures
        val now = F.now
        return listOf(
            "trips" to ArgoCards.widget(S.argo, F.ms, F.zone),
            "trips-travel" to ArgoCards.widget(ArgoData(listOf(S.flyingToday)), S.at(12), F.zone),
            "drive" to WidgetContent.drive(S.odyssey, now),
            "money" to WidgetContent.money(S.txns, listOf(S.netflix), emptySet(), now),
            "hearth" to WidgetContent.hearth(FakeHomeBackend.sample(), listOf("d1", "d9", "d4", "d3")),
            "health" to WidgetContent.health(S.health, now),
            "brief" to WidgetContent.brief(S.brief, now),
            "room" to WidgetContent.room(S.room, now),
        )
    }

    @Test fun widgets() {
        val sheet = ArrayList<Pair<String, List<Bitmap>>>()
        for ((name, v, sizes) in views()) {
            val row = ArrayList<Bitmap>()
            for ((w, h) in sizes) for (night in listOf(false, true)) {
                val bmp = shot(v, w, h, night)
                File(dir, "widget-$name-${w}x$h-${if (night) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                row += bmp
            }
            sheet += name to row
        }
        contact(sheet)
        assertTrue(File(dir, "widgets-contact.png").isFile)
    }

    /** One image of every shot: a row per widget, scaled to 60%, each row labelled. */
    private fun contact(rows: List<Pair<String, List<Bitmap>>>) {
        val scale = 0.6f
        val gap = 24
        val label = 44
        val cols = rows.maxOf { it.second.size }
        val colW = (0 until cols).map { i -> rows.maxOf { (it.second.getOrNull(i)?.width ?: 0) * scale }.toInt() }
        val rowH = rows.map { r -> (r.second.maxOf { it.height } * scale).toInt() + label }
        val out = Bitmap.createBitmap(colW.sum() + gap * (cols + 1), rowH.sum() + gap * (rows.size + 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFEFE9E0.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1814.toInt(); textSize = 26f; isFakeBoldText = true }
        val img = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        var y = gap
        for ((ri, r) in rows.withIndex()) {
            c.drawText(r.first, gap.toFloat(), y + 30f, text)
            var x = gap
            for ((i, b) in r.second.withIndex()) {
                c.drawBitmap(b, null, Rect(x, y + label, x + (b.width * scale).toInt(), y + label + (b.height * scale).toInt()), img)
                x += colW[i] + gap
            }
            y += rowH[ri] + gap
        }
        File(dir, "widgets-contact.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
