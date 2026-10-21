package dev.phonerecall.prometheus

import dev.phonerecall.mind.AgentRef
import dev.phonerecall.mind.AmbiguousFailure
import dev.phonerecall.mind.MapKv
import dev.phonerecall.mind.MindEvent
import dev.phonerecall.mind.SessionInfo
import dev.phonerecall.mind.stateJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records every value that would leave the phone for Anthropic. */
class FakeForgeApi : ForgeApi {
    val sent = ArrayList<String>()
    val secrets = ArrayList<Pair<String, String>>()
    val sessions = LinkedHashMap<String, SessionInfo>()
    val nonces = HashMap<String, String>()
    var repo: RepoMount? = null
    var vault: String? = "unset"
    val budgets = ArrayList<Long>()
    var agentCreates = 0
    var agentUpdates = 0
    var loseNextSession = false

    override fun findAgent(name: String): AgentRef? = null
    override fun createCodeAgent(spec: CodeAgentSpec): AgentRef { agentCreates++; specs += spec;sent += listOf(spec.name, spec.description, spec.system); return AgentRef("agent_p", 1) }
    override fun updateCodeAgent(ref: AgentRef, spec: CodeAgentSpec): AgentRef { agentUpdates++; sent += spec.system; return ref.copy(version = ref.version + 1) }
    override fun findEnvironment(name: String): String? = null
    override fun createLimitedEnvironment(name: String, hosts: List<String>): String { sent += hosts; return "env_p" }
    override fun findVault(name: String): String? = null
    override fun createVault(name: String): String { sent += name; return "vlt_p" }
    override fun putSecret(vaultId: String, secretName: String, value: String, hosts: List<String>) { secrets += secretName to value; sent += hosts }
    override fun findSession(nonce: String) = nonces[nonce]?.let { sessions[it] }
    override fun createRepoSession(agent: AgentRef, environmentId: String, vaultId: String?, repo: RepoMount, title: String, nonce: String, budgetCents: Long, firstMessage: String): SessionInfo {
        this.repo = repo; this.vault = vaultId; budgets += budgetCents
        sent += listOf(title, nonce, firstMessage, repo.toString())
        val s = SessionInfo("sesn_${sessions.size + 1}", "running", budgetCents = budgetCents, title = title)
        sessions[s.id] = s; nonces[nonce] = s.id
        if (loseNextSession) { loseNextSession = false; throw AmbiguousFailure(java.io.IOException("reply lost")) }
        return s
    }
    override fun session(id: String) = sessions.getValue(id)
    val results = ArrayList<Triple<String, String, Boolean>>()
    val specs = ArrayList<CodeAgentSpec>()
    override fun events(sessionId: String): List<MindEvent> = emptyList()
    override fun sendToolResult(sessionId: String, toolUseId: String, text: String, isError: Boolean) { results += Triple(toolUseId, text, isError); sent += text }
    override fun interrupt(sessionId: String, threadId: String?) { sessions[sessionId] = sessions.getValue(sessionId).copy(status = "idle") }
}

class CloudTest {
    private val sha = "a".repeat(40)
    private val head = "b".repeat(40)
    private val apkSha = "c".repeat(64)
    private val token = "github_pat_11ZEUSTEST_k9QxR2vLm8TnW4pY7sJd3HfB6gC1aE5"

    private fun release(author: String = Repo.BOT, target: String = sha, digest: String? = "sha256:$apkSha", uploader: String = Repo.BOT, body: String = "Zeus 1.8.2\n\nSHA-256: `$apkSha`\n") =
        Release("v1.8.2", "Zeus 1.8.2", author, target, false, body, "https://github.com/your-github-user/zeus/releases/tag/v1.8.2", listOf(Asset(7, "zeus-1.8.2.apk", 90_000_000, digest, uploader)))

    // ---- release asset verification ----

    @Test fun onlyTheWorkflowsReleaseOfTheMergedCommitPasses() {
        assertNull(ReleaseCheck.refuse(release(), sha))
        assertEquals(apkSha, ReleaseCheck.sha(release()))
        assertNotNull(ReleaseCheck.refuse(release(author = "your-github-user"), sha))          // made with a person's token, e.g. Prometheus's
        assertNotNull(ReleaseCheck.refuse(release(target = head), sha))                     // another commit
        assertNotNull(ReleaseCheck.refuse(release(uploader = "your-github-user"), sha))          // APK swapped in later
        assertNotNull(ReleaseCheck.refuse(release(digest = "sha256:" + "d".repeat(64)), sha)) // GitHub's digest disagrees with the notes
        assertNotNull(ReleaseCheck.refuse(release(body = "no hash here"), sha))
        assertNotNull(ReleaseCheck.refuse(release().copy(assets = emptyList()), sha))
        assertNull(ReleaseCheck.refuse(release(digest = null), sha))                        // older API without digests: the notes' hash still applies
    }

