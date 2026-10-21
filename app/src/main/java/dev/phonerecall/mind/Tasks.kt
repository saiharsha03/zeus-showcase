package dev.phonerecall.mind

/*
 * Spawned tasks: Athena's long work, each in its own Managed Agents session run by one of her circle. Her own
 * session stays free (he can talk to her any time) and small (only the task's short result comes back to her).
 * The phone answers a task's tool calls on every wake, posts its result to his chat once, and keeps the list
 * that Horologion shows with a Cancel.
 */

/** status: starting (no session yet), running, done, failed, cancelled. Costs and budgets in cents. */
data class SpawnedTask(
    val id: String = "",
    val title: String = "",
    val instructions: String = "",
    /** The tools it may use; empty means every read tool her circle has. */
    val tools: List<String> = emptyList(),
    val who: String = Protege.ODYSSEUS.display,
    val budgetCents: Long = Tasks.DEFAULT_CENTS,
    /** What she asked for when it was more than the default: a Fates card asks him to allow it. */
    val askedCents: Long = 0,
    val status: String = "starting",
    val session: String? = null,
    val nonce: String = "",
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    val result: String? = null,
    val costCents: Long = 0,
    /** The result went to his chat and to her (once). */
    val posted: Boolean = false,
) {
    val open: Boolean get() = status == "starting" || status == "running"
}

object Tasks {
    /** At most this many run at once. */
    const val MAX_RUNNING = 3
    /** A task's budget unless he allows more on a Fates card. */
    const val DEFAULT_CENTS = 25L
    /** The most a card may allow for one task. */
    const val MAX_CENTS = 200L
    /** A task paused at its budget waits this long for his answer to the card, then ends. */
    const val BUDGET_WAIT_MS = 24 * 3_600_000L

    /** The task's first message. Tagged with its id, so a retry never sends it twice. */
    fun message(t: SpawnedTask): String = """
[Zeus] TASK ${t.id} from Athena [${t.id}]: ${t.title}
${t.instructions}

Tools you may use: ${if (t.tools.isEmpty()) "your read tools" else t.tools.joinToString(", ")}. You work on your own; nobody is watching this session. When you are done, reply with the result in at most six short sentences: it goes to the owner's chat as you write it, and to Athena. If the instructions name a LONG JOB id, also call report_job with it. You can't propose or change anything: say what should happen and Athena decides.
""".trim()

    /** One line per open task for Horologion and the snapshot. */
    fun line(t: SpawnedTask): String = "${t.title} (${t.who}, ${t.costCents} of ${t.budgetCents} cents)"
}
