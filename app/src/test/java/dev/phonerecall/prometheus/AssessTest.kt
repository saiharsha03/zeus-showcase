package dev.phonerecall.prometheus

import dev.phonerecall.brain.Check
import dev.phonerecall.brain.ExecResult
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.Step
import dev.phonerecall.brain.StepExecutor
import dev.phonerecall.brain.Validate
import dev.phonerecall.brain.confirmNow
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.MindEvent
import dev.phonerecall.mind.Rig
import dev.phonerecall.mind.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessTest {
    private val now = 1_792_000_000_000L
    private val a = Assessment(id = "a0123456789ab", title = "Weekly drive card", need = "A card on Sunday with the week's miles", created = now, nonce = "n1", sessionId = "sesn_1")
    private val json = """Done. {"feasible": "partly", "approach": "Add a card source in Olympus.\nRead Odyssey's drives.", "files": ["app/src/main/java/dev/phonerecall/olympia/Cards.kt"], "risk": "Low", "needs": "none", "done_when": "A card shows on Sunday"}"""

    // ---- read-only, Athena only ----

    @Test fun assessAgentCanOnlyRead() {
        val api = FakeForgeApi()
        val forge = Forge(api, MapKv())
        forge.assessAgent()
        forge.agent()
        val (assess, build) = api.specs
        assertEquals(Assess.NAME, assess.name)
        assertEquals(setOf("read", "glob", "grep"), assess.builtins)
        for (t in listOf("bash", "write", "edit")) assertFalse(t, t in assess.builtins)
        assertTrue(assess.tools.isEmpty())                                       // no read_clio, nothing the phone answers
        assertEquals(CodeAgentSpec.BUILD_TOOLS, build.builtins)
        assertFalse(assess.hash == assess.copy(builtins = CodeAgentSpec.BUILD_TOOLS).hash)
    }

    @Test fun onlyAssessIsAsked() {
        assertNull(Assess.refuse(mapOf("kind" to "assess", "title" to "t", "need" to "n")))
        assertNotNull(Assess.refuse(mapOf("kind" to "build", "title" to "t", "need" to "n")))
        assertNotNull(Assess.refuse(mapOf("title" to "t", "need" to "n")))
        assertNotNull(Assess.refuse(mapOf("kind" to "assess", "title" to "t")))
        val tools = Rig().tools
        assertTrue(tools.run(Assess.TOOL, mapOf("kind" to "build", "title" to "t", "need" to "n"), null, asCircle = false).isError)
        assertTrue(tools.run(Assess.TOOL, mapOf("kind" to "assess", "title" to "t", "need" to "n"), null, asCircle = true).isError)
        assertFalse(tools.specs(circle = true).any { it.name == Assess.TOOL })
        assertTrue(tools.specs(circle = false).any { it.name == Assess.TOOL })
    }

    @Test fun anAssessmentIsCappedAndHasNoVault() {
        val api = FakeForgeApi()
        val forge = Forge(api, MapKv())
        forge.assess(a, "tok", 500)
        forge.assess(a.copy(nonce = "n2"), "tok", 3)
        assertEquals(listOf(Assess.BUDGET_CENTS, 3L), api.budgets)
        assertNull(api.vault)
        assertTrue(api.sent.any { it.contains("Read only: no branch, no commits, no PR.") && it.contains("Weekly drive card") })
        assertTrue(api.secrets.isEmpty())
    }

    // ---- the session's answer ----

    @Test fun parsesTheAnswer() {
        val r = Assess.parse(json)!!
        assertEquals("partly", r.feasible)
        assertEquals(listOf("app/src/main/java/dev/phonerecall/olympia/Cards.kt"), r.files)
        assertEquals("A card shows on Sunday", r.doneWhen)
        assertTrue(r.buildable)
        assertNull(Assess.parse("no json here"))
        assertNull(Assess.parse("""{"feasible": "maybe"}"""))
        assertFalse(Assess.parse("""{"feasible": "no"}""")!!.buildable)
    }

    @Test fun stepsFromWorkingToDoneOrFailed() {
        val msg = MindEvent.AgentMessage("e1", 1, json)
        val idle = MindEvent.Idle("e2", 2, "end_turn")
        assertEquals(AssessStage.WORKING, Assess.step(a, SessionInfo("sesn_1", "running"), listOf(msg), now + 60_000).stage)
        val done = Assess.step(a, SessionInfo("sesn_1", "idle", listCostCents = 12), listOf(msg, idle), now + 60_000)
        assertEquals(AssessStage.DONE, done.stage)
        assertEquals(12, done.spentCents)
        val broke = Assess.step(a, SessionInfo("sesn_1", "idle"), listOf(MindEvent.Idle("e3", 3, "budget_reached")), now)
        assertEquals(AssessStage.FAILED, broke.stage)
        assertTrue(broke.error!!.contains("budget"))
        assertEquals(AssessStage.FAILED, Assess.step(a, SessionInfo("sesn_1", "running"), emptyList(), now + Assess.TIMEOUT_MS + 1).stage)
        assertTrue(Assess.words(done).contains("feasible partly"))
        assertTrue(Assess.words(done).contains("Build card"))
    }

    // ---- the Build card ----

    private val done = a.copy(stage = AssessStage.DONE, result = Assess.parse(json))

    @Test fun onlyAFeasibleAssessmentBecomesACard() {
        assertNull(Assess.proposal(done.copy(result = AssessResult("no"))))
        assertNull(Assess.proposal(a))
        val step = Assess.proposal(done)!!
        assertEquals(ProposalKind.BUILD_APP, step.kind)
        assertTrue(Validate.step(ProposalKind.BUILD_APP, step.args, FakeState(), now) is Check.Ok)
        assertTrue(Validate.step(ProposalKind.BUILD_APP, step.args + ("feasible" to "no"), FakeState(), now) is Check.Bad)
        assertTrue(Validate.step(ProposalKind.BUILD_APP, step.args + ("assessment" to "x"), FakeState(), now) is Check.Bad)
        assertEquals(3, ProposalKind.BUILD_APP.tier)
        assertFalse(ProposalKind.BUILD_APP in ProposalKind.autoCandidates)
        assertFalse(ProposalKind.BUILD_APP.modelTool)                           // no model can propose it directly
    }

    /** What the phone's executor does with a confirmed Build card: the normal build request (AndroidExecutor's `builds`). */
    private class Builds : StepExecutor {
        val submitted = ArrayList<String>()
        override fun run(step: Step): ExecResult { if (step.kind == ProposalKind.BUILD_APP) submitted += Assess.problem(step.payload); return ExecResult(step.summary) }
        override fun undo(step: Step) = false
    }

    @Test fun confirmingTheCardSubmitsTheBuildAndNothingElseDoes() {
        val store = MemoryStore()
        val exec = Builds()
        val fates = Fates(store, FakeState(), exec, { _, _, _ -> }, { now })
        fates.autoApprove = ProposalKind.entries.toSet()                          // even with everything on auto, it waits for him
        val step = Assess.proposal(done)!!
        val first = fates.propose(listOf(step), "Build: ${done.title}", null, "Prometheus") as ProposeResult.Created
        val second = fates.propose(listOf(step), "Build: ${done.title}", null, "Prometheus") as ProposeResult.Created
        assertTrue(exec.submitted.isEmpty())
        fates.reject(second.proposal.id)
        assertTrue(exec.submitted.isEmpty())
        fates.confirmNow(first.proposal.id, store)
        assertEquals(1, exec.submitted.size)
        val problem = exec.submitted.single()
        assertTrue(problem.startsWith("Build: Weekly drive card"))
        assertTrue(problem.contains("A card on Sunday with the week's miles"))
        assertTrue(problem.contains("Approach from your assessment a0123456789ab:"))
        assertTrue(problem.contains("Done when: A card shows on Sunday"))
        assertFalse(problem.contains("Open needs"))                                // "none" isn't repeated
    }
}
