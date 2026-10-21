package dev.phonerecall.mind

import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.SpendCapReached
import java.time.ZoneId

/**
 * Managed Agents in memory: no network, no API calls. Counts every create, so idempotency is a number.
 * A scripted "agent" answers each user.message: optionally one custom tool call (the session then waits),
 * then a message and an end_turn idle once the result arrives.
 */
class FakeMindApi : MindApi {
    /** What GET /v1/models answers. */
    var modelList = emptyList<dev.phonerecall.models.ModelEntry>()
    override fun models() = modelList
    val environments = LinkedHashMap<String, String>()
    val stores = LinkedHashMap<String, String>()
    val agents = LinkedHashMap<String, AgentSpec>()
    val versions = HashMap<String, Int>()
    val deployments = LinkedHashMap<String, DeploymentInfo>()
    val deploymentSpecs = HashMap<String, DeploymentSpec>()
    val deploymentBudgets = HashMap<String, Long>()
    val sessions = LinkedHashMap<String, SessionInfo>()
    val nonces = HashMap<String, String>()
    val events = HashMap<String, MutableList<MindEvent>>()
    val memories = LinkedHashMap<String, MemoryFile>()
    val threads = HashMap<String, List<ThreadInfo>>()
    var creates = 0
    var updates = 0
    var messagesSent = 0
    var toolResults = 0
    var interrupts = ArrayList<String?>()
    val paused = HashSet<String>()

    /** The next create of this kind succeeds on the server but its reply is "lost". */
    var loseReplyOf: String? = null
    /** What the agent does with the next user message: call this tool first, or just answer. */
    var script: ((String) -> Pair<String, Map<String, Any?>>?) = { null }
    var reply = "Noted."
    var nextCost = 12L

    private var n = 0
    private fun id(p: String) = "${p}_${++n}"
    private fun lose(kind: String) { if (loseReplyOf == kind) { loseReplyOf = null; throw AmbiguousFailure(java.io.IOException("connection reset")) } }
    private fun at() = 1_000L + n

    override fun findEnvironment(name: String) = environments[name]
    override fun createEnvironment(name: String): String { creates++; val id = id("env"); environments[name] = id; lose("env"); return id }
    override fun findMemoryStore(name: String) = stores[name]
    override fun createMemoryStore(name: String, description: String): String { creates++; val id = id("memstore"); stores[name] = id; lose("store"); return id }

    override fun findAgent(name: String) = agents.keys.firstOrNull { it == name }?.let { AgentRef("agent_$it", versions[it] ?: 1) }
    override fun createAgent(spec: AgentSpec): AgentRef {
        creates++
        if (spec.advisor != null && unavailable.contains(spec.advisor)) throw ModelUnavailable(spec.advisor, "Metis isn't available on this account (${spec.advisor}). It needs 30-day data retention.")
        agents[spec.name] = spec; versions[spec.name] = 1
        lose("agent")
        return AgentRef("agent_${spec.name}", 1)
    }
    val unavailable = HashSet<String>()
    override fun updateAgent(ref: AgentRef, spec: AgentSpec): AgentRef {
        updates++
        if (spec.advisor != null && unavailable.contains(spec.advisor)) throw ModelUnavailable(spec.advisor, "Metis isn't available on this account.")
        agents[spec.name] = spec; val v = (versions[spec.name] ?: 1) + 1; versions[spec.name] = v
        return AgentRef(ref.id, v)
    }

