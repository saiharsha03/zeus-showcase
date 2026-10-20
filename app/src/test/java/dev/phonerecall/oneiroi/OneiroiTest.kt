package dev.phonerecall.oneiroi

import dev.phonerecall.mind.LongJob
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.MemoryFile
import dev.phonerecall.mind.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

private fun noTwoLive(store: FactStore) {
    val live = store.live()
    assertEquals("two live versions of one fact", live.size, live.map { it.key }.distinct().size)
}

/** A model that answers from a script and records what it was sent. */
private class ScriptModel(var reply: String = "{}", var micros: Long = 1_000) : MeteredModel {
    val sent = ArrayList<String>()
    override fun complete(system: String, user: String, maxTokens: Int): Metered { sent += user; return Metered(reply, micros) }
}

class FactStoreTest {
    private var now = 1_792_000_000_000L
    private fun store() = FactStore(MapKv()) { now++ }

    @Test fun aChangedFactSupersedesAndNeverLeavesTwoLive() {
        val s = store()
        val a = s.put("car", "His car is a Mazda CX-5", source = "chat", key = "car model") as FactWrite.Added
        val b = s.put("car", "His car is a Honda Civic", source = "chat", key = "car model") as FactWrite.Superseded
        assertEquals(a.fact.id, b.old.id)
        assertEquals(a.fact.id, b.fact.supersedes)
        assertEquals(b.fact.id, s.get(a.fact.id)!!.supersededBy)
        assertEquals(listOf("His car is a Honda Civic"), s.live().map { it.text })
        noTwoLive(s)
        // Saying it again changes nothing; a correction by id makes the third version.
        assertTrue(s.put("car", "His car is a Honda Civic", source = "chat", key = "car model") is FactWrite.Same)
        val c = s.supersede(b.fact.id, "His car is a Honda Accord", "owner") as FactWrite.Superseded
        assertEquals(FactStatus.CONFIRMED, c.fact.statusOf)
        assertEquals(listOf(a.fact.id, b.fact.id, c.fact.id), s.chain(a.fact.id).map { it.id })
        assertTrue(s.supersede(a.fact.id, "anything", "owner") is FactWrite.Refused)
        noTwoLive(s)
        assertEquals(3, s.all().size)
    }

    @Test fun mergeSettleAndRetractKeepOneLiveVersion() {
        val s = store()
        val x = (s.put("home", "He lives in Buffalo", source = "a") as FactWrite.Added).fact
        val y = (s.put("home", "He rents in Buffalo", source = "b") as FactWrite.Added).fact
        val m = s.merge(listOf(x.id, y.id), "He rents a flat in Buffalo", "dream", FactStatus.INFERRED) as FactWrite.Superseded
        assertEquals(listOf(m.fact.id), s.live().map { it.id })
        assertTrue(s.retract(m.fact.id))
        assertTrue(s.live().isEmpty())
        assertFalse(s.retract(m.fact.id))
        noTwoLive(s)
    }

    @Test fun statusDefaultsToObservedAndUnknownStatusesStillRead() {
        val s = store()
        val f = (s.put("work", "Office Tuesday to Thursday", source = "chat") as FactWrite.Added).fact
        assertEquals("observed", f.status)
        assertEquals(FactStatus.OBSERVED, Fact(status = "theogony-verified").statusOf)
        // A stronger status is a new version, not an edit in place.
        assertTrue(s.put("work", "Office Tuesday to Thursday", FactStatus.CONFIRMED, "owner") is FactWrite.Superseded)
        noTwoLive(s)
    }
}

