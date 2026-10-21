package dev.phonerecall.mind

/*
 * Phase 2c: Athena's mind on Claude Managed Agents. Every Managed Agents call goes through this one
 * interface. SdkMindApi is the real one (the official Anthropic Java SDK 2.73.0, through NetGate);
 * tests use FakeMindApi. Nothing here knows about Android.
 *
 * Endpoints (the SDK sets anthropic-beta: managed-agents-2026-04-01 on agents, environments,
 * sessions, threads, deployments and deployment runs, and agent-memory-2026-07-22 on memory stores):
 *   GET/POST /v1/environments
 *   GET/POST /v1/memory_stores; GET/POST/PATCH/DELETE /v1/memory_stores/{id}/memories[/{mid}]
 *   GET/POST /v1/agents, POST /v1/agents/{id} (update = new version)
 *   GET/POST /v1/sessions, GET/POST /v1/sessions/{id}, GET /v1/sessions/{id}/threads
 *   POST /v1/sessions/{id}/events, GET /v1/sessions/{id}/events, GET /v1/sessions/{id}/events/stream
 *   GET/POST /v1/deployments, POST /v1/deployments/{id}[/pause|/unpause|/run|/archive], GET /v1/deployment_runs
 */

/** A custom tool the phone answers. `schema` is a JSON Schema object as plain maps. */
data class ToolSpec(val name: String, val description: String, val schema: Map<String, Any?>)

/**
 * One stored agent: Athena (the coordinator) or one of her circle. `roster` is set only on Athena:
 * her circle at their current versions; `self` adds a copy of herself. Every agent gets the memory
 * store's file tools (read, write, edit, glob, grep); bash stays off. `web` (Perseus only) turns on
 * web search and fetch and turns write and edit off, so web pages can never land in the memory store.
 * `effort` null means the model's default. `hash` decides whether the stored agent needs an update.
 */
data class AgentSpec(
    val name: String,
    val model: String,
    val description: String,
    val system: String,
    val tools: List<ToolSpec>,
    val effort: String? = null,
    val roster: List<AgentRef> = emptyList(),
    val self: Boolean = false,
    /** The advisor model for Athena's primary thread (Metis), or null for none. */
    val advisor: String? = null,
    val web: Boolean = false,
) {
    val hash: String get() = sha(
        listOf(model, effort.orEmpty(), description, system, tools.joinToString("\n") { it.name + it.description + it.schema }, roster.joinToString { "${it.id}@${it.version}" }, "$self", advisor.orEmpty(), "$web").joinToString("\n"),
    )
}

data class AgentRef(val id: String, val version: Int)

/** A scheduled deployment: cron in an IANA zone, the message that starts each run. */
data class DeploymentSpec(val name: String, val cron: String, val zone: String, val message: String) {
    val hash: String get() = sha("$cron|$zone|$message")
}

data class DeploymentInfo(val id: String, val name: String, val paused: Boolean, val nextRuns: List<String> = emptyList())

data class RunInfo(val id: String, val deploymentId: String, val sessionId: String?, val createdAt: Long, val error: String?)

/** status: idle, running, rescheduling, terminated. `listCostCents` is usage.list_cost (it includes running time). */
data class SessionInfo(
    val id: String,
    val status: String,
    val listCostCents: Long = 0,
    val activeSeconds: Double = 0.0,
    val budgetCents: Long? = null,
    val title: String? = null,
    /** Input tokens read so far (uncached plus cache reads), summed over every request: how big the session has grown. */
    val tokens: Long = 0,
)

/** One thread of a session: the primary (Athena) or a child working for her. */
data class ThreadInfo(val id: String, val agentName: String, val status: String, val listCostCents: Long, val primary: Boolean)

data class MemoryFile(val id: String, val path: String, val sha: String, val size: Int, val content: String? = null)

/** The events the phone acts on, parsed from the wire format (managed-agents-events.md). */
sealed class MindEvent {
    abstract val id: String
    abstract val at: Long
    data class AgentMessage(override val id: String, override val at: Long, val text: String) : MindEvent()
    /** `thread` is the child thread that asked (cross-posted to the primary stream), null for Athena herself. */
    data class CustomToolUse(override val id: String, override val at: Long, val name: String, val input: Map<String, Any?>, val thread: String? = null) : MindEvent()
    data class ToolResultSent(override val id: String, override val at: Long, val toolUseId: String) : MindEvent()
    data class UserMessage(override val id: String, override val at: Long, val text: String) : MindEvent()
    /** stopReason: end_turn, requires_action, budget_reached, retries_exhausted, refusal. */
    data class Idle(override val id: String, override val at: Long, val stopReason: String) : MindEvent()
    data class Terminated(override val id: String, override val at: Long) : MindEvent()
    data class ThreadCreated(override val id: String, override val at: Long, val thread: String, val agentName: String) : MindEvent()
    data class ThreadIdle(override val id: String, override val at: Long, val thread: String, val agentName: String, val stopReason: String) : MindEvent()
    data class Usage(override val id: String, override val at: Long, val listCostCents: Long, val activeSeconds: Double) : MindEvent()
    data class Error(override val id: String, override val at: Long, val message: String) : MindEvent()
    data class Other(override val id: String, override val at: Long, val type: String) : MindEvent()
}

