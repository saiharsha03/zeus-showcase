package dev.phonerecall.brain

import java.security.MessageDigest

/*
 * The people graph. On the phone only, encrypted (Aegis-level).
 * Relationships come from Contacts' relation fields, from how he saved people ("Amma", "Nanna"), and
 * from his own answers. Priority tiers are learned from how fast he responds per person. The brain
 * only ever sees labels like "family (mother)" or "manager", never names.
 */

enum class Relation(val word: String, val label: String) {
    MOTHER("mother", "family"), FATHER("father", "family"), SISTER("sister", "family"), BROTHER("brother", "family"),
    GRANDPARENT("grandparent", "family"), PARTNER("partner", "family"), RELATIVE("relative", "family"),
    MANAGER("manager", "manager"), COWORKER("coworker", "work"), ROOMMATE("roommate", "roommate"), FRIEND("friend", "friend");

    companion object {
        fun of(s: String?): Relation? = s?.trim()?.let { v -> entries.firstOrNull { it.name.equals(v, true) || it.word.equals(v, true) } }
    }
}

enum class Tier(val word: String, val label: String) {
    REPLY_NOW("reply now", "Reply now"), TODAY("today", "Today"), WHENEVER("whenever", "Whenever"), MUTED("muted", "Muted");

    companion object { fun of(s: String?): Tier? = entries.firstOrNull { it.name == s } }
}

/** How he answers one person: counts and a smoothed response time. */
data class ResponseStats(val n: Int = 0, val engaged: Int = 0, val ewmaMinutes: Double? = null, val lastAt: Long = 0)

/** What a removed notification says about him. */
enum class Response { ENGAGED, DISMISSED, IGNORED }

data class Person(
    val key: String,
    val name: String,
    val relation: Relation? = null,
    /** The word that suggested it ("Amma"), when it came from a saved name. */
    val term: String? = null,
    /** He said yes (or it came from his Contacts relation fields). */
    val confirmed: Boolean = false,
    /** The one-time "Is 'Amma' your mother?" was already asked. */
    val asked: Boolean = false,
    /** His own choice on the People screen; always wins. */
    val pinned: Tier? = null,
    val stats: ResponseStats = ResponseStats(),
    /** Sealed in Calypso: their notifications are never stored. */
    val calypso: Boolean = false,
    /** Seen in a work app (Teams, Slack, Outlook). */
    val work: Boolean = false,
    /** IANA zone where they live: inferred (+91 numbers) or his own choice on the People screen. Null means his own zone. */
    val zone: String? = null,
) {
    /** Their clock, or null when they live where he does. */
    val zoneId: java.time.ZoneId? get() = TwoClocks.zoneOrNull(zone)

    val tier: Tier get() = TierMath.tier(pinned, relation, stats, confirmed)
    val learned: Boolean get() = pinned == null && stats.n >= TierMath.MIN_SAMPLES

    /** What the brain may know about this person. Never the name. */
    val label: String get() = relation?.let { if (it.label == "family") "family (${it.word})" else it.label } ?: if (work) "work contact" else "a contact"
}

object People {
    /** A stable key for a name or number, so nothing indexable holds the name itself. */
    fun keyOf(name: String): String {
        val digits = name.filter { it.isDigit() }
        val norm = if (digits.length >= 7 && digits.length >= name.count { it.isLetter() }) digits.takeLast(10)
        else name.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
        val d = MessageDigest.getInstance("SHA-256").digest(norm.toByteArray(Charsets.UTF_8))
        return d.take(8).joinToString("") { "%02x".format(it) }
    }
}

