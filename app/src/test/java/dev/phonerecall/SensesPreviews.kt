package dev.phonerecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.phonerecall.brain.Actor
import dev.phonerecall.brain.Capture
import dev.phonerecall.brain.CaptureKind
import dev.phonerecall.brain.CaptureStatus
import dev.phonerecall.brain.LetheContent
import dev.phonerecall.brain.LetheRow
import dev.phonerecall.brain.ParnassusContent
import dev.phonerecall.brain.PeopleContent
import dev.phonerecall.brain.People
import dev.phonerecall.brain.Person
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Relation
import dev.phonerecall.brain.ResponseStats
import dev.phonerecall.brain.Section
import dev.phonerecall.brain.SectionChange
import dev.phonerecall.brain.SectionContent
import dev.phonerecall.brain.SectionItem
import dev.phonerecall.brain.SensesCallbacks
import dev.phonerecall.brain.SensesState
import dev.phonerecall.brain.Step
import dev.phonerecall.brain.Tier
import dev.phonerecall.home.CardSource
import dev.phonerecall.home.GeneratedCard
import dev.phonerecall.home.PreviewSenseCards
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space

/** Fixed phase 2b screens for the screenshot test. */
object SensesPreviews {
    private val now = System.currentTimeMillis()
    private val h = 3_600_000L
    private val day = 24 * h

    private val groceries = Section(1, "Groceries", createdBy = Actor.ATHENA, createdAt = now - 3 * day)
    private val venus = Section(2, "Venus notes", place = "venus", createdBy = Actor.ATHENA, createdAt = now - 10 * day, order = 1)
    private val books = Section(3, "Books to read", createdBy = Actor.OWNER, createdAt = now - 20 * day, pinned = true, order = 2)

    private val groceryItems = listOf(
        SectionItem(11, 1, "Tomatoes", createdAt = now - h),
        SectionItem(12, 1, "Garlic", createdAt = now - h),
        SectionItem(13, 1, "Parmesan", note = "The block, not grated", createdAt = now - h),
        SectionItem(14, 1, "Pumpkin for carving", createdAt = now - day, until = now + 12 * day),
        SectionItem(15, 1, "Milk", done = true, createdAt = now - 2 * day, doneAt = now - day),
    )

    private val state = SensesState(
        loaded = true,
        sections = listOf(groceries, venus, books),
        items = mapOf(1L to groceryItems, 2L to listOf(SectionItem(21, 2, "Green scarf needs a wash", createdAt = now - day)), 3L to emptyList()),
        captures = listOf(
            Capture(5, now - 5 * 60_000L, CaptureKind.VOICE, "make pasta this week", status = CaptureStatus.SORTED, place = "Praxis", outcome = "Added to Praxis: Make pasta. Ingredients are waiting for you in Fates.", undo = mapOf("task_id" to "9")),
            Capture(4, now - 40 * 60_000L, CaptureKind.TEXT, "need tomatoes and garlic", status = CaptureStatus.SORTED, place = "Groceries", outcome = "Added to Groceries: Tomatoes, Garlic", undo = mapOf("item_ids" to "11,12")),
            Capture(3, now - 2 * h, CaptureKind.TEXT, "did my refund come in", status = CaptureStatus.SORTED, place = "Iris", outcome = "For Iris. Waiting for the phase 3 channel.", undo = mapOf("job_id" to "1")),
            Capture(2, now - 5 * h, CaptureKind.LINK, "https://www.alleghenyforest.org/fall-colors", status = CaptureStatus.UNSORTED, outcome = "Kept here. Not sorted yet."),
            Capture(1, now - day, CaptureKind.PHOTO, "A flyer for the Oak Street fall festival", status = CaptureStatus.SORTED, place = "Chronos", outcome = "Waiting for you in Fates: Add to calendar: Oak Street Fall Festival, Sat, Oct 24"),
        ),
        changes = listOf(SectionChange(1, now - 40 * 60_000L, Actor.ATHENA, "create", emptyList(), listOf(groceries))),
    )

