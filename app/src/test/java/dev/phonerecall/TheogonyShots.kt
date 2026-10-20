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
import dev.phonerecall.theogony.ChatTurn
import dev.phonerecall.theogony.Interview
import dev.phonerecall.theogony.Phase
import dev.phonerecall.theogony.Question
import dev.phonerecall.theogony.SeedFact
import dev.phonerecall.theogony.SeedPath
import dev.phonerecall.theogony.SeedStep
import dev.phonerecall.theogony.SettingProposal
import dev.phonerecall.theogony.Status
import dev.phonerecall.theogony.StepStatus
import dev.phonerecall.theogony.TheogonyCalls
import dev.phonerecall.theogony.TheogonyContent
import dev.phonerecall.theogony.TheogonyState
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

/** Theogony to PNG (app/build/shots/theogony-*.png), on made-up data. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TheogonyShots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val facts = listOf(
        SeedFact(SeedPath.ROUTINES, "Office Tuesday to Thursday, remote Monday and Friday.", "iris", 0.8, "work_week"),
        SeedFact(SeedPath.ROUTINES, "Recurring: Team sync on Tuesday around 10 AM.", "phone", 0.8, "work_week"),
        SeedFact(SeedPath.ROUTINES, "Usually picks up the phone around 7 AM on weekdays, so likely wakes then.", "phone", 0.5, "wake", Status.INFERRED),
        SeedFact(SeedPath.PLANS, "Inter Miami away game, early November (not booked).", "iris", 0.8, "trips"),
        SeedFact(SeedPath.PLANS, "Japan trip idea for the spring, not booked.", "iris", 0.5, "trips", Status.INFERRED),
        SeedFact(SeedPath.PROFILE, "Leased the car: May 20, 2024.", "iris", 0.9, "milestones", Status.CONFIRMED, "2024-05-20"),
        SeedFact(SeedPath.PEOPLE, "Family: family (mother) and family (father), in India.", "people", 0.9, "people_family", Status.CONFIRMED),
        SeedFact(SeedPath.PEOPLE, "Inner circle: roommate and two other contacts.", "people", 0.6, "people_inner", Status.INFERRED),
        SeedFact(SeedPath.PROFILE, "Usual stores: Costco, Whole Foods and a farmers market.", "phone", 0.8, "spending"),
        SeedFact(SeedPath.PEOPLE, "Taylor is his manager; Jordan is his roommate.", "iris", 0.8, "people"),
        SeedFact(SeedPath.PREFERENCES, "Likes short plain messages and one question at a time.", "iris", 0.8, "preferences", Status.CONFIRMED),
        SeedFact(SeedPath.PREFERENCES, "Uses WhatsApp, Instagram and Spotify the most.", "phone", 0.8, "apps"),
    )

    private val seeding = TheogonyState(
        Phase.ASKING,
        listOf(
            SeedStep("Asking Iris", StepStatus.RUNNING),
            SeedStep("Reading your calendar", StepStatus.DONE, 9),
            SeedStep("Your app habits", StepStatus.DONE, 2),
            SeedStep("Your calls", StepStatus.SKIPPED),
            SeedStep("Contacts and birthdays", StepStatus.DONE, 4),
            SeedStep("Music", StepStatus.DONE, 2),
            SeedStep("Health, if shared", StepStatus.SKIPPED),
            SeedStep("Spending, in words", StepStatus.DONE, 3),
            SeedStep("Your closet", StepStatus.RUNNING),
            SeedStep("Places you go", StepStatus.WAITING),
        ),
        facts.filter { it.source == "phone" } + List(17) { SeedFact(SeedPath.SNAPSHOT, "Fact $it", "phone") },
        irisPending = true,
    )

    private val opening = "I think you're in the office Tuesday to Thursday, Taylor is your manager, and you shop at Costco and Whole Foods. Right?"
    private val sleep = Question("sleep", gap = "sleep", text = "And when do you usually go to bed?", chips = listOf("10:30 PM", "11 PM", "Midnight", "Later"))
    private val talked = listOf(
        ChatTurn("t1", ChatTurn.ATHENA, opening, Interview.OPENING, listOf(facts[0].text, facts[9].text, facts[8].text, facts[11].text)),
        ChatTurn("t2", ChatTurn.YOU, "3 of 4 are right", Interview.OPENING),
        ChatTurn("t3", ChatTurn.ATHENA, "When do you usually wake up on workdays?", "wake"),
        ChatTurn("t4", ChatTurn.YOU, "7 AM", "wake"),
        ChatTurn("ask-sleep", ChatTurn.ATHENA, sleep.text, "sleep"),
    )

    private val interview = TheogonyState(
        Phase.INTERVIEW, emptyList(), facts, sleep,
        asked = 2, total = 11, irisPending = false,
        settings = listOf(SettingProposal("set-add_reminder", "add_reminder", "Let Athena add Reminders without asking (each one still shows, with Undo)")),
        turns = talked, left = 9, canAskIris = true,
    )

    private val guesses = Question("b:x", text = "A few more I guessed. These look right?", bundle = listOf(facts[2].key, facts[4].key, facts[7].key))
    private val bundle = TheogonyState(
        Phase.INTERVIEW, emptyList(), facts, guesses, asked = 8, total = 11,
        turns = talked.dropLast(1) + listOf(
            ChatTurn("t5", ChatTurn.ATHENA, sleep.text, "sleep"), ChatTurn("t6", ChatTurn.YOU, "11 PM", "sleep"),
            ChatTurn("t7", ChatTurn.ATHENA, "Which festivals should I remind you about?", "festivals"), ChatTurn("t8", ChatTurn.YOU, "Diwali; Ugadi", "festivals"),
            ChatTurn("ask-b:x", ChatTurn.ATHENA, guesses.text, guesses.id, listOf(facts[2].text, facts[4].text, facts[7].text)),
        ),
        left = 3,
    )

    private val done = TheogonyState(
        Phase.DONE, emptyList(), facts,
        turns = listOf(
            ChatTurn("t1", ChatTurn.ATHENA, "Which festivals should I remind you about?", "festivals"), ChatTurn("t2", ChatTurn.YOU, "Diwali; Ugadi", "festivals"),
            ChatTurn("t3", ChatTurn.ATHENA, dev.phonerecall.theogony.TheogonyEngine.DONE_TEXT),
        ),
    )

    @Composable private fun Page(s: TheogonyState, dark: Boolean, wide: Boolean) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            TheogonyContent(s, if (wide) Space.gutterWide else Space.gutterNarrow, wide, TheogonyCalls(allow = if (s.phase == Phase.ASKING) ({}) else null))
        }
    }

    private fun shot(s: TheogonyState, dark: Boolean, wide: Boolean, name: String) { compose.setContent { Page(s, dark, wide) }; save(name) }

    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi") fun seedingCoverLight() = shot(seeding, false, false, "theogony-seeding-cover-light")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi") fun seedingCoverDark() = shot(seeding, true, false, "theogony-seeding-cover-dark")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1800dp-xhdpi") fun resultsCoverLight() = shot(done, false, false, "theogony-results-cover-light")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1800dp-xhdpi") fun resultsCoverDark() = shot(done, true, false, "theogony-results-cover-dark")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1800dp-xhdpi") fun interviewCoverLight() = shot(interview, false, false, "theogony-interview-cover-light")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1800dp-xhdpi") fun interviewCoverDark() = shot(interview, true, false, "theogony-interview-cover-dark")
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun interviewInnerLight() = shot(interview, false, true, "theogony-interview-inner-light")
    @Test @Config(sdk = [35], qualifiers = "w780dp-h1500dp-xhdpi") fun interviewInnerDark() = shot(interview, true, true, "theogony-interview-inner-dark")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi") fun bundleCoverLight() = shot(bundle, false, false, "theogony-bundle-cover-light")
    @Test @Config(sdk = [35], qualifiers = "w384dp-h1500dp-xhdpi") fun bundleCoverDark() = shot(bundle, true, false, "theogony-bundle-cover-dark")
}
