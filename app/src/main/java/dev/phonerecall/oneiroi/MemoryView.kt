package dev.phonerecall.oneiroi

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.stateJson

/** What he asked of her memory in Delphi. Answered on the phone, no model. */
sealed class MemoryAsk {
    data class About(val topic: String) : MemoryAsk()
    data class Correct(val id: Int, val text: String) : MemoryAsk()
    data class Retract(val id: Int) : MemoryAsk()
    data class Confirm(val id: Int) : MemoryAsk()
}

/** One fact line on the answer card. */
data class FactLine(val id: Int = 0, val text: String = "", val status: String = "", val source: String = "")

/** The /memory answer card: a title, the live facts, one line on what happened. */
data class MemoryAnswer(val title: String = "", val facts: List<FactLine> = emptyList(), val note: String? = null)

/**
 * Iris's /memory, copied: "what do you know about <topic>" lists the live facts with short ids,
 * status and source; "#12 is wrong, it's ..." supersedes; "forget #12" retracts.
 */
object MemoryView {
    private val ABOUT = Regex("(?i)^\\s*(?:/memory\\s*(.*)|what (?:do|did) you (?:know|remember) about\\s+(.+?)|what do you know|what do you remember)\\s*[?.!]*\\s*$")
    private val CORRECT = Regex("(?i)^\\s*#(\\d+)\\s+(?:is wrong|is not right|isn't right|is old|is out of date)[,.:;]?\\s*(?:it(?:'s| is| should be)|now(?: it's)?|actually)\\s+(.+?)\\s*$|^\\s*(?:correct|fix)\\s+#(\\d+)\\s*[:,]?\\s*(?:to\\s+)?(.+?)\\s*$|^\\s*#(\\d+)\\s+should be\\s+(.+?)\\s*$")
    private val RETRACT = Regex("(?i)^\\s*(?:(?:forget|retract|delete|remove|drop)\\s+#(\\d+)|#(\\d+)\\s+(?:is wrong|is not true|isn't true))\\s*[.!]?\\s*$")
    private val CONFIRM = Regex("(?i)^\\s*(?:confirm\\s+#(\\d+)|#(\\d+)\\s+is (?:right|correct|true))\\s*[.!]?\\s*$")

    fun parse(text: String): MemoryAsk? {
        CORRECT.find(text)?.let { m ->
            val g = m.groupValues
            val (id, t) = listOf(1 to 2, 3 to 4, 5 to 6).first { g[it.first].isNotEmpty() }.let { g[it.first].toInt() to g[it.second] }
            return MemoryAsk.Correct(id, t.trimEnd('.'))
        }
        RETRACT.find(text)?.let { m -> return MemoryAsk.Retract((m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()) }
        CONFIRM.find(text)?.let { m -> return MemoryAsk.Confirm((m.groupValues[1].ifEmpty { m.groupValues[2] }).toInt()) }
        ABOUT.find(text)?.let { m -> return MemoryAsk.About((m.groupValues[1].ifEmpty { m.groupValues[2] }).trim().trimEnd('?')) }
        return null
    }

    /** Live facts about a topic: matched on topic, key and text, with refs ("M3" also finds "Mazda"). Empty topic: everything. */
    fun about(topic: String, facts: FactStore, refs: RefTable): List<Fact> {
        val live = facts.live()
        val q = Facts.norm(topic)
        if (q.isEmpty() || q in setOf("me", "everything", "all", "owner", "him")) return live.takeLast(LIMIT).reversed()
        val terms = (q.split(' ') + refs.resolve(topic).flatMap { Facts.norm(it.second).split(' ') })
            .filter { it.length > 1 && it !in setOf("my", "his", "the", "a", "an", "of", "about") }.toSet()
        return live.map { f -> f to Facts.norm("${f.topic} ${f.key.substringAfter(':')} ${f.text}").split(' ').toSet().let { w -> terms.count { it in w } } }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Fact, Int>> { it.second }.thenByDescending { it.first.id }).take(LIMIT).map { it.first }
    }

    fun answer(ask: MemoryAsk, facts: FactStore, refs: RefTable, source: String = "owner"): MemoryAnswer = when (ask) {
        is MemoryAsk.About -> {
            val found = about(ask.topic, facts, refs)
            val title = if (ask.topic.isBlank()) "What I know" else "What I know about ${ask.topic}"
            MemoryAnswer(title, found.map { it.line() }, if (found.isEmpty()) "Nothing yet. Tell me and I'll keep it." else "Say \"#${found.first().id} is wrong, it's ...\" to fix one, or \"forget #${found.first().id}\".")
        }
        is MemoryAsk.Correct -> when (val w = facts.supersede(ask.id, ask.text, source, FactStatus.CONFIRMED)) {
            is FactWrite.Superseded -> MemoryAnswer("Fixed", listOf(w.fact.line()), "#${ask.id} is replaced by #${w.fact.id}. The old one stays in the history.")
            is FactWrite.Refused -> MemoryAnswer("Not changed", emptyList(), w.reason)
            else -> MemoryAnswer("Not changed", emptyList(), "Nothing to change.")
        }
        is MemoryAsk.Retract -> if (facts.retract(ask.id)) MemoryAnswer("Forgotten", emptyList(), "#${ask.id} is no longer something I know.")
        else MemoryAnswer("Not changed", emptyList(), "There is no live #${ask.id}.")
        is MemoryAsk.Confirm -> {
            val f = facts.get(ask.id)?.takeIf { it.live }
            if (f?.statusOf == FactStatus.CONFIRMED) MemoryAnswer("Confirmed", listOf(f.line()), "Already confirmed.")
            else when (val w = f?.let { facts.supersede(it.id, it.text, source, FactStatus.CONFIRMED) }) {
                is FactWrite.Superseded -> MemoryAnswer("Confirmed", listOf(w.fact.line()), null)
                else -> MemoryAnswer("Not changed", emptyList(), "There is no live #${ask.id}.")
            }
        }
    }

    private fun Fact.line() = FactLine(id, text, status, source)

    fun encode(a: MemoryAnswer): String = stateJson.writeValueAsString(a)
    fun decode(s: String): MemoryAnswer? = runCatching { stateJson.readValue(s, object : TypeReference<MemoryAnswer>() {}) }.getOrNull()

    const val LIMIT = 20
}
