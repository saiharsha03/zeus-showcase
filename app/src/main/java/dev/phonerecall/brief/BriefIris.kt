package dev.phonerecall.brief

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Iris's part of the brief, as parsed from her reply. Display text only: never an action, never a proposal.
 * `slot` is when her own Telegram morning briefing fires ("07:15"), if she said, so Eos keeps clear of it.
 */
data class IrisSection(val lines: List<BriefLine> = emptyList(), val at: Long = 0, val slot: String? = null)

/**
 * The brief's request to Iris and her reply (docs/BRIEF.md, server/docs/IRIS-BRIEF.md). It rides the existing
 * POST /api/jobs route as an unsigned job, so the gateway marks it "FROM ATHENA ... SUGGESTION ONLY": she reads
 * and summarises, and changes or sends nothing. Nothing Zeus-private goes in the request: no health, no money,
 * no places, no screen time; only the date, his zone and what is wanted.
 */
object BriefIris {
    const val KIND = "brief.request"
    const val FORMAT = "zeus-brief v1"
    val WANT = listOf("mail_highlights", "tasks_for_owner", "bills_due", "flags")
    private val LABELS = mapOf("mail" to "Mail", "task" to "Task", "bill" to "Bill", "flag" to "Flag")

    fun jobId(date: LocalDate) = "brief-$date"
    fun title(date: LocalDate) = "Morning brief for $date: your part"

    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /**
     * The job payload. The same inputs give the same payload, so a retry is the same job (the gateway dedupes by id).
     * `eosAt` is when Eos expects to go out (his alarm, else the learned wake time), or null when not known yet.
     * `irisSlot` is her briefing time as the owner set it or she told us; without it she is asked for it.
     * Her schedule is hers: Eos never moves it, it only tells her when it fires.
     */
    fun request(date: LocalDate, zone: ZoneId, eosAt: LocalTime? = null, irisSlot: LocalTime? = null): Map<String, Any?> = linkedMapOf(
        "type" to KIND,
        "v" to 1,
        "for_date" to date.toString(),
        "zone" to zone.id,
        "want" to WANT + (if (irisSlot == null) listOf("your_briefing_time") else emptyList()),
        "reply_format" to FORMAT,
        "amounts" to false,
        "max_lines_each" to 3,
        "eos_fires_at" to eosAt?.toString(),
        "note" to "Athena's Eos fires at ${eosAt?.let { clock.format(it) } ?: "his alarm (no time known yet)"}; tell me if you'd like to move or merge yours.",
    )

    /** Her reply starts with this line; Zeus files it in the brief instead of showing it as a chat notification. */
    fun isReply(text: String?): Boolean = text?.trimStart()?.startsWith(FORMAT) == true

    private val line = Regex("^(mail|task|bill|flag)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val slotLine = Regex("^slot\\s*:\\s*(\\d{1,2}):(\\d{2})\\s*(am|pm)?\\s*$", RegexOption.IGNORE_CASE)

    /** "slot: 07:15" or "slot: 7:15 AM" to a time; anything else is null. */
    fun slot(raw: String): LocalTime? {
        val m = slotLine.matchEntire(raw.trim()) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        when (m.groupValues[3].lowercase()) { "pm" -> if (h < 12) h += 12; "am" -> if (h == 12) h = 0 }
        return if (h in 0..23 && min in 0..59) LocalTime.of(h, min) else null
    }
    private val money = Regex("""(?i)([$₹€£]\s?\d[\d,.]*|\b\d[\d,.]*\s?(usd|inr|rs\.?|dollars|rupees)\b)""")
    private val url = Regex("""(?i)\bhttps?://\S+""")

    /**
     * Her reply, made safe to show: only the four labelled kinds, at most three of each, one line of 160 characters,
     * no links, no amounts (cut even if she sent one), no control characters. "none" lines are dropped.
     * Returns null when the reply isn't in the brief format.
     */
    fun parse(text: String?, at: Long = 0): IrisSection? {
        if (!isReply(text)) return null
        val counts = HashMap<String, Int>()
        val out = ArrayList<BriefLine>()
        var slot: String? = null
        for (raw in text!!.lines().drop(1)) {
            slot(raw)?.let { slot = it.toString(); continue }
            val m = line.matchEntire(raw.trim()) ?: continue
            val kind = m.groupValues[1].lowercase()
            var v = m.groupValues[2].filter { it >= ' ' }.replace(url, "").replace(money, "[amount]").replace(Regex("\\s+"), " ").trim()
            if (v.isEmpty() || v.equals("none", true) || v.equals("nothing", true)) continue
            if (v.length > 160) v = v.take(159).trimEnd() + "…"
            val n = counts.merge(kind, 1, Int::plus)!!
            if (n <= 3) out += BriefLine(LABELS.getValue(kind), v)
        }
        val order = LABELS.values.toList()
        return IrisSection(out.sortedBy { order.indexOf(it.label) }, at, slot)
    }
}
