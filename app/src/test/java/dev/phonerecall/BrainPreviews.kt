package dev.phonerecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.phonerecall.brain.CalendarChoice
import dev.phonerecall.brain.SealedShelf
import dev.phonerecall.vault.Shelf
import dev.phonerecall.vault.VaultContent
import dev.phonerecall.brain.ChatLine
import dev.phonerecall.brain.ChatState
import dev.phonerecall.brain.DelphiContent
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.FatesContent
import dev.phonerecall.brain.LineKind
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Step
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Space

/** Fixed brain screens for the screenshot test: a fake conversation, Fates cards, Calypso unlocked. */
object BrainPreviews {
    private val t0 = 1_792_000_000_000L

    private val plan = Proposal(
        id = 1, createdAt = t0, expiresAt = t0 + 12 * 3_600_000L, status = ProposalStatus.PENDING, title = "Flight to Newark tomorrow",
        conversationId = 1,
        steps = listOf(
            Step(ProposalKind.SET_ALARM, mapOf("hour" to "5", "minute" to "15", "label" to "Flight"), "Alarm at 5:15 AM, Flight"),
            Step(ProposalKind.SET_ALARM, mapOf("hour" to "5", "minute" to "25"), "Alarm at 5:25 AM", selected = false),
            Step(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-20T05:55", "text" to "Leave for BUF"), "Remind you Tue, Oct 20, 5:55 AM: Leave for BUF"),
            Step(ProposalKind.ADD_TASK, mapOf("title" to "Check in for the flight", "list" to "NEXT"), "Add task: Check in for the flight"),
        ),
    )
    private val task = Proposal(
        id = 2, createdAt = t0, expiresAt = t0 + 24 * 3_600_000L, status = ProposalStatus.PENDING, title = "Add task: Book the oil change",
        conversationId = 1, steps = listOf(Step(ProposalKind.ADD_TASK, mapOf("title" to "Book the oil change", "list" to "NEXT", "due" to "2026-10-23"), "Add task: Book the oil change")),
    )
    private val event = Proposal(
        id = 3, createdAt = t0, expiresAt = t0 + 48 * 3_600_000L, status = ProposalStatus.PENDING, title = "Add to calendar: Barcelona vs Real Madrid, Sun, Oct 25, 10:15 AM",
        steps = listOf(Step(ProposalKind.ADD_CALENDAR_EVENT, mapOf("title" to "Barcelona vs Real Madrid", "start" to "2026-10-25T10:15", "end" to "2026-10-25T12:15", "all_day" to "false"), "Add to calendar", needs = "calendar")),
    )
    private val done = Proposal(
        id = 4, createdAt = t0, expiresAt = t0, status = ProposalStatus.CONFIRMED, auto = true, outcome = "Done automatically", title = "Timer for 25 min, Pasta",
        steps = listOf(Step(ProposalKind.SET_TIMER, mapOf("seconds" to "1500", "label" to "Pasta"), "Timer for 25 min, Pasta", result = "ok", undo = mapOf("x" to "1"))),
    )
    private val rejected = done.copy(id = 5, status = ProposalStatus.REJECTED, auto = false, outcome = "Rejected", title = "Alarm at 6:00 AM", steps = done.steps.map { it.copy(undo = null) })

    private val chat = ChatState(
        loaded = true, chatId = 1,
        lines = listOf(
            ChatLine(1, LineKind.USER, "I fly to Newark tomorrow at 7:40. Set me up."),
            ChatLine(2, LineKind.ATHENA, "Tomorrow is a remote day and your calendar is clear until 2 PM. Here is the plan: two alarms, a leave-by reminder and a check-in task."),
            ChatLine(3, LineKind.PROPOSAL, plan.title, proposalId = 1),
            ChatLine(4, LineKind.NOTE, "The owner confirmed “Flight to Newark tomorrow”. It was done."),
            ChatLine(5, LineKind.USER, "Also remind me about the oil change this week"),
            ChatLine(6, LineKind.PROPOSAL, task.title, proposalId = 2),
        ),
        streaming = "Done. It's on a card for Friday",
        working = "Writing",
    )

    @Composable private fun Page(dark: Boolean = true, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Composable fun Delphi() = Page {
        DelphiContent(chat, mapOf(1L to plan.copy(status = ProposalStatus.CONFIRMED, outcome = "Done"), 2L to task), emptyList(), true, Space.gutterNarrow, FatesCallbacks(), {}, {}, {}, {})
    }

    @Composable fun DelphiEmpty() = Page {
        DelphiContent(ChatState(loaded = true), emptyMap(), emptyList(), true, Space.gutterNarrow, FatesCallbacks(), {}, {}, {}, {})
    }

    @Composable fun Fates() = Page {
        FatesContent(listOf(plan, event, done, rejected), listOf(CalendarChoice(3, "Personal", "owner@example.com"), CalendarChoice(4, "Football", "owner@example.com")), Space.gutterNarrow, FatesCallbacks())
    }

    @Composable fun Calypso() = Page {
        VaultContent(Space.gutterNarrow, false, Shelf.SEALED, {}, unlocked = true, failure = null, onUnlock = {}, log = emptyList(), otherLocked = true, locked = {}) {
            SealedShelf(keySet = true, masked = "sk-ant-api03-\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022Xq4f", savedAt = t0, revealed = true, {}, {}, {}, {})
        }
    }
}