    override fun findSession(nonce: String) = nonces[nonce]?.let { sessions[it] }
    override fun createSession(agent: AgentRef, environmentId: String, memoryStoreId: String, title: String, nonce: String, budgetCents: Long): SessionInfo {
        creates++
        val s = SessionInfo(id("sesn"), "idle", budgetCents = budgetCents, title = title)
        sessions[s.id] = s; nonces[nonce] = s.id; events[s.id] = ArrayList(); sessionAgent[s.id] = agent.id
        lose("session")
        return s
    }
    override fun session(id: String) = sessions.getValue(id)
    /** Which agent each session runs, and the sessions archived (the fresh start). */
    val sessionAgent = HashMap<String, String>()
    val archivedSessions = ArrayList<String>()
    override fun sessionsOf(agentId: String) = sessions.values.filter { sessionAgent[it.id] == agentId && it.id !in archivedSessions }
    override fun archiveSession(id: String) { archivedSessions += id }
    override fun deploymentsNamed(prefix: String) = deployments.values.filter { it.name.startsWith(prefix) }.map { it.copy(paused = it.id in paused) }
    override fun setSessionBudget(id: String, cents: Long) { sessions[id] = sessions.getValue(id).copy(budgetCents = cents) }
    override fun threads(sessionId: String) = threads[sessionId].orEmpty()

    override fun events(sessionId: String): List<MindEvent> = events.getValue(sessionId).toList()
    override fun stream(sessionId: String): MindStream {
        val list = events.getValue(sessionId)
        var i = list.size
        return object : MindStream {
            override fun next(): MindEvent? = if (i < list.size) list[i++] else null
            override fun close() = Unit
        }
    }

    private fun charge(sessionId: String) { sessions[sessionId] = sessions.getValue(sessionId).let { it.copy(listCostCents = it.listCostCents + nextCost, activeSeconds = it.activeSeconds + 30) } }

    /** The next this many user.messages are refused the way the API refuses one while the session waits ("waiting"). */
    var busyNext = 0
    /** Every user.message that reached a session, in order. */
    val sentTexts = ArrayList<Pair<String, String>>()

    override fun sendMessage(sessionId: String, text: String) {
        if (busyNext > 0) { busyNext--; throw SessionBusy(false, "Invalid user.message event at events[0]: waiting") }
        messagesSent++
        sentTexts += sessionId to text
        val list = events.getValue(sessionId)
        list += MindEvent.UserMessage(id("sevt"), at(), text)
        val tool = script(text)
        if (tool != null) {
            list += MindEvent.CustomToolUse(id("sevt"), at(), tool.first, tool.second)
            list += MindEvent.Idle(id("sevt"), at(), "requires_action")
        } else {
            list += MindEvent.AgentMessage(id("sevt"), at(), reply)
            list += MindEvent.Idle(id("sevt"), at(), "end_turn")
        }
        charge(sessionId)
    }

    override fun sendToolResult(sessionId: String, toolUseId: String, text: String, isError: Boolean) {
        toolResults++
        val list = events.getValue(sessionId)
        list += MindEvent.ToolResultSent(id("sevt"), at(), toolUseId)
        lastToolResult = text
        list += MindEvent.AgentMessage(id("sevt"), at(), reply)
        list += MindEvent.Idle(id("sevt"), at(), "end_turn")
    }
    var lastToolResult: String? = null

    override fun interrupt(sessionId: String, threadId: String?) { interrupts += threadId }

    override fun findDeployment(name: String) = deployments.values.firstOrNull { it.name == name }
    override fun createDeployment(spec: DeploymentSpec, agent: AgentRef, environmentId: String, memoryStoreId: String, budgetCents: Long): DeploymentInfo {
        creates++
        val d = DeploymentInfo(id("depl"), spec.name, false)
        deployments[d.id] = d; deploymentSpecs[d.id] = spec; deploymentBudgets[d.id] = budgetCents
        return d
    }
    override fun updateDeployment(id: String, spec: DeploymentSpec, agent: AgentRef, budgetCents: Long): DeploymentInfo { updates++; deploymentSpecs[id] = spec; deploymentBudgets[id] = budgetCents; return deployments.getValue(id) }
    override fun setDeploymentPaused(id: String, paused: Boolean) { if (paused) this.paused += id else this.paused -= id }
    override fun runs(deploymentId: String, sinceMs: Long) = runList.filter { it.deploymentId == deploymentId && it.createdAt > sinceMs }
    val runList = ArrayList<RunInfo>()
    val archived = ArrayList<String>()
    override fun runDeployment(id: String): RunInfo = RunInfo(id("run"), id, null, 0L, null).also { runList += it }
    override fun archiveDeployment(id: String) { archived += id; deployments.remove(id) }

