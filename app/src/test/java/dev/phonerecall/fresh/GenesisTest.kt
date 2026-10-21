package dev.phonerecall.fresh

import dev.phonerecall.brain.AppKind
import dev.phonerecall.mind.FakeMindApi
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.stateJson
import dev.phonerecall.models.ModelBook
import dev.phonerecall.setup.Check
import dev.phonerecall.setup.Fix
import dev.phonerecall.setup.Status
import dev.phonerecall.setup.Telete
import dev.phonerecall.theogony.Gap
import dev.phonerecall.theogony.Interview
import dev.phonerecall.theogony.IrisLink
import dev.phonerecall.theogony.Phase
import dev.phonerecall.theogony.SeedFact
import dev.phonerecall.theogony.SeedPath
import dev.phonerecall.theogony.SeedRows
import dev.phonerecall.theogony.Status as Seen
import dev.phonerecall.theogony.TheogonyEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenesisTest {
    private var now = 1_000_000L
    private val kv = MapKv().apply { put(Genesis.K_START, "500000") }
    private fun genesis() = Genesis(kv) { now }
    private val book = ModelBook(MapKv())

    private fun check(id: String, status: Status, group: String = Telete.PHONE) = Check(id, group, id, "why", status, fix = Fix.Open("HOME"))

    @After fun noGapsLeftBehind() { Interview.extras = { emptyList() }; Interview.onAnswer = null }

    @Test fun stepsGoInOrderAndResume() {
        val g = genesis()
        assertTrue(g.active)
        assertEquals(Stage.SETUP, g.stage)
        assertEquals(Stage.THEOGONY, g.advance(book))
        // A restart picks up where he was.
        assertEquals(Stage.THEOGONY, genesis().stage)
        assertEquals(Stage.SHAKEDOWN, genesis().advance(book))
        assertEquals(Stage.SPLASH, genesis().advance(book))
        assertTrue(genesis().active)
        assertEquals(Stage.DONE, genesis().advance(book))
        assertFalse(genesis().active)
        assertEquals(Stage.DONE, genesis().advance(book))
    }

    @Test fun neverShowsOnAPhoneThatWasNotWiped() {
        val g = Genesis(MapKv())
        assertFalse(g.active)
        assertTrue(g.gaps().isEmpty())
    }

    @Test fun setupRowsAreSkippableAndCountWhatIsLeft() {
        val g = genesis()
        val checks = listOf(
            check("location", Status.MISSING), check("nearby", Status.MISSING), check("usage", Status.OK),
            check("key-anthropic", Status.OK, Telete.KEYS), check("theogony", Status.MISSING, Telete.ATHENA), check("home-office", Status.MISSING, Telete.PLACES),
        )
        var rows = g.rows(checks, phraseSetAt = null, backupSetUp = true)
        assertTrue("Theogony is the next step, not a row", rows.none { it.check.id == "theogony" })
        assertEquals(Status.MISSING, rows.first { it.check.id == Genesis.PHRASE }.check.status)
        assertEquals(4, g.left(rows))
        g.skip("nearby"); g.skip("home-office")
        rows = g.rows(checks, phraseSetAt = 600_000L, backupSetUp = true)
        assertEquals(Status.OK, rows.first { it.check.id == Genesis.PHRASE }.check.status)
        assertEquals(1, g.left(rows))
        assertTrue(rows.first { it.check.id == "nearby" }.skipped)
        // Missing first in each group, groups in Telete's order.
        assertEquals(listOf("location", "nearby", "usage"), rows.filter { it.check.group == Telete.PHONE }.map { it.check.id })
        g.unskip("nearby")
        assertEquals(2, g.left(g.rows(checks, 600_000L, true)))
        // No backup yet: the phrase row is optional (the backup row asks first).
        assertEquals(Status.OPTIONAL, g.rows(checks, null, false).first { it.check.id == Genesis.PHRASE }.check.status)
        // An old phrase, set before the fresh start, is still the old one.
        assertEquals(Status.MISSING, g.rows(checks, 400_000L, true).first { it.check.id == Genesis.PHRASE }.check.status)
    }

    @Test fun annabethLeadsOnFableForTheInterviewAndGetsHerModelBack() {
        val g = genesis()
        val own = book.of(Genesis.ANNABETH)
        assertEquals("claude-opus-5-5", own)
        g.advance(book)
        assertEquals(Genesis.FABLE, book.of(Genesis.ANNABETH))
        g.advance(book)
        assertEquals(own, book.of(Genesis.ANNABETH))
    }

    @Test fun aModelHeChoseDuringTheInterviewIsNotUndone() {
        val g = genesis()
        g.advance(book)
        book.set(Genesis.ANNABETH, "claude-sonnet-5-5")
        g.advance(book)
        assertEquals("claude-sonnet-5-5", book.of(Genesis.ANNABETH))
    }

    // ---- the topics and Annabeth's plan ----

    @Test fun everyTopicHasABuiltInQuestion() {
        assertEquals(GenesisGaps.TOPICS.map { it.id }.toSet() - setOf("sleep", "music"), GenesisGaps.BUILTIN_QUESTIONS.map { it.topic }.toSet())
        val gaps = GenesisGaps.gaps(null)
        assertTrue(gaps.all { it.id.startsWith(GenesisGaps.BUILTIN) })
        assertEquals("home", GenesisGaps.topicOf("z:home")!!.id)
        assertEquals("car", GenesisGaps.topicOf("a:car:3")!!.id)
        assertNull(GenesisGaps.topicOf("wake"))
    }

    @Test fun anAddressOrANumberNeverBecomesAFact() {
        val gaps = GenesisGaps.gaps(null).associateBy { it.id }
        val home = gaps.getValue("z:home").statement("221 Oak Street Ave, Buffalo NY")
        assertFalse(home, home.contains("Oak Street"))
        assertEquals("Home is marked on the phone (Odyssey).", home)
        assertEquals("Home isn't marked yet; ask him when he's there.", gaps.getValue("z:home").statement(GenesisGaps.LATER))
        val phone = gaps.getValue("z:phone").statement("716 555 0199; +91 99999 00002")
        assertFalse(phone, phone.any(Char::isDigit))
        // Her own template can't change that either.
        val planned = GenesisGaps.gaps(listOf(PlannedQuestion("office", "Where do you work?", emptyList(), false, "Office: {answer}.")), builtin = false).single()
        assertEquals("The office is marked on the phone (Odyssey).", planned.statement("1 Main St"))
        assertEquals("Car: Mazda CX-5, leased.", GenesisGaps.gaps(null).first { it.id == "z:car" }.statement("Mazda CX-5, leased"))
        assertEquals("Follows: Barcelona, Buffalo Bills.", GenesisGaps.gaps(null).first { it.id == "z:sports" }.statement("Barcelona; Buffalo Bills"))
    }

    @Test fun herPlanIsCheckedBeforeItIsAsked() {
        val text = """Here is the plan:
            {"questions":[
              {"topic":"parking","question":"Where do you usually park at home?","chips":["Garage","Street"],"multi":false,"statement":"Parks: {answer}."},
              {"topic":"parking","question":"A second parking question?","chips":[]},
              {"topic":"bank_pin","question":"What's your card PIN?"},
              {"topic":"games","question":"Hi"},
              {"topic":"sports","question":"Which teams do you follow, and where do you watch them?","chips":["Barcelona","Buffalo Bills"],"multi":true}
            ]}"""
        val plan = GenesisGaps.parse(text)!!
        assertEquals(listOf("parking", "sports"), plan.map { it.topic })
        val gaps = GenesisGaps.gaps(plan, builtin = false)
        assertEquals(listOf("a:parking:0", "a:sports:1"), gaps.map { it.id })
        assertEquals("Parks: Garage.", gaps[0].statement("Garage"))
        assertEquals("Teams and where he watches: Barcelona, Buffalo Bills.", gaps[1].statement("Barcelona; Buffalo Bills"))
        assertNull(GenesisGaps.parse("I can't help with that."))
        assertNull(GenesisGaps.parse("""{"questions":[]}"""))
        // Kept and read back.
        val g = genesis()
        g.savePlan(plan)
        assertEquals(plan, genesis().plan())
        assertEquals(listOf("a:parking:0", "a:sports:1"), genesis().gaps().map { it.id })
    }

    @Test fun herBriefCarriesTheTopicsAndOnlySanitizedLines() {
        val clean = Sanitizer(mapOf("Meera" to "family (mother)"))
        val facts = listOf(
            SeedFact(SeedPath.PEOPLE, "Meera lives in Pune.", "iris", 0.8, "people"),
            SeedFact(SeedPath.ROUTINES, "Office Monday to Wednesday.", "iris", 0.8, "work_week"),
        )
        val p = GenesisGaps.prompt(facts, clean)
        for (t in GenesisGaps.TOPICS) assertTrue(t.id, p.contains("- ${t.id}: "))
        assertFalse(p.contains("Meera"))
        assertTrue(p.contains("Office Monday to Wednesday."))
        assertTrue(p.contains("Do not call any tools"))
    }

    @Test fun annabethWritesThePlanInHerOwnSessionAndAToolCallIsRefused() {
        val api = FakeMindApi()
        val ref = api.createAgent(dev.phonerecall.mind.AgentSpec(Protege.ANNABETH.agentName, Genesis.FABLE, "", "", emptyList()))
        var first = true
        api.script = { if (first) { first = false; "read_facts" to emptyMap() } else null }
        api.reply = """{"questions":[{"topic":"games","question":"Do you play anything with friends?","chips":["PUBG"],"multi":true}]}"""
        val r = AnnabethPlan(api, sleep = {}).ask(ref, "env", "store", "brief")
        assertEquals(listOf("games"), GenesisGaps.parse(r.text)!!.map { it.topic })
        assertTrue(api.lastToolResult.orEmpty().contains("Not available while planning"))
        assertTrue(r.sessionId in api.archivedSessions)
    }

    @Test fun noAnswerInTimeFallsBackToTheBuiltInQuestions() {
        val api = FakeMindApi()
        val ref = api.createAgent(dev.phonerecall.mind.AgentSpec(Protege.ANNABETH.agentName, Genesis.FABLE, "", "", emptyList()))
        // She never goes idle.
        val silent = object : dev.phonerecall.mind.MindApi by api { override fun sendMessage(sessionId: String, text: String) {} }
        var t = 0L
        val r = AnnabethPlan(silent, clock = { t }, sleep = { t += it }).ask(ref, "env", "store", "brief", limitMs = 10_000)
        assertNull(r.text)
        assertEquals("no answer in 10 s", r.why)
        assertTrue(r.sessionId in api.archivedSessions)
        val g = genesis()
        g.savePlan(GenesisGaps.parse(r.text))
        assertTrue(g.planned)
        assertTrue(g.gaps().all { it.id.startsWith(GenesisGaps.BUILTIN) })
    }

    // ---- with Theogony ----

    private class Iris(val reply: String?) : IrisLink {
        var sent = ""
        override fun available() = true
        override fun send(text: String, clientId: String): Long { sent = text; return 7 }
        override fun reply(messageId: Long) = reply
    }

    private val confirmed = ArrayList<SeedFact>()
    private val rows = object : SeedRows {
        override fun confirmed(f: SeedFact) { confirmed += f }
        override fun forgot(f: SeedFact) {}
    }

    private fun theogony(iris: IrisLink) = TheogonyEngine(MapKv(), iris, emptyList(), {}, { _, _ -> }, { Sanitizer() }, { now }, { "ask01" }, rows = rows)

    @Test fun whatIrisKnowsLandsInTheTickListNotInTheFacts() {
        val reply = stateJson.writeValueAsString(mapOf("type" to "ask.reply", "id" to "ask01", "sections" to mapOf(
            "profile" to listOf("Works as a data engineer at an employer [email]"),
            "accounts" to listOf("Banks with M&T and Chase [email]"),
            "senders" to listOf("Bank alerts from M&T always matter [memory]"),
        )))
        val iris = Iris(reply)
        val e = theogony(iris)
        e.seed()
        assertTrue("the seed asks for the fresh start's sections", listOf("profile", "accounts", "senders").all { iris.sent.contains(it) })
        val opening = e.state().question!!
        assertEquals(Interview.OPENING, opening.id)
        val texts = e.facts().filter { it.key in opening.bundle }.map { it.text }
        assertTrue(texts.toString(), texts.any { "data engineer" in it })
        assertTrue("nothing is confirmed until he ticks it", confirmed.isEmpty())
        e.confirmBundle(opening.id, opening.bundle.toSet())
        assertTrue(confirmed.isNotEmpty() && confirmed.all { it.status == Seen.CONFIRMED })
    }

    @Test fun genesisTopicsAreAskedFirstAndAnswersAreKeptAsConfirmedAndFiled() {
        val g = genesis()
        val filed = ArrayList<Pair<String, String>>()
        Interview.extras = { g.gaps() }
        Interview.onAnswer = { gap: Gap, w: String -> filed += gap.id to w }
        val e = theogony(Iris(null).let { object : IrisLink by it { override fun available() = false } })
        e.seed()
        assertEquals(Phase.INTERVIEW, e.phase)
        val first = e.state().question!!
        assertEquals("z:home", first.id)
        e.answer("z:home", "I'm home now")
        e.answer("z:office", GenesisGaps.LATER)
        e.answer("z:car", "Mazda CX-5, leased")
        assertEquals(listOf("z:home" to "I'm home now", "z:office" to GenesisGaps.LATER, "z:car" to "Mazda CX-5, leased"), filed)
        val car = e.facts().single { it.topic == "z:car" }
        assertEquals(Seen.CONFIRMED, car.status); assertEquals("you", car.source); assertEquals("Car: Mazda CX-5, leased.", car.text)
        assertTrue(confirmed.any { it.topic == "z:car" })
        assertEquals("Home is marked on the phone (Odyssey).", e.facts().single { it.topic == "z:home" }.text)
        // Her plan arrives: the interview is planned again with her questions, his answers stay.
        g.savePlan(listOf(PlannedQuestion("parking", "Where do you park at home?", listOf("Garage"), false, null)))
        e.replan()
        assertEquals("a:parking:0", e.state().question!!.id)
        assertEquals(3, e.facts().count { it.source == "you" })
    }

    @Test fun alwaysMatterSetsTheTriageFloor() {
        assertEquals(setOf(AppKind.BANK, AppKind.WORK), SenseRules.parse("Bank alerts; Work chat; Deliveries"))
        val old = SenseRules.kinds
        try {
            SenseRules.kinds = { setOf(AppKind.BANK) }
            val v = dev.phonerecall.brain.Rules.triage(AppKind.BANK, null, null, dev.phonerecall.brain.Cues(), false)
            assertEquals(dev.phonerecall.brain.Importance.MEDIUM, v.importance)
            val chat = dev.phonerecall.brain.Rules.triage(AppKind.OTHER, null, null, dev.phonerecall.brain.Cues(), false)
            assertEquals(dev.phonerecall.brain.Importance.LOW, chat.importance)
        } finally { SenseRules.kinds = old }
    }

    @Test fun theProfileForAthenaHasOnlyHisConfirmedLines() {
        val facts = listOf(
            SeedFact(SeedPath.PREFERENCES, "Parking: Garage.", "you", 1.0, "a:parking:0", Seen.CONFIRMED),
            SeedFact(SeedPath.PROFILE, "Maybe likes cricket.", "phone", 0.4, "x", Seen.INFERRED),
            SeedFact(SeedPath.PEOPLE, "Family: Meera in India.", "you", 1.0, "z:family", Seen.CONFIRMED),
        )
        val p = GenesisGaps.profile(facts, Sanitizer(mapOf("Meera" to "family (mother)")))
        assertTrue(p.contains("- Parking: Garage."))
        assertFalse(p.contains("cricket"))
        assertFalse(p.contains("Meera"))
        assertTrue(p.startsWith("# The owner"))
    }
}
