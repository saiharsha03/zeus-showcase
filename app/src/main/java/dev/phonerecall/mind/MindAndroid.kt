package dev.phonerecall.mind

import android.content.Context
import android.os.BatteryManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainReads
import dev.phonerecall.brain.Different
import dev.phonerecall.brain.CalendarChoice
import dev.phonerecall.brain.DelphiExtras
import dev.phonerecall.brain.Nano
import dev.phonerecall.brain.NanoModel
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.SpendCapReached
import dev.phonerecall.brain.ToolBox
import dev.phonerecall.data.Availability
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalendarPipeline
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.CalendarSource
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.ClosetSource
import dev.phonerecall.data.EngagementStore
import dev.phonerecall.data.RelevanceStore
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.data.TaskList
import dev.phonerecall.home.ScoredCard
import dev.phonerecall.memory.Alexandria
import dev.phonerecall.memory.GemmaEmbedder
import dev.phonerecall.memory.Indexer
import dev.phonerecall.memory.LexicalReranker
import dev.phonerecall.memory.NanoReranker
import dev.phonerecall.memory.SqliteIndex
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Availability to the mind (PLAN "Availability is shared knowledge"): day types only, never event details.
 * The next 15-minute batch carries it to the session, so Iris, Atlas and the schedules plan with it, and
 * read_availability already answers from the same model.
 */
class MindAvailability(private val core: BrainCore) : dev.phonerecall.data.AvailabilityPublisher {
    override fun publish(days: List<Availability>) {
        if (days.isEmpty()) return
        core.mindHost.offer(MindNote(System.currentTimeMillis(), "Availability changed on the phone: " + days.joinToString("; ") { "${it.date}: ${it.type.label}" + (it.reason?.let { r -> " ($r)" } ?: "") }, key = "availability"))
    }

    companion object {
        /** "WFH today?" > Yes: today becomes a remote day everywhere availability is read. */
        fun remote(app: Context, core: BrainCore, day: LocalDate) {
            dev.phonerecall.data.DayOverrides.set(app, day, dev.phonerecall.data.DayType.REMOTE)
            MindAvailability(core).publish(listOf(Availability(day, dev.phonerecall.data.DayType.REMOTE, "working from home")))
            core.audit.log("The owner", "availability", "$day: remote day (WFH today? > Yes)")
            core.changed()
        }
    }
}

/** The read tools' view of the phone when no screen is open: the same sources the ViewModel reads, read on demand. */
class BackgroundReads(private val app: Context, private val core: BrainCore) : BrainReads {
    override fun now(): ZonedDateTime = ZonedDateTime.now()
    private fun cals() = CalendarSource(app).calendars().associateBy { it.id }
    override fun relevantEvents(from: Long, to: Long): List<CalItem> {
        val src = CalendarSource(app)
        if (!src.allowed()) return emptyList()
        val c = cals()
        return CalendarPipeline.curate(CalendarSnapshot(true, src.readRange(from, to, c), true), c, RelevanceStore(app).load(), BirthdaySource(app).read()).snapshot.events.filter { it.end > from && it.begin < to }
    }
    override fun allEvents(from: Long, to: Long): List<CalItem> = CalendarSource(app).let { if (it.allowed()) it.readRange(from, to, cals()) else emptyList() }
    override fun tasks(): TaskSnapshot = core.tasks.load()
    override fun closet(): ClosetSnapshot = ClosetSource(app).load()
    override fun homeCards(): List<ScoredCard> = emptyList()
    override fun availability(): Map<LocalDate, Availability> {
        val src = CalendarSource(app)
        if (!src.allowed()) return emptyMap()
        val c = cals()
        dev.phonerecall.data.DayOverrides.load(app)
        return CalendarPipeline.curate(src.read(c), c, RelevanceStore(app).load(), BirthdaySource(app).read()).availability
    }
    override fun writableCalendars(): List<CalendarChoice> = dev.phonerecall.brain.AndroidLocalState.writable(app)
}

/**
 * The mind on the phone: Mind (Managed Agents), Alexandria (memory search), and what they read and
 * write here. Every network call happens on a worker or IO thread, one at a time.
 */
