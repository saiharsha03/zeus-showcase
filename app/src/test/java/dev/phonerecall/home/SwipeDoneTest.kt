package dev.phonerecall.home

import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.data.dismiss
import dev.phonerecall.widgets.WidgetFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeDoneTest {
    private val F = WidgetFixtures
    private val day = F.now.toLocalDate().atStartOfDay(F.zone).toInstant().toEpochMilli()
    private fun birthday(id: Long, name: String) = CalItem(id, 1, "$name's birthday", day, day + 86_400_000L, true, EventCategory.BIRTHDAY, "Birthdays", null, null, true)
    private val ann = birthday(1, "Ann")
    private val bob = birthday(2, "Bob")

    private fun board(p: HomePrefs, at: Long = F.ms, events: List<CalItem> = listOf(ann, bob)) = CardEngine.build(
        BoardInputs(F.now.plusNanos((at - F.ms) * 1_000_000L), CalendarSnapshot(true, events, true), F.availability, false, p, Learned(), F.tasks, ClosetSnapshot(loaded = true), emptyList(), false),
    ).cards

    private fun birthdays(p: HomePrefs, at: Long = F.ms) = board(p, at).filter { it.id.startsWith("event:") && it.content.title.contains("birthday") }

    @Test fun eachBirthdayHasItsOwnInstanceKey() {
        val cards = birthdays(HomePrefs())
        assertEquals(2, cards.size)
        assertEquals(2, cards.map { it.instanceKey }.toSet().size)
        assertEquals(day + 86_400_000L, cards.first().relevantUntil)
    }

    @Test fun dismissingOneBirthdayLeavesTheOther() {
        val first = birthdays(HomePrefs()).first()
        val p = HomePrefs(dismissed = mapOf(first.instanceKey to first.relevantUntil!!))
        val left = birthdays(p)
        assertEquals(1, left.size)
        assertFalse(left.single().id == first.id)
    }

    @Test fun dismissalExpiresWithTheItem() {
        val first = birthdays(HomePrefs()).first()
        val p = HomePrefs(dismissed = mapOf(first.instanceKey to first.relevantUntil!!))
        assertTrue(board(p, at = F.ms).none { it.id == first.id })
        // Expired entry no longer hides the card
        val expired = HomePrefs(dismissed = mapOf(first.instanceKey to F.ms - 1))
        assertTrue(board(expired).any { it.id == first.id })
    }

    @Test fun kindStaysOnHomeAfterSwipe() {
        val first = birthdays(HomePrefs()).first()
        val p = HomePrefs(dismissed = mapOf(first.instanceKey to first.relevantUntil!!))
        assertTrue(CardEngine.onHome(CardKind.EVENT, p))
        assertTrue(p.hidden.isEmpty())
    }

    private fun meeting(id: Long, minsAhead: Long) = CalItem(id, 1, "Meeting $id", F.ms + minsAhead * 60_000L, F.ms + (minsAhead + 30) * 60_000L, false, EventCategory.WORK, "Work", null, null, false)

    private fun topLive(p: HomePrefs, events: List<CalItem>) = board(p, events = events).firstOrNull { it.content.live != null }

    @Test fun birthdayCardsCarryNoLiveInfo() {
        assertTrue(birthdays(HomePrefs()).all { it.content.live == null })
    }

    @Test fun birthdayBookCardsCarryNoLiveInfoInTheMorning() {
        val b = dev.phonerecall.brain.DayBirthday("sam", "Sam", F.now.toLocalDate(), true, setOf(dev.phonerecall.brain.BirthdayFrom.CONTACTS), emptySet(), day, day + 86_400_000L)
        val inputs = BoardInputs(F.now, CalendarSnapshot(true, emptyList(), true), F.availability, false, HomePrefs(), Learned(), F.tasks, ClosetSnapshot(loaded = true), emptyList(), false, birthdays = dev.phonerecall.brain.BirthdayBoard(listOf(b)))
        val cards = CardEngine.build(inputs).cards.filter { it.id == "birthday:${b.id}" }
        assertEquals(1, cards.size)
        assertEquals(null, cards.single().content.live)
    }

    @Test fun nowBarItemDismissedStaysGoneAfterRestartAndNewOnePosts() {
        val events = listOf(meeting(10, 30))
        val live = topLive(HomePrefs(), events)!!
        // The delete intent stores the key in prefs; a restart only re-reads prefs, so nothing is posted for it.
        val saved = HomePrefs().dismiss(live.instanceKey, live.relevantUntil, F.ms)
        // The dismissed meeting is gone from every card (its own and Next up); something else may take the Now Bar.
        org.junit.Assert.assertFalse(board(saved, events = events).any { it.content.live?.title == "Meeting 10" })
        // A different item still posts.
        assertEquals("Meeting 11", topLive(saved, events + meeting(11, 45))!!.content.title)
    }

    @Test fun homeDismissalExpiresAfterDayWhenUnknown() {
        val p = HomePrefs().dismiss("weather@1", null, F.ms)
        assertEquals(F.ms + 24 * 3_600_000L, p.dismissed["weather@1"])
    }
}
