package dev.phonerecall.brain

import android.content.Context

/*
 * The group birthday wish: when a WhatsApp group starts wishing someone, a card offers his wish as one chip, in
 * his own style for that group (the wording he and his family use there, the relation word, his emoji). One tap
 * sends it to that group through its notification's reply. Everything here stays on the phone.
 */
object GroupWish {
    const val NAME = "{name}"
    const val DEFAULT = "Happy birthday {name} 🎉"
    private val EMOJI = Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]\x{FE0F}?""")

    /** A wish turned into a wording for this group: the birthday person's name becomes {name}. */
    fun template(text: String, name: String?): String {
        val t = text.lineSequence().first().trim().take(120)
        if (name.isNullOrBlank()) return t
        return t.replace(Regex("""(?i)(?<![\p{L}])${Regex.escape(name)}(?![\p{L}])"""), NAME)
    }

    /** The emoji he puts in his wishes, most used first. */
    fun emojis(text: String): List<String> = EMOJI.findAll(text).map { it.value }.toList()

    /**
     * What he calls the birthday person: the relation word he saved them under ("Amma", "Nanna"); for elders the
     * family suffix his group uses ("anna", "akka", "bava", "mama", "aunty", "uncle"), when his wordings carry it.
     */
    fun address(name: String, person: Person?): String {
        val term = person?.term?.takeIf { it.isNotBlank() }
        return when (person?.relation) {
            Relation.MOTHER, Relation.FATHER, Relation.GRANDPARENT -> term ?: name
            else -> term?.takeIf { !it.equals(name, true) }?.let { "$name $it" } ?: name
        }
    }

    /**
     * The wish he'd send: his own wording for this group first (his count three times a relative's), then the
     * family's, then the default; his usual emoji when the wording has none.
     */
    fun wording(templates: Map<String, Int>, addressed: String, emoji: String?): String {
        val best = templates.filterKeys { it.isNotBlank() }.maxByOrNull { it.value }?.key ?: DEFAULT
        var text = if (best.contains(NAME)) best.replace(NAME, addressed) else best
        if (emoji != null && emojis(text).isEmpty()) text = "$text $emoji"
        if (emoji != null && best == DEFAULT) text = text.replace("🎉", emoji)
        return text.replace(Regex("\\s+"), " ").trim()
    }

    // ---- learning, per group (on the phone only) ----

    private fun sp(c: Context) = c.getSharedPreferences("wish_style", Context.MODE_PRIVATE)
    private fun group(thread: String) = "g" + thread.hashCode().toUInt().toString(36)

    /** A wish seen in a group: his own counts 3, family's 1. Others' wordings aren't learned. */
    fun learn(c: Context, thread: String, text: String, name: String?, his: Boolean) {
        val t = template(text, name)
        if (t.isBlank()) return
        val k = "${group(thread)}|t|$t"
        val e = sp(c).edit().putInt(k, sp(c).getInt(k, 0) + if (his) 3 else 1)
        if (his) for (m in emojis(text)) { val ek = "me|e|$m"; e.putInt(ek, sp(c).getInt(ek, 0) + 1) }
        e.apply()
    }

    fun templates(c: Context, thread: String): Map<String, Int> {
        val g = group(thread) + "|t|"
        return sp(c).all.mapNotNull { (k, v) -> if (k.startsWith(g) && v is Int) k.removePrefix(g) to v else null }.toMap()
    }

    fun emoji(c: Context): String? = sp(c).all.mapNotNull { (k, v) -> if (k.startsWith("me|e|") && v is Int) k.removePrefix("me|e|") to v else null }.maxByOrNull { it.second }?.first

    // ---- the card's wish, in memory ----

    private val open = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    /** The card for [thread] wishes [name] with [text]. */
    fun offer(thread: String, name: String, text: String) { open[thread] = name to text }

    fun offered(thread: String): Pair<String, String>? = open[thread]

    /** He sent [text] on [note]: a wish card's birthday is done, and his wording is learned (kept or edited). */
    fun sent(c: Context, note: String, text: String) {
        for (card in LiveActions.cardsOf(note).filter { it.startsWith("wish:") }) {
            val thread = card.removePrefix("wish:")
            val (name, _) = open.remove(thread) ?: continue
            learn(c, thread, text, name, his = true)
            runCatching { BirthdayDay.engaged(c, thread, name) }
        }
    }

    fun clear() = open.clear()
}

/** Splitwise's notifications: who owes whom, read on the phone for the card only. */
object Splitwise {
    const val PKG = "com.Splitwise.SplitwiseMobile"

    enum class Kind { OWE, OWED, SETTLED, OTHER }

    data class Line(val kind: Kind, val amount: String?)

    private val AMOUNT = Regex("""(?:[$₹€£]|Rs\.?\s?|INR\s?|USD\s?)\s?\d[\d,]*(?:\.\d{1,2})?""")
    private val OWE = Regex("""(?i)\byou owe\b""")
    private val OWED = Regex("""(?i)\b(you get back|you are owed|you're owed|owes you|you lent)\b""")
    private val SETTLED = Regex("""(?i)\b(settled up|recorded a payment|paid you|you paid|all settled)\b""")

    fun read(text: String): Line {
        val amount = AMOUNT.find(text)?.value?.replace(Regex("\\s+"), "")
        val kind = when {
            SETTLED.containsMatchIn(text) -> Kind.SETTLED
            OWE.containsMatchIn(text) -> Kind.OWE
            OWED.containsMatchIn(text) -> Kind.OWED
            else -> Kind.OTHER
        }
        return Line(kind, amount)
    }

    /** The card's title: the money he owes or is owed, plainly. */
    fun title(l: Line): String = when (l.kind) {
        Kind.OWE -> "You owe" + (l.amount?.let { " $it" } ?: "")
        Kind.OWED -> "You get back" + (l.amount?.let { " $it" } ?: "")
        Kind.SETTLED -> "Splitwise: settled"
        Kind.OTHER -> "Splitwise update"
    }
}
