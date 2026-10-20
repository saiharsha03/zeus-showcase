package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FatesTest {
    private var now = java.time.ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val store = MemoryStore()
    private val state = FakeState()
    private val exec = FakeExecutor()
    private val log = ArrayList<String>()
    private val fates = Fates(store, state, exec, { a, b, _ -> log += "$a $b" }, { now })

    private fun propose(kind: ProposalKind, vararg args: Pair<String, String>): Proposal =
        (fates.propose(listOf(RawStep(kind, mapOf(*args))), null, 1L) as ProposeResult.Created).proposal

    @Test fun proposingNeverExecutes() {
        val p = propose(ProposalKind.ADD_TASK, "title" to "Book the oil change")
        assertEquals(ProposalStatus.PENDING, p.status)
        assertTrue(exec.ran.isEmpty())
        assertEquals("Add task: Book the oil change", p.title)
    }

    @Test fun confirmExecutesExactlyOnce() {
        val p = propose(ProposalKind.SET_ALARM, "hour" to "6", "minute" to "30", "label" to "Gym")
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
        assertTrue(fates.confirmNow(p.id, store) is Decision.Refused)
        assertEquals(1, exec.ran.size)
        assertEquals(ProposalStatus.CONFIRMED, store.get(p.id)!!.status)
    }

    @Test fun confirmRequiresPendingEvenIfTheRowWasClaimed() {
        val p = propose(ProposalKind.SET_TIMER, "seconds" to "600")
        // Another confirm claimed it first (PENDING to RUNNING): this one must not run.
        assertTrue(store.casStatus(p.id, ProposalStatus.PENDING, ProposalStatus.RUNNING))
        assertTrue(fates.confirmNow(p.id, store) is Decision.Refused)
        assertTrue(exec.ran.isEmpty())
    }

    @Test fun rejectedNeverExecutes() {
        val p = propose(ProposalKind.ADD_TASK, "title" to "Call the landlord")
        assertTrue(fates.reject(p.id) is Decision.Rejected)
        assertTrue(fates.confirmNow(p.id, store) is Decision.Refused)
        assertTrue(exec.ran.isEmpty())
        assertEquals(ProposalStatus.REJECTED, store.get(p.id)!!.status)
    }

    @Test fun expiredNeverExecutes() {
        val p = propose(ProposalKind.SET_TIMER, "seconds" to "300")
        now += 31 * 60_000L   // timers wait 30 minutes
        assertTrue(fates.confirmNow(p.id, store) is Decision.Expired)
        assertTrue(exec.ran.isEmpty())
        assertEquals(ProposalStatus.EXPIRED, store.get(p.id)!!.status)
        assertTrue(fates.confirmNow(p.id, store) is Decision.Refused)
    }

    @Test fun sweepExpiresStalePending() {
        val p = propose(ProposalKind.ADD_TASK, "title" to "x")
        now += 25 * 3_600_000L
        assertEquals(listOf(p.id), fates.sweep().map { it.id })
        assertEquals(ProposalStatus.EXPIRED, store.get(p.id)!!.status)
    }

    @Test fun revalidationFailureMarksFailedAndRunsNothing() {
        val p = propose(ProposalKind.COMPLETE_TASK, "task_id" to "7")
        assertEquals("Mark done: Return the green scarf", p.title)
        state.open.remove(7L)   // he finished it himself in the meantime
        val d = fates.confirmNow(p.id, store)
        assertTrue(d is Decision.Failed)
        assertTrue(exec.ran.isEmpty())
        assertEquals(ProposalStatus.FAILED, store.get(p.id)!!.status)
    }

    @Test fun invalidArgumentsNeverBecomeProposals() {
        val r = fates.propose(listOf(RawStep(ProposalKind.COMPLETE_TASK, mapOf("task_id" to "999"))), null, 1L)
        assertTrue(r is ProposeResult.Invalid)
        assertTrue(store.rows.isEmpty())
        assertTrue(fates.propose(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "25"))), null, 1L) is ProposeResult.Invalid)
        assertTrue(fates.propose(listOf(RawStep(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-19T09:00", "text" to "past"))), null, 1L) is ProposeResult.Invalid)
    }

    @Test fun calendarEventWaitsForACalendarChoice() {
        state.calendars = listOf(CalendarChoice(3, "Personal", "a"), CalendarChoice(4, "Football", "a"))
        val p = propose(ProposalKind.ADD_CALENDAR_EVENT, "title" to "Barca vs Madrid", "start" to "2026-10-25T10:15")
        assertEquals("calendar", p.needs)
        assertTrue(fates.confirmNow(p.id, store) is Decision.Refused)
        assertTrue(exec.ran.isEmpty())
        fates.edit(p.id, 0, p.steps[0].payload + ("calendar_id" to "4"))
        assertNull(store.get(p.id)!!.needs)
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
        assertEquals("4", exec.ran.single().payload["calendar_id"])
    }

    @Test fun bundledPlanRunsOnlyCheckedSteps() {
        val r = fates.propose(
            listOf(
                RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "5", "minute" to "15")),
                RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "5", "minute" to "25")),
                RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Check in for the flight")),
            ),
            "Flight tomorrow", 1L,
        ) as ProposeResult.Created
        fates.toggle(r.proposal.id, 1)
        assertTrue(fates.confirmNow(r.proposal.id, store) is Decision.Done)
        assertEquals(listOf("Alarm at 5:15 AM", "Add task: Check in for the flight"), exec.ran.map { it.summary })
    }

    // ---- auto-approve ----

    @Test fun autoApproveIsEmptyByDefault() {
        assertTrue(fates.autoApprove.isEmpty())
        val p = propose(ProposalKind.SET_TIMER, "seconds" to "60")
        assertEquals(ProposalStatus.PENDING, p.status)
        assertTrue(exec.ran.isEmpty())
    }

    @Test fun onlyTierTwoListedKindsAreEligible() {
        val all = ProposalKind.entries.toSet()
        fun one(kind: ProposalKind) = Proposal(createdAt = 0, expiresAt = 1, status = ProposalStatus.PENDING, title = "", steps = listOf(Step(kind, emptyMap(), "")))
        assertEquals(
            // Phase 2b: a Rambler list expansion is tier 2 and may be listed; birthdays (tier 3) and relations (tier 1) never.
            // Music: only music while driving; play_music and music_control always ask. Hestia: lights only, never plugs. Moments: timers under 1 hour.
            setOf(ProposalKind.SET_ALARM, ProposalKind.SET_TIMER, ProposalKind.ADD_REMINDER, ProposalKind.ADD_TASK, ProposalKind.CREATE_CARD, ProposalKind.ADD_TO_LIST, ProposalKind.MUSIC_WHILE_DRIVING, ProposalKind.LIGHTS, ProposalKind.SHORT_TIMER),
            ProposalKind.entries.filter { fates.eligibleForAuto(one(it), all) }.toSet(),
        )
        assertFalse(fates.eligibleForAuto(one(ProposalKind.ADD_BIRTHDAY), all))
        assertFalse(fates.eligibleForAuto(one(ProposalKind.SET_RELATION), all))
        assertFalse(fates.eligibleForAuto(one(ProposalKind.ADD_CALENDAR_EVENT), all))
        assertFalse(fates.eligibleForAuto(one(ProposalKind.SET_TIMER), emptySet()))
        // A plan is automatic only if every checked step is.
        val mixed = one(ProposalKind.SET_TIMER).let { it.copy(steps = it.steps + Step(ProposalKind.ADD_CALENDAR_EVENT, emptyMap(), "")) }
        assertFalse(fates.eligibleForAuto(mixed, all))
        // Even if storage were tampered with, ineligible kinds never auto-run.
        assertFalse(ProposalKind.ADD_CALENDAR_EVENT in ProposalKind.autoCandidates)
        assertFalse(ProposalKind.COMPLETE_TASK in ProposalKind.autoCandidates)
    }

    @Test fun autoApprovedRunsThroughTheSamePathAndCanBeUndone() {
        fates.autoApprove = setOf(ProposalKind.ADD_TASK)
        val r = fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Buy milk"))), null, 1L) as ProposeResult.Created
        assertTrue(r.decision is Decision.Done)
        val p = store.get(r.proposal.id)!!
        assertEquals(ProposalStatus.CONFIRMED, p.status)
        assertTrue(p.auto)
        assertEquals("Done automatically", p.outcome)
        assertEquals(1, exec.ran.size)
        assertTrue(log.any { it.startsWith("Auto-approve confirmed") })
        assertTrue(fates.undo(p.id) is Decision.Undone)
        assertEquals(1, exec.undone.size)
        assertEquals(ProposalStatus.UNDONE, store.get(p.id)!!.status)
    }

    @Test fun autoApproveStillRevalidates() {
        fates.autoApprove = setOf(ProposalKind.ADD_REMINDER)
        state.notify = false
        val r = fates.propose(listOf(RawStep(ProposalKind.ADD_REMINDER, mapOf("at" to "2026-10-19T18:00", "text" to "Laundry"))), null, 1L)
        assertTrue(r is ProposeResult.Invalid)
        assertTrue(exec.ran.isEmpty())
    }

    @Test fun executorFailureIsRecorded() {
        exec.failWith = "No clock app takes this"
        val p = propose(ProposalKind.SET_ALARM, "hour" to "7")
        val d = fates.confirmNow(p.id, store)
        assertTrue(d is Decision.Failed)
        assertEquals(ProposalStatus.FAILED, store.get(p.id)!!.status)
        assertEquals("Not done: No clock app takes this", store.get(p.id)!!.outcome)
    }
}
