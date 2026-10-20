package dev.phonerecall.mind

import dev.phonerecall.brain.BlockedHostException
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * Every request the mind sends is built for real and stopped at NetGate (the origin is not allowed),
 * so a missing required field shows up here as IllegalStateException instead of on the phone.
 */
class SdkMindApiTest {
    private val api = SdkMindApi({ "sk-ant-test" }, origin = "https://blocked.invalid")

    private fun blocked(what: String, call: () -> Any?) {
        val e = runCatching { call() }.exceptionOrNull()
        assertTrue("$what: ${e?.javaClass?.simpleName}: ${e?.message}", generateSequence(e) { it.cause }.any { it is BlockedHostException })
    }

    @Test fun everyRequestBuildsAndReachesTheGate() {
        val r = Rig()
        val agent = AgentRef("agent_1", 1)
        val specs = Protege.entries.map { r.mind.protegeSpec(it) } + r.mind.athenaSpec(listOf(agent), withMetis = true)
        for (s in specs) {
            blocked("create ${s.name}") { api.createAgent(s) }
            blocked("update ${s.name}") { api.updateAgent(agent, s) }
        }
        blocked("environment") { api.createEnvironment("env") }
        blocked("store") { api.createMemoryStore("store", "desc") }
        blocked("session") { api.createSession(agent, "env_1", "store_1", "Athena", "nonce", 500) }
        blocked("budget") { api.setSessionBudget("sesn_1", 500) }
        blocked("message") { api.sendMessage("sesn_1", "hi") }
        blocked("tool result") { api.sendToolResult("sesn_1", "tu_1", "ok", false) }
        blocked("interrupt") { api.interrupt("sesn_1", "th_1") }
        for (d in Schedules.specs(ZoneId.of("America/New_York"))) {
            blocked("deploy ${d.name}") { api.createDeployment(d, agent, "env_1", "store_1", 150) }
            blocked("redeploy ${d.name}") { api.updateDeployment("dep_1", d, agent, 150) }
        }
        blocked("runs") { api.runs("dep_1", 0) }
        blocked("memories") { api.listMemories("store_1", "/") }
        blocked("memory read") { api.readMemory("store_1", "mem_1") }
        blocked("memory create") { api.createMemory("store_1", "/a.md", "x") }
        blocked("memory update") { api.updateMemory("store_1", "mem_1", "x") }
    }
}
