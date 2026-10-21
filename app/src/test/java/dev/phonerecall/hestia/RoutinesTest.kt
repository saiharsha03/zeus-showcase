package dev.phonerecall.hestia

import dev.phonerecall.brain.ExecResult
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.Step
import dev.phonerecall.brain.StepExecutor
import dev.phonerecall.mind.MapKv
import dev.phonerecall.shortcuts.Learn
import dev.phonerecall.shortcuts.Seen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Home routines through the real Fates and HestiaCore path, on the fake home with his computer plugged into the bedroom. */
class RoutinesTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val fake = FakeHomeBackend(FakeHomeBackend.sample().let { s ->
        s.copy(devices = s.devices.map { if (it.id == "d3") it.copy(on = true) else it } +
            HomeDevice("pc", "Computer", DeviceKind.PLUG, "r2", "s1", traits = setOf(Trait.ON_OFF), on = true) +
            HomeDevice("sw", "Hall switch", DeviceKind.SWITCH, "r4", "s1", traits = setOf(Trait.ON_OFF), on = true))
    })
    private val core = HestiaCore(fake)
    private val state = FakeState(home = fake.state.value)
    private val store = MemoryStore()
    private var clock = at(20, 18)
    private val exec = object : StepExecutor {
        override fun run(step: Step): ExecResult = core.control(step.payload)
        override fun undo(step: Step): Boolean = core.restore(step.undo!!)
    }
    private val fates = Fates(store, state, exec, { _, _, _ -> }, { clock })
    private val book = RoutineBook(MapKv())
    private var evidence = emptyList<dev.phonerecall.shortcuts.Evidence>()
    private val engine = RoutineEngine(book, { fake.state.value }, { steps, title ->
        state.home = fake.state.value
        when (val r = fates.own(steps, title, "Home routines")) { is ProposeResult.Created -> listOf(r.proposal.id); is ProposeResult.Invalid -> error(r.reason) }
    }, { evidence })

    /** Buffalo. */
    private val lat = 42.89; private val lng = -78.88
    private fun now(t: Long, home: Boolean? = null, offSince: Long? = null, media: Boolean = false, bedtime: Long? = null) =
        RoutineEngine.now(t, zone, home, lat, lng, offSince, media, bedtime)

    private fun on(id: String) = fake.state.value.device(id)!!.on
    private fun sentTo() = fake.sent.map { it.first }.toSet()

    @Test fun theComputerIsPreTaggedAndNeverTouched() {
        assertEquals(listOf("pc"), RoutineRules.computers(fake.state.value).map { it.id })
        clock = at(20, 8)
        val out = engine.event(Moment.LEAVE, now(clock))
        assertTrue("pc" in book.load().never)
        // Bedroom lights that were on, and every plug and switch that was on, except the computer.
        assertEquals(setOf("d4", "d3", "sw"), sentTo())
        assertEquals(true, on("pc"))
        assertEquals(true, on("d1"))       // living-room lights stay
        assertEquals(1, out.single().ids.size)
    }

    @Test fun noRoutineEverTouchesALockOrAForbiddenKind() {
        book.update { it.copy(routines = it.routines.map { r -> r.copy(devices = listOf("d8"), rooms = listOf("*"), plugs = true) }) }
        val s = fake.state.value
        for (r in book.load().routines) assertTrue(RoutineRules.targets(r, s, emptySet(), listOf("d8")).none { it.kind.forbidden })
        assertNull(RoutineRules.adopt("arrive:home", "home:d8:on", "unlock the door", s, emptySet(), "hr-1"))
        assertNull(RoutineRules.adopt("arrive:home", "home:pc:on", "the computer", s, setOf("pc"), "hr-1"))
        // And the guard below the routines refuses it anyway.
        assertEquals(HomeRules.FORBIDDEN, runBlocking { core.tap("d8", HomeCommand.Power(true)) })
    }

    @Test fun undoPutsEveryDeviceBackAsItWas() {
        clock = at(20, 8)
        val id = engine.event(Moment.LEAVE, now(clock)).single().ids.single()
        assertEquals(false, on("d4")); assertEquals(false, on("d3"))
        state.home = fake.state.value
        fates.undo(id)
        assertEquals(ProposalStatus.UNDONE, store.get(id)!!.status)
        assertEquals(true, on("d4")); assertEquals(true, on("d3")); assertEquals(true, on("sw"))
    }

    @Test fun arrivalAfterDarkTurnsOnTheDefaultUntilItLearnsHisUsual() {
        fake.set(fake.state.value.copy(devices = fake.state.value.devices.map { if (it.kind == DeviceKind.LIGHT) it.copy(on = false) else it }))
        engine.event(Moment.LEAVE, now(at(20, 8)))
        fake.sent.clear()
        // 3 PM is daylight: nothing.
        assertEquals("not dark yet", engine.event(Moment.ARRIVE, now(at(20, 15))).single().skipped)
        engine.event(Moment.LEAVE, now(at(20, 16)))
        fake.sent.clear()
        // 8 PM: the living-room default.
        engine.event(Moment.ARRIVE, now(at(20, 20)))
        assertEquals(setOf("d1", "d2"), sentTo())

        // He turns the porch light on after getting home on two days: that becomes his usual set.
        val porch = RoutineRules.tapStep(fake.state.value.device("d9")!!, HomeCommand.Power(true), fake.state.value)!!
        val seen = Learn.seen(porch)!!
        evidence = Learn.evidence(listOf(Seen(at(18, 21), "arrive:home", "you get home"), Seen(at(19, 21), "arrive:home", "you get home")),
            listOf(seen.copy(at = at(18, 21, 2)), seen.copy(at = at(19, 21, 1))), zone)
        assertEquals(listOf("d9"), RoutineRules.learned(book.load().routine("arrive")!!, evidence))
        fake.set(fake.state.value.copy(devices = fake.state.value.devices.map { if (it.kind == DeviceKind.LIGHT) it.copy(on = false) else it }))
        engine.event(Moment.LEAVE, now(at(21, 8)))
        fake.sent.clear()
        engine.event(Moment.ARRIVE, now(at(21, 20)))
        assertEquals(setOf("d9"), sentTo())
    }

    @Test fun arrivalNeverRunsWhenAlreadyHomeOrAsleep() {
        fake.set(fake.state.value.copy(devices = fake.state.value.devices.map { if (it.kind == DeviceKind.LIGHT) it.copy(on = false) else it }))
        engine.event(Moment.LEAVE, now(at(20, 17)))
        engine.event(Moment.ARRIVE, now(at(20, 20)))
        assertEquals("already there", engine.event(Moment.ARRIVE, now(at(20, 21))).single().skipped)
        // Asleep at 1 AM: a geofence bounce in and out changes nothing and flickers nothing.
        val off = at(21, 0, 30)
        engine.tick(now(at(21, 1), home = true, offSince = off))
        assertTrue(book.load().asleepAt != null)
        fake.sent.clear()
        assertTrue(engine.event(Moment.LEAVE, now(at(21, 1, 10), offSince = off)).single().skipped!!.startsWith("you look asleep"))
        assertTrue(engine.event(Moment.ARRIVE, now(at(21, 1, 12), offSince = off)).single().skipped!!.startsWith("you look asleep"))
        assertTrue(fake.sent.isEmpty())
    }

    @Test fun duskRunsOnceAndOnlyAtHome() {
        fake.set(fake.state.value.copy(devices = fake.state.value.devices.map { if (it.kind == DeviceKind.LIGHT) it.copy(on = false) else it }))
        val sunset = Sun.times(LocalDate.of(2026, 10, 20), lat, lng)!!.second
        // Buffalo, Oct 20: sunset around 6:25 PM.
        val t = java.time.Instant.ofEpochMilli(sunset).atZone(zone)
        assertTrue("$t", t.hour == 18 && t.minute in 15..40)
        assertTrue(engine.tick(now(sunset + 60_000, home = false)).none { it.ids.isNotEmpty() })
        assertTrue(engine.tick(now(sunset - 60_000, home = true)).none { it.ids.isNotEmpty() })
        val ran = engine.tick(now(sunset + 120_000, home = true)).single { it.routine.id == "dusk" }
        assertEquals(1, ran.ids.size)
        assertEquals(setOf("d1", "d2"), sentTo())
        fake.set(fake.state.value.copy(devices = fake.state.value.devices.map { if (it.kind == DeviceKind.LIGHT) it.copy(on = false) else it }))
        assertEquals("already ran today", engine.tick(now(sunset + 600_000, home = true)).single { it.routine.id == "dusk" }.skipped)
    }

    @Test fun lightsGoOffWhenHeLooksAsleepButNotTheComputer() {
        val night = at(20, 23, 50)
        // Too early, the screen just went off, or music playing: nothing.
        assertEquals("not his bedtime yet", engine.tick(now(at(20, 21), home = true, offSince = at(20, 20))).single { it.routine.id == "asleep" }.skipped)
        assertTrue(engine.tick(now(night, home = true, offSince = night - 5 * 60_000)).single { it.routine.id == "asleep" }.skipped!!.startsWith("the screen was on"))
        assertEquals("something is playing", engine.tick(now(night, home = true, offSince = night - 40 * 60_000, media = true)).single { it.routine.id == "asleep" }.skipped)
        assertEquals("not home", engine.tick(now(night, home = false, offSince = night - 40 * 60_000)).single { it.routine.id == "asleep" }.skipped)
        assertTrue(fake.sent.isEmpty())
        // Past 11:30 PM, screen off 40 min, home, quiet: every light off; plugs and the computer stay.
        engine.tick(now(night, home = true, offSince = night - 40 * 60_000))
        assertEquals(setOf("d1", "d2", "d4"), sentTo())
        assertEquals(true, on("pc")); assertEquals(true, on("d3"))
        // Hypnos's bedtime wins over his setting when it is later than 10 PM.
        val r = book.load().routine("asleep")!!
        assertEquals(at(21, 0, 15), RoutineRules.nightStart(r, now(at(21, 1), bedtime = at(21, 0, 15))))
        assertEquals(at(20, 22), RoutineRules.nightStart(r, now(at(20, 23), bedtime = at(20, 21))))
    }

    @Test fun hisTapsFeedTheEvidenceAndAYesBecomesARoutine() {
        val s = fake.state.value
        var tapped: Step? = null
        HestiaCore.tapped = { d, cmd, snap -> tapped = RoutineRules.tapStep(d, cmd, snap) }
        try { runBlocking { core.tap("d1", HomeCommand.Power(false)) } } finally { HestiaCore.tapped = { _, _, _ -> } }
        val seen = Learn.seen(tapped!!)!!
        assertEquals("home:d1:off", seen.key)
        assertEquals("Living room", seen.args["room"])
        assertEquals("true", seen.args["light"])
        // Sunset: "you turn the floor lamp on around sunset" -> his Yes -> a dusk routine for that lamp.
        val r = RoutineRules.adopt("sun:set", "home:d1:on", "turn on Floor lamp", s, emptySet(), "hr-abc")!!
        assertEquals(Moment.DUSK, r.moment)
        assertEquals(listOf("d1"), r.devices)
        assertTrue(r.turnOn)
        assertNull(RoutineRules.adopt("match:bar", "home:d1:on", "x", s, emptySet(), "hr-abc"))
        assertEquals("suggestion", r.source)
    }

    @Test fun theClockLooksAtSunsetAndThroughTheNight() {
        val d = book.load()
        val sunset = Sun.times(LocalDate.of(2026, 10, 20), lat, lng)!!.second
        assertEquals(sunset, RoutineEngine.nextTick(d, at(20, 15), zone, lat, lng, null))
        assertEquals(at(20, 23, 30), RoutineEngine.nextTick(d, at(20, 19), zone, lat, lng, null))
        assertEquals(at(21, 1, 15), RoutineEngine.nextTick(d, at(21, 1), zone, lat, lng, null))
        assertEquals(sunset + 86_400_000L - RoutineEngine.nextTick(d, at(21, 9), zone, lat, lng, null) in -300_000L..300_000L, true)
    }
}
