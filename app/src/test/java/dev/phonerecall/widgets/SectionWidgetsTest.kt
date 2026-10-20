package dev.phonerecall.widgets

import dev.phonerecall.argo.ArgoCards
import dev.phonerecall.argo.ArgoData
import dev.phonerecall.hestia.FakeHomeBackend
import dev.phonerecall.hestia.HestiaPicks
import dev.phonerecall.hestia.Link
import dev.phonerecall.iaso.IasoState
import dev.phonerecall.odyssey.OdysseyData
import dev.phonerecall.widgets.WidgetFixtures.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The widgets for the newer sections: Argo, Odyssey, Moneta, Hestia, Iaso, Eos and Agora. */
class SectionWidgetsTest {
    private val F = WidgetFixtures
    private val ms = F.ms
    private val zone = F.zone
    private val MIN = 60_000L
    private val DAY = 86_400_000L

    private fun rows(v: WView, group: String) = v.groups.first { it.label == group }.rows
    private val S = SectionFixtures
    private val odyssey = S.odyssey
    private val txns = S.txns
    private val netflix = S.netflix
    private val brief = S.brief
    private val room = S.room
    private fun at(h: Int, m: Int = 0) = S.at(h, m)
    private fun msg(id: Long, sender: String, text: String, minAgo: Long) = S.msg(id, sender, text, minAgo)

    // ---- Argo ----

    @Test fun tripsCountsDownInDays() {
        val v = ArgoCards.widget(S.argo, ms, zone)
        assertEquals(WKind.TRIPS, v.kind)
        assertEquals("Denver in 45 days", v.title)
        assertEquals("No trip planned", ArgoCards.widget(ArgoData(), ms, zone).title)
    }

    @Test fun tripsOnTheTravelDayShowTheLegAndWhenToLeave() {
        val v = ArgoCards.widget(ArgoData(listOf(S.flyingToday)), at(12), zone)
        assertEquals("XY 123 at 3:42 PM", v.title)
        assertEquals("Leave by 1:42 PM", v.sub)
        assertEquals(null, v.place)
        assertEquals(ArgoCards.DEST, rows(v, "Next leg").first().dest)
    }

    // ---- Odyssey ----

    @Test fun driveShowsTheLastDriveTheWeekAndTheLeaveBy() {
        val v = WidgetContent.drive(odyssey, now)
        assertEquals("Score 92 · 11 mi", v.title)
        assertEquals("24 min · Commute · 6:30 AM", v.sub)
        assertEquals("11 of ~192 mi", rows(v, "This week").single().text)
        assertEquals(5, v.bar)
        assertEquals(WRow("Leave by 8:50 AM", "+6 min", WTone.ACTIVE, Mark.DOT, dest = "ODYSSEY"), rows(v, "Commute").single())
        assertEquals(WTone.OK, v.tone)
        assertTrue(WidgetSafety.isSafe(WidgetSafety.seal(v)))
    }

    @Test fun driveWithoutDrivesSaysSo() {
        val v = WidgetContent.drive(OdysseyData(), now)
        assertEquals("No drives yet", v.title)
        assertTrue(v.groups.isEmpty())
    }

    // ---- Moneta ----

    @Test fun moneyShowsPercentsAndCountsNeverAmounts() {
        val v = WidgetSafety.seal(WidgetContent.money(txns, listOf(netflix), emptySet(), now))
        assertEquals("70% of a usual week", v.title)
        assertEquals(70, v.bar)
        assertEquals(WRow("Did you make this?", "1 to check", WTone.ALERT, Mark.DOT, dest = "MONEY"), rows(v, "To look at").single())
        assertEquals(WRow("Netflix", "Oct 24", WTone.QUIET, Mark.DOT, dest = "MONEY"), rows(v, "Next due").single())
        assertTrue(WidgetSafety.isSafe(v))
        for (t in WidgetSafety.texts(v)) assertFalse(t, Regex("[$¢]|15\\.49|\\b(40|15|100)\\b").containsMatchIn(t))
    }

    @Test fun moneyDropsAnyLineWithAnAmount() {
        val v = WidgetSafety.seal(WidgetContent.money(txns, listOf(netflix.copy(name = "Netflix $15.49")), setOf("${ms - (0.9 * DAY).toLong()}:1500:Uber"), now))
        assertTrue(rows(v, "Next due").isEmpty())
        assertEquals("Nothing to check", rows(v, "To look at").single().text)
        assertEquals("No usual week yet", WidgetContent.money(emptyList(), emptyList(), emptySet(), now).title)
    }

    @Test fun ownWidgetsKeepTheirWordsButNeverAnAmountOrASecret() {
        assertEquals("Weight 72.4 kg", WidgetSafety.clean("Weight 72.4 kg", WKind.HEALTH))
        assertNull(WidgetSafety.clean("Weight 72.4 kg"))
        assertEquals("Next bill: Netflix", WidgetSafety.clean("Next bill: Netflix", WKind.MONEY))
        assertNull(WidgetSafety.clean("Next bill: Netflix"))
        for (bad in listOf("Netflix $15.49", "Paid 15.49 at Fresh Mart", "12 dollars at Fresh Mart", "Root password is hunter2", "Card ending 4417")) {
            assertNull(bad, WidgetSafety.clean(bad, WKind.MONEY))
        }
        // Only Moneta's and Iaso's own widgets get the carve-out.
        assertEquals(setOf(WKind.MONEY, WKind.HEALTH), WKind.entries.filter { it.own }.toSet())
    }