    @Test fun shaAndSignerMismatchAreRefusedBeforeInstall() {
        val ok = Signer.verdict("dev.phonerecall", setOf("k1"), "dev.phonerecall", setOf("k1"), 144, 145)
        val otherKey = Signer.verdict("dev.phonerecall", setOf("k1"), "dev.phonerecall", setOf("k2"), 144, 145)
        assertEquals(Signer.Verdict.Ok, ok)
        assertNull(InstallGate.refuse(apkSha, apkSha.uppercase(), ok))
        assertNotNull(InstallGate.refuse(apkSha, "e".repeat(64), ok))       // bytes changed on the way
        assertNotNull(InstallGate.refuse(null, apkSha, ok))                 // no hash to compare with
        assertEquals("Signed with a different key than the app on this phone", InstallGate.refuse(apkSha, apkSha, otherKey))
    }

    // ---- the phone's state machine ----

    @Test fun requestToReleaseWalksTheStages() {
        var c = CloudChange(id = "c1", problem = "Fix the tasks card", nonce = "n1")
        c = CloudFlow.session(c, SessionInfo("s1", "running", listCostCents = 40), listOf(MindEvent.AgentMessage("e1", 0, "Reading TasksCard.kt")), 1)
        assertEquals(CloudStage.WORKING, c.stage)
        assertEquals("Reading TasksCard.kt", c.progress)
        assertEquals(40, c.spentCents)

        val pr = Pull(3, "https://github.com/your-github-user/zeus/pull/3", "Fix the tasks card", "open", false, head, null)
        c = CloudFlow.pull(c, pr, CiRun(Ci.RUNNING, null), 2)
        assertEquals(CloudStage.PR_OPEN, c.stage)
        assertNotNull(CloudFlow.mergeBlocked(c))                              // CI still running, and Prometheus too

        c = CloudFlow.pull(c, pr, CiRun(Ci.GREEN, null), 3)
        assertNotNull(CloudFlow.mergeBlocked(c))                              // green, but the agent may still push
        c = CloudFlow.session(c, SessionInfo("s1", "idle", listCostCents = 90), emptyList(), 4)
        assertNull(CloudFlow.mergeBlocked(c))

        // A new commit after CI: the live check refuses the merge, and the next read resets CI to that head.
        val newer = pr.copy(headSha = "f".repeat(40))
        assertNotNull(CloudFlow.mergeRefused(c, newer, CiRun(Ci.GREEN, null)))
        assertNull(CloudFlow.mergeRefused(c, pr, CiRun(Ci.GREEN, null)))
        assertNotNull(CloudFlow.mergeBlocked(CloudFlow.pull(c, newer, CiRun(Ci.RUNNING, null), 5)))

        c = CloudFlow.dispatched(CloudFlow.merged(c, sha, 6), 7)
        assertEquals(CloudStage.RELEASING, c.stage)
        assertEquals(c, CloudFlow.release(c, CiRun(Ci.RUNNING, null), null, 7).copy(updated = 7))

        val ready = CloudFlow.release(c, CiRun(Ci.GREEN, null), release(), 8)
        assertEquals(CloudStage.READY, ready.stage)
        assertEquals("1.8.2", ready.versionName)
        assertEquals(7L, ready.assetId)
        assertEquals(apkSha, ready.apkSha256)
        assertEquals(CloudStage.INSTALLED, CloudFlow.installed(ready, 9).stage)
    }

    @Test fun failuresStopTheFlow() {
        val releasing = CloudChange(id = "c2", stage = CloudStage.RELEASING, mergeSha = sha)
        assertEquals(CloudStage.FAILED, CloudFlow.release(releasing, CiRun(Ci.RED, "https://x"), null, 1).stage)
        val forged = CloudFlow.release(releasing, null, release(author = "your-github-user"), 1)
        assertEquals(CloudStage.FAILED, forged.stage)
        assertNull(forged.assetId)                                             // nothing to download, so nothing to install

        val working = CloudChange(id = "c3", stage = CloudStage.WORKING)
        val closed = CloudFlow.pull(working, Pull(4, "u", "t", "closed", false, head, null), null, 1)
        assertEquals(CloudStage.CLOSED, closed.stage)
        val mergedElsewhere = CloudFlow.pull(working, Pull(4, "u", "t", "closed", true, head, sha), null, 1)
        assertEquals(CloudStage.MERGED, mergedElsewhere.stage)
        assertEquals(sha, mergedElsewhere.mergeSha)
        assertNotNull(CloudFlow.session(working, SessionInfo("s", "terminated"), emptyList(), 1).error)
        assertFalse(CloudStage.READY.active)
    }

    // ---- the token stays out of every prompt ----