class MindHost(private val app: Context, private val core: BrainCore) : MindSink, MindContext, MindLedger, MindExtras, DelphiExtras {
    val kv: Kv = core.mindKv
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val reads = BackgroundReads(app, core)
    val alexandria = Alexandria(SqliteIndex(app)) { GemmaEmbedder.get(app) }
    val indexer = Indexer(app, core, alexandria)
    private val indexing = java.util.concurrent.atomic.AtomicBoolean(false)
    val indexingNow: Boolean get() = indexing.get()

    /** Re-reads every source, then embeds up to `embed` passages. Records when and how it went for Alexandria's page. */
    fun index(embed: Int) {
        if (!indexing.compareAndSet(false, true)) return
        core.changed()
        try {
            val r = runCatching {
                indexer.reindex()
                var left = embed
                while (left > 0) { val n = alexandria.embedPending(minOf(left, 100)); if (n == 0) break; left -= n }
            }
            kv.put("alexandria.last", "${System.currentTimeMillis()}")
            kv.put("alexandria.error", r.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
            r.exceptionOrNull()?.let { dev.phonerecall.clio.Clio.error("alexandria", it) }
        } finally { indexing.set(false) }
    }
    private val tools = MindTools(ToolBox(reads, core.fates, alarms = dev.phonerecall.brain.AlarmWatch.get(app), trips = dev.phonerecall.argo.ArgoTools(app)), this, dev.phonerecall.vault.VaultCore.get(app).tools) { Sanitizer(names(), sealed()) }
    val mind = Mind(
        SdkMindApi({ core.vault.read() }), kv, tools, this, this, this,
        extraFiles = { dev.phonerecall.theogony.TheogonyEngine.savedFiles(kv) },
        proactive = { dev.phonerecall.theogony.TheogonyHub.get(app).proactiveReady() },
    )
    private val lock = Any()

    fun <T> locked(block: Mind.() -> T): T = synchronized(lock) { block(mind) }

    /** A derived event for the next batch. High ones go now (one worker, network required). */
    fun offer(n: MindNote) {
        if (!core.vault.has()) return
        val now = synchronized(lock) { HeartbeatNotes.add(kv, n.copy(at = if (n.at == 0L) System.currentTimeMillis() else n.at)); mind.batcher.offer(n) }
        MindWork.flushSoon(app, if (now) 0 else mind.batcher.delay())
    }

    fun metisAllowed(): Boolean = synchronized(lock) { TopTierCaps.allowed(mind.circleCents(Metis.THREAD), mind.circleCents(Protege.ANNABETH.agentName)).metis } && kv.get("metis.unavailable") == null

    fun addAdvisorSpend(micros: Long) = synchronized(lock) { mind.addCircle(Metis.THREAD, (micros + 9_999) / 10_000) }

    // ---- MindSink ----

    override fun card(title: String, body: String, hours: Int) {
        val now = System.currentTimeMillis()
        core.db.addCard(title, body, now, now + hours * 3_600_000L)
        core.changed()
    }

    override fun propose(title: String?, steps: List<RawStep>, proposer: String): String = when (val r = core.fates.propose(steps, title, null, proposer)) {
        is ProposeResult.Created -> "Proposal #${r.proposal.id}: ${r.proposal.title}"
        is ProposeResult.Invalid -> "Not proposed: ${r.reason}"
    }

    override fun log(actor: String, action: String, detail: String) = core.audit.log(actor, action, detail)
    override fun changed() = core.changed()

    override fun briefLine(line: String) {
        dev.phonerecall.brief.BriefStore.setLede(app, java.time.LocalDate.now(zone), line)
        runCatching { dev.phonerecall.brief.BriefCore.refresh(app) }
    }

    // ---- MindLedger ----

    override fun check() = core.spend.check()
    override fun capCents(): Long = core.spend.state.value.capUsd * 100L
    override fun spentCents(): Long = core.spend.state.value.current(dev.phonerecall.brain.SpendState.thisMonth()).spentMicros / 10_000
    override fun recordMind(cents: Long, activeSeconds: Long) = core.spend.recordMind(cents, activeSeconds)

    // ---- MindContext (derived text only; the sanitizer runs again before upload) ----

    override fun zone(): ZoneId = zone

    override fun plans(): String = runCatching { dev.phonerecall.argo.Argo.plans(app) }.getOrDefault("")

    // ---- investigations (Investigate.kt) ----

    /** During a shakedown her ask belongs to the test, so the answer is checked there and never resumes the session. */
    override fun askWorker(worker: String, topic: String, question: String): String =
        AskHub.held(app, worker.lowercase().trim())?.let { "Not asked: $it" } ?: AskHub.get(app).ask(worker.lowercase().trim(), topic, question, owner = if (TestMode.probe != null) dev.phonerecall.setup.Shakedown.ASK_OWNER else "mind").fold(
            { "Asked ${it.worker.replaceFirstChar { c -> c.uppercase() }} (ask ${it.id}). The answer arrives as a note in this session; after 30 minutes without one you get a note saying so. Carry on meanwhile." },
            { "Not asked: ${it.message}" },
        )

    override fun schedules(): String = synchronized(lock) {
        val z = ZoneId.of(Schedules.zone(zone))
        val ids = mind.deploymentIds()
        mind.book.jobs().joinToString("\n") { j ->
            val next = Cron.next(j.cron, z, java.time.Instant.now()).firstOrNull()?.let { DateTimeFormatter.ofPattern("EEE MMM d h:mm a", Locale.US).format(it) }
            val last = mind.lastRun(j.name)?.let { (at, how) -> "${DateTimeFormatter.ofPattern("EEE MMM d h:mm a", Locale.US).format(java.time.Instant.ofEpochMilli(at).atZone(z))} ($how)" }
            "- ${j.full}: ${Cron.words(j.cron)} [${j.cron}] ${z.id}" + (if (j.paused) ", paused" else "") + (if (j.builtin) ", built in" else ", yours") +
                (if (j.name !in ids) ", not deployed yet" else "") + (next?.let { ", next $it" } ?: "") + (last?.let { ", last run $it" } ?: ", no run seen yet")
        }
    }

    /** The phone's own posts (trial summaries): the same guard as post_agora, logged and skipped when it refuses. */
    override fun agora(text: String) {
        AskGuard.refuse(text)?.let { core.audit.log("Athena", "Agora post skipped", it); return }
        if (!dev.phonerecall.lemnos.DeviceKeys.paired(app)) return
        runCatching { dev.phonerecall.lemnos.Gateway.sendAsAthena("agora", text.take(4000), "athena-${java.util.UUID.randomUUID()}") }
            .onFailure { core.audit.log("Athena", "Agora post failed", it.message ?: "") }
    }

    /** Phone-private data (health, amounts, addresses, numbers) stays on the phone, the same rule as ask_worker. */
    override fun postAgora(text: String, room: String): String {
        AskGuard.refuse(text)?.let { return "Not posted: $it" }
        if (!dev.phonerecall.lemnos.DeviceKeys.paired(app)) return "Not posted: Lemnos isn't paired on this phone."
        return try {
            val m = dev.phonerecall.lemnos.Gateway.sendAsAthena(room, text.trim(), "athena-${java.util.UUID.randomUUID()}")
            core.audit.log("Athena", if (room == "group") "posted in the group" else "posted in Agora", text.take(120))
            if (m.kind == "held") "Posted in Agora, but agent-to-agent is paused until the owner is back or 30 minutes pass; he will see it." else "Posted (#${m.id})."
        } catch (e: dev.phonerecall.lemnos.GatewayException) {
            "Not posted: ${e.message}"
        }
    }

    override fun recordFinding(input: Map<String, Any?>): String = runCatching { dev.phonerecall.argo.Argo.finding(app, input) }.getOrElse { "Not recorded: ${it.message}" }

    override fun proposeTrips(input: Map<String, Any?>): String = runCatching { dev.phonerecall.argo.Argo.proposeTrips(app, input) }.getOrElse { "Not proposed: ${it.message}" }

    private val dayFmt = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val timeFmt = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.US)

