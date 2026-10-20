package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FatigueTest {
    private val day = Fatigue.DAY
    private var now = java.time.ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val store = MemoryStore()
    private val state = FakeState()
    private val exec = FakeExecutor()
    private val meter = MemoryMeter()
    private val log = ArrayList<String>()
    private val fates = Fates(store, state, exec, { a, b, c -> log += "$a $b $c" }, { now }, meter)

    private fun events(kind: ProposalKind, signal: Signal, n: Int, from: Long = now - 20 * day, every: Long = day) =
        (0 until n).map { KindEvent(kind, signal, from + it * every) }

    // ---- the meta-proposal ----

    @Test fun fifteenConfirmsAndNoRejectsEarnsOneOffer() {
        val e = events(ProposalKind.ADD_REMINDER, Signal.CONFIRM, 15)
        assertEquals(ProposalKind.ADD_REMINDER, Fatigue.offer(e, emptySet(), now))
        assertNull("14 is not enough", Fatigue.offer(events(ProposalKind.ADD_REMINDER, Signal.CONFIRM, 14), emptySet(), now))
        assertNull("one rejection spoils it", Fatigue.offer(e + KindEvent(ProposalKind.ADD_REMINDER, Signal.REJECT, now - day), emptySet(), now))
        assertNull("one 'not useful' too", Fatigue.offer(e + KindEvent(ProposalKind.ADD_REMINDER, Signal.NOT_USEFUL, now - day), emptySet(), now))
        assertNull("already on the list", Fatigue.offer(e, setOf(ProposalKind.ADD_REMINDER), now))
    }

    @Test fun confirmsOlderThanThirtyDaysDontCount() {
        val old = events(ProposalKind.ADD_TASK, Signal.CONFIRM, 15, from = now - 45 * day, every = day / 2)
        assertNull(Fatigue.offer(old, emptySet(), now))
        assertEquals(0, Fatigue.stats(old, ProposalKind.ADD_TASK, now).confirms)
    }

    @Test fun neverOfferedForAKindThatMayNotBeAutomatic() {
        for (k in listOf(ProposalKind.COMPLETE_TASK, ProposalKind.ADD_CALENDAR_EVENT, ProposalKind.ADD_BIRTHDAY, ProposalKind.PLAY_MUSIC, ProposalKind.HOME_CONTROL, ProposalKind.SET_RELATION, ProposalKind.ADD_AUTO_APPROVE)) {
            assertNull(k.wire, Fatigue.offer(events(k, Signal.CONFIRM, 40), emptySet(), now))
        }
    }

    @Test fun atMostOneOfferAWeekAndNotTheSameKindForAMonth() {
        val e = events(ProposalKind.ADD_REMINDER, Signal.CONFIRM, 20) + events(ProposalKind.SET_TIMER, Signal.CONFIRM, 16)
        val offered = KindEvent(ProposalKind.ADD_REMINDER, Signal.OFFERED, now - 3 * day)
        assertNull("one this week already", Fatigue.offer(e + offered, emptySet(), now))
        // A week on, the next steady kind; the declined one waits its 30 days.
        assertEquals(ProposalKind.SET_TIMER, Fatigue.offer(e + offered, emptySet(), now + 5 * day))
    }

    @Test fun theSteadiestKindWins() {
        val e = events(ProposalKind.SET_TIMER, Signal.CONFIRM, 16) + events(ProposalKind.ADD_TASK, Signal.CONFIRM, 22, every = day / 2)
        assertEquals(ProposalKind.ADD_TASK, Fatigue.offer(e, emptySet(), now))
    }

    @Test fun fatesMakesTheMetaProposalOnceAndItNeedsHisYes() {
        meter.all += events(ProposalKind.ADD_REMINDER, Signal.CONFIRM, 15)
        val p = fates.offerAutoApprove()!!
        assertEquals("Add reminder to auto-approve?", p.title)
        assertEquals(ProposalKind.ADD_AUTO_APPROVE, p.steps.single().kind)
        assertEquals(ProposalStatus.PENDING, p.status)
        assertTrue("never automatic", exec.ran.isEmpty())
        assertNull("one a week", fates.offerAutoApprove())
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
        assertEquals("add_reminder", exec.ran.single().payload["kind"])
        // His answer to the meta-proposal isn't itself measured.
        assertTrue(meter.all.none { it.kind == ProposalKind.ADD_AUTO_APPROVE })
    }

    @Test fun theMetaProposalRefusesAForbiddenKind() {
        assertTrue(Validate.step(ProposalKind.ADD_AUTO_APPROVE, mapOf("kind" to "add_calendar_event"), state, now) is Check.Bad)
        assertTrue(Validate.step(ProposalKind.ADD_AUTO_APPROVE, mapOf("kind" to "home_control"), state, now) is Check.Bad)
        assertTrue(Validate.step(ProposalKind.ADD_AUTO_APPROVE, mapOf("kind" to "nope"), state, now) is Check.Bad)
        assertTrue(Validate.step(ProposalKind.ADD_AUTO_APPROVE, mapOf("kind" to "set_timer"), state, now) is Check.Ok)
        assertFalse("a model can't call it", ProposalKind.ADD_AUTO_APPROVE.modelTool)
        assertFalse("it is never on the list itself", ProposalKind.ADD_AUTO_APPROVE in ProposalKind.autoCandidates)
    }

    // ---- quiet ----

    @Test fun threeRejectionsInFourteenDaysQuietAKindForThirtyDays() {
        val e = listOf(now - 10 * day, now - 5 * day, now - day).map { KindEvent(ProposalKind.CREATE_CARD, Signal.REJECT, it) }
        assertEquals(now - day + Fatigue.QUIET_FOR, Fatigue.quietUntil(e, ProposalKind.CREATE_CARD, now))
        assertNull("other kinds stay", Fatigue.quietUntil(e, ProposalKind.ADD_TASK, now))
        assertNull("it wakes after 30 days", Fatigue.quietUntil(e, ProposalKind.CREATE_CARD, now + 30 * day))
    }

    @Test fun rejectionsSpreadWiderThanFourteenDaysDontQuiet() {
        val e = listOf(now - 29 * day, now - 15 * day, now - day).map { KindEvent(ProposalKind.CREATE_CARD, Signal.REJECT, it) }
        assertNull(Fatigue.quietUntil(e, ProposalKind.CREATE_CARD, now))
        assertNull("two are not three", Fatigue.quietUntil(e.drop(1).take(1) + e.last(), ProposalKind.CREATE_CARD, now))
    }

    @Test fun notUsefulCountsAsANo() {
        val e = listOf(KindEvent(ProposalKind.ADD_TO_LIST, Signal.REJECT, now - 3 * day), KindEvent(ProposalKind.ADD_TO_LIST, Signal.NOT_USEFUL, now - 2 * day), KindEvent(ProposalKind.ADD_TO_LIST, Signal.REJECT, now - day))
        assertNotNull(Fatigue.quietUntil(e, ProposalKind.ADD_TO_LIST, now))
    }

    @Test fun aQuietKindIsHeldBackUnlessHeAsks() {
        repeat(3) { i ->
            val p = (fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Task $i"))), null, null) as ProposeResult.Created).proposal
            fates.reject(p.id); now += day
        }
        val held = fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to "One more"))), null, null)
        assertTrue(held is ProposeResult.Invalid)
        assertTrue((held as ProposeResult.Invalid).reason.contains("quiet until"))
        assertTrue(log.any { it.startsWith("Fates held back") })
        // Delphi (a conversation) is him asking: allowed.
        assertTrue(fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Asked for"))), null, 1L) is ProposeResult.Created)
        // Other kinds carry on.
        assertTrue(fates.propose(listOf(RawStep(ProposalKind.SET_TIMER, mapOf("seconds" to "60"))), null, null) is ProposeResult.Created)
        now += 31 * day
        assertTrue("awake again", fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Later"))), null, null) is ProposeResult.Created)
    }

    // ---- what is measured ----

    @Test fun everyDecisionFeedsTheMeter() {
        val a = (fates.propose(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "6"))), null, 1L) as ProposeResult.Created).proposal
        fates.confirmNow(a.id, store)
        val b = (fates.propose(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "7"))), null, 1L) as ProposeResult.Created).proposal
        fates.reject(b.id)
        val c = (fates.propose(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "8"))), null, 1L) as ProposeResult.Created).proposal
        fates.edit(c.id, 0, mapOf("hour" to "9"))
        now += 13 * 3_600_000L
        fates.sweep()
        fates.useful(ProposalKind.SET_ALARM, true)
        val s = fates.stats()[ProposalKind.SET_ALARM]!!.first
        assertEquals(KindStats(confirms = 1, rejects = 1, edits = 1, ignores = 1, useful = 1), s)
        assertFalse(ProposalKind.SET_ALARM in fates.unrated())
    }

    @Test fun autoApprovedRunsAreNotHisConfirms() {
        fates.autoApprove = setOf(ProposalKind.SET_TIMER)
        val r = fates.propose(listOf(RawStep(ProposalKind.SET_TIMER, mapOf("seconds" to "300"))), null, 1L) as ProposeResult.Created
        assertTrue(r.decision is Decision.Done)
        assertTrue(meter.all.none { it.signal == Signal.CONFIRM })
    }

    @Test fun onlySelectedStepsOfAPlanAreMeasured() {
        val r = fates.propose(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "5")), RawStep(ProposalKind.ADD_TASK, mapOf("title" to "Pack"))), "Trip", 1L) as ProposeResult.Created
        fates.toggle(r.proposal.id, 1)
        fates.confirmNow(r.proposal.id, store)
        assertEquals(listOf(ProposalKind.SET_ALARM), meter.all.map { it.kind })
    }
}
