package dev.phonerecall.oneiroi

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.list
import dev.phonerecall.mind.long
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.putLong

/** One line someone said: the owner, Athena, or Zeus itself (a proposal or job note). `thread` groups a chat. */
data class Said(val at: Long, val who: String, val text: String, val thread: String = "") {
    companion object {
        const val OWNER = "owner"
        const val ATHENA = "athena"
        const val ZEUS = "zeus"
    }
}

/** A named thing future-Athena won't recognise: "M3" is his Mazda, "Amma" is his mother. */
data class Ref(val term: String = "", val meaning: String = "", val kind: String = "thing", val source: String = "", val added: Long = 0)

/**
 * The lookup table (Iris's refs-sweep). Deterministic: whole-word, case-insensitive, longest term
 * first. No fuzzy search, so the same words always resolve the same way.
 */
class RefTable(private val kv: Kv, private val clock: () -> Long = System::currentTimeMillis) {
    private val type = object : TypeReference<List<Ref>>() {}

    fun all(): List<Ref> = kv.list(KEY, type)

    /** Adds or replaces (the latest meaning wins: a wrong entry is fixed where it lives). */
    fun put(term: String, meaning: String, kind: String = "thing", source: String): Boolean {
        val t = term.trim().take(40)
        val m = meaning.trim().take(120)
        if (t.length < 2 || m.length < 2 || t.lowercase() in STOP) return false
        val rows = all()
        val old = rows.firstOrNull { it.term.equals(t, true) }
        if (old != null && old.meaning.equals(m, true)) return false
        kv.putList(KEY, rows.filter { !it.term.equals(t, true) } + Ref(t, m, kind.take(20), source.take(40), clock()))
        return true
    }

    fun remove(term: String) = kv.putList(KEY, all().filter { !it.term.equals(term.trim(), true) })

    /**
     * Every term in `text` with its meaning, in the order found. `people` (known names to labels)
     * resolves too, on the phone only.
     */
    fun resolve(text: String, people: Map<String, String> = emptyMap()): List<Pair<String, String>> {
        val entries = (all().map { it.term to it.meaning } + people.filter { it.key.length >= 2 }.map { it.key to it.value })
            .distinctBy { it.first.lowercase() }.sortedByDescending { it.first.length }
        var rest = text
        val out = ArrayList<Pair<Int, Pair<String, String>>>()
        for ((term, meaning) in entries) {
            val m = Regex("(?i)(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])").find(rest) ?: continue
            out += m.range.first to (term to meaning)
            // Blank it out so a shorter term inside it ("M3" inside "M3 GT") does not match again.
            rest = rest.replaceRange(m.range, " ".repeat(m.value.length))
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    /** One line for Delphi, or null when nothing resolves. */
    fun note(text: String, people: Map<String, String> = emptyMap()): String? =
        resolve(text, people).takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "[Zeus refs] ") { "${it.first} = ${it.second}" }

    companion object {
        const val KEY = "oneiroi.refs"
        val STOP = setOf("it", "this", "that", "he", "she", "they", "which", "what", "there", "here", "today", "tomorrow", "mine", "me", "i", "we", "you", "one")
    }
}

/** Plain rules for the sweep: "M3 is my Mazda", "my Mazda, M3", "we call it M3". No model. */
object RefRules {
    private const val TERM = "([A-Z][\\p{L}\\p{N}'-]{1,23}|[\\p{L}]+\\d[\\p{L}\\p{N}-]*)"
    private val IS_MY = Regex("(?<![\\p{L}\\p{N}])$TERM\\s+(?:is|=|means)\\s+(?:my|our)\\s+([\\p{L}\\p{N}' -]{2,40}?)(?=[.,;!?]|\\s+(?:and|but|who|which|from|in|at)\\b|$)")
    private val MY_COMMA = Regex("(?i)\\bmy\\s+([\\p{L}' -]{2,30}?),\\s+$TERM(?=[.,;!?\\s]|$)")
    private val CALL = Regex("\\b(?:[Ww]e|I)\\s+call\\s+(?:it|him|her|them)\\s+$TERM")

    fun extract(said: List<Said>): List<Ref> = said.filter { it.who == Said.OWNER }.flatMap { s ->
        val out = ArrayList<Ref>()
        IS_MY.findAll(s.text).forEach { m -> out += Ref(m.groupValues[1], "his " + m.groupValues[2].trim(), source = "he said", added = s.at) }
        MY_COMMA.findAll(s.text).forEach { m -> if (m.groupValues[2].first().isUpperCase() || m.groupValues[2].any { it.isDigit() }) out += Ref(m.groupValues[2], "his " + m.groupValues[1].trim(), source = "he said", added = s.at) }
        CALL.findAll(s.text).forEach { m -> s.text.substring(0, m.range.first).let { before -> Regex("(?i)\\bmy\\s+([\\p{L}' -]{2,30}?)[.,;]?\\s*$").find(before)?.let { out += Ref(m.groupValues[1], "his " + it.groupValues[1].trim(), source = "he said", added = s.at) } } }
        out
    }.filter { it.term.lowercase() !in RefTable.STOP }
}

/**
 * The refs sweep with a watermark: only what was said after the stamp is read, then the stamp moves
 * to the newest line. Running it twice on the same lines reads nothing the second time.
 */
class RefSweep(private val kv: Kv, private val table: RefTable) {
    fun watermark(): Long = kv.long(MARK) ?: 0L

    /** Returns how many refs were added or changed. `extract` sees only the new lines. */
    fun run(said: List<Said>, extract: (List<Said>) -> List<Ref> = RefRules::extract): Int {
        val mark = watermark()
        val fresh = said.filter { it.at > mark }
        if (fresh.isEmpty()) return 0
        val n = extract(fresh).count { table.put(it.term, it.meaning, it.kind, it.source.ifBlank { "sweep" }) }
        kv.putLong(MARK, fresh.maxOf { it.at })
        return n
    }

    companion object { const val MARK = "oneiroi.refs.mark" }
}
