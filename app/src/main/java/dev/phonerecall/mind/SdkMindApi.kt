package dev.phonerecall.mind

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.NotFoundException
import com.anthropic.models.beta.BetaCurrency
import com.anthropic.models.beta.BetaMonetaryAmount
import com.anthropic.models.beta.agents.AgentCreateParams
import com.anthropic.models.beta.agents.AgentUpdateParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAgent
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolset20260401Params
import com.anthropic.models.beta.agents.BetaManagedAgentsAgentToolsetDefaultConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsAlwaysAllowPolicy
import com.anthropic.models.beta.agents.BetaManagedAgentsBashToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsCustomToolInputSchema
import com.anthropic.models.beta.agents.BetaManagedAgentsCustomToolParams
import com.anthropic.models.beta.agents.BetaManagedAgentsEditToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsGlobToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsGrepToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsModelConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsMultiagentCoordinatorParams
import com.anthropic.models.beta.agents.BetaManagedAgentsMultiagentSelfParams
import com.anthropic.models.beta.agents.BetaManagedAgentsReadToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWebFetchToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWebSearchToolConfigParams
import com.anthropic.models.beta.agents.BetaManagedAgentsWriteToolConfigParams
import com.anthropic.models.beta.deploymentruns.DeploymentRunListParams
import com.anthropic.models.beta.deployments.BetaManagedAgentsDeployment
import com.anthropic.models.beta.deployments.DeploymentCreateParams
import com.anthropic.models.beta.deployments.DeploymentUpdateParams
import com.anthropic.models.beta.environments.BetaCloudConfigParams
import com.anthropic.models.beta.environments.BetaUnrestrictedNetwork
import com.anthropic.models.beta.environments.EnvironmentCreateParams
import com.anthropic.models.beta.memorystores.MemoryStoreCreateParams
import com.anthropic.models.beta.memorystores.memories.BetaManagedAgentsMemory
import com.anthropic.models.beta.memorystores.memories.BetaManagedAgentsMemoryView
import com.anthropic.models.beta.memorystores.memories.MemoryCreateParams
import com.anthropic.models.beta.memorystores.memories.MemoryDeleteParams
import com.anthropic.models.beta.memorystores.memories.MemoryListParams
import com.anthropic.models.beta.memorystores.memories.MemoryRetrieveParams
import com.anthropic.models.beta.memorystores.memories.MemoryUpdateParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsAdvisorParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsAgentParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsMemoryStoreResourceParam
import com.anthropic.models.beta.sessions.BetaManagedAgentsMultiagentRosterEntryParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsSession
import com.anthropic.models.beta.sessions.SessionCreateParams
import com.anthropic.models.beta.sessions.SessionUpdateParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserCustomToolResultEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserInterruptEventParams
import com.anthropic.models.beta.sessions.events.BetaManagedAgentsUserMessageEventParams
import com.anthropic.models.beta.sessions.events.EventSendParams
import com.fasterxml.jackson.core.type.TypeReference
import com.anthropic.models.beta.environments.BetaLimitedNetworkParams
import com.anthropic.models.beta.sessions.BetaManagedAgentsGitHubRepositoryResourceParams
import com.anthropic.models.beta.vaults.VaultCreateParams
import com.anthropic.models.beta.vaults.credentials.BetaManagedAgentsInjectionLocationParams
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.NoKey
import dev.phonerecall.prometheus.CodeAgentSpec
import dev.phonerecall.prometheus.ForgeApi
import dev.phonerecall.prometheus.RepoMount
import com.anthropic.models.beta.vaults.credentials.BetaManagedAgentsEnvironmentVariableCreateParams as EnvVarCreate
import com.anthropic.models.beta.vaults.credentials.BetaManagedAgentsEnvironmentVariableUpdateParams as EnvVarUpdate
import com.anthropic.models.beta.vaults.credentials.CredentialCreateParams as VaultItemCreate
import com.anthropic.models.beta.vaults.credentials.CredentialUpdateParams as VaultItemUpdate
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The real Managed Agents client: the official Java SDK, built like Delphi's (NetGate in front of
 * OkHttp, base URL pinned, telemetry off). maxRetries is 0: the SDK would otherwise retry a POST whose
 * reply was lost and create a second paid session or event. Mind retries itself, after looking.
 */