    // ---- Hestia ----

    @Test fun hearthShowsHisPicksAsSwitchesAndNeverALock() {
        val s = FakeHomeBackend.sample()
        val v = WidgetContent.hearth(s, listOf("d9", "d8", "d1"))
        assertEquals("3 lights on", v.title)
        assertEquals(
            listOf(WRow("Porch light", "Off", WTone.QUIET, Mark.DOT, dest = "DEVICES", act = "d9|on"), WRow("Floor lamp", "On", WTone.OK, Mark.DOT, dest = "DEVICES", act = "d1|off")),
            v.groups.single().rows,
        )
        // No picks: the first lights and plugs it controls, reachable ones first; an offline one has no switch.
        assertEquals(listOf("Floor lamp", "TV backlight", "Air purifier plug", "Bedside lamp"), WidgetContent.hearth(s, emptyList()).groups.single().rows.map { it.text })
        assertNull(WidgetContent.hearth(s, listOf("d7")).groups.single().rows.single().act)
        assertEquals("Not connected", WidgetContent.hearth(FakeHomeBackend.sample(Link.NeedsPermission), emptyList()).title)
    }

    @Test fun hestiaPicksKeepFourInHisOrder() {
        assertEquals(listOf("a", "b"), HestiaPicks.toggle(listOf("a"), "b"))
        assertEquals(listOf("b"), HestiaPicks.toggle(listOf("a", "b"), "a"))
        assertEquals(listOf("b", "c", "d", "e"), HestiaPicks.toggle(listOf("a", "b", "c", "d"), "e"))
    }

    // ---- Iaso ----

    @Test fun healthShowsStepsWeightAndSleep() {
        val v = WidgetContent.health(S.health, now)
        assertEquals("6,420 steps", v.title)
        assertEquals(listOf("6,420 steps" to "of 8,000", "72.6 kg" to "Down 0.3 kg a week", "Slept 7 h 12 min" to "Last night"), v.groups.single().rows.map { it.text to it.meta })
        assertEquals("Checked 8:25 AM", v.foot)
        assertEquals(v, WidgetSafety.seal(v))
        assertEquals("Nothing read yet", WidgetContent.health(IasoState(), now).title)
    }

    // ---- Eos ----

    @Test fun briefShowsTheLedeAndTopThreeLines() {
        val v = WidgetContent.brief(brief, now)
        assertEquals("Two meetings, then Barça", v.title)
        assertEquals("Monday, Oct 19", v.sub)
        assertEquals(listOf("Weather", "First", "Leave by"), v.groups.single().rows.map { it.meta })
        assertEquals("8:00 AM", v.foot)
        assertEquals("This morning", WidgetContent.brief(brief, now.withHour(14)).sub)
        assertEquals("No brief yet today", WidgetContent.brief(brief.copy(date = "2026-10-18"), now).title)
        assertEquals("No brief yet today", WidgetContent.brief(null, now).title)
    }

    // ---- Agora ----

    @Test fun roomShowsTheLatestPostWithItsUnreadDot() {
        val v = WidgetContent.room(room, now)
        assertEquals("Iris: Denver hotel is booked for Dec 3", v.title)
        assertEquals(WTone.ACTIVE, v.tone)
        assertEquals(listOf("Iris · 8:14 AM", "You · 8:00 AM"), rows(v, "Latest").map { it.meta })
        val seen = WidgetContent.room(room.copy(agoraSeen = 2), now)
        assertEquals(WTone.QUIET, seen.tone)
        assertEquals("", seen.sub)
        val secret = WidgetContent.room(room.copy(chat = mapOf("agora" to listOf(msg(4, "atlas", "Root password is hunter2", 1)))), now)
        assertEquals("Atlas: A message to read in Zeus", secret.title)
        assertEquals("Not paired", WidgetContent.room(room.copy(pairing = dev.phonerecall.lemnos.DeviceKeys.State.NONE), now).title)
    }

    // ---- every new widget ----

    @Test fun plainNamesSwapEveryNewKicker() {
        val names = mapOf(
            WKind.TRIPS to ("Argo" to "Trips"), WKind.DRIVE to ("Odyssey" to "Places"), WKind.MONEY to ("Moneta" to "Money"), WKind.HEARTH to ("Hestia" to "Devices"),
            WKind.HEALTH to ("Iaso" to "Health"), WKind.BRIEF to ("Eos" to "Morning brief"), WKind.ROOM to ("Agora" to "Agent room"),
        )
        for ((k, n) in names) {
            assertEquals(n.first, WView(k, "x").kicker(false))
            assertEquals(n.second, WView(k, "x").kicker(true))
        }
    }

    @Test fun newWidgetsOfferSmallShortAndMedium() {
        for (k in listOf(WKind.TRIPS, WKind.DRIVE, WKind.MONEY, WKind.HEARTH, WKind.HEALTH, WKind.BRIEF, WKind.ROOM)) {
            assertEquals("$k", listOf(Bucket.SMALL, Bucket.SHORT, Bucket.MEDIUM), WidgetViews.buckets(k))
        }
    }
}
