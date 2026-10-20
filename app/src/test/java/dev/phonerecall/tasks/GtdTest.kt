package dev.phonerecall.tasks

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.phonerecall.brain.EngineRig
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.Check
import dev.phonerecall.brain.Validate
import dev.phonerecall.data.Project
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskContext
import dev.phonerecall.data.TaskList
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.data.TaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

private val ZONE = ZoneId.of("America/New_York")
private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) = ZonedDateTime.of(y, m, d, h, min, 0, 0, ZONE)
private fun task(id: Long, title: String, list: TaskList, project: Long? = null, context: TaskContext? = null, who: String? = null, followUp: Long? = null, due: Long? = null) =
    Task(id, title, list, project, due, false, 0.0, false, null, false, context, who, followUp)

class GtdTest {
    // Tuesday.
    private val today = LocalDate.of(2026, 10, 20)

    @Test fun clarifySuggestions() {
        fun pick(t: String) = Gtd.suggest(t, today)
        assertEquals(Clarify.REFERENCE, pick("Wifi password at Amma's").choice)
        assertEquals(Clarify.SOMEDAY, pick("Fall foliage drive, Allegheny?").choice)
        assertEquals(Clarify.SOMEDAY, pick("Japan trip ideas").choice)
        assertEquals(Clarify.PROJECT, pick("Plan the Inter Miami away game").choice)
        assertEquals(Clarify.DO_NOW, pick("Text Jordan about the grocery run").choice)
        assertEquals(Suggestion(Clarify.NEXT, TaskContext.CALLS), pick("Call the dentist"))
        assertEquals(Suggestion(Clarify.NEXT, TaskContext.ERRANDS), pick("Drop off the package return"))
        assertEquals(Suggestion(Clarify.NEXT, TaskContext.COMPUTER), pick("Renew the car registration online"))
        assertEquals(Suggestion(Clarify.NEXT, TaskContext.HOME), pick("Do the laundry"))
        assertEquals(Suggestion(Clarify.NEXT, TaskContext.ANYWHERE), pick("Think about the Diwali gifts"))
        // Waiting for: who, and a check-back day three days out.
        assertEquals(Suggestion(Clarify.WAITING, who = "Taylor", date = today.plusDays(3)), pick("Waiting on Taylor for the report numbers"))
        assertEquals(Clarify.WAITING, pick("Refund from the store").choice)
        // A date makes it Scheduled; one it already has wins.
        assertEquals(Suggestion(Clarify.SCHEDULED, TaskContext.CALLS, date = today.plusDays(1)), pick("Call the landlord tomorrow"))
        assertEquals(today.plusDays(3), pick("Pay rent Friday").date)
        assertEquals(Suggestion(Clarify.SCHEDULED, date = today.plusDays(9)), Gtd.suggest("Anything", today, today.plusDays(9)))
        // An explicit context in the title.
        assertEquals(TaskContext.OFFICE, Gtd.contextOf("Ask about the parking pass @office"))
    }

    @Test fun contextsFitWhereHeIs() {
        val office = Gtd.contextsNow(Situation(officeDay = true, atOffice = true, atHome = false, hour = 10))!!
        assertTrue(TaskContext.OFFICE in office && TaskContext.CALLS in office && TaskContext.ANYWHERE in office)
        assertFalse(TaskContext.HOME in office || TaskContext.ERRANDS in office)
        // An office day's working hours count even before Odyssey knows where he is.
        assertEquals(office, Gtd.contextsNow(Situation(officeDay = true, hour = 9)))
        assertNull(Gtd.contextsNow(Situation(officeDay = true, hour = 20)))
        assertNull(Gtd.contextsNow(Situation(officeDay = false, hour = 10)))
        // Home, even on an office day.
        val home = Gtd.contextsNow(Situation(officeDay = true, atOffice = false, atHome = true, hour = 10))!!
        assertTrue(TaskContext.HOME in home && TaskContext.COMPUTER in home)
        assertFalse(TaskContext.OFFICE in home)
        // Driving: errands, never calls.
        val driving = Gtd.contextsNow(Situation(officeDay = true, atHome = false, driving = true, hour = 9))!!
        assertEquals(setOf(TaskContext.ERRANDS, TaskContext.ANYWHERE), driving)
        // Out of the car, away from home: errands and calls.
        val out = Gtd.contextsNow(Situation(atHome = false, atOffice = false, hour = 14))!!
        assertTrue(TaskContext.ERRANDS in out && TaskContext.CALLS in out)
        // A task with no context fits anywhere; nothing known shows all.
        assertTrue(Gtd.fits(task(1, "No context", TaskList.NEXT), driving))
        assertFalse(Gtd.fits(task(2, "Call Amma", TaskList.NEXT, context = TaskContext.CALLS), driving))
        assertTrue(Gtd.fits(task(2, "Call Amma", TaskList.NEXT, context = TaskContext.CALLS), null))
        assertEquals("Driving, so no calls", Gtd.whereLine(Situation(driving = true)))
    }

