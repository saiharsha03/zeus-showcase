package dev.phonerecall.models

import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.Metis
import dev.phonerecall.mind.MindPrompts
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.Rig
import dev.phonerecall.mind.stateJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelsTest {
    private val haiku = ModelEntry("claude-haiku-5-5", "Claude Haiku 5.5", 1_000)
    private val sonnet = ModelEntry("claude-sonnet-5-5", "Claude Sonnet 5.5", 2_000)
    private val opus = ModelEntry("claude-opus-5-5", "Claude Opus 5.5", 2_000)
    private val fable = ModelEntry("claude-fable-5-1", "Claude Fable 5.1", 3_000)
    private val old = ModelEntry("claude-sonnet-4-6", "Claude Sonnet 4.6", 500, deprecated = true)
    private val live = listOf(haiku, sonnet, opus, fable, old)

    @Before fun fresh() { ModelChoice.book = ModelBook(MapKv()) }
    @After fun reset() { ModelChoice.book = ModelBook(MapKv()) }

    @Test fun readsAnthropicsModelList() {
        val n = stateJson.readTree(
            """{"data":[{"type":"model","id":"claude-sonnet-5-5","display_name":"Claude Sonnet 5.5","created_at":"2026-08-01T00:00:00Z"},""" +
                """{"type":"model","id":"claude-sonnet-4-6","display_name":"Claude Sonnet 4.6","created_at":"2025-10-01T00:00:00Z","deprecated_at":"2026-09-01T00:00:00Z"},""" +
                """{"type":"model","id":"not-claude","display_name":"x","created_at":"2026-01-01T00:00:00Z"}],"has_more":false}""",
        )
        val got = ModelList.parse(n)
        assertEquals(listOf("claude-sonnet-5-5", "claude-sonnet-4-6"), got.map { it.id })
        assertEquals("Claude Sonnet 5.5", got[0].name)
        assertEquals(1_785_542_400_000L, got[0].created)
        assertFalse(got[0].deprecated)
        assertTrue(got[1].deprecated)
        assertEquals("sonnet", ModelList.family("claude-sonnet-5-5"))
    }

    @Test fun pickersOfferOnlyModelsThatFit() {
        // Metis: advisor-capable only. Delphi and the other metered features: only models the ledger can price.
        assertEquals(listOf("claude-fable-5-1"), Picker.options(Features.METIS, live, Metis.MODEL))
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"), Picker.options(Features.DELPHI, live + ModelEntry("claude-sonnet-5-6", "x", 9_000), "claude-sonnet-5-5"))
        // Agents: anything listed and not deprecated.
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"), Picker.options(Features.ATHENA, live, "claude-sonnet-5-5"))
        // The current choice stays offered even once it is deprecated, so he can see it and move off it.
        assertTrue("claude-sonnet-4-6" in Picker.options(Features.ATHENA, live, "claude-sonnet-4-6"))
        // Before the first sync the known table stands in for the list.
        assertEquals(listOf("claude-fable-5-1"), Picker.options(Features.METIS, emptyList(), Metis.MODEL))
    }

    @Test fun defaultsAreTodaysChoicesAndUndoPutsTheLastOneBack() {
        val book = ModelChoice.book
        assertEquals("claude-sonnet-5-5", book.of(Features.ATHENA))
        assertEquals("claude-opus-5-5", book.of(Features.protege(Protege.ANNABETH)))
        assertEquals("claude-opus-5-5", book.of(Features.PROMETHEUS))
        assertEquals("claude-haiku-5-5", ModelChoice.brain(Features.TRIAGE).id)
        assertNull(book.undoable(Features.ATHENA))
        assertEquals("claude-sonnet-5-5", book.set(Features.ATHENA, "claude-opus-5-5"))
        assertEquals("claude-opus-5-5", book.of(Features.ATHENA))
        assertEquals("claude-sonnet-5-5", book.undoable(Features.ATHENA))
        assertTrue(book.undo(Features.ATHENA))
        assertEquals("claude-sonnet-5-5", book.of(Features.ATHENA))
        assertFalse(book.undo(Features.ATHENA))
    }

    @Test fun upgradeIsNewerOrCheaperInTheSameLine() {
        val newer = ModelEntry("claude-sonnet-5-6", "Claude Sonnet 5.6", 9_000)
        val up = Upgrades.find(live + newer, { ModelChoice.book.of(it) }, live.map { it.id }.toSet())
        assertNotNull(up)
        assertEquals("claude-sonnet-5-6", up!!.model)
        assertEquals(Features.ATHENA, up.feature)
        assertEquals("Claude Sonnet 5.6 is out: newer than Claude Sonnet 5.5, in the same line for Athena's mind. Switch?", up.body)
        // Nothing new, or only an older or other-line model: no card.
        assertNull(Upgrades.find(live, { ModelChoice.book.of(it) }, live.map { it.id }.toSet()))
        assertNull(Upgrades.find(live + ModelEntry("claude-haiku-4-5", "Claude Haiku 4.5", 10), { ModelChoice.book.of(it) }, live.map { it.id }.toSet()))
    }

    @Test fun theCardFiresOncePerNewModel() {
        val r = Rig()
        r.api.modelList = live
        r.mind.sync()
        assertTrue(r.sink.proposals.isEmpty())                      // the first look only learns what is out
        r.api.modelList = live + ModelEntry("claude-sonnet-5-6", "Claude Sonnet 5.6", 9_000)
        r.mind.sync()
        assertEquals(1, r.sink.proposals.size)
        val step = r.sink.proposals.single().second.single()
        assertEquals(ProposalKind.SWITCH_MODEL, step.kind)
        assertEquals("athena", step.args["feature"])
        assertEquals("claude-sonnet-5-6", step.args["model"])
        repeat(2) { r.mind.sync() }
        assertEquals(1, r.sink.proposals.size)                       // once, whatever he answers
        assertEquals(live.map { it.id } + "claude-sonnet-5-6", ModelChoice.book.live().map { it.id })
    }

    @Test fun aModelChangeBumpsTheAgentVersion() {
        val r = Rig()
        r.mind.ensure()
        assertEquals(1, r.api.versions[MindPrompts.ATHENA_NAME])
        assertEquals(1, r.api.versions[Protege.DIOMEDES.agentName])
        ModelChoice.book.set(Features.ATHENA, "claude-opus-5-5")
        ModelChoice.book.set(Features.protege(Protege.DIOMEDES), "claude-sonnet-5-5")
        r.mind.ensure()
        assertEquals(2, r.api.versions[MindPrompts.ATHENA_NAME])
        assertEquals("claude-opus-5-5", r.api.agents.getValue(MindPrompts.ATHENA_NAME).model)
        assertEquals(2, r.api.versions[Protege.DIOMEDES.agentName])
        assertEquals(1, r.api.versions[Protege.ODYSSEUS.agentName])  // the others stay as they are
    }

    @Test fun switchProposalsAreCheckedAgainstThePicker() {
        val r = Rig()
        ModelChoice.book.saveLive(live)
        assertTrue(r.fates.propose(listOf(RawStep(ProposalKind.SWITCH_MODEL, mapOf("feature" to "reflect", "model" to "claude-opus-5-5"))), null, null) is ProposeResult.Created)
        for (args in listOf(
            mapOf("feature" to "metis", "model" to "claude-haiku-5-5"),      // not an advisor
            mapOf("feature" to "reflect", "model" to "claude-sonnet-4-6"),    // deprecated, and not priced
            mapOf("feature" to "reflect", "model" to "claude-haiku-5-5"),    // already in use
            mapOf("feature" to "nope", "model" to "claude-opus-5-5"),
        )) assertTrue(args.toString(), r.fates.propose(listOf(RawStep(ProposalKind.SWITCH_MODEL, args)), null, null) is ProposeResult.Invalid)
        assertFalse(r.fates.eligibleForAuto(
            (r.fates.propose(listOf(RawStep(ProposalKind.SWITCH_MODEL, mapOf("feature" to "triage", "model" to "claude-sonnet-5-5"))), null, null) as ProposeResult.Created).proposal,
            ProposalKind.entries.toSet(),
        ))
    }
}