    override fun snapshot(): String = buildString {
        val now = ZonedDateTime.now(zone)
        append("# Snapshot from the phone\nAs of ${timeFmt.format(now)} (${zone.id}).\n${dev.phonerecall.brain.TwoClocks(zone).contextLine(now.toInstant())}\n\n## Different from usual (derived on the phone; empty means nothing differs)\n${Different.current(now).joinToString("\n") { "- $it" }.ifEmpty { "- nothing" }}\n\n## Availability\n")
        runCatching { reads.availability() }.getOrDefault(emptyMap()).toSortedMap().values.take(7).forEach { append("- ${dayFmt.format(it.date)}: ${it.type.label}${it.reason?.let { r -> " ($r)" } ?: ""}\n") }
        append("\n## Calendar, next 48 hours (titles and times only)\n")
        val from = now.toInstant().toEpochMilli()
        runCatching { reads.relevantEvents(from, from + 48 * 3_600_000L) }.getOrDefault(emptyList()).take(25).forEach { e ->
            append("- ${timeFmt.format(Instant.ofEpochMilli(e.begin).atZone(zone))}: ${e.title}${if (e.allDay) " (all day)" else ""}\n")
        }
        append("\n## Open tasks\n")
        val t = core.tasks.load()
        t.open().filter { !it.sample }.take(30).forEach { task -> append("- ${task.title} [${task.list.label}]${task.due?.let { d -> ", due " + dayFmt.format(Instant.ofEpochMilli(d).atZone(zone)) } ?: ""}\n") }
        append("\n## Lists\n")
        core.sections.visible().forEach { s ->
            val items = core.store.items(s.id).filter { !it.done && !it.lethe }.map { it.text }
            append("- ${s.name}: ${if (items.isEmpty()) "empty" else items.take(12).joinToString(", ")}\n")
        }
        // Yesterday's screen time, in words only (no per-app minutes, no times). Sensitive apps are never named.
        dev.phonerecall.brief.BriefStore.screen(app)?.takeIf { it.date == now.toLocalDate().minusDays(1).toString() }?.let {
            append("\n## Yesterday on the phone\n- ${dev.phonerecall.brief.ScreenTime.words(it)}\n")
        }
        val jobs = mind.jobs().filter { it.status !in setOf("done", "failed", "cancelled") }
        if (jobs.isNotEmpty()) { append("\n## Long jobs open\n"); jobs.forEach { append("- ${it.id}: ${it.title} (${it.status})\n") } }
    }

