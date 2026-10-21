package dev.phonerecall.horologion

import dev.phonerecall.lemnos.ApprovalRequest
import dev.phonerecall.lemnos.JobPolicy
import dev.phonerecall.lemnos.Origin
import dev.phonerecall.mind.Cron
import dev.phonerecall.mind.SchedJob
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Horologion: one inventory of the whole system, by owner. Jobs (with their hours), skills and hooks, and every
 * other thing that is set up: names, one-liners and values only, never secrets, prompts or file contents. What it
 * may change: Athena's own things directly (with his fingerprint), Iris's and Atlas's by a FROM OWNER request
 * carrying his approval token, which they apply and confirm themselves.
 */

enum class Owner(val label: String) { ATHENA("Athena"), IRIS("Iris"), ATLAS("Atlas"), SERVER("Server") }

enum class Tab(val label: String) { JOBS("Jobs"), SKILLS("Skills & hooks"), SETUP("Everything else") }

/** One thing that is set up. [where] is where it runs; [value] its current setting, if it has one. */
data class Item(
    val id: String,
    val owner: Owner,
    val tab: Tab,
    val group: String,
    val name: String,
    val what: String,
    val where: String,
    val value: String? = null,
    val action: Action? = null,
)

sealed class Action {
    /** One of Athena's scheduled runs: new time, pause or resume, run now. */
    data class AthenaSchedule(val job: SchedJob) : Action()
    /** A WorkManager job on the phone whose interval may change within [bounds]. */
    data class PhoneInterval(val job: PhoneJob) : Action()
    /** One of Iris's crons: a request to her (new schedule, pause, resume). */
    data class IrisCron(val job: IrisJob) : Action()
    /** Anything else of Iris's or Atlas's (a hook, a skill, a plugin): a request in words. */
    data class Ask(val owner: Owner, val thing: String, val name: String) : Action()
    /** A switch of Athena's or the phone's own that already exists; flipped here with his fingerprint. */
    data class Toggle(val key: String, val on: Boolean) : Action()
    /** One feature's model (models/Models.kt): pick from what fits, with his fingerprint; Undo puts the last one back. */
    data class Model(val pick: ModelPick) : Action()
    /** A task Athena spawned (mind/Tasks.kt) that is still running: Cancel stops its session. */
    data class CancelTask(val id: String, val title: String) : Action()
    /** A learned shortcut (shortcuts/): suggest, do it with Undo (only where allowed), or delete. */
    data class Shortcut(val id: String, val mode: dev.phonerecall.shortcuts.Mode, val canAuto: Boolean) : Action()
    /** A Hestia home routine: on or off here; devices, rooms and times in Hestia. */
    data class HomeRoutine(val id: String, val on: Boolean) : Action()
}

/** A feature's model as Horologion shows it: the choice, what it may switch to, and what Undo would restore. */
data class ModelPick(val key: String, val label: String, val current: String, val options: List<String>, val undo: String? = null, val default: String = current)

// ---- what the gateway sends (names only) ----
data class IrisJob(val id: String, val cron: String?, val status: String?, val lastRun: String?)
data class Skill(val name: String, val description: String?, val from: String)
data class Hook(val event: String?, val matcher: String?, val script: String?, val file: String)
data class Plugin(val name: String, val enabled: Boolean)
data class IrisSetup(
    val skills: List<Skill> = emptyList(), val hooks: List<Hook> = emptyList(), val plugins: List<Plugin> = emptyList(),
    val mcp: List<String> = emptyList(), val memory: List<Pair<String, Int>> = emptyList(), val briefs: List<String> = emptyList(),
    val channels: List<String> = emptyList(), val model: String? = null,
)
data class AtlasSetup(
    val version: String? = null, val model: String? = null, val notify: String? = null, val approval: String? = null, val sandbox: String? = null,
    val mcp: List<String> = emptyList(), val prompts: List<String> = emptyList(), val skills: List<String> = emptyList(),
)
data class ServerSetup(val sites: List<String> = emptyList(), val mnemosyneLast: Long? = null, val backupResult: String? = null, val backupFinished: String? = null, val units: List<String> = emptyList())

// ---- the phone's own ----
/** How far an interval may move, in minutes, and the choices the picker offers. */
data class Bounds(val min: Long, val max: Long, val choices: List<Long>) {
    fun ok(minutes: Long) = minutes in min..max
}

