package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A confirmation is bound to what he saw: the payload, its targets, the account, and a short window. */
class BindingTest {
    private var now = java.time.ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val store = MemoryStore()
    private val state = FakeState(calendars = listOf(CalendarChoice(3, "Personal", "owner@example.com"), CalendarChoice(4, "Work", "owner@work.example.com")))
    private val exec = FakeExecutor()
    private val fates = Fates(store, state, exec, { _, _, _ -> }, { now })

    private fun propose(kind: ProposalKind, vararg args: Pair<String, String>): Proposal =
        (fates.propose(listOf(RawStep(kind, mapOf(*args))), null, 1L) as ProposeResult.Created).proposal

    private fun event() = propose(ProposalKind.ADD_CALENDAR_EVENT, "title" to "Dentist", "start" to "2026-10-22T09:00", "calendar_id" to "3")

    private fun refusedWith(reason: String, d: Decision) {
        assertTrue("$d", d is Decision.Refused)
        assertEquals(reason, (d as Decision.Refused).reason)
        assertTrue("nothing ran", exec.ran.isEmpty())
    }

    @Test fun whatHeSawRuns() {
        val p = event()
        val seen = fates.bind(p)
        now += 60_000L
        assertTrue(fates.confirm(p.id, seen) is Decision.Done)
    }

    @Test fun aChangedPayloadIsRefused() {
        val p = propose(ProposalKind.ADD_TASK, "title" to "Book the oil change")
        val seen = fates.bind(p)
        fates.edit(p.id, 0, mapOf("title" to "Book the oil change and tyres"))   // changed after he looked
        refusedWith(Bindings.CHANGED, fates.confirm(p.id, seen))
        assertEquals(ProposalStatus.PENDING, store.get(p.id)!!.status)
        // A fresh look binds again, and then it runs.
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
    }

    @Test fun aChangedTargetIsRefused() {
        val p = event()
        val seen = fates.bind(p)
        // Same title and time, another calendar: the target moved.
        store.save(p.copy(steps = p.steps.map { it.copy(payload = it.payload + ("calendar_id" to "4") + ("calendar" to "Work")) }))
        refusedWith(Bindings.CHANGED, fates.confirm(p.id, seen))
    }

    @Test fun aChangedAccountIsRefused() {
        val p = event()
        val seen = fates.bind(p)
        // Calendar 3 now belongs to another Google account (re-added on the phone): not what he approved.
        state.calendars = listOf(CalendarChoice(3, "Personal", "someone.else@example.org"))
        refusedWith(Bindings.CHANGED, fates.confirm(p.id, seen))
        assertEquals(ProposalStatus.PENDING, store.get(p.id)!!.status)
    }

