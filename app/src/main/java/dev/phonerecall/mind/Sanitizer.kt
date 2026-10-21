package dev.phonerecall.mind

import dev.phonerecall.brain.Topic
import dev.phonerecall.brain.Topics

/**
 * Everything the phone writes to the memory store or sends to a session passes through here first.
 * Never out: Calypso content, raw notifications (only derived lines are ever handed in), location
 * trails and coordinates, health, money amounts, contact names, phone numbers and emails.
 *
 * `people` maps a known name to its label ("Amma" to "family (mother)"); `sealed` are Calypso names
 * and terms: a line that mentions one is dropped whole.
 */
class Sanitizer(private val people: Map<String, String> = emptyMap(), private val sealed: Set<String> = emptySet()) {

    /**
     * Built once per sanitizer, not per line: one pattern for every name and one for Calypso's. Compiling ~1,400
     * patterns for each line (every Clio line and request log goes through here) churned tens of MB a second and
     * stalled the main thread on GCs. Longest names first, so "Amma Kumar" is replaced before "Amma".
     */
    private val named: List<Pair<String, String>> by lazy { people.entries.filter { it.key.length >= 2 }.sortedByDescending { it.key.length }.map { it.key to it.value } }
    private val names: Regex? by lazy { words(named.map { it.first }) }
    private val labels: Map<String, String> by lazy { HashMap<String, String>().also { m -> for ((n, l) in named) m.putIfAbsent(n.lowercase(), l) } }
    private val calypso: Regex? by lazy { words(sealed.filter { it.isNotBlank() }) }

    private fun words(list: List<String>): Regex? = list.takeIf { it.isNotEmpty() }?.let { w -> Regex("(?i)\\b(?:" + w.joinToString("|") { Regex.escape(it) } + ")\\b") }

    /** A match's label; a name the lowercase map misses (odd case folding) still never goes out as itself. */
    private fun label(match: String): String = labels[match.lowercase()] ?: named.firstOrNull { it.first.equals(match, true) }?.second ?: "[name]"

    fun clean(text: String): String = text.lineSequence().mapNotNull { line(it) }.joinToString("\n").trim()

    /**
     * His own chat words to Athena ("Athena reads all of Zeus but Calypso"): as he wrote them,
     * names, numbers and amounts included. Only two things stay on the phone: a line naming something sealed in Calypso,
     * and a line on an off-limits topic (health, immigration, dating). Third-party text he quotes is defused by the caller.
     */
    fun chat(text: String): String = text.lineSequence().filter { l ->
        calypso?.containsMatchIn(l) != true && Topics.of(l).none { it == Topic.HEALTH || it == Topic.IMMIGRATION || it == Topic.DATING }
    }.joinToString("\n").trim()

    /** A JSON tool result: every string value cleaned on its own; a dropped one becomes "[withheld]". Not JSON: clean(). */
    fun json(text: String): String {
        val mapper = com.anthropic.core.jsonMapper()
        val tree = runCatching { mapper.readTree(text) }.getOrNull()?.takeIf { it.isContainerNode } ?: return clean(text)
        fun walk(n: com.fasterxml.jackson.databind.JsonNode): com.fasterxml.jackson.databind.JsonNode = when {
            n.isTextual -> com.fasterxml.jackson.databind.node.TextNode(line(n.asText()) ?: "[withheld]")
            n.isObject -> mapper.createObjectNode().also { o -> n.fields().forEach { (k, v) -> o.set<com.fasterxml.jackson.databind.JsonNode>(k, walk(v)) } }
            n.isArray -> mapper.createArrayNode().also { a -> n.forEach { a.add(walk(it)) } }
            else -> n
        }
        return mapper.writeValueAsString(walk(tree))
    }

    /** One line, cleaned, or null when the whole line must go. */
    fun line(raw: String): String? {
        var s = raw
        if (calypso?.containsMatchIn(s) == true) return null
        val topics = Topics.of(s)
        if (Topic.HEALTH in topics || Topic.IMMIGRATION in topics || Topic.DATING in topics) return null
        if (TRAIL.containsMatchIn(s)) return null
        s = MONEY.replace(s, "[amount]")
        if (Topic.MONEY in topics) s = DIGITS.replace(s, "#")
        s = EMAIL.replace(s, "[email]")
        s = COORD.replace(s, "[place]")
        s = PHONE.replace(s, "[number]")
        s = ADDRESS.replace(s, "[address]")
        names?.let { r -> s = r.replace(s) { label(it.value) } }
        return s.takeIf { it.isNotBlank() }
    }

    companion object {
        private val MONEY = Regex("(?i)(?:[$₹€£]\\s?\\d[\\d,]*(?:\\.\\d+)?)|(?:\\b\\d[\\d,]*(?:\\.\\d+)?\\s?(?:usd|dollars?|bucks|rupees?|inr|rs\\.?|eur|gbp)\\b)|(?:\\b(?:rs\\.?|inr)\\s?\\d[\\d,]*)")
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val COORD = Regex("-?\\d{1,3}\\.\\d{3,}\\s*,\\s*-?\\d{1,3}\\.\\d{3,}")
        // North American numbers (716-555-0142, (716) 555 0199, +1 716 555 0199) and +country numbers.
        // ISO dates and times (2026-10-20T08:00) are not phone numbers and pass.
        private val PHONE = Regex("(?<![\\w#:-])(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]\\d{3}[\\s.-]\\d{4}(?![\\w-])|\\+\\d{1,3}[\\s.-]?\\d{2,5}[\\s.-]?\\d{3,5}[\\s.-]?\\d{3,5}")
        private val ADDRESS = Regex("\\b\\d{1,6}\\s+(?:[A-Z][a-z]+\\s){1,4}(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Pkwy|Parkway)\\b\\.?")
        private val TRAIL = Regex("(?i)\\b(location history|route taken|gps trail|visited at \\d|lat(itude)?\\s*[:=]|lng|longitude\\s*[:=])")
        private val DIGITS = Regex("\\d")
    }
}