data class PhoneJob(
    val id: String, val name: String, val what: String, val everyMinutes: Long, val constraints: String,
    val next: Long? = null, val state: String? = null, val bounds: Bounds? = null,
)

data class Switch(val key: String, val name: String, val what: String, val on: Boolean)

/** Athena and the phone, as plain facts. */
data class AthenaFacts(
    val tools: List<Pair<String, String>> = emptyList(),
    val circle: List<Triple<String, String, String>> = emptyList(),    // name, model, role
    val models: List<Pair<String, String>> = emptyList(),               // feature, model
    val caps: List<Pair<String, String>> = emptyList(),
    val auto: List<Switch> = emptyList(),
    val hosts: List<String> = emptyList(),
    val permissions: List<Pair<String, Boolean>> = emptyList(),
    val switches: List<Switch> = emptyList(),
    val widgets: List<Pair<String, Int>> = emptyList(),
    val store: String? = null,
    val oneiroi: List<Pair<String, String>> = emptyList(),               // job, what
    val picks: List<ModelPick> = emptyList(),
    /** Her conversation session in words: "context: 12% · new session at 4 AM". */
    val session: String? = null,
    /** Tasks she spawned, newest last. */
    val tasks: List<dev.phonerecall.mind.SpawnedTask> = emptyList(),
    /** The fresh start's agent work (fresh/): name, what, where it stands. */
    val fresh: List<Triple<String, String, String>> = emptyList(),
)

object Horologion {
    const val ET = "America/New_York"
    private val WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

    fun at(ms: Long, zone: ZoneId): String = WHEN.format(Instant.ofEpochMilli(ms).atZone(zone))

    /** "Every 30 min", "Every 6 h", "Every 2 days". */
    fun every(minutes: Long): String = when {
        minutes < 60 -> "Every $minutes min"
        minutes % 1440 == 0L -> if (minutes == 1440L) "Daily" else if (minutes == 10_080L) "Weekly" else "Every ${minutes / 1440} days"
        minutes % 60 == 0L -> if (minutes == 60L) "Hourly" else "Every ${minutes / 60} h"
        else -> "Every $minutes min"
    }

    /** Athena's deployments: the schedule in words and his zone, the next and last run. */
    fun athenaJobs(jobs: List<SchedJob>, last: (String) -> Pair<Long, String>?, zone: ZoneId, now: Instant): List<Item> = jobs.map { j ->
        val next = if (j.paused) null else Cron.next(j.cron, zone, now).firstOrNull()?.let { WHEN.format(it) }
        val lastRun = last(j.name)?.let { (at, how) -> "last ${at(at, zone)} ($how)" }
        Item(
            "athena-job-${j.name}", Owner.ATHENA, Tab.JOBS, "Scheduled runs", j.full,
            if (j.builtin) j.prompt.substringBefore(". ").take(120) else j.why ?: j.prompt.take(120),
            "Anthropic (Managed Agents)",
            listOfNotNull("${Cron.words(j.cron)}, ${zone.id}", if (j.paused) "paused" else next?.let { "next $it" }, lastRun).joinToString(" · "),
            Action.AthenaSchedule(j),
        )
    }

    /** His learned shortcuts: when, then, how, and how often they fired. */
    fun shortcuts(rules: List<dev.phonerecall.shortcuts.Rule>, zone: ZoneId): List<Item> = rules.map { r ->
        Item(
            "shortcut-${r.id}", Owner.ATHENA, Tab.JOBS, "Learned shortcuts", "${r.actionLabel.replaceFirstChar { it.uppercase() }} when ${r.triggerLabel}",
            if (r.mode == dev.phonerecall.shortcuts.Mode.AUTO) "Does it on its own, with Undo" else "A one-tap card each time", "This phone",
            listOfNotNull("fired ${r.fired}x", r.lastFired?.let { "last ${at(it, zone)}" }).joinToString(" · "),
            Action.Shortcut(r.id, r.mode, dev.phonerecall.shortcuts.Learn.autoAllowed(r)),
        )
    }

    /** Hestia's home routines: what each does, on or off, and its last run. */
    fun homeRoutines(routines: List<dev.phonerecall.hestia.Routine>, describe: (dev.phonerecall.hestia.Routine) -> String, zone: ZoneId): List<Item> = routines.map { r ->
        Item(
            "home-routine-${r.id}", Owner.ATHENA, Tab.JOBS, "Home routines", r.name, describe(r), "This phone (Hestia, with Undo)",
            listOfNotNull(if (r.on) "on" else "off", "ran ${r.runs}x".takeIf { r.runs > 0 }, r.lastRun?.let { "last ${at(it, zone)}" }, r.lastWords).joinToString(" · "),
            Action.HomeRoutine(r.id, r.on),
        )
    }

