package dev.phonerecall.brain

import com.anthropic.core.jsonMapper
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/*
 * Triage: does this notification matter? Rules first (people tiers, app, keywords). What the rules
 * can't settle goes to Gemini Nano while Zeus is on screen, or, in the background, to Haiku in a batch
 * at most once per 15 minutes, as derived lines only.
 */

enum class Importance { HIGH, MEDIUM, LOW, NOISE }

enum class NoteKind { CALL, MESSAGE, REQUEST, DELIVERY, SECURITY, PROMO, BANK, OTHER }

data class Verdict(
    val importance: Importance,
    val kind: NoteKind,
    val reason: String,
    /** The rules could not decide; a model should look. */
    val needsModel: Boolean = false,
    /** Worth a background Haiku call (an unknown person asking something, a deadline). */
    val looksImportant: Boolean = false,
)

object Rules {
    private fun up(i: Importance) = Importance.entries[(i.ordinal - 1).coerceAtLeast(0)]

    /** The rules, then his own word on whole kinds of app (the fresh start's "always matter"): never below medium. */
    fun triage(kind: AppKind, who: Who?, person: Person?, cues: Cues, isGroup: Boolean): Verdict =
        rules(kind, who, person, cues, isGroup).let { v ->
            if (dev.phonerecall.fresh.SenseRules.matters(kind) && v.importance > Importance.MEDIUM && v.kind != NoteKind.PROMO && person?.tier != Tier.MUTED) v.copy(importance = Importance.MEDIUM, reason = v.reason + ", always matters") else v
        }

    private fun rules(kind: AppKind, who: Who?, person: Person?, cues: Cues, isGroup: Boolean): Verdict {
        val tier = person?.tier
        if (who?.missedCall == true) {
            val i = when (tier) { Tier.REPLY_NOW -> Importance.HIGH; Tier.MUTED -> Importance.LOW; else -> Importance.MEDIUM }
            return Verdict(i, NoteKind.CALL, "missed call")
        }
        if (cues.security) return Verdict(Importance.HIGH, NoteKind.SECURITY, "security words")
        if (!kind.personal) {
            return when {
                cues.promo -> Verdict(Importance.NOISE, NoteKind.PROMO, "promotion")
                cues.delivery -> Verdict(Importance.MEDIUM, NoteKind.DELIVERY, "delivery or refund")
                cues.urgent || cues.deadline != null -> Verdict(Importance.LOW, NoteKind.OTHER, "app, time words", needsModel = true, looksImportant = true)
                else -> Verdict(Importance.LOW, NoteKind.OTHER, "app", needsModel = true)
            }
        }
        if (tier == Tier.MUTED) return Verdict(Importance.NOISE, NoteKind.MESSAGE, "muted person")
        val request = cues.request && (cues.deadline != null || cues.question || kind == AppKind.WORK || kind == AppKind.MAIL)
        val noteKind = if (request) NoteKind.REQUEST else NoteKind.MESSAGE
        var i = when (tier) {
            Tier.REPLY_NOW -> Importance.HIGH
            Tier.TODAY -> Importance.MEDIUM
            else -> Importance.LOW
        }
        if (isGroup && tier != Tier.REPLY_NOW) i = Importance.LOW
        if (request || cues.urgent || cues.deadline != null) i = up(i)
        // Someone with no history and no relationship: the rules can't tell.
        val unknown = person == null || (person.relation == null && person.pinned == null && !person.learned)
        val asking = cues.request || cues.question || cues.urgent || cues.deadline != null || kind == AppKind.WORK
        return Verdict(i, noteKind, if (tier != null) "tier ${tier.word}" else "unknown sender", needsModel = unknown && !isGroup, looksImportant = unknown && !isGroup && asking)
    }
}

/** One model answer for one derived line. */
data class ModelVerdict(val index: Int, val importance: Importance, val card: String? = null)

/** The prompt both models get, and the parser for what comes back. */
object TriagePrompt {
    val SYSTEM = """
You sort the owner's phone notifications by importance. Each line is one notification, described by derived facts only: the app kind, who it is from as a label (never a name), his priority tier for that person, cues read from the words, and for ordinary apps a short masked snippet. People's own words are never included.

Importance:
- high: he should see it within minutes (family or his manager asking for him, anything urgent, security).
- medium: today is fine (a request with a deadline, a delivery or refund update, a question from someone he answers).
- low: whenever (most group chat, app news).
- noise: promotions, marketing, social media likes.

Answer with only a JSON array, one object per line, in order: [{"i": 1, "importance": "high", "card": ""}]. "card" is at most 8 plain words about why it matters, or empty. No names, no emoji, no em dashes.
""".trim()