class SdkMindApi(private val key: () -> String?, private val origin: String = NetGate.ORIGIN) : MindApi, ForgeApi {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null
    private val json = jsonMapper()

    @Synchronized
    private fun c(): AnthropicClient {
        val k = key() ?: throw NoKey()
        if (client == null || clientKey != k) {
            client?.close()
            client = AnthropicOkHttpClient.builder()
                .apiKey(k)
                .baseUrl(origin)
                .addInterceptor(NetGate.interceptor)
                .openTelemetry(false)
                .maxRetries(0)
                .timeout(Duration.ofSeconds(90))
                .build()
            clientKey = k
        }
        return client!!
    }

    /** A POST that creates something paid or permanent: a lost reply becomes AmbiguousFailure. */
    private fun <T> creating(block: () -> T): T = try { block() } catch (e: AnthropicIoException) { throw AmbiguousFailure(e) }

    private fun map(v: Any?): Map<String, Any?> = runCatching { json.convertValue(v, object : TypeReference<Map<String, Any?>>() {}) }.getOrDefault(emptyMap())

    private fun cents(m: Any?): Long = ((m as? Map<*, *>)?.get("amount")?.toString()?.toLongOrNull()) ?: 0L

    // ---- environment, memory store ----

    override fun findEnvironment(name: String): String? =
        c().beta().environments().list().autoPager().firstOrNull { it.name() == name && !it.archivedAt().isPresent }?.id()

    override fun createEnvironment(name: String): String = creating {
        // Unrestricted only so Perseus's web tools (which run on Anthropic's servers but obey limited
        // networking's allowed_hosts) can reach pages. Bash is off for every agent, so nothing in the
        // sandbox can open a connection itself.
        c().beta().environments().create(
            EnvironmentCreateParams.builder()
                .name(name)
                .config(BetaCloudConfigParams.builder().networking(BetaUnrestrictedNetwork.builder().build()).build())
                .build(),
        ).id()
    }

    override fun findMemoryStore(name: String): String? =
        c().beta().memoryStores().list().autoPager().firstOrNull { it.name() == name && !it.archivedAt().isPresent }?.id()

    override fun createMemoryStore(name: String, description: String): String = creating {
        c().beta().memoryStores().create(MemoryStoreCreateParams.builder().name(name).description(description).build()).id()
    }

    // ---- agents ----

    override fun findAgent(name: String): AgentRef? =
        c().beta().agents().list().autoPager().firstOrNull { it.name() == name && !it.archivedAt().isPresent }?.let { AgentRef(it.id(), it.version()) }

    private fun model(spec: AgentSpec): BetaManagedAgentsModelConfigParams = BetaManagedAgentsModelConfigParams.builder().id(spec.model).apply {
        spec.effort?.let { effort(BetaManagedAgentsModelConfigParams.Effort.ofBetaManagedAgentsEffortLevel(BetaManagedAgentsModelConfigParams.Effort.BetaManagedAgentsEffortLevel.of(it))) }
    }.build()

