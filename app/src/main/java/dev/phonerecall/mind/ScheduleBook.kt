package dev.phonerecall.mind

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.type.TypeReference

/**
 * One of Athena's scheduled runs (a Managed Agents deployment named "Zeus · <name>"). The built-in ones
 * ([Schedules.ALL]) keep their prompts; only their hour and pause can change. Created ones carry their own prompt.
 */
data class SchedJob(
    val name: String = "",
    val cron: String = "",
    val prompt: String = "",
    val paused: Boolean = false,
    val builtin: Boolean = false,
    val why: String? = null,
) {
    @get:JsonIgnore val full: String get() = Schedules.PREFIX + name
}

/** What propose_schedule (and Horologion) may ask for. */
data class SchedChange(val action: String, val name: String, val cron: String? = null, val prompt: String? = null, val why: String? = null, val trial: Boolean = false)

/**
 * The phone's record of Athena's schedules, the source [Mind.ensure] deploys from. Edits land here first (a
 * confirmed proposal, or the owner in Horologion); the next sync applies them: new and changed ones are
 * deployed, deleted ones archived, pauses set, trial runs started. Only "Zeus · " deployments are hers.
 */
class ScheduleBook(private val kv: Kv) {
    private val type = object : TypeReference<List<SchedJob>>() {}

    fun created(): List<SchedJob> = kv.list(USER, type)

    fun jobs(): List<SchedJob> = Schedules.ALL.map { e ->
        val o = kv.get("$OVER${e.kind}")?.split('|')
        SchedJob(e.kind, o?.getOrNull(0)?.takeIf { Cron.valid(it) } ?: e.cron, e.what, paused = o?.getOrNull(1) == "1", builtin = true)
    } + created()

    fun job(name: String): SchedJob? = jobs().firstOrNull { it.name == name }

    /** Names of deleted ones still to archive on the server, and of trial runs still to start. */
    fun pendingDeletes(): Set<String> = kv.get(DELETES)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    fun pendingTrials(): Set<String> = kv.get(TRIALS)?.split(',')?.filter { it.isNotEmpty() }?.toSet().orEmpty()
    fun archived(name: String) = kv.put(DELETES, (pendingDeletes() - name).joinToString(",").ifEmpty { null })
    fun trialStarted(name: String) = kv.put(TRIALS, (pendingTrials() - name).joinToString(",").ifEmpty { null })
    fun requestTrial(name: String) = kv.put(TRIALS, (pendingTrials() + name).joinToString(","))

    /** A trial's output goes to Agora only (a handoff's test run), until its first outbox file arrives. */
    fun trialRunning(name: String): Boolean = kv.get("$TRIAL_AT$name") != null
    fun markTrial(name: String, at: Long) = kv.put("$TRIAL_AT$name", "$at")
    fun trialDone(name: String) = kv.put("$TRIAL_AT$name", null)

    /** Checks [c] against the rules and what exists now; returns why not, or null when it may go. */
    fun problem(c: SchedChange): String? = check(c, jobs())

    /** Applies a checked change. Throws IllegalArgumentException when it no longer fits. Returns the job as it now is. */
    fun apply(c: SchedChange): SchedJob {
        problem(c)?.let { throw IllegalArgumentException(it) }
        val name = bare(c.name)
        val cur = job(name)
        val out: SchedJob
        if (cur != null && cur.builtin) {
            out = when (c.action) {
                "update" -> cur.copy(cron = c.cron!!.trim())
                "pause" -> cur.copy(paused = true)
                else -> cur.copy(paused = false)
            }
            kv.put("$OVER$name", "${out.cron}|${if (out.paused) "1" else "0"}")
        } else {
            val list = created().toMutableList()
            out = when (c.action) {
                "create" -> SchedJob(name, c.cron!!.trim(), c.prompt!!.trim(), why = c.why?.take(200)).also { list += it }
                "delete" -> cur!!.also { list.removeAll { j -> j.name == name }; kv.put(DELETES, (pendingDeletes() + name).joinToString(",")) }
                else -> {
                    val next = when (c.action) {
                        "update" -> cur!!.copy(cron = c.cron?.trim() ?: cur.cron, prompt = c.prompt?.trim()?.takeIf { it.isNotEmpty() } ?: cur.prompt, why = c.why?.take(200) ?: cur.why)
                        "pause" -> cur!!.copy(paused = true)
                        else -> cur!!.copy(paused = false)
                    }
                    list[list.indexOfFirst { it.name == name }] = next
                    next
                }
            }
            kv.putList(USER, list)
        }
        if (c.trial && c.action in setOf("create", "update", "resume")) requestTrial(name)
        return out
    }

    companion object {
        const val CAP = 12
        val ACTIONS = setOf("create", "update", "pause", "resume", "delete")
        private const val USER = "sched.user"
        private const val OVER = "sched.over."
        private const val DELETES = "sched.deletes"
        private const val TRIALS = "sched.trials"
        private const val TRIAL_AT = "sched.trial."
        private val NAME = Regex("^[a-z0-9][a-z0-9-]{1,39}$")

        /** "Zeus · weekly-review" or "weekly-review" both name weekly-review. */
        fun bare(name: String) = name.trim().removePrefix(Schedules.PREFIX).trim()

        /** Pure: the rules for one change, against the jobs that exist. */
        fun check(c: SchedChange, jobs: List<SchedJob>): String? {
            if (c.action !in ACTIONS) return "action must be one of ${ACTIONS.joinToString(", ")}"
            val raw = c.name.trim()
            if (" · " in raw && !raw.startsWith(Schedules.PREFIX)) return "Only Athena's own schedules (\"${Schedules.PREFIX}...\") can be changed"
            val name = bare(raw)
            if (!NAME.matches(name)) return "name must be a short lowercase slug, like weekly-review"
            val cur = jobs.firstOrNull { it.name == name }
            if (c.action != "create" && cur == null) return "$name isn't one of Athena's schedules"
            if (c.action == "create" && cur != null) return "$name already exists; update it instead"
            if (c.action == "create" && jobs.count { !it.builtin } >= CAP) return "At most $CAP schedules of her own; delete one first"
            if (c.action == "delete" && cur?.builtin == true) return "$name is built in; pause it instead"
            if (c.action == "update" && cur?.builtin == true && !c.prompt.isNullOrBlank()) return "A built-in schedule keeps its prompt; only its time changes"
            if (c.action == "create" || (c.action == "update" && c.cron != null)) {
                val cron = c.cron?.trim() ?: return "schedule (cron) is missing"
                Cron.problem(cron)?.let { return it }
                if (Cron.inDstWindow(cron)) return "Pick an hour outside 1-3 AM (daylight saving skips or repeats it)"
            }
            if (c.action == "update" && c.cron == null && c.prompt.isNullOrBlank()) return "Nothing to change"
            if (c.action == "create" && c.prompt.isNullOrBlank()) return "prompt is missing: what each run should do"
            if ((c.prompt?.length ?: 0) > 2000) return "Keep the prompt under 2000 characters"
            return null
        }
    }
}
