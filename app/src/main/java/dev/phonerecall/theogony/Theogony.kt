package dev.phonerecall.theogony

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.list
import dev.phonerecall.mind.long
import dev.phonerecall.mind.putList
import dev.phonerecall.mind.putLong
import dev.phonerecall.mind.putStrings
import dev.phonerecall.mind.sha
import dev.phonerecall.mind.stateJson
import dev.phonerecall.mind.strings

/*
 * Theogony ("Getting to know you"): Athena's first run. Nothing here knows about Android.
 *  1. Seed: ask Iris for everything she may share (one ask.request over the Zeus channel), read the phone
 *     (words only) and build the People picture. Every line passes the Sanitizer and SeedFilter, then lands in
 *     the memory store as a short fact with a source, a confidence and a status:
 *       observed  - a signal summary (counts, what a document or his Contacts say)
 *       inferred  - a hypothesis ("inner circle: a friend"); never treated as fact by other features
 *       confirmed - he said so (the interview, or Confirm in What Athena remembers)
 *  2. Conversation: Athena opens with what she believes (a bundle he ticks), then asks only the gaps, one at
 *     a time, with quick replies and free text; her guesses come back in bundles of five. She may hand a gap to
 *     Iris mid-way. Every answer is a confirmed fact; an unticked guess is deleted. "skip" and "later" work.
 *  3. Done: proactive cards start (or 10 minutes after the seed began, if Iris never answered).
 */

/** Where a fact lives in Athena's memory store: /<path>/theogony.md. */
enum class SeedPath(val dir: String, val title: String) {
    PROFILE("profile", "About him"),
    PREFERENCES("preferences", "Likes and how he wants things"),
    PEOPLE("people", "People, as labels"),
    ROUTINES("routines", "His week"),
    PLANS("plans", "Plans and dates ahead"),
    SNAPSHOT("snapshot", "Right now"),
}

/**
 * One short factual line. `source` is iris, phone, people or you; `topic` is the Iris section or checklist id;
 * `status` observed, inferred or confirmed; `date` an ISO date for milestones.
 */
data class SeedFact(
    val path: SeedPath = SeedPath.PROFILE,
    val text: String = "",
    val source: String = "",
    val confidence: Double = 0.5,
    val topic: String = "",
    val status: String = Status.OBSERVED,
    val date: String? = null,
) {
    val key: String get() = sha(path.dir + "|" + text.lowercase().replace(Regex("\\W+"), " ").trim())
    val sure: String get() = if (confidence >= 0.8) "sure" else if (confidence >= 0.6) "likely" else "unsure"
    val milestone: Boolean get() = topic == Seed.MILESTONES && date != null
}

/** A phone source's raw output, before the filters. */
data class RawFact(val path: SeedPath, val text: String, val confidence: Double, val topic: String, val status: String = Status.OBSERVED)

/** One on-device reader. read() returns null when it can't run (no permission, not connected, sharing off). */
interface PhoneSource {
    val label: String
    fun read(): List<RawFact>?
}

/** The People reader (Contacts, the senses' message counts, calls). Null when Contacts aren't allowed. */
fun interface PeopleSource {
    fun read(): PeopleInput?
}

/** The Zeus channel to Iris. send() returns the gateway's message id; reply() her answer to it, or null. */
interface IrisLink {
    fun available(): Boolean
    fun send(text: String, clientId: String): Long
    fun reply(messageId: Long): String?
}

/** Athena's memory store, as whole files (path to content; blank content removes the file). */
fun interface SeedMemory {
    fun write(files: Map<String, String>)
}

/** Athena's one memory (Oneiroi's rows): a fact he confirmed goes in once, a confirmed fact he forgets comes out. */
interface SeedRows {
    fun confirmed(f: SeedFact)
    fun forgot(f: SeedFact)
}

/** Settings the interview may change, each one only after he confirms its proposal. */
fun interface SeedSettings {
    fun autoApprove(kindWire: String, on: Boolean)
}

// ---- the ask.request envelope (shared with argo's ask_worker) ----

data class AskSection(val id: String, val title: String, val path: SeedPath, val ask: String)

/** The seed ask on the mind's envelope ([dev.phonerecall.mind.AskEnvelope]), with the seed's own fallbacks. */
object SeedAsk {
    fun text(id: String): String =
        dev.phonerecall.mind.AskEnvelope.text(dev.phonerecall.mind.AskRequest(id, Seed.TOPIC, Seed.QUESTION)) + "\nRules: /opt/zeus/docs/IRIS-SEED.md"

    /**
     * Her answer, as data: the ask.reply JSON (also when it isn't fenced); one for another id is refused. Plain
     * text falls back to "## section" headings, or everything under must_know.
     */
    fun parse(text: String, expectId: String): dev.phonerecall.mind.AskReply? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val json = if (start >= 0 && end > start) text.substring(start, end + 1) else null
        val r = dev.phonerecall.mind.AskEnvelope.parse(json ?: text, expectId)
        if (r.id != null) return r
        if (json != null && "\"sections\"" in json) return null
        if (text.isBlank()) return null
        val out = LinkedHashMap<String, MutableList<String>>()
        var cur = "must_know"
        for (line in text.lines()) {
            val h = Regex("^#+\\s*(.+)$").find(line.trim())
            if (h != null) { cur = Seed.sectionFor(h.groupValues[1]); continue }
            if (line.isNotBlank()) out.getOrPut(cur) { ArrayList() } += line
        }
        return dev.phonerecall.mind.AskReply(null, out, text = text)
    }
}

object Seed {
    const val TOPIC = "seed"
    const val MILESTONES = "milestones"
    /** How long step 1 waits for Iris before going on without her. Her later answer still merges. */
    const val IRIS_WAIT_MS = 10 * 60_000L
    const val MAX_LINES_PER_SECTION = 12
    const val MAX_FACTS = 200

    val SECTIONS = listOf(
        AskSection("trips", "Trips and vacation plans", SeedPath.PLANS, "booked or planned trips and vacations, with month and place name (bookings, confirmations)"),
        AskSection("calendar", "Calendar outlook", SeedPath.PLANS, "the next 8 weeks: notable events, busy weeks, days off"),
        AskSection("work_week", "Usual work week", SeedPath.ROUTINES, "office days vs remote, usual hours, recurring meetings"),
        AskSection("spending", "Spending patterns", SeedPath.PROFILE, "categories he spends on, usual stores, subscriptions by name (receipts; no amounts, balances or card numbers)"),
        AskSection(
            "people", "People and relationships", SeedPath.PEOPLE,
            "family (who, relationship, India at city level), close friends, work contacts and roles, who to never disturb him about, who is always urgent; first name or label and relationship only, birthdays as month and day",
        ),
        AskSection("routines", "Routines", SeedPath.ROUTINES, "daily and weekly habits, briefings, errands, gym, quiet hours"),
        AskSection("preferences", "Preferences, likes and dislikes", SeedPath.PREFERENCES, "how he likes messages and reminders, food, music, sports, films"),
        AskSection("deadlines", "Upcoming bills, deadlines and renewals", SeedPath.PLANS, "what is coming up, by name and date only (no amounts)"),
        AskSection(
            MILESTONES, "Personal milestones and special days", SeedPath.PROFILE,
            "anniversaries and special days: car bought or leased (the car maker's app welcome email is close to the delivery date), moved to Buffalo, job start, graduation, first apartment, important family dates; " +
                "each line as \"YYYY-MM-DD | one-line label\" (YYYY-MM when the day is unknown); only what you can back with an email, a contract or owner.db",
        ),
        AskSection("projects", "Ongoing projects", SeedPath.PROFILE, "what he is working on or planning, at a high level"),
        AskSection("must_know", "Anything else Athena must know", SeedPath.PROFILE, "anything else that helps a new assistant on day one"),
    )

