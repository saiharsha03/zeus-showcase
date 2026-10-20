package dev.phonerecall.oneiroi

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.MemoryFile
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.list
import dev.phonerecall.mind.long
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.putLong
import java.time.Instant
import java.time.ZoneId

/** One night's "what changed", shown in Themis → Memory care. */
data class DreamNote(val at: Long = 0, val lines: List<String> = emptyList(), val micros: Long = 0, val skipped: String? = null)

/**
 * The dream: a nightly pass over the rows (Iris's dream, copied as a pattern). Plain code on the phone
 * first (exact repeats merge, no network), then Athena's 3 AM scheduled run ("Zeus · dream") reads the
 * rows with read_facts and sends one propose_fact_changes, which is checked and applied here. Every
 * change is a supersede row; a confirmed fact is never dropped or rewritten on the dream's say-so.
 *
 * The run's own budget is `capMicros` (default $0.10); its cost comes back through the ledger's
 * recordMind and [spent], and a night already over the cap applies nothing.
 */
class Dreamer(
    private val facts: FactStore,
    private val kv: Kv,
    private val clean: () -> Sanitizer,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capMicros: Long = CAP_MICROS,
) {
    private val noteType = object : TypeReference<List<DreamNote>>() {}

    fun notes(): List<DreamNote> = kv.list(NOTES, noteType)

    private fun night(): String = Instant.ofEpochMilli(clock()).atZone(zone()).minusHours(12).toLocalDate().toString()
    fun spentTonight(): Long = kv.long("$SPENT${night()}") ?: 0L

    /** The dream run's cost since last looked (session list cost, already in the monthly ledger). */
    fun spent(micros: Long) { if (micros > 0) kv.putLong("$SPENT${night()}", spentTonight() + micros) }

    private fun keep(note: DreamNote): DreamNote { kv.putList(NOTES, (listOf(note) + notes()).take(14)); return note }

    /** Exact repeats under different topics (a folded file and a chat said the same): keep the newest. */
    fun repeats(changes: MutableList<String>) {
        for (group in facts.live().groupBy { Facts.norm(it.text) }.values.filter { it.size > 1 }) {
            val keep = group.maxBy { it.id }
            for (old in group.filter { it.id != keep.id }) if (facts.settle(old.id, keep.id)) changes += "${old.short} was a repeat of ${keep.short}"
        }
    }

    /** The phone's own pass (Dream now, and before a run's changes): repeats only. */
    fun dream(): DreamNote {
        val changes = ArrayList<String>()
        repeats(changes)
        return if (changes.isEmpty()) DreamNote(clock(), skipped = "no repeats") else keep(DreamNote(clock(), changes, spentTonight()))
    }

    /** read_facts: the live rows, sanitized. A row whose text can't leave the phone is left out. */
    fun rows(): List<Map<String, Any?>> {
        val s = clean()
        return facts.live().mapNotNull { f ->
            val text = s.line(f.text) ?: return@mapNotNull null
            mapOf(
                "id" to f.id, "topic" to f.topic, "key" to (s.line(f.key.substringAfter(':')) ?: ""), "text" to text,
                "status" to f.status, "source" to (s.line(f.source) ?: ""), "created" to Instant.ofEpochMilli(f.created).atZone(zone()).toLocalDate().toString(),
            )
        }
    }

    /** propose_fact_changes from the 3 AM run: repeats first, then her changes by the rules, then the note. */
    fun applyRun(input: Map<String, Any?>): DreamNote {
        if (spentTonight() >= capMicros) return keep(DreamNote(clock(), micros = spentTonight(), skipped = "over tonight's cap"))
        val changes = ArrayList<String>()
        repeats(changes)
        apply(input, changes)
        input["note"]?.toString()?.let { clean().line(it.take(200)) }?.let { changes += "Athena: $it" }
        return keep(DreamNote(clock(), changes, spentTonight(), if (changes.isEmpty()) "nothing to change" else null))
    }

    /** Applies the dream's ops. Confirmed facts are never dropped or rewritten; texts pass the Sanitizer. */
    @Suppress("UNCHECKED_CAST")
    fun apply(m: Map<String, Any?>, changes: MutableList<String>) {
        val s = clean()
        fun id(v: Any?) = v?.toString()?.trimStart('#')?.toDoubleOrNull()?.toInt()
        fun ints(v: Any?) = (v as? List<Any?>).orEmpty().mapNotNull { id(it) }
        fun live(id: Int?) = id?.let { facts.get(it) }?.takeIf { it.live }
        fun sure(f: Fact) = f.statusOf == FactStatus.CONFIRMED
        for (op in (m["merges"] as? List<Map<String, Any?>>).orEmpty().take(10)) {
            val keep = live(id(op["keep"])) ?: continue
            val drops = ints(op["drop"]).filter { it != keep.id }.mapNotNull { live(it) }.filter { !sure(it) }
            if (drops.isEmpty()) continue
            val text = op["text"]?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) {
                if (sure(keep)) continue
                val t = s.line(text) ?: continue
                (facts.merge(listOf(keep.id) + drops.map { it.id }, t, "dream", FactStatus.INFERRED) as? FactWrite.Superseded)?.let { changes += "Merged ${(listOf(keep) + drops).joinToString(", ") { it.short }} into ${it.fact.short}" }
            } else for (d in drops) if (facts.settle(d.id, keep.id)) changes += "${d.short} folded into ${keep.short}"
        }
        for (rid in ints(m["retire"]).take(10)) {
            val f = live(rid)?.takeIf { !sure(it) } ?: continue
            if (facts.retract(f.id)) changes += "Retired ${f.short}"
        }
        for (op in (m["replace"] as? List<Map<String, Any?>>).orEmpty().take(10)) {
            val old = live(id(op["id"]))?.takeIf { !sure(it) } ?: continue
            val t = op["text"]?.toString()?.let { s.line(it) } ?: continue
            (facts.supersede(old.id, t, "dream", old.statusOf) as? FactWrite.Superseded)?.let { changes += "${old.short} was stale; now ${it.fact.short}" }
        }
        for (p in (m["patterns"] as? List<Any?>).orEmpty().take(3)) {
            val op = p as? Map<String, Any?> ?: mapOf("text" to p)
            val t = op["text"]?.toString()?.let { s.line(it) } ?: continue
            val topic = op["topic"]?.toString()?.let { s.line(it) } ?: "patterns"
            (facts.put(topic, t, FactStatus.INFERRED, "dream", key = op["key"]?.toString()?.let { s.line(it) }, kind = FactKind.LESSON) as? FactWrite.Added)?.let { changes += "New pattern ${it.fact.short}: ${t.take(80)}" }
        }
    }

    companion object {
        const val CAP_MICROS = 100_000L
        private const val NOTES = "oneiroi.dreams"
        private const val SPENT = "oneiroi.dream.spent."
    }
}

