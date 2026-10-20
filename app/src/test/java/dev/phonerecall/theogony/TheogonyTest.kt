package dev.phonerecall.theogony

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.list
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.stateJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TheogonyTest {
    private class FakeIris(var up: Boolean = true) : IrisLink {
        val sent = ArrayList<Pair<String, String>>()
        var answer: ((String) -> String?) = { null }
        override fun available() = up
        override fun send(text: String, clientId: String): Long { sent += text to clientId; return 41L + sent.size }
        override fun reply(messageId: Long): String? = answer(sent.last().first)
    }

    private fun source(label: String, vararg facts: RawFact) = object : PhoneSource {
        override val label = label
        override fun read() = facts.toList()
    }

    private val noPermission = object : PhoneSource {
        override val label = "Your calls"
        override fun read(): List<RawFact>? = null
    }

    private var now = 1_000_000L
    private val files = ArrayList<Map<String, String>>()
    private val turnedOn = ArrayList<String>()
    private val kv = MapKv()
    private val clean = Sanitizer(mapOf("Meera" to "family (mother)"))

    private fun engine(iris: IrisLink?, vararg phone: PhoneSource) = TheogonyEngine(
        kv, iris, phone.toList(), { files += it }, { k, _ -> turnedOn += k }, { clean }, { now }, { "ask01" },
    )

    /** What Iris would send, as the JSON object her reply tool carries. */
    private fun reply(id: String, sections: Map<String, List<String>>) =
        stateJson.writeValueAsString(mapOf("type" to "ask.reply", "id" to id, "sections" to sections))

    private val irisSections = mapOf(
        "work_week" to listOf("Office Tuesday to Thursday, remote Monday and Friday [db]", "Work hours 9 AM to 5 PM [email]"),
        "trips" to listOf("~Japan trip idea for the spring, not booked [memory]"),
        "spending" to listOf("Shops at Costco and Whole Foods [email]", "Card ending in 4242 for groceries [db]", "Rent is $1,234 a month [db]"),
        "people" to listOf("Meera lives in Pune [memory]", "Taylor is his manager [email]", "Alex: +91 99999 00001 [memory]"),
        "preferences" to listOf("Likes the morning briefing short and plain [memory]", "nothing known"),
        "must_know" to listOf("Waiting on a visa appointment [email]"),
        "milestones" to listOf("2024-05-20 | Leased the car [email]", "2025-03 | Moved to Buffalo [memory]"),
    )

    @Test fun requestEnvelopeHasTheExactShapeAndTheOffLimitsRule() {
        val iris = FakeIris()
        engine(iris).seed()
        val (text, clientId) = iris.sent.single()
        assertEquals("ask-ask01", clientId)
        val json = stateJson.readTree(text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1))
        assertEquals(listOf("type", "id", "from", "authority", "topic", "question", "want_format"), json.fieldNames().asSequence().toList())
        assertEquals("ask.request", json["type"].asText()); assertEquals("ask01", json["id"].asText())
        assertEquals("athena", json["from"].asText()); assertEquals("read", json["authority"].asText())
        assertEquals("seed", json["topic"].asText()); assertEquals("sections", json["want_format"].asText())
        val q = json["question"].asText()
        assertTrue(q.contains("OFF-LIMITS")); assertTrue(q.contains("data tiers"))
        assertTrue(q.contains("email history")); assertTrue(q.contains("owner.db")); assertTrue(q.contains("[email], [memory] or [db]"))
        assertTrue(q.contains("YYYY-MM-DD | one-line label")); assertTrue(q.contains("welcome email")); assertTrue(q.contains("who is always urgent"))
        for (s in Seed.SECTIONS) assertTrue(s.id, q.contains(s.id))
        assertFalse("the gateway would defuse it", Regex("(?i)FROM\\s+(OWNER|ATHENA|ZEUS)").containsMatchIn(text))
    }

    @Test fun seedLinesCarryNoDigitsCoordinatesJidsOrOffLimits() {
        val lines = listOf(
            "Card ending in 4242 for groceries" to null,
            "Account number 123456789 at the credit union" to null,
            "Rent is $1,234 a month" to null,
            "Talks to 919999900001@s.whatsapp.net daily" to null,
            "Lunch near 42.8864, -78.8784 most days" to null,
            "Visa appointment pending" to null,
            "Call Alex at 716-555-0142" to null,
            "Trip to Goa in December 2026" to "Trip to Goa in December 2026",
            "Meera lives in Pune" to "family (mother) lives in Pune",
            "Order 88812345 arrives Friday" to "Order arrives Friday",
        )
        for ((raw, want) in lines) {
            val got = SeedFilter.clean(clean, raw)
            assertEquals(raw, want, got)
            if (got != null) assertFalse(raw, Regex("(?<!\\d)(?!20\\d\\d)\\d{3,}").containsMatchIn(got))
        }
    }

    @Test fun irisReplyBecomesFactsAndFiles() {
        val iris = FakeIris().apply { answer = { reply("ask01", irisSections) } }
        val e = engine(iris, source("Reading your calendar", RawFact(SeedPath.PLANS, "Flight to Boston: Nov 7.", 0.8, "calendar")))
        e.seed()
        val f = e.facts()
        assertTrue(f.any { it.source == "iris" && it.text.startsWith("Office Tuesday") && it.path == SeedPath.ROUTINES })
        assertTrue(f.any { it.text == "family (mother) lives in Pune" && it.path == SeedPath.PEOPLE })
        assertTrue(f.any { it.source == "phone" && it.text.startsWith("Flight to Boston") })
        assertEquals(0.5, f.first { it.text.startsWith("Japan trip") }.confidence, 0.0)
        assertTrue(f.none { it.text.contains("4242") || it.text.contains("99999") || it.text.contains("visa", true) || it.text == "nothing known" })
        val last = files.last()
        assertTrue(last.getValue("/routines/theogony.md").contains("Office Tuesday to Thursday, remote Monday and Friday [iris, observed, sure]"))
        assertTrue(last.getValue("/plans/theogony.md").contains("Japan trip idea for the spring, not booked [iris, inferred, unsure]"))
        assertTrue(last.getValue("/routines/theogony.md").contains("never act on it as a fact"))
        assertEquals(Status.INFERRED, f.first { it.text.startsWith("Likes the morning") }.status)
        // Milestones: the documented one is confirmed, the remembered one waits for him.
        val ms = e.milestones()
        assertEquals(listOf("Leased the car"), ms.confirmed().map { it.label })
        assertEquals(listOf("1 year since you leased the car"), ms.on(java.time.LocalDate.of(2025, 5, 20)))
        assertTrue(last.getValue("/profile/milestones.md").contains("2024-05-20: Leased the car [iris, confirmed]"))
        assertTrue(last.getValue("/profile/milestones.md").contains("2025-03: Moved to Buffalo [iris, inferred]"))
        assertEquals("", last.getValue("/snapshot/theogony.md"))
        assertEquals(Phase.INTERVIEW, e.phase)
    }

    @Test fun replyForAnotherIdIsRefused() {
        assertNull(SeedAsk.parse(reply("someone-else", irisSections), "ask01"))
        assertNotNull(SeedAsk.parse(reply("ask01", irisSections), "ask01"))
        val plain = SeedAsk.parse("## Trips\nGoa in December\n## Routines\nGym on Saturdays", "ask01")!!
        assertEquals(listOf("Goa in December"), plain.sections["trips"])
        assertEquals(listOf("Gym on Saturdays"), plain.sections["routines"])
    }

    @Test fun irisTimeoutGivesPartialResultsAndALateAnswerStillMerges() {
        val iris = FakeIris()
        val e = engine(iris, source("Your app habits", RawFact(SeedPath.PREFERENCES, "Uses WhatsApp and Spotify the most.", 0.8, "apps")), noPermission)
        e.seed()
        assertEquals(Phase.ASKING, e.phase)
        assertFalse(e.proactiveReady(paired = true))
        assertEquals(StepStatus.SKIPPED, e.state().steps.first { it.label == "Your calls" }.status)
        now += Seed.IRIS_WAIT_MS
        e.poll()
        assertEquals(Phase.INTERVIEW, e.phase)
        assertTrue(e.proactiveReady(paired = true))
        assertTrue(e.state().irisPending)
        assertEquals(listOf("phone"), e.facts().map { it.source }.distinct())
        val before = e.state().total
        // She opens with what the phone told her, then the gaps.
        assertEquals(Interview.OPENING, e.state().question!!.id)
        assertEquals("wake", e.state(mapOf(Interview.OPENING to "Yes, all right")).question!!.id)
        iris.answer = { reply("ask01", irisSections) }
        e.poll()
        assertFalse(e.state().irisPending)
        assertTrue(e.facts().any { it.source == "iris" })
        // Iris covered work hours: that question is dropped from the queue still to come.
        assertTrue(e.state().total < before)
        assertTrue(e.state().steps.first().status == StepStatus.DONE)
    }

    @Test fun noIrisMeansNoWait() {
        val e = engine(FakeIris(up = false), source("Your closet", RawFact(SeedPath.PROFILE, "Closet is catalogued in Venus, mostly tops.", 0.8, "closet")))
        e.seed()
        assertEquals(Phase.INTERVIEW, e.phase)
        assertEquals(StepStatus.SKIPPED, e.state().steps.first().status)
    }

    @Test fun gapQuestionsSkipKnownFactsAndAskAboutUnsureOnes() {
        val facts = listOf(
            SeedFact(SeedPath.ROUTINES, "Gym on Saturday mornings", "iris", 0.8, "routines"),
            SeedFact(SeedPath.PREFERENCES, "Remind him about Diwali and Holi", "iris", 0.8, "preferences"),
            SeedFact(SeedPath.ROUTINES, "Usually picks up the phone around 7 AM on weekdays, so likely wakes then.", "phone", 0.5, "wake", Status.INFERRED),
            SeedFact(SeedPath.ROUTINES, "Goes to bed around 11 PM", "iris", 0.7, "routines", Status.INFERRED),
        )
        val q = Interview.questions(facts, emptySet())
        val ids = q.map { it.id }
        assertFalse("places" in ids); assertFalse("festivals" in ids)
        // An inferred guess is asked as "is this right?", not as the open question, and never counts as known.
        assertFalse("wake" in ids); assertFalse("sleep" in ids); assertTrue("auto_approve" in ids)
        assertEquals(2, ids.count { it.startsWith("check:") })
        assertTrue(q.size <= Interview.MAX)
        assertEquals(q, Interview.questions(facts, emptySet()))
        assertTrue("wake" in Interview.questions(facts.dropLast(2), emptySet()).map { it.id })
    }

    @Test fun interviewIsOneQuestionAtATimeAndResumable() {
        val e = engine(null)
        e.seed()
        val first = e.state().question!!
        assertEquals(1, listOfNotNull(e.state().question).size)
        e.answer(first.id, "7 AM")
        val second = e.state().question!!
        assertTrue(second.id != first.id)
        // A restart: a new engine on the same storage resumes at the same question.
        val again = engine(null)
        assertEquals(second.id, again.state().question!!.id)
        assertEquals(1, again.state().asked)
        assertTrue(again.facts().any { it.source == "you" && it.text == "Wakes up around 7 AM on workdays." })
        again.later()
        assertFalse(again.dueForInterview())
        now += 25 * 3_600_000L
        assertTrue(again.dueForInterview())
        assertEquals(second.id, again.state().question!!.id)
    }

    @Test fun autoApproveAnswersAreProposalsUntilConfirmed() {
        val e = engine(null)
        e.seed()
        while (e.state().question?.id != "auto_approve") e.skip(e.state().question!!.id)
        e.answer("auto_approve", "Reminders; Tasks")
        assertTrue(turnedOn.isEmpty())
        val p = e.proposals()
        assertEquals(listOf("add_reminder", "add_task"), p.map { it.kindWire })
        e.decide(p[0].id, true)
        e.decide(p[1].id, false)
        assertEquals(listOf("add_reminder"), turnedOn)
        assertEquals(listOf("confirmed", "declined"), e.proposals().map { it.status })
    }

    @Test fun refreshIsIdempotentAndKeepsAnswersAndForgets() {
        val iris = FakeIris().apply { answer = { reply("ask01", irisSections) } }
        val phone = source("Your app habits", RawFact(SeedPath.PREFERENCES, "Uses WhatsApp and Spotify the most.", 0.8, "apps"))
        val e = engine(iris, phone)
        e.seed()
        e.answer("wake", "7 AM")
        val gone = e.facts().first { it.text.startsWith("Shops at Costco") }
        e.forget(gone.key)
        val facts = e.facts().sortedBy { it.key }
        val file = files.last()
        e.resetForRefresh(); e.seed()
        e.resetForRefresh(); e.seed()
        assertEquals(facts, e.facts().sortedBy { it.key })
        assertEquals(file, files.last())
        assertTrue(e.facts().none { it.key == gone.key })
        assertFalse("wake" in Interview.questions(e.facts(), emptySet()).map { it.id })
        assertTrue(e.proactiveReady(paired = true))
    }

    @Test fun proactiveGate() {
        val e = engine(FakeIris())
        assertTrue(e.proactiveReady(paired = false))
        assertFalse(e.proactiveReady(paired = true))
        e.seed()
        assertFalse(e.proactiveReady(paired = true))
        e.skipWaiting()
        assertTrue(e.proactiveReady(paired = true))
    }

    // ---- the conversation ----

    /** Iris with one answer per message, as the gateway threads them. */
    private class ThreadedIris : IrisLink {
        val sent = ArrayList<String>()
        val replies = HashMap<Long, String>()
        override fun available() = true
        override fun send(text: String, clientId: String): Long { sent += text; return 100L + sent.size }
        override fun reply(messageId: Long): String? = replies[messageId]
    }

    private var ids = 0
    private fun counting(iris: IrisLink?) = TheogonyEngine(
        kv, iris, emptyList(), { files += it }, { k, _ -> turnedOn += k }, { clean }, { now }, { "a" + (++ids) },
    )

    private val apps = source("Your app habits", RawFact(SeedPath.PREFERENCES, "Uses WhatsApp and Spotify the most.", 0.8, "apps"))
    private val queueType = object : TypeReference<List<Question>>() {}

    @Test fun everyChoiceTypeSavesAndAdvances() {
        val e = engine(null)
        e.seed()
        var guard = 0
        while (true) {
            val q = e.state().question ?: break
            val before = e.state().asked
            when {
                q.bundle.isNotEmpty() -> e.confirmBundle(q.id, q.bundle.toSet())
                q.prefill -> e.answer(q.id, q.chips.first() + ", May 2024")
                q.multi -> e.answer(q.id, q.chips.take(2).joinToString("; "))
                else -> {
                    val chips = q.chips.filterNot { it.startsWith("I'll type") }
                    e.answer(q.id, if (chips.isEmpty()) "Planet Fitness" else chips.getOrElse(1) { chips[0] })
                }
            }
            assertEquals(q.id, before + 1, e.state().asked)
            assertTrue(q.id, e.state().question?.id != q.id)
            if (q.gap != null) assertTrue(q.id, e.facts().any { it.source == "you" && it.topic == q.gap && it.status == Status.CONFIRMED })
            assertTrue(guard++ < 30)
        }
        assertEquals(Phase.DONE, e.phase)
        // The reported one: 11 PM for bedtime is kept.
        assertTrue(e.facts().any { it.text == "Usually in bed around 11 pm." })
        assertTrue(e.milestones().confirmed().isNotEmpty())
        assertEquals(TheogonyEngine.DONE_TEXT, e.turns().last().text)
    }

    @Test fun aTapNeverWaitsForTheFiltersOrTheSave() {
        var cleaned = 0
        val e = TheogonyEngine(kv, null, emptyList(), { files += it }, { _, _ -> }, { cleaned++; clean }, { now }, { "x" })
        e.seed()
        val calls = cleaned
        val shown = e.state(mapOf("wake" to "7 AM"))
        assertEquals(calls, cleaned)
        assertEquals("sleep", shown.question!!.id)
        assertEquals(listOf("When do you usually wake up on workdays?", "7 AM", "And when do you usually go to bed?"), shown.turns.map { it.text })
        assertEquals(e.state().left - 1, shown.left)
    }

    @Test fun conversationOpensWithWhatSheKnowsAndBundlesConfirmations() {
        val iris = FakeIris().apply { answer = { reply("ask01", irisSections) } }
        val e = engine(iris, apps)
        e.seed()
        val open = e.state().question!!
        assertEquals(Interview.OPENING, open.id)
        assertTrue(open.bundle.size in 2..Interview.BUNDLE)
        val lines = open.bundle.map { k -> e.facts().first { it.key == k } }
        assertTrue(lines.none { it.status == Status.CONFIRMED || it.milestone })
        assertEquals(Interview.OPENING_TEXT, open.text)
        assertEquals(lines.map { it.text }, e.openingDraft())
        // Her own phrasing replaces the plain line; one with a name in it is refused.
        e.setOpening("I think you're in the office Tuesday to Thursday and shop at Costco. Right?")
        assertEquals("I think you're in the office Tuesday to Thursday and shop at Costco. Right?", e.state().question!!.text)
        assertNull(e.openingDraft())
        kv.put(TheogonyEngine.K_OPEN_TRIED, null); kv.put(TheogonyEngine.K_OPENING, null)
        e.setOpening("I think Meera lives in Pune. Right?")
        assertEquals(Interview.OPENING_TEXT, e.state().question!!.text)

        // Untick one: the rest are confirmed, the unticked one is gone for good.
        val dropped = open.bundle.last()
        e.confirmBundle(open.id, open.bundle.toSet() - dropped)
        for (k in open.bundle - dropped) assertEquals(Status.CONFIRMED, e.facts().first { it.key == k }.status)
        assertTrue(e.facts().none { it.key == dropped })
        val t = e.turns()
        assertEquals(Interview.OPENING_TEXT, t[t.size - 2].text)
        assertEquals(lines.map { it.text }, t[t.size - 2].items)
        assertEquals("${open.bundle.size - 1} of ${open.bundle.size} are right", t.last().text)
        assertEquals(ChatTurn.YOU, t.last().who)

        // Her guesses come later, five at a time, never one by one.
        val q = Interview.chat(Interview.questions(e.facts(), emptySet()), e.facts(), setOf(Interview.OPENING))
        assertTrue(q.none { it.id.startsWith("check:") })
        assertTrue(q.any { it.bundle.isNotEmpty() && it.id != Interview.OPENING })
        assertTrue(q.all { it.bundle.size <= Interview.BUNDLE })
    }

    @Test fun skipLaterAndFreeTextInTheChat() {
        val e = engine(null)
        e.seed()
        assertEquals("wake", e.state().question!!.id)
        e.say("wake", "skip")
        assertEquals("sleep", e.state().question!!.id)
        assertEquals(listOf("When do you usually wake up on workdays?", Interview.SKIP), e.turns().map { it.text })
        assertTrue(e.facts().none { it.topic == "wake" })
        // His own words answer the open question as a confirmed fact.
        e.say("sleep", "half past eleven")
        assertEquals(Status.CONFIRMED, e.facts().first { it.topic == "sleep" }.status)
        assertEquals("Usually in bed around half past eleven.", e.facts().first { it.topic == "sleep" }.text)
        assertEquals("half past eleven", e.turns().last().text)
        // Off limits: neither memory nor the chat keeps it.
        val q = e.state().question!!
        e.say(q.id, "After my therapist appointment")
        assertTrue(e.facts().none { it.text.contains("therapist", true) })
        assertTrue(e.turns().none { it.text.contains("therapist", true) })
        // "later" waits a day, at the same question.
        val next = e.state().question!!
        e.say(next.id, "later")
        assertFalse(e.dueForInterview())
        assertEquals(next.id, e.state().question!!.id)
        now += 25 * 3_600_000L
        assertTrue(e.dueForInterview())
    }

    @Test fun conversationResumesWhereHeLeft() {
        val e = engine(FakeIris().apply { answer = { reply("ask01", irisSections) } }, apps)
        e.seed()
        val open = e.state().question!!
        e.confirmBundle(open.id, open.bundle.toSet())
        val q = e.state().question!!
        e.answer(q.id, q.chips.first())
        val left = e.state().left
        val turns = e.state().turns
        // He leaves; a new engine on the same storage is at the same place, with the same conversation.
        val again = engine(null)
        assertEquals(turns, again.state().turns)
        assertEquals(left, again.state().left)
        assertEquals(e.state().question!!.id, again.state().question!!.id)
        assertTrue(left > 0)
    }

    @Test fun anOldInterviewIsPlannedAgainAsAConversation() {
        val e = engine(FakeIris().apply { answer = { reply("ask01", irisSections) } }, apps)
        e.seed()
        // As frozen before the conversation: one question at a time, no opening.
        kv.putList(TheogonyEngine.K_QUEUE, Interview.questions(e.facts(), emptySet()))
        kv.put(TheogonyEngine.K_CHAT_V, null)
        e.answer("wake", "7 AM")
        e.migrate()
        assertEquals(Interview.OPENING, e.state().question!!.id)
        assertTrue(e.facts().any { it.text == "Wakes up around 7 AM on workdays." })
        assertFalse(kv.list(TheogonyEngine.K_QUEUE, queueType).any { it.id == "wake" })
    }

    @Test fun askIrisMidConversation() {
        val iris = ThreadedIris()
        val e = counting(iris)
        e.seed()
        e.skipWaiting()
        assertEquals("wake", e.state().question!!.id)
        assertTrue(e.state().canAskIris)
        e.askIris("wake")
        // The ask is the shared envelope, with the seed's rules, and the conversation moves on.
        val ask = iris.sent.last()
        assertTrue(ask.contains("ask.request")); assertTrue(ask.contains("OFF-LIMITS")); assertTrue(ask.contains("When do you usually wake up on workdays?"))
        assertEquals("sleep", e.state().question!!.id)
        assertTrue(e.state().irisPending)
        // A documented answer settles it.
        iris.replies[100L + iris.sent.size] = reply("a2", mapOf("wake" to listOf("Alarm set for 6:45 AM on weekdays [email]")))
        e.poll()
        assertEquals(Status.OBSERVED, e.facts().first { it.topic == "wake" }.status)
        assertTrue(e.turns().last().text.startsWith("Iris found it: "))
        // A guess comes back to him as a bundle.
        e.askIris("sleep")
        iris.replies[100L + iris.sent.size] = reply("a3", mapOf("sleep" to listOf("~Usually asleep by midnight [memory]")))
        e.poll()
        assertTrue(e.turns().last().text.startsWith("Iris thinks: "))
        val guess = e.facts().first { it.topic == "sleep" }
        assertTrue(kv.list(TheogonyEngine.K_QUEUE, queueType).any { guess.key in it.bundle })
    }

    @Test fun confirmedAnswersReachOneiroiOnceAndLeaveWhenForgotten() {
        val inRows = ArrayList<String>()
        val rows = object : SeedRows {
            override fun confirmed(f: SeedFact) { inRows += f.text }
            override fun forgot(f: SeedFact) { inRows -= f.text }
        }
        val e = TheogonyEngine(kv, null, listOf(apps), { files += it }, { _, _ -> }, { clean }, { now }, { "x" }, rows = rows)
        e.seed()
        assertTrue(inRows.isEmpty())
        val open = e.state().question!!
        e.confirmBundle(open.id, open.bundle.toSet())
        assertEquals(listOf("Uses WhatsApp and Spotify the most."), inRows)
        e.answer("wake", "7 AM")
        e.resetForRefresh(); e.seed()
        assertEquals(listOf("Uses WhatsApp and Spotify the most.", "Wakes up around 7 AM on workdays."), inRows)
        e.forget(e.facts().first { it.topic == "wake" }.key)
        assertEquals(listOf("Uses WhatsApp and Spotify the most."), inRows)
    }
}
