package dev.phonerecall.theogony

import dev.phonerecall.brain.AddressTerms
import dev.phonerecall.brain.People
import dev.phonerecall.brain.Relation

/*
 * The People picture for Theogony. On the phone only: names stay here (the roster), the memory store gets
 * labels, relationships and tiers ("family (mother)", "inner circle: a friend"), never a name, number or JID.
 *
 * Sources: Contacts relation fields, his own saved names and nicknames (Amma, Nanna, ...), starred contacts,
 * contact groups (Family, Work, Emergency/ICE), +91 numbers or India WhatsApp JIDs (only a yes/no is kept),
 * and how often each person messages or calls (counts only, never content).
 *
 * Android has no readable emergency-contact list for third-party apps (Samsung's and Safety's are private), so
 * the only signals are the ICE naming convention and an Emergency/ICE contact group; otherwise the interview asks.
 * WhatsApp has no API for pinned or priority chats: priority is inferred from counts, then confirmed by him.
 * How fast he replies is kept as an observation only: a fast reply is not importance.
 */

enum class PriorityTier(val label: String) { INNER_CIRCLE("Inner circle"), FAMILY("Family"), WORK("Work"), OTHERS("Others") }

/** One contact as the reader saw it. `phones` are used here for the India check and dropped. */
data class ContactInfo(
    val name: String,
    val starred: Boolean = false,
    val groups: List<String> = emptyList(),
    val nicknames: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
)

/** Counts for one 1:1 chat or caller over the last weeks. `replyMinutes` is an observation, never a ranking input. */
data class ChatSignal(val name: String, val messages: Int = 0, val calls: Int = 0, val replyMinutes: Double? = null)

/** Everything the people reader hands over. `busyGroups` counts group chats with a lot of traffic (names unknown). */
data class PeopleInput(
    val contacts: List<ContactInfo> = emptyList(),
    /** Relation fields from Contacts: (the related person's name, the relation). */
    val relations: List<Pair<String, Relation>> = emptyList(),
    val signals: List<ChatSignal> = emptyList(),
    val busyGroups: Int = 0,
    /** People the graph already knows for sure (his "yes" on Harmonia). */
    val confirmed: Map<String, Relation> = emptyMap(),
)

/** One person in the roster, on the phone. `status` is observed, inferred or confirmed. */
data class RosterPerson(
    val key: String = "",
    val name: String = "",
    val relation: String? = null,
    val relationStatus: String = Status.OBSERVED,
    val tier: PriorityTier = PriorityTier.OTHERS,
    val tierStatus: String = Status.OBSERVED,
    val india: Boolean = false,
    val emergency: Boolean = false,
    val emergencyStatus: String = Status.OBSERVED,
    val starred: Boolean = false,
    val alwaysThrough: Boolean = false,
    val quiet: Boolean = false,
    val score: Int = 0,
    val replyMinutes: Double? = null,
) {
    val rel: Relation? get() = Relation.of(relation)
    /** What the brain may know: the relationship, never the name. */
    val label: String get() = rel?.let { if (it.label == "family") "family (${it.word})" else it.label } ?: when (tier) {
        PriorityTier.WORK -> "a work contact"
        else -> "a contact"
    }
}

object Status {
    const val OBSERVED = "observed"
    const val INFERRED = "inferred"
    const val CONFIRMED = "confirmed"
}

object PeopleSeed {
    // ContactsContract.CommonDataKinds.Relation.TYPE_* (stable public constants).
    private val RELATION_TYPES = mapOf(
        2 to Relation.BROTHER, 3 to Relation.RELATIVE, 4 to Relation.PARTNER, 5 to Relation.FATHER, 6 to Relation.FRIEND,
        7 to Relation.MANAGER, 8 to Relation.MOTHER, 9 to Relation.RELATIVE, 10 to Relation.PARTNER, 12 to Relation.RELATIVE,
        13 to Relation.SISTER, 14 to Relation.PARTNER,
    )
    const val INNER_MAX = 5
    const val INNER_MIN_SCORE = 10
    private val ICE = Regex("(?i)^\\s*(ice\\d*\\b|in case of emergency\\b)[\\s:-]*")
    private val EMERGENCY_GROUP = Regex("(?i)\\b(emergency|ice)\\b")
    private val FAMILY_GROUP = Regex("(?i)\\bfamily\\b")
    private val WORK_GROUP = Regex("(?i)\\b(work|coworkers?|colleagues?|office)\\b")