/** A live SSE connection. next() returns null when the stream ends or is closed. */
interface MindStream : AutoCloseable {
    fun next(): MindEvent?
}

interface MindApi {
    fun findEnvironment(name: String): String?
    fun createEnvironment(name: String): String

    fun findMemoryStore(name: String): String?
    fun createMemoryStore(name: String, description: String): String

    fun findAgent(name: String): AgentRef?
    fun createAgent(spec: AgentSpec): AgentRef
    fun updateAgent(ref: AgentRef, spec: AgentSpec): AgentRef

    /** The session that carries `nonce` in its metadata, for recovering a create whose reply was lost. */
    fun findSession(nonce: String): SessionInfo?
    fun createSession(agent: AgentRef, environmentId: String, memoryStoreId: String, title: String, nonce: String, budgetCents: Long): SessionInfo
    fun session(id: String): SessionInfo
    fun setSessionBudget(id: String, cents: Long)
    fun threads(sessionId: String): List<ThreadInfo>

    /** Every event in order (the implementation pages). */
    fun events(sessionId: String): List<MindEvent>
    fun stream(sessionId: String): MindStream
    fun sendMessage(sessionId: String, text: String)
    fun sendToolResult(sessionId: String, toolUseId: String, text: String, isError: Boolean)
    /** Stops one child thread (a long job), or every thread when `threadId` is null. */
    fun interrupt(sessionId: String, threadId: String?)

    fun findDeployment(name: String): DeploymentInfo?
    fun createDeployment(spec: DeploymentSpec, agent: AgentRef, environmentId: String, memoryStoreId: String, budgetCents: Long): DeploymentInfo
    fun updateDeployment(id: String, spec: DeploymentSpec, agent: AgentRef, budgetCents: Long): DeploymentInfo
    fun setDeploymentPaused(id: String, paused: Boolean)
    fun runs(deploymentId: String, sinceMs: Long): List<RunInfo>
    /** One run now, outside the schedule (POST /v1/deployments/{id}/run). */
    fun runDeployment(id: String): RunInfo
    /** Retires a deployment for good (POST /v1/deployments/{id}/archive). */
    fun archiveDeployment(id: String)

    fun listMemories(storeId: String, prefix: String): List<MemoryFile>
    fun readMemory(storeId: String, memoryId: String): MemoryFile
    fun createMemory(storeId: String, path: String, content: String): MemoryFile
    fun updateMemory(storeId: String, memoryId: String, content: String): MemoryFile
    fun deleteMemory(storeId: String, memoryId: String)

    /** One agent's sessions that aren't archived (GET /v1/sessions?agent_id=), for the fresh start. */
    fun sessionsOf(agentId: String): List<SessionInfo> = emptyList()
    /** POST /v1/sessions/{id}/archive: read-only from then on, nothing deleted. */
    fun archiveSession(id: String) {}
    /** Every deployment not archived whose name starts with [prefix]. */
    fun deploymentsNamed(prefix: String): List<DeploymentInfo> = emptyList()

    /** Anthropic's model list (GET /v1/models), for the model book. Empty where there is none (tests). */
    fun models(): List<dev.phonerecall.models.ModelEntry> = emptyList()
}

/** A create whose reply never arrived: the server may or may not have done it. Look before retrying. */
class AmbiguousFailure(cause: Throwable) : RuntimeException(cause.message, cause)

/**
 * The session takes no new user.message right now: it waits on a tool result (stop_reason requires_action,
 * the API's "waiting") or is paused at its budget. Nothing was sent; queue it and send once the session is idle.
 */
class SessionBusy(val budget: Boolean, message: String) : RuntimeException(message)

/** The model refused (400) for this account, e.g. Fable on a zero-data-retention org. */
class ModelUnavailable(val model: String, message: String) : RuntimeException(message)

fun sha(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
