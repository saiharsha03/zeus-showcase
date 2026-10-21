package dev.phonerecall.oneiroi

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.LineKind
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.LongJob
import dev.phonerecall.mind.PrefsKv
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.WakeLog
import dev.phonerecall.mind.Wakeup
import dev.phonerecall.mind.putStrings
import dev.phonerecall.mind.strings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/** What Themis → Memory care draws. */
@Immutable
data class MemoryCare(
    val loaded: Boolean = false,
    val live: List<Fact> = emptyList(),
    val replaced: Int = 0,
    val refs: List<Ref> = emptyList(),
    val nudges: List<Nudge> = emptyList(),
    val dreams: List<DreamNote> = emptyList(),
    val irisQueued: Int = 0,
    val reflected: Int = 0,
    val lastSweep: Long? = null,
    val spentTonightMicros: Long = 0,
)

/**
 * Oneiroi on the phone: the rows, the lookup table and the queue to Iris in their own small store,
 * and the jobs that keep them (reflect at session end, the dream's rows for Athena's 3 AM run, the
 * subconscious every two days). One per process.
 */
class Oneiroi private constructor(private val app: Context) {
    private val core = BrainCore.get(app)
    val kv: Kv = PrefsKv(app, "oneiroi")
    val facts = FactStore(kv)
    val refs = RefTable(kv)
    private val sweep = RefSweep(kv, refs)
    val iris = IrisQueue(kv, link = dev.phonerecall.Wiring.irisNotes(app))
    private val lock = Any()

    fun sanitizer(): Sanitizer = core.mindHost.let { Sanitizer(it.names(), it.sealed()) }
    private fun model(m: BrainModel): MeteredModel? = if (core.vault.has()) GatewayModel(core.gateway, core.spend, m) else null

    private val reflector = Reflector(facts, refs, iris, ::sanitizer) { model(dev.phonerecall.models.ModelChoice.brain(dev.phonerecall.models.Features.REFLECT)) }
    private val dreamer = Dreamer(facts, kv, ::sanitizer)
    private val subconscious = Subconscious(kv, facts)

    private val _state = MutableStateFlow(MemoryCare())
    val state: StateFlow<MemoryCare> = _state.asStateFlow()

    fun reload() {
        val all = facts.all()
        _state.value = MemoryCare(
            true, all.filter { it.live }.sortedWith(compareBy({ it.topic }, { -it.id })), all.count { !it.live }, refs.all().sortedBy { it.term.lowercase() },
            subconscious.open(), dreamer.notes(), iris.queued().size, kv.strings(REFLECTED).size, subconscious.lastRun(), dreamer.spentTonight(),
        )
    }

    // ---- Delphi ----

    /** A /memory question or correction, answered here without a model. Null when the text is not one. */
    fun ask(text: String): MemoryAnswer? {
        val a = MemoryView.parse(text) ?: return null
        val answer = synchronized(lock) { MemoryView.answer(a, facts, refs) }
        if (a !is MemoryAsk.About) core.audit.log("The owner", "memory", answer.title + (answer.note?.let { ": $it" } ?: ""))
        reload()
        return answer
    }

    /** The refs line Delphi reads before answering, or null. */
    fun refsNote(text: String): String? = refs.note(text, runCatching { core.mindHost.names() }.getOrDefault(emptyMap()))

    // ---- reflect ----

    private fun said(chat: Long): List<Said> = core.db.lines(chat).mapNotNull { l -> l.said(chat) }

    private fun dev.phonerecall.brain.ChatLine.said(chat: Long): Said? = when (kind) {
        LineKind.USER -> Said(at, Said.OWNER, text, "chat-$chat")
        LineKind.ATHENA -> Said(at, Said.ATHENA, text, "chat-$chat")
        LineKind.PROPOSAL -> Said(at, Said.ZEUS, "proposal: $text", "chat-$chat")
        else -> null
    }

    /** One finished Delphi chat, once. */
    fun reflectChat(chat: Long): ReflectOutcome {
        val mark = "chat-$chat"
        if (mark in kv.strings(REFLECTED)) return ReflectOutcome(skipped = "already reflected")
        val said = said(chat)
        synchronized(lock) { sweep.run(said) }
        val out = synchronized(lock) { reflector.reflect("Chat", said) }
        if (out.skipped == null || out.skipped == "nothing he said") kv.putStrings(REFLECTED, (kv.strings(REFLECTED) + mark).takeLast(200))
        after("reflected a chat", out)
        return out
    }

