package dev.phonerecall.oneiroi

/**
 * Drafts he will SEND (messages, emails) lose their AI-isms here, after the spirit of Iris's
 * humanizer. On-device rules first; `polish` asks Haiku only for what the rules flag but can't fix.
 */
object Humanizer {
    data class Result(val text: String, val fixed: List<String>, val flagged: List<String>)

    private data class Rule(val name: String, val re: Regex, val with: String)

    private val SENTENCES = listOf(
        "opener" to "(?i)(^|(?<=[.!?\\n]))\\s*(i hope (this|my) (email|message|note|text )?finds you well|i hope you('| a)re (doing )?well|hope you('| a)re (doing )?well|i hope all is well)[.!,]?",
        "opener" to "(?i)(^|(?<=[.!?\\n]))\\s*(certainly|absolutely|great question|of course)!\\s*",
        "closer" to "(?i)\\s*(please )?(don't|do not) hesitate to (reach out|contact me|let me know)[^.!?\\n]*[.!]?",
        "closer" to "(?i)\\s*feel free to (reach out|contact me|let me know)[^.!?\\n]*[.!]?",
        "closer" to "(?i)\\s*i hope this helps[.!]?",
        "closer" to "(?i)\\s*let me know if you have any (other |further )?questions[.!]?",
    )

    private val RULES = listOf(
        // Em and en dashes used as punctuation, and their ASCII stand-ins.
        Rule("dash", Regex("\\s*[—–]\\s*|\\s+--\\s+"), ", "),
        Rule("filler", Regex("(?i)(^|(?<=[.!?]\\s))(furthermore|moreover|additionally|in conclusion|that said|ultimately),\\s+"), ""),
        Rule("word", Regex("(?i)\\bdelve into\\b"), "look at"),
        Rule("word", Regex("(?i)\\butili[sz]e\\b"), "use"),
        Rule("word", Regex("(?i)\\bleverage\\b(?= (the|our|my|your|this|these|it)\\b)"), "use"),
        Rule("word", Regex("(?i)\\bseamless(ly)?\\b"), "smooth$1"),
        Rule("word", Regex("(?i)\\bat the end of the day,?\\s*"), ""),
        Rule("emoji", Regex("[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}]"), ""),
    )

    /** Three adjectives in a row ("fast, reliable, and secure"): keep two. A list of things is left alone. */
    private val TRIPLET = Regex("\\b([a-z]+), ([a-z]+),? and ([a-z]+)\\b")
    private val ADJ = Regex("^(\\w+(ive|ful|less|able|ible|ous|ent|ant|al|ic|ary|ing)|fast|clear|simple|quick|easy|smooth|secure|safe|clean|bold|smart|strong|robust|modern|new|fresh)$")

    private val FLAGS = listOf(
        "not just" to Regex("(?i)\\bnot (just|only) [^.,]{1,40}, (but|it's|it is)\\b"),
        "worth noting" to Regex("(?i)\\bit('s| is) worth noting\\b"),
        "fast-paced" to Regex("(?i)\\bin today's (fast-paced|digital|ever-changing)\\b"),
        "reach out" to Regex("(?i)\\bi wanted to reach out\\b"),
        "tapestry" to Regex("(?i)\\b(tapestry|testament to|game[- ]changer|navigate the complexities)\\b"),
    )

    fun fix(text: String): Result {
        var s = text
        val fixed = ArrayList<String>()
        for ((name, re) in SENTENCES) {
            val r = Regex(re)
            if (r.containsMatchIn(s)) { s = r.replace(s, ""); fixed += name }
        }
        for (rule in RULES) if (rule.re.containsMatchIn(s)) { s = rule.re.replace(s, rule.with); fixed += rule.name }
        s = TRIPLET.replace(s) { m ->
            val (a, b, c) = m.destructured
            if (listOf(a, b, c).all { ADJ.matches(it) }) { fixed += "triplet"; "$a and $b" } else m.value
        }
        // Tidy what the cuts left: doubled spaces and commas, a comma before a full stop, a lower-case start.
        s = s.replace(Regex(",\\s*,"), ",").replace(Regex("\\s+([,.!?])"), "$1").replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("(?m)^\\s*,\\s*"), "").replace(Regex("(?m)^[ \\t]+"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
        s = s.replace(Regex("(^|[.!?]\\s+)([a-z])")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        val flagged = FLAGS.filter { it.second.containsMatchIn(s) }.map { it.first }
        return Result(s, fixed.distinct(), flagged)
    }

    /** Rules first; Haiku only when something flagged is left. The rules run again on what comes back. */
    fun polish(text: String, model: MeteredModel?): Result {
        val first = fix(text)
        if (first.flagged.isEmpty() || model == null) return first
        val reply = runCatching { model.complete(SYSTEM, first.text, 600).text }.getOrNull()?.takeIf { it.isNotBlank() } ?: return first
        val second = fix(reply)
        return second.copy(fixed = (first.fixed + second.fixed + "haiku").distinct())
    }

    private val ASKS = Regex("(?i)\\b(draft|write|compose|reply to|respond to|text|message|email|mail|wish)\\b.{0,40}\\b(to|for|back|him|her|them|my|a|an|the)\\b|\\b(draft|write) (me )?(a|an) (message|email|text|note|reply|wish)\\b")

    /** Did he ask for something to send? Then her answer is a draft and goes through the rules. */
    fun asksForDraft(text: String): Boolean = ASKS.containsMatchIn(text)

    const val SYSTEM = "Rewrite the message so it reads like a person wrote it. Keep the meaning, names, facts and length. No em dashes, no \"not just X, but Y\", no stock phrases, no lists of three adjectives, no greeting or sign-off that was not there. Return only the message."
}