/** "Amma", "Nanna", "Akka", "Mom": what a saved name tells about the relationship. */
object AddressTerms {
    private val TERMS: Map<String, Relation> = buildMap {
        for (t in listOf("amma", "mom", "mommy", "mummy", "mum", "mother", "maa", "ammi", "mataji", "aai", "ammagaru")) put(t, Relation.MOTHER)
        for (t in listOf("nanna", "nannagaru", "dad", "daddy", "papa", "father", "appa", "abba", "pitaji", "baba")) put(t, Relation.FATHER)
        for (t in listOf("akka", "chelli", "didi", "sis", "sister")) put(t, Relation.SISTER)
        for (t in listOf("anna", "annayya", "tammudu", "thammudu", "bhaiya", "bhai", "brother")) put(t, Relation.BROTHER)
        for (t in listOf("thatha", "tatha", "ammamma", "nanamma", "nani", "dadi", "grandma", "grandpa", "granny")) put(t, Relation.GRANDPARENT)
        for (t in listOf("babai", "pinni", "atta", "attayya", "mavayya", "peddamma", "peddanana", "chinnamma", "chacha", "chachi", "mausi", "bua")) put(t, Relation.RELATIVE)
        // "Mama" is left out on purpose: in Telugu and Hindi it is an uncle, in English a mother.
    }
    /** Words that may follow the term in a saved name without changing who it is: "Amma Cell", "Nanna India". */
    private val QUALIFIERS = setOf("cell", "mobile", "home", "india", "us", "usa", "new", "work", "phone", "number", "jio", "airtel", "whatsapp", "personal", "old", "2")

    /** The relation and the term, when the whole saved name is an address term. "Alex Anna" is not (anna is also an honorific). */
    fun relationOf(savedName: String): Pair<Relation, String>? {
        val words = savedName.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        val first = words.firstOrNull() ?: return null
        val r = TERMS[first] ?: return null
        if (words.drop(1).any { it !in QUALIFIERS }) return null
        return r to savedName.trim().split(Regex("\\s+")).first()
    }
}

/**
 * Priority tiers from response latency. Each removal of a person's notification is one sample: tapped
 * or cleared by the app after he replied (engaged, with the time since the thread's first unanswered
 * notification) or swiped away (dismissed). The response time is an exponentially weighted moving
 * average (alpha 0.3, capped at a day) so recent habits count most; engagement is Laplace-smoothed.
 */
object TierMath {
    const val ALPHA = 0.3
    const val MIN_SAMPLES = 5
    const val CAP_MINUTES = 24 * 60.0
    const val REPLY_NOW_MINUTES = 15.0
    const val TODAY_MINUTES = 6 * 60.0

    fun observe(s: ResponseStats, response: Response, latencyMs: Long?, at: Long): ResponseStats = when (response) {
        Response.IGNORED -> s
        Response.DISMISSED -> s.copy(n = s.n + 1, lastAt = at)
        Response.ENGAGED -> {
            val m = ((latencyMs ?: 0L) / 60_000.0).coerceIn(0.0, CAP_MINUTES)
            s.copy(n = s.n + 1, engaged = s.engaged + 1, ewmaMinutes = s.ewmaMinutes?.let { ALPHA * m + (1 - ALPHA) * it } ?: m, lastAt = at)
        }
    }

    /** Share of notifications he acted on, smoothed toward one half while samples are few. */
    fun engagement(s: ResponseStats): Double = (s.engaged + 1.0) / (s.n + 2.0)

    /** The tier the numbers alone give, or null below five samples. */
    fun learned(s: ResponseStats): Tier? {
        if (s.n < MIN_SAMPLES) return null
        val e = engagement(s)
        val t = s.ewmaMinutes ?: CAP_MINUTES
        return when {
            s.n >= 8 && e < 0.15 -> Tier.MUTED
            t <= REPLY_NOW_MINUTES && e >= 0.6 -> Tier.REPLY_NOW
            t <= TODAY_MINUTES && e >= 0.35 -> Tier.TODAY
            else -> Tier.WHENEVER
        }
    }

    /** Where a relationship starts before there is data. */
    fun prior(r: Relation?): Tier = when (r) {
        Relation.MOTHER, Relation.FATHER, Relation.PARTNER, Relation.MANAGER -> Tier.REPLY_NOW
        Relation.SISTER, Relation.BROTHER, Relation.GRANDPARENT, Relation.ROOMMATE -> Tier.TODAY
        else -> Tier.WHENEVER
    }

    /**
     * His pin wins. Otherwise the learned tier, or the relationship's prior while samples are few.
     * Confirmed parents, partner and manager never learn their way below Today; only his pin can.
     */
    fun tier(pinned: Tier?, relation: Relation?, s: ResponseStats, confirmed: Boolean): Tier {
        pinned?.let { return it }
        val l = learned(s) ?: return prior(relation)
        val floor = confirmed && prior(relation) == Tier.REPLY_NOW
        return if (floor && l.ordinal > Tier.TODAY.ordinal) Tier.TODAY else l
    }
}
