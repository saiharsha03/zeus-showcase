package dev.phonerecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.phonerecall.assist.AssistLine
import dev.phonerecall.assist.AssistSheet
import dev.phonerecall.assist.ScreenContext
import dev.phonerecall.brain.Actor
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.MindState
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Section
import dev.phonerecall.brain.SpendState
import dev.phonerecall.brain.Step
import dev.phonerecall.memory.MemoryHit
import dev.phonerecall.memory.PickedDoc
import dev.phonerecall.memory.Shield
import dev.phonerecall.memory.Source
import dev.phonerecall.mind.AlexandriaContent
import dev.phonerecall.mind.LongJob
import dev.phonerecall.mind.LongJobsContent
import dev.phonerecall.mind.MemoryFile
import dev.phonerecall.mind.Metis
import dev.phonerecall.mind.MindRows
import dev.phonerecall.mind.PantheonContent
import dev.phonerecall.mind.PlacesContent
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.RememberedContent
import dev.phonerecall.mind.SpendRows
import dev.phonerecall.mind.Wakeup
import dev.phonerecall.places.Place
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.Place as UiPlace
import java.io.File

/** Fixed phase 2c screens for the screenshot test. */
object MindPreviews {
    private val now = System.currentTimeMillis()
    private val h = 3_600_000L
    private val day = 24 * h

    private val groceries = Section(1, "Groceries", createdBy = Actor.ATHENA, createdAt = now - 3 * day)
    private val household = Section(2, "Household", createdBy = Actor.OWNER, createdAt = now - 9 * day)
    private val books = Section(3, "Books to read", createdBy = Actor.OWNER, createdAt = now - 20 * day)

    private val mind = MindState(
        ready = true, setUp = true, session = "sesn_1",
        jobs = listOf(
            LongJob("job-1", "Winter tires for the Camry", "", now - 20 * h, "progress", "Perseus", "Research in his own notes and lists", "Narrowed to three all-season and two winter options", updatedAt = now - h),
            LongJob("job-2", "Plan the away game trip", "", now - 2 * day, "done", "Odysseus", null, null, "Away game, Nov 7", "Leave by 1:30 PM, parking lot 22 is easiest. Two reminders are waiting in Fates.", updatedAt = now - 3 * h),
            LongJob("job-3", "Is the lease renewal worth it", "", now - 3 * h, "started", "Annabeth", "Big decision with a deadline, he asked for Annabeth", "Comparing the renewal against moving after August", updatedAt = now - 2 * h),
        ),
        wakeupsToday = listOf(Wakeup(now - 20 * 60_000L, "sync (1 from outbox)", 2_300, 6), Wakeup(now - 50 * 60_000L, "batch sent", 1_800, 1), Wakeup(now - 2 * h, "geofence enter (haiku)", 1_400, 1)),
        lastSync = now - 20 * 60_000L,
        circle = mapOf(Metis.THREAD to 412L, Protege.PERSEUS.agentName to 186L, Protege.ODYSSEUS.agentName to 240L, Protege.ANNABETH.agentName to 905L, Protege.DIOMEDES.agentName to 12L),
        places = listOf(
            Place("p1", "Walmart, Main St", 39.7, -105.0, sections = listOf(1, 2), pregen = "Walmart: tomatoes, garlic and parmesan, plus dish soap.", pregenFor = "x"),
            Place("p2", "Costco", 39.6, -104.9, sections = listOf(1)),
            Place("p3", "Farmers market on Elm", 39.8, -105.1, sections = listOf(1)),
        ),
        here = "p1",
        memoryCounts = mapOf(Source.PARNASSUS to 42, Source.NOTE to 63, Source.TASK to 118, Source.CALENDAR to 209, Source.CLOSET to 57, Source.NOTIFICATION to 311, Source.DOCUMENT to 4),
        memoryChunks = 1_046, memoryVectors = 0, embedder = "model not on the phone",
        documents = listOf(
            PickedDoc("1.pdf", "Lease agreement 2026.pdf", File("x"), now - 30 * day, 0),
            PickedDoc("2.pdf", "Camry owner's manual.pdf", File("x"), now - 12 * day, 0),
            PickedDoc("3.md", "Trip ideas.md", File("x"), now - 4 * day, 0),
        ),
    )