class DerivationTest {
    @Test fun aRunningNumberIsRefusedAndItsFormulaIsKept() {
        val s = FactStore(MapKv())
        assertTrue(s.put("money", "Monthly fuel is $212", source = "chat") is FactWrite.Refused)
        assertTrue(s.put("money", "He spent 212 on fuel this month", source = "chat") is FactWrite.Refused)
        // What the Sanitizer leaves of an amount is still a stale number.
        assertTrue(s.put("money", "Monthly fuel is [amount]", source = "chat") is FactWrite.Refused)
        val f = s.put("money", "monthly fuel = sum(Moneta Fuel, month)", source = "chat", kind = FactKind.DERIVED) as FactWrite.Added
        assertEquals(FactKind.DERIVED, f.fact.kindOf)
        assertTrue(s.put("money", "monthly fuel = 212", source = "chat", kind = FactKind.DERIVED) is FactWrite.Refused)
        assertTrue(s.put("money", "monthly fuel = sum($212)", source = "chat", kind = FactKind.DERIVED) is FactWrite.Refused)
        // Ordinary numbers are fine: they are not totals.
        assertTrue(s.put("car", "His car is a 2021 Mazda", source = "chat") is FactWrite.Added)
        // A correction of a formula must be a formula.
        assertTrue(s.supersede(f.fact.id, "it was 230 this month", "owner") is FactWrite.Refused)
        assertTrue(s.markdown().contains("monthly fuel = sum(Moneta Fuel, month)"))
        assertFalse(s.markdown().contains("212"))
    }
}

class ReflectTest {
    private val clean = Sanitizer(mapOf("Amma" to "family (mother)"))

    private fun rig(reply: String): Triple<Reflector, FactStore, ScriptModel> {
        val kv = MapKv()
        val facts = FactStore(kv)
        val model = ScriptModel(reply)
        return Triple(Reflector(facts, RefTable(kv), IrisQueue(kv), { clean }, { model }), facts, model)
    }

    @Test fun eachFindingGoesToItsStoreAndCorrectionsSupersede() {
        val kv = MapKv()
        val facts = FactStore(kv)
        val refs = RefTable(kv)
        val iris = IrisQueue(kv)
        val old = (facts.put("car", "His car is a Mazda", source = "chat", key = "car model") as FactWrite.Added).fact
        val reply = """Sure: ```json
{"facts":[{"topic":"car","key":"car model","text":"His car is a Honda Civic","status":"confirmed","corrects":${old.id}},
 {"topic":"home","key":"city","text":"He moved to Rochester","status":"confirmed","for_iris":true},
 {"topic":"money","key":"fuel","text":"monthly fuel = sum(Moneta Fuel, month)","derivation":true},
 {"topic":"money","key":"fuel total","text":"Fuel this month was 212"}],
 "refs":[{"term":"M3","meaning":"his Mazda","kind":"nickname"}],
 "lessons":["He wants one-line answers before 9 AM"],"workflows":["Groceries go to the Groceries list"]}
```"""
        val model = ScriptModel(reply)
        val r = Reflector(facts, refs, iris, { clean }, { model })
        val out = r.reflect("Delphi", listOf(Said(1, Said.OWNER, "Actually I drive a Honda Civic now, and I moved to Rochester"), Said(2, Said.ATHENA, "Noted.")))
        assertEquals(1, out.corrected)
        assertEquals(2, out.added)
        assertEquals(1, out.refused)
        assertEquals(1, out.refs)
        assertEquals(2, out.lessons)
        assertEquals(1, out.toIris)
        assertFalse(facts.get(old.id)!!.live)
        assertEquals("His car is a Honda Civic", facts.live().first { it.topic == "car" }.text)
        assertEquals(FactKind.DERIVED, facts.live().first { it.topic == "money" }.kindOf)
        assertEquals(listOf("lessons", "workflows"), facts.live().filter { it.kindOf == FactKind.LESSON }.map { it.topic })
        assertEquals("his Mazda", refs.all().single().meaning)
        val e = iris.queued().single()
        assertEquals("note", e.type); assertEquals("athena", e.from); assertEquals("read", e.authority); assertEquals("home", e.topic)
        noTwoLive(facts)
    }

    @Test fun theSanitizerCleansWhatGoesOutAndWhatComesBack() {
        val (r, facts, model) = rig("""{"facts":[{"topic":"family","text":"Amma's email is amma@example.com"},{"topic":"health","text":"He has a doctor appointment for his blood pressure"}]}""")
        r.reflect("Delphi", listOf(Said(1, Said.OWNER, "Amma called from 716-555-0142, her email is amma@example.com."), Said(2, Said.OWNER, "My doctor appointment is Friday.")))
        val sent = model.sent.single()
        assertFalse(sent.contains("Amma"))
        assertFalse(sent.contains("716-555-0142"))
        assertFalse(sent.contains("amma@example.com"))
        assertFalse(sent.contains("doctor"))
        assertEquals(listOf("family (mother)'s email is [email]"), facts.live().map { it.text })
    }

