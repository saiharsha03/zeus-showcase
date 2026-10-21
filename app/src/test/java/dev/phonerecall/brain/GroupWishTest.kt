package dev.phonerecall.brain

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** His wish, worded his way for each group, and the relation word for who it's for. */
class GroupWishRulesTest {
    private fun p(name: String, r: Relation?, term: String? = null) = Person(People.keyOf(name), name, r, term = term, confirmed = true, asked = true)

    @Test fun aWishBecomesAWordingForThatGroup() {
        assertEquals("Happy birthday {name} anna 🎂🎂", GroupWish.template("Happy birthday Alex anna 🎂🎂", "Alex"))
        assertEquals("Many happy returns of the day {name}!", GroupWish.template("Many happy returns of the day alex!\nsee you soon", "Alex"))
        // "Rohan" isn't "Alex".
        assertEquals("HBD Rohan", GroupWish.template("HBD Rohan", "Alex"))
    }

    @Test fun theRelationWordHeUses() {
        assertEquals("Amma", GroupWish.address("Meera", p("Meera", Relation.MOTHER, "Amma")))
        assertEquals("Nanna", GroupWish.address("Kumar", p("Kumar", Relation.FATHER, "Nanna")))
        assertEquals("Alex anna", GroupWish.address("Alex", p("Alex", Relation.BROTHER, "anna")))
        assertEquals("Riley akka", GroupWish.address("Riley", p("Riley", Relation.SISTER, "akka")))
        assertEquals("Sam", GroupWish.address("Sam", p("Sam", Relation.FRIEND)))
        assertEquals("Sam", GroupWish.address("Sam", null))
    }

    @Test fun hisWordingFirstThenTheDefaultWithHisEmoji() {
        assertEquals("Happy birthday Alex anna 🎂", GroupWish.wording(mapOf("Happy birthday {name} anna 🎂" to 3, "HBD {name}" to 1), "Alex", "🎉"))
        assertEquals("Happy birthday Amma ❤️", GroupWish.wording(emptyMap(), "Amma", "❤️"))
        assertEquals("Happy birthday Sam 🎉", GroupWish.wording(emptyMap(), "Sam", null))
        // A wording without an emoji gets his usual one.
        assertEquals("Many happy returns Sam 🥳", GroupWish.wording(mapOf("Many happy returns {name}" to 2), "Sam", "🥳"))
    }
}

class SplitwiseRulesTest {
    @Test fun owedLentSettled() {
        assertEquals(Splitwise.Line(Splitwise.Kind.OWE, "$12.50"), Splitwise.read("Alex added \"Dinner\" in \"Trip\". You owe $12.50."))
        assertEquals(Splitwise.Line(Splitwise.Kind.OWED, "₹1,200"), Splitwise.read("You get back ₹1,200 from Riley"))
        assertEquals(Splitwise.Kind.SETTLED, Splitwise.read("Sam recorded a payment of $20 to you").kind)
        assertEquals("You owe $12.50", Splitwise.title(Splitwise.read("You owe $12.50")))
        assertEquals("You get back ₹1,200", Splitwise.title(Splitwise.read("You get back ₹1,200")))
        assertEquals("Splitwise update", Splitwise.title(Splitwise.read("Alex added a photo")))
    }