    /** The memory mount's file tools; bash off. Web agents (Perseus) get search + fetch and lose write + edit. */
    private fun toolset(web: Boolean): BetaManagedAgentsAgentToolset20260401Params {
        val allow = BetaManagedAgentsAlwaysAllowPolicy.builder().type(BetaManagedAgentsAlwaysAllowPolicy.Type.ALWAYS_ALLOW).build()
        return BetaManagedAgentsAgentToolset20260401Params.builder()
            .type(BetaManagedAgentsAgentToolset20260401Params.Type.AGENT_TOOLSET_20260401)
            .defaultConfig(BetaManagedAgentsAgentToolsetDefaultConfigParams.builder().enabled(false).build())
            .addConfig(BetaManagedAgentsReadToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsWriteToolConfigParams.builder().enabled(!web).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsEditToolConfigParams.builder().enabled(!web).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsGlobToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsGrepToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsBashToolConfigParams.builder().enabled(false).build())
            .addConfig(BetaManagedAgentsWebFetchToolConfigParams.builder().enabled(web).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsWebSearchToolConfigParams.builder().enabled(web).permissionPolicy(allow).build())
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun custom(t: ToolSpec): BetaManagedAgentsCustomToolParams {
        val props = (t.schema["properties"] as? Map<String, Any?>).orEmpty()
        val schema = BetaManagedAgentsCustomToolInputSchema.builder()
            .type(JsonValue.from("object"))
            .properties(BetaManagedAgentsCustomToolInputSchema.Properties.builder().apply { for ((k, v) in props) putAdditionalProperty(k, JsonValue.from(v)) }.build())
            .required((t.schema["required"] as? List<String>).orEmpty())
            .apply { t.schema["additionalProperties"]?.let { putAdditionalProperty("additionalProperties", JsonValue.from(it)) } }
            .build()
        return BetaManagedAgentsCustomToolParams.builder().type(BetaManagedAgentsCustomToolParams.Type.CUSTOM).name(t.name).description(t.description).inputSchema(schema).build()
    }

    private fun roster(spec: AgentSpec): List<BetaManagedAgentsMultiagentRosterEntryParams> = buildList {
        for (r in spec.roster) add(BetaManagedAgentsMultiagentRosterEntryParams.ofAgent(BetaManagedAgentsAgentParams.builder().type(BetaManagedAgentsAgentParams.Type.AGENT).id(r.id).version(r.version).build()))
        if (spec.self) add(BetaManagedAgentsMultiagentRosterEntryParams.ofSelf(BetaManagedAgentsMultiagentSelfParams.builder().type(BetaManagedAgentsMultiagentSelfParams.Type.SELF).build()))
        spec.advisor?.let { add(BetaManagedAgentsMultiagentRosterEntryParams.ofAdvisor(BetaManagedAgentsAdvisorParams.builder().type(BetaManagedAgentsAdvisorParams.Type.ADVISOR).model(it).build())) }
    }

    private fun ref(a: BetaManagedAgentsAgent) = AgentRef(a.id(), a.version())

    override fun createAgent(spec: AgentSpec): AgentRef = creating {
        modelGuard(spec.model, spec.advisor) {
            val p = AgentCreateParams.builder().name(spec.name).model(model(spec)).description(spec.description).system(spec.system).addTool(toolset(spec.web))
            for (t in spec.tools) p.addTool(custom(t))
            // The "coordinator" form is a roster only: dynamic workflows stay off.
            roster(spec).takeIf { it.isNotEmpty() }?.let { p.coordinatorMultiagent(it) }
            ref(c().beta().agents().create(p.build()))
        }
    }

    override fun updateAgent(ref: AgentRef, spec: AgentSpec): AgentRef = modelGuard(spec.model, spec.advisor) {
        val p = AgentUpdateParams.builder().version(ref.version).name(spec.name).model(model(spec)).description(spec.description).system(spec.system).addTool(toolset(spec.web))
        for (t in spec.tools) p.addTool(custom(t))
        val r = roster(spec)
        if (r.isNotEmpty()) p.coordinatorMultiagent(r) else p.multiagent(java.util.Optional.empty())
        ref(c().beta().agents().update(ref.id, p.build()))
    }

    /** A 400 naming the model (Fable on a zero-data-retention org, say) says plainly which one. */
    private fun <T> modelGuard(model: String, advisor: String?, block: () -> T): T = try { block() } catch (e: BadRequestException) {
        val m = e.message.orEmpty()
        when {
            advisor != null && m.contains(advisor) -> throw ModelUnavailable(advisor, "Metis isn't available on this account ($advisor). It needs 30-day data retention.")
            m.contains(model) -> throw ModelUnavailable(model, "$model isn't available on this account.")
            else -> throw e
        }
    }

    // ---- sessions ----

    private fun info(s: BetaManagedAgentsSession): SessionInfo {
        val u = s.usage()
        return SessionInfo(
            id = s.id(), status = s.status().asString(),
            listCostCents = u.listCost().map { it.amount().toLongOrNull() ?: 0L }.orElse(0L),
            activeSeconds = u.activeSeconds().orElse(0.0),
            budgetCents = s.budget().map { b -> b.maxListCost().amount().toLongOrNull() }.orElse(null),
            title = s.title().orElse(null),
        )
    }

    override fun findSession(nonce: String): SessionInfo? =
        c().beta().sessions().list().autoPager().take(30).firstOrNull { s ->
            s.metadata()._additionalProperties()["zeus_nonce"]?.let { v -> map(mapOf("v" to v))["v"]?.toString() } == nonce
        }?.let { info(it) }

    private fun money(cents: Long) = BetaMonetaryAmount.builder().amount(cents.coerceAtLeast(1).toString()).currency(BetaCurrency.USD).build()

    override fun createSession(agent: AgentRef, environmentId: String, memoryStoreId: String, title: String, nonce: String, budgetCents: Long): SessionInfo = creating {
        info(
            c().beta().sessions().create(
                SessionCreateParams.builder()
                    .agent(BetaManagedAgentsAgentParams.builder().type(BetaManagedAgentsAgentParams.Type.AGENT).id(agent.id).version(agent.version).build())
                    .environmentId(environmentId)
                    .title(title)
                    .addResource(
                        BetaManagedAgentsMemoryStoreResourceParam.builder()
                            .type(BetaManagedAgentsMemoryStoreResourceParam.Type.MEMORY_STORE)
                            .memoryStoreId(memoryStoreId)
                            .access(BetaManagedAgentsMemoryStoreResourceParam.Access.READ_WRITE)
                            .instructions("Athena's long-term memory, organized by path. Read /profile/, /routines/ and /decisions/ before planning. Children write only under /jobs/<id>/.")
                            .build(),
                    )
                    // A hard dollar cap (minor units), platform-enforced before every model request.
                    .limitBudget(money(budgetCents))
                    .metadata(SessionCreateParams.Metadata.builder().putAdditionalProperty("zeus_nonce", JsonValue.from(nonce)).build())
                    .build(),
            ),
        )
    }

    override fun session(id: String): SessionInfo = info(c().beta().sessions().retrieve(id))

    override fun setSessionBudget(id: String, cents: Long) {
        c().beta().sessions().update(id, SessionUpdateParams.builder().limitBudget(money(cents)).build())
    }

    override fun threads(sessionId: String): List<ThreadInfo> = c().beta().sessions().threads().list(sessionId).autoPager().map { t ->
        val m = map(t)
        val agent = m["agent"] as? Map<*, *>
        val name = if (agent?.get("type") == "advisor") Metis.THREAD else agent?.get("name")?.toString().orEmpty()
        ThreadInfo(t.id(), name, m["status"]?.toString().orEmpty(), cents((m["usage"] as? Map<*, *>)?.get("list_cost")), !t.parentThreadId().isPresent)
    }.toList()

    // ---- events ----

    private fun time(m: Map<String, Any?>): Long = (m["processed_at"] ?: m["created_at"])?.toString()?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() } ?: 0L