    fun phoneJobs(jobs: List<PhoneJob>, zone: ZoneId): List<Item> = jobs.map { j ->
        Item(
            "phone-job-${j.id}", Owner.ATHENA, Tab.JOBS, "On the phone", j.name, j.what, "This phone (WorkManager)",
            listOfNotNull(every(j.everyMinutes), j.constraints.takeIf { it.isNotEmpty() }, j.state?.lowercase(), j.next?.let { "next ${at(it, zone)}" }).joinToString(" · "),
            j.bounds?.let { Action.PhoneInterval(j) },
        )
    }

    fun irisJobs(jobs: List<IrisJob>): List<Item> = jobs.map { j ->
        Item(
            "iris-job-${j.id}", Owner.IRIS, Tab.JOBS, "Her crons", j.id, "One of her scheduled jobs (the prompt stays on the server)", "The VPS (Iris's session)",
            listOfNotNull(j.cron?.let { "${Cron.words(it)}, Eastern" }, j.status, j.lastRun?.let { "last $it" }).joinToString(" · "),
            Action.IrisCron(j),
        )
    }

    fun iris(s: IrisSetup): List<Item> {
        val out = ArrayList<Item>()
        s.skills.forEach { k -> out += Item("iris-skill-${k.from}-${k.name}", Owner.IRIS, Tab.SKILLS, if (k.from == "plugin") "Plugin skills" else "Skills", k.name, k.description ?: "A skill", "Iris's session", null, Action.Ask(Owner.IRIS, "skill", k.name)) }
        s.hooks.forEachIndexed { i, h ->
            out += Item("iris-hook-$i", Owner.IRIS, Tab.SKILLS, "Hooks", "${h.event ?: "?"}: ${h.script ?: "command"}", "Runs on ${h.event ?: "an event"}" + (h.matcher?.takeIf { it != "*" }?.let { " for $it" } ?: ""), "Iris's session (${h.file})", null, Action.Ask(Owner.IRIS, "hook", "${h.event}: ${h.script}"))
        }
        s.plugins.forEach { p -> out += Item("iris-plugin-${p.name}", Owner.IRIS, Tab.SKILLS, "Plugins and channels", p.name, if (p.name.startsWith("zeus@")) "The zeus channel: Zeus messages in, replies out" else "A Claude Code plugin", "Iris's session", if (p.enabled) "on" else "off", Action.Ask(Owner.IRIS, "plugin", p.name)) }
        s.mcp.forEach { m -> out += Item("iris-mcp-$m", Owner.IRIS, Tab.SKILLS, "MCP servers", m, "A tool server she can call", "Iris's session", null, Action.Ask(Owner.IRIS, "mcp server", m)) }
        s.model?.let { out += Item("iris-model", Owner.IRIS, Tab.SETUP, "Model", it, "Her model", "Iris's session", it) }
        if (s.channels.isNotEmpty()) out += Item("iris-channels", Owner.IRIS, Tab.SETUP, "Channels", s.channels.joinToString(", "), "Where her messages come from", "Iris's session", "on")
        s.memory.forEach { (f, n) -> out += Item("iris-memory-$f", Owner.IRIS, Tab.SETUP, "Memory index", f, "A memory file (contents stay on the server)", "The VPS", "$n lines") }
        s.briefs.forEach { b -> out += Item("iris-brief-$b", Owner.IRIS, Tab.SETUP, "Identity and briefs", b, "Present", "The VPS") }
        return out
    }

