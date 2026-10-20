package dev.phonerecall.hestia

import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.EngineTest
import dev.phonerecall.brain.ExecResult
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.confirmNow
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.Step
import dev.phonerecall.brain.StepExecutor
import dev.phonerecall.brain.ToolBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HestiaTest {
    private var now = ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val fake = FakeHomeBackend()
    private val core = HestiaCore(fake)
    private val state = FakeState(home = fake.state.value)
    private val store = MemoryStore()

    /** The real path: Fates runs home steps through HestiaCore, the same as AndroidExecutor does. */
    private val exec = object : StepExecutor {
        override fun run(step: Step): ExecResult = core.control(step.payload)
        override fun undo(step: Step): Boolean = core.restore(step.undo!!)
    }
    private val fates = Fates(store, state, exec, { _, _, _ -> }, { now })
    private val tools = ToolBox(EngineTest.EmptyReads, fates, home = core)

    private fun sync() { state.home = fake.state.value }

    // ---- the model only proposes ----

    @Test fun homeControlOnlyProposes() {
        val out = tools.run("home_control", mapOf("device_id" to "d1", "action" to "off"), 1L)
        assertFalse(out.text, out.isError)
        val p = out.proposal!!
        assertEquals(ProposalStatus.PENDING, p.status)
        assertEquals(ProposalKind.LIGHTS, p.steps.single().kind)
        assertEquals(2, p.tier)
        assertTrue("nothing reached the home", fake.sent.isEmpty())
        assertTrue(out.text.contains("has not happened yet"))
        // Only his confirm acts.
        assertTrue(fates.confirmNow(p.id, store) is Decision.Done)
        assertEquals(listOf("d1" to HomeCommand.Power(false)), fake.sent)
    }

    @Test fun plugsAreHomeControlNotLights() {
        val p = tools.run("home_control", mapOf("device_id" to "Air purifier plug", "action" to "on"), 1L).proposal!!
        assertEquals(ProposalKind.HOME_CONTROL, p.steps.single().kind)
    }

    @Test fun lightsIsNotAModelTool() {
        assertNull(ProposalKind.tool("lights"))
        assertTrue(tools.run("lights", mapOf("device_id" to "d1", "action" to "off"), 1L).isError)
        val plan = tools.run("propose_plan", mapOf("title" to "x", "steps" to listOf(mapOf("tool" to "lights", "input" to mapOf("device_id" to "d1", "action" to "off")))), 1L)
        assertTrue(plan.isError)
        assertTrue(tools.definitions.none { it.name() == "lights" })
        assertTrue(tools.definitions.any { it.name() == "home_control" } && tools.definitions.any { it.name() == "home_status" })
    }

    @Test fun homeStatusIsReadOnly() {
        val out = tools.run("home_status", emptyMap(), 1L)
        assertFalse(out.isError)
        assertNull(out.proposal)
        assertTrue(fake.sent.isEmpty())
        assertTrue(out.text.contains("\"device_id\":\"d1\""))
        assertTrue(out.text.contains("Good morning"))
        // The lock is listed, read only, with nothing it "can" do.
        val lock = Regex("\\{[^{}]*\"name\":\"Front door\"[^{}]*\\}").find(out.text)!!.value
        assertTrue(lock, lock.contains("\"read_only\":true") && !lock.contains("\"can\""))
    }

    // ---- forbidden kinds ----

    @Test fun locksGaragesAndSecurityAreRefusedEverywhere() {
        val forbidden = listOf(DeviceKind.LOCK, DeviceKind.GARAGE, DeviceKind.SECURITY, DeviceKind.CAMERA, DeviceKind.DOORBELL)
        assertTrue(forbidden.all { it.forbidden })
        assertEquals(forbidden.toSet(), DeviceKind.entries.filter { it.forbidden }.toSet())
        val extra = forbidden.mapIndexed { i, k -> HomeDevice("f$i", "Thing $i", k, "r4", "s1", traits = setOf(Trait.ON_OFF), on = false) }
        fake.set(fake.state.value.let { it.copy(devices = it.devices + extra) }); sync()
        for (d in extra + fake.state.value.device("d8")!!) {
            for (action in listOf("on", "off")) {
                val out = tools.run("home_control", mapOf("device_id" to d.id, "action" to action), 1L)
                assertTrue(out.text, out.isError && out.text.contains("never controls"))
            }
            val plan = tools.run("propose_plan", mapOf("title" to "x", "steps" to listOf(
                mapOf("tool" to "home_control", "input" to mapOf("device_id" to "d1", "action" to "off")),
                mapOf("tool" to "home_control", "input" to mapOf("device_id" to d.id, "action" to "on")),
            )), 1L)
            assertTrue(plan.isError)
            // His own taps and the hands refuse them too.
            assertEquals(HomeRules.FORBIDDEN, runBlocking { core.tap(d.id, HomeCommand.Power(true)) })
            assertTrue(runCatching { core.control(mapOf("device_id" to d.id, "device" to d.name, "action" to "on")) }.isFailure)
        }
        assertTrue(store.rows.isEmpty())
        assertTrue(fake.sent.isEmpty())
        assertFalse(HomeDevice("x", "x", DeviceKind.LOCK, null, "s1", traits = setOf(Trait.ON_OFF)).controllable)
    }

    @Test fun aLockSwappedInBeforeConfirmFailsRevalidation() {
        val p = tools.run("home_control", mapOf("device_id" to "d9", "action" to "on"), 1L).proposal!!
        // The device under that id now reports a lock (re-linked in Google Home): confirm must not act.
        fake.set(fake.state.value.let { s -> s.copy(devices = s.devices.map { if (it.id == "d9") it.copy(kind = DeviceKind.LOCK) else it }) }); sync()
        assertTrue(fates.confirmNow(p.id, store) is Decision.Failed)
        assertTrue(fake.sent.isEmpty())
    }

    // ---- auto-approve ----

    @Test fun lightsIsAnAutoCandidateButOffByDefault() {
        assertTrue(ProposalKind.LIGHTS in ProposalKind.autoCandidates)
        assertFalse(ProposalKind.HOME_CONTROL in ProposalKind.autoCandidates)
        assertTrue(fates.autoApprove.isEmpty())
        val p = tools.run("home_control", mapOf("device_id" to "d1", "action" to "off"), 1L).proposal!!
        assertEquals(ProposalStatus.PENDING, p.status)
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun lightsOnTheListRunWithUndoPlugsStillAsk() {
        fates.autoApprove = setOf(ProposalKind.LIGHTS)
        val out = tools.run("home_control", mapOf("device_id" to "d4", "action" to "brightness", "value" to "60"), 1L)
        assertTrue(out.decision is Decision.Done)
        assertEquals(listOf("d4" to HomeCommand.Brightness(60)), fake.sent)
        // Undo puts the old brightness back.
        sync()
        assertTrue(fates.undo(out.proposal!!.id) is Decision.Undone)
        assertEquals("d4" to HomeCommand.Brightness(20), fake.sent.last())

        val plug = tools.run("home_control", mapOf("device_id" to "d3", "action" to "on"), 1L)
        assertNull(plug.decision)
        assertEquals(ProposalStatus.PENDING, plug.proposal!!.status)
        // Even forced onto the list, HOME_CONTROL is never eligible.
        fates.autoApprove = setOf(ProposalKind.LIGHTS, ProposalKind.HOME_CONTROL)
        val plug2 = tools.run("home_control", mapOf("device_id" to "d3", "action" to "on"), 1L)
        assertEquals(ProposalStatus.PENDING, plug2.proposal!!.status)
        // A plan mixing a light and a plug asks, as a whole.
        val plan = tools.run("propose_plan", mapOf("title" to "Movie", "steps" to listOf(
            mapOf("tool" to "home_control", "input" to mapOf("device_id" to "d1", "action" to "off")),
            mapOf("tool" to "home_control", "input" to mapOf("device_id" to "d3", "action" to "on")),
        )), 1L)
        assertEquals(ProposalStatus.PENDING, plan.proposal!!.status)
    }

    @Test fun homeProposalsExpireInTenMinutes() {
        val p = tools.run("home_control", mapOf("device_id" to "d1", "action" to "off"), 1L).proposal!!
        now += 11 * 60_000L
        assertTrue(fates.confirmNow(p.id, store) is Decision.Expired)
        assertTrue(fake.sent.isEmpty())
    }

    // ---- trait mapping on the fake home ----

    private fun check(vararg a: Pair<String, String>) = HomeRules.check(mapOf(*a), fake.state.value)
    private fun bad(vararg a: Pair<String, String>) = (check(*a) as ControlCheck.Bad).reason

    @Test fun traitsDecideWhatIsAllowed() {
        assertTrue(bad("device_id" to "d9", "action" to "brightness", "value" to "50").contains("can't dim"))
        assertTrue(bad("device_id" to "d4", "action" to "color", "value" to "blue").contains("isn't a color light"))
        assertTrue(bad("device_id" to "d5", "action" to "color_temp", "value" to "warm").contains("no white"))
        assertTrue(bad("device_id" to "d4", "action" to "color_temp", "value" to "2000").contains("2200 K to 6500 K"))
        assertTrue(bad("device_id" to "d7", "action" to "on").contains("offline"))
        assertTrue(bad("device_id" to "d1", "action" to "brightness", "value" to "0").contains("1 to 100"))
        assertTrue(bad("device_id" to "d1", "action" to "color", "value" to "lilac").contains("must be one of"))
        assertTrue(bad("device_id" to "d1", "action" to "dance").contains("action must be"))
        assertTrue(bad("device" to "Nope", "action" to "on").contains("No device"))

        val warm = check("device_id" to "d1", "action" to "color_temp", "value" to "warm") as ControlCheck.Ok
        assertEquals("2700", warm.payload["value"])
        assertEquals("Set Floor lamp to 2700 K (warm)", warm.summary)
        assertEquals(HomeCommand.ColorTemp(2700), HomeRules.command(warm.payload))
        val blue = check("device" to "tv backlight", "action" to "color", "value" to "blue") as ControlCheck.Ok
        assertEquals(HomeCommand.Color(225, 100), HomeRules.command(blue.payload))
        assertEquals("Living room", blue.payload["room"])
        // "Room name" plus device name finds it too.
        assertTrue(check("device" to "Bedroom Ceiling light", "action" to "on") is ControlCheck.Ok)
    }

    @Test fun duplicateNamesNeedTheId() {
        fake.set(fake.state.value.let { s -> s.copy(devices = s.devices + s.device("d1")!!.copy(id = "d1b", roomId = "r2")) })
        assertTrue(bad("device" to "Floor lamp", "action" to "on").contains("matches 2 devices"))
        assertTrue(check("device_id" to "d1b", "action" to "on") is ControlCheck.Ok)
    }

    @Test fun notConnectedRefusesAndSaysWhy() {
        fake.set(FakeHomeBackend.sample(Link.NotConnected("Google Play services is missing"))); sync()
        assertTrue(bad("device_id" to "d1", "action" to "on").contains("isn't connected"))
        assertTrue(tools.run("home_status", emptyMap(), 1L).text.contains("Google Play services is missing"))
    }

    @Test fun matterUnits() {
        assertEquals(254, HomeRules.toLevel(100))
        assertEquals(3, HomeRules.toLevel(1))
        assertEquals(127, HomeRules.toLevel(50))
        assertEquals(100, HomeRules.toPercent(254))
        assertEquals(50, HomeRules.toPercent(127))
        assertEquals(1, HomeRules.toPercent(1))
        assertEquals(370, HomeRules.toMireds(2700))
        assertEquals(2700, HomeRules.toKelvin(370))
        assertEquals(6490, HomeRules.toKelvin(154))
        assertEquals(254, HomeRules.toMatterSat(100))
        assertEquals(225, HomeRules.toDegrees(HomeRules.toMatterHue(225)).let { if (it in 224..226) 225 else it })
    }

    @Test fun undoRestoresWhatWasThere() {
        val d = fake.state.value.device("d1")!!
        assertEquals(mapOf("device_id" to "d1", "device" to "Floor lamp", "action" to "on"), HomeRules.undo(d, mapOf("action" to "off")))
        assertEquals("70", HomeRules.undo(d, mapOf("action" to "brightness", "value" to "20"))!!["value"])
        assertNull(HomeRules.undo(d, mapOf("action" to "color", "value" to "red")))
    }

    @Test fun debugStatusHasNoIds() {
        val m = HomeRules.debugStatus(fake.state.value)
        assertEquals(true, m["connected"])
        assertEquals(listOf("Maple Street apartment"), m["structure_names"])
        assertEquals(4, m["rooms"])
        val text = m.toString()
        assertFalse(text, Regex("\\b(d[0-9]|r[0-9]|s1)\\b").containsMatchIn(text))
        assertTrue(text.contains("Floor lamp") && text.contains("Living room") && text.contains("level=70"))
    }

    // ---- Olympus ----

    @Test fun olympusCardOnlyWhenAwayOrLate() {
        val s = fake.state.value
        val noon = ZonedDateTime.of(2026, 10, 19, 12, 0, 0, 0, ZoneId.of("America/New_York"))
        val away = HestiaCards.card(s, away = true, noon)
        assertNotNull(away)
        assertEquals("3 lights on, you're away", away!!.content.title)
        assertEquals("Away", away.content.status)
        assertNull(HestiaCards.card(s, away = false, noon))
        assertNull(HestiaCards.card(s, away = null, noon))
        assertEquals("3 lights still on", HestiaCards.card(s, away = null, noon.withHour(23).withMinute(30))!!.content.title)
        val dark = s.copy(devices = s.devices.map { it.copy(on = false) })
        assertNull(HestiaCards.card(dark, away = true, noon))
        assertNull(HestiaCards.card(FakeHomeBackend.sample(Link.NeedsPermission), away = true, noon))
    }
}
