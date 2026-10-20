package dev.phonerecall.oneiroi

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.list
import dev.phonerecall.mind.putList

/*
 * Oneiroi (plain: Memory care). Athena's memory as rows, after Iris's notes: every
 * fact is a row with a supersede chain, never two live versions of the same fact, and a number that
 * can be worked out is stored as how to work it out, never as the number.
 */

/** How sure Athena is. Strings on the row so a status added later (Theogony) still reads. */
enum class FactStatus(val word: String) {
    OBSERVED("observed"), INFERRED("inferred"), CONFIRMED("confirmed");

    companion object {
        val DEFAULT = OBSERVED
        fun of(s: String?): FactStatus = entries.firstOrNull { it.word.equals(s?.trim(), true) } ?: DEFAULT
    }
}

/** fact: a changeable atomic fact. derived: how to work a number out. lesson: a lesson or a repeated workflow. */
enum class FactKind(val word: String) {
    FACT("fact"), DERIVED("derived"), LESSON("lesson");

    companion object { fun of(s: String?): FactKind = entries.firstOrNull { it.word.equals(s?.trim(), true) } ?: FACT }
}

/**
 * One row. `key` names the fact (two rows with one key are two versions of it); `supersedes` and
 * `supersededBy` make the chain. Live = not superseded and not retracted.
 */
data class Fact(
    val id: Int = 0,
    val topic: String = "",
    val key: String = "",
    val text: String = "",
    val status: String = FactStatus.DEFAULT.word,
    val source: String = "",
    val created: Long = 0,
    val supersedes: Int? = null,
    val supersededBy: Int? = null,
    val retracted: Boolean = false,
    val kind: String = FactKind.FACT.word,
) {
    val live: Boolean get() = supersededBy == null && !retracted
    val statusOf: FactStatus get() = FactStatus.of(status)
    val kindOf: FactKind get() = FactKind.of(kind)
    val short: String get() = "#$id"
}

sealed class FactWrite {
    data class Added(val fact: Fact) : FactWrite()
    data class Superseded(val old: Fact, val fact: Fact) : FactWrite()
    data class Same(val fact: Fact) : FactWrite()
    data class Refused(val reason: String) : FactWrite()
}

/** Numbers that are really totals or rates: store the derivation instead. */
object Derivations {
    private val DERIVED = Regex("(?i)\\b(total|totals|sum|spent|spend|spending|average|avg|monthly|weekly|daily|yearly|per (day|week|month|year)|a (week|month|year)|this (week|month|year)|so far|count|balance|running|mileage|streak)\\b")
    // "[amount]" and "###" are what the Sanitizer leaves of money: still a number that goes stale.
    private val NUMBER = Regex("[$₹€£]\\s?\\d|\\b\\d[\\d,]*(\\.\\d+)?\\b|\\[amount]|(?<![\\w#])#+(?![\\w#])")
    private val FORMULA = Regex("^[^=]{2,80}=\\s*\\w+\\([^()]{1,120}\\)\\s*$")

    /** "Monthly fuel is $212": a number that will go stale. */
    fun bareNumber(text: String): Boolean = DERIVED.containsMatchIn(text) && NUMBER.containsMatchIn(text) && !FORMULA.matches(text.trim())

    /** "monthly fuel = sum(Moneta Fuel, month)": a name, an equals sign and one function call, no amounts. */
    fun formula(text: String): Boolean = FORMULA.matches(text.trim()) && !Regex("[$₹€£]\\s?\\d").containsMatchIn(text)
}

object Facts {
    fun norm(s: String): String = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** A key when none is given: the topic and the whole normalized text, so only exact repeats collide. */
    fun keyOf(topic: String, text: String): String = norm(topic) + ":" + norm(text).take(80)

    fun cleanTopic(t: String): String = norm(t).ifBlank { "general" }.take(40)
}

/**
 * The rows, in the mind's small key-value state (a few hundred rows at most). Rows are never edited
 * in place: a change is a new row that supersedes the old one, so the history stays readable.
 */