    fun atlas(s: AtlasSetup): List<Item> {
        val out = ArrayList<Item>()
        fun fact(id: String, name: String, what: String, value: String?) { if (value != null) out += Item("atlas-$id", Owner.ATLAS, Tab.SETUP, "Codex", name, what, "The VPS (Atlas's session)", value, Action.Ask(Owner.ATLAS, "setting", name)) }
        fact("version", "Codex", "His CLI", s.version)
        fact("model", "Model", "His model", s.model)
        fact("approval", "Approval mode", "When he asks before acting", s.approval)
        fact("sandbox", "Sandbox", "What he may touch", s.sandbox)
        s.notify?.let { out += Item("atlas-notify", Owner.ATLAS, Tab.SKILLS, "Hooks", "notify: $it", "Files his answers to Zeus messages", "The VPS", null, Action.Ask(Owner.ATLAS, "hook", it)) }
        s.mcp.forEach { m -> out += Item("atlas-mcp-$m", Owner.ATLAS, Tab.SKILLS, "MCP servers", m, "A tool server he can call", "The VPS", null, Action.Ask(Owner.ATLAS, "mcp server", m)) }
        s.prompts.forEach { p -> out += Item("atlas-prompt-$p", Owner.ATLAS, Tab.SKILLS, "Prompts", p, "A saved prompt (contents stay on the server)", "The VPS") }
        s.skills.forEach { p -> out += Item("atlas-skill-$p", Owner.ATLAS, Tab.SKILLS, "Skills", p, "A skill", "The VPS", null, Action.Ask(Owner.ATLAS, "skill", p)) }
        return out
    }

    fun server(s: ServerSetup, zone: ZoneId): List<Item> {
        val out = ArrayList<Item>()
        s.sites.forEach { h -> out += Item("server-site-$h", Owner.SERVER, Tab.SETUP, "Caddy sites", h, "A site Caddy serves", "The VPS") }
        s.units.forEach { u -> out += Item("server-unit-$u", Owner.SERVER, Tab.SETUP, "Services Lemnos controls", u, "Start, stop or restart from Lemnos", "The VPS") }
        out += Item("server-mnemosyne", Owner.SERVER, Tab.SETUP, "Backups", "Mnemosyne", "This phone's encrypted nightly backup", "The storage box", s.mnemosyneLast?.let { "last ${at(it * 1000, zone)}" } ?: "none yet")
        out += Item("server-borg", Owner.SERVER, Tab.SETUP, "Backups", "Server backup", "The VPS's own backup", "The storage box", listOfNotNull(s.backupResult, s.backupFinished?.takeIf { it.isNotBlank() }).joinToString(" · ").ifEmpty { null })
        return out
    }

    fun athena(f: AthenaFacts): List<Item> {
        val out = ArrayList<Item>()
        f.session?.let { out += Item("athena-session", Owner.ATHENA, Tab.JOBS, "Her conversation", "Today's session", "Chat, batches, heartbeats and Agora all go here. A new one each day at 4 AM, seeded with what she wrote at the end of the last one and Oneiroi's facts; earlier when it grows too big", "Anthropic (Managed Agents)", it) }
        f.tasks.forEach { t ->
            out += Item(
                "athena-task-${t.id}", Owner.ATHENA, Tab.JOBS, "Tasks she spawned", t.title, t.instructions.take(140), "Anthropic (its own session)",
                listOfNotNull(t.status, "${t.who}, ${t.costCents} of ${t.budgetCents} cents", t.result?.take(120)).joinToString(" · "),
                if (t.open) Action.CancelTask(t.id, t.title) else null,
            )
        }
        f.tools.forEach { (n, d) -> out += Item("athena-tool-$n", Owner.ATHENA, Tab.SKILLS, "Her tools", n, d.substringBefore(". ").take(140), "Answered on this phone") }
        f.circle.forEach { (n, m, r) -> out += Item("athena-circle-$n", Owner.ATHENA, Tab.SKILLS, "Her circle", n, r, "Anthropic (Managed Agents)", m) }
        f.store?.let { out += Item("athena-store", Owner.ATHENA, Tab.SKILLS, "Memory", it, "Her long-term memory store", "Anthropic (Managed Agents)") }
        f.oneiroi.forEach { (n, w) -> out += Item("athena-oneiroi-$n", Owner.ATHENA, Tab.JOBS, "Oneiroi", n, w, "This phone") }
        f.fresh.forEach { (n, w, v) -> out += Item("athena-fresh-$n", Owner.ATHENA, Tab.JOBS, "Fresh start", n, w, "Anthropic (Managed Agents)", v) }
        f.picks.forEach { p -> out += Item("athena-model-${p.key}", Owner.ATHENA, Tab.SETUP, "Models", p.label, if (p.current == p.default) "The model behind it" else "The model behind it (default ${p.default})", "Anthropic", p.current, Action.Model(p)) }
        f.models.forEach { (feat, m) -> out += Item("athena-model-$feat", Owner.ATHENA, Tab.SETUP, "Models", feat, "The model behind it", "Anthropic", m) }
        f.caps.forEach { (n, v) -> out += Item("athena-cap-$n", Owner.ATHENA, Tab.SETUP, "Budgets and caps", n, "A spending limit", "This phone", v) }
        f.auto.forEach { s -> out += Item("athena-auto-${s.key}", Owner.ATHENA, Tab.SETUP, "Auto-approve", s.name, s.what, "This phone", if (s.on) "on" else "off", Action.Toggle(s.key, s.on)) }
        f.switches.forEach { s -> out += Item("athena-switch-${s.key}", Owner.ATHENA, Tab.SETUP, "Features", s.name, s.what, "This phone", if (s.on) "on" else "off", if (s.key.startsWith("info.")) null else Action.Toggle(s.key, s.on)) }
        f.hosts.forEach { h -> out += Item("athena-host-$h", Owner.ATHENA, Tab.SETUP, "NetGate allowlist", h, "A host the phone may reach", "This phone") }
        f.permissions.forEach { (p, ok) -> out += Item("athena-perm-$p", Owner.ATHENA, Tab.SETUP, "Permissions", p, "An Android permission", "This phone", if (ok) "granted" else "missing") }
        f.widgets.forEach { (w, n) -> out += Item("athena-widget-$w", Owner.ATHENA, Tab.SETUP, "Widgets placed", w, "On the home screen", "This phone", "$n placed") }
        return out
    }