    /** A long job's result, once: what the circle found may hold facts too. */
    fun reflectJob(job: LongJob): ReflectOutcome {
        val mark = "job-${job.id}"
        if (mark in kv.strings(REFLECTED)) return ReflectOutcome(skipped = "already reflected")
        val said = listOfNotNull(
            Said(job.startedAt, Said.OWNER, "${job.title}. ${job.brief}", mark),
            job.resultBody?.let { Said(job.updatedAt, Said.ATHENA, "${job.resultTitle.orEmpty()}. $it", mark) },
        )
        val out = synchronized(lock) { reflector.reflect("long job", said) }
        if (out.skipped == null || out.skipped == "nothing he said") kv.putStrings(REFLECTED, (kv.strings(REFLECTED) + mark).takeLast(200))
        after("reflected a job", out)
        return out
    }

    private fun after(what: String, o: ReflectOutcome) {
        if (o.added + o.corrected + o.refs + o.lessons > 0) core.audit.log("Athena", what, "${o.added} new, ${o.corrected} corrected, ${o.refs} names, ${o.lessons} lessons" + if (o.toIris > 0) ", ${o.toIris} for Iris" else "")
        iris.flush()
        reload()
    }

    // ---- the dream ----

    /** New notes Athena wrote under /notes/ become rows (backup first). Network; the mind's lock is reentrant. */
    private fun fold() {
        if (!core.vault.has()) return
        runCatching {
            val files = core.mindHost.locked { remembered() }
            MemoryFold.fold(files, facts, sanitizer(), ::backup, { f, text -> core.mindHost.locked { rewrite(f, text) } }, LocalDate.now().toString())
        }.onSuccess { if (it.files > 0) core.audit.log("Athena", "folded memory", "${it.files} files into ${it.added} facts; backup kept on the phone") }
            .onFailure { dev.phonerecall.clio.Clio.error("mind", it) }
    }

    /** Dream now (Memory care): fold and the plain-code repeats. The model's part is the 3 AM run. */
    fun dream(): DreamNote {
        fold()
        val note = synchronized(lock) { dreamer.dream() }
        after(note)
        return note
    }

    /** read_facts for the 3 AM run: fold first, so tonight's notes are rows too. */
    fun dreamRows(): List<Map<String, Any?>> {
        fold()
        return synchronized(lock) { dreamer.rows() }
    }

    /** propose_fact_changes from the 3 AM run. Returns the tool's one line. */
    fun applyDream(input: Map<String, Any?>): String {
        val note = synchronized(lock) { dreamer.applyRun(input) }
        after(note)
        return note.skipped?.let { "Nothing applied: $it." } ?: "Applied ${note.lines.size} changes:\n" + note.lines.joinToString("\n") { "- $it" }
    }

    fun dreamSpent(micros: Long) { dreamer.spent(micros); reload() }

    private fun after(note: DreamNote) {
        if (note.lines.isNotEmpty()) core.audit.log("Athena", "dreamed", note.lines.size.toString() + " changes")
        iris.flush()
        reload()
        core.changed()
    }

    /** The memory files as they were, before folding: app-private, never uploaded. */
    private fun backup(files: List<dev.phonerecall.mind.MemoryFile>) {
        val dir = java.io.File(app.filesDir, "oneiroi").apply { mkdirs() }
        java.io.File(dir, "memory-backup-${System.currentTimeMillis()}.json").writeText(dev.phonerecall.mind.stateJson.writeValueAsString(files.map { mapOf("path" to it.path, "content" to it.content) }))
    }

    // ---- the subconscious ----

    fun sweepIfDue(force: Boolean = false): List<Nudge> {
        if (!force && !subconscious.due()) return emptyList()
        val since = System.currentTimeMillis() - Subconscious.EVERY_MS
        val said = core.db.linesSince(since).mapNotNull { (chat, l) -> l.said(chat) }
        val found = synchronized(lock) { subconscious.sweep(said, runCatching { core.mindHost.mind.jobs() }.getOrDefault(emptyList())) }
        reload()
        return found
    }

