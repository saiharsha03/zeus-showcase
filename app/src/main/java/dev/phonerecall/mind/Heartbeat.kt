package dev.phonerecall.mind

import com.fasterxml.jackson.core.type.TypeReference
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What changed since the last beat and what is ahead, built on the phone with no model call. Notes are
 * new since the last beat by construction; the rest is the current state, compared by [fingerprint].
 */
data class HeartbeatDigest(
    val notes: List<String> = emptyList(),
    val calendar: List<String> = emptyList(),
    val jobs: List<String> = emptyList(),
    val asks: List<String> = emptyList(),
    val different: List<String> = emptyList(),
    val trip: String? = null,
    val place: String? = null,
) {
    fun fingerprint(): String = sha(listOf(calendar, jobs, asks, different, listOf(trip), listOf(place)).toString())

    fun changedSince(last: String?): Boolean = notes.isNotEmpty() || fingerprint() != last

    fun text(): String = buildString {
        fun part(title: String, lines: List<String>) { append("\n## $title\n"); append(if (lines.isEmpty()) "- nothing\n" else lines.joinToString("") { "- $it\n" }) }
        part("New since the last beat", notes)
        part("Calendar, next 6 hours", calendar)
        part("Long jobs open", jobs)
        part("Asks to Iris", asks)
        part("Different from usual", different)
        part("Trip", listOfNotNull(trip))
        part("Where he is", listOfNotNull(place))
    }
}

/**
 * The phone-side gate: no model call unless a beat is worth it. Hourly from 7 AM to 11 PM his time (none in
 * quiet hours), never at 90% of the month's cap or past $0.60 of heartbeats today, and silent when nothing
 * changed and the last real beat was under three hours ago.
 */
object HeartbeatGate {
    const val FIRST_HOUR = 7
    const val END_HOUR = 23
    const val DAY_CENTS = 60L
    const val MONTH_PCT = 90
    const val STALE_MS = 3 * 3_600_000L

    fun quiet(t: ZonedDateTime): Boolean = t.hour < FIRST_HOUR || t.hour >= END_HOUR

    /** Why this beat is skipped, or null to run it. `changed` is only asked once the cheap checks pass. */
    fun skip(now: ZonedDateTime, on: Boolean, spentCents: Long, capCents: Long, dayCents: Long, lastReal: Long?, changed: () -> Boolean): String? = when {
        !on -> "off"
        quiet(now) -> "quiet hours"
        capCents <= 0 || spentCents * 100 >= capCents * MONTH_PCT -> "month at $MONTH_PCT% of the cap"
        dayCents >= DAY_CENTS -> "today's heartbeat budget used"
        !changed() && lastReal != null && now.toInstant().toEpochMilli() - lastReal < STALE_MS -> "nothing new"
        else -> null
    }
}

object HeartbeatPrompt {
    const val OK = "HEARTBEAT_OK"

    fun message(digest: HeartbeatDigest, tag: String): String =
        "[Zeus] Heartbeat [$tag]. Look at what changed and what's ahead. Decide on your own: is there something worth doing now " +
            "(a proposal, a card, starting or nudging a long job, asking Iris), or nothing? If nothing, reply exactly $OK and do nothing.\n" + digest.text()

    /** True when she said only HEARTBEAT_OK (or nothing) and called no tool: the beat stays silent. */
    fun silent(messages: List<String>, toolCalls: Int): Boolean = toolCalls == 0 && messages.all { it.trim().trim('.', '`', '*').equals(OK, ignoreCase = true) }
}

/** Notes the phone offered since the last beat (the batcher clears its queue on send, so they are kept here too). */
object HeartbeatNotes {
    private const val KEY = "hb.notes"
    private val type = object : TypeReference<List<MindNote>>() {}

    fun add(kv: Kv, n: MindNote) = kv.putList(KEY, (kv.list(KEY, type).filter { n.key.isEmpty() || it.key != n.key } + n.copy(text = n.text.take(200))).takeLast(30))
    fun since(kv: Kv, t: Long): List<MindNote> = kv.list(KEY, type).filter { it.at > t }
}

data class HeartbeatView(val on: Boolean = true, val line: String = "")

/** The heartbeat's small state in the mind's Kv. */
class HeartbeatStore(private val kv: Kv) {
    var on: Boolean
        get() = kv.get("hb.off") == null
        set(v) = kv.put("hb.off", if (v) null else "1")
    val lastBeat: Long? get() = kv.long("hb.last")
    val lastReal: Long? get() = kv.long("hb.real")
    val fingerprint: String? get() = kv.get("hb.fp")

    fun dayCents(zone: ZoneId, now: Long): Long = kv.long("hb.day." + Instant.ofEpochMilli(now).atZone(zone).toLocalDate()) ?: 0L

    /** Every beat, skipped or not: the cursor for new notes and the outcome Themis shows. */
    fun beat(now: Long, outcome: String) { kv.putLong("hb.last", now); kv.put("hb.outcome", outcome) }

    /** A beat that reached Athena: its fingerprint and its cost against today's heartbeat budget. */
    fun real(now: Long, fingerprint: String, cents: Long, zone: ZoneId) {
        kv.putLong("hb.real", now)
        kv.put("hb.fp", fingerprint)
        val key = "hb.day." + Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        kv.putLong(key, (kv.long(key) ?: 0L) + cents)
    }

    fun view(zone: ZoneId): HeartbeatView {
        val last = lastBeat?.let { "${DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.ofEpochMilli(it).atZone(zone))} ${kv.get("hb.outcome").orEmpty()}".trim() } ?: "not yet"
        return HeartbeatView(on, "Hourly, 7 AM to 11 PM · last: $last")
    }
}

data class HeartbeatResult(val outcome: String, val cents: Long, val silent: Boolean)

/** One heartbeat turn on this month's session. Results go through her card and propose tools; nothing executes. */
fun Mind.heartbeat(digest: HeartbeatDigest, now: Long, spent: () -> Long): HeartbeatResult {
    val athena = ensure()
    val sid = session(athena) ?: return HeartbeatResult("no session (cap)", 0, true)
    val before = spent()
    val tag = "hb" + sha(digest.fingerprint() + now)
    val r = drive(sid, sanitizer().clean(HeartbeatPrompt.message(digest, tag)), tag)
    account(sid)
    val cents = (spent() - before).coerceAtLeast(0)
    val silent = HeartbeatPrompt.silent(r.messages, r.toolCalls)
    val outcome = when {
        r.stop == "budget_reached" -> "paused at cap"
        silent -> "nothing to do"
        r.toolCalls > 0 -> "acted (${r.toolCalls} tool call${if (r.toolCalls == 1) "" else "s"})"
        else -> "replied"
    }
    return HeartbeatResult(outcome, cents, silent)
}
