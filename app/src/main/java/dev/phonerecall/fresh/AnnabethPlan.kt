package dev.phonerecall.fresh

import dev.phonerecall.mind.AgentRef
import dev.phonerecall.mind.MindApi
import dev.phonerecall.mind.MindEvent

/**
 * Annabeth writes the fresh start's interview: one session of her own (her agent, on Fable for this run), one message,
 * her JSON back. She is told not to use tools; a tool call she makes anyway is answered with a plain no, so the session
 * never hangs on the phone. At most [limitMs], under [budgetCents]; the session is archived after.
 */
class AnnabethPlan(
    private val api: MindApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    data class Reply(val sessionId: String, val text: String?, val why: String?)

    fun ask(agent: AgentRef, environmentId: String, storeId: String, prompt: String, budgetCents: Long = BUDGET_CENTS, limitMs: Long = LIMIT_MS): Reply {
        val nonce = java.util.UUID.randomUUID().toString()
        val s = api.createSession(agent, environmentId, storeId, "Genesis · Annabeth plans the interview", nonce, budgetCents)
        try {
            api.sendMessage(s.id, prompt)
            val until = clock() + limitMs
            var answered = HashSet<String>()
            while (clock() < until) {
                val events = api.events(s.id)
                val results = events.filterIsInstance<MindEvent.ToolResultSent>().map { it.toolUseId }.toSet()
                for (u in events.filterIsInstance<MindEvent.CustomToolUse>()) if (u.id !in results && answered.add(u.id))
                    api.sendToolResult(s.id, u.id, "Not available while planning. Answer from the brief alone, with only the JSON.", true)
                val idle = events.lastOrNull { it is MindEvent.Idle } as? MindEvent.Idle
                val last = events.lastOrNull()
                if (last is MindEvent.Terminated) return Reply(s.id, null, "her session ended")
                if (idle != null && last === idle && idle.stopReason != "requires_action") {
                    val text = events.filterIsInstance<MindEvent.AgentMessage>().lastOrNull()?.text
                    return Reply(s.id, text, if (idle.stopReason == "end_turn") null else idle.stopReason)
                }
                sleep(POLL_MS)
            }
            return Reply(s.id, null, "no answer in ${limitMs / 1000} s")
        } finally {
            runCatching { api.archiveSession(s.id) }
        }
    }

    companion object {
        /** Fable at high effort writing ~2k tokens of JSON from a ~3k-token brief: well under this. */
        const val BUDGET_CENTS = 60L
        const val LIMIT_MS = 240_000L
        const val POLL_MS = 3_000L
    }
}