    fun sectionFor(heading: String): String {
        val h = heading.lowercase()
        return SECTIONS.firstOrNull { h.contains(it.id.replace('_', ' ')) || h.contains(it.title.lowercase().substringBefore(' ')) }?.id ?: "must_know"
    }

    /** The question in the envelope: where to look, the sections, the rules, the format. */
    val QUESTION: String = buildString {
        append("Athena, the owner's new assistant in Zeus, is starting up and needs to know him. Tell her as much as you can about everything below.\n")
        append("Search his email history (confirmations, contracts, bookings, receipts, greetings and wishes), your own memory and owner.db, and his calendars.\n")
        append("Apply your OFF-LIMITS list and your data tiers exactly as you would for Athena: ")
        append("leave out anything on it, and anything that needs his OK first. Never include amounts, balances, account or card numbers, phone numbers, emails, ")
        append("exact addresses, coordinates, plate numbers, immigration, health, dating, credentials or server details, or quotes from mail.\n")
        append("Write short factual lines in plain words, one fact per line. End every line with where it came from: [email], [memory] or [db]. ")
        append("Prefix a line with ~ when you are not sure. Say \"nothing known\" for an empty section.\n")
        append("Sections:\n")
        for (s in SECTIONS) append("- ${s.id}: ${s.title}: ${s.ask}\n")
        append("Reply with one JSON object: {\"type\":\"ask.reply\",\"id\":\"<the request id>\",\"sections\":{\"<section id>\":[\"line [email]\", ...]},\"note\":\"optional\"}.")
    }

    private val CITE = Regex("\\s*[\\[(](email|mail|memory|db|owner\\.db|calendar)[\\])]\\s*\\.?\\s*$", RegexOption.IGNORE_CASE)

    /** A line's cited source: email, db, calendar or memory, or null when she gave none. */
    fun cite(line: String): Pair<String, String?> {
        val m = CITE.find(line) ?: return line to null
        val kind = m.groupValues[1].lowercase().let { if (it == "mail") "email" else if (it == "owner.db") "db" else it }
        return line.removeRange(m.range) to kind
    }

    /**
     * Her reply as facts. A line backed by email, db or calendar is observed; her memory or no citation is
     * inferred; "~" lowers it further. Milestones are confirmed only when a document backs them.
     */
    fun fromIris(r: dev.phonerecall.mind.AskReply, clean: Sanitizer): List<SeedFact> {
        val out = ArrayList<SeedFact>()
        for ((id, lines) in r.sections) {
            val sec = SECTIONS.firstOrNull { it.id == id } ?: SECTIONS.last()
            for (raw in lines.take(MAX_LINES_PER_SECTION)) {
                val unsure = raw.trim().startsWith("~")
                val (body, kind) = cite(raw.trim().removePrefix("~").trim())
                val documented = kind in setOf("email", "db", "calendar") && !unsure
                if (sec.id == MILESTONES) {
                    for ((label, date) in MilestoneText.parse(body)) {
                        val text = SeedFilter.clean(clean, label) ?: continue
                        out += milestoneFact(text, date, "iris", if (documented) Status.CONFIRMED else Status.INFERRED, if (documented) 0.9 else 0.5)
                    }
                    continue
                }
                line(raw, sec.path, sec.id, clean)?.let { out += it }
            }
        }
        return out
    }

    /** One of her lines as a fact: documented is observed, her memory inferred, "~" unsure. */
    private fun line(raw: String, path: SeedPath, topic: String, clean: Sanitizer): SeedFact? {
        val unsure = raw.trim().startsWith("~")
        val (body, kind) = cite(raw.trim().removePrefix("~").trim())
        val text = SeedFilter.clean(clean, body) ?: return null
        if (NOTHING.matches(text)) return null
        val (status, conf) = when {
            unsure -> Status.INFERRED to 0.5
            kind in setOf("email", "db", "calendar") -> Status.OBSERVED to 0.85
            else -> Status.INFERRED to 0.7
        }
        return SeedFact(path, text, "iris", conf, topic, status)
    }

    /** The one-gap question Athena puts to Iris mid-conversation, on the same rules as the seed. */
    fun gapQuestion(g: Gap): String =
        "Athena is getting to know the owner and has one gap she can't fill from the phone: \"${g.question}\" Answer only what you can back with his email, owner.db or your memory. " +
            "Apply your OFF-LIMITS list and data tiers exactly as for the seed; leave out anything that needs his OK. Short factual lines, each ending [email], [memory] or [db]; prefix ~ when unsure; " +
            "\"nothing known\" if you don't know. Reply with one JSON object: {\"type\":\"ask.reply\",\"id\":\"<the request id>\",\"sections\":{\"${g.id}\":[\"line [email]\"]}}."

    /** Her answer to one gap, as facts on that gap's path and topic. */
    fun fromIrisGap(r: dev.phonerecall.mind.AskReply, g: Gap, clean: Sanitizer): List<SeedFact> =
        (r.sections.values.flatten() + listOfNotNull(r.note)).take(MAX_LINES_PER_SECTION).mapNotNull { line(it, g.path, g.id, clean) }

    fun milestoneFact(label: String, date: String, source: String, status: String, conf: Double) = SeedFact(
        SeedPath.PROFILE, "${label.trimEnd('.')}: ${MilestoneText.shown(Milestone(label, date))}.", source, conf, MILESTONES, status, date,
    )

    fun fromPhone(raw: List<RawFact>, clean: Sanitizer): List<SeedFact> =
        raw.mapNotNull { f -> SeedFilter.clean(clean, f.text)?.let { SeedFact(f.path, it, "phone", f.confidence.coerceIn(0.0, 1.0), f.topic, f.status) } }

    private val NOTHING = Regex("(?i)^(nothing( known)?|none|n/?a|no data)\\.?$")
}

/**
 * The seed's own belt over the Sanitizer: a line that names a JID, a card or an account goes whole; runs of
 * three or more digits are cut (years stay); a line left with a placeholder ([amount], [number], [email]...) goes.
 */
object SeedFilter {
    private val JID = Regex("(?i)@(s\\.whatsapp\\.net|g\\.us|c\\.us|lid)\\b|\\b\\d{6,}@")
    private val OFF = Regex(
        "(?i)\\b(card number|account number|acct|routing|ending in|last ?4|balance|ssn|social security|password|passcode|pin code|otp|token|api key|" +
            "ssh|firewall|ip address|vin|plate number|license plate|salary|income|latitude|longitude)\\b",
    )
    private val DIGITS = Regex("(?<!\\d)(?!(?:19|20)\\d{2}(?!\\d))\\d{3,}")
    private val PLACEHOLDER = Regex("\\[(amount|number|email|place|address|withheld)]")
    private val HASHES = Regex("#{2,}|(?<=\\s)#(?=\\s)")

