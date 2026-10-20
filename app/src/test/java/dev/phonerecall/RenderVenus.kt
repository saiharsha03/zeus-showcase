package dev.phonerecall

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.phonerecall.closet.DayContext
import dev.phonerecall.closet.DayWeather
import dev.phonerecall.closet.Outfits
import dev.phonerecall.closet.Packing
import dev.phonerecall.closet.Trip
import dev.phonerecall.closet.VenusPrefs
import dev.phonerecall.closet.VenusPreview
import dev.phonerecall.closet.VenusSection
import dev.phonerecall.closet.VenusStore
import dev.phonerecall.closet.Warmth
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.data.DayType
import dev.phonerecall.ui.Space
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

/** Venus's Today, Packing and Insights from the laptop copy of the closet (Athena/_data/closet), to app/build/shots. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderVenus {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.of(2026, 10, 19)
    private val snap by lazy { org.junit.Assume.assumeTrue("the closet is laptop-only (_data, not in git)", File(System.getProperty("user.dir"), "../_data/closet").isDirectory); ClosetSource.parse(File(System.getProperty("user.dir"), "../_data/closet")).also { assertTrue("closet parsed", it.imported) } }
    private val prefs by lazy {
        // Nine days of logged outfits so Insights has something to say.
        var p = VenusPrefs(favorites = setOf("navy-polo"))
        for (d in 9L downTo 1L) {
            val day = today.minusDays(d)
            val plan = Outfits.plan(snap, DayContext(day, if (day.dayOfWeek.value in 2..4) DayType.WORKDAY else DayType.REMOTE), p)!!
            p = VenusStore.wore(p, plan.main.ids, day.toEpochDay())
        }
        p
    }

    private fun shot(
        name: String, section: VenusSection, wide: Boolean, dark: Boolean,
        ctx: DayContext = DayContext(today, DayType.WORKDAY, weather = DayWeather(feelsLikeC = 7.0, rainChance = 20)),
    ) {
        val plan = Outfits.plan(snap, ctx, prefs)
        println("$name: ${plan?.main?.title} / ${plan?.main?.reason} / alts ${plan?.alternates?.map { it.title }}")
        val packs = listOf(Packing.pack(snap, Trip(today.plusDays(4), today.plusDays(7), "Austin"), Warmth.MILD, prefs))
        compose.setContent { VenusPreview(section, snap, plan, prefs, packs, wide, if (wide) Space.gutterWide else Space.gutterNarrow, dark, today) }
        // Photos decode on IO; give them a moment, letting Compose apply each result.
        repeat(30) { Thread.sleep(150); compose.waitForIdle() }
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun coverDark() = shot("venus-cover-dark", VenusSection.TODAY, wide = false, dark = true)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun coverLight() = shot("venus-cover-light", VenusSection.TODAY, wide = false, dark = false)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun innerDark() = shot("venus-inner-dark", VenusSection.TODAY, wide = true, dark = true)

    @Test @Config(sdk = [35], qualifiers = "w780dp-h1100dp-xhdpi")
    fun innerLight() = shot("venus-inner-light", VenusSection.TODAY, wide = true, dark = false)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1400dp-xhdpi")
    fun festival() = shot("venus-festival", VenusSection.TODAY, wide = false, dark = true, DayContext(LocalDate.of(2026, 10, 20), DayType.WORKDAY, festival = "Dussehra"))

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun packing() = shot("venus-packing", VenusSection.PACKING, wide = false, dark = true)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi")
    fun insights() = shot("venus-insights", VenusSection.INSIGHTS, wide = false, dark = false)
}