    private fun text(content: Any?): String = (content as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.takeIf { b -> b["type"] == "text" }?.get("text")?.toString() }?.joinToString("").orEmpty()

    @Suppress("UNCHECKED_CAST")
    fun parse(m: Map<String, Any?>): MindEvent {
        val id = m["id"]?.toString().orEmpty()
        val at = time(m)
        fun stop() = ((m["stop_reason"] as? Map<*, *>)?.get("type") ?: m["stop_reason"])?.toString().orEmpty()
        return when (val type = m["type"]?.toString().orEmpty()) {
            "agent.message" -> MindEvent.AgentMessage(id, at, text(m["content"]))
            "agent.custom_tool_use" -> MindEvent.CustomToolUse(id, at, m["name"]?.toString().orEmpty(), (m["input"] as? Map<String, Any?>).orEmpty(), m["session_thread_id"]?.toString())
            "user.custom_tool_result" -> MindEvent.ToolResultSent(id, at, m["custom_tool_use_id"]?.toString().orEmpty())
            "user.message" -> MindEvent.UserMessage(id, at, text(m["content"]))
            "session.status_idle" -> MindEvent.Idle(id, at, stop())
            "session.status_terminated" -> MindEvent.Terminated(id, at)
            "session.thread_created" -> MindEvent.ThreadCreated(id, at, m["session_thread_id"]?.toString().orEmpty(), m["agent_name"]?.toString().orEmpty())
            "session.thread_status_idle" -> MindEvent.ThreadIdle(id, at, m["session_thread_id"]?.toString().orEmpty(), m["agent_name"]?.toString().orEmpty(), stop())
            "session.usage" -> MindEvent.Usage(id, at, cents(m["list_cost"]), (m["active_seconds"] as? Number)?.toDouble() ?: 0.0)
            "session.error" -> MindEvent.Error(id, at, ((m["error"] as? Map<*, *>)?.get("message") ?: m["message"] ?: "error").toString())
            else -> MindEvent.Other(id, at, type)
        }
    }

