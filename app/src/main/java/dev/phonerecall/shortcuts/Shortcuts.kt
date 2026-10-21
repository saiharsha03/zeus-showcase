package dev.phonerecall.shortcuts

import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.brain.Sealer
import dev.phonerecall.mind.Kv
import dev.phonerecall.mind.stateJson
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/*
 * Learned shortcuts and Athena's ideas. The phone keeps the evidence: what he did right after something happened
 * ("opens Paramount+ when a Barca match starts", "plays owner_1 at the gym"), with counts, contexts and when last seen.
 * Athena decides what is worth offering and why (no fixed threshold, no weekly quota); everything she picks in one pass
 * goes in ONE grouped card, a Yes / Not now / Never row each. His Yes makes a small sealed rule: a one-tap card each time,
 * or, only for kinds Fates may auto-approve, done with Undo. Her ideas come the same way (Yes / No): a rule when it is a
 * trigger and an allowed action, otherwise a drafted Prometheus request he sends himself.
 * Hard rules kept here: never offer what he answered Never or No to, never anything on the killed list, and never a
 * shortcut that sends a message or pays.
 */

/** Something that happened. Triggers: "arrive:<place>", "match:<team>", Hestia's "leave:home", "sun:set", "bedtime". Actions: "open:<package>", "music:<uri or query>", "alarm:<h>:<m>", "home:<device>:<action>". */
data class Seen(val at: Long = 0, val key: String = "", val label: String = "", val args: Map<String, String> = emptyMap())

/** One (trigger, action) pair as Athena sees it: how often, on how many days, when, and when last. */
data class Evidence(
    val trigger: String = "",
    val triggerLabel: String = "",
    val action: String = "",
    val actionLabel: String = "",
    val times: Int = 0,
    val days: Int = 0,
    /** Hours of the day (his zone) and weekdays it happened. */
    val hours: List<Int> = emptyList(),
    val weekdays: List<String> = emptyList(),
    val lastSeen: Long = 0,
    /** Minutes from the trigger to the action, on average. */
    val minutesAfter: Int = 0,
    val args: Map<String, String> = emptyMap(),
) {
    val key: String get() = "$trigger>$action"
}

enum class Mode { SUGGEST, AUTO }

data class Rule(
    val id: String = "",
    val trigger: String = "",
    val triggerLabel: String = "",
    val action: String = "",
    val actionLabel: String = "",
    val args: Map<String, String> = emptyMap(),
    val mode: Mode = Mode.SUGGEST,
    val createdAt: Long = 0,
    val fired: Int = 0,
    val lastFired: Long? = null,
) {
    val key: String get() = "$trigger>$action"
}

/** What the book keeps, sealed as one value. */
data class BookData(
    val rules: List<Rule> = emptyList(),
    /** Pattern keys he said Never to: never offered again. */
    val never: Set<String> = emptySet(),
    /** Pattern keys he said Not now to, and when (she sees it, and decides when to ask again). */
    val notNow: Map<String, Long> = emptyMap(),
    /** Ideas he said No to, by their normalized title: never offered again. */
    val declined: Set<String> = emptySet(),
    /** His answers on grouped cards still open: "<proposal id>:<row>" to yes, notnow, never or no. */
    val answers: Map<String, String> = emptyMap(),
    /** Triggers seen in the last 30 days (app opens come from UsageStats when the evidence is read). */
    val triggers: List<Seen> = emptyList(),
    /** Actions he took through Zeus (music, alarms, Hestia), last 30 days. */
    val acted: List<Seen> = emptyList(),
)

object Learn {
    /** What he did within this long after the trigger counts as "right after". */
    const val WINDOW_MS = 15 * 60_000L
    const val KEEP_MS = 30 * 86_400_000L
    /** How many pairs she is shown, most days first. */
    const val EVIDENCE_MAX = 40

    /** What a shortcut may do. Never a message or a payment: those stay one-tap chips he sends himself. */
    val ACTIONS = setOf("open", "music", "alarm", "home")

    fun allowed(action: String): Boolean = action.substringBefore(':') in ACTIONS

    /** What may run on its own (with Undo): only kinds Fates lets auto-approve (an alarm, a light). Apps can't open from the background. */
    fun autoAllowed(r: Rule): Boolean = r.action.startsWith("alarm:") || (r.action.startsWith("home:") && r.args["light"] == "true")

    /** One step he took through Zeus as a candidate action, or null when it can't be one. */
    fun seen(step: dev.phonerecall.brain.Step): Seen? {
        val p = step.payload
        return when (step.kind) {
            dev.phonerecall.brain.ProposalKind.SET_ALARM -> Seen(0, "alarm:${p["hour"]}:${p["minute"]}", "set an alarm for ${p["hour"]}:${p["minute"]?.padStart(2, '0')}", p - "days")
            dev.phonerecall.brain.ProposalKind.PLAY_MUSIC -> Seen(0, "music:${p["uri"] ?: p["query"]}", "play ${p["name"] ?: "that"}", p - "reason")
            dev.phonerecall.brain.ProposalKind.LIGHTS, dev.phonerecall.brain.ProposalKind.HOME_CONTROL ->
                Seen(0, "home:${p["device_id"] ?: p["device"]}:${p["action"]}", step.summary.replaceFirstChar { it.lowercase() }, p + ("light" to "${step.kind == dev.phonerecall.brain.ProposalKind.LIGHTS}"))
            else -> null
        }
    }