    @Test fun nothingHeSaidMeansNoCall() {
        val (r, _, model) = rig("{}")
        assertEquals("nothing he said", r.reflect("job", listOf(Said(1, Said.ATHENA, "Done."))).skipped)
        assertTrue(model.sent.isEmpty())
    }
}

class RefsTest {
    @Test fun theSweepReadsEachLineOnceThanksToTheWatermark() {
        val kv = MapKv()
        val table = RefTable(kv)
        val sweep = RefSweep(kv, table)
        val said = listOf(Said(10, Said.OWNER, "M3 is my Mazda, it needs an oil change."), Said(20, Said.OWNER, "Book the service for my car, Beast."))
        var reads = 0
        val extract = { l: List<Said> -> reads += l.size; RefRules.extract(l) }
        assertEquals(2, sweep.run(said, extract))
        assertEquals(20L, sweep.watermark())
        assertEquals(0, sweep.run(said, extract))
        assertEquals(2, reads)
        // A new line after the stamp is read alone.
        assertEquals(1, sweep.run(said + Said(30, Said.OWNER, "My dog, Nest, hates the snow."), extract))
        assertEquals(3, reads)
        assertEquals(mapOf("M3" to "his Mazda", "Beast" to "his car"), table.all().filter { it.term != "Nest" }.associate { it.term to it.meaning })
    }

    @Test fun resolutionIsDeterministicWholeWordAndLongestFirst() {
        val table = RefTable(MapKv())
        table.put("M3", "his Mazda", source = "t")
        table.put("M3 GT", "his brother's car", source = "t")
        assertEquals(listOf("M3 GT" to "his brother's car"), table.resolve("Is the M3 GT back?"))
        assertEquals(listOf("M3" to "his Mazda"), table.resolve("fill up m3 tomorrow"))
        assertTrue(table.resolve("M35 is a road").isEmpty())
        assertEquals("[Zeus refs] Amma = family (mother); M3 = his Mazda", table.note("Amma wants a ride in M3", mapOf("Amma" to "family (mother)")))
        assertNull(table.note("nothing here"))
        // A wrong entry is fixed where it lives: the latest meaning wins.
        table.put("M3", "his Mazda CX-5", source = "t")
        assertEquals("his Mazda CX-5", table.resolve("M3").single().second)
    }
}

class DreamTest {
    private val clean = Sanitizer(mapOf("Riley" to "friend"))
    private var now = 1_792_000_000_000L

    private fun facts(kv: MapKv) = FactStore(kv) { now }.also {
        it.put("home", "He lives in Buffalo", source = "a")
        it.put("profile", "He lives in Buffalo", source = "b")
        it.put("car", "His car is a Mazda", source = "a", key = "car")
        it.put("car", "He drives a Mazda CX-5", source = "b")
    }

    private fun dreamer(f: FactStore, kv: MapKv, cap: Long = Dreamer.CAP_MICROS) = Dreamer(f, kv, { clean }, { ZoneId.of("America/New_York") }, { now }, cap)

    @Test fun theRunsChangesApplyAfterThePlainRepeats() {
        val kv = MapKv()
        val f = facts(kv)
        val n = dreamer(f, kv).applyRun(mapOf(
            "merges" to listOf(mapOf("keep" to 3, "drop" to listOf(4), "text" to "His car is a Mazda CX-5")),
            "patterns" to listOf(mapOf("topic" to "car", "key" to "one car", "text" to "He keeps one car")),
            "note" to "Tidied the car facts",
        ))
        assertTrue(n.lines.any { it.contains("repeat") })
        assertTrue(n.lines.any { it.startsWith("Merged #3, #4") })
        assertTrue(n.lines.any { it.startsWith("New pattern") })
        assertEquals("Athena: Tidied the car facts", n.lines.last())
        noTwoLive(f)
    }

