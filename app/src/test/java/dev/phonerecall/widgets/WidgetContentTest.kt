package dev.phonerecall.widgets

import dev.phonerecall.data.Learned
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.home.BoardInputs
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.CardEngine
import dev.phonerecall.home.CardKind
import dev.phonerecall.home.CardPhoto
import dev.phonerecall.home.CardSize
import dev.phonerecall.home.Fact
import dev.phonerecall.home.ScoredCard
import dev.phonerecall.lemnos.Alert
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.IrisTask
import dev.phonerecall.lemnos.LemnosState
import dev.phonerecall.music.NowPlaying
import dev.phonerecall.music.Player
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.widgets.WidgetFixtures.events
import dev.phonerecall.widgets.WidgetFixtures.now
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContentTest {
    private val F = WidgetFixtures

    private fun rows(v: WView, group: String) = v.groups.first { it.label == group }.rows
    private fun card(kind: CardKind, title: String, learnKey: String = kind.name, tone: Tone = Tone.QUIET, placeholder: Boolean = false, neverHide: String? = null) =
        ScoredCard(kind.name, kind, learnKey, kind.label, CardContent(title, tone = tone, placeholder = placeholder), 10f, CardSize.NORMAL, Tint.NEUTRAL, false, false, neverHide != null, neverHide)

    // ---- the builders ----

    @Test fun todayShowsWhatIsNextTasksCardsAndTheBirthday() {
        val cards = listOf(
            card(CardKind.BRIEF, "Two meetings, then Barça"),
            card(CardKind.GENERATED, "Card ending 4417 charged", learnKey = "GENERATED:BANK", neverHide = "Bank alert"),
            card(CardKind.WEATHER, "Weather moves in with phase 4", placeholder = true),
            card(CardKind.SECURITY, "1 alert from Cerberus", tone = Tone.ALERT),
        )
        val v = WidgetContent.today(now, cards, events, F.availability, F.tasks)
        assertEquals("Monday, October 19", v.title)
        assertEquals("Office day · Alex's birthday", v.sub)
        // Past events drop off; tomorrow's carry the day; a health event shows only as busy.
        assertEquals(listOf("Team standup", "Data review with Taylor", "Barça v Sevilla", "Busy", "Sprint planning"), rows(v, "Next").map { it.text })
        assertEquals("Tomorrow 10:00 AM", rows(v, "Next")[3].meta)
        assertEquals("Renew car registration", rows(v, "Praxis").first().text)
        assertEquals(listOf("Cerberus needs you", "Two meetings, then Barça"), rows(v, "Olympus").map { it.text })
        assertTrue(rows(v, "Aeolia").isEmpty())
        assertEquals(WTone.ALERT, v.tone)
    }

    @Test fun todayShowsAeoliaWeatherAndSevereAlertsOnly() {
        val ms = now.toInstant().toEpochMilli()
        val w = dev.phonerecall.aeolia.WeatherState(tempC = 10.0, highC = 12.0, lowC = 4.0, next = "Rain from 3 PM", updatedAt = ms - 60_000, loaded = true,
            alerts = listOf(dev.phonerecall.aeolia.WeatherAlert("a", "Wind Advisory", "Moderate", ends = ms + 3_600_000L), dev.phonerecall.aeolia.WeatherAlert("b", "Lake Effect Snow Warning", "Severe", ends = ms + 3_600_000L)))
        val r = WidgetContent.weatherRows(w, ms)
        assertEquals(listOf("Lake Effect Snow Warning", "50° · Rain from 3 PM"), r.map { it.text })
        assertEquals("H 54° L 39°", r.last().meta)
        assertTrue(WidgetContent.weatherRows(w, ms + 7 * 3_600_000L).isEmpty())
    }

    @Test fun chronosShowsThreeDaysWithTheirKindAndBirthdaysFirst() {
        val v = WidgetContent.chronos(now, events, F.availability)
        assertEquals(listOf("Today · Office day", "Tomorrow · Office day", "Wed, Oct 21 · Out of office"), v.groups.map { it.label })
        assertEquals("Alex's birthday", v.groups[0].rows.first().text)
        assertEquals("Birthday", v.groups[0].rows.first().meta)
        assertFalse(v.groups[0].rows.any { it.text == "Old meeting" })
        assertEquals("Team standup at 9:00 AM", v.title)
        assertEquals(WTone.ACTIVE, v.groups[2].rows.single().tone)
    }

    @Test fun agoraTicksOnlyHisOwnTasksAndAgentsOpenPantheon() {
        val agent = WidgetContent.agentTasks(F.irisTasks, now)
        val v = WidgetContent.agora(now, F.tasks, agent)
        val own = rows(v, "Yours")
        assertEquals(listOf("Renew car registration", "Return the Amazon package", "Reply to Jordan about the lease", "Book the foliage drive"), own.map { it.text })
        assertTrue(own.all { it.mark == Mark.CHECK && it.taskId != null })
        assertEquals("Overdue", own[0].meta)
        // Iris's sensitive task never shows; the agents' rows have no box and open Pantheon.
        assertEquals(listOf("Confirm the WWE tickets", "Patch the media server"), agent.map { it.text })
        assertTrue(agent.all { it.mark == Mark.DOT && it.taskId == null && it.dest == "AGENTS" })
        assertEquals("Iris · waiting", agent[0].meta)
        assertEquals("6 open", v.title)
        assertEquals("1 overdue", v.sub)
    }

    @Test fun snoozedTasksStayOffUntilTheirTime() {
        val v = WidgetContent.ownTasks(F.tasks, now, mapOf("4" to F.ms + 60_000L))
        assertFalse(v.any { it.text == "Renew car registration" })
    }

    @Test fun delphiShowsNoData() {
        val v = WidgetContent.delphi()
        assertEquals("Ask Athena", v.title)
        assertTrue(v.groups.isEmpty() && v.sub.isEmpty() && v.foot.isEmpty())
    }

    @Test fun venusShowsTodaysOutfitWithItsPictures() {
        val c = CardContent("Navy polo, grey chinos", body = "Office day, so smart casual.", facts = listOf(Fact("Top", "Navy polo"), Fact("Bottom", "Grey chinos")),
            photos = listOf(CardPhoto("/p/top.jpg", -1, true), CardPhoto("/p/bottom.jpg", null, false)))
        val v = WidgetContent.venus(c, true)
        assertEquals("Navy polo, grey chinos", v.title)
        assertEquals(listOf("Navy polo", "Grey chinos"), v.pics.map { it.label })
        assertEquals("Closet not imported yet", WidgetContent.venus(null, false).title)
        assertEquals("Wearing today", WidgetContent.venus(c.copy(status = "Wearing"), true).sub)
    }

    @Test fun musicShowsTheTrackAndWhetherItPlays() {
        val v = WidgetContent.music(NowPlaying("Clocks", "Coldplay", true, source = Player.SPOTIFY))
        assertEquals("Clocks", v.title)
        assertEquals("Coldplay · Spotify", v.sub)
        assertTrue(v.playing)
        assertEquals("Nothing playing", WidgetContent.music(null).title)
    }

    @Test fun lemnosShowsUpOrDownAndUsage() {
        val v = WidgetContent.lemnos(F.lemnos, now)
        assertEquals("Up 12 days", v.title)
        assertEquals(listOf("CPU" to "10%", "Memory" to "37%", "Disk" to "61%"), v.groups.single().rows.map { it.text to it.meta })
        assertEquals("Checked 8:26 AM", v.foot)
        val down = WidgetContent.lemnos(F.lemnos.copy(online = false), now)
        assertEquals("Not answering", down.title)
        assertEquals(WTone.ALERT, down.tone)
        assertEquals("Not paired", WidgetContent.lemnos(LemnosState(), now).title)
    }

    @Test fun cerberusCountsUnseenBySeverityAndMasksTheLatestHigh() {
        val v = WidgetContent.cerberus(F.lemnos, now)
        assertEquals("1 high, 2 medium, 1 low", v.title)
        assertEquals("SSH login for user someone from an address, not a trusted address", rows(v, "Latest high").single().text)
        assertEquals(listOf("1", "2", "1"), rows(v, "Unseen").map { it.meta })
        val cred = F.lemnos.copy(alerts = listOf(Alert(9, F.ms / 1000.0, "scan", "high", "Credential harvest: token found in env", "", false)))
        assertEquals("New high alert from Scan", rows(WidgetContent.cerberus(cred, now), "Latest high").single().text)
        assertEquals("All clear", WidgetContent.cerberus(F.lemnos.copy(alerts = emptyList()), now).title)
    }

    @Test fun pantheonShowsEachWorkerAndTheirLastActivity() {
        val v = WidgetContent.pantheon(F.lemnos, now)
        val r = v.groups.single().rows
        assertEquals(listOf("Iris", "Atlas", "Echo"), r.map { it.text })
        assertEquals("Thinking · 8:10 AM", r[1].meta)
        assertEquals(listOf(WTone.OK, WTone.ACTIVE, WTone.ALERT), r.map { it.tone })
        assertEquals("Echo down", v.title)
    }

    @Test fun plainNamesSwapTheKicker() {
        assertEquals("Praxis", WKind.AGORA.label(false))
        assertEquals("Tasks", WKind.AGORA.label(true))
        assertEquals("Ask Athena", WKind.DELPHI.label(true))
        assertEquals("Olympus", WKind.TODAY.label(false))
    }

    @Test fun localViewsComeFromTheSameBoardAsHome() {
        val inputs = BoardInputs(now, CalendarSnapshot(true, events, true), F.availability, false, HomePrefs(), Learned(), F.tasks, ClosetSnapshot(loaded = true), emptyList(), false)
        val views = WidgetBus.localViews(WidgetBus.LocalSnapshot(inputs, CardEngine.build(inputs), false), emptyList())
        assertEquals(listOf(WKind.TODAY, WKind.CHRONOS, WKind.AGORA, WKind.DELPHI, WKind.HERMES, WKind.VENUS), views.map { it.kind })
        assertTrue(views.all { WidgetSafety.isSafe(WidgetSafety.seal(it)) })
    }

    // ---- never sensitive ----

    private val poison = listOf(
        "Pay rent $1,450 by Friday", "Card ending 4417 charged", "Calypso: passport scan", "Aegis unlock", "Doctor at 3 pm",
        "Visa interview at the consulate", "Call 716-555-0142", "Email taylor@example.com", "ssh key rotated on vps.example.com",
        "Root password is hunter2", "Login from 198.51.100.4 port 22", "Weight 72 kg today", "Tinder first date",
        "VIN 1HGCM82633A004352", "Transfer money to savings", "Token sk-ant-abcdefghijklmnopqrstuvwxyz0123",
    )
    private val forbidden = Regex("(?i)(\\$|\\d{3}-\\d{3}-\\d{4}|@|calypso|aegis|passport|doctor|visa|consulate|password|hunter2|198\\.51|vps\\.example|\\bkg\\b|tinder|1HGCM|savings|sk-ant|4417|\\brent\\b)")

    @Test fun nothingOffLimitsReachesAnyWidget() {
        val evs = poison.mapIndexed { i, t -> F.event(100L + i, t, F.ms + (i + 1) * 60_000L, cat = EventCategory.PERSONAL) }
        val tasks = TaskSnapshot(poison.mapIndexed { i, t -> F.task(100L + i, t) }, emptyList(), true)
        val iris = poison.mapIndexed { i, t -> IrisTask(100L + i, t, "pending", null, null, "iris", false, null, "", false) }
        val cards = poison.map { card(CardKind.BRIEF, it) }
        val s = F.lemnos.copy(alerts = poison.mapIndexed { i, t -> Alert(100L + i, F.ms / 1000.0, "ssh", "high", t, t, false) })
        val views = listOf(
            WidgetContent.today(now, cards, evs, F.availability, tasks),
            WidgetContent.chronos(now, evs, F.availability),
            WidgetContent.agora(now, tasks, WidgetContent.agentTasks(iris, now)),
            WidgetContent.cerberus(s, now),
            WidgetContent.pantheon(s, now),
            WidgetContent.music(NowPlaying("Pay rent $1,450", "Doctor visit", true)),
        ).map { WidgetSafety.seal(it) }
        for (v in views) {
            assertTrue("${v.kind} passes", WidgetSafety.isSafe(v))
            for (t in WidgetSafety.texts(v)) assertFalse("${v.kind}: \"$t\"", forbidden.containsMatchIn(t))
        }
        // Calendar rows keep their time as "Busy" rather than vanishing.
        assertTrue(views[1].groups[0].rows.count { it.text == "Busy" } >= 10)
    }

    @Test fun safetyMasksAndDrops() {
        assertNull(WidgetSafety.clean("Rent due, $1,450"))
        assertNull(WidgetSafety.clean("Open Calypso"))
        assertNull(WidgetSafety.clean("Dentist cleaning"))
        assertEquals("Sudo by someone on a host", WidgetSafety.clean("Sudo by owner on box.example.com"))
        assertEquals("Login from an address", WidgetSafety.clean("Login from 10.0.0.12"))
        assertEquals("Team standup", WidgetSafety.clean("Team standup\nnotes: secret plan"))
    }

    @Test fun todayNeverShowsBankOrPlaceholderCards() {
        val cards = listOf(card(CardKind.GENERATED, "Payment due Friday", learnKey = "GENERATED:BANK", neverHide = "Bank alert"), card(CardKind.VPS, "Server card", placeholder = true))
        val v = WidgetContent.today(now, cards, emptyList(), F.availability, TaskSnapshot(loaded = true))
        assertTrue(v.groups.none { it.label == "Olympus" })
    }

    // ---- sizes ----

    @Test fun sizesTakeWhatFitsAtTheirSmallestHeight() {
        val groups = listOf(WGroup("A", List(5) { WRow("a$it") }), WGroup("B", List(4) { WRow("b$it") }), WGroup("C", emptyList(), "none"))
        fun rows(b: Bucket, reserved: Float = 0f) = WidgetPlan.fit(groups, b, reserved).map { it.rows.size }
        assertTrue(WidgetPlan.fit(groups, Bucket.SMALL).isEmpty())
        assertEquals(listOf(2), rows(Bucket.MEDIUM))
        assertEquals(listOf(5, 1), rows(Bucket.LARGE))
        assertEquals(listOf(5, 4, 0), rows(Bucket.FULL))
        assertEquals(listOf(5), rows(Bucket.LARGE, Bucket.SUB_DP + 20f))
        // Whatever fits must really fit: labels and rows never add up past the body.
        for (b in Bucket.entries) {
            val used = WidgetPlan.fit(groups, b).sumOf { g -> (if (g.label.isEmpty()) 0.0 else Bucket.LABEL_DP.toDouble()) + maxOf(g.rows.size, 1) * Bucket.ROW_DP.toDouble() }
            assertTrue("$b", used <= maxOf(b.bodyDp, 0f))
        }
        val (l, r) = WidgetPlan.columns(groups, Bucket.WIDE_LARGE)
        assertEquals(listOf("A"), l.map { it.label })
        assertEquals(listOf("B", "C"), r.map { it.label })
    }

    // ---- the update throttle ----

    @Test fun throttleRunsOnceThenHoldsOneThenDrops() {
        val t = Throttle(10_000)
        assertEquals(0L, t.request(1_000))
        assertEquals(6_000L, t.request(5_000))
        assertNull(t.request(6_000))
        assertNull(t.request(9_000))
        t.fired(11_000)
        assertEquals(8_000L, t.request(13_000))
        t.fired(21_000)
        assertEquals(0L, t.request(31_500))
    }
}