    /** Every (trigger, allowed action) pair he did, the action within [WINDOW_MS] after: the evidence she judges. */
    fun evidence(triggers: List<Seen>, actions: List<Seen>, zone: ZoneId): List<Evidence> {
        data class Hit(val t: Seen, val a: Seen)
        val hits = LinkedHashMap<String, MutableList<Hit>>()
        for (t in triggers) for (a in actions) {
            if (a.at < t.at || a.at > t.at + WINDOW_MS || !allowed(a.key)) continue
            hits.getOrPut("${t.key}>${a.key}") { ArrayList() } += Hit(t, a)
        }
        return hits.values.map { l ->
            val at = l.map { Instant.ofEpochMilli(it.t.at).atZone(zone) }
            val last = l.maxBy { it.t.at }
            Evidence(
                last.t.key, last.t.label, last.a.key, last.a.label, l.size, at.map { it.toLocalDate() }.distinct().size,
                at.map { it.hour }.distinct().sorted(), at.map { it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) }.distinct(),
                last.t.at, (l.sumOf { it.a.at - it.t.at } / l.size / 60_000L).toInt(), last.a.args,
            )
        }.sortedWith(compareByDescending<Evidence> { it.days }.thenByDescending { it.lastSeen }).take(EVIDENCE_MAX)
    }
}

/** One shortcut Athena chose to offer, with her one-line why. */
data class Pick(val trigger: String = "", val action: String = "", val why: String = "")

/** One idea of hers. A rule when it has a trigger and an allowed action; otherwise a Prometheus request (title, problem, acceptance). */
data class Idea(
    val title: String = "",
    val why: String = "",
    val trigger: String? = null,
    val triggerLabel: String? = null,
    val action: String? = null,
    val actionLabel: String? = null,
    val problem: String? = null,
    val acceptance: String? = null,
) {
    val rule: Boolean get() = !trigger.isNullOrBlank() && !action.isNullOrBlank() && Learn.allowed(action)
}

/** The hard rules on her picks and ideas. Everything else is her judgement. */
object Offer {
    /** Her picks that stand: on the phone's evidence, an allowed action, not a Never, not a rule already. And why each other one doesn't. */
    fun shortcuts(picks: List<Pick>, evidence: List<Evidence>, d: BookData): Pair<List<Pair<Pick, Evidence>>, List<String>> {
        val ok = ArrayList<Pair<Pick, Evidence>>()
        val no = ArrayList<String>()
        val ruled = d.rules.map { it.key }.toSet()
        for (p in picks.distinctBy { it.trigger to it.action }) {
            val key = "${p.trigger}>${p.action}"
            val e = evidence.firstOrNull { it.key == key }
            when {
                !Learn.allowed(p.action) -> no += "$key: a shortcut never sends a message or pays"
                key in d.never -> no += "$key: he said Never"
                key in ruled -> no += "$key: already a shortcut"
                e == null -> no += "$key: not in the phone's evidence"
                p.why.isBlank() -> no += "$key: give a one-line why"
                else -> ok += p to e
            }
        }
        return ok to no
    }

    /** Words that carry an idea, plural folded ("recipes" and "recipe" match). */
    fun words(s: String): Set<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.map { it.removeSuffix("s") }.toSet()

    fun title(s: String): String = words(s).sorted().joinToString(" ")

    /** True when every word of a killed item is in the idea (Bills home-game traffic, recipes, watch app...). */
    fun killed(idea: Idea, killed: List<String>): String? {
        val w = words(idea.title + " " + idea.why + " " + idea.problem.orEmpty())
        return killed.firstOrNull { k -> words(k).let { it.isNotEmpty() && w.containsAll(it) } }
    }

    /** Declined before: the same title, or most of its words. */
    fun declined(idea: Idea, declined: Set<String>): Boolean {
        val w = words(idea.title)
        return title(idea.title) in declined || declined.any { d -> val dw = d.split(' ').toSet(); dw.isNotEmpty() && w.intersect(dw).size * 10 >= maxOf(w.size, dw.size) * 6 }
    }

    /** Her ideas that stand, and why each other one doesn't. */
    fun ideas(ideas: List<Idea>, killed: List<String>, d: BookData, backlogDeclined: List<String> = Killed.DECLINED): Pair<List<Idea>, List<String>> {
        val ok = ArrayList<Idea>()
        val no = ArrayList<String>()
        val ruled = d.rules.map { it.key }.toSet()
        val declined = d.declined + backlogDeclined.map { title(it) }
        for (i in ideas.distinctBy { title(it.title) }) {
            val k = killed(i, killed)
            when {
                i.title.isBlank() || i.why.isBlank() -> no += "${i.title}: a title and a one-line why"
                k != null -> no += "${i.title}: \"$k\" was killed; don't rebuild it"
                declined(i, declined) -> no += "${i.title}: he said No to this before"
                i.action != null && !Learn.allowed(i.action) -> no += "${i.title}: nothing that sends a message or pays on its own"
                i.rule && "${i.trigger}>${i.action}" in ruled -> no += "${i.title}: already a shortcut"
                i.rule && "${i.trigger}>${i.action}" in d.never -> no += "${i.title}: he said Never to that one"
                !i.rule && (i.problem.isNullOrBlank() || i.acceptance.isNullOrBlank()) -> no += "${i.title}: a code change needs a problem and an acceptance line"
                else -> ok += i
            }
        }
        return ok to no
    }
}