    override fun decisions(): String = buildString {
        append("# Decisions the owner made on proposals (newest first). Do not re-propose what he rejected.\n")
        core.db.all().filter { it.status in setOf(ProposalStatus.CONFIRMED, ProposalStatus.REJECTED, ProposalStatus.UNDONE) && it.decidedAt != null }.take(60).forEach { p ->
            val word = when (p.status) { ProposalStatus.CONFIRMED -> if (p.auto) "auto-approved" else "confirmed"; ProposalStatus.REJECTED -> "rejected"; else -> "undid" }
            append("- ${dayFmt.format(Instant.ofEpochMilli(p.decidedAt!!).atZone(zone))}: $word \"${p.title}\" (${p.steps.joinToString(", ") { it.kind.label }}, from ${p.proposer})\n")
        }
    }

    override fun people(): String = buildString {
        append("# People he hears from, as labels only\n")
        core.store.people().filter { !it.calypso }.groupBy { it.label to it.tier.label }.forEach { (k, v) -> append("- ${k.first}: ${k.second}${if (v.size > 1) " (${v.size} people)" else ""}\n") }
    }

    override fun routines(): String = buildString {
        append("# Routines\n- Office Tuesday to Thursday, remote Monday and Friday; calendar entries win.\n- Quiet hours 11 PM to 7 AM.\n")
        val av = runCatching { reads.availability() }.getOrDefault(emptyMap())
        av.values.filter { it.type == dev.phonerecall.data.DayType.OOO }.forEach { append("- Out of office on ${dayFmt.format(it.date)}.\n") }
    }

