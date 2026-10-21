package dev.phonerecall.prometheus

import dev.phonerecall.clio.ClioLine
import dev.phonerecall.clio.Level
import dev.phonerecall.mind.MindEvent
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.ToolSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.getAndUpdate

/*
 * What Prometheus may see of Clio (docs/PROMETHEUS.md "Clio evidence"):
 * - every request carries the crash and freeze entries ("crash": ANRs and uncaught exceptions; "ui": main-thread
 *   stalls) of the last 48 h, stripped to code frames and their time, area, level and event;
 * - while a request is open, Prometheus can ask for more with read_clio, answered by the phone on its progress poll.
 * Everything passes the Sanitizer again on the way out. Areas that hold a trail or people stay on the phone.
 */
object ClioEvidence {
    const val TOOL = "read_clio"
    const val WINDOW_MS = 48 * 3_600_000L
    const val KEEP_MS = 14 * 86_400_000L
    /** Crash and freeze entries per request, newest first. */
    const val ENTRIES = 8
    const val LIMIT = 500
    /** The most text one attachment or one read_clio answer carries. */
    const val CHARS = 60_000
    val STACK_AREAS = dev.phonerecall.clio.Clio.STACK_AREAS
    /** Visits and drives (a location trail), birthdays (names), the device's day (apps, car): never sent. */
    val NEVER = setOf("places", "odyssey", "chronos", "device")

    private val FRAME = Regex("""at [\w$.<>\-]+\((?:[\w$.\-]+(?::\d+)?|Native [Mm]ethod|Unknown Source)\)""")
    private val BARE = Regex("""[\w$<>\-]+\.[\w$<>\-]+:-?\d+""")
    private val OURS = Regex("""(?:\d+ samples\. )?Ours: (?:none|[\w$<>.\-]+:-?\d+ \d+%(?:; [\w$<>.\-]+:-?\d+ \d+%)*)""")
    private val LOCK = Regex("""- (?:waiting to lock|locked|waiting on|sleeping on) <0x[0-9a-f]+> \(a [\w$.]+\)(?: held by thread \d+)?""")
    private val THREAD = Regex("""^("[\w\- ]+" (?:daemon )?prio=\d+ tid=\d+ \w+)""")
    private val THROWN = Regex("""^(Caused by: |Suppressed: )?((?:[\w$]+\.)+[\w$]*(?:Exception|Error|Throwable))\b""")
    private val LINE = Regex(""":(\d+)\)$""")
    private val SAMPLES = Regex("""\d+ samples\.|Most common \(\d+x\):|\.\.\. \d+ more""")

    /** A stack with only its code left: frames, locks, thread states, exception classes (never their messages). */
    fun frames(detail: String): String = detail.lineSequence().map { it.trim() }.mapNotNull { t ->
        when {
            FRAME.matches(t) || BARE.matches(t) || LOCK.matches(t) || OURS.matches(t) || SAMPLES.matches(t) -> t
            else -> THREAD.find(t)?.groupValues?.get(1) ?: THROWN.find(t)?.let { it.groupValues[1] + it.groupValues[2] }
        }
    }.joinToString("\n")

    private fun stamp(ts: Long) = Instant.ofEpochMilli(ts).truncatedTo(ChronoUnit.SECONDS).toString()

    /** One entry as Prometheus sees it, or null when the Sanitizer takes the whole line. Stacks keep only frames. */
    fun render(l: ClioLine, clean: Sanitizer): String? {
        val head = clean.line("${stamp(l.ts)} ${l.area} ${l.level.name.lowercase()} ${l.event}") ?: return null
        val body = clean.clean(if (l.area in STACK_AREAS) frames(l.detail) else l.detail)
        return if (body.isEmpty()) head else "$head\n$body"
    }

    /** The crash and freeze entries of the last 48 h (and `pinned`, the one he sent from Clio), newest first, capped. */
    fun attachment(lines: List<ClioLine>, now: Long, clean: Sanitizer, pinned: ClioLine? = null): List<String> {
        val recent = lines.filter { it.area in STACK_AREAS && it.ts >= now - WINDOW_MS && it.ts <= now }.sortedByDescending { it.ts }.take(ENTRIES)
        val all = (listOfNotNull(pinned?.takeIf { it.area in STACK_AREAS }) + recent).distinctBy { it.ts to it.event }
        var left = CHARS
        return all.mapNotNull { render(it, clean) }.takeWhile { left -= it.length; left >= 0 }
    }

    /** The first of our own frames in a stack, for the request's title ("Mind.drive:412"): a stall's top "Ours", else the first dev.phonerecall frame. */
    fun firstOurs(detail: String): String? {
        val f = frames(detail).lines()
        f.firstOrNull { "Ours: " in it && !it.endsWith("Ours: none") }?.let { return it.substringAfter("Ours: ").substringBefore(' ') }
        val at = f.firstOrNull { it.startsWith("at dev.phonerecall.") } ?: return null
        val name = at.removePrefix("at ").substringBefore('(').split('.').takeLast(2).joinToString(".")
        return name + (LINE.find(at)?.let { ":" + it.groupValues[1] } ?: "")
    }

    /** What "Send to Prometheus" on a crash or freeze entry fills in. He still reads it and taps Send. */
    fun prefill(l: ClioLine): String = "Fix: " + l.event + (firstOurs(l.detail)?.let { " at $it" } ?: "")

