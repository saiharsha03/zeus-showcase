package dev.phonerecall.horologion

import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.ScheduleBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

object HorologionFixtures {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val now: Instant = Instant.parse("2026-10-20T14:00:00Z")
    val iris = IrisSetup(
        skills = listOf(Skill("morning-brief", "Builds the morning briefing for Telegram", "own"), Skill("agora-etiquette", "How to talk in Agora", "plugin")),
        hooks = listOf(Hook("Stop", "*", "iris_stop.py", "settings.json"), Hook("PreToolUse", "Bash", "guard.sh", "settings.json")),
        plugins = listOf(Plugin("zeus@zeus-local", true), Plugin("telegram@claude-plugins", true)),
        mcp = listOf("calendar", "gmail"), memory = listOf("MEMORY.md" to 212), briefs = listOf("SOUL.md", "IRIS-BRIEF.md"), channels = listOf("telegram", "zeus"), model = "claude-opus-5-5",
    )
    val atlas = AtlasSetup("codex-cli 0.98.0", "gpt-6.1-sol", "atlas_notify.py", "on-request", "workspace-write", listOf("github"), listOf("review.md"), emptyList())
    val server = ServerSetup(listOf("zeus.example.com", "media.example.org"), 1_792_000_000L, "success", "Tue 2026-10-20 04:12:09 UTC", listOf("jellyfin.service", "iris-gateway.service"))
    val facts = AthenaFacts(
        tools = listOf("read_calendar" to "His calendar, next days. Summaries only.", "post_agora" to "Post in Agora, the agents' room."),
        circle = listOf(Triple("Perseus", "claude-sonnet-5-5", "Research and finding answers")),
        models = listOf("Food photos (Iaso)" to "Sonnet 5.5"), caps = listOf("Monthly cap" to "$100"),
        auto = listOf(Switch("auto.add_reminder", "Reminder", "Runs without asking, with Undo", true)),
        hosts = listOf("api.anthropic.com"), permissions = listOf("POST_NOTIFICATIONS" to true, "READ_CALENDAR" to false),
        switches = listOf(Switch("brief", "Eos", "The morning brief", true), Switch("heartbeat", "Heartbeat", "Her hourly look", false), Switch("info.rhythms", "Horae", "Learns habits", true)),
        widgets = listOf("Praxis" to 1), store = "zeus-athena-memory", oneiroi = listOf("Dream" to "3 AM on the charger"),
    )
    val irisJobs = listOf(IrisJob("morning-brief", "30 6 * * *", "active", "Tue Oct 20, 6:30 AM"), IrisJob("weekly-bills", "0 9 * * 0", "paused", null))
    val phone = listOf(PhoneJob("zeus-mind-sync", "Mind sync", "Sends Athena's batch", 30, "network", null, "ENQUEUED", PhoneJobs.SPECS[0].bounds), PhoneJob("zeus-hygiene", "Hygiene", "Clears expired cards", 60, "battery not low"))

    fun items(): List<Item> {
        val book = ScheduleBook(MapKv())
        return Horologion.athenaJobs(book.jobs(), { n -> if (n == "daily-plan") 1_792_051_200_000L to "ok" else null }, zone, now) +
            Horologion.phoneJobs(phone, zone) + Horologion.athena(facts) + Horologion.irisJobs(irisJobs) + Horologion.iris(iris) + Horologion.atlas(atlas) + Horologion.server(server, zone)
    }
}

class HorologionTest {
    private val items = HorologionFixtures.items()

    @Test fun athenaJobsSayWhenInWordsAndHisZone() {
        val plan = items.single { it.id == "athena-job-daily-plan" }
        assertEquals("Zeus · daily-plan", plan.name)
        assertTrue(plan.value!!, plan.value!!.startsWith("Daily at 6:00 AM, America/New_York · next Wed Oct 21, 6:00 AM · last "))
        assertTrue(plan.action is Action.AthenaSchedule)
        assertEquals(Tab.JOBS, plan.tab)
    }

    @Test fun everyOwnerIsGroupedAndSearchFindsAcrossTabs() {
        assertEquals(listOf(Owner.ATHENA, Owner.IRIS, Owner.ATLAS, Owner.SERVER), Horologion.grouped(items).map { it.first })
        assertEquals(listOf("iris-hook-0"), Horologion.search(items, "stop hook").map { it.id })
        assertEquals(setOf("iris-mcp-gmail"), Horologion.search(items, "GMAIL").map { it.id }.toSet())
        assertTrue(Horologion.search(items, "media.example").single().owner == Owner.SERVER)
        assertEquals(items, Horologion.search(items, "  "))
        assertTrue(Horologion.search(items, "nothing like this").isEmpty())
    }

