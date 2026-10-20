package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.time.ZonedDateTime

/** The SQLite and SharedPreferences sides: the meter's table, the outbound log, the attention tally. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Fates2StoreTest {
    private val app = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("America/New_York")
    private var now = ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, zone)

    @Test fun theMeterReadsBackWhatItRecorded() {
        val db = BrainDb(app)
        val t = now.toInstant().toEpochMilli()
        db.record(KindEvent(ProposalKind.ADD_TASK, Signal.CONFIRM, t - 40 * Fatigue.DAY))
        db.record(KindEvent(ProposalKind.ADD_TASK, Signal.REJECT, t - Fatigue.DAY))
        assertEquals(listOf(KindEvent(ProposalKind.ADD_TASK, Signal.REJECT, t - Fatigue.DAY)), db.events(t - 30 * Fatigue.DAY))
    }

    @Test fun theOutboundLogMergesRepeatsAndKeepsAWeek() {
        val db = BrainDb(app)
        val t = now.toInstant().toEpochMilli()
        val tile = OutboundRow(t - 9 * Fatigue.DAY, "tilecache.rainviewer.com", "Aeolia radar and map", emptySet())
        db.logOutbound(tile)
        for (i in 0 until 5) db.logOutbound(tile.copy(at = t + i * 60_000L))           // five tiles in five minutes: one row
        db.logOutbound(tile.copy(at = t + 30 * 60_000L))                                 // half an hour later: a new row
        db.logOutbound(OutboundRow(t, "api.tomtom.com", "Odyssey traffic", setOf(FieldCat.COORDINATES, FieldCat.KEY)))
        val rows = db.outbound(t - 30 * Fatigue.DAY)
        assertEquals("the 9-day-old row was dropped", 3, rows.size)
        assertEquals(listOf(1, 5), rows.filter { it.host == "tilecache.rainviewer.com" }.map { it.count })
        assertEquals(setOf(FieldCat.COORDINATES, FieldCat.KEY), rows.single { it.host == "api.tomtom.com" }.fields)
    }

    @Test fun overBudgetNotificationsWaitForTheBriefOnce() {
        val a = AttentionStore(app) { now }
        a.setBudget(AttentionBudget(cards = 2, notifications = 1))
        var posted = 0
        assertTrue(a.notify(Urgency.NICE, "result:1", "Liverpool 2-1") { posted++ })
        assertFalse(a.notify(Urgency.NICE, "result:2", "Arsenal 0-0") { posted++ })
        assertFalse(a.notify(Urgency.NICE, "result:2", "Arsenal 0-0") { posted++ })
        assertTrue("time-critical never waits", a.notify(Urgency.TIME_CRITICAL, "arrival:w", "Walmart") { posted++ })
        assertEquals(2, posted)
        assertEquals(listOf("Arsenal 0-0"), a.digest().map { it.title })
        assertTrue("not in today's brief", a.forBrief().isEmpty())
        now = now.plusDays(1).withHour(7)
        assertEquals(listOf("Arsenal 0-0"), a.forBrief().map { it.title })
        assertEquals("a new day starts at zero", 0, a.day().notified)
    }

    @Test fun cardsOverBudgetGoToTheDigestAndShownOnesStay() {
        val a = AttentionStore(app) { now }
        a.setBudget(AttentionBudget(cards = 2, notifications = 3))
        val cards = listOf("Delivery update", "Worth a look", "Closed for you")
        assertEquals(cards.take(2), a.cards(cards, { it }, { it }, { Urgency.NICE }))
        assertEquals(listOf("Closed for you"), a.digest().map { it.title })
        // Later the same day, a new nice card can't push one that already showed off the board.
        assertEquals(cards.take(2), a.cards(listOf("New note") + cards, { it }, { it }, { Urgency.NICE }))
    }

    @Test fun theAskIsPickedOnceAndSpentWhenAnswered() {
        val a = AttentionStore(app) { now }
        val k = a.ask(listOf(ProposalKind.ADD_TO_LIST), setOf(ProposalKind.ADD_TO_LIST))
        assertEquals(ProposalKind.ADD_TO_LIST, k)
        assertEquals("the same one until answered", k, a.ask(listOf(ProposalKind.CREATE_CARD), setOf(ProposalKind.CREATE_CARD)))
        a.answered()
        assertEquals(null, a.ask(listOf(ProposalKind.CREATE_CARD), setOf(ProposalKind.CREATE_CARD)))
    }
}