    @Test fun readFactsIsSanitized() {
        val kv = MapKv()
        val f = FactStore(kv) { now }
        f.put("people", "Riley likes chai", source = "chat with Riley")
        f.put("health", "His dentist is on Oak Street", source = "chat")
        f.put("contact", "Email him at h@example.com", source = "chat")
        val rows = dreamer(f, kv).rows()
        assertEquals(2, rows.size)
        assertEquals("friend likes chai", rows[0]["text"])
        assertEquals("chat with friend", rows[0]["source"])
        assertEquals("Email him at [email]", rows[1]["text"])
        assertEquals(setOf("id", "topic", "key", "text", "status", "source", "created"), rows[0].keys)
    }

    @Test fun confirmedFactsAreNeverDroppedOrRewritten() {
        val kv = MapKv()
        val f = FactStore(kv) { now }
        val sure = (f.put("home", "He lives in Buffalo", FactStatus.CONFIRMED, "owner", key = "city") as FactWrite.Added).fact
        val guess = (f.put("home", "He may live in Toronto", FactStatus.INFERRED, "dream", key = "maybe") as FactWrite.Added).fact
        val d = dreamer(f, kv)
        val n = d.applyRun(mapOf(
            "merges" to listOf(mapOf("keep" to guess.id, "drop" to listOf(sure.id)), mapOf("keep" to sure.id, "drop" to listOf(guess.id), "text" to "He lives in Toronto")),
            "retire" to listOf(sure.id),
            "replace" to listOf(mapOf("id" to sure.id, "text" to "He lives in Toronto")),
        ))
        assertTrue(f.get(sure.id)!!.live)
        assertEquals("He lives in Buffalo", f.get(sure.id)!!.text)
        assertEquals("nothing to change", n.skipped)
        // Kept as is, a confirmed fact may absorb a guess.
        d.applyRun(mapOf("merges" to listOf(mapOf("keep" to sure.id, "drop" to listOf(guess.id)))))
        assertFalse(f.get(guess.id)!!.live)
    }

    @Test fun numbersStayFormulasAndTheNightsCapStopsApplying() {
        val kv = MapKv()
        val f = FactStore(kv) { now }
        val fuel = (f.put("car", "Fuel is bought at Costco", source = "a") as FactWrite.Added).fact
        val d = dreamer(f, kv, cap = 50_000)
        d.applyRun(mapOf("replace" to listOf(mapOf("id" to fuel.id, "text" to "Monthly fuel is 212 dollars"))))
        assertEquals("Fuel is bought at Costco", f.get(fuel.id)!!.text)
        d.spent(50_000)
        val n = d.applyRun(mapOf("retire" to listOf(fuel.id)))
        assertEquals("over tonight's cap", n.skipped)
        assertTrue(f.get(fuel.id)!!.live)
    }

    @Test fun theOldPhoneDreamJobIsCancelled() {
        val cancelled = ArrayList<String>()
        OneiroiWork.retire { cancelled += it }
        assertEquals(listOf("zeus-oneiroi-dream"), cancelled)
    }
}

class FoldTest {
    @Test fun oldMemoryFilesBecomeRowsWithABackupFirst() {
        val f = FactStore(MapKv())
        val files = listOf(
            MemoryFile("m1", "/profile/owner.md", "s1", 0, "# The owner\n- Eastern time. Quiet hours 11 PM to 7 AM.\n- Likes short plain English.\n- Monthly fuel is $212\n- Email him at h@example.com"),
            MemoryFile("m2", "/snapshot/today.md", "s2", 0, "- phone owned"),
            MemoryFile("m3", "/notes/car.md", "s3", 0, "M3 needs tyres"),
            MemoryFile("m4", "/outbox/run.json", "s4", 0, "{}"),
        )
        val order = ArrayList<String>()
        val rewritten = HashMap<String, String>()
        val r = MemoryFold.fold(files, f, Sanitizer(), { b -> order += "backup:" + b.joinToString { it.path } }, { file, text -> order += "rewrite:${file.path}"; rewritten[file.path] = text }, "2026-10-20")
        assertEquals("backup:/profile/owner.md, /notes/car.md", order.first())
        assertEquals(setOf("/profile/owner.md", "/notes/car.md"), rewritten.keys)
        assertEquals(2, r.files)
        assertEquals(4, r.added)
        assertEquals(listOf("Monthly fuel is [amount]"), r.refused)
        assertTrue(f.live().filter { it.topic == "profile" }.all { it.statusOf == FactStatus.CONFIRMED })
        assertTrue(f.live().any { it.text == "Email him at [email]" })
        assertEquals("car", f.live().first { it.text.startsWith("M3") }.topic)
        // Folded files hold the pointer now, so the next night folds nothing.
        val again = files.map { m -> rewritten[m.path]?.let { m.copy(content = it) } ?: m }
        assertEquals(0, MemoryFold.fold(again, f, Sanitizer(), { error("no backup") }, { _, _ -> error("no rewrite") }, "2026-10-21").files)
    }
}