    fun clean(s: Sanitizer, raw: String): String? {
        var t = raw.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim()
        if (t.length < 3) return null
        if (JID.containsMatchIn(t) || OFF.containsMatchIn(t)) return null
        t = s.line(t) ?: return null
        if (PLACEHOLDER.containsMatchIn(t)) return null
        t = DIGITS.replace(t, "").let { HASHES.replace(it, "") }
        t = t.replace(Regex("\\s{2,}"), " ").replace(Regex("\\s+([,.;:])"), "$1").trim().trimEnd(',', ';', ':').trim()
        return t.takeIf { it.length >= 3 && it.any(Char::isLetter) }?.take(200)
    }
}

// ---- step 2: the interview ----

/** One checklist question. `covers` decides whether a known (not inferred) fact already answers it. */
data class Gap(
    val id: String,
    val path: SeedPath,
    val question: String,
    val chips: List<String>,
    val covers: Regex?,
    val statement: (String) -> String,
    val multi: Boolean = false,
    /** For auto-approve: chip word to the proposal kind it would put on the list. */
    val settings: Map<String, String> = emptyMap(),
)

/**
 * A question as asked: a checklist gap, "is this right?" about one inferred fact (`factKey`), a people
 * question (id "p:..."), the milestones question, or a bundle ("b:...", `bundle` = fact keys he ticks or
 * unticks in one go). `prefill` chips put their words in the text field.
 */
data class Question(
    val id: String = "", val gap: String? = null, val factKey: String? = null, val text: String = "",
    val chips: List<String> = emptyList(), val multi: Boolean = false, val prefill: Boolean = false, val hint: String? = null,
    val bundle: List<String> = emptyList(),
)

/**
 * One line of the conversation. Athena's lines are hers; his are what was recorded (a chip's words, or his
 * own after the filters), never a raw dump. `items` are a bundle's lines as they were when she asked.
 */
data class ChatTurn(val id: String = "", val who: String = ATHENA, val text: String = "", val qid: String? = null, val items: List<String> = emptyList()) {
    companion object {
        const val ATHENA = "athena"
        const val YOU = "you"
    }
}

/** A gap Athena asked Iris about mid-conversation: the request id, the gateway message, the question and gap. */
data class GapAsk(val id: String = "", val msg: Long = 0, val qid: String = "", val gap: String = "", val at: Long = 0)

/** A settings change from the interview, waiting for his tap. */
data class SettingProposal(val id: String = "", val kindWire: String = "", val label: String = "", val status: String = "pending")

object Interview {
    const val MAX = 12
    const val SURE = 0.7
    const val YES = "Yes"
    const val NOT_QUITE = "Not quite"
    const val FORGET = "Forget it"
    const val MILESTONE_Q = "m:milestones"
    const val NO_MILESTONES = "None I want marked"
    const val OPENING = "b:open"
    const val BUNDLE = 5
    const val ASK_IRIS = "Ask Iris"
    const val SKIP = "Skip"
    const val OPENING_TEXT = "Here's what I've picked up from Iris and your phone. These look right? Untick anything that's off."

    enum class Intent { SKIP, LATER }

    private val SKIP_WORDS = Regex("(?i)^(skip|pass|next|no idea|not sure|don'?t know|dunno|rather not say)[.!]?$")
    private val LATER_WORDS = Regex("(?i)^(later|not now|ask me later|another time|maybe later|stop|enough for now)[.!]?$")

    /** "skip" and "later" typed in the chat do what the buttons do. */
    fun intent(words: String): Intent? = when {
        SKIP_WORDS.matches(words.trim()) -> Intent.SKIP
        LATER_WORDS.matches(words.trim()) -> Intent.LATER
        else -> null
    }

    private val OPENING_PATHS = listOf(SeedPath.PROFILE, SeedPath.ROUTINES, SeedPath.PEOPLE, SeedPath.PREFERENCES, SeedPath.PLANS)

    /**
     * Her opening: up to five things she believes but he hasn't said (documented ones first, at most two per
     * place), as one bundle to tick. Null when there's nothing to put to him.
     */
    fun opening(facts: List<SeedFact>): Question? {
        val pick = ArrayList<SeedFact>()
        facts.filter { it.status != Status.CONFIRMED && !it.milestone && it.path in OPENING_PATHS && it.source != "you" }
            .sortedWith(compareBy({ it.status == Status.INFERRED }, { -it.confidence }, { OPENING_PATHS.indexOf(it.path) }, { it.text }))
            .forEach { f -> if (pick.size < BUNDLE && pick.count { it.path == f.path } < 2) pick += f }
        return if (pick.isEmpty()) null else Question(OPENING, text = OPENING_TEXT, bundle = pick.map { it.key })
    }

    /**
     * The conversation's plan: the opening bundle, then the questions in order, with the "is this right?" checks
     * gathered into bundles of up to five ("These look right?") instead of one at a time.
     */
    fun chat(questions: List<Question>, facts: List<SeedFact>, asked: Set<String>): List<Question> {
        val open = if (OPENING in asked) null else opening(facts)
        val inOpening = open?.bundle.orEmpty().toSet()
        val checks = questions.filter { it.factKey != null && it.factKey !in inOpening }.mapNotNull { it.factKey }
        val bundles = checks.chunked(BUNDLE).map { keys -> Question("b:" + keys.first(), text = "A few more I guessed. These look right?", bundle = keys) }
        return listOfNotNull(open) + questions.filter { it.factKey == null } + bundles.filter { it.id !in asked }
    }

    /** Multi-select answers are joined with "; " (names may hold commas). */
    fun parts(answer: String): List<String> = answer.split(';').map { it.trim() }.filter { it.isNotEmpty() }