    override fun preferences(): String = buildString {
        append("# Preferences the phone has seen\n")
        val auto = core.prefs.load().autoApprove
        append("- Auto-approve: ${if (auto.isEmpty()) "nothing (he confirms every proposal)" else auto.joinToString(", ") { it.label }}.\n")
        runCatching { RelevanceStore(app).load().rules }.getOrDefault(emptyList()).take(20).forEach { r ->
            append("- Calendar rule: ${if (r.action == dev.phonerecall.data.RuleAction.KEEP) "show" else "hide"} \"${r.label.ifEmpty { r.match.replace("|", ", ") }}\".\n")
        }
        runCatching { EngagementStore(app).load() }.getOrNull()?.table?.mapValues { (_, byHour) -> byHour.values.sumOf { it.taps } to byHour.values.sumOf { it.dismissals } }
            ?.entries?.sortedByDescending { it.value.first - it.value.second }?.take(8)?.forEach { (k, v) -> append("- Card \"${k.substringBefore(':')}\": tapped ${v.first}, dismissed ${v.second}.\n") }
        core.sections.all().filter { it.pinned }.forEach { append("- Pinned list: ${it.name}.\n") }
    }

    override fun names(): Map<String, String> = core.store.people().associate { it.name to it.label }

    override fun sealed(): Set<String> = core.store.people().filter { it.calypso }.map { it.name }.toSet()

    override fun facts(): String = dev.phonerecall.oneiroi.Oneiroi.get(app).markdown()

    // ---- MindExtras / DelphiExtras ----

    override fun cards(): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        return core.db.cards(now).map { mapOf("title" to it.title, "body" to it.body) } + core.store.cards(now).map { mapOf("title" to it.title, "body" to it.body, "kind" to it.source.lowercase()) }
    }

    override fun peopleLabels(): List<Map<String, Any?>> = core.store.people().filter { !it.calypso }.groupBy { it.label to it.tier }.map { (k, v) -> mapOf("label" to k.first, "tier" to k.second.label, "count" to v.size) }

    override fun sections(): List<Map<String, Any?>> = core.sections.visible().map { s ->
        mapOf("name" to s.name, "pinned" to s.pinned, "open_items" to core.store.items(s.id).filter { !it.done && !it.lethe }.map { it.text }.take(30))
    }

    override fun searchMemory(query: String, k: Int): String {
        val reranker = if (Nano.foreground()) NanoReranker(NanoModel, Nano::foreground) else LexicalReranker
        return Alexandria.asToolJson(alexandria.search(query, k, reranker, forAgent = true), zone)
    }

    override fun reportJob(input: Map<String, Any?>, thread: String?): String {
        val answer = synchronized(lock) { mind.reportJob(input, thread) }
        // A finished job is a session end too: Oneiroi reflects on it once (off this thread, it calls Haiku).
        if (input["status"]?.toString() == "done") synchronized(lock) { mind.jobs().firstOrNull { it.id == input["job_id"]?.toString() } }?.let { job ->
            Thread({ runCatching { dev.phonerecall.oneiroi.Oneiroi.get(app).reflectJob(job) }.onFailure { dev.phonerecall.clio.Clio.error("mind", it) } }, "zeus-oneiroi-reflect").start()
        }
        return answer
    }

    override fun usage(): String = dev.phonerecall.metron.MetronCore.get(app).words()

    override fun factRows(): List<Map<String, Any?>> = dev.phonerecall.oneiroi.Oneiroi.get(app).dreamRows()

    override fun proposeFactChanges(input: Map<String, Any?>): String = dev.phonerecall.oneiroi.Oneiroi.get(app).applyDream(input)

    override fun dreamSpent(cents: Long) = dev.phonerecall.oneiroi.Oneiroi.get(app).dreamSpent(cents * 10_000L)

    override fun handOff(title: String, brief: String, protege: String?): String {
        if (!core.vault.has()) return "Add the Anthropic key in Calypso first."
        if (runCatching { core.spend.check() }.isFailure) return "This month's cap is reached, so the circle can't start a job. Tell him to raise it in Themis."
        val p = protege?.let { name -> Protege.entries.firstOrNull { it.display.equals(name, true) } }
        val job = synchronized(lock) { mind.startJob(title, brief, p?.display) }
        MindWork.flushSoon(app, 0)
        return "Started long job ${job.id} for Athena's circle${p?.let { " (${it.display})" } ?: ""}. It comes back as a card in Praxis under Long jobs. Tell him in one line; do not say it is done."
    }
}