    private fun person(name: String, r: Relation?, confirmed: Boolean, stats: ResponseStats = ResponseStats(), pinned: Tier? = null, work: Boolean = false) =
        Person(People.keyOf(name), name, r, confirmed = confirmed, asked = true, pinned = pinned, stats = stats, work = work)

    private val people = listOf(
        person("Amma", Relation.MOTHER, true, ResponseStats(14, 13, 3.2)),
        person("Taylor", Relation.MANAGER, true, ResponseStats(9, 9, 11.0), work = true),
        person("Akka", Relation.SISTER, false, ResponseStats(3, 2, 40.0)),
        person("Jordan", Relation.ROOMMATE, true, ResponseStats(11, 8, 95.0)),
        person("Alex", Relation.FRIEND, true, ResponseStats(7, 4, 600.0)),
        person("Leasing office", null, false, ResponseStats(6, 1, 1300.0), pinned = Tier.MUTED),
    ).sortedWith(compareBy({ it.tier.ordinal }, { it.name }))

    private val lethe = listOf(
        LetheRow(3, now - 2 * h, "task", 7, "Fall foliage drive to Allegheny", "Foliage season ended", false),
        LetheRow(2, now - 2 * day, "item", 4, "Inter Miami away tickets", "The away games have passed", false),
        LetheRow(1, now - 9 * day, "task", 3, "Pumpkin farm with Jordan", "Relevant until Oct 10", true),
    )

    @Composable private fun Page(content: @Composable () -> Unit) = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Composable fun Parnassus() = Page { ParnassusContent(state, Space.gutterNarrow, SensesCallbacks(), "", {}, {}, {}, {}) }

    @Composable fun Groceries() = Page { SectionContent(groceries, groceryItems, Space.gutterNarrow, SensesCallbacks(), {}) }

    @Composable fun PeopleScreen() = Page { PeopleContent(people, Space.gutterNarrow, SensesCallbacks(), contactsOk = true, onAllowContacts = {}, open = People.keyOf("Akka")) }

    @Composable fun Lethe() = Page { LetheContent(lethe, Space.gutterNarrow, SensesCallbacks(), {}, ripple = 0.35f) }

    private val t0 = now
    private val report = Proposal(
        id = 11, createdAt = t0, expiresAt = t0 + 20 * h, status = ProposalStatus.PENDING, title = "Taylor: can you send the report by EOD",
        steps = listOf(Step(ProposalKind.ADD_TASK, mapOf("title" to "Send the report", "list" to "NEXT", "due" to "2026-10-19T17:00"), "Add task: Send the report, due Mon, Oct 19, 5:00 PM")),
    )
    private val amma = Proposal(
        id = 12, createdAt = t0, expiresAt = t0 + 7 * day, status = ProposalStatus.PENDING, title = "Is “Amma” your mother?",
        steps = listOf(Step(ProposalKind.SET_RELATION, mapOf("person" to "k", "term" to "Amma", "relation" to "MOTHER"), "“Amma” is your mother")),
    )
    private val alex = Proposal(
        id = 13, createdAt = t0, expiresAt = t0 + 3 * day, status = ProposalStatus.PENDING, title = "Save Alex's birthday (today) in Google Calendar?",
        steps = listOf(Step(ProposalKind.ADD_BIRTHDAY, mapOf("name" to "Alex", "date" to "10-19", "source" to "the “Family” group"), "Save Alex's birthday (Oct 19) in Google Calendar. It repeats every year.")),
    )

    @Composable fun NotificationCards() = PreviewSenseCards(
        listOf(
            GeneratedCard("Amma called twice", "Last call 9:41 PM", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-call", real = true),
            GeneratedCard("Bank alert", "Chase, at 8:12 PM", listOf("Got it"), now + 12 * h, CardSource.BANK, id = "sense-bank", real = true),
            GeneratedCard("Delivery update", "Amazon: Your package was delivered to the front desk", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-delivery", real = true),
            GeneratedCard("Closed for you", "Fall foliage drive and Inter Miami away tickets went to Lethe this week.", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-closed", real = true),
        ),
        listOf(report, amma, alex),
        Space.gutterNarrow,
    )
}
