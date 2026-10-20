package dev.phonerecall.brain

/*
 * Birthday catcher: wishes in WhatsApp and SMS group notifications, in English and in
 * Telugu and Hindi (romanized or in script). Runs on the phone; the text goes nowhere.
 */

/** One wish found in a message: the phrase that matched and who it is addressed to, if the text says. */
data class Wish(val phrase: String, val name: String?)

/** "Not a birthday", taught from a card: this name in this group, or a phrase in this group. */
data class BirthdayNegative(val thread: String, val nameKey: String?, val phrase: String?)

object BirthdayCatcher {
    /** Canonical phrases. Spaces match any run of spaces; romanizations vary, so common spellings are listed. */
    val PHRASES = listOf(
        // English
        "happy birthday", "happy bday", "happy b'day", "happy b day", "happiest birthday", "happy belated birthday", "hbd", "hbday",
        "many happy returns",
        // Telugu (romanized)
        "janmadina subhakankshalu", "janmadina shubhakankshalu", "janmadina subhakanshalu", "janma dina subhakankshalu",
        "puttina roju subhakankshalu", "puttinaroju subhakankshalu", "puttina roju shubhakankshalu", "puttinaroju shubhakankshalu",
        "hrudaya poorvaka janmadina subhakankshalu",
        // Hindi / Urdu (romanized)
        "janamdin mubarak", "janmdin mubarak", "janmadin mubarak", "janam din mubarak", "janmdin ki shubhkamnaye", "janmdin ki shubhkamnayein",
        "janamdin ki shubhkamnayein", "janmdin ki hardik shubhkamnayein", "janmadin ki hardik shubhkamnaye", "janmadin ki badhai",
        "saalgirah mubarak", "salgirah mubarak", "saal girah mubarak",
        // In script
        "जन्मदिन मुबारक", "जन्मदिन की शुभकामनाएं", "जन्मदिन की हार्दिक शुभकामनाएं", "పుట్టినరోజు శుభాకాంక్షలు", "జన్మదిన శుభాకాంక్షలు",
    )

    private val patterns: List<Pair<String, Regex>> = PHRASES.map { p ->
        val body = p.split(' ').joinToString("\\s*") { Regex.escape(it) }
        val ascii = p.all { it.code < 128 }
        p to Regex(if (ascii) "(?i)(?<![\\p{L}])$body(?![\\p{L}])" else body)
    }

    /** Words around a name that are not the name: "dear Alex anna", "Alex garu", "to you". */
    private val FILLERS = setOf(
        "dear", "dearest", "my", "our", "to", "you", "u", "ya", "yaa", "all", "everyone", "and", "the", "bro", "bhai", "bhaiya", "ra", "raa",
        "re", "ji", "garu", "gaaru", "anna", "akka", "mama", "babai", "buddy", "man", "da", "di", "sir", "madam", "maam", "boss",
        "once", "again", "wishes", "wish", "many", "more", "a", "very", "lots", "of", "love", "god", "bless", "have", "great", "day",
        "year", "ahead", "returns", "of", "the", "ki", "ko", "ku", "ke", "kaa", "ka", "nuvvu", "meeru", "tum", "aap", "ga", "ho", "hai", "hamesha", "khush", "raho",
    )
    private val MENTION = Regex("@([\\p{L}][\\p{L}.'-]{1,30})")
    private val WORD = Regex("[\\p{L}][\\p{L}'-]*")

    private fun nameish(w: String?): String? {
        val t = w?.trim('\'', '-', '.')?.takeIf { it.length in 2..20 } ?: return null
        if (t.lowercase() in FILLERS) return null
        if (!t.first().isLetter()) return null
        return t.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The first wish in the text, or null. */
    fun detect(text: String): Wish? {
        for ((phrase, re) in patterns) {
            val m = re.find(text) ?: continue
            return Wish(phrase, addressee(text, m.range))
        }
        return null
    }

    /** @mention first; then the name after the phrase ("happy birthday dear Alex anna"); then the one before it ("Alex, hbd", "Alex ki janmadina ..."). */
    fun addressee(text: String, phrase: IntRange): String? {
        MENTION.find(text)?.let { m -> nameish(m.groupValues[1].substringBefore('.'))?.let { return it } }
        val after = WORD.findAll(text.substring(phrase.last + 1)).take(4).map { it.value }.toList()
        for (w in after) {
            if (w.lowercase() in FILLERS) continue
            return nameish(w)
        }
        val before = WORD.findAll(text.substring(0, phrase.first)).map { it.value }.toList().takeLast(3).asReversed()
        for (w in before) {
            if (w.lowercase() in FILLERS) continue
            return nameish(w)
        }
        return null
    }

    /**
     * Who a group is wishing: the most named person across the wishes seen in that group today. A
     * single wish needs an explicit name; with none named, a group called "Alex's birthday" still says.
     */
    fun resolve(wishes: List<Wish>, conversation: String?, owner: Set<String> = OWNER): String? {
        val named = wishes.mapNotNull { it.name }.filter { it.lowercase() !in owner }
        if (named.isNotEmpty()) return named.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val m = Regex("(?i)^([\\p{L}]{2,20})(?:'s)?\\s+(birthday|bday|b'day)").find(conversation.orEmpty().trim())
        return m?.groupValues?.get(1)?.let { nameish(it) }?.takeIf { it.lowercase() !in owner }
    }

    /** His own names: a wish to him is not someone else's birthday to add. */
    val OWNER = setOf("owner", "me")

    /** "Not a birthday" was said for this name in this group, or this phrase was rejected twice in this group. */
    fun blocked(negatives: List<BirthdayNegative>, thread: String, nameKey: String?, phrase: String): Boolean =
        negatives.any { it.thread == thread && it.nameKey != null && it.nameKey == nameKey } ||
            negatives.count { it.thread == thread && it.phrase == phrase } >= 2
}