    @Test fun aChangedSelectionIsRefused() {
        val r = fates.propose(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "5")), RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Pack"))), "Trip", 1L) as ProposeResult.Created
        val seen = fates.bind(r.proposal)
        fates.toggle(r.proposal.id, 0)
        refusedWith(Bindings.CHANGED, fates.confirm(r.proposal.id, seen))
    }

    @Test fun anotherProposalsBindingIsRefused() {
        val a = propose(ProposalKind.SET_TIMER, "seconds" to "60")
        val b = propose(ProposalKind.SET_TIMER, "seconds" to "60")
        refusedWith(Bindings.CHANGED, fates.confirm(b.id, fates.bind(a)))
    }

    @Test fun outwardKindsExpireAfterTenMinutes() {
        val p = event()
        val seen = fates.bind(p)
        now += 10 * 60_000L
        refusedWith(Bindings.TIMED_OUT, fates.confirm(p.id, seen))
        assertEquals(ProposalStatus.PENDING, store.get(p.id)!!.status)
    }

    @Test fun phoneKindsLastADay() {
        val p = propose(ProposalKind.ADD_TASK, "title" to "Call the landlord")
        val seen = fates.bind(p)
        now += 23 * 3_600_000L
        assertTrue(fates.confirm(p.id, seen) is Decision.Done)
        val q = propose(ProposalKind.ADD_TASK, "title" to "Call the bank")
        val seenQ = fates.bind(q)
        now += 24 * 3_600_000L
        exec.ran.clear()
        // The proposal itself expired at 24 h too; either way nothing runs.
        assertTrue(fates.confirm(q.id, seenQ) !is Decision.Done)
        assertTrue(exec.ran.isEmpty())
    }

    @Test fun aBindingFromTheFutureIsRefused() {
        val p = propose(ProposalKind.SET_TIMER, "seconds" to "60")
        refusedWith(Bindings.TIMED_OUT, fates.confirm(p.id, fates.bind(p).copy(boundAt = now + 60_000L)))
    }

    @Test fun thePhoneMovingUnderneathSendsItBackForAnotherLook() {
        val p = propose(ProposalKind.ADD_TO_LIST, "section" to "Groceries", "items" to "tomatoes, garlic")
        val seen = fates.bind(p)
        // Garlic got on the list another way: what would run now is only tomatoes, not what he saw.
        val withGarlic = object : LocalState by state { override fun listItems(section: String) = listOf("garlic") }
        val f2 = Fates(store, withGarlic, exec, { _, _, _ -> }, { now })
        refusedWith(Bindings.CHANGED, f2.confirm(p.id, seen))
        val back = store.get(p.id)!!
        assertEquals(ProposalStatus.PENDING, back.status)
        assertEquals("tomatoes", back.steps.single().payload["items"])
        assertTrue(f2.confirm(p.id, f2.bind(back)) is Decision.Done)
    }

    // ---- the pure check, field by field ----

    @Test fun eachBoundFieldIsChecked() {
        val b = Binding(1, "hash", "task_id=7", "phone", now)
        assertNull(Bindings.check(b, b, 2, now + 1))
        assertEquals(Bindings.CHANGED, Bindings.check(b, b.copy(payloadHash = "other"), 2, now + 1))
        assertEquals(Bindings.CHANGED, Bindings.check(b, b.copy(target = "task_id=8"), 2, now + 1))
        assertEquals(Bindings.CHANGED, Bindings.check(b, b.copy(account = "x@example.org"), 2, now + 1))
        assertEquals(Bindings.CHANGED, Bindings.check(b, b.copy(proposalId = 2), 2, now + 1))
        assertEquals(Bindings.TIMED_OUT, Bindings.check(b, b, 3, now + 10 * 60_000L))
        assertEquals(Bindings.TIMED_OUT, Bindings.check(b, b, 4, now + 10 * 60_000L))
        assertNull(Bindings.check(b, b, 2, now + 10 * 60_000L))
        assertEquals(Bindings.TIMED_OUT, Bindings.check(b, b, 2, now + 24 * 3_600_000L))
    }

    @Test fun theHashCoversEveryPayloadValueButNotTheWording() {
        val s = Step(ProposalKind.ADD_TASK, mapOf("title" to "A", "list" to "NEXT"), "Add task: A")
        assertEquals(Bindings.payloadHash(listOf(s)), Bindings.payloadHash(listOf(s.copy(summary = "reworded"))))
        assertEquals(Bindings.payloadHash(listOf(s)), Bindings.payloadHash(listOf(s.copy(payload = mapOf("list" to "NEXT", "title" to "A")))))
        assertTrue(Bindings.payloadHash(listOf(s)) != Bindings.payloadHash(listOf(s.copy(payload = s.payload + ("list" to "INBOX")))))
    }

    @Test fun aSchedulesWholePromptIsShownAndBound() {
        val prompt = "Review the week. ".repeat(30) + "Then email the summary to everyone."
        val st = Step(ProposalKind.SET_SCHEDULE, mapOf("action" to "create", "name" to "weekly-review", "schedule" to "0 18 * * 5", "prompt" to prompt), "New schedule")
        assertTrue(prompt.length > PROMPT_PEEK)
        assertEquals(prompt, Validate.facts(st, java.time.ZoneId.of("America/New_York")).toMap()["Each run"])
        assertEquals(prompt, Bindings.schedulePrompt(st))
        // A change past what a short card would show still breaks the look he confirmed.
        val tail = st.copy(payload = st.payload + ("prompt" to prompt.replace("everyone", "a stranger")))
        assertTrue(Bindings.target(listOf(st)) != Bindings.target(listOf(tail)))
        val seen = Binding(1, Bindings.payloadHash(listOf(st)), Bindings.target(listOf(st)), "phone", now)
        val cur = Binding(1, Bindings.payloadHash(listOf(tail)), Bindings.target(listOf(tail)), "phone", now)
        assertEquals(Bindings.CHANGED, Bindings.check(seen, cur, 3, now + 1))
        assertNull(Bindings.schedulePrompt(Step(ProposalKind.ADD_TASK, mapOf("prompt" to "x"), "t")))
    }
}
