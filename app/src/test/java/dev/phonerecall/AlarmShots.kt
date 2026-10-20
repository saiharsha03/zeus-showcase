package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.brain.AlarmCards
import dev.phonerecall.brain.AlarmMoments
import dev.phonerecall.brain.AlarmKind
import dev.phonerecall.brain.Bedtime
import dev.phonerecall.brain.Classified
import dev.phonerecall.brain.AlarmRules
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.home.PreviewBoard
import dev.phonerecall.ui.Space
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The evening alarm card on Olympus (app/build/shots/alarms-*.png). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 19)
    private val now = today.atTime(21, 0).atZone(zone)
    private val habit = AlarmRules.habit((1..28).map { today.minusDays(it.toLong()) }.filter { it.dayOfWeek.value <= 5 }.map { it.atTime(6, 45).atZone(zone).toInstant().toEpochMilli() }, now.toInstant().toEpochMilli(), zone)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun offer() { compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, alarm = AlarmCards.card(null, habit, now)) }; save("alarms-offer") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun set() {
        val next = dev.phonerecall.brain.NextAlarm.of(today.plusDays(1).atTime(6, 45).atZone(zone).toInstant().toEpochMilli(), "com.sec.android.app.clockpackage", "dev.phonerecall")
        compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, alarm = AlarmCards.card(next, habit, now)) }; save("alarms-set")
    }

    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun bedtime() {
        val tomorrow = today.plusDays(1)
        val flight = CalItem(1, 1, "Flight to SFO", ms(tomorrow, 8, 10), ms(tomorrow, 10, 0), false, EventCategory.TRAVEL, "Travel", null, null, false)
        val plan = Bedtime.plan(tomorrow, zone, dev.phonerecall.brain.NextAlarm(ms(tomorrow, 5), "Clock"), listOf(flight), usualMinute = 7 * 60)!!
        compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, alarm = AlarmCards.earlyDay(plan, zone, null)) }; save("alarms-bedtime")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun midnightIndia() {
        val c = Classified(AlarmKind.INDIA_MIDNIGHT, "x", people = listOf("Alex Kumar"))
        val m = AlarmMoments.india(c, ms(today, 14, 30), "https://wa.me/919999999999", "tel:+919999999999")
        compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, alarm = AlarmCards.moment(m)) }; save("alarms-india")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun dasara() {
        val m = AlarmMoments.festival("Dasara", ms(today.plusDays(1), 5), "Blue linen shirt", "Amma", "https://wa.me/919999999999")
        compose.setContent { PreviewBoard(1, Space.gutterNarrow, sample = false, alarm = AlarmCards.moment(m)) }; save("alarms-dasara")
    }
}
