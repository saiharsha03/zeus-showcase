package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AttentionTest {
    private data class C(val id: String, val u: Urgency = Urgency.NICE)

    private fun split(items: List<C>, shown: Set<String> = emptySet(), budget: Int = 5) = Attention.cards(items, { it.id }, { it.u }, shown, budget)

    @Test fun niceToHavesFillTheBudgetAndTheRestWaitForTheBrief() {
        val s = split((1..8).map { C("n$it") })
        assertEquals((1..5).map { "n$it" }, s.show.map { it.id })
        assertEquals(listOf("n6", "n7", "n8"), s.batch.map { it.id })
    }

    @Test fun urgentCardsAlwaysShowAndUseTheBudget() {
        val items = listOf(C("a", Urgency.SAFETY), C("b", Urgency.DEADLINE_TODAY), C("c", Urgency.TIME_CRITICAL), C("n1"), C("n2"), C("n3"))
        val s = split(items, budget = 4)
        assertEquals(listOf("a", "b", "c", "n1"), s.show.map { it.id })
        assertEquals(listOf("n2", "n3"), s.batch.map { it.id })
        // Over budget already: urgent still shows, nice waits.
        val full = split(listOf(C("x", Urgency.SAFETY), C("n9")), shown = setOf("1", "2", "3", "4", "5"))
        assertEquals(listOf("x"), full.show.map { it.id })
        assertEquals(listOf("n9"), full.batch.map { it.id })
    }

    @Test fun aCardThatShowedTodayStays() {
        val s = split(listOf(C("n1"), C("n2")), shown = setOf("n2", "o1", "o2", "o3", "o4"))
        assertEquals(listOf("n2"), s.show.map { it.id })
        assertEquals(listOf("n1"), s.batch.map { it.id })
    }

    @Test fun aQuietDayStaysQuiet() {
        val s = split(emptyList())
        assertTrue(s.show.isEmpty() && s.batch.isEmpty())
        assertNull("no brief line when nothing waited", Attention.briefLine(emptyList()))
        // Two cards on a budget of five: two cards, nothing invented to fill it.
        assertEquals(2, split(listOf(C("a"), C("b"))).show.size)
    }

    @Test fun notificationsHaveTheirOwnBudget() {
        assertTrue(Attention.mayNotify(Urgency.NICE, 2, 3))
        assertFalse(Attention.mayNotify(Urgency.NICE, 3, 3))
        for (u in listOf(Urgency.SAFETY, Urgency.DEADLINE_TODAY, Urgency.TIME_CRITICAL)) assertTrue(u.name, Attention.mayNotify(u, 99, 3))
    }

    @Test fun whatWaitsGoesToTheNextMorningsBrief() {
        val z = java.time.ZoneId.of("America/New_York")
        assertEquals("2026-10-20", Attention.briefDay(java.time.ZonedDateTime.of(2026, 10, 19, 21, 0, 0, 0, z)).toString())
        assertEquals("2026-10-19", Attention.briefDay(java.time.ZonedDateTime.of(2026, 10, 19, 2, 0, 0, 0, z)).toString())
    }

    @Test fun theBriefLineIsShort() {
        assertEquals("Delivery update, Worth a look", Attention.briefLine(listOf(DigestItem("1", "Delivery update"), DigestItem("2", "Worth a look"))))
        assertEquals("A, B and 3 more", Attention.briefLine("ABCDE".map { DigestItem("$it", "$it") }))
    }

    @Test fun usefulIsAskedAtMostOnceADayOnANewKind() {
        val today = "2026-10-19"
        val rnd = Random(4)
        val unrated = setOf(ProposalKind.ADD_TO_LIST, ProposalKind.CREATE_CARD)
        val k = Attention.pickAsk(listOf(ProposalKind.ADD_TO_LIST, ProposalKind.SET_ALARM, ProposalKind.CREATE_CARD), unrated, AttentionDay(today), today, rnd)
        assertTrue(k in unrated)
        assertNull("already picked today", Attention.pickAsk(listOf(ProposalKind.ADD_TO_LIST), unrated, AttentionDay(today, ask = "add_to_list"), today, rnd))
        assertNull("already answered today", Attention.pickAsk(listOf(ProposalKind.ADD_TO_LIST), unrated, AttentionDay(today, asked = true), today, rnd))
        assertNull("only kinds he never rated", Attention.pickAsk(listOf(ProposalKind.SET_ALARM), unrated, AttentionDay(today), today, rnd))
        assertEquals("a new day may ask again", ProposalKind.ADD_TO_LIST, Attention.pickAsk(listOf(ProposalKind.ADD_TO_LIST), unrated, AttentionDay("2026-10-18", asked = true), today, rnd))
        assertNull("never the meta-proposal", Attention.pickAsk(listOf(ProposalKind.ADD_AUTO_APPROVE), setOf(ProposalKind.ADD_AUTO_APPROVE), AttentionDay(today), today, rnd))
    }

    @Test fun budgetStepsCycle() {
        assertEquals(8, AttentionBudget.next(AttentionBudget.CARD_STEPS, 5))
        assertEquals(3, AttentionBudget.next(AttentionBudget.CARD_STEPS, 12))
        assertEquals(AttentionBudget(5, 3), AttentionBudget())
    }

    @Test fun generatedCardsKnowTheirUrgency() {
        val g = dev.phonerecall.home.GeneratedCard("t", "b", emptyList(), 0, dev.phonerecall.home.CardSource.ATHENA)
        assertEquals(Urgency.NICE, g.urgency)
        assertEquals(Urgency.DEADLINE_TODAY, dev.phonerecall.home.GeneratedCard("t", "b", emptyList(), 0, dev.phonerecall.home.CardSource.ATHENA, dueToday = true).urgency)
        assertEquals(Urgency.SAFETY, dev.phonerecall.home.GeneratedCard("t", "b", emptyList(), 0, dev.phonerecall.home.CardSource.SECURITY).urgency)
    }
}