class SubconsciousTest {
    private var now = 1_792_000_000_000L
    private val day = 86_400_000L

    @Test fun theSweepOnlyProposes() {
        val kv = MapKv()
        val facts = FactStore(kv)
        facts.put("car", "His car is a Mazda CX-5", source = "chat")
        val before = facts.all()
        val sub = Subconscious(kv, facts) { now }
        assertTrue(sub.due())
        val said = listOf(
            Said(now - day, Said.OWNER, "Can you look at flights home?", "chat-1"),
            Said(now - day + 1, Said.ATHENA, "I'll check prices tonight and get back to you.", "chat-1"),
            Said(now - day + 2, Said.ATHENA, "I'll remind you at 6.", "chat-2"),
            Said(now - day + 3, Said.ZEUS, "proposal: Reminder at 6 PM", "chat-2"),
            Said(now - day + 4, Said.OWNER, "Remember that my gym is closed on Sundays.", "chat-2"),
            Said(now - day + 5, Said.OWNER, "My car is a Mazda CX-5.", "chat-2"),
        )
        val jobs = listOf(LongJob("job-1", "Winter tyres", startedAt = now - 5 * day, status = "started", updatedAt = now - 3 * day), LongJob("job-2", "Done one", status = "done", updatedAt = now - 9 * day))
        val found = sub.sweep(said, jobs)
        assertEquals(listOf(Nudge.DROPPED, Nudge.DROPPED, Nudge.BACKFILL), found.map { it.kind })
        assertTrue(found[0].text.startsWith("Athena said she'd check prices tonight"))
        assertTrue(found[1].text.contains("Winter tyres"))
        assertTrue(found[2].text.contains("his gym is closed on Sundays"))
        // Proposals only: the facts are exactly as they were.
        assertEquals(before, facts.all())
        assertFalse(sub.due())
        // The same sweep again proposes nothing new.
        now += 3 * day
        assertTrue(sub.sweep(said, jobs).isEmpty())
        assertEquals(3, sub.open().size)
        assertNotNull(sub.decide(found[0].id, false))
        assertNull(sub.decide(found[0].id, true))
        assertEquals(2, sub.open().size)
        assertEquals(before, facts.all())
    }
}

class MemoryViewTest {
    @Test fun parsesQuestionsCorrectionsAndRetractions() {
        assertEquals(MemoryAsk.About("my car"), MemoryView.parse("What do you know about my car?"))
        assertEquals(MemoryAsk.About("M3"), MemoryView.parse("/memory M3"))
        assertEquals(MemoryAsk.About(""), MemoryView.parse("/memory"))
        assertEquals(MemoryAsk.Correct(12, "a Honda Civic"), MemoryView.parse("#12 is wrong, it's a Honda Civic."))
        assertEquals(MemoryAsk.Correct(3, "Rochester"), MemoryView.parse("correct #3: Rochester"))
        assertEquals(MemoryAsk.Correct(4, "every Friday"), MemoryView.parse("#4 should be every Friday"))
        assertEquals(MemoryAsk.Retract(12), MemoryView.parse("#12 is wrong"))
        assertEquals(MemoryAsk.Retract(7), MemoryView.parse("forget #7"))
        assertEquals(MemoryAsk.Confirm(2), MemoryView.parse("#2 is right"))
        assertNull(MemoryView.parse("what's the weather"))
        assertNull(MemoryView.parse("set an alarm for 6"))
    }

