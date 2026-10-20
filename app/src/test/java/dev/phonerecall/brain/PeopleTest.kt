package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class TierMathTest {
    private val min = 60_000L

    private fun answered(vararg minutes: Long): ResponseStats =
        minutes.fold(ResponseStats()) { s, m -> TierMath.observe(s, Response.ENGAGED, m * min, 0) }

    @Test fun ewmaWeightsRecentAnswers() {
        val s = answered(10, 20)
        // First sample seeds it; then 0.3 * 20 + 0.7 * 10.
        assertEquals(13.0, s.ewmaMinutes!!, 1e-9)
        assertEquals(2, s.n)
        assertEquals(2, s.engaged)
        val capped = TierMath.observe(ResponseStats(), Response.ENGAGED, 5 * 24 * 60 * min, 0)
        assertEquals(TierMath.CAP_MINUTES, capped.ewmaMinutes!!, 1e-9)
    }

    @Test fun dismissalsCountIgnoredDoNot() {
        val s = TierMath.observe(answered(5), Response.DISMISSED, null, 0)
        assertEquals(2, s.n)
        assertEquals(1, s.engaged)
        assertEquals(5.0, s.ewmaMinutes!!, 1e-9)
        assertEquals(s, TierMath.observe(s, Response.IGNORED, 1000, 0))
        // Laplace: (1 + 1) / (2 + 2).
        assertEquals(0.5, TierMath.engagement(s), 1e-9)
    }

    @Test fun tiersFromLatencyAndEngagement() {
        assertNull(TierMath.learned(answered(1, 1, 1, 1)))                       // four samples: not yet
        assertEquals(Tier.REPLY_NOW, TierMath.learned(answered(2, 4, 3, 6, 5)))
        assertEquals(Tier.TODAY, TierMath.learned(answered(90, 120, 60, 200, 45)))
        assertEquals(Tier.WHENEVER, TierMath.learned(answered(900, 1200, 800, 1000, 700)))
        val ignored = (1..8).fold(ResponseStats()) { s, _ -> TierMath.observe(s, Response.DISMISSED, null, 0) }
        assertEquals(Tier.MUTED, TierMath.learned(ignored))
        // Fast but rarely answered is not reply-now.
        var s = answered(2, 2)
        repeat(6) { s = TierMath.observe(s, Response.DISMISSED, null, 0) }
        assertEquals(Tier.WHENEVER, TierMath.learned(s))
    }

    @Test fun priorsPinsAndTheFamilyFloor() {
        assertEquals(Tier.REPLY_NOW, TierMath.tier(null, Relation.MOTHER, ResponseStats(), confirmed = false))
        assertEquals(Tier.REPLY_NOW, TierMath.tier(null, Relation.MANAGER, ResponseStats(), confirmed = true))
        assertEquals(Tier.TODAY, TierMath.tier(null, Relation.ROOMMATE, ResponseStats(), confirmed = true))
        assertEquals(Tier.WHENEVER, TierMath.tier(null, null, ResponseStats(), confirmed = false))
        val slow = answered(900, 1200, 800, 1000, 700)
        // A confirmed parent never learns below Today; an unconfirmed guess does.
        assertEquals(Tier.TODAY, TierMath.tier(null, Relation.MOTHER, slow, confirmed = true))
        assertEquals(Tier.WHENEVER, TierMath.tier(null, Relation.MOTHER, slow, confirmed = false))
        // His pin always wins.
        assertEquals(Tier.MUTED, TierMath.tier(Tier.MUTED, Relation.MOTHER, answered(1, 1, 1, 1, 1), confirmed = true))
    }

    @Test fun addressTermsFromSavedNames() {
        assertEquals(Relation.MOTHER to "Amma", AddressTerms.relationOf("Amma"))
        assertEquals(Relation.FATHER to "Nanna", AddressTerms.relationOf("Nanna India"))
        assertEquals(Relation.SISTER to "Akka", AddressTerms.relationOf("Akka"))
        assertEquals(Relation.BROTHER to "Anna", AddressTerms.relationOf("Anna"))
        assertEquals(Relation.MOTHER, AddressTerms.relationOf("Mom Cell")?.first)
        assertEquals(Relation.FATHER, AddressTerms.relationOf("Dad")?.first)
        assertNull(AddressTerms.relationOf("Alex Anna"))   // an honorific, not a brother
        assertNull(AddressTerms.relationOf("Mama"))        // uncle in Telugu, mother in English
        assertNull(AddressTerms.relationOf("Taylor"))
    }

    @Test fun theBrainSeesLabelsNotNames() {
        assertEquals("family (mother)", Person("k", "Amma", Relation.MOTHER).label)
        assertEquals("manager", Person("k", "Taylor", Relation.MANAGER).label)
        assertEquals("work contact", Person("k", "Taylor B", work = true).label)
        assertEquals("a contact", Person("k", "Alex").label)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PeopleLearningTest {
    @Test fun removalsTeachTheTierAndAskAboutAmmaOnce() {
        val rig = EngineRig()
        rig.engine.ingest(rig.note("com.whatsapp", "Amma", "Did you eat?", key = "w1"))
        val ask = rig.pending().single()
        assertEquals(ProposalKind.SET_RELATION, ask.steps.single().kind)
        assertEquals("Is “Amma” your mother?", ask.title)
        val key = People.keyOf("Amma")
        assertTrue(rig.store.person(key)!!.asked)

        // Five quick answers, each about two minutes after the first unanswered notification.
        repeat(5) { i ->
            rig.engine.ingest(rig.note("com.whatsapp", "Amma", "message $i", key = "m$i"))
            rig.now += 2 * 60_000L
            rig.engine.removed("m$i", Response.ENGAGED, rig.now)
            rig.now += 60 * 60_000L
        }
        val p = rig.store.person(key)!!
        assertEquals(5, p.stats.n)
        assertEquals(2.0, p.stats.ewmaMinutes!!, 1e-9)
        assertEquals(Tier.REPLY_NOW, p.tier)
        // Still one question, never repeated.
        assertEquals(1, rig.pending().count { it.steps.single().kind == ProposalKind.SET_RELATION })
    }
}
