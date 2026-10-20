package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class BirthdayTest {
    private fun name(text: String) = BirthdayCatcher.detect(text)?.name

    @Test fun englishWishes() {
        assertEquals("Alex", name("Happy birthday Alex!!"))
        assertEquals("Alex", name("HBD alex anna 🎂"))
        assertEquals("Alex", name("happy bday dear Alex, have a great year"))
        assertEquals("Sita", name("Many happy returns of the day Sita"))
        assertEquals("Alex", name("Alex, happy birthday!"))
        assertEquals("Alex", name("@Alex happy b'day"))
        assertEquals("Riley", name("Happiest birthday to our Riley"))
        assertNull(name("Happy birthday to you"))               // a wish with no name
    }

    @Test fun teluguAndHindiTransliterations() {
        assertEquals("Alex", name("Alex ki janmadina subhakankshalu"))
        assertEquals("Alex", name("Puttina roju shubhakankshalu Alex garu"))
        assertEquals("Alex", name("puttinaroju subhakankshalu alex"))
        assertEquals("Riley", name("Janamdin mubarak ho Riley"))
        assertEquals("Riley", name("Riley ko janmdin ki shubhkamnaye"))
        assertEquals("Arjun", name("Arjun saalgirah mubarak"))
        assertTrue(BirthdayCatcher.detect("పుట్టినరోజు శుభాకాంక్షలు రవి") != null)
        assertTrue(BirthdayCatcher.detect("जन्मदिन मुबारक प्रिया") != null)
    }

    @Test fun notWishes() {
        assertNull(BirthdayCatcher.detect("The birthday party is at 6, bring snacks"))
        assertNull(BirthdayCatcher.detect("hbdx is the code name"))
        assertNull(BirthdayCatcher.detect("happy to help with the move"))
    }

    @Test fun theGroupsRepeatedNameWins() {
        val wishes = listOf(Wish("hbd", "Alex"), Wish("happy birthday", null), Wish("happy birthday", "Alex"), Wish("hbd", "Sita"))
        assertEquals("Alex", BirthdayCatcher.resolve(wishes, "Family"))
        assertEquals("Alex", BirthdayCatcher.resolve(listOf(Wish("happy birthday", null)), "Alex's birthday 🎉"))
        assertNull(BirthdayCatcher.resolve(listOf(Wish("happy birthday", null)), "Family"))
        // A wish to him is not someone else's birthday.
        assertNull(BirthdayCatcher.resolve(listOf(Wish("hbd", "Owner")), "Family"))
    }

    @Test fun notABirthdayIsRemembered() {
        val neg = listOf(BirthdayNegative("t1", People.keyOf("Alex"), "hbd"))
        assertTrue(BirthdayCatcher.blocked(neg, "t1", People.keyOf("Alex"), "happy birthday"))
        assertFalse(BirthdayCatcher.blocked(neg, "t2", People.keyOf("Alex"), "hbd"))
        // A phrase rejected twice in a group stops counting there.
        val twice = listOf(BirthdayNegative("t1", People.keyOf("A"), "hbd"), BirthdayNegative("t1", People.keyOf("B"), "hbd"))
        assertTrue(BirthdayCatcher.blocked(twice, "t1", People.keyOf("C"), "hbd"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BirthdayFlowTest {
    private fun EngineRig.wish(text: String, key: String) =
        engine.ingest(note("com.whatsapp", "Family: Uncle", text, key = key, group = true, conversation = "Family", sender = "Uncle"))

    @Test fun aWishInAGroupProposesTheBirthdayOnce() {
        val rig = EngineRig()
        rig.wish("Happy birthday Alex!!", "a")
        rig.wish("HBD alex 🎂", "b")
        val p = rig.pending().single()
        val s = p.steps.single()
        assertEquals(ProposalKind.ADD_BIRTHDAY, s.kind)
        assertEquals(3, p.tier)
        assertEquals("Alex", s.payload["name"])
        assertEquals("10-19", s.payload["date"])
        assertEquals("the “Family” group", s.payload["source"])
        assertNull(s.needs)                                   // Google Calendar is allowed: nothing to pick
        assertEquals("Save Alex's birthday (today) in Google Calendar?", p.title)
        // Group senders are not people; nothing about the uncle is learned.
        assertTrue(rig.store.people().isEmpty())
    }

    @Test fun knownBirthdaysAreLeftAlone() {
        val rig = EngineRig()
        rig.effects.known += "alex"
        rig.wish("Happy birthday Alex", "a")
        assertTrue(rig.pending().isEmpty())
    }

    @Test fun notABirthdayTeachesIt() {
        val rig = EngineRig()
        rig.wish("Happy birthday Alex", "a")
        rig.engine.notBirthday(rig.pending().single())
        assertTrue(rig.pending().isEmpty())
        assertEquals(1, rig.store.negatives().size)
        rig.now += 400L * 86_400_000L
        rig.wish("Happy birthday Alex", "b")
        assertTrue(rig.pending().isEmpty())
    }
}