/**
 * docs/BACKLOG.md's "Killed, don't rebuild" (with its 2026-10-20 additions) and "Declined QoL ideas": shipped to Athena
 * and checked on the phone. A test keeps these copies the same as the backlog.
 */
object Killed {
    val ALL = listOf(
        "translation of any kind", "screen awareness", "an agent that drives other apps", "scam detection", "call screening",
        "panchangam", "Bills home-game traffic", "PL pool", "watch app", "last year's festival memory", "standup rehearsal", "recipes",
        "family gift memory", "server-errors memory",
    )

    /** Ideas he already said No to in the backlog: offered never again, like a No on a card. */
    val DECLINED = listOf(
        "Game mode", "join-meeting chip (he joins on the laptop)", "Spotify link → queue", "group thread summary on the cover screen",
        "match times in both clocks shareable", "gate change → tell the pickup person", "friend birthday 1:1 nudge", "Wi-Fi share for guests",
        "office parking reservation", "spoiler guard on news apps", "PUBG session break", "podcast in a calendar gap", "workout cooldown timer",
    )
}

/** The learned shortcuts on the phone, sealed (Keystore key "zeus.aegis.shortcuts" on the phone). */
class ShortcutBook(private val kv: Kv, private val sealer: Sealer, private val clock: () -> Long = System::currentTimeMillis) {
    private val type = object : TypeReference<BookData>() {}

    fun load(): BookData = kv.get(KEY)?.let { sealer.open(it) }?.let { runCatching { stateJson.readValue(it, type) }.getOrNull() } ?: BookData()

    @Synchronized
    fun update(change: (BookData) -> BookData): BookData = change(load()).also { kv.put(KEY, sealer.seal(stateJson.writeValueAsString(it))) }

    fun rules(): List<Rule> = load().rules

    fun seenTrigger(s: Seen) = update { d -> d.copy(triggers = (d.triggers + s).filter { clock() - it.at < Learn.KEEP_MS }.takeLast(400)) }

    fun acted(s: Seen) = update { d -> d.copy(acted = (d.acted + s).filter { clock() - it.at < Learn.KEEP_MS }.takeLast(400)) }

    fun never(key: String) = update { d -> d.copy(never = d.never + key, rules = d.rules.filter { it.key != key }) }
    fun notNow(key: String) = update { d -> d.copy(notNow = d.notNow + (key to clock())) }
    fun decline(title: String) = update { d -> d.copy(declined = d.declined + Offer.title(title)) }

    fun answer(row: String, value: String) = update { d -> d.copy(answers = d.answers + (row to value)) }
    fun answers(proposal: Long): Map<Int, String> = load().answers.filterKeys { it.startsWith("$proposal:") }.mapKeys { it.key.substringAfter(':').toInt() }
    fun clearAnswers(proposal: Long) = update { d -> d.copy(answers = d.answers.filterKeys { !it.startsWith("$proposal:") }) }

    /** His Yes: a rule that suggests (a one-tap card) until he changes it in Horologion. Saying Yes twice keeps one. */
    fun accept(trigger: Seen, action: Seen): Rule {
        val r = Rule("sc-" + dev.phonerecall.mind.sha(trigger.key + action.key).take(8), trigger.key, trigger.label, action.key, action.label, action.args, Mode.SUGGEST, clock())
        update { d -> if (d.rules.any { it.key == r.key }) d else d.copy(rules = d.rules + r) }
        return rules().first { it.key == r.key }
    }

    fun remove(id: String) = update { d -> d.copy(rules = d.rules.filter { it.id != id }) }

    /** A mode the rule may have: auto only where [Learn.autoAllowed]. False when not allowed. */
    fun setMode(id: String, mode: Mode): Boolean {
        val r = rules().firstOrNull { it.id == id } ?: return false
        if (mode == Mode.AUTO && !Learn.autoAllowed(r)) return false
        update { d -> d.copy(rules = d.rules.map { if (it.id == id) it.copy(mode = mode) else it }) }
        return true
    }

    /** The rules this trigger fires, each counted. */
    fun fire(trigger: String): List<Rule> {
        val hit = rules().filter { it.trigger == trigger }
        if (hit.isEmpty()) return hit
        val now = clock()
        update { d -> d.copy(rules = d.rules.map { if (it.trigger == trigger) it.copy(fired = it.fired + 1, lastFired = now) else it }) }
        return hit
    }

    private companion object { const val KEY = "book" }
}
