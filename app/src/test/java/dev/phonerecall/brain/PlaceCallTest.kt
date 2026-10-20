package dev.phonerecall.brain

import dev.phonerecall.calls.CallRouting
import dev.phonerecall.calls.CallTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCallTest {
    private var target: CallTarget? = CallTarget(41, "Amma Home", video = true, voice = true, hasNumber = true, auto = CallRouting.VIDEO, country = "IN")
    private val base = FakeState()
    private val state = object : LocalState by base {
        override fun callTarget(who: String, contactId: Long?) = target
        override fun driving() = base.drive
    }
    private val exec = FakeExecutor()
    private val fates = Fates(MemoryStore(), state, exec, { _, _, _ -> })

    private fun propose(vararg args: Pair<String, String>) = fates.propose(listOf(RawStep(ProposalKind.PLACE_CALL, mapOf(*args))), null, null, "Rhythms")

    @Test fun indiaDefaultsToWhatsAppVideoAndKeepsHisWords() {
        val p = (propose("who" to "Amma") as ProposeResult.Created).proposal
        val s = p.steps.single()
        assertEquals(CallRouting.VIDEO, s.payload["via"])
        assertEquals("41", s.payload["contact_id"])
        // The title (what Delphi hears back) has his word, not the contact's saved name.
        assertEquals("WhatsApp video call Amma", p.title)
        assertEquals(listOf("Who" to "Amma Home", "How" to "WhatsApp video"), Validate.facts(s, state.zone))
        // A call is never auto-approved, even if every kind is on the list.
        assertFalse(fates.eligibleForAuto(p, ProposalKind.entries.toSet()))
        assertNull((propose("who" to "Amma") as ProposeResult.Created).decision)
        assertTrue(fates.confirm(p.id, fates.bind(p)) is Decision.Done)
        assertEquals(ProposalKind.PLACE_CALL, exec.ran.single().kind)
    }

    @Test fun fallsBackToWhatTheContactHas() {
        target = target!!.copy(video = false)
        assertEquals(CallRouting.VOICE, (propose("who" to "Amma", "via" to "video") as ProposeResult.Created).proposal.steps.single().payload["via"])
        target = target!!.copy(voice = false)
        assertEquals(CallRouting.PHONE, (propose("who" to "Amma", "via" to "whatsapp") as ProposeResult.Created).proposal.steps.single().payload["via"])
        target = CallTarget(7, "Alex", video = false, voice = false, hasNumber = true, auto = CallRouting.PHONE, country = "US")
        assertEquals("Call Alex", (propose("who" to "Alex") as ProposeResult.Created).proposal.title)
    }

    @Test fun noContactNoVideoWhileDriving() {
        base.drive = true
        assertTrue(propose("who" to "Amma", "via" to "video") is ProposeResult.Invalid)
        assertEquals(CallRouting.VOICE, (propose("who" to "Amma", "via" to "voice") as ProposeResult.Created).proposal.steps.single().payload["via"])
        target = null
        assertTrue(propose("who" to "Amma") is ProposeResult.Invalid)
    }

    @Test fun saysWhenTheyAreAsleep() {
        target = target!!.copy(there = "1:30 AM IST", awake = "usually up 6:30 AM–10:30 PM IST", asleep = true)
        val p = (propose("who" to "Amma") as ProposeResult.Created).proposal
        assertEquals("WhatsApp video call Amma (1:30 AM IST, usually asleep)", p.title)
        val facts = Validate.facts(p.steps.single(), state.zone)
        assertTrue("There" to "1:30 AM IST" in facts)
        assertTrue("Awake" to "Usually up 6:30 AM–10:30 PM IST; asleep now" in facts)
    }

    @Test fun bookmarkForIsAProposal() {
        val p = (fates.propose(listOf(RawStep(ProposalKind.BOOKMARK_FOR, mapOf("person" to "Amma", "note" to "the new job offer"))), null, null) as ProposeResult.Created).proposal
        assertEquals("Tell Amma: the new job offer", p.title)
        assertEquals(listOf("For" to "Amma", "Tell" to "the new job offer"), Validate.facts(p.steps.single(), state.zone))
        assertTrue(fates.propose(listOf(RawStep(ProposalKind.BOOKMARK_FOR, mapOf("person" to "Amma"))), null, null) is ProposeResult.Invalid)
    }
}
