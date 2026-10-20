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
import androidx.compose.ui.unit.Dp
import dev.phonerecall.data.Project
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskContext
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.tasks.PraxisCalls
import dev.phonerecall.tasks.Situation
import dev.phonerecall.tasks.TasksContent
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
import java.time.LocalDate
import java.time.ZoneId

/** Praxis's GTD flows to PNG (app/build/shots/praxis-*.png): clarify one by one, Next with contexts, the weekly review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PraxisShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val zone = ZoneId.systemDefault()
    private fun day(plus: Long) = LocalDate.now().plusDays(plus).atStartOfDay(zone).toInstant().toEpochMilli()
    private var next = 1L
    private fun t(title: String, list: TaskList, context: TaskContext? = null, project: Long? = null, who: String? = null, followUp: Long? = null, due: Long? = null, quick: Boolean = false) =
        Task(next++, title, list, project, due, false, next.toDouble(), quick, null, false, context, who, followUp)

    private fun snap(inboxFirst: String) = TaskSnapshot(
        listOf(
            t(inboxFirst, TaskList.INBOX),
            t("Wifi password at Amma's", TaskList.INBOX),
            t("Plan the Inter Miami away game", TaskList.INBOX),
            t("Fall foliage drive, Allegheny?", TaskList.INBOX),
            t("Text Jordan about the grocery run", TaskList.INBOX),
            t("Reply to Taylor about the weekly report", TaskList.NEXT, TaskContext.COMPUTER, due = day(0)),
            t("Ask facilities about the parking pass", TaskList.NEXT, TaskContext.OFFICE),
            t("Call the dentist about the cleaning", TaskList.NEXT, TaskContext.CALLS),
            t("Drop off the package return", TaskList.NEXT, TaskContext.ERRANDS),
            t("Check the drive time to the stadium", TaskList.NEXT, TaskContext.COMPUTER, project = 1),
            t("Do the laundry", TaskList.NEXT, TaskContext.HOME),
            t("Think about the Diwali gifts", TaskList.NEXT, TaskContext.ANYWHERE),
            t("Pick up the dry cleaning", TaskList.NEXT, TaskContext.ERRANDS),
            t("Report numbers", TaskList.WAITING, who = "Taylor", followUp = day(0)),
            t("Sofa delivery", TaskList.WAITING, who = "Wayfair", followUp = day(4)),
            t("Car wash membership renews", TaskList.SCHEDULED, due = day(3)),
            t("Japan trip ideas", TaskList.SOMEDAY),
        ),
        listOf(Project(1, "Inter Miami away game", false), Project(2, "Garage clean-out", false)),
        loaded = true,
    )

    @Composable private fun Page(dark: Boolean, s: TaskSnapshot, at: Situation, gutter: Dp, wide: Boolean, view: String = TaskList.INBOX.name, clarify: Boolean = false, review: Boolean = false) =
        AthenaTheme(dark) {
            Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
                TasksContent(s, at, wide, gutter, PraxisCalls(), startView = view, startClarify = clarify, startReview = review)
            }
        }

    private val office = Situation(officeDay = true, atOffice = true, atHome = false, hour = 10)
    private val driving = Situation(officeDay = true, atHome = false, driving = true, hour = 8)

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun clarifyNarrowLight() { compose.setContent { Page(false, snap("Renew the car registration online"), office, Space.gutterNarrow, false, clarify = true) }; save("praxis-clarify-narrow-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun clarifyNarrowDark() { compose.setContent { Page(true, snap("Waiting on Taylor for the report numbers"), office, Space.gutterNarrow, false, clarify = true) }; save("praxis-clarify-narrow-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun clarifyWideLight() { compose.setContent { Page(false, snap("Plan Amma's visit in the spring"), office, Space.gutterWide, true, clarify = true) }; save("praxis-clarify-wide-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun clarifyWideDark() { compose.setContent { Page(true, snap("Call the landlord tomorrow about the heat"), office, Space.gutterWide, true, clarify = true) }; save("praxis-clarify-wide-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun nextNarrowLight() { compose.setContent { Page(false, snap("Renew the car registration online"), office, Space.gutterNarrow, false, view = TaskList.NEXT.name) }; save("praxis-next-narrow-light") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun nextNarrowDark() { compose.setContent { Page(true, snap("Renew the car registration online"), driving, Space.gutterNarrow, false, view = TaskList.NEXT.name) }; save("praxis-next-narrow-dark") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun nextWideLight() { compose.setContent { Page(false, snap("Renew the car registration online"), office, Space.gutterWide, true, view = TaskList.NEXT.name) }; save("praxis-next-wide-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun nextWideDark() { compose.setContent { Page(true, snap("Renew the car registration online"), Situation(), Space.gutterWide, true, view = TaskList.NEXT.name) }; save("praxis-next-wide-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun inboxNarrowLight() { compose.setContent { Page(false, snap("Renew the car registration online"), office, Space.gutterNarrow, false) }; save("praxis-inbox-narrow-light") }

    @Test @Config(sdk = [35], qualifiers = "w780dp-h900dp-xhdpi")
    fun reviewWideDark() { compose.setContent { Page(true, snap("Renew the car registration online"), office, Space.gutterWide, true, review = true) }; save("praxis-review-wide-dark") }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1000dp-xhdpi")
    fun projectsNarrowLight() { compose.setContent { Page(false, snap("Renew the car registration online"), office, Space.gutterNarrow, false, view = "PROJECTS") }; save("praxis-projects-narrow-light") }
}