    @Test fun buttonsOnlyOpenTheApp() {
        val n = Incoming("k", Splitwise.PKG, "Splitwise", "Splitwise", "Alex added \"Dinner\". You owe $12.50.", 0L)
        assertEquals(
            listOf("Open Splitwise" to Smart.Launch(Splitwise.PKG), "Settle up" to Smart.Launch(Splitwise.PKG)),
            SmartActions.of(n, AppKinds.of(n.pkg, n.app), null, null, emptyList()),
        )
        val added = n.copy(text = "Alex added a photo to Trip")
        assertEquals(listOf("Open Splitwise"), SmartActions.of(added, AppKind.OTHER, null, null, emptyList()).map { it.first })
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GroupWishAndroidTest {
    private val app: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() { LiveActions.clear(); GroupWish.clear(); Agency.clio = { _, _ -> } }
    @After fun tearDown() { LiveActions.clear(); GroupWish.clear() }

    @Test fun hisWordsCountMoreThanFamilysAndHisEmojiIsLearned() {
        GroupWish.learn(app, "fam", "HBD Alex", "Alex", his = false)
        GroupWish.learn(app, "fam", "HBD Alex", "Alex", his = false)
        GroupWish.learn(app, "fam", "Happy birthday Alex anna 🎂", "Alex", his = true)
        assertEquals(mapOf("HBD {name}" to 2, "Happy birthday {name} anna 🎂" to 3), GroupWish.templates(app, "fam"))
        assertEquals("🎂", GroupWish.emoji(app))
        // Another group starts fresh.
        assertTrue(GroupWish.templates(app, "office").isEmpty())
        assertEquals("Happy birthday Sam anna 🎂", GroupWish.wording(GroupWish.templates(app, "fam"), "Sam", GroupWish.emoji(app)))
    }

    @Test fun theWishIsTheCardsDraftAndWithoutAReplyItCopiesAndOpens() {
        LiveActions.put("0|com.whatsapp|3|null|1", "WhatsApp", listOf(LiveAction("Mark as read")), 0L)
        LiveActions.link("wish:fam", "0|com.whatsapp|3|null|1")
        Agency.offerWish(app, "fam", "Alex", "0|com.whatsapp|3|null|1", null)
        assertEquals("Happy birthday Alex 🎉", LiveActions.draft("wish:fam"))
        val first = LiveActions.forNote("0|com.whatsapp|3|null|1").first()
        assertEquals(Smart.CopyOpen("Happy birthday Alex 🎉", "com.whatsapp"), first.smart)
    }

    @Test fun sendingTheWishMarksItAndLearnsHisWording() {
        LiveActions.put("n", "WhatsApp", listOf(LiveAction("Reply")), 0L)
        LiveActions.link("wish:fam", "n")
        GroupWish.offer("fam", "Alex", "Happy birthday Alex 🎉")
        GroupWish.sent(app, "n", "Happy bday Alex anna 🥳")
        assertEquals(mapOf("Happy bday {name} anna 🥳" to 3), GroupWish.templates(app, "fam"))
        assertNull(GroupWish.offered("fam"))
        // Not a wish card: nothing learned.
        LiveActions.link("msg:alex", "m")
        GroupWish.sent(app, "m", "ok")
        assertFalse(GroupWish.templates(app, "fam").containsKey("ok"))
    }

    @Test fun aGroupWishingSomeoneMakesOneCardOnTheNewestNotification() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note("com.whatsapp", "Family: Mom", "Happy birthday Alex!", key = "w1", group = true, conversation = "Family", sender = "Mom"))
        rig.now += 60_000L
        rig.engine.ingest(rig.note("com.whatsapp", "Family: Dad", "Happy birthday Alex 🎂", key = "w2", group = true, conversation = "Family", sender = "Dad"))
        val wishes = rig.store.cards(rig.now).filter { it.key.startsWith("wish:") }
        assertEquals("Wish Alex happy birthday", wishes.single().title)
        assertEquals("w2", rig.effects.links.getValue(wishes.single().key))
        assertEquals(listOf("Alex", "Alex"), rig.effects.wishes)
    }

    @Test fun aFamilyWishIsLearnedOthersArent() {
        val rig = EngineRig()
        rig.store.savePerson(Person(People.keyOf("Riley"), "Riley", Relation.SISTER, confirmed = true, asked = true))
        rig.engine.ingest(rig.note("com.whatsapp", "Family: Riley", "Happy birthday Alex anna 🥳", key = "w1", group = true, conversation = "Family", sender = "Riley"))
        rig.engine.ingest(rig.note("com.whatsapp", "Family: Sam", "hbd alex", key = "w2", group = true, conversation = "Family", sender = "Sam"))
        assertEquals(listOf("Happy birthday Alex anna 🥳"), rig.effects.learned)
    }

    @Test fun aSplitwiseNotificationMakesItsCard() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note(Splitwise.PKG, "Splitwise", "Alex added \"Dinner\" in \"Trip\". You owe $12.50.", key = "s1"))
        val card = rig.store.cards(rig.now).single { it.key == "splitwise" }
        assertEquals("You owe $12.50", card.title)
        assertEquals("s1", rig.effects.links["splitwise"])
    }
}
