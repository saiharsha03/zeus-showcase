package dev.phonerecall.theogony

import dev.phonerecall.brain.Relation
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.Sanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PeopleSeedTest {
    @Test fun relationTypesAndCustomLabels() {
        assertEquals(Relation.MOTHER, PeopleSeed.relationFromType(8))
        assertEquals(Relation.FATHER, PeopleSeed.relationFromType(5))
        assertEquals(Relation.SISTER, PeopleSeed.relationFromType(13))
        assertEquals(Relation.MANAGER, PeopleSeed.relationFromType(7))
        assertEquals(Relation.PARTNER, PeopleSeed.relationFromType(14))
        assertEquals(Relation.MOTHER, PeopleSeed.relationFromType(0, "Amma"))
        assertNull(PeopleSeed.relationFromType(0, "Gym buddy"))
    }

    @Test fun nicknames() {
        assertEquals(Relation.MOTHER, PeopleSeed.nickname("Amma"))
        assertEquals(Relation.FATHER, PeopleSeed.nickname("Nanna India"))
        assertEquals(Relation.FATHER, PeopleSeed.nickname("ICE Dad"))
        assertEquals(Relation.SISTER, PeopleSeed.nickname("Akka"))
        assertEquals(Relation.BROTHER, PeopleSeed.nickname("Anna"))
        assertEquals(Relation.RELATIVE, PeopleSeed.nickname("Bava"))
        assertEquals(Relation.RELATIVE, PeopleSeed.nickname("Chinna"))
        assertNull(PeopleSeed.nickname("Alex Anna"))
        assertNull(PeopleSeed.nickname("Mama"))
    }

    @Test fun indiaAndEmergencySignals() {
        assertTrue(PeopleSeed.india(listOf("+91 99999 00001")))
        assertTrue(PeopleSeed.india(listOf("919999900001@s.whatsapp.net")))
        assertFalse(PeopleSeed.india(listOf("+1 716 555 0142", "7165550142")))
        assertTrue(PeopleSeed.emergency(ContactInfo("ICE Dad")))
        assertTrue(PeopleSeed.emergency(ContactInfo("Jordan", groups = listOf("Emergency"))))
        assertFalse(PeopleSeed.emergency(ContactInfo("Iceland trip")))
    }

    private val input = PeopleInput(
        contacts = listOf(
            ContactInfo("Amma", starred = true, phones = listOf("+91 99999 00001")),
            ContactInfo("Nanna", phones = listOf("919999911111@s.whatsapp.net")),
            ContactInfo("Alex Kumar", phones = listOf("+1 716 555 0199")),
            ContactInfo("Jordan", starred = true),
            ContactInfo("Pizza Place"),
            ContactInfo("Taylor", groups = listOf("Work")),
        ),
        relations = listOf("Taylor" to Relation.MANAGER),
        signals = listOf(
            ChatSignal("Amma", messages = 40, calls = 6),
            ChatSignal("Alex Kumar", messages = 30, calls = 2),
            ChatSignal("Jordan", messages = 4),
            // Replies within a minute, rarely writes: fast replies are not importance.
            ChatSignal("Pizza Place", messages = 3, replyMinutes = 1.0),
        ),
        busyGroups = 2,
    )

    @Test fun priorityIsInferredFromCountsNotReplySpeed() {
        val r = PeopleSeed.build(input, emptyList()).associateBy { it.name }
        assertEquals(PriorityTier.FAMILY, r.getValue("Amma").tier)
        assertEquals(Status.INFERRED, r.getValue("Amma").relationStatus)
        assertTrue(r.getValue("Amma").india && r.getValue("Nanna").india && !r.getValue("Alex Kumar").india)
        assertEquals(PriorityTier.WORK, r.getValue("Taylor").tier)
        assertEquals(Status.OBSERVED, r.getValue("Taylor").relationStatus)
        assertEquals(PriorityTier.INNER_CIRCLE, r.getValue("Alex Kumar").tier)
        assertEquals(Status.INFERRED, r.getValue("Alex Kumar").tierStatus)
        assertEquals(PriorityTier.INNER_CIRCLE, r.getValue("Jordan").tier)
        assertEquals(PriorityTier.OTHERS, r.getValue("Pizza Place").tier)
        // Inferred never answers for other features.
        val p = PeoplePriority(r.values.toList())
        assertTrue(p.innerCircle().isEmpty())
        assertTrue(p.family().isEmpty())
        assertNull(p.tierOf("Alex Kumar"))
        assertEquals(PriorityTier.WORK, p.tierOf("Taylor"))
        assertTrue(p.suggestions().any { it.name == "Alex Kumar" })
    }

    @Test fun interviewConfirmsOrDeletesHypotheses() {
        var r = PeopleSeed.build(input, emptyList())
        val qs = PeopleSeed.questions(r, emptySet()).map { it.id }
        assertEquals(listOf(PeopleSeed.FAMILY, PeopleSeed.ALWAYS, PeopleSeed.EMERGENCY, PeopleSeed.TOP, PeopleSeed.QUIET), qs)
        r = PeopleSeed.apply(PeopleSeed.FAMILY, "Amma: mother", r)
        assertEquals(Status.CONFIRMED, r.first { it.name == "Amma" }.relationStatus)
        assertNull(r.first { it.name == "Nanna" }.relation)
        r = PeopleSeed.apply(PeopleSeed.TOP, "Alex Kumar", r)
        assertEquals(Status.CONFIRMED, r.first { it.name == "Alex Kumar" }.tierStatus)
        assertEquals(PriorityTier.OTHERS, r.first { it.name == "Jordan" }.tier)
        r = PeopleSeed.apply(PeopleSeed.ALWAYS, "Amma; Alex Kumar", r)
        r = PeopleSeed.apply(PeopleSeed.EMERGENCY, "Uncle Mohan", r)
        r = PeopleSeed.apply(PeopleSeed.QUIET, "Pizza Place", r)
        val p = PeoplePriority(r)
        assertEquals(listOf("Alex Kumar"), p.innerCircle())
        assertEquals(listOf("Amma"), p.family())
        assertEquals(setOf("Amma", "Alex Kumar"), p.alwaysThrough().toSet())
        assertEquals(listOf("Uncle Mohan"), p.emergency())
        assertEquals(listOf("Pizza Place"), p.quietUnlessUrgent())
        // A refresh keeps every answer.
        val again = PeoplePriority(PeopleSeed.build(input, r))
        assertEquals(p.innerCircle(), again.innerCircle()); assertEquals(p.emergency(), again.emergency()); assertEquals(p.alwaysThrough().toSet(), again.alwaysThrough().toSet())
        assertTrue(PeopleSeed.questions(r, emptySet()).isEmpty())
    }

    @Test fun noNamesNumbersOrJidsReachMemory() {
        val files = ArrayList<Map<String, String>>()
        val kv = MapKv()
        val e = TheogonyEngine(kv, null, emptyList(), { files += it }, { _, _ -> }, { Sanitizer() }, { 1L }, { "x" }, people = { input })
        e.seed()
        val facts = e.facts().filter { it.source == "people" }
        assertTrue(facts.any { it.text.startsWith("Family: family (mother)") && it.status == Status.INFERRED })
        assertTrue(facts.any { it.text.startsWith("Inner circle:") && it.status == Status.INFERRED })
        assertTrue(facts.any { it.text == "Work: manager." && it.status == Status.OBSERVED })
        assertTrue(facts.any { it.text == "Has two busy WhatsApp group chats." })
        val all = files.flatMap { it.values } + e.facts().map { it.text }
        for (t in all) {
            for (name in listOf("Amma", "Nanna", "Alex", "Jordan", "Pizza", "Taylor")) assertFalse("$name in $t", Regex("\\b$name\\b").containsMatchIn(t))
            assertFalse(t, Regex("(?<!\\d)(?!20\\d\\d)\\d{3,}").containsMatchIn(t))
            assertFalse(t, t.contains("whatsapp.net") || t.contains("+91"))
        }
        // Confirm on the inferred family line confirms the roster behind it.
        e.confirm(e.facts().first { it.topic == "people_family" && it.status == Status.INFERRED }.key)
        assertEquals(listOf("Amma", "Nanna"), e.peoplePriority().family().sorted())
        assertTrue(e.facts().any { it.topic == "people_family" && it.status == Status.CONFIRMED })
        // Forget on the inferred inner-circle line drops the hypothesis.
        e.forget(e.facts().first { it.topic == "people_inner" }.key)
        assertTrue(e.roster().none { it.tier == PriorityTier.INNER_CIRCLE })
    }

    @Test fun milestoneParsingAndAnniversaries() {
        assertEquals(listOf("Got the car" to "2024-05", "Moved to Buffalo" to "2025-03-10"), MilestoneText.parse("Got the car, May 2024; Moved to Buffalo, 2025-03-10"))
        assertEquals(listOf("Leased the car" to "2024-05-20"), MilestoneText.parse("2024-05-20 | Leased the car"))
        assertEquals(listOf("Started at Acme" to "2023-04-03"), MilestoneText.parse("Started at Acme April 3, 2023"))
        val car = Milestone("Got the car", "2024-05-20")
        assertEquals("1 year since you got the car", MilestoneText.anniversary(car, LocalDate.of(2025, 5, 20)))
        assertEquals("2 years since you got the car", MilestoneText.anniversary(car, LocalDate.of(2026, 5, 20)))
        assertNull(MilestoneText.anniversary(car, LocalDate.of(2024, 5, 20)))
        assertNull(MilestoneText.anniversary(car, LocalDate.of(2025, 5, 21)))
        assertNull(MilestoneText.anniversary(Milestone("Moved", "2024-08"), LocalDate.of(2025, 8, 1)))
        val ms = Milestones(listOf(car, Milestone("Graduated", "2022-05-14", Status.INFERRED)))
        assertEquals(listOf("Got the car"), ms.confirmed().map { it.label })
        assertTrue(ms.on(LocalDate.of(2025, 5, 14)).isEmpty())
        assertFalse(ms.notify)
    }

    @Test fun milestonesAreAskedOnlyWhenIrisDoesNotKnowThem() {
        val car = Seed.milestoneFact("Leased the car", "2024-05-20", "iris", Status.CONFIRMED, 0.9)
        assertEquals(listOf("Moved to Buffalo", "Started the job", "Graduated"), Interview.missingMilestones(listOf(car)))
        val q = Interview.questions(listOf(car), emptySet()).first { it.id == Interview.MILESTONE_Q }
        assertFalse(q.chips.contains("Got the car"))
        val e = TheogonyEngine(MapKv(), null, emptyList(), { }, { _, _ -> }, { Sanitizer() }, { 1L }, { "x" })
        e.seed()
        while (e.state().question?.id != Interview.MILESTONE_Q) e.skip(e.state().question!!.id)
        e.answer(Interview.MILESTONE_Q, "Got the car, May 20 2024; Moved to Buffalo, Mar 2025")
        assertEquals(listOf("Got the car", "Moved to Buffalo"), e.milestones().confirmed().map { it.label })
        e.setMilestoneNotify(true)
        assertTrue(e.milestones().notify)
    }
}
