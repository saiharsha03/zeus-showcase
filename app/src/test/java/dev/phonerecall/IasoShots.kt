package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.iaso.DayStat
import dev.phonerecall.iaso.FoodItem
import dev.phonerecall.iaso.HcStatus
import dev.phonerecall.iaso.HealthReport
import dev.phonerecall.iaso.HrPoint
import dev.phonerecall.iaso.IasoContent
import dev.phonerecall.iaso.IasoState
import dev.phonerecall.iaso.IasoWeekContent
import dev.phonerecall.iaso.Meal
import dev.phonerecall.iaso.MealConfirmContent
import dev.phonerecall.iaso.MealDraft
import dev.phonerecall.iaso.MealType
import dev.phonerecall.iaso.SleepNight
import dev.phonerecall.iaso.WeightPoint
import dev.phonerecall.iaso.Workout
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

/** Iaso rendered to app/build/shots/iaso-*.png: a watch-synced Tuesday, cover and inner, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IasoShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable private fun page(dark: Boolean, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2300dp-xhdpi")
    fun cover() { compose.setContent { page(true) { IasoContent(Sample.state, Sample.now, Space.gutterNarrow, false) } }; save("iaso-cover") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h2300dp-xhdpi")
    fun coverLight() { compose.setContent { page(false) { IasoContent(Sample.state, Sample.now, Space.gutterNarrow, false) } }; save("iaso-cover-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1400dp-xhdpi")
    fun inner() { compose.setContent { page(true) { IasoContent(Sample.state, Sample.now, Space.gutterWide, true) } }; save("iaso-inner") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1400dp-xhdpi")
    fun innerLight() { compose.setContent { page(false) { IasoContent(Sample.state, Sample.now, Space.gutterWide, true) } }; save("iaso-inner-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h900dp-xhdpi")
    fun notConnected() {
        compose.setContent { page(true) { IasoContent(IasoState(status = HcStatus.NOT_PERMITTED, loaded = true), Sample.now, Space.gutterNarrow, false) } }
        save("iaso-connect")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun confirm() {
        compose.setContent { page(true) { MealConfirmContent(null, Sample.draft, false, null, Space.gutterNarrow, {}, {}, {}) } }
        save("iaso-confirm")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1100dp-xhdpi")
    fun confirmLight() {
        compose.setContent { page(false) { MealConfirmContent(null, Sample.draft, false, null, Space.gutterNarrow, {}, {}, {}) } }
        save("iaso-confirm-light")
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h400dp-xhdpi")
    fun week() {
        compose.setContent { page(true) { Box(Modifier.padding(Space.gutterNarrow)) { IasoWeekContent(Sample.state, Sample.now) {} } } }
        save("iaso-week")
    }

    object Sample {
        private val zone = ZoneId.of("America/New_York")
        val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 20, 14, 30, 0, 0, zone)
        private val today = now.toLocalDate()
        private fun ms(daysAgo: Long, h: Int, m: Int = 0) = today.minusDays(daysAgo).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        val state = IasoState(
            status = HcStatus.READY, loaded = true,
            report = HealthReport(
                days = listOf(8_420L, 10_310L, 5_120L, 7_900L, 12_050L, 6_300L, 5_412L).mapIndexed { i, s ->
                    DayStat(today.minusDays(6L - i), steps = s, activeKcal = s / 22.0, exerciseMin = if (i == 6) 45 else 20, restingHr = listOf(60L, 59, 61, 58, 57, 59, 58)[i], avgHr = 74)
                },
                sleep = listOf(6, 5, 4, 3, 2, 1, 0).map { d ->
                    SleepNight(today.minusDays(d.toLong()), ms(d + 1L, 23, 40), ms(d.toLong(), 7, 5), asleepMin = listOf(402L, 455, 371, 430, 468, 389, 412)[6 - d], deepMin = 62, remMin = 88)
                },
                todayHr = listOf(62L, 66, 91, 104, 78, 72, 75, 70).mapIndexed { i, b -> HrPoint(ms(0, 7 + i), b) },
                workoutsToday = listOf(Workout("Walk", ms(0, 7, 30), 45)),
                weights = (0..40 step 2).map { d -> WeightPoint(ms(40L - d, 7), 67.2 - d * 0.04 + if (d % 4 == 0) 0.25 else -0.2) },
                readAt = now.toInstant().toEpochMilli(),
            ),
            meals = listOf(
                Meal(1, ms(0, 8, 10), MealType.BREAKFAST, "Coffee and two slices of toast", "2 slices, 1 cup", 520, 12.0, 64.0, 22.0, inHc = true, fromPhoto = true),
                Meal(2, ms(0, 12, 45), MealType.LUNCH, "Chicken rice bowl with yogurt", "1 plate, about 400 g", 720, 34.0, 88.0, 24.0, inHc = true, fromPhoto = true),
            ),
            water = mapOf(today to 4),
        )

        val draft = MealDraft(
            dish = "Chicken rice bowl with yogurt", portion = "1 plate, about 400 g", kcal = "720", protein = "34", carbs = "88", fat = "24",
            type = MealType.LUNCH, at = ms(0, 12, 45), fromPhoto = true, confidence = "medium", note = "Butter and oil are hard to see; could be 100 kcal more.",
            items = listOf(FoodItem("Chicken rice bowl", "350 g", 640), FoodItem("Yogurt", "50 g", 80)),
        )
    }
}
