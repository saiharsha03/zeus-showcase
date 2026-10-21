package dev.phonerecall.fresh

import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.MindApi
import dev.phonerecall.mind.MindPrompts
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.Schedules
import dev.phonerecall.mind.long
import dev.phonerecall.mind.putLong

/**
 * Athena's cloud side of the fresh start, once, on her first sync after the wipe ([dev.phonerecall.mind.Mind.ensure]
 * calls it before anything else, so her new session starts on it): every session of hers and her circle's is archived
 * (read-only, not deleted: tasks, deployment runs and the old daily sessions), her own schedules ("Zeus · " ones she
 * made) are archived and the built-ins unpaused (ensure then sets them back to the book's hours), and her memory store
 * is emptied. The agents, the environment and the store stay. Agora's history on the server is not touched. No handoff:
 * the phone's copy went with the wipe.
 */
class FreshCloud(private val api: MindApi, private val kv: Kv, private val log: (String, String) -> Unit = { _, _ -> }) {
    data class Done(val sessions: Int, val deployments: Int, val memories: Int)

    /**
     * Runs while due. A failure throws (the sync fails and tries again) up to [MAX_TRIES]; then it gives up with a log
     * line rather than keep Athena from ever starting.
     */
    fun run(): Done? {
        if (!due(kv)) return null
        return try {
            reset().also {
                kv.put(KEY, null); kv.put(TRIES, null)
                log("fresh start", "Archived ${it.sessions} sessions and ${it.deployments} of her schedules; cleared ${it.memories} memory files")
            }
        } catch (e: Exception) {
            val n = (kv.long(TRIES) ?: 0) + 1
            if (n < MAX_TRIES) { kv.putLong(TRIES, n); throw e }
            kv.put(KEY, null); kv.put(TRIES, null)
            log("fresh start incomplete", "Gave up after $n tries: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun reset(): Done {
        var sessions = 0
        for (name in listOf(MindPrompts.ATHENA_NAME) + Protege.entries.map { it.agentName }) {
            val id = kv.get("id.agent.$name")?.substringBefore('@') ?: api.findAgent(name)?.id ?: continue
            for (s in api.sessionsOf(id)) {
                if (s.status == "running") runCatching { api.interrupt(s.id, null) }
                api.archiveSession(s.id)
                sessions++
            }
        }
        val builtin = Schedules.ALL.map { Schedules.PREFIX + it.kind }.toSet()
        var deployments = 0
        for (d in api.deploymentsNamed(Schedules.PREFIX)) {
            if (d.name in builtin) { if (d.paused) api.setDeploymentPaused(d.id, false) } else { api.archiveDeployment(d.id); deployments++ }
        }
        var memories = 0
        val store = kv.get("id.store") ?: api.findMemoryStore(MindPrompts.STORE_NAME)
        if (store != null) for (m in api.listMemories(store, "/")) { api.deleteMemory(store, m.id); memories++ }
        return Done(sessions, deployments, memories)
    }

    companion object {
        const val KEY = "fresh.cloud"
        const val PENDING = "pending"
        const val MAX_TRIES = 5
        private const val TRIES = "fresh.cloud.tries"

        fun due(kv: Kv) = kv.get(KEY) != null
    }
}