    override fun listMemories(storeId: String, prefix: String) = memories.values.filter { it.path.startsWith(prefix) }.map { it.copy(content = null) }
    override fun readMemory(storeId: String, memoryId: String) = memories.values.first { it.id == memoryId }
    private fun digest(s: String) = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    override fun createMemory(storeId: String, path: String, content: String): MemoryFile {
        val f = MemoryFile(id("mem"), path, digest(content), content.length, content); memories[path] = f; return f
    }
    override fun updateMemory(storeId: String, memoryId: String, content: String): MemoryFile {
        val old = memories.values.first { it.id == memoryId }
        val f = old.copy(sha = digest(content), size = content.length, content = content); memories[old.path] = f; return f
    }
    override fun deleteMemory(storeId: String, memoryId: String) { memories.entries.removeIf { it.value.id == memoryId } }
}

class FakeSink : MindSink {
    val cards = ArrayList<Pair<String, String>>()
    val proposals = ArrayList<Pair<String?, List<RawStep>>>()
    val logs = ArrayList<String>()
    override fun card(title: String, body: String, hours: Int) { cards += title to body }
    override fun propose(title: String?, steps: List<RawStep>, proposer: String): String { proposals += title to steps; return "ok" }
    override fun log(actor: String, action: String, detail: String) { logs += "$actor $action: $detail" }
    override fun changed() = Unit
    val agora = ArrayList<String>()
    override fun agora(text: String) { agora += text }
    val chatReplies = ArrayList<Pair<Long, String>>()
    val chatProposals = ArrayList<Pair<Long, Long>>()
    val chatNotes = ArrayList<Pair<String, String>>()
    override fun chatReply(chat: Long, text: String) { chatReplies += chat to text }
    override fun chatProposal(chat: Long, id: Long, title: String) { chatProposals += chat to id }
    override fun chatNote(key: String, text: String) { chatNotes += key to text }
    val sessionsStarted = ArrayList<String>()
    override fun sessionStarted(day: String) { sessionsStarted += day }
}

class FakeContext(var zoneId: ZoneId = ZoneId.of("America/New_York")) : MindContext {
    var snapshotText = "# Snapshot\n- Groceries: tomatoes, garlic"
    var people = mapOf("Amma" to "family (mother)", "Taylor" to "manager")
    var sealedNames = setOf("Riley")
    override fun zone() = zoneId
    override fun snapshot() = snapshotText
    override fun decisions() = "# Decisions\n- Oct 19: rejected \"Alarm at 6:00 AM\""
    override fun people() = "# People\n- family (mother): reply now"
    override fun routines() = "# Routines\n- Office Tuesday to Thursday."
    override fun preferences() = "# Preferences\n- Auto-approve: nothing."
    override fun names() = people
    override fun sealed() = sealedNames
    var factsText = ""
    override fun facts() = factsText
    var pendingText = ""
    override fun pending() = pendingText
    val turns = ArrayList<String>()
    override fun recentTurns(n: Int) = turns.takeLast(n)
}

class FakeLedger(var capCents: Long = 10_000, var spent: Long = 0) : MindLedger {
    var mindCents = 0L
    var seconds = 0L
    override fun check() { if (spent >= capCents) throw SpendCapReached((capCents / 100).toInt()) }
    override fun capCents() = capCents
    override fun spentCents() = spent
    override fun recordMind(cents: Long, activeSeconds: Long) { spent += cents; mindCents += cents; seconds += activeSeconds }
}