    /**
     * His tap on a nudge. Accepting a backfill keeps the fact (he confirmed it); accepting a dropped
     * ball parks a task proposal in Fates, which he still confirms. Nothing else happens.
     */
    fun decide(id: String, accept: Boolean) {
        val n = synchronized(lock) { subconscious.decide(id, accept) } ?: return
        if (accept) when (n.kind) {
            Nudge.BACKFILL -> sanitizer().line(n.text)?.let { synchronized(lock) { facts.put(n.topic, it, FactStatus.CONFIRMED, "owner (sweep)") } }
            else -> core.fates.propose(listOf(RawStep(ProposalKind.ADD_TASK, mapOf("title" to n.text.substringBefore(". Nothing").removePrefix("Athena said she'd ").replaceFirstChar { it.uppercase() }.take(120)))), null, null, "Athena (subconscious)")
        }
        reload()
        core.changed()
    }

    fun retract(id: Int) { synchronized(lock) { facts.retract(id) }; core.audit.log("The owner", "memory", "forgot #$id"); reload() }
    fun confirm(id: Int) { synchronized(lock) { facts.get(id)?.takeIf { it.live && it.statusOf != FactStatus.CONFIRMED }?.let { facts.supersede(id, it.text, "owner", FactStatus.CONFIRMED) } }; reload() }
    fun removeRef(term: String) { refs.remove(term); reload() }

    /** The live rows for the memory store (/facts/live.md). */
    fun markdown(): String = facts.markdown()

    companion object {
        private const val REFLECTED = "oneiroi.reflected"

        @Volatile private var instance: Oneiroi? = null
        fun get(context: Context): Oneiroi = instance ?: synchronized(this) {
            instance ?: Oneiroi(context.applicationContext).also { instance = it }
        }
    }
}

/** The jobs. Reflect and the sweep are cheap and stay here; the dream is Athena's 3 AM deployment ("Zeus · dream"). */
object OneiroiWork {
    /** Phone jobs that moved elsewhere: cancelled on every start. */
    val RETIRED = listOf("zeus-oneiroi-dream")

    fun retire(cancel: (String) -> Unit) = RETIRED.forEach(cancel)

    fun schedule(c: Context, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP) {
        val wm = WorkManager.getInstance(c)
        retire { wm.cancelUniqueWork(it) }
        wm.enqueueUniquePeriodicWork(
            "zeus-oneiroi-subconscious", policy,
            PeriodicWorkRequestBuilder<SubconsciousWorker>(dev.phonerecall.horologion.PhoneJobs.minutes(c, "zeus-oneiroi-subconscious"), TimeUnit.MINUTES).build(),
        )
    }

    /** Milliseconds to the next 3:00 AM. */
    fun untilThree(now: ZonedDateTime): Long {
        var next = now.toLocalDate().atTime(3, 0).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    /** A Delphi chat ended (a new one started, or he left it): reflect on it once. */
    fun reflect(c: Context, chat: Long) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "zeus-oneiroi-reflect-$chat", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReflectWorker>().setInputData(workDataOf("chat" to chat))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }
}

class ReflectWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val chat = inputData.getLong("chat", -1L).takeIf { it >= 0 } ?: return Result.success()
        val started = System.currentTimeMillis()
        val o = runCatching { Oneiroi.get(applicationContext).reflectChat(chat) }.getOrNull()
        WakeLog.add(BrainCore.get(applicationContext).mindKv, Wakeup(started, "reflect: " + (o?.skipped ?: "${o?.added ?: 0} new, ${o?.corrected ?: 0} fixed"), System.currentTimeMillis() - started, if (o?.skipped == null && o != null) 1 else 0))
        return Result.success()
    }
}

/** Retired: the old nightly job, kept only so a run already queued before [OneiroiWork.retire] ends quietly. */
class DreamWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Result.success()
}

class SubconsciousWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        val started = System.currentTimeMillis()
        val found = runCatching { Oneiroi.get(applicationContext).sweepIfDue() }.getOrDefault(emptyList())
        WakeLog.add(BrainCore.get(applicationContext).mindKv, Wakeup(started, "subconscious: ${found.size} proposals", System.currentTimeMillis() - started, 0))
        return Result.success()
    }
}