class FactStore(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    private val type = object : TypeReference<List<Fact>>() {}
    private val lock = Any()

    fun all(): List<Fact> = kv.list(KEY, type)
    fun live(): List<Fact> = all().filter { it.live }
    fun get(id: Int): Fact? = all().firstOrNull { it.id == id }

    private fun save(rows: List<Fact>) = kv.putList(KEY, rows)

    /**
     * Adds a fact, or supersedes the live version with the same key. Repeating a live fact changes
     * nothing (unless the status is now stronger, which is a new version). A bare total is refused.
     */
    fun put(
        topic: String, text: String, status: FactStatus = FactStatus.DEFAULT, source: String,
        key: String? = null, kind: FactKind = FactKind.FACT,
    ): FactWrite = synchronized(lock) {
        val t = text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)
        if (t.length < 3) return FactWrite.Refused("Too short to be a fact.")
        if (kind == FactKind.DERIVED && !Derivations.formula(t)) return FactWrite.Refused("A derived value is stored as its formula, like \"monthly fuel = sum(Moneta Fuel, month)\".")
        if (kind != FactKind.DERIVED && Derivations.bareNumber(t)) return FactWrite.Refused("That number goes stale. Store how to work it out instead.")
        val tp = Facts.cleanTopic(topic)
        val k = key?.let { Facts.norm(it) }?.takeIf { it.isNotBlank() }?.let { if (':' in it) it else "$tp:$it" } ?: Facts.keyOf(tp, t)
        val rows = all()
        val same = rows.firstOrNull { it.live && (it.key == k || (it.topic == tp && Facts.norm(it.text) == Facts.norm(t))) }
        if (same != null && Facts.norm(same.text) == Facts.norm(t) && same.statusOf.ordinal >= status.ordinal) return FactWrite.Same(same)
        val fact = Fact(nextId(rows), tp, same?.key ?: k, t, status.word, source.take(60), clock(), supersedes = same?.id, kind = kind.word)
        save(rows.map { if (it.id == same?.id) it.copy(supersededBy = fact.id) else it } + fact)
        if (same != null) FactWrite.Superseded(same, fact) else FactWrite.Added(fact)
    }

    /** A correction by id ("#12 is wrong, it's ..."): a new version of that fact. */
    fun supersede(id: Int, text: String, source: String, status: FactStatus = FactStatus.CONFIRMED): FactWrite = synchronized(lock) {
        val old = get(id) ?: return FactWrite.Refused("There is no #$id.")
        if (!old.live) return FactWrite.Refused("#$id was already replaced${old.supersededBy?.let { " by #$it" } ?: ""}.")
        val t = text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)
        if (t.length < 3) return FactWrite.Refused("Say what it should be.")
        val kind = old.kindOf
        if (kind == FactKind.DERIVED && !Derivations.formula(t)) return FactWrite.Refused("#$id is a formula; give the new formula.")
        if (kind != FactKind.DERIVED && Derivations.bareNumber(t)) return FactWrite.Refused("That number goes stale. Store how to work it out instead.")
        val rows = all()
        val fact = Fact(nextId(rows), old.topic, old.key, t, status.word, source.take(60), clock(), supersedes = id, kind = old.kind)
        save(rows.map { if (it.id == id) it.copy(supersededBy = fact.id) else it } + fact)
        FactWrite.Superseded(old, fact)
    }

    /** Several live rows become one (the dream's merge). The new row supersedes the first; the rest point at it. */
    fun merge(ids: List<Int>, text: String, source: String, status: FactStatus): FactWrite = synchronized(lock) {
        val rows = all()
        val olds = ids.distinct().mapNotNull { id -> rows.firstOrNull { it.id == id && it.live } }
        if (olds.size < 2) return FactWrite.Refused("Nothing to merge.")
        val t = text.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT)
        if (t.length < 3 || Derivations.bareNumber(t)) return FactWrite.Refused("Merged text refused.")
        val first = olds.first()
        val fact = Fact(nextId(rows), first.topic, first.key, t, status.word, source.take(60), clock(), supersedes = first.id, kind = first.kind)
        val gone = olds.map { it.id }.toSet()
        save(rows.map { if (it.id in gone) it.copy(supersededBy = fact.id) else it } + fact)
        FactWrite.Superseded(first, fact)
    }

    /** One live row points at another that already says the right thing (a contradiction settled). */
    fun settle(drop: Int, keep: Int): Boolean = synchronized(lock) {
        val rows = all()
        if (drop == keep || rows.none { it.id == keep && it.live } || rows.none { it.id == drop && it.live }) return false
        save(rows.map { if (it.id == drop) it.copy(supersededBy = keep) else it })
        true
    }

    /** "Forget #12": no longer live, kept in the history. */
    fun retract(id: Int): Boolean = synchronized(lock) {
        val rows = all()
        if (rows.none { it.id == id && it.live }) return false
        save(rows.map { if (it.id == id) it.copy(retracted = true) else it })
        true
    }

    /** The chain a fact belongs to, oldest first. */
    fun chain(id: Int): List<Fact> {
        val rows = all().associateBy { it.id }
        var head = rows[id] ?: return emptyList()
        while (true) head = head.supersedes?.let { rows[it] } ?: break
        val out = ArrayList<Fact>()
        var cur: Fact? = head
        while (cur != null && cur !in out) { out += cur; cur = cur.supersededBy?.let { rows[it] } }
        return out
    }

    private fun nextId(rows: List<Fact>) = (rows.maxOfOrNull { it.id } ?: 0) + 1

    /** Live rows as Markdown for the memory store (/facts/live.md). Sanitized again before upload. */
    fun markdown(): String {
        val rows = live()
        if (rows.isEmpty()) return ""
        return buildString {
            append("# What Athena knows (kept by the phone; read only for agents)\n")
            append("Each line: id, status (observed, inferred, confirmed), the fact, its source. Never edit this file: write new notes under /notes/ and the phone folds them in. Numbers are stored as formulas; work them out from the source.\n")
            for ((topic, group) in rows.groupBy { it.topic }.toSortedMap()) {
                append("\n## ").append(topic).append('\n')
                for (f in group) append("- ${f.short} [${f.status}] ${f.text} (${f.source})\n")
            }
        }
    }

    companion object {
        const val KEY = "oneiroi.facts"
        const val MAX_TEXT = 280
    }
}
