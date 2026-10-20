package dev.phonerecall.prometheus

import androidx.compose.runtime.Immutable
import dev.phonerecall.brain.ProposalKind
import kotlin.math.roundToInt

/*
 * Prometheus, "Self-improvement": the last phase, manual trigger only. Nothing here
 * runs on its own. A run reads what is already on the phone and turns it into suggestions; a suggestion
 * changes nothing until he approves it, and every applied one can be undone.
 */

/** What a suggestion may touch. Anything else is refused before it is even shown. */
enum class TweakKind(val wire: String, val label: String) {
    /** Move one Home card kind up or down (-10..+10 points on its score). */
    CARD_WEIGHT("card_weight", "Card weight"),
    /** Put one tier 2 proposal kind on the auto-approve list. */
    AUTO_APPROVE("auto_approve", "Auto-approve"),
    /** A standing note in Athena's instructions (data, not code). */
    NOTE("note", "Note for Athena");

    companion object { fun of(wire: String): TweakKind? = entries.firstOrNull { it.wire == wire } }
}

enum class TweakStatus { PROPOSED, APPLIED, REJECTED, UNDONE }

/**
 * One suggestion. `value` is what approving sets and `before` what undo puts back:
 * a weight as text ("-5"), "on"/"off" for auto-approve, or the note's new and old text.
 */
@Immutable
data class Tweak(
    val id: Long = 0,
    val runId: Long,
    val kind: TweakKind,
    val target: String,
    val value: String,
    val before: String,
    val why: String,
    val status: TweakStatus = TweakStatus.PROPOSED,
    val createdAt: Long = 0,
    val decidedAt: Long? = null,
    /** "local" from the phone's own counts, or the model that wrote it. */
    val source: String = "local",
)

/** A suggestion before it has an id. */
data class Draft(val kind: TweakKind, val target: String, val value: String, val before: String, val why: String)

/**
 * The hard limits. Pure: the same draft always gets the same answer. A non-null result is the reason it
 * is refused; refused drafts are logged and never shown as cards.
 */
object Guard {
    const val MAX_WEIGHT = 10
    const val MAX_NOTE = 280
    const val MAX_NOTES = 12

    /** Cards that carry alerts or the server: their place on Home is not Prometheus's to change. */
    val LOCKED_CARDS = setOf("SECURITY", "WEATHER_ALERT", "VPS", "GENERATED:BANK", "GENERATED:SECURITY")

    /** Words a suggestion may never carry: money, messages, deletes, the server, permissions. */
    private val FORBIDDEN = Regex(
        "(?i)\\b(money|pay(ment)?s?|transfer|bank|card number|send(ing)?|message|e-?mail|text (him|her|them)|sms|delete|remove (all|every)|wipe|" +
            "server|vps|ssh|sudo|root|deploy|install|permission|allowlist|auto-?approv\\w*|without (asking|confirm\\w*)|skip (the )?(card|confirm\\w*)|" +
            "password|credential|token|secret|ignore (the |all |previous )?(rules|instructions))\\b",
    )

    /** Kinds that only plain code may ever auto-run, whatever a count says. */
    fun autoAllowed(kind: ProposalKind): Boolean = kind.tier == 2 && kind.autoEligible && kind in ProposalKind.autoCandidates

    fun refuse(d: Draft): String? = when (d.kind) {
        TweakKind.CARD_WEIGHT -> {
            val w = d.value.toIntOrNull()
            when {
                d.target in LOCKED_CARDS -> "${d.target} carries alerts or the server; its place is fixed"
                !CARD_KEY.matches(d.target) -> "Not a card kind: ${d.target}"
                w == null || w !in -MAX_WEIGHT..MAX_WEIGHT -> "Weights stay between -$MAX_WEIGHT and $MAX_WEIGHT"
                else -> null
            }
        }
        TweakKind.AUTO_APPROVE -> {
            val k = ProposalKind.of(d.target)
            when {
                k == null -> "Not a proposal kind Fates knows: ${d.target}"
                k.tier >= 3 -> "${k.label} is tier ${k.tier}: always asked"
                !autoAllowed(k) -> "${k.label} can never be auto-approved"
                d.value != "on" && d.value != "off" -> "Auto-approve is on or off"
                else -> null
            }
        }
        TweakKind.NOTE -> when {
            d.value.isBlank() && d.before.isBlank() -> "An empty note"
            d.value.length > MAX_NOTE -> "Notes stay under $MAX_NOTE characters"
            FORBIDDEN.containsMatchIn(d.value) -> "A note can't grant anything or touch money, messages, deletes or the server"
            d.value.contains('\n') -> "One line per note"
            else -> null
        }
    }

    private val CARD_KEY = Regex("[A-Z_]{2,24}|GENERATED:[A-Z_]{2,24}")
}

/** Taps and dismissals of one card kind, summed over the week. */
data class CardCounts(val taps: Int, val dismissals: Int) {
    val n: Int get() = taps + dismissals
    val tapRate: Double get() = if (n == 0) 0.0 else taps.toDouble() / n
}