    fun user(lines: List<String>): String = "Notifications:\n" + lines.joinToString("\n")

    fun parse(text: String): List<ModelVerdict> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val node = runCatching { jsonMapper().readTree(text.substring(start, end + 1)) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return node.mapNotNull { o ->
            val i = o.get("i")?.asInt(-1) ?: -1
            val imp = Importance.entries.firstOrNull { it.name.equals(o.get("importance")?.asText(), ignoreCase = true) }
            if (i < 0 || imp == null) null
            else ModelVerdict(i, imp, o.get("card")?.asText()?.trim()?.replace('—', ',')?.take(80)?.takeIf { it.isNotEmpty() })
        }
    }
}

/** Haiku in the background: at most once per 15 minutes, and only when something important-looking waits. */
object TriageBatch {
    const val WINDOW_MS = 15 * 60_000L
    const val MAX_LINES = 20

    fun due(pendingImportant: Int, lastCallAt: Long?, now: Long): Boolean =
        pendingImportant > 0 && (lastCallAt == null || now - lastCallAt >= WINDOW_MS)

    /** How long until the next call is allowed. */
    fun delay(lastCallAt: Long?, now: Long): Long = lastCallAt?.let { (it + WINDOW_MS - now).coerceAtLeast(0) } ?: 0
}

/** 11 PM to 7 AM is quiet. Only reply-now people get through, and only for something that matters now. */
object QuietHours {
    fun quiet(t: ZonedDateTime): Boolean = t.hour >= 23 || t.hour < 7

    /** Zeus posts its own nudge only in quiet hours, when the source app's sound is likely silenced. */
    fun breaksThrough(tier: Tier?, importance: Importance, t: ZonedDateTime): Boolean =
        quiet(t) && tier == Tier.REPLY_NOW && importance == Importance.HIGH
}

/** "can you send the report by EOD" becomes a task "Send the report", due today 5 PM. */
object Requests {
    private val LEAD = Regex("(?i)^(hey|hi|hello|yo|ok|okay|so|also|and)[,!.]?\\s+")
    private val ASK = Regex("(?i)^(can you|could you|would you|can u|could u|will you|pls|plz|please|need you to|i need you to|make sure (to|you)?|don'?t forget to|remember to)(\\s+|$)")
    private val TAIL = Regex(
        "(?i)[\\s,]*(by (eod|e\\.o\\.d|end of (the )?day|cob|close of business|tonight|tomorrow|tmrw|today|\\d{1,2}(:\\d{2})?\\s?(am|pm))|" +
            "eod|tonight|tomorrow|today|asap|please|pls|plz|thanks|thank you|thx)[\\s?.!]*$",
    )
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    /** The task title, or null when there is nothing left to do once the asking words are gone. */
    fun title(text: String): String? {
        val sentence = text.split(Regex("(?<=[.!?])\\s+|\\n")).firstOrNull { CueReader.read(it).request } ?: return null
        var t = sentence.trim().trimEnd('?', '.', '!').trim()
        repeat(3) { t = t.replace(LEAD, "") }
        t = t.replace(ASK, "")
        repeat(3) { t = t.replace(TAIL, "") }
        t = t.trim().trimEnd('?', '.', '!', ',').trim()
        if (t.length < 3) return null
        return t.replaceFirstChar { it.uppercase() }.take(80)
    }

    /** A due time for a deadline cue: "2026-10-19T17:00", or a date for "tomorrow". Null with no cue. */
    fun due(deadline: String?, now: ZonedDateTime): String? {
        val today = now.toLocalDate()
        fun at(h: Int, m: Int = 0): String {
            val t = today.atTime(h, m).atZone(now.zone)
            return ISO.format(if (t.isAfter(now)) t else t.plusDays(1))
        }
        return when (deadline) {
            null -> null
            "eod" -> when {
                now.toLocalTime().isBefore(LocalTime.of(17, 0)) -> at(17)
                now.toLocalTime().isBefore(LocalTime.of(21, 0)) -> at(21)
                else -> ISO.format(today.plusDays(1).atTime(9, 0))
            }
            "tonight" -> at(21)
            "tomorrow" -> today.plusDays(1).toString()
            else -> runCatching { LocalTime.parse(deadline) }.getOrNull()?.let { at(it.hour, it.minute) }
        }
    }
}

/** Card wording for things the senses noticed. Shown to him only, so names are fine here. */
object SenseWords {
    fun calls(name: String, n: Int): String = when (n) {
        1 -> "$name called"
        2 -> "$name called twice"
        else -> "$name called $n times"
    }

    fun messages(name: String, n: Int): String = if (n <= 1) "Message from $name" else "$name sent $n messages"
}