    /** Case-insensitive match on name, what, group, owner, where and value; every word must match. */
    fun search(items: List<Item>, query: String): List<Item> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return items
        return items.filter { i -> val hay = listOfNotNull(i.name, i.what, i.group, i.owner.label, i.where, i.value).joinToString(" ").lowercase(); words.all { it in hay } }
    }

    /** By owner (Athena, Iris, Atlas, Server), then group, in the order they were built. */
    fun grouped(items: List<Item>): List<Pair<Owner, List<Pair<String, List<Item>>>>> =
        Owner.entries.mapNotNull { o -> items.filter { it.owner == o }.takeIf { it.isNotEmpty() }?.let { l -> o to l.groupBy { it.group }.toList() } }
}

/**
 * The owner's own request to Iris or Atlas, from Horologion: a job marked as his (by = owner) that carries his
 * fingerprint approval token. The worker verifies it, applies the change herself and confirms what changed.
 */
data class OwnerRequest(val out: JobPolicy.Outgoing) {
    val by: String get() = "owner"
}

object OwnerRequests {
    const val CRON = "iris.cron_change"
    fun setupKind(owner: Owner) = "${owner.name.lowercase()}.setup_change"

    /** "Please change your cron morning-brief to 0 7 * * * (ET)", or pause / resume. Null when the cron isn't valid. */
    fun cron(job: IrisJob, action: String, cron: String?, nonce: String): Pair<ApprovalRequest, String>? {
        val payload = linkedMapOf<String, Any?>("id" to job.id, "action" to action, "zone" to Horologion.ET)
        val title = when (action) {
            "change" -> { if (cron == null || Cron.problem(cron) != null) return null; payload["cron"] = cron.trim(); "Please change your cron ${job.id} to ${cron.trim()} (ET), ${Cron.words(cron)}" }
            "pause" -> "Please pause your cron ${job.id}"
            "resume" -> "Please resume your cron ${job.id}"
            else -> return null
        }
        payload["text"] = title
        return ApprovalRequest("cron-${job.id}-$nonce".take(80).replace(Regex("[^A-Za-z0-9_.:-]"), "-"), CRON, "iris", payload) to title
    }

    /** "Please turn off your Stop hook iris_stop.py", in his words when he gives some. */
    fun setup(owner: Owner, thing: String, name: String, action: String, note: String?, nonce: String): Pair<ApprovalRequest, String>? {
        if (owner != Owner.IRIS && owner != Owner.ATLAS) return null
        val verb = when (action) { "enable" -> "turn on"; "disable" -> "turn off"; "change" -> "change"; else -> return null }
        val title = "Please $verb your $thing $name" + (note?.trim()?.takeIf { it.isNotEmpty() }?.let { ": ${it.take(300)}" } ?: "")
        val payload = linkedMapOf<String, Any?>("thing" to thing, "name" to name, "action" to action, "text" to title)
        return ApprovalRequest("setup-${owner.name.lowercase()}-$nonce".take(80), setupKind(owner), owner.name.lowercase(), payload) to title
    }

    /** What goes to the gateway once his fingerprint signed it. */
    fun outgoing(req: ApprovalRequest, title: String, token: String): OwnerRequest = OwnerRequest(JobPolicy.outgoing(req, title, Origin.OWNER, token))
}