    @Test fun followUpTiming() {
        val thursday = at(2026, 10, 22, 0).toInstant().toEpochMilli()
        val t = task(5, "The weekly report numbers", TaskList.WAITING, who = "Taylor", followUp = thursday)
        assertFalse(Gtd.followUpDue(t, at(2026, 10, 21, 18)))
        // Not in the night, even on the day.
        assertFalse(Gtd.followUpDue(t, at(2026, 10, 22, 6)))
        assertTrue(Gtd.followUpDue(t, at(2026, 10, 22, 7)))
        assertTrue(Gtd.followUpDue(t, at(2026, 10, 24, 12)))
        assertFalse(Gtd.followUpDue(t.copy(list = TaskList.NEXT), at(2026, 10, 22, 9)))
        assertFalse(Gtd.followUpDue(t.copy(followUp = null), at(2026, 10, 22, 9)))
        assertEquals("Follow up with Taylor about the weekly report numbers", Gtd.followUpTitle(t))
        assertEquals("Follow up with them about lease renewal", Gtd.followUpTitle(t.copy(title = "Lease renewal", waitingOn = null)))
    }

    @Test fun stuckProjects() {
        val s = TaskSnapshot(
            listOf(
                task(1, "Check the drive time", TaskList.NEXT, project = 10),
                task(2, "Book tickets", TaskList.WAITING, project = 11),
                task(3, "Old step", TaskList.DONE, project = 12),
                task(4, "Pick the dates", TaskList.SOMEDAY, project = 12),
            ),
            listOf(Project(10, "Inter Miami away game", false), Project(11, "Diwali", false), Project(12, "Garage", false), Project(13, "Empty", false)),
            loaded = true,
        )
        assertEquals(listOf("Diwali", "Garage", "Empty"), Gtd.stuck(s).map { it.name })
    }

    @Test fun reviewDueOnSundayAfternoon() {
        val sunday = at(2026, 10, 25, 15)
        assertTrue(Gtd.reviewDue(sunday, null))
        assertFalse(Gtd.reviewDue(at(2026, 10, 25, 11), null))
        // Quiet hours and other days: no card.
        assertFalse(Gtd.reviewDue(at(2026, 10, 25, 23), null))
        assertFalse(Gtd.reviewDue(at(2026, 10, 24, 15), null))
        // Done this weekend (Saturday counts), not again.
        assertFalse(Gtd.reviewDue(sunday, at(2026, 10, 24, 10).toInstant().toEpochMilli()))
        assertTrue(Gtd.reviewDue(sunday, at(2026, 10, 18, 16).toInstant().toEpochMilli()))
        // Eos: owed from Sunday 1 PM until it is done.
        val lastWeek = at(2026, 10, 18, 16).toInstant().toEpochMilli()
        assertEquals(null, Gtd.reviewOwed(at(2026, 10, 25, 9), lastWeek))
        assertEquals(LocalDate.of(2026, 10, 25), Gtd.reviewOwed(at(2026, 10, 26, 7), lastWeek))
        assertEquals(null, Gtd.reviewOwed(at(2026, 10, 26, 7), at(2026, 10, 25, 17).toInstant().toEpochMilli()))
    }