    override fun events(sessionId: String): List<MindEvent> =
        c().beta().sessions().events().list(sessionId).autoPager().map { ev -> parse(ev._json().map { map(it) }.orElse(emptyMap())) }.toList()

    override fun stream(sessionId: String): MindStream {
        val response = c().beta().sessions().events().streamStreaming(sessionId)
        val it = response.stream().iterator()
        return object : MindStream {
            override fun next(): MindEvent? = try {
                if (it.hasNext()) it.next().let { ev -> parse(ev._json().map { j -> map(j) }.orElse(emptyMap())) } else null
            } catch (_: Exception) { null }
            override fun close() = response.close()
        }
    }

    override fun sendMessage(sessionId: String, text: String) = creating {
        c().beta().sessions().events().send(
            sessionId,
            EventSendParams.builder().addEvent(
                BetaManagedAgentsUserMessageEventParams.builder().type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE).addTextContent(text).build(),
            ).build(),
        )
        Unit
    }

    override fun sendToolResult(sessionId: String, toolUseId: String, text: String, isError: Boolean) = creating {
        c().beta().sessions().events().send(
            sessionId,
            EventSendParams.builder().addEvent(
                BetaManagedAgentsUserCustomToolResultEventParams.builder()
                    .type(BetaManagedAgentsUserCustomToolResultEventParams.Type.USER_CUSTOM_TOOL_RESULT)
                    .customToolUseId(toolUseId)
                    .addTextContent(text)
                    .isError(isError)
                    .build(),
            ).build(),
        )
        Unit
    }

    override fun interrupt(sessionId: String, threadId: String?) {
        c().beta().sessions().events().send(
            sessionId,
            EventSendParams.builder().addEvent(
                BetaManagedAgentsUserInterruptEventParams.builder().type(BetaManagedAgentsUserInterruptEventParams.Type.USER_INTERRUPT).apply { if (threadId != null) sessionThreadId(threadId) }.build(),
            ).build(),
        )
    }

    // ---- deployments ----

    private fun dinfo(d: BetaManagedAgentsDeployment) = DeploymentInfo(
        d.id(), d.name(), d.status().asString() == "paused",
        d.schedule().flatMap { it.upcomingRunsAt() }.map { l -> l.map { it.toString() } }.orElse(emptyList()),
    )

    override fun findDeployment(name: String): DeploymentInfo? =
        c().beta().deployments().list().autoPager().firstOrNull { it.name() == name && !it.archivedAt().isPresent }?.let { dinfo(it) }

    private fun schedule(s: DeploymentSpec) = com.anthropic.models.beta.deployments.BetaManagedAgentsScheduleParams.builder()
        .type(com.anthropic.models.beta.deployments.BetaManagedAgentsScheduleParams.Type.CRON).expression(s.cron).timezone(s.zone).build()

    private fun startMessage(s: DeploymentSpec) =
        BetaManagedAgentsUserMessageEventParams.builder().type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE).addTextContent(s.message).build()

    override fun createDeployment(spec: DeploymentSpec, agent: AgentRef, environmentId: String, memoryStoreId: String, budgetCents: Long): DeploymentInfo = creating {
        dinfo(
            c().beta().deployments().create(
                DeploymentCreateParams.builder()
                    .name(spec.name)
                    .agent(BetaManagedAgentsAgentParams.builder().type(BetaManagedAgentsAgentParams.Type.AGENT).id(agent.id).version(agent.version).build())
                    .environmentId(environmentId)
                    .addMemoryStoreResource(memoryStoreId)
                    .addInitialEvent(startMessage(spec))
                    .schedule(schedule(spec))
                    .limitBudget(money(budgetCents))
                    .build(),
            ),
        )
    }

    override fun updateDeployment(id: String, spec: DeploymentSpec, agent: AgentRef, budgetCents: Long): DeploymentInfo = dinfo(
        c().beta().deployments().update(
            id,
            DeploymentUpdateParams.builder()
                .agent(BetaManagedAgentsAgentParams.builder().type(BetaManagedAgentsAgentParams.Type.AGENT).id(agent.id).version(agent.version).build())
                .addInitialEvent(startMessage(spec))
                .schedule(schedule(spec))
                .limitBudget(money(budgetCents))
                .build(),
        ),
    )

    override fun setDeploymentPaused(id: String, paused: Boolean) {
        if (paused) c().beta().deployments().pause(id) else c().beta().deployments().unpause(id)
    }

    override fun runDeployment(id: String): RunInfo = c().beta().deployments().run(id).let { r ->
        RunInfo(r.id(), r.deploymentId(), r.sessionId().orElse(null), r.createdAt().toInstant().toEpochMilli(), r.error().map { e -> map(e)["type"]?.toString() ?: "error" }.orElse(null))
    }

    override fun archiveDeployment(id: String) { c().beta().deployments().archive(id) }

    override fun runs(deploymentId: String, sinceMs: Long): List<RunInfo> =
        c().beta().deploymentRuns().list(
            DeploymentRunListParams.builder().deploymentId(deploymentId).createdAtGt(OffsetDateTime.ofInstant(Instant.ofEpochMilli(sinceMs), ZoneOffset.UTC)).build(),
        ).autoPager().take(50).map { r ->
            RunInfo(r.id(), r.deploymentId(), r.sessionId().orElse(null), r.createdAt().toInstant().toEpochMilli(), r.error().map { e -> map(e)["type"]?.toString() ?: "error" }.orElse(null))
        }.toList()

    // ---- memory store (agent-memory-2026-07-22) ----

    private fun file(m: BetaManagedAgentsMemory) = MemoryFile(m.id(), m.path(), m.contentSha256(), m.contentSizeBytes(), m.content().orElse(null))

    override fun listMemories(storeId: String, prefix: String): List<MemoryFile> =
        c().beta().memoryStores().memories().list(storeId, MemoryListParams.builder().pathPrefix(prefix).view(BetaManagedAgentsMemoryView.BASIC).build())
            .autoPager().filter { it.isMemory() }.map { file(it.asMemory()) }.toList()

    override fun readMemory(storeId: String, memoryId: String): MemoryFile =
        file(c().beta().memoryStores().memories().retrieve(memoryId, MemoryRetrieveParams.builder().memoryStoreId(storeId).build()))

    override fun createMemory(storeId: String, path: String, content: String): MemoryFile = creating {
        file(c().beta().memoryStores().memories().create(storeId, MemoryCreateParams.builder().path(path).content(content).build()))
    }

    override fun updateMemory(storeId: String, memoryId: String, content: String): MemoryFile =
        file(c().beta().memoryStores().memories().update(memoryId, MemoryUpdateParams.builder().memoryStoreId(storeId).content(content).build()))

    override fun deleteMemory(storeId: String, memoryId: String) {
        try { c().beta().memoryStores().memories().delete(memoryId, MemoryDeleteParams.builder().memoryStoreId(storeId).build()) } catch (_: NotFoundException) { }
    }

    override fun models(): List<dev.phonerecall.models.ModelEntry> = c().models().list().autoPager().take(100).map { m ->
        dev.phonerecall.models.ModelEntry(m.id(), m.displayName(), m.createdAt().toInstant().toEpochMilli(), m.deprecatedAt().isPresent)
    }.filter { it.id.startsWith("claude-") }.toList()

    // ---- Prometheus (ForgeApi): a code agent, its own limited sandbox, a vault, repository sessions ----

    /** bash and the file tools, always allowed inside a sandbox whose network is GitHub only. No web tools. */
    private fun codeToolset(): BetaManagedAgentsAgentToolset20260401Params {
        val allow = BetaManagedAgentsAlwaysAllowPolicy.builder().type(BetaManagedAgentsAlwaysAllowPolicy.Type.ALWAYS_ALLOW).build()
        return BetaManagedAgentsAgentToolset20260401Params.builder()
            .type(BetaManagedAgentsAgentToolset20260401Params.Type.AGENT_TOOLSET_20260401)
            .defaultConfig(BetaManagedAgentsAgentToolsetDefaultConfigParams.builder().enabled(false).build())
            .addConfig(BetaManagedAgentsBashToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsReadToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsWriteToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsEditToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsGlobToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsGrepToolConfigParams.builder().enabled(true).permissionPolicy(allow).build())
            .addConfig(BetaManagedAgentsWebFetchToolConfigParams.builder().enabled(false).build())
            .addConfig(BetaManagedAgentsWebSearchToolConfigParams.builder().enabled(false).build())
            .build()
    }

    override fun createCodeAgent(spec: CodeAgentSpec): AgentRef = creating {
        modelGuard(spec.model, null) {
            ref(c().beta().agents().create(
                AgentCreateParams.builder().name(spec.name).model(BetaManagedAgentsModelConfigParams.builder().id(spec.model).build())
                    .description(spec.description).system(spec.system).addTool(codeToolset()).build(),
            ))
        }
    }

    override fun updateCodeAgent(ref: AgentRef, spec: CodeAgentSpec): AgentRef = modelGuard(spec.model, null) {
        ref(c().beta().agents().update(
            ref.id,
            AgentUpdateParams.builder().version(ref.version).name(spec.name).model(BetaManagedAgentsModelConfigParams.builder().id(spec.model).build())
                .description(spec.description).system(spec.system).addTool(codeToolset()).build(),
        ))
    }

    override fun createLimitedEnvironment(name: String, hosts: List<String>): String = creating {
        c().beta().environments().create(
            EnvironmentCreateParams.builder()
                .name(name)
                .config(BetaCloudConfigParams.builder().networking(
                    BetaLimitedNetworkParams.builder().allowedHosts(hosts).allowMcpServers(false).allowPackageManagers(false).build(),
                ).build())
                .build(),
        ).id()
    }

    override fun findVault(name: String): String? =
        c().beta().vaults().list().autoPager().firstOrNull { it.displayName() == name && !it.archivedAt().isPresent }?.id()

    override fun createVault(name: String): String = creating {
        c().beta().vaults().create(VaultCreateParams.builder().displayName(name).build()).id()
    }

    /** Header only, and only on requests to `hosts`: the sandbox sees a placeholder, never the value. */
    override fun putSecret(vaultId: String, secretName: String, value: String, hosts: List<String>) {
        val items = c().beta().vaults().credentials()
        val existing = items.list(vaultId).autoPager().firstOrNull { !it.archivedAt().isPresent && map(it.auth())["secret_name"] == secretName }
        if (existing == null) creating {
            items.create(
                vaultId,
                VaultItemCreate.builder().displayName(secretName).auth(
                    EnvVarCreate.builder().type(EnvVarCreate.Type.ENVIRONMENT_VARIABLE)
                        .secretName(secretName).secretValue(value).limitedNetworking(hosts)
                        .injectionLocation(BetaManagedAgentsInjectionLocationParams.builder().header(true).body(false).build())
                        .build(),
                ).build(),
            )
        } else items.update(
            existing.id(),
            VaultItemUpdate.builder().vaultId(vaultId).auth(
                EnvVarUpdate.builder().type(EnvVarUpdate.Type.ENVIRONMENT_VARIABLE).secretValue(value).limitedNetworking(hosts).build(),
            ).build(),
        )
    }

    override fun createRepoSession(agent: AgentRef, environmentId: String, vaultId: String, repo: RepoMount, title: String, nonce: String, budgetCents: Long, firstMessage: String): SessionInfo = creating {
        info(
            c().beta().sessions().create(
                SessionCreateParams.builder()
                    .agent(BetaManagedAgentsAgentParams.builder().type(BetaManagedAgentsAgentParams.Type.AGENT).id(agent.id).version(agent.version).build())
                    .environmentId(environmentId)
                    .title(title)
                    .addResource(
                        BetaManagedAgentsGitHubRepositoryResourceParams.builder()
                            .type(BetaManagedAgentsGitHubRepositoryResourceParams.Type.GITHUB_REPOSITORY)
                            .url(repo.url).authorizationToken(repo.token).branchCheckout(repo.branch).mountPath(repo.mountPath)
                            .build(),
                    )
                    .addVaultId(vaultId)
                    .limitBudget(money(budgetCents))
                    .metadata(SessionCreateParams.Metadata.builder().putAdditionalProperty("zeus_nonce", JsonValue.from(nonce)).build())
                    .addInitialEvent(BetaManagedAgentsUserMessageEventParams.builder().type(BetaManagedAgentsUserMessageEventParams.Type.USER_MESSAGE).addTextContent(firstMessage).build())
                    .build(),
            ),
        )
    }
}