    /** A Contacts relation row's type, or a custom label read as a nickname ("Amma"). */
    fun relationFromType(type: Int, customLabel: String? = null): Relation? =
        RELATION_TYPES[type] ?: customLabel?.let { nickname(it) }

    /** "Amma", "Nanna India", "ICE Dad": the relation a saved name or nickname tells. */
    fun nickname(saved: String): Relation? {
        val s = saved.replace(ICE, "").trim()
        AddressTerms.relationOf(s)?.let { return it.first }
        val words = s.lowercase().replace(Regex("[^\\p{L} ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.singleOrNull()?.let { IN_LAWS[it] }
    }

    /** Telugu family words AddressTerms leaves out (in-laws, elder and younger uncles). Whole saved name only. */
    private val IN_LAWS = listOf("bava", "vadina", "chinna", "chinnanna", "peddanna", "chinnayya", "mamayya", "bavagaru", "vadinagaru").associateWith { Relation.RELATIVE }

    /** A +91 number or an India WhatsApp JID. Only this yes/no ever leaves the reader. */
    fun india(phones: List<String>): Boolean = phones.any { p ->
        val jid = p.substringBefore('@', p)
        val digits = jid.filter(Char::isDigit)
        p.trim().startsWith("+91") || (digits.length == 12 && digits.startsWith("91")) || (p.trim().startsWith("0091") && digits.length == 14)
    }

    fun emergency(c: ContactInfo): Boolean = ICE.containsMatchIn(c.name) || c.groups.any { EMERGENCY_GROUP.containsMatchIn(it) }

    /** Ranking: messages, calls (x3), starred (+15), family (+10). Reply speed is left out on purpose. */
    fun score(messages: Int, calls: Int, starred: Boolean, family: Boolean): Int = messages + 3 * calls + (if (starred) 15 else 0) + (if (family) 10 else 0)

    /**
     * The roster from fresh input, keeping every choice he already made in `previous` (confirmed fields win).
     */
    fun build(input: PeopleInput, previous: List<RosterPerson>): List<RosterPerson> {
        val prev = previous.associateBy { it.key }
        val byKey = LinkedHashMap<String, RosterPerson>()
        fun put(name: String, f: (RosterPerson) -> RosterPerson) {
            val clean = name.replace(ICE, "").trim().ifEmpty { name.trim() }
            if (clean.isEmpty()) return
            val k = People.keyOf(clean)
            byKey[k] = f(byKey[k] ?: RosterPerson(k, clean))
        }
        for (c in input.contacts) put(c.name) { p ->
            val nick = (listOf(c.name) + c.nicknames).firstNotNullOfOrNull { nickname(it) }
            val fromGroup = when {
                c.groups.any { FAMILY_GROUP.containsMatchIn(it) } -> PriorityTier.FAMILY
                c.groups.any { WORK_GROUP.containsMatchIn(it) } -> PriorityTier.WORK
                else -> null
            }
            p.copy(
                relation = p.relation ?: nick?.name,
                relationStatus = if (p.relation == null && nick != null) Status.INFERRED else p.relationStatus,
                tier = fromGroup ?: p.tier,
                india = p.india || india(c.phones),
                emergency = p.emergency || emergency(c),
                starred = p.starred || c.starred,
            )
        }
        // Relation fields are his own words in Contacts: observed, and they beat a nickname guess.
        for ((name, r) in input.relations) put(name) { it.copy(relation = r.name, relationStatus = Status.OBSERVED) }
        for ((name, r) in input.confirmed) put(name) { it.copy(relation = r.name, relationStatus = Status.CONFIRMED) }
        for (s in input.signals) put(s.name) { p -> p.copy(score = p.score + s.messages + 3 * s.calls, replyMinutes = s.replyMinutes ?: p.replyMinutes) }

        var out = byKey.values.map { p ->
            val r = p.rel
            val family = r != null && r.label == "family"
            val tier = when {
                family -> PriorityTier.FAMILY
                r == Relation.MANAGER || r == Relation.COWORKER -> PriorityTier.WORK
                else -> p.tier
            }
            p.copy(tier = tier, tierStatus = if (tier == PriorityTier.OTHERS) Status.OBSERVED else p.relationStatus.takeIf { r != null } ?: Status.OBSERVED,
                score = p.score + (if (p.starred) 15 else 0) + (if (family) 10 else 0))
        }
        // Inner circle: a hypothesis from the counts, for him to confirm.
        // Family keeps its own tier; the inner-circle guess is about everyone else.
        val inner = out.filter { it.tier == PriorityTier.OTHERS && it.score >= INNER_MIN_SCORE }.sortedWith(compareBy({ -it.score }, { it.name })).take(INNER_MAX).map { it.key }.toSet()
        out = out.map { if (it.key in inner) it.copy(tier = PriorityTier.INNER_CIRCLE, tierStatus = Status.INFERRED) else it }
        // His earlier answers win over anything new.
        out = out.map { p ->
            val old = prev[p.key] ?: return@map p
            var q = p.copy(alwaysThrough = old.alwaysThrough, quiet = old.quiet)
            // A confirmed relation (or his "not family") wins, with the tier it gave.
            if (old.relationStatus == Status.CONFIRMED) q = q.copy(relation = old.relation, relationStatus = Status.CONFIRMED, tier = old.tier, tierStatus = old.tierStatus)
            if (old.tierStatus == Status.CONFIRMED) q = q.copy(tier = old.tier, tierStatus = Status.CONFIRMED)
            if (old.emergencyStatus == Status.CONFIRMED) q = q.copy(emergency = old.emergency, emergencyStatus = Status.CONFIRMED)
            q
        }
        // People he added by hand (typed an emergency contact not in Contacts).
        out = out + previous.filter { o -> out.none { it.key == o.key } && (o.emergencyStatus == Status.CONFIRMED || o.tierStatus == Status.CONFIRMED || o.alwaysThrough || o.quiet) }
        return out.filter { it.rel != null || it.tier != PriorityTier.OTHERS || it.emergency || it.starred || it.alwaysThrough || it.quiet || it.score > 0 }
    }

    private fun count(n: Int) = when (n) { 1 -> "one"; 2 -> "two"; 3 -> "three"; 4 -> "four"; 5 -> "five"; else -> "several" }

    /** Labels joined: "family (mother), manager and two contacts". */
    fun labels(ps: List<RosterPerson>): String {
        val named = ps.map { it.label }
        val anon = named.count { it == "a contact" || it == "a work contact" }
        val words = named.filter { it != "a contact" && it != "a work contact" }.distinct() +
            if (anon == 0) emptyList() else listOf(if (anon == 1) named.first { it.startsWith("a ") } else if (named.size == anon) "${count(anon)} contacts" else "${count(anon)} other contacts")
        return when (words.size) { 0 -> "nobody"; 1 -> words[0]; else -> words.dropLast(1).joinToString(", ") + " and " + words.last() }
    }

    /** The memory lines: labels, relationships, tiers and status only. */
    fun memoryLines(roster: List<RosterPerson>, busyGroups: Int): List<SeedFact> {
        val out = ArrayList<SeedFact>()
        fun line(text: String, status: String, topic: String, conf: Double = 0.8) {
            out += SeedFact(SeedPath.PEOPLE, text, "people", conf, topic, status = status)
        }
        for (status in listOf(Status.CONFIRMED, Status.OBSERVED, Status.INFERRED)) {
            val fam = roster.filter { it.tier == PriorityTier.FAMILY && it.tierStatus == status }
            if (fam.isNotEmpty()) {
                val india = fam.count { it.india }
                line("Family: ${labels(fam)}" + (if (india == fam.size) ", in India." else if (india > 0) ", some in India." else "."), status, "people_family", if (status == Status.INFERRED) 0.6 else 0.9)
            }
            val inner = roster.filter { it.tier == PriorityTier.INNER_CIRCLE && it.tierStatus == status }
            if (inner.isNotEmpty()) line("Inner circle: ${labels(inner)}.", status, "people_inner", if (status == Status.INFERRED) 0.6 else 0.9)
            val work = roster.filter { it.tier == PriorityTier.WORK && it.tierStatus == status }
            if (work.isNotEmpty()) line("Work: ${labels(work)}.", status, "people_work")
            val sos = roster.filter { it.emergency && it.emergencyStatus == status }
            if (sos.isNotEmpty()) line("Emergency contact: ${labels(sos)}.", status, "people_emergency", 0.9)
        }
        roster.filter { it.alwaysThrough }.takeIf { it.isNotEmpty() }?.let { line("Always gets through, even in Do Not Disturb: ${labels(it)}.", Status.CONFIRMED, "people_always", 1.0) }
        roster.filter { it.quiet }.takeIf { it.isNotEmpty() }?.let { line("Keep quiet unless urgent: ${labels(it)}.", Status.CONFIRMED, "people_quiet", 1.0) }
        if (busyGroups > 0) line("Has ${count(busyGroups)} busy WhatsApp group chats.", Status.OBSERVED, "people_groups", 0.8)
        roster.filter { it.replyMinutes != null && it.replyMinutes <= 15 && it.rel != null }.take(3).takeIf { it.isNotEmpty() }
            ?.let { line("Usually replies within minutes to ${labels(it)} (an observation, not a ranking).", Status.OBSERVED, "people_reply", 0.7) }
        return out
    }

    // ---- interview ----

    const val ALWAYS = "p:always_through"
    const val EMERGENCY = "p:emergency"
    const val FAMILY = "p:family_confirm"
    const val TOP = "p:top_chats"
    const val QUIET = "p:quiet"
    const val NOBODY = "Nobody"
    const val NONE = "None of these"
    const val TYPE_IT = "I'll type it"

    private fun famChip(p: RosterPerson) = "${p.name}: ${p.rel?.word}"

    /** The people questions still worth asking, given the roster. */
    fun questions(roster: List<RosterPerson>, asked: Set<String>): List<Question> {
        val out = ArrayList<Question>()
        val ranked = roster.sortedWith(compareBy({ -it.score }, { it.name }))
        val unsureFamily = roster.filter { it.tier == PriorityTier.FAMILY && it.relationStatus == Status.INFERRED }
        if (FAMILY !in asked && unsureFamily.isNotEmpty())
            out += Question(FAMILY, text = "Is this your family? Tap the ones that are right.", chips = unsureFamily.take(8).map(::famChip) + NONE, multi = true)
        if (ALWAYS !in asked && roster.none { it.alwaysThrough }) {
            val c = (roster.filter { it.tier == PriorityTier.FAMILY || it.tier == PriorityTier.INNER_CIRCLE || it.starred }.sortedWith(compareBy({ -it.score }, { it.name }))).take(6)
            if (c.isNotEmpty()) out += Question(ALWAYS, text = "Who should always get through, even in Do Not Disturb?", chips = c.map { it.name } + NOBODY, multi = true)
        }
        if (EMERGENCY !in asked && roster.none { it.emergency }) {
            val c = roster.filter { it.tier == PriorityTier.FAMILY }.take(4).map { it.name }
            out += Question(EMERGENCY, text = "Who is your emergency contact?", chips = c + TYPE_IT)
        }
        val chats = ranked.filter { it.score > 0 }.take(8)
        if (TOP !in asked && chats.size >= 2 && roster.none { it.tier == PriorityTier.INNER_CIRCLE && it.tierStatus == Status.CONFIRMED })
            out += Question(TOP, text = "Which WhatsApp chats matter most to you? Pick up to five.", chips = chats.map { it.name } + NONE, multi = true)
        val loud = ranked.filter { it.tier == PriorityTier.OTHERS && it.score > 0 }.take(5)
        if (QUIET !in asked && loud.isNotEmpty() && roster.none { it.quiet })
            out += Question(QUIET, text = "Anyone to keep quiet unless it's urgent?", chips = loud.map { it.name } + NOBODY, multi = true)
        return out
    }

    fun stillOpen(id: String, roster: List<RosterPerson>): Boolean = questions(roster, emptySet()).any { it.id == id }

    /** Picks from an answer: chip words, or names he typed that match the roster. */
    private fun picks(answer: String, roster: List<RosterPerson>): List<RosterPerson> {
        val parts = answer.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
        return roster.filter { p -> parts.any { it.equals(p.name, true) || it.equals(famChip(p), true) } }
    }

    /** Applies his answer to the roster. Everything he picks is confirmed; a hypothesis he didn't pick goes. */
    fun apply(id: String, answer: String, roster: List<RosterPerson>): List<RosterPerson> {
        val picked = picks(answer, roster).map { it.key }.toSet()
        return when (id) {
            FAMILY -> roster.map { p ->
                if (p.tier != PriorityTier.FAMILY || p.relationStatus != Status.INFERRED) p
                else if (p.key in picked) p.copy(relationStatus = Status.CONFIRMED, tierStatus = Status.CONFIRMED)
                else p.copy(relation = null, relationStatus = Status.CONFIRMED, tier = PriorityTier.OTHERS, tierStatus = Status.CONFIRMED)
            }
            ALWAYS -> roster.map { if (it.key in picked) it.copy(alwaysThrough = true) else it }
            QUIET -> roster.map { if (it.key in picked) it.copy(quiet = true, alwaysThrough = false) else it }
            TOP -> roster.map { p ->
                when {
                    p.key in picked && p.tier == PriorityTier.FAMILY -> p.copy(tierStatus = Status.CONFIRMED, relationStatus = Status.CONFIRMED)
                    p.key in picked -> p.copy(tier = PriorityTier.INNER_CIRCLE, tierStatus = Status.CONFIRMED)
                    p.tier == PriorityTier.INNER_CIRCLE && p.tierStatus == Status.INFERRED ->
                        p.copy(tier = PriorityTier.OTHERS, tierStatus = Status.CONFIRMED)
                    else -> p
                }
            }
            EMERGENCY -> {
                if (picked.isNotEmpty()) roster.map { if (it.key in picked) it.copy(emergency = true, emergencyStatus = Status.CONFIRMED) else it }
                else {
                    val name = answer.split(';', ',').first().trim().takeIf { it.isNotEmpty() && it != TYPE_IT } ?: return roster
                    val k = People.keyOf(name)
                    roster.filter { it.key != k } + RosterPerson(k, name, emergency = true, emergencyStatus = Status.CONFIRMED)
                }
            }
            else -> roster
        }
    }

    /** "Confirm" on an inferred people line in What Athena remembers. */
    fun confirmTopic(topic: String, roster: List<RosterPerson>): List<RosterPerson> = roster.map { p ->
        when (topic) {
            "people_family" -> if (p.tier == PriorityTier.FAMILY && p.relationStatus == Status.INFERRED) p.copy(relationStatus = Status.CONFIRMED, tierStatus = Status.CONFIRMED) else p
            "people_inner" -> if (p.tier == PriorityTier.INNER_CIRCLE && p.tierStatus == Status.INFERRED) p.copy(tierStatus = Status.CONFIRMED) else p
            else -> p
        }
    }

    /** "Forget" on an inferred people line: the hypothesis goes. */
    fun dropTopic(topic: String, roster: List<RosterPerson>): List<RosterPerson> = roster.map { p ->
        when (topic) {
            "people_family" -> if (p.tier == PriorityTier.FAMILY && p.relationStatus == Status.INFERRED) p.copy(relation = null, relationStatus = Status.CONFIRMED, tier = PriorityTier.OTHERS, tierStatus = Status.CONFIRMED) else p
            "people_inner" -> if (p.tier == PriorityTier.INNER_CIRCLE && p.tierStatus == Status.INFERRED) p.copy(tier = PriorityTier.OTHERS, tierStatus = Status.CONFIRMED) else p
            else -> p
        }
    }
}

/**
 * The read other features use (DND bypass suggestions, Kairos, Horae, Argo). On the phone, names allowed.
 * Only observed and confirmed facts answer; inferred ones are in [suggestions], never treated as fact.
 */
class PeoplePriority(private val roster: List<RosterPerson>) {
    private fun known(p: RosterPerson) = p.tierStatus != Status.INFERRED

    fun tierOf(name: String): PriorityTier? = roster.firstOrNull { it.key == People.keyOf(name) }?.takeIf(::known)?.tier
    fun innerCircle(): List<String> = roster.filter { it.tier == PriorityTier.INNER_CIRCLE && it.tierStatus == Status.CONFIRMED }.map { it.name }
    fun family(): List<String> = roster.filter { it.tier == PriorityTier.FAMILY && it.relationStatus != Status.INFERRED }.map { it.name }
    fun alwaysThrough(): List<String> = roster.filter { it.alwaysThrough }.map { it.name }
    fun quietUnlessUrgent(): List<String> = roster.filter { it.quiet }.map { it.name }
    fun emergency(): List<String> = roster.filter { it.emergency && it.emergencyStatus != Status.INFERRED }.map { it.name }
    /** Hypotheses for him to confirm: never act on these. */
    fun suggestions(): List<RosterPerson> = roster.filter { it.tierStatus == Status.INFERRED || it.relationStatus == Status.INFERRED }

    companion object { val EMPTY = PeoplePriority(emptyList()) }
}