    @Test fun correctAndRetractByIdSupersede() {
        val kv = MapKv()
        val facts = FactStore(kv)
        val refs = RefTable(kv)
        refs.put("M3", "his Mazda", source = "t")
        val car = (facts.put("car", "His Mazda is a 2021 CX-5", source = "chat") as FactWrite.Added).fact
        val gym = (facts.put("routine", "Gym on Mondays", source = "chat") as FactWrite.Added).fact
        val about = MemoryView.answer(MemoryView.parse("what do you know about M3")!!, facts, refs)
        assertEquals(listOf(car.id), about.facts.map { it.id })
        assertEquals("observed", about.facts.single().status)
        val fixed = MemoryView.answer(MemoryView.parse("#${car.id} is wrong, it's His Mazda is a 2022 CX-5")!!, facts, refs)
        assertEquals("Fixed", fixed.title)
        assertFalse(facts.get(car.id)!!.live)
        val now = facts.live().first { it.topic == "car" }
        assertEquals("confirmed", now.status)
        assertEquals(car.id, now.supersedes)
        assertEquals("Forgotten", MemoryView.answer(MemoryAsk.Retract(gym.id), facts, refs).title)
        assertEquals("Not changed", MemoryView.answer(MemoryAsk.Retract(gym.id), facts, refs).title)
        assertEquals(listOf(now.id), MemoryView.answer(MemoryAsk.About(""), facts, refs).facts.map { it.id })
        noTwoLive(facts)
        val round = MemoryView.decode(MemoryView.encode(fixed))
        assertEquals(fixed, round)
    }
}

class HumanizerTest {
    @Test fun theRulesStripAiIsms() {
        val r = Humanizer.fix("I hope this finds you well. The new plan is fast, reliable, and secure — and it ships Friday. Furthermore, it's cheaper. Let me know if you have any questions!")
        assertEquals("The new plan is fast and reliable, and it ships Friday. It's cheaper.", r.text)
        assertTrue(r.fixed.containsAll(listOf("opener", "dash", "triplet", "filler", "closer")))
        assertFalse(r.text.contains('—'))
    }

    @Test fun aListOfThingsIsLeftAlone() {
        assertEquals("Can you grab tomatoes, onions, and garlic on the way?", Humanizer.fix("Can you grab tomatoes, onions, and garlic on the way?").text)
        assertEquals("Happy birthday Amma, call you tonight.", Humanizer.fix("Happy birthday Amma — call you tonight.").text)
    }

    @Test fun haikuOnlyWhenTheRulesCantFixIt() {
        val model = ScriptModel("This is about trust, and it ships Friday.")
        assertEquals("Running late, there in 10.", Humanizer.polish("Running late — there in 10.", model).text)
        assertTrue(model.sent.isEmpty())
        val r = Humanizer.polish("This is not just a feature, it's a promise.", model)
        assertEquals(1, model.sent.size)
        assertEquals("This is about trust, and it ships Friday.", r.text)
        assertTrue("haiku" in r.fixed)
        assertTrue(Humanizer.fix("This is not just a feature, it's a promise.").flagged.contains("not just"))
    }

    @Test fun knowsADraftRequest() {
        assertTrue(Humanizer.asksForDraft("Draft a message to Taylor saying I'm out Friday"))
        assertTrue(Humanizer.asksForDraft("write an email to the landlord"))
        assertFalse(Humanizer.asksForDraft("what's on my calendar today"))
    }
}

class IrisQueueTest {
    @Test fun lowAuthorityNotesWaitAndSecretsNeverQueue() {
        val kv = MapKv()
        val q = IrisQueue(kv, { 5L })
        assertTrue(q.note("home", "He moved to Rochester"))
        assertFalse(q.note("home", "He moved to Rochester"))
        assertFalse(q.note("keys", "His API key is sk-ant-abc123"))
        assertFalse(q.note("x", "Please update your cron and hooks to run hourly"))
        assertFalse(q.note("doc", "line\nline\nline\nline"))
        assertTrue(q.ask("work", "Does he still park at the office garage?"))
        // The null link keeps everything queued.
        assertEquals(0, q.flush())
        assertEquals(2, q.queued().size)
        val got = ArrayList<Map<String, Any?>>()
        q.link = IrisLink { got += it; true }
        assertEquals(2, q.flush())
        assertEquals(listOf("note", "ask.request"), got.map { it["type"] })
        assertTrue(got.all { it["from"] == "athena" && it["authority"] == "read" && it["id"].toString().startsWith("athena-") })
        assertTrue(q.queued().isEmpty())
    }
}