    @Test fun addTaskToolTakesContextWaitingAndFollowUp() {
        val ok = Validate.step(ProposalKind.ADD_TASK, mapOf("title" to "Report numbers", "waiting_on" to "Taylor", "follow_up" to "2026-10-23", "context" to "calls"), FakeState(), 0L) as Check.Ok
        assertEquals("WAITING", ok.step.payload["list"])
        assertEquals("Taylor", ok.step.payload["waiting_on"])
        assertEquals("2026-10-23", ok.step.payload["follow_up"])
        assertEquals("calls", ok.step.payload["context"])
        assertEquals("Add task: Report numbers, @calls, waiting on Taylor, check back Fri, Oct 23", ok.step.summary)
        assertTrue(Validate.step(ProposalKind.ADD_TASK, mapOf("title" to "X", "context" to "gym"), FakeState(), 0L) is Check.Bad)
        // A list he named wins over the Waiting default.
        val next = Validate.step(ProposalKind.ADD_TASK, mapOf("title" to "Y", "list" to "next", "waiting_on" to "Casey"), FakeState(), 0L) as Check.Ok
        assertEquals("NEXT", next.step.payload["list"])
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TaskStoreMigrationTest {
    /** The v1 schema exactly as it shipped. */
    private class V1(c: android.content.Context) : SQLiteOpenHelper(c, "tasks.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, list TEXT NOT NULL, " +
                    "project_id INTEGER, due INTEGER, due_has_time INTEGER NOT NULL DEFAULT 0, sort REAL NOT NULL DEFAULT 0, " +
                    "quick INTEGER NOT NULL DEFAULT 0, prev_list TEXT, done_at INTEGER, sample INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL("CREATE TABLE projects (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sample INTEGER NOT NULL DEFAULT 0)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Test fun v1DataSurvivesTheUpgrade() {
        val app = RuntimeEnvironment.getApplication()
        V1(app).writableDatabase.apply {
            insert("projects", null, ContentValues().apply { put("name", "Garage") })
            insert("tasks", null, ContentValues().apply { put("title", "Return the green scarf"); put("list", "NEXT"); put("project_id", 1); put("due", 1_792_000_000_000L); put("quick", 1) })
            insert("tasks", null, ContentValues().apply { put("title", "Sofa delivery"); put("list", "WAITING") })
            close()
        }
        val store = TaskStore(app)
        val s = store.load()
        assertEquals(2, s.tasks.size)
        val scarf = s.tasks.first { it.title == "Return the green scarf" }
        assertEquals(TaskList.NEXT, scarf.list)
        assertEquals(1L, scarf.projectId)
        assertEquals(1_792_000_000_000L, scarf.due)
        assertTrue(scarf.quick)
        assertNull(scarf.context); assertNull(scarf.waitingOn); assertNull(scarf.followUp)
        assertEquals("Garage", s.projects.single().name)
        assertNull(s.lastReview)

        // The new columns write and read back.
        val sofa = s.tasks.first { it.title == "Sofa delivery" }
        store.update(sofa.copy(waitingOn = "Wayfair", followUp = 1_792_100_000_000L, context = TaskContext.CALLS))
        val id = store.add("Call Amma", TaskList.NEXT, context = TaskContext.CALLS)
        store.markReviewed(1_792_200_000_000L)
        val after = store.load()
        assertEquals(Triple("Wayfair", 1_792_100_000_000L, TaskContext.CALLS), after.tasks.first { it.id == sofa.id }.let { Triple(it.waitingOn, it.followUp, it.context) })
        assertEquals(TaskContext.CALLS, after.tasks.first { it.id == id }.context)
        assertEquals(1_792_200_000_000L, after.lastReview)
        store.close()
    }

    @Test fun aFreshInstallHasTheV2Schema() {
        val store = TaskStore(RuntimeEnvironment.getApplication())
        val id = store.add("Pick up the badge", TaskList.NEXT, context = TaskContext.OFFICE, waitingOn = " ", followUp = null)
        val t = store.load().tasks.single { it.id == id }
        assertEquals(TaskContext.OFFICE, t.context)
        assertNull(t.waitingOn)
        val pid = store.addProject("Empty")
        store.deleteProjectIfEmpty(pid)
        assertTrue(store.load().projects.none { it.id == pid })
        store.close()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FollowUpProposalTest {
    @Test fun theCheckBackDayBringsOneProposal() {
        val rig = EngineRig(at(2026, 10, 22, 9))
        val id = rig.tasks.addTask("The weekly report numbers", TaskList.WAITING)
        val day = at(2026, 10, 22, 0).toInstant().toEpochMilli()
        rig.tasks.rows[id] = rig.tasks.rows.getValue(id).copy(waitingOn = "Taylor", followUp = day)
        val later = rig.tasks.addTask("Sofa delivery", TaskList.WAITING)
        rig.tasks.rows[later] = rig.tasks.rows.getValue(later).copy(followUp = day + 2 * 86_400_000L)
        rig.engine.hygiene()
        val p = rig.proposals.all().single()
        assertEquals("Follow up with Taylor about the weekly report numbers?", p.title)
        assertEquals(ProposalKind.ADD_TASK, p.steps.single().kind)
        assertEquals("NEXT", p.steps.single().payload["list"])
        // Offered once per check-back day; nothing runs on its own.
        rig.engine.hygiene()
        assertEquals(1, rig.proposals.all().size)
        assertEquals(TaskList.WAITING, rig.tasks.rows.getValue(id).list)
        // A new check-back day brings a new one.
        rig.tasks.rows[id] = rig.tasks.rows.getValue(id).copy(followUp = day + 86_400_000L)
        rig.now += 86_400_000L
        rig.engine.hygiene()
        assertEquals(2, rig.proposals.all().size)
    }
}