/** His own decisions on one proposal kind (auto-approved ones don't count). */
data class Decisions(val confirmed: Int, val rejected: Int, val expired: Int) {
    val decided: Int get() = confirmed + rejected
}

/** Delphi: thumbs on her lines, and the times he corrected her straight after one. */
data class DelphiSignal(val up: Int = 0, val down: Int = 0, val corrections: List<String> = emptyList())

/** Everything a run reads, gathered on the phone. Nothing here leaves it unless he runs the notes step. */
@Immutable
data class Signals(
    val cards: Map<String, CardCounts> = emptyMap(),
    val less: Map<String, Int> = emptyMap(),
    val proposals: Map<ProposalKind, Decisions> = emptyMap(),
    val delphi: DelphiSignal = DelphiSignal(),
    val snoozes: Int = 0,
    val autoOn: Set<ProposalKind> = emptySet(),
    val weights: Map<String, Int> = emptyMap(),
    val notes: List<String> = emptyList(),
)

/** The soft-tuning rules. Plain counting, no model. */
object Analyst {
    /** Fewer events than this say nothing yet. */
    const val MIN_CARD_EVENTS = 8
    const val MIN_DECISIONS = 5
    const val STEP = 5

    fun suggest(s: Signals): List<Draft> = buildList {
        for ((key, c) in s.cards.entries.sortedBy { it.key }) {
            if (c.n < MIN_CARD_EVENTS || key in Guard.LOCKED_CARDS) continue
            val now = s.weights[key] ?: 0
            val pct = (c.tapRate * 100).roundToInt()
            if (c.tapRate <= 0.25 && now > -Guard.MAX_WEIGHT) {
                val to = (now - STEP).coerceAtLeast(-Guard.MAX_WEIGHT)
                add(Draft(TweakKind.CARD_WEIGHT, key, "$to", "$now", "You opened ${name(key)} $pct% of the ${c.n} times it showed. Move it down."))
            } else if (c.tapRate >= 0.75 && now < Guard.MAX_WEIGHT) {
                val to = (now + STEP).coerceAtMost(Guard.MAX_WEIGHT)
                add(Draft(TweakKind.CARD_WEIGHT, key, "$to", "$now", "You opened ${name(key)} $pct% of the ${c.n} times it showed. Move it up."))
            }
        }
        for ((key, n) in s.less.entries.sortedBy { it.key }) {
            if (n < 2 || key in Guard.LOCKED_CARDS || any { it.target == key }) continue
            val now = s.weights[key] ?: 0
            if (now > -Guard.MAX_WEIGHT) add(Draft(TweakKind.CARD_WEIGHT, key, "${(now - STEP).coerceAtLeast(-Guard.MAX_WEIGHT)}", "$now", "You said \"Less like this\" to ${name(key)} $n times."))
        }
        if (s.snoozes >= 5 && none { it.target == "TASKS" }) {
            val now = s.weights["TASKS"] ?: 0
            if (now > -Guard.MAX_WEIGHT) add(Draft(TweakKind.CARD_WEIGHT, "TASKS", "${(now - 3).coerceAtLeast(-Guard.MAX_WEIGHT)}", "$now", "You snoozed ${s.snoozes} tasks off Home. Let Tasks due sit a little lower."))
        }
        for ((kind, d) in s.proposals.entries.sortedBy { it.key.ordinal }) {
            if (kind in s.autoOn || !Guard.autoAllowed(kind)) continue
            if (d.confirmed >= MIN_DECISIONS && d.rejected == 0) {
                add(Draft(TweakKind.AUTO_APPROVE, kind.wire, "on", "off", "You confirmed all ${d.confirmed} ${kind.label.lowercase()} proposals and turned none down."))
            }
        }
        for ((kind, d) in s.proposals.entries.sortedBy { it.key.ordinal }) {
            if (d.decided < MIN_DECISIONS || d.rejected * 10 < d.decided * 7) continue
            val note = "The owner turns down most ${kind.label.lowercase()} proposals. Offer one only when he asks for it."
            if (note !in s.notes && s.notes.size < Guard.MAX_NOTES) add(Draft(TweakKind.NOTE, "athena", note, "", "He turned down ${d.rejected} of ${d.decided} ${kind.label.lowercase()} proposals."))
        }
    }.filter { Guard.refuse(it) == null }

    fun name(key: String): String = key.removePrefix("GENERATED:").lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }.let {
        if (key.startsWith("GENERATED:")) "$it cards" else it
    }
}

/** What applying or undoing a tweak does to the phone. Implemented over BrainHub and the tuning store. */
interface Knobs {
    fun weight(key: String): Int
    fun setWeight(key: String, value: Int)
    fun autoOn(kind: ProposalKind): Boolean
    fun setAuto(kind: ProposalKind, on: Boolean)
    fun notes(): List<String>
    fun setNotes(notes: List<String>)
}