    val CHECKLIST: List<Gap> = listOf(
        Gap("wake", SeedPath.ROUTINES, "When do you usually wake up on workdays?", listOf("6:30 AM", "7 AM", "7:30 AM", "8 AM"), Regex("(?i)\\b(wakes?|waking|up at|alarm)\\b"), { "Wakes up around $it on workdays." }),
        Gap("sleep", SeedPath.ROUTINES, "And when do you usually go to bed?", listOf("10:30 PM", "11 PM", "Midnight", "Later"), Regex("(?i)\\b(bed|bedtime|sleeps?|asleep)\\b"), { "Usually in bed around ${it.lowercase()}." }),
        Gap("work_hours", SeedPath.ROUTINES, "What are your usual work hours?", listOf("9 to 5", "9 to 6", "10 to 6", "It varies"), Regex("(?i)\\bwork(ing)? hours\\b|\\b\\d{1,2}(:\\d\\d)?\\s?(am)?\\s?(to|-|until)\\s?\\d{1,2}(:\\d\\d)?\\s?pm\\b"), { "Work hours: ${it.lowercase()}." }),
        Gap("notify_style", SeedPath.PREFERENCES, "How should I reach you about routine things?", listOf("In the briefings only", "A card when it matters", "Ping me right away"), Regex("(?i)\\b(briefings?|notifications?|reminders?)\\b.*\\b(likes?|prefers?|wants?|style)\\b|\\b(likes?|prefers?)\\b.*\\b(briefings?|notifications?)\\b"), { "Routine things: ${it.lowercase()}." }),
        Gap(
            "auto_approve", SeedPath.PREFERENCES, "Is there anything I may do without asking first? You'll still see each one, with Undo.",
            listOf("Reminders", "Tasks", "Cards on Olympus", "Nothing, ask every time"), null,
            { if (it.startsWith("Nothing")) "Wants to confirm every proposal." else "Happy for Athena to do without asking: ${parts(it).joinToString(", ").lowercase()}." },
            multi = true, settings = mapOf("Reminders" to "add_reminder", "Tasks" to "add_task", "Cards on Olympus" to "create_card"),
        ),
        Gap("festivals", SeedPath.PREFERENCES, "Which festivals should I remind you about?", listOf("Diwali", "Holi", "Ugadi", "Sankranti", "None"), Regex("(?i)\\b(diwali|holi|ugadi|sankranti|eid|festivals?)\\b"), { if (it == "None") "No festival reminders." else "Remind him about: ${parts(it).joinToString(", ")}." }, multi = true),
        Gap("family_india", SeedPath.PEOPLE, "Which city is your family in India in?", listOf("Mumbai", "Bengaluru", "Chennai", "I'd rather not say"), Regex("(?i)\\bfamily\\b.*\\bindia\\b.*\\b(in|at)\\s+[A-Z]|\\bfamily\\b.*\\b(lives?|lived) in\\b"), { if (it.startsWith("I'd rather")) "Family in India: he'd rather not say where." else "Family in India: $it." }),
        Gap("places", SeedPath.ROUTINES, "Do you go to a gym? Which one, by name?", listOf("No gym", "I'll type it"), Regex("(?i)\\bgym\\b"), { if (it == "No gym") "Does not go to a gym." else "Gym: $it." }),
        Gap("music", SeedPath.PREFERENCES, "What do you like to play, and when?", listOf("Upbeat on drives", "Focus music for work", "Bollywood", "Leave music to me"), Regex("(?i)\\b(music|playlists?|songs?|recently played|listens?)\\b"), { "Music: ${parts(it).joinToString(", ").lowercase()}." }, multi = true),
        Gap("budget", SeedPath.PREFERENCES, "How should I treat your spending?", listOf("Weekly check-in", "Only flag unusual", "Stay out of it"), Regex("(?i)\\bbudgets?\\b|\\bspending check\\b"), { "Spending: ${it.lowercase()}." }),
        Gap("weekend", SeedPath.ROUTINES, "What does a usual weekend look like?", listOf("Errands and rest", "Out and about", "Sports on TV", "Short drives"), Regex("(?i)\\bweekends?\\b"), { "Weekends: ${parts(it).joinToString(", ").lowercase()}." }, multi = true),
    )

    private fun known(f: SeedFact) = f.status != Status.INFERRED && f.confidence >= SURE
    private fun matches(g: Gap, f: SeedFact) = g.covers != null && (f.topic == g.id || g.covers.containsMatchIn(f.text))
    private fun covered(g: Gap, facts: List<SeedFact>): Boolean = facts.any { known(it) && matches(g, it) }

    /** Milestone kinds no milestone fact (of any status) mentions yet: only those are asked. */
    fun missingMilestones(facts: List<SeedFact>): List<String> {
        val ms = facts.filter { it.topic == Seed.MILESTONES }
        return MilestoneText.KINDS.filter { (_, re) -> ms.none { re.containsMatchIn(it.text) } }.map { it.first }
    }

    /**
     * Deterministic, at most MAX: the day (wake, sleep, hours), people, how to reach him and auto-approve,
     * milestones Iris doesn't know, the rest of the checklist, then inferred facts to confirm. A gap an inferred fact
     * already guesses is asked as that fact's "is this right?" instead. `asked` are never asked again.
     */
    fun questions(facts: List<SeedFact>, asked: Set<String>, roster: List<RosterPerson> = emptyList(), max: Int = MAX): List<Question> {
        val out = ArrayList<Question>()
        val inferred = facts.filter { it.status == Status.INFERRED && it.source != "people" }
        fun gap(g: Gap) {
            if (g.id in asked || covered(g, facts)) return
            if (inferred.any { matches(g, it) }) return
            out += Question(g.id, gap = g.id, text = g.question, chips = g.chips, multi = g.multi)
        }
        CHECKLIST.take(3).forEach(::gap)
        out += PeopleSeed.questions(roster, asked)
        CHECKLIST.drop(3).take(2).forEach(::gap)
        val missing = missingMilestones(facts)
        if (MILESTONE_Q !in asked && missing.isNotEmpty())
            out += Question(
                MILESTONE_Q, text = "Any special days I should mark? Tap one, then add the date.", chips = missing + NO_MILESTONES,
                prefill = true, hint = "Got the car, May 2024; Moved to Buffalo, Mar 2025",
            )
        CHECKLIST.drop(5).forEach(::gap)
        for (f in inferred.sortedWith(compareBy({ it.confidence }, { it.text }))) {
            val id = "check:" + f.key
            if (id in asked) continue
            out += Question(id, factKey = f.key, text = "Is this right? \"${f.text}\"", chips = listOf(YES, NOT_QUITE, FORGET))
        }
        return out.take(max)
    }

    /** Still a question worth asking, given what is known now (Iris may have answered meanwhile). */
    fun stillOpen(q: Question, facts: List<SeedFact>, roster: List<RosterPerson>): Boolean = when {
        q.id.startsWith("p:") -> PeopleSeed.stillOpen(q.id, roster)
        q.id == MILESTONE_Q -> missingMilestones(facts).isNotEmpty()
        q.bundle.isNotEmpty() -> facts.any { it.key in q.bundle && it.status != Status.CONFIRMED }
        q.gap != null -> CHECKLIST.firstOrNull { it.id == q.gap }?.let { !covered(it, facts) } ?: false
        q.factKey != null -> facts.any { it.key == q.factKey && it.status == Status.INFERRED }
        else -> false
    }
}

// ---- the engine ----

enum class Phase { NONE, SEEDING, ASKING, INTERVIEW, DONE }
enum class StepStatus { WAITING, RUNNING, DONE, SKIPPED }
data class SeedStep(val label: String = "", val status: StepStatus = StepStatus.WAITING, val learned: Int = 0, val note: String? = null)

data class TheogonyState(
    val phase: Phase = Phase.NONE,
    val steps: List<SeedStep> = emptyList(),
    val facts: List<SeedFact> = emptyList(),
    val question: Question? = null,
    val asked: Int = 0,
    val total: Int = 0,
    val irisPending: Boolean = false,
    val settings: List<SettingProposal> = emptyList(),
    val laterUntil: Long = 0,
    val startedAt: Long = 0,
    val milestoneNotify: Boolean = false,
    /** The conversation so far, ending with her current question when there is one. */
    val turns: List<ChatTurn> = emptyList(),
    /** Questions still to come, the current one included ("Getting to know you · N left"). */
    val left: Int = 0,
    val canAskIris: Boolean = false,
)