    val SPEC = ToolSpec(
        TOOL,
        "Read more of Clio, Zeus's on-phone log, for this request. Answered by the owner's phone when it next checks in " +
            "(usually within a minute while he has Prometheus open), sanitized, read-only, newest first. Only while this " +
            "request is open. Stacks come back as code frames only. Visits, drives, birthdays and device usage are never sent.",
        mapOf(
            "type" to "object",
            "properties" to mapOf(
                "area" to mapOf("type" to "string", "description" to "One area: mind, heartbeat, senses, fates, alarms, music, argo, praxis, lemnos, netgate, widgets, workers, alexandria, audit, ui, crash, setup, prometheus."),
                "level" to mapOf("type" to "string", "enum" to listOf("info", "warn", "error"), "description" to "The lowest level to return (warn returns warn and error)."),
                "from" to mapOf("type" to "string", "description" to "ISO-8601 instant or date (UTC). Default: 48 hours ago. At most 14 days back."),
                "to" to mapOf("type" to "string", "description" to "ISO-8601 instant or date (UTC). Default: now."),
                "query" to mapOf("type" to "string", "description" to "Text to look for in the event or detail, case-insensitive."),
                "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to LIMIT, "description" to "At most $LIMIT lines. Default 100."),
            ),
            "required" to emptyList<String>(),
            "additionalProperties" to false,
        ),
    )

    /** Open requests may read; once merged, closed, stopped or failed the answer is a refusal. */
    fun open(c: CloudChange) = c.stage in setOf(CloudStage.STARTING, CloudStage.WORKING, CloudStage.PR_OPEN)

    private fun time(v: Any?, endOfDay: Boolean): Long? {
        val s = v?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { return Instant.parse(s).toEpochMilli() }
        return runCatching { LocalDate.parse(s).let { if (endOfDay) it.plusDays(1) else it }.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - if (endOfDay) 1 else 0 }
            .getOrElse { throw IllegalArgumentException("'$s' isn't an ISO-8601 instant or date") }
    }

    data class Query(val area: String?, val level: Level?, val from: Long, val to: Long, val query: String?, val limit: Int)

    fun query(input: Map<String, Any?>, now: Long): Query {
        val level = input["level"]?.toString()?.takeIf { it.isNotBlank() }?.let { w -> Level.entries.firstOrNull { it.name.equals(w, true) } ?: throw IllegalArgumentException("level is info, warn or error") }
        val from = (time(input["from"], false) ?: (now - WINDOW_MS)).coerceAtLeast(now - KEEP_MS)
        val to = (time(input["to"], true) ?: now).coerceAtMost(now)
        val limit = (input["limit"] as? Number)?.toInt() ?: input["limit"]?.toString()?.toIntOrNull() ?: 100
        return Query(input["area"]?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }, level, from, to, input["query"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }, limit.coerceIn(1, LIMIT))
    }

    data class Answer(val text: String, val error: Boolean, val lines: Int = 0)

    /** One read_clio call. `read(from)` gives Clio's lines at or after `from`, newest first. */
    fun answer(c: CloudChange, input: Map<String, Any?>, now: Long, clean: Sanitizer, read: (Long) -> List<ClioLine>): Answer {
        if (!open(c)) return Answer("Refused: this request is ${c.stage.word.lowercase()}; Clio is read only while a request is open.", true)
        val q = runCatching { query(input, now) }.getOrElse { return Answer("Bad input: ${it.message}", true) }
        if (q.area != null && q.area in NEVER) return Answer("Refused: ${q.area} stays on the phone.", true)
        val picked = read(q.from).asSequence()
            .filter { it.ts in q.from..q.to && it.area !in NEVER }
            .filter { q.area == null || it.area == q.area }
            .filter { q.level == null || it.level >= q.level }
            // Render (sanitize) first, then match: a query run against the raw text would tell Prometheus whether a
            // scrubbed word was there, by which lines come back.
            .mapNotNull { render(it, clean) }
            .filter { q.query == null || it.contains(q.query, true) }
            .take(q.limit).toList()
        val out = StringBuilder()
        var shown = 0
        for (p in picked) { if (out.length + p.length + 1 > CHARS) break; out.append(p).append('\n'); shown++ }
        if (shown < picked.size) out.append("(${picked.size - shown} more not shown: the answer is capped at $CHARS characters; narrow the filter)\n")
        return Answer(out.toString().trimEnd().ifEmpty { "No Clio lines match." }, false, shown)
    }

    /** Prometheus's tool calls the phone hasn't answered yet, oldest first. */
    fun pending(events: List<MindEvent>): List<MindEvent.CustomToolUse> {
        val answered = events.filterIsInstance<MindEvent.ToolResultSent>().map { it.toolUseId }.toSet()
        return events.filterIsInstance<MindEvent.CustomToolUse>().filter { it.id !in answered }
    }
}

/** "Send to Prometheus" on a Clio entry: the entry waits here until Prometheus's request sheet takes it. */
object PrometheusDraft {
    val stack = kotlinx.coroutines.flow.MutableStateFlow<ClioLine?>(null)

    fun send(l: ClioLine) { if (l.area in ClioEvidence.STACK_AREAS) stack.value = l }

    fun take(): ClioLine? = stack.getAndUpdate { null }

    // A request Athena drafted from an idea he said Yes to: waits in the Prometheus field until he sends or clears it.
    private fun sp(c: android.content.Context) = c.getSharedPreferences("prometheus.draft", android.content.Context.MODE_PRIVATE)
    fun set(c: android.content.Context, text: String) { sp(c).edit().putString("text", text.take(2000)).apply() }
    fun peek(c: android.content.Context): String? = sp(c).getString("text", null)
    fun clear(c: android.content.Context) { sp(c).edit().remove("text").apply() }
}
