package dev.phonerecall.hestia

import dev.phonerecall.brain.ExecResult
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Step
import dev.phonerecall.brain.StepExecutor
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.stateJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Chat-made routines: a fake Athena turns his words into propose_routine input (her part); the phone checks it, says it
 * back in one sentence, and runs it through the same guard. Several phrasings, refusals in plain words, edits by chat.
 */
class RoutineSpecTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val fake = FakeHomeBackend(FakeHomeBackend.sample().let { s ->
        s.copy(devices = s.devices.map { if (it.id == "d3") it.copy(on = true) else it } +
            HomeDevice("pc", "Computer", DeviceKind.PLUG, "r2", "s1", traits = setOf(Trait.ON_OFF), on = true) +
            HomeDevice("hl", "Hall light", DeviceKind.LIGHT, "r4", "s1", traits = setOf(Trait.ON_OFF, Trait.LEVEL), on = false, level = 50) +
            HomeDevice("th", "Thermostat", DeviceKind.THERMOSTAT, "r1", "s1"))
    })
    private val s get() = fake.state.value
    private val book = RoutineBook(MapKv()).also { b -> b.update { RoutineRules.seed(it, fake.state.value) } }
    private var n = 0
    private fun check(input: Map<String, Any?>) = RoutineSpec.check(input, s, book.load()) { "rt-${++n}" }
    private fun ok(input: Map<String, Any?>) = check(input) as? SpecCheck.Ok ?: error("refused: ${(check(input) as? SpecCheck.Bad)?.reason}")
    private fun bad(input: Map<String, Any?>) = check(input) as? SpecCheck.Bad ?: error("not refused")

    private val core = HestiaCore(fake)
    private val state = FakeState(home = fake.state.value)
    private val store = MemoryStore()
    private val fates = Fates(store, state, object : StepExecutor {
        override fun run(step: Step): ExecResult = core.control(step.payload)
        override fun undo(step: Step): Boolean = core.restore(step.undo!!)
    }, { _, _, _ -> }, { at(20, 21) })
    private val engine = RoutineEngine(book, { fake.state.value }, { steps, title ->
        state.home = fake.state.value
        when (val r = fates.own(steps, title, "Home routines")) { is ProposeResult.Created -> listOf(r.proposal.id); is ProposeResult.Invalid -> error(r.reason) }
    })
    private fun now(t: Long, home: Boolean? = null) = RoutineEngine.now(t, zone, home, 42.89, -78.88, null, false, null)

    @Test fun whenIGetHomeAfterNineTurnOnTheHallLight() {
        // "Athena, when I get home after 9 turn on the hall light"
        val r = ok(mapOf("name" to "Hall light at night", "when" to mapOf("type" to "arrive"), "if" to listOf(mapOf("type" to "after", "value" to "21:00")),
            "then" to listOf(mapOf("type" to "device", "device" to "hall light", "action" to "on"))))
        assertEquals("When you get home, if after 9:00 PM: turn on Hall light.", r.sentence)
        assertEquals("chat", r.routine.source)
        book.put(r.routine)
        engine.event(Moment.LEAVE, now(at(20, 18)))
        assertEquals("before 21:00", engine.event(Moment.ARRIVE, now(at(20, 20, 30))).first { it.routine.id == r.routine.id }.skipped)
        engine.event(Moment.LEAVE, now(at(20, 20, 40)))
        val ran = engine.event(Moment.ARRIVE, now(at(20, 21, 30))).first { it.routine.id == r.routine.id }
        assertEquals(ran.skipped, 1, ran.ids.size)
        assertEquals(true, s.device("hl")!!.on)
    }

    @Test fun leavingHomeSaysTheComputerIsLeftAlone() {
        // "jab main ghar se niklun, bedroom ki lights aur saare plugs band kar dena, computer chhod ke"
        val r = ok(mapOf("when" to mapOf("type" to "leave"), "then" to listOf(mapOf("type" to "lights", "action" to "off", "rooms" to listOf("bedroom"), "every_plug" to true))))
        assertEquals("When you leave home: turn off the Bedroom lights and every plug and switch, except Computer.", r.sentence)
        assertEquals(listOf("Bedroom"), r.routine.rooms)
        assertTrue(RoutineRules.targets(r.routine, s, book.load().never, emptyList()).none { it.id == "pc" })
    }

    @Test fun morePhrasings() {
        // "half an hour before sunset on weekdays, if I'm home, floor lamp to 40%"
        val a = ok(mapOf("when" to mapOf("type" to "sunset", "offset_min" to -30), "if" to listOf(mapOf("type" to "weekdays"), mapOf("type" to "home")),
            "then" to listOf(mapOf("type" to "device", "device" to "Floor lamp", "action" to "brightness", "value" to "40"))))
        assertEquals("30 min before sunset, if on weekdays and you're home: set Floor lamp to 40%.", a.sentence)
        // "at 10:30 PM play my sleep playlist and put up a card to charge the car"
        val b = ok(mapOf("when" to mapOf("type" to "time", "at" to "22:30"), "then" to listOf(mapOf("type" to "music", "query" to "sleep playlist"), mapOf("type" to "card", "title" to "Charge the car"))))
        assertEquals("At 10:30 PM: play sleep playlist and put a card on Olympus: Charge the car.", b.sentence)
        // "when my alarm goes off, turn the bedside lamp on"
        val c = ok(mapOf("when" to mapOf("type" to "wake"), "then" to listOf(mapOf("type" to "lights", "action" to "on", "devices" to listOf("Bedside lamp")))))
        assertEquals("When you dismiss your alarm: turn on Bedside lamp.", c.sentence)
        // "when I get to work, turn off every light at home"
        val d = ok(mapOf("when" to mapOf("type" to "arrive", "place" to "work"), "then" to listOf(mapOf("type" to "lights", "action" to "off", "rooms" to listOf("*")))))
        assertEquals("When you get to work: turn off every light.", d.sentence)
        assertEquals("work", d.routine.placeKey)
    }

    @Test fun locksMessagesAndPaymentsAreRefusedInPlainWords() {
        assertEquals(HomeRules.FORBIDDEN, bad(mapOf("when" to mapOf("type" to "arrive"), "then" to listOf(mapOf("type" to "device", "device" to "Front door", "action" to "off")))).reason)
        assertEquals(HomeRules.FORBIDDEN, bad(mapOf("when" to mapOf("type" to "arrive"), "then" to listOf(mapOf("type" to "unlock", "device" to "Front door")))).reason)
        assertEquals(RoutineSpec.NO_MESSAGES, bad(mapOf("when" to mapOf("type" to "leave"), "then" to listOf(mapOf("type" to "message", "to" to "Amma", "text" to "leaving")))).reason)
        assertEquals(RoutineSpec.NO_MESSAGES, bad(mapOf("when" to mapOf("type" to "arrive"), "then" to listOf(mapOf("type" to "pay", "amount" to "5")))).reason)
        assertEquals(RoutineSpec.NO_MESSAGES, bad(mapOf("when" to mapOf("type" to "car_on"), "then" to listOf(mapOf("type" to "whatsapp", "action" to "send")))).reason)
        // A card that only mentions a call is still just a card.
        assertNotNull(ok(mapOf("when" to mapOf("type" to "wake"), "then" to listOf(mapOf("type" to "card", "title" to "Call Amma today")))))
        // Never automate: refused unless he said so for this routine.
        assertTrue(bad(mapOf("when" to mapOf("type" to "time", "at" to "02:00"), "then" to listOf(mapOf("type" to "device", "device" to "Computer", "action" to "off")))).reason.contains("Never automate"))
        val over = ok(mapOf("when" to mapOf("type" to "time", "at" to "02:00"), "then" to listOf(mapOf("type" to "device", "device" to "Computer", "action" to "off", "override_never" to true))))
        assertEquals(listOf("pc"), over.routine.overrides)
        assertTrue(bad(mapOf("when" to mapOf("type" to "leave"), "then" to listOf(mapOf("type" to "lights", "action" to "on", "every_plug" to true)))).reason.contains("never all of them on"))
        assertTrue(bad(mapOf("when" to mapOf("type" to "time", "at" to "25:00"), "then" to listOf(mapOf("type" to "card", "title" to "x")))).reason.contains("HH:MM"))
    }

    @Test fun whatZeusCantDoYetGoesToPrometheus() {
        val charging = bad(mapOf("when" to mapOf("type" to "charging"), "then" to listOf(mapOf("type" to "lights", "action" to "off", "rooms" to listOf("*")))))
        assertEquals("Routine: the phone starts charging", charging.assess)
        assertTrue(charging.reason.contains("ask_prometheus") && charging.reason.contains("assess"))
        assertNotNull(bad(mapOf("when" to mapOf("type" to "doorbell_rings"), "then" to listOf(mapOf("type" to "card", "title" to "x")))).assess)
        assertNotNull(bad(mapOf("when" to mapOf("type" to "leave"), "then" to listOf(mapOf("type" to "vacuum")))).assess)
        assertNotNull(bad(mapOf("when" to mapOf("type" to "leave"), "then" to listOf(mapOf("type" to "device", "device" to "Thermostat", "action" to "off")))).assess)
        assertNotNull(bad(mapOf("when" to mapOf("type" to "leave"), "if" to listOf(mapOf("type" to "raining")), "then" to listOf(mapOf("type" to "card", "title" to "x")))).assess)
    }

    @Test fun editAndDeleteByChat() {
        book.edit("arrive") { it.copy(runs = 4, history = listOf(RunLine(at(19, 20), "Floor lamp on"))) }
        // "make the home-after-dark one only after 10 PM"
        val e = ok(mapOf("id" to "arrive", "when" to mapOf("type" to "arrive"), "if" to listOf(mapOf("type" to "dark"), mapOf("type" to "after", "value" to "22:00")),
            "then" to listOf(mapOf("type" to "lights", "action" to "on", "rooms" to listOf("Living room"), "usual" to true))))
        assertEquals("arrive", e.routine.id)
        assertNotNull(e.before)
        assertEquals("When you get home, if it's dark and after 10:00 PM: turn on the Living room lights (until it learns your usual).", e.sentence)
        book.put(e.routine)
        assertEquals(4, book.load().routine("arrive")!!.runs)
        assertEquals(1, book.load().routine("arrive")!!.history.size)
        assertTrue(check(mapOf("id" to "asleep", "delete" to true)) is SpecCheck.Delete)
        assertTrue(bad(mapOf("id" to "nope", "delete" to true)).reason.contains("read_routines"))
    }

    @Test fun theCardIsOneSentenceAndItsConfirmRechecksTheGuard() {
        val r = ok(mapOf("when" to mapOf("type" to "arrive"), "then" to listOf(mapOf("type" to "device", "device" to "Porch light", "action" to "on"))))
        val p = (fates.propose(listOf(RoutineTool.step(r)), r.sentence, null, "Athena", onRequest = true) as ProposeResult.Created).proposal
        assertEquals(ProposalKind.SET_ROUTINE, p.steps.single().kind)
        assertEquals("When you get home: turn on Porch light.", p.steps.single().summary)
        // A routine smuggling a lock past the tool is refused when it is validated.
        val lock = r.routine.copy(then = listOf(Act("device", mapOf("device_id" to "d8", "action" to "on"))))
        val smuggled = fates.propose(listOf(RawStep(ProposalKind.SET_ROUTINE, mapOf("routine" to stateJson.writeValueAsString(lock), "sentence" to "x"))), "x", null, "Athena", onRequest = true)
        assertTrue(smuggled is ProposeResult.Invalid && smuggled.reason.contains(HomeRules.FORBIDDEN))
        // And the run path would skip it anyway: a forbidden device never becomes a step.
        book.put(lock)
        assertTrue(engine.event(Moment.ARRIVE, now(at(20, 21))).first { it.routine.id == lock.id }.skipped == "nothing to switch")
        assertNull(fake.sent.firstOrNull { it.first == "d8" })
    }

    @Test fun readRoutinesRoundTripsHerShape() {
        val view = RoutineTool.read(book.load(), s)
        @Suppress("UNCHECKED_CAST") val rs = view["routines"] as List<Map<String, Any?>>
        val leave = rs.first { it["id"] == "leave" }
        assertEquals(listOf("Computer"), view["never_automate"])
        // What she reads back is what propose_routine takes.
        @Suppress("UNCHECKED_CAST")
        val again = ok(mapOf("id" to "leave", "when" to leave["when"], "then" to leave["then"]) as Map<String, Any?>)
        assertEquals(book.load().routine("leave")!!.copy(source = "preset"), again.routine.copy(source = "preset"))
    }
}