/** No foreground service: one periodic sync (30 min, network) and one-shot flushes. Each run is logged as a wakeup. */
object MindWork {
    /** The interval is Horologion's (15 to 120 minutes, 30 by default); a change there re-enqueues with UPDATE. */
    fun schedule(c: Context, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "zeus-mind-sync", policy,
            PeriodicWorkRequestBuilder<MindSyncWorker>(dev.phonerecall.horologion.PhoneJobs.minutes(c, "zeus-mind-sync"), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** On open: one sync now. */
    fun syncNow(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "zeus-mind-open", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MindSyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    /** Alexandria → Index now: every source and every passage, with no network or charging constraint. */
    fun indexNow(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork("zeus-alexandria-now", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexNowWorker>().build())
    }

    /** A batch flush after `delayMs` (0 for urgent ones: REPLACE so an urgent note does not wait behind a delayed one). */
    fun flushSoon(c: Context, delayMs: Long) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "zeus-mind-flush", if (delayMs == 0L) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<MindFlushWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}

private fun charging(c: Context): Boolean = c.getSystemService(BatteryManager::class.java)?.isCharging == true

class MindSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val started = System.currentTimeMillis()
        val core = BrainCore.get(applicationContext)
        val host = core.mindHost
        var what = "sync"
        var calls = 0
        // Clio's device area rides this wake-up: the battery reading, and yesterday's screen time once a day.
        runCatching { dev.phonerecall.clio.DeviceWatch.tick(applicationContext) }.onFailure { dev.phonerecall.clio.Clio.error("device", it) }
        host.index(if (charging(applicationContext) || Nano.foreground()) 200 else 0)
        if (core.vault.has()) {
            dev.phonerecall.clio.Clio.clean = host.mind.sanitizer()
            val before = host.spentCents()
            val r = runCatching { host.locked { sync() } }
            r.onSuccess { calls = it.calls; what = "sync (${it.outbox} from outbox${if (it.sent) ", batch sent" else ""})" }
            r.onFailure { e -> what = "sync failed: " + dev.phonerecall.brain.AthenaEngine.plainError(e); if (e !is SpendCapReached) { core.audit.log("Athena", "sync failed", what); dev.phonerecall.clio.Clio.error("mind", e) } }
            if (r.getOrNull()?.sent == true) dev.phonerecall.clio.Clio.info("mind", "athena turn", "trigger sync batch · ${host.spentCents() - before} cents")
        } else what = "sync skipped (no key)"
        // Metron rides the same wake-up: a fresh plan reading when paired, then the cap and window thresholds.
        val metron = dev.phonerecall.metron.MetronCore.get(applicationContext)
        (if (dev.phonerecall.lemnos.LemnosHub.get(applicationContext).paired) runCatching { metron.fetch() } else runCatching { metron.check() }).onFailure { dev.phonerecall.clio.Clio.error("lemnos", it) }
        WakeLog.add(host.kv, Wakeup(started, what, System.currentTimeMillis() - started, calls))
        core.changed()
        return Result.success()
    }
}

class IndexNowWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val core = BrainCore.get(applicationContext)
        core.mindHost.index(Int.MAX_VALUE)
        core.changed()
        return Result.success()
    }
}

class MindFlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val started = System.currentTimeMillis()
        val core = BrainCore.get(applicationContext)
        val host = core.mindHost
        val before = host.spentCents()
        val sent = runCatching { host.locked { flush() } }.getOrElse { e ->
            if (e !is SpendCapReached) { core.audit.log("Athena", "batch failed", dev.phonerecall.brain.AthenaEngine.plainError(e)); dev.phonerecall.clio.Clio.error("mind", e) }
            false
        }
        if (sent) dev.phonerecall.clio.Clio.info("mind", "athena turn", "trigger batch · ${host.spentCents() - before} cents")
        WakeLog.add(host.kv, Wakeup(started, if (sent) "batch sent" else "batch held", System.currentTimeMillis() - started, if (sent) 1 else 0))
        core.changed()
        return Result.success()
    }
}
