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

    /** One-tap agency: the apps' own buttons on the cards, and a reply open with Athena's draft. */
    @Composable fun AgencyCards() {
        fun live(vararg l: String) = l.mapIndexed { i, s -> dev.phonerecall.home.CardAction(s, dev.phonerecall.home.ActionRole.LIVE, "n#$i") }
        val reply = androidx.compose.runtime.remember { dev.phonerecall.home.ReplyBox().apply { open("gen:sense-msg", dev.phonerecall.home.CardAction("Reply", dev.phonerecall.home.ActionRole.LIVE, "n#0"), "On my way, I'll call you in 10") } }
        PreviewSenseCards(
            listOf(
                GeneratedCard("Amma called twice", "Last call 9:41 PM", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-call", real = true, live = live("Call back", "Message")),
                GeneratedCard(
                    "Alex", "Are you coming tonight?", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-msg2", real = true, live = live("Edit", "Mark as read"),
                    replies = listOf("Send \u201cSure, 7 works for me\u201d", "On my way", "Call you in 10").map { dev.phonerecall.home.CardAction(it, dev.phonerecall.home.ActionRole.LIVE_SEND, "n#0") },
                ),
                GeneratedCard("Alex", "Are you coming tonight?", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-msg", real = true, live = live("Edit", "Mark as read")),
                GeneratedCard("Code from Messages", "Copy it from here. Zeus clears the clipboard after a minute.", emptyList(), now + h, CardSource.ATHENA, id = "code-1", real = true, live = live("Copy code")),
                GeneratedCard("Delivery update", "UPS: Out for delivery today", listOf("Got it"), now + 12 * h, CardSource.ATHENA, id = "sense-delivery", real = true, live = live("Track")),
            ),
            emptyList(), Space.gutterNarrow, dev.phonerecall.home.CardCallbacks(reply = reply),
        )
    }

    /** One-tap agency, later stages: intent buttons, the group wish, Splitwise and plans from a thread. Expiries are far out: the preview board runs at 9:48 PM whatever the clock says. */
    @Composable fun AgencyCards2(columns: Int, gutter: androidx.compose.ui.unit.Dp) {
        fun live(vararg l: String) = l.mapIndexed { i, s -> dev.phonerecall.home.CardAction(s, dev.phonerecall.home.ActionRole.LIVE, "n#$i") }
        fun chips(vararg l: String) = l.map { dev.phonerecall.home.CardAction(it, dev.phonerecall.home.ActionRole.LIVE_SEND, "n#0") }
        PreviewSenseCards(
            listOf(
                GeneratedCard("BGMI at 10:00 PM with Alex, Sam", "From the chat", emptyList(), now + 36 * h, CardSource.ATHENA, id = "plan-1", real = true, travel = true, live = live("Open BGMI", "Edit"), replies = chips("In", "Can't today")),
                GeneratedCard(
                    "PUBG? with Alex", "Which time did they mean?", emptyList(), now + 36 * h, CardSource.ATHENA, id = "plan-2", real = true, live = live("Open BGMI"),
                    replies = listOf("10:00 PM", "In 10 min").map { dev.phonerecall.home.CardAction(it, dev.phonerecall.home.ActionRole.PLAN_PICK, "t|0") },
                ),
                GeneratedCard("Message from Alex", "Pubg?", listOf("Got it"), now + 36 * h, CardSource.ATHENA, id = "sense-pubg", real = true, live = live("Open BGMI", "Edit", "Mark as read"), replies = chips("In", "Can't today")),
                GeneratedCard("Message from Riley", "pics bhej from yesterday", listOf("Got it"), now + 36 * h, CardSource.ATHENA, id = "sense-pics", real = true, live = live("Share photos", "Edit"), replies = chips("Sending now", "On my way")),
                GeneratedCard("Message from Sam", "did you pay for dinner?", listOf("Got it"), now + 36 * h, CardSource.ATHENA, id = "sense-pay", real = true, live = live("Open GPay", "Edit")),
                GeneratedCard(
                    "Wish Alex happy birthday", "In Family", emptyList(), now + 36 * h, CardSource.ATHENA, id = "sense-wish", real = true, live = live("Edit", "Mark as read"),
                    replies = chips("Send \u201cHappy birthday Alex anna \uD83C\uDF82\uD83C\uDF82\u201d", "Happy birthday Alex", "Many happy returns"),
                ),
                GeneratedCard("You owe \$12.50", "Alex added \"Dinner\" in \"Trip\".", listOf("Got it"), now + 36 * h, CardSource.ATHENA, id = "sense-splitwise", real = true, live = live("Open Splitwise", "Settle up")),
            ),
            emptyList(), gutter, columns = columns,
        )
    }

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