    @Test fun promptNeverContainsTheToken() {
        val api = FakeForgeApi()
        val forge = Forge(api, MapKv())
        val c = CloudChange(id = "c4", problem = "Weather card shows yesterday", nonce = "n4")
        forge.start(c, token, listOf("aeolia fetch: 503"), 500)
        assertTrue(api.sent.isNotEmpty())
        for (s in api.sent) assertFalse(s, s.contains(token) || s.contains(token.takeLast(12)))
        assertFalse(PrometheusPrompts.SYSTEM.contains("github_pat"))
        assertEquals(token, api.repo!!.token)                                  // only the repository resource carries it
        assertEquals(listOf(Forge.SECRET to token), api.secrets)              // and the vault, once
        assertTrue(api.sent.any { it.contains("Branch: prometheus/c4") })
        assertTrue(api.sent.any { it.contains("<log>aeolia fetch: 503</log>") })
    }

    @Test fun vaultAndAgentAreWrittenOnlyWhenTheyChange() {
        val api = FakeForgeApi()
        val kv = MapKv()
        val forge = Forge(api, kv)
        val c = CloudChange(id = "c5", problem = "x", nonce = "n5")
        forge.start(c, token, emptyList(), 500)
        forge.start(c.copy(nonce = "n6"), token, emptyList(), 500)
        assertEquals(1, api.secrets.size)
        assertEquals(1, api.agentCreates)
        forge.start(c.copy(nonce = "n7"), token + "rotated", emptyList(), 500)
        assertEquals(2, api.secrets.size)
        kv.put("agent.hash", "old")
        forge.agent()
        assertEquals(1, api.agentUpdates)
        assertFalse(kv.map.values.any { it.contains(token) })                  // the phone keeps a short hash, never the token
    }

    @Test fun aLostCreateIsFoundAgainNotMadeTwice() {
        val api = FakeForgeApi().apply { loseNextSession = true }
        val s = Forge(api, MapKv()).start(CloudChange(id = "c6", problem = "x", nonce = "n8"), token, emptyList(), 500)
        assertEquals("sesn_1", s.id)
        assertEquals(1, api.sessions.size)
    }

    @Test fun cloudChangesSurviveTheirStore() {
        val c = CloudChange(id = "c7", problem = "p", stage = CloudStage.PR_OPEN, ci = Ci.GREEN, prNumber = 9, headSha = head)
        val back = stateJson.readValue(stateJson.writeValueAsString(listOf(c)), object : com.fasterxml.jackson.core.type.TypeReference<List<CloudChange>>() {})
        assertEquals(listOf(c), back)
    }

    // ---- GitHub's JSON and the download host ----

    @Test fun readsGitHubsJson() {
        val p = GitHubJson.pull(stateJson.readTree("""{"number":3,"html_url":"https://github.com/your-github-user/zeus/pull/3","title":"Fix","state":"closed","merged_at":"2026-10-20T10:00:00Z","head":{"sha":"$head"},"merge_commit_sha":"$sha"}"""))
        assertEquals(Pull(3, "https://github.com/your-github-user/zeus/pull/3", "Fix", "closed", true, head, sha), p)
        assertFalse(GitHubJson.pull(stateJson.readTree("""{"number":3,"state":"open","merged_at":null,"head":{"sha":"$head"}}""")).merged)

        val runs = stateJson.readTree("""{"workflow_runs":[{"display_title":"release $sha","status":"completed","conclusion":"failure","html_url":"https://r/1"},{"display_title":"other","status":"in_progress"}]}""")
        assertEquals(CiRun(Ci.RED, "https://r/1"), GitHubJson.run(runs))
        assertEquals(Ci.RED, GitHubJson.run(runs, "release $sha")!!.state)
        assertNull(GitHubJson.run(runs, "release $head"))
        assertEquals(Ci.RUNNING, GitHubJson.run(runs, "other")!!.state)

        val r = GitHubJson.release(stateJson.readTree("""{"tag_name":"v1.8.2","name":"Zeus 1.8.2","author":{"login":"github-actions[bot]"},"target_commitish":"$sha","draft":false,"body":"SHA-256: `$apkSha`","html_url":"u","assets":[{"id":7,"name":"zeus-1.8.2.apk","size":10,"digest":"sha256:$apkSha","uploader":{"login":"github-actions[bot]"}}]}"""))
        assertNull(ReleaseCheck.refuse(r, sha))
    }

    @Test fun downloadsLandOnlyOnGitHubsReleaseStorage() {
        assertTrue(GitHubApi.assetHost("https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sig=x"))
        assertFalse(GitHubApi.assetHost("http://release-assets.githubusercontent.com/x"))
        assertFalse(GitHubApi.assetHost("https://api.github.com/repos/your-github-user/zeus"))       // allowed by NetGate, but not a download host
        assertFalse(GitHubApi.assetHost("https://objects.githubusercontent.com/x"))
        assertFalse(GitHubApi.assetHost("https://evil.example/zeus.apk"))
    }
}