    private val hits = listOf(
        MemoryHit(1, "doc:lease", Source.DOCUMENT, "Lease agreement 2026.pdf", "…Page 2. The lease ends on August 31, 2027. The tenant must give 60 days notice before moving out…", now - 30 * day, Shield.OPEN, null),
        MemoryHit(2, "task:9", Source.TASK, "Decide on the lease renewal", "Decide on the lease renewal. Next. Due Thu, Jul 1, 2027", now - 2 * day, Shield.OPEN, null),
        MemoryHit(3, "note:77", Source.NOTIFICATION, "Outlook notification", "Shielded. Unlock to read.", now - 6 * day, Shield.AEGIS, null),
        MemoryHit(4, "cap:12", Source.PARNASSUS, "ask the leasing office about the heater", "ask the leasing office about the heater before renewing", now - 9 * day, Shield.OPEN, null),
    )

    @Composable private fun Page(content: @Composable () -> Unit) = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Composable fun Alexandria() = Page { AlexandriaContent(mind, hits, "when does my lease end", Space.gutterNarrow, null, {}, {}, {}, {}) }

    @Composable fun Places() = Page {
        PlacesContent(mind.places, mind.here, listOf(groceries, household, books), fine = true, background = false, Space.gutterNarrow, null, {}, {}, { _, _ -> }, { _, _ -> }, {}, {})
    }

    @Composable fun LongJobs() = Page { LongJobsContent(mind.jobs, Space.gutterNarrow, {}, {}) }

    @Composable fun Pantheon() = Page { PantheonContent(Space.gutterNarrow, mind.circle, dev.phonerecall.home.SampleData.workers) }

    @Composable fun ThemisSpend() = Page {
        val spend = SpendState("2026-10", 23_480_000, 100, mindMicros = 17_550_000, activeSeconds = 2_940, advisorMicros = 1_200_000)
        Column {
            ScreenHeader(UiPlace.SETTINGS.title(), Space.gutterNarrow, emblem = UiPlace.SETTINGS, subtitle = UiPlace.SETTINGS.subtitle())
            Column(Modifier.padding(horizontal = Space.gutterNarrow)) {
                Kicker("Athena", Modifier.padding(top = Space.s, bottom = Space.s))
                SpendRows(spend, mind)
                MindRows(mind, keySet = true, {}, {}, {})
            }
        }
    }

    @Composable fun Remembered() = Page {
        RememberedContent(
            listOf(
                MemoryFile("m1", "/profile/owner.md", "a", 812, "# The owner: how he likes things\n- Short plain English, one question at a time.\n- Quiet hours 11 PM to 7 AM."),
                MemoryFile("m2", "/people/labels.md", "b", 140, "# People he hears from, as labels only\n- family (mother): Reply now\n- manager: Reply now\n- roommate: Today"),
                MemoryFile("m3", "/decisions/recent.md", "c", 310, "- Oct 19: rejected \"Alarm at 6:00 AM\" (Alarm)\n- Oct 18: confirmed \"Add task: Book the oil change\""),
                MemoryFile("m4", "/routines/days.md", "d", 96, "# Routines\n- Office Tuesday to Thursday, remote Monday and Friday."),
                MemoryFile("m5", "/jobs/job-1/notes.md", "e", 220, "Three all-season options fit the Camry; he prefers low noise over price."),
            ),
            null, Space.gutterNarrow, {}, {},
        )
    }

    private val reminder = Proposal(
        id = 21, createdAt = now, expiresAt = now + 12 * h, status = ProposalStatus.PENDING, title = "Remind you Tue, Oct 20, 5:30 PM: Pick up the parcel at the front desk",
        steps = listOf(Step(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-20T17:30", "text" to "Pick up the parcel at the front desk"), "Remind you Tue, Oct 20, 5:30 PM: Pick up the parcel at the front desk")),
    )

    /** The sheet over another app: a dimmed stand-in for the app behind it. */
    @Composable fun AssistOverlay() = AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Color(0xFF2B3440))) {
            Box(Modifier.fillMaxSize().background(Color(0x99000000)))
            AssistSheet(
                lines = listOf(
                    AssistLine(true, "remind me to grab this after work"),
                    AssistLine(false, "Your parcel is at the front desk. I put a reminder for 5:30 PM on a card for you."),
                    AssistLine(false, reminder.title, proposalId = 21),
                    AssistLine(false, "Quick, Haiku 5.5", note = true),
                ),
                proposals = mapOf(21L to reminder), working = null,
                screen = ScreenContext("com.google.android.gm", "Gmail", "Your package was delivered to the front desk", false),
                useScreen = true, onUseScreen = {}, onAsk = {}, cb = FatesCallbacks(), onClose = {}, voice = false,
            )
        }
    }
}