/**
 * The whole first run, persisted in Kv so every step survives a restart: facts, the people roster, the frozen
 * question queue, what was asked, the pending ask to Iris. Calls are blocking: run them on IO, one at a time.
 */
class TheogonyEngine(
    private val kv: Kv,
    private val iris: IrisLink?,
    private val phone: List<PhoneSource>,
    private val memory: SeedMemory,
    private val settings: SeedSettings,
    private val sanitizer: () -> Sanitizer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString().replace("-", "").take(16) },
    private val people: PeopleSource? = null,
    private val rows: SeedRows? = null,
) {
    private val factType = object : TypeReference<List<SeedFact>>() {}
    private val stepType = object : TypeReference<List<SeedStep>>() {}
    private val qType = object : TypeReference<List<Question>>() {}
    private val setType = object : TypeReference<List<SettingProposal>>() {}
    private val rosterType = object : TypeReference<List<RosterPerson>>() {}

    var phase: Phase
        get() = kv.get(K_PHASE)?.let { runCatching { Phase.valueOf(it) }.getOrNull() } ?: Phase.NONE
        private set(v) = kv.put(K_PHASE, v.name)

    fun facts(): List<SeedFact> = kv.list(K_FACTS, factType)
    fun roster(): List<RosterPerson> = kv.list(K_ROSTER, rosterType)
    private fun steps(): List<SeedStep> = kv.list(K_STEPS, stepType)
    private fun queue(): List<Question> = kv.list(K_QUEUE, qType)
    private fun asked(): Set<String> = kv.strings(K_ASKED).toSet()
    private fun forgotten(): Set<String> = kv.strings(K_FORGOT).toSet()
    fun proposals(): List<SettingProposal> = kv.list(K_SETTINGS, setType)

    private val turnType = object : TypeReference<List<ChatTurn>>() {}
    private val gapType = object : TypeReference<List<GapAsk>>() {}
    fun turns(): List<ChatTurn> = kv.list(K_CHAT, turnType)
    private fun gapAsks(): List<GapAsk> = kv.list(K_GAP_ASKS, gapType)

    /**
     * What the screen shows. `pending` are answers he gave that are still being saved (question id to his words):
     * they show as his lines and the conversation moves on, so a tap never waits for the work behind it.
     */
    fun state(pending: Map<String, String> = emptyMap()): TheogonyState {
        val q = queue().map(::shown)
        val asked = asked() + pending.keys
        val interview = phase == Phase.INTERVIEW
        val next = if (interview) q.firstOrNull { it.id !in asked } else null
        val f = facts()
        val turns = ArrayList(turns())
        for ((id, words) in pending) {
            val pq = q.firstOrNull { it.id == id } ?: continue
            if (turns.lastOrNull()?.qid != id) turns += askTurn(pq, f)
            turns += ChatTurn("pending-$id", ChatTurn.YOU, words, id)
        }
        if (next != null && turns.lastOrNull()?.let { it.qid == next.id && it.who == ChatTurn.ATHENA } != true) turns += askTurn(next, f)
        return TheogonyState(
            phase, steps(), f, next,
            q.count { it.id in asked }, q.size, kv.get(K_ASK_MSG) != null || gapAsks().isNotEmpty(), proposals(), kv.long(K_LATER) ?: 0, kv.long(K_STARTED) ?: 0,
            kv.get(K_MS_NOTIFY) == "1", turns, if (interview) q.count { it.id !in asked } else 0,
            next?.gap != null && runCatching { iris?.available() == true }.getOrDefault(false),
        )
    }

    /** The opening's words: hers when the model phrased them, else the plain line. */
    private fun shown(q: Question) = if (q.id == Interview.OPENING) kv.get(K_OPENING)?.let { q.copy(text = it) } ?: q else q

    private fun askTurn(q: Question, facts: List<SeedFact>) =
        ChatTurn("ask-" + q.id, ChatTurn.ATHENA, q.text, q.id, q.bundle.mapNotNull { k -> facts.firstOrNull { it.key == k }?.text })

    private fun addTurns(vararg t: ChatTurn) {
        var n = kv.long(K_TURN_SEQ) ?: 0
        val next = t.map { it.copy(id = "t" + (++n)) }
        kv.putLong(K_TURN_SEQ, n)
        kv.putList(K_CHAT, (turns() + next).takeLast(MAX_TURNS))
    }

    private fun athena(text: String) = addTurns(ChatTurn(who = ChatTurn.ATHENA, text = text))

    /** Her question, then his answer as recorded. */
    private fun record(q: Question, words: String) {
        val asked = if (turns().lastOrNull()?.qid == q.id) emptyList() else listOf(askTurn(shown(q), facts()))
        addTurns(*(asked + ChatTurn(who = ChatTurn.YOU, text = words, qid = q.id)).toTypedArray())
    }

    /** His own words as they may be kept: a people answer is names he picked (they stay on the phone); the rest passes the filters. */
    private fun heard(q: Question, words: String): String =
        if (q.id.startsWith("p:") || words in q.chips) words else SeedFilter.clean(sanitizer(), words) ?: "(not kept: that's off my notes)"

    // ---- what other features read: confirmed and observed only ----

    fun peoplePriority(): PeoplePriority = PeoplePriority(roster())

    fun milestones(): Milestones = Milestones(
        facts().filter { it.milestone }.map { Milestone(it.text.substringBeforeLast(':'), it.date!!, it.status, it.source) },
        notify = kv.get(K_MS_NOTIFY) == "1",
    )

    /** His opt-in for a notification on a milestone's day (off by default; Chronos shows the line either way). */
    fun setMilestoneNotify(on: Boolean) = kv.put(K_MS_NOTIFY, if (on) "1" else null)

    /** Step 3's gate: proactive cards wait while a seed is running (at most IRIS_WAIT_MS), and before the first one on a paired phone. */
    fun proactiveReady(paired: Boolean): Boolean {
        val started = kv.long(K_STARTED) ?: return !paired
        return kv.long(K_SEEDED) != null || clock() - started >= Seed.IRIS_WAIT_MS
    }

    /** Step 1. A refresh re-runs it (Themis): phone, people and Iris facts are replaced, his answers stay. */
    fun seed(progress: () -> Unit = {}) {
        val clean = sanitizer()
        // A refresh keeps K_SEEDED: proactive cards only ever wait for the first seed.
        kv.putLong(K_STARTED, clock())
        phase = Phase.SEEDING
        val labels = listOf(ASK_LABEL) + (if (people != null) listOf(PEOPLE_LABEL) else emptyList()) + phone.map { it.label }
        kv.putList(K_STEPS, labels.map { SeedStep(it) })
        progress()

        // Ask Iris first: she works while the phone reads.
        val iris = iris
        if (iris == null || !runCatching { iris.available() }.getOrDefault(false)) {
            step(ASK_LABEL) { it.copy(status = StepStatus.SKIPPED, note = "Iris isn't connected yet") }
        } else {
            step(ASK_LABEL) { it.copy(status = StepStatus.RUNNING) }
            val id = newId()
            runCatching { iris.send(SeedAsk.text(id), dev.phonerecall.mind.AskEnvelope.clientId(id)) }
                .onSuccess { mid -> kv.put(K_ASK_ID, id); kv.putLong(K_ASK_MSG, mid); kv.putLong(K_ASK_AT, clock()) }
                .onFailure { e -> step(ASK_LABEL) { it.copy(status = StepStatus.SKIPPED, note = "Couldn't reach Iris: ${e.message ?: e.javaClass.simpleName}") } }
        }
        progress()

        people?.let { src ->
            step(PEOPLE_LABEL) { it.copy(status = StepStatus.RUNNING) }
            progress()
            val input = runCatching { src.read() }.getOrNull()
            if (input == null) step(PEOPLE_LABEL) { it.copy(status = StepStatus.SKIPPED) }
            else {
                kv.putList(K_ROSTER, PeopleSeed.build(input, roster()))
                kv.put(K_GROUPS, input.busyGroups.toString())
                val n = syncPeople()
                step(PEOPLE_LABEL) { it.copy(status = StepStatus.DONE, learned = n) }
            }
        }

        val phoneFacts = ArrayList<SeedFact>()
        for (src in phone) {
            step(src.label) { it.copy(status = StepStatus.RUNNING) }
            progress()
            val raw = runCatching { src.read() }.getOrNull()
            if (raw == null) { step(src.label) { it.copy(status = StepStatus.SKIPPED) }; continue }
            val got = Seed.fromPhone(raw, clean)
            phoneFacts += got
            step(src.label) { it.copy(status = StepStatus.DONE, learned = got.size) }
        }
        merge(phoneFacts, "phone")
        phase = if (kv.get(K_ASK_MSG) != null) Phase.ASKING else Phase.SEEDING
        progress()
        poll(progress)
    }

    /** The people lines in memory, regenerated from the roster. Returns how many. */
    private fun syncPeople(): Int {
        val lines = PeopleSeed.memoryLines(roster(), kv.get(K_GROUPS)?.toIntOrNull() ?: 0)
        val clean = sanitizer()
        merge(lines.mapNotNull { f -> SeedFilter.clean(clean, f.text)?.let { f.copy(text = it) } }, "people")
        return lines.size
    }

    /**
     * Checks for Iris's answer. Step 1 ends when she answered, when there is no ask, or after IRIS_WAIT_MS;
     * a late answer still merges and trims the questions still to come.
     */
    fun poll(progress: () -> Unit = {}) {
        pollGaps()
        val mid = kv.long(K_ASK_MSG)
        val iris = iris
        if (mid != null && iris != null) {
            val text = runCatching { iris.reply(mid) }.getOrNull()
            val reply = text?.let { SeedAsk.parse(it, kv.get(K_ASK_ID).orEmpty()) }
            if (reply != null) {
                val got = Seed.fromIris(reply, sanitizer())
                merge(got, "iris")
                kv.put(K_ASK_MSG, null)
                step(ASK_LABEL) { it.copy(status = StepStatus.DONE, learned = got.size, note = null) }
                trimQueue()
            } else if (text != null) {
                kv.put(K_ASK_MSG, null)
                step(ASK_LABEL) { it.copy(status = StepStatus.SKIPPED, note = "Iris answered in a form Zeus couldn't read") }
            }
        }
        val waiting = kv.get(K_ASK_MSG) != null && clock() - (kv.long(K_ASK_AT) ?: 0) < Seed.IRIS_WAIT_MS
        if ((phase == Phase.SEEDING || phase == Phase.ASKING) && !waiting) finishSeed()
        if (kv.get(K_ASK_MSG) != null && !waiting) step(ASK_LABEL) { if (it.status == StepStatus.RUNNING) it.copy(note = "Still waiting for Iris; her answer is added when it comes") else it }
        progress()
    }

    /** Step 1 is over: the interview starts now, with the questions frozen so it can resume where it stopped. */
    fun skipWaiting() { if (phase == Phase.ASKING || phase == Phase.SEEDING) finishSeed() }

    private fun finishSeed() {
        kv.putLong(K_SEEDED, clock())
        plan()
        if (phase == Phase.DONE) athena(DONE_TEXT)
    }

    /** Freezes the conversation's questions (opening, gaps, bundles) so it resumes where it stopped. */
    private fun plan() {
        val asked = asked()
        val q = Interview.chat(Interview.questions(facts(), asked, roster()), facts(), asked)
        kv.putList(K_QUEUE, q)
        kv.put(K_CHAT_V, "1")
        kv.put(K_OPENING, null); kv.put(K_OPEN_TRIED, null)
        phase = if (q.isEmpty()) Phase.DONE else Phase.INTERVIEW
    }

    /** An interview frozen before the conversation (one question at a time) is planned again as one; his answers stay. */
    fun migrate() {
        if (phase == Phase.INTERVIEW && kv.get(K_CHAT_V) == null) plan()
    }

    private fun trimQueue() {
        if (phase != Phase.INTERVIEW) return
        val f = facts()
        val r = roster()
        val asked = asked()
        val q = queue().filter { it.id in asked || Interview.stillOpen(it, f, r) }
        kv.putList(K_QUEUE, q)
        if (q.none { it.id !in asked }) phase = Phase.DONE
    }

    /** His answer to the current question: one chip, several (multi, joined with "; "), or his own words. */
    fun answer(questionId: String, answer: String) {
        val q = queue().firstOrNull { it.id == questionId } ?: return
        val words = answer.trim()
        if (words.isEmpty()) return skip(questionId)
        if (q.bundle.isNotEmpty()) return note(q, words)
        val clean = sanitizer()
        record(q, heard(q, words))
        when {
            q.id.startsWith("p:") -> {
                kv.putList(K_ROSTER, PeopleSeed.apply(q.id, words, roster()))
                syncPeople()
            }
            q.id == Interview.MILESTONE_Q -> if (words != Interview.NO_MILESTONES) {
                for ((label, date) in MilestoneText.parse(words)) {
                    val text = SeedFilter.clean(clean, label) ?: continue
                    add(Seed.milestoneFact(text, date, "you", Status.CONFIRMED, 1.0))
                }
            }
            q.factKey != null -> {
                val f = facts().firstOrNull { it.key == q.factKey }
                when {
                    f == null -> {}
                    words == Interview.YES -> confirm(f.key)
                    words == Interview.FORGET -> forget(f.key)
                    words == Interview.NOT_QUITE -> replaceFact(f, null)
                    else -> { replaceFact(f, null); SeedFilter.clean(clean, words)?.let { add(SeedFact(f.path, it, "you", 1.0, f.topic, Status.CONFIRMED)) } }
                }
            }
            else -> {
                val g = Interview.CHECKLIST.first { it.id == q.gap }
                SeedFilter.clean(clean, g.statement(words))?.let { add(SeedFact(g.path, it, "you", 1.0, g.id, Status.CONFIRMED)) }
                if (g.settings.isNotEmpty()) {
                    val chosen = Interview.parts(words).mapNotNull { w -> g.settings[w]?.let { w to it } }
                    val now = proposals()
                    val fresh = chosen.filter { (_, k) -> now.none { it.kindWire == k && it.status == "pending" } }
                        .map { (w, k) -> SettingProposal("set-$k", k, "Let Athena add $w without asking (each one still shows, with Undo)") }
                    kv.putList(K_SETTINGS, now.filter { p -> fresh.none { it.id == p.id } } + fresh)
                }
            }
        }
        markAsked(questionId)
    }

    fun skip(questionId: String) {
        val q = queue().firstOrNull { it.id == questionId } ?: return
        record(q, Interview.SKIP)
        markAsked(questionId)
    }

    /** "Ask me later": the interview waits a day; it resumes at the same question. */
    fun later(hours: Int = 24) = kv.putLong(K_LATER, clock() + hours * 3_600_000L)

    fun dueForInterview(): Boolean = phase == Phase.INTERVIEW && clock() >= (kv.long(K_LATER) ?: 0)

    private fun markAsked(id: String) {
        kv.putStrings(K_ASKED, (asked() + id).toList())
        kv.put(K_LATER, null)
        if (phase == Phase.INTERVIEW && queue().none { it.id !in asked() }) { phase = Phase.DONE; athena(DONE_TEXT) }
    }

    /**
     * Something he typed in the chat. "skip" and "later" do what the buttons do; anything else answers the
     * current question (or, with no question open, is noted as something he told her).
     */
    fun say(questionId: String?, words: String) {
        val w = words.trim()
        if (w.isEmpty()) return
        val q = questionId?.let { id -> queue().firstOrNull { it.id == id && it.id !in asked() } }
        when (Interview.intent(w)) {
            Interview.Intent.SKIP -> if (q != null) skip(q.id) else note(null, w)
            Interview.Intent.LATER -> {
                if (q != null) record(q, "Later") else addTurns(ChatTurn(who = ChatTurn.YOU, text = "Later"))
                later()
                athena("Sure. I'll pick up here tomorrow, or whenever you open this again.")
            }
            null -> if (q == null) note(null, w) else answer(q.id, w)
        }
    }

    /** His own words outside a question (or on a bundle): one confirmed fact when they pass the filters. */
    private fun note(q: Question?, words: String) {
        val text = SeedFilter.clean(sanitizer(), words)
        if (q != null) record(q, text ?: "(not kept)") else addTurns(ChatTurn(who = ChatTurn.YOU, text = text ?: "(not kept)"))
        if (text == null) return athena("I won't keep that one: it's off my notes.")
        add(SeedFact(SeedPath.PROFILE, text, "you", 1.0, "you_said", Status.CONFIRMED))
        athena(if (q?.bundle?.isNotEmpty() == true) "Noted. Tick what's right above and I'll go on." else "Noted.")
    }

    /** His ticks on a bundle: ticked lines become confirmed, unticked ones are forgotten. */
    fun confirmBundle(questionId: String, keep: Set<String>) {
        val q = queue().firstOrNull { it.id == questionId && it.bundle.isNotEmpty() } ?: return
        val live = q.bundle.filter { k -> facts().any { it.key == k } }
        val kept = live.filter { it in keep }
        record(q, when { kept.size == live.size -> "Yes, all right"; kept.isEmpty() -> "None of these"; else -> "${kept.size} of ${live.size} are right" })
        markAsked(q.id)
        for (k in live) if (k in keep) confirm(k) else forget(k)
    }

    /**
     * "Ask Iris": the gap goes to her as one ask (the shared envelope) and the conversation moves on. Her answer
     * lands in poll(): documented lines settle the gap; a guess comes back to him as a bundle.
     */
    fun askIris(questionId: String) {
        val q = queue().firstOrNull { it.id == questionId && it.gap != null } ?: return
        val g = Interview.CHECKLIST.firstOrNull { it.id == q.gap } ?: return
        val iris = iris?.takeIf { runCatching { it.available() }.getOrDefault(false) } ?: return
        record(q, Interview.ASK_IRIS)
        val id = newId()
        runCatching { iris.send(dev.phonerecall.mind.AskEnvelope.text(dev.phonerecall.mind.AskRequest(id, Seed.TOPIC, Seed.gapQuestion(g))), dev.phonerecall.mind.AskEnvelope.clientId(id)) }
            .onSuccess { mid -> kv.putList(K_GAP_ASKS, gapAsks() + GapAsk(id, mid, q.id, g.id, clock())); athena("I've asked Iris. Let's go on; I'll tell you what she finds.") }
            .onFailure { athena("I couldn't reach Iris just now. Let's go on.") }
        markAsked(q.id)
    }

    private fun pollGaps() {
        val iris = iris ?: return
        val asks = gapAsks().takeIf { it.isNotEmpty() } ?: return
        val left = ArrayList<GapAsk>()
        for (a in asks) {
            val g = Interview.CHECKLIST.firstOrNull { it.id == a.gap } ?: continue
            val about = g.question.trimEnd('?').replaceFirstChar { it.lowercase() }
            val text = runCatching { iris.reply(a.msg) }.getOrNull()
            val r = text?.let { SeedAsk.parse(it, a.id) }
            when {
                r != null -> irisAnswered(g, Seed.fromIrisGap(r, g, sanitizer()))
                text != null -> athena("Iris answered about $about in a form I couldn't read.")
                clock() - a.at >= Seed.IRIS_WAIT_MS -> athena("Iris hasn't answered about $about. I'll ask again when you refresh.")
                else -> left += a
            }
        }
        kv.putList(K_GAP_ASKS, left)
    }

    private fun irisAnswered(g: Gap, got: List<SeedFact>) {
        val gone = forgotten()
        val fresh = got.filter { it.key !in gone }
        if (fresh.isEmpty()) return athena("Iris didn't know either. We'll leave it for now.")
        save(facts().filter { f -> fresh.none { it.key == f.key } } + fresh)
        val guesses = fresh.filter { it.status == Status.INFERRED }
        athena((if (guesses.isEmpty()) "Iris found it: " else "Iris thinks: ") + fresh.joinToString("; ") { it.text.trimEnd('.') } + ".")
        if (guesses.isEmpty()) return trimQueue()
        val b = Question("b:" + guesses.first().key, text = "Is that right?", bundle = guesses.take(Interview.BUNDLE).map { it.key })
        if (queue().any { it.id == b.id }) return
        kv.putList(K_QUEUE, queue() + b)
        if (phase == Phase.DONE) phase = Phase.INTERVIEW
    }

    /** The opening's facts, while the model may still phrase it (once, outside the engine's lock). */
    fun openingDraft(): List<String>? {
        if (kv.get(K_OPEN_TRIED) != null) return null
        val q = queue().firstOrNull { it.id == Interview.OPENING && it.id !in asked() } ?: return null
        val f = facts()
        return q.bundle.mapNotNull { k -> f.firstOrNull { it.key == k }?.text }.takeIf { it.isNotEmpty() }
    }

    /** Her phrasing of the opening, kept only when it is short and passes the Sanitizer whole. */
    fun setOpening(text: String?) {
        kv.put(K_OPEN_TRIED, "1")
        val t = text?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.length in 12..320 } ?: return
        val clean = sanitizer().line(t)?.takeIf { it == t } ?: return
        kv.put(K_OPENING, clean)
    }

    /** His tap on a settings proposal. Only a confirm changes anything. */
    fun decide(proposalId: String, confirm: Boolean) {
        val all = proposals()
        val p = all.firstOrNull { it.id == proposalId && it.status == "pending" } ?: return
        if (confirm) settings.autoApprove(p.kindWire, true)
        kv.putList(K_SETTINGS, all.map { if (it.id == p.id) it.copy(status = if (confirm) "confirmed" else "declined") else it })
    }

    /** "Yes, that's right": an inferred fact becomes confirmed (his word). People lines confirm the roster behind them. */
    fun confirm(key: String) {
        val f = facts().firstOrNull { it.key == key } ?: return
        if (f.source == "people") {
            kv.putList(K_ROSTER, PeopleSeed.confirmTopic(f.topic, roster()))
            syncPeople()
        } else {
            replaceFact(f, f.copy(status = Status.CONFIRMED, confidence = 1.0))
        }
        kv.putStrings(K_ASKED, (asked() + ("check:" + key)).toList())
        trimQueue()
    }

    /** Forget one fact: gone from memory, and never re-added by a refresh. A people hypothesis leaves the roster too. */
    fun forget(key: String) {
        val f = facts().firstOrNull { it.key == key }
        if (f != null && f.source == "people" && f.status == Status.INFERRED) kv.putList(K_ROSTER, PeopleSeed.dropTopic(f.topic, roster()))
        kv.putStrings(K_FORGOT, (forgotten() + key).toList())
        save(facts().filter { it.key != key })
        if (f?.source == "people") syncPeople()
    }

    /** Starts over from step 1 without losing his answers (Themis "Refresh what you know"). */
    fun resetForRefresh() {
        kv.put(K_ASK_MSG, null)
        // A refresh opens again with what she now believes; his other answers stay asked.
        kv.putStrings(K_ASKED, (asked() - Interview.OPENING).toList())
        phase = Phase.NONE
    }

    // ---- facts and files ----

    private fun add(f: SeedFact) = save(facts().filter { it.key != f.key } + f)

    private fun replaceFact(old: SeedFact, new: SeedFact?) = save(facts().filter { it.key != old.key } + listOfNotNull(new))

    /**
     * Replaces every fact from `source` with `fresh`, drops forgotten ones, keeps the first of duplicates. A
     * fresh line he already confirmed keeps its confirmation.
     */
    private fun merge(fresh: List<SeedFact>, source: String) {
        val gone = forgotten()
        val before = facts()
        val confirmed = before.filter { it.source == source && it.status == Status.CONFIRMED }.map { it.key }.toSet()
        val keep = before.filter { it.source != source }
        val keys = keep.map { it.key }.toMutableSet()
        val added = fresh.filter { it.key !in gone && keys.add(it.key) }.map { if (it.key in confirmed && it.status != Status.CONFIRMED) it.copy(status = Status.CONFIRMED, confidence = 1.0) else it }
        save((keep + added).take(Seed.MAX_FACTS))
    }

    private fun save(facts: List<SeedFact>) {
        val was = facts().filter { it.status == Status.CONFIRMED }
        kv.putList(K_FACTS, facts)
        // Oneiroi follows: a newly confirmed line goes in once (its key keeps a repeat from making a second row), one that left comes out.
        val now = facts.filter { it.status == Status.CONFIRMED }
        now.filter { f -> was.none { it.key == f.key } }.forEach { f -> runCatching { rows?.confirmed(f) } }
        was.filter { f -> now.none { it.key == f.key } }.forEach { f -> runCatching { rows?.forgot(f) } }
        val files = files(facts)
        kv.put(K_FILES, stateJson.writeValueAsString(files))
        memory.write(files)
    }

    private fun step(label: String, f: (SeedStep) -> SeedStep) = kv.putList(K_STEPS, steps().map { if (it.label == label) f(it) else it })

    companion object {
        const val ASK_LABEL = "Asking Iris"
        const val PEOPLE_LABEL = "Your people"
        private const val P = "theo."
        const val K_PHASE = P + "phase"
        const val K_FACTS = P + "facts"
        const val K_ROSTER = P + "roster"
        const val K_GROUPS = P + "groups"
        const val K_STEPS = P + "steps"
        const val K_QUEUE = P + "queue"
        const val K_ASKED = P + "asked"
        const val K_FORGOT = P + "forgot"
        const val K_SETTINGS = P + "settings"
        const val K_ASK_ID = P + "ask.id"
        const val K_ASK_MSG = P + "ask.msg"
        const val K_ASK_AT = P + "ask.at"
        const val K_STARTED = P + "started"
        const val K_SEEDED = P + "seeded"
        const val K_LATER = P + "later"
        const val K_FILES = P + "files"
        const val K_MS_NOTIFY = P + "milestones.notify"
        const val K_CHAT = P + "chat"
        const val K_CHAT_V = P + "chat.v"
        const val K_TURN_SEQ = P + "chat.seq"
        const val K_GAP_ASKS = P + "gap.asks"
        const val K_OPENING = P + "opening"
        const val K_OPEN_TRIED = P + "opening.tried"
        const val MAX_TURNS = 120
        const val DONE_TEXT = "That's all I need for now. I'll start your day plans and cards from here. To tell me more, use Refresh what you know in settings."

        fun path(p: SeedPath) = "/${p.dir}/theogony.md"
        const val MILESTONES_PATH = "/profile/milestones.md"

        private fun line(f: SeedFact) = "- ${f.text} [${f.source}, ${f.status}, ${f.sure}]\n"
        private val order = compareBy<SeedFact>({ it.status != Status.CONFIRMED }, { it.status == Status.INFERRED }, { -it.confidence }, { it.text })

        /** Every path, always: a path with no facts left is blank, so the store drops its file. */
        fun files(facts: List<SeedFact>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for (p in SeedPath.entries) {
                val mine = facts.filter { it.path == p && !it.milestone }
                out[path(p)] = if (mine.isEmpty()) "" else buildString {
                    append("# ${p.title} (Theogony: what Athena learned getting to know him)\n")
                    append("After each line: source, status, confidence. confirmed = he said so; observed = a signal or a document; ")
                    append("inferred = a guess: never act on it as a fact, ask him or let the interview settle it.\n")
                    for (f in mine.sortedWith(order)) append(line(f))
                }
            }
            val ms = facts.filter { it.milestone }
            out[MILESTONES_PATH] = if (ms.isEmpty()) "" else buildString {
                append("# Milestones and special days (Theogony)\n")
                append("Confirmed ones may get a quiet \"one year since\" line on the day. Inferred ones are unconfirmed: do not mark them.\n")
                for (f in ms.sortedBy { it.date }) append("- ${f.date}: ${f.text.substringBeforeLast(':')} [${f.source}, ${f.status}]\n")
            }
            return out
        }

        /** The files the last save produced, for the mind's sync (it uploads them when they change). */
        fun savedFiles(kv: Kv): Map<String, String> = kv.get(K_FILES)?.let { runCatching { stateJson.readValue(it, object : TypeReference<Map<String, String>>() {}) }.getOrNull() }.orEmpty()
    }
}