/**
 * Folds the memory store's own notes into rows: the one-time migration of what Athena wrote before
 * Oneiroi, and every night after for new notes. The phone's files, the outbox, long-job notes and
 * /facts/ are left alone. A folded file is backed up first, then replaced by a pointer, so there is
 * one live version of each fact.
 */
object MemoryFold {
    const val STUB = "Moved to /facts/live.md by Oneiroi"
    val PHONE = setOf("/snapshot/today.md", "/decisions/recent.md", "/people/labels.md", "/routines/days.md", "/preferences/phone.md")
    private val SKIP = listOf("/outbox/", "/jobs/", "/facts/")

    /** Theogony's files are the phone's too: its confirmed lines reach the rows directly, its guesses never do. */
    fun theogony(path: String) = path.endsWith("/theogony.md") || path == "/profile/milestones.md"

    fun foldable(f: MemoryFile): Boolean = f.path !in PHONE && !theogony(f.path) && SKIP.none { f.path.startsWith(it) } && f.content?.trimStart()?.startsWith(STUB) == false

    /** /profile/owner.md → "profile"; /notes/car.md → "car". */
    fun topic(path: String): String {
        val parts = path.trim('/').split('/')
        val stem = parts.last().substringBeforeLast('.')
        return Facts.cleanTopic(if (parts.size > 1 && parts.first() != "notes") parts.first() else stem)
    }

    fun lines(content: String): List<String> = content.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("```") }
        .map { it.removePrefix("- ").removePrefix("* ").replace(Regex("^\\d+[.)]\\s+"), "").trim() }
        .filter { it.length >= 3 }
        .toList()

    data class Folded(val files: Int, val added: Int, val refused: List<String>)

    /**
     * `backup` gets every foldable file before anything changes; `rewrite` replaces one with the pointer.
     * Each line goes through the Sanitizer. The profile came from his own notes, so it is confirmed.
     */
    fun fold(files: List<MemoryFile>, store: FactStore, clean: Sanitizer, backup: (List<MemoryFile>) -> Unit, rewrite: (MemoryFile, String) -> Unit, day: String): Folded {
        val todo = files.filter { foldable(it) && !it.content.isNullOrBlank() }
        if (todo.isEmpty()) return Folded(0, 0, emptyList())
        backup(todo)
        var added = 0
        val refused = ArrayList<String>()
        for (f in todo) {
            val status = if (f.path.startsWith("/profile/")) FactStatus.CONFIRMED else FactStatus.OBSERVED
            for (raw in lines(f.content.orEmpty())) {
                val line = clean.line(raw) ?: continue
                when (store.put(topic(f.path), line, status, "memory ${f.path}".take(60))) {
                    is FactWrite.Added, is FactWrite.Superseded -> added++
                    is FactWrite.Refused -> refused += line
                    is FactWrite.Same -> Unit
                }
            }
            rewrite(f, "$STUB on $day. The backup stays on the phone.")
        }
        return Folded(todo.size, added, refused)
    }
}