    @Test fun onlySafeThingsHaveAnEditAction() {
        assertNull(items.single { it.id == "athena-host-api.anthropic.com" }.action)
        assertNull(items.single { it.id == "athena-switch-info.rhythms" }.action)          // changed in its own screen
        assertTrue(items.single { it.id == "athena-switch-brief" }.action is Action.Toggle)
        assertEquals(Action.Toggle("heartbeat", false), items.single { it.id == "athena-switch-heartbeat" }.action)
        assertNull(items.single { it.id == "phone-job-zeus-hygiene" }.action)               // no safe interval change
        assertTrue(items.single { it.id == "phone-job-zeus-mind-sync" }.action is Action.PhoneInterval)
        assertTrue(items.single { it.id == "iris-hook-0" }.action is Action.Ask)
        assertNull(items.single { it.id == "iris-memory-MEMORY.md" }.action)
        assertEquals("212 lines", items.single { it.id == "iris-memory-MEMORY.md" }.value)
    }

    @Test fun intervalsStayInTheirBounds() {
        val sync = PhoneJobs.SPECS.single { it.id == "zeus-mind-sync" }.bounds!!
        assertTrue(sync.ok(15) && sync.ok(120)); assertFalse(sync.ok(14)); assertFalse(sync.ok(121))
        val sub = PhoneJobs.SPECS.single { it.id == "zeus-oneiroi-subconscious" }.bounds!!
        assertTrue(sub.ok(1440) && sub.ok(7 * 1440)); assertFalse(sub.ok(720)); assertFalse(sub.ok(8 * 1440))
        assertEquals(listOf(1440L, 10_080L), PhoneJobs.SPECS.single { it.id == "mnemosyne.nightly" }.bounds!!.choices)
        assertTrue(PhoneJobs.SPECS.filter { it.bounds != null }.all { s -> s.bounds!!.ok(s.defaultMinutes) && s.bounds!!.choices.all { s.bounds!!.ok(it) } })
        assertEquals(listOf("Every 15 min", "Every 30 min", "Hourly", "Every 2 h", "Daily", "Weekly", "Every 3 days"), listOf(15L, 30, 60, 120, 1440, 10_080, 4320).map { Horologion.every(it) })
    }

    @Test fun theEditorsPresetsRoundTripTheirCrons() {
        // What the picker opens on is what it saves back: every Iris and Athena cron, as a preset or Advanced.
        val crons = HorologionFixtures.irisJobs.mapNotNull { it.cron } + ScheduleBook(MapKv()).jobs().map { it.cron } + "0 9 1 * *"
        for (c in crons) assertEquals(c, dev.phonerecall.mind.Cron.of(c).cron())
    }

    @Test fun aChangeForIrisIsHerOwnRequestCarryingHisToken() {
        val job = HorologionFixtures.irisJobs[0]
        val (req, title) = OwnerRequests.cron(job, "change", "0 7 * * 1-5", "abc12345")!!
        assertEquals("Please change your cron morning-brief to 0 7 * * 1-5 (ET), Weekdays at 7:00 AM", title)
        assertEquals("iris.cron_change", req.kind); assertEquals("iris", req.executor)
        assertEquals(mapOf("id" to "morning-brief", "action" to "change", "zone" to "America/New_York", "cron" to "0 7 * * 1-5", "text" to title), req.payload)
        val out = OwnerRequests.outgoing(req, title, "signed-token")
        assertEquals("signed-token", out.out.approval); assertEquals("owner", out.by); assertFalse(out.out.suggestion)
        assertNull(OwnerRequests.cron(job, "change", "0 25 * * *", "x"))
        assertNull(OwnerRequests.cron(job, "delete", null, "x"))
        assertEquals("Please pause your cron morning-brief", OwnerRequests.cron(job, "pause", null, "x")!!.second)

        val (s, t) = OwnerRequests.setup(Owner.IRIS, "hook", "Stop: iris_stop.py", "disable", " only at night ", "n1")!!
        assertEquals("Please turn off your hook Stop: iris_stop.py: only at night", t)
        assertEquals("iris.setup_change", s.kind)
        assertEquals("atlas.setup_change", OwnerRequests.setup(Owner.ATLAS, "setting", "Sandbox", "change", "read-only", "n2")!!.first.kind)
        assertNull(OwnerRequests.setup(Owner.SERVER, "x", "y", "enable", null, "n3"))
    }
}
