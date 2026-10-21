package dev.phonerecall.setup

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class Verdict { PASS, FAIL, SKIP }

/** Who does a check: the phone itself, or Athena through her tools (the phone still judges what it can see). */
enum class By { PHONE, ATHENA }

/**
 * One end-to-end check. [telete] is the Telete check whose fix the report's Fix link runs; [dest] a
 * screen to open instead ([dev.phonerecall.Dest] name). [notify] marks the ones he answers yes or no;
 * [manual] ones run only when he taps them (the mic test), never in the full run.
 */
data class ShakeCheck(
    val id: String, val feature: String, val label: String, val by: By = By.PHONE,
    val telete: String? = null, val dest: String? = null, val notify: Boolean = false, val manual: Boolean = false,
)

/** What a check came to. [claim] is what Athena reported for it, kept beside the phone's verdict. */
data class ShakeResult(val id: String = "", val verdict: Verdict = Verdict.SKIP, val detail: String = "", val ms: Long = 0, val claim: String? = null)

/** One finished run, as it is kept and exported. [seen] holds his answers to "did it appear?", null until he taps. */
data class ShakeReport(
    val id: String = "",
    val at: Long = 0,
    val ms: Long = 0,
    val results: List<ShakeResult> = emptyList(),
    val seen: Map<String, Boolean?> = emptyMap(),
    val summary: String = "",
    val spentCents: Long = 0,
    val aborted: String? = null,
    val cleanup: List<String> = emptyList(),
)

/** Athena's report_check, validated. */
data class ShakeClaim(val id: String, val verdict: Verdict, val detail: String)

/** A notification the shakedown posts: which check, which of Zeus's channels, as that channel is made. */
data class TestChannel(val check: String, val channel: String, val name: String, val importance: Int, val title: String)

/** The check was skipped for this reason (shown as is). */
class TestSkipped(reason: String) : Exception(reason)

/** One step of a run: [estimateCents] is what it may spend, so a step that would pass the cap is skipped first. */
class ShakeStep(val id: String, val timeoutMs: Long = 30_000L, val estimateCents: Long = 0, val run: suspend () -> String)

data class ShakeOutcome(val results: List<ShakeResult>, val aborted: String?, val cleanup: List<String>, val spentCents: Long, val ms: Long)

/**
 * The shakedown: one button in Telete, then every feature end to end. Pure: the checks, the prompt Athena
 * gets, report_check's rules, the runner, the summary and the comparison with the last run.
 */
object Shakedown {
    /** The whole run's spend cap, cents, and its wall-clock limit. */
    const val CAP_CENTS = 50L
    const val DEADLINE_MS = 5 * 60_000L
    /** Test notifications cancel themselves after this. */
    const val NOTIFY_MS = 60_000L
    /** Every test artifact carries this. */
    const val TAG = "Zeus test"
    const val CARD_TITLE = "Zeus test card"
    /** AskHub owner for Athena's asks while a run is on: answers come to the test, never back into the session. */
    const val ASK_OWNER = "shakedown"
    /** How long Iris and Agora get to answer. */
    const val IRIS_MS = 85_000L
    const val AGORA_MS = 90_000L

    private fun a(id: String, feature: String, label: String, telete: String? = null, dest: String? = null) = ShakeCheck(id, feature, label, By.ATHENA, telete, dest)
    private fun p(id: String, feature: String, label: String, telete: String? = null, dest: String? = null) = ShakeCheck(id, feature, label, By.PHONE, telete, dest)

    /** Athena's read tools and the check each one is. */
    val READ_TOOLS = linkedMapOf(
        "now" to "tool-now", "read_calendar" to "tool-calendar", "read_tasks" to "tool-tasks", "read_availability" to "tool-availability",
        "read_trips" to "tool-trips", "read_cards" to "tool-cards", "read_people_labels" to "tool-people", "read_sections" to "tool-sections",
        "search_memory" to "tool-memory", "read_usage" to "tool-usage", "read_schedules" to "tool-schedules", "read_zeus" to "tool-zeus",
    )

    val CHECKS: List<ShakeCheck> = listOf(
        p("high-note", "Athena", "An urgent note goes at once", telete = "mind"),
        a("mind-turn", "Athena", "She takes the turn and finishes it", telete = "mind"),
        a("tool-now", "Athena", "now"),
        a("tool-calendar", "Chronos", "read_calendar", telete = "calendar"),
        a("tool-tasks", "Praxis", "read_tasks"),
        a("tool-availability", "Chronos", "read_availability", telete = "calendar"),
        a("tool-trips", "Argo", "read_trips", dest = "TRIPS"),
        a("tool-cards", "Olympus", "read_cards sees the test card"),
        a("tool-people", "Harmonia", "read_people_labels", telete = "contacts"),
        a("tool-sections", "Lists", "read_sections"),
        a("tool-memory", "Alexandria", "search_memory", telete = "alexandria"),
        a("tool-usage", "Metron", "read_usage", telete = "lemnos"),
        a("tool-schedules", "Horologion", "read_schedules", telete = "mind"),
        a("tool-zeus", "Athena", "read_zeus: the index and one area"),
        p("card", "Olympus", "A test card is placed and removed", dest = "HOME"),
        p("proposal", "Fates", "A test proposal is confirmed, then undone", dest = "FATES"),
        p("heartbeat", "Athena", "One heartbeat turn", telete = "heartbeat"),
        p("scheduled", "Horologion", "Scheduled runs: the phone answered their tool calls", telete = "mind"),
        a("ask-iris", "Iris", "ask_worker reaches Iris (read-only)", telete = "iris"),
        p("iris-reply", "Iris", "Iris answers within 85 s", telete = "iris"),
        a("agora-post", "Agora", "post_agora lands in Agora", telete = "agora"),
        p("agora-answer", "Agora", "Iris or Atlas answers in Agora", telete = "agora"),
        p("group", "Agora", "Group chat relay reaches Athena (nothing posted)", telete = "agora"),
        p("delphi", "Athena", "One chat message reaches her session and she answers", telete = "key-anthropic"),
        p("live", "Voice", "Gemini Live answers a typed turn", telete = "key-gemini"),
        ShakeCheck("live-mic", "Voice", "Mic test: say something within 10 s", telete = "mic", manual = true),
        p("hermes", "Hermes", "Commands parse, nothing set"),
        p("praxis", "Praxis", "A test task is added and undone", dest = "TASKS"),
        p("chronos-read", "Chronos", "Reads the next week", telete = "calendar"),
        p("chronos-event", "Chronos", "A test event is made and deleted in your calendar", telete = "calendar"),
        p("google-birthdays", "Chronos", "Google Calendar birthdays: allowed (nothing written)", telete = "google-birthdays"),
        p("argo", "Argo", "Reads trips and their evidence", dest = "TRIPS"),
        p("moneta", "Moneta", "Parses and folds two sample alerts in a sandbox", telete = "listener"),
        p("odyssey-fix", "Odyssey", "One location fix", telete = "location"),
        p("odyssey-drive", "Odyssey", "Drive report service, dry run", telete = "activity"),
        p("weather", "Aeolia", "Forecast for home", telete = "location"),
        p("tomtom", "Odyssey", "TomTom times home to office", telete = "key-tomtom"),
        p("spotify", "Spotify", "Now playing", telete = "spotify"),
        p("hestia", "Hestia", "Google Home devices", telete = "hestia"),
        p("iaso", "Iaso", "Health Connect reads this week's steps", telete = "health"),
        p("alexandria", "Alexandria", "Searches and embeds", telete = "alexandria"),
        p("oneiroi", "Oneiroi", "Reads the live facts", dest = "MEMORY"),
        p("clio", "Clio", "Writes, reads back and exports the log"),
        p("mnemosyne", "Mnemosyne", "Backup dry run (nothing uploaded) and the last run", telete = "backup"),
        p("lemnos", "Lemnos", "Gateway status and a read-only terminal ticket", telete = "lemnos"),
        p("cerberus", "Cerberus", "Reads the server's alerts", telete = "lemnos", dest = "ALERTS"),
        p("metron", "Metron", "Reads plan usage", telete = "lemnos", dest = "USAGE"),
        p("widgets", "Widgets", "Counts placed widgets and refreshes them"),
    ) + TestNotify.CHANNELS.map { ShakeCheck(it.check, "Notifications", it.title, telete = "notify", notify = true) }

    val ATHENA_IDS: Set<String> get() = CHECKS.filter { it.by == By.ATHENA }.map { it.id }.toSet()

    fun check(id: String): ShakeCheck? = CHECKS.firstOrNull { it.id == id }

    /**
     * What Athena gets, as one urgent note. Plain words only: the sanitizer must pass it unchanged (no money,
     * immigration or medical words), so the test reads the same on the phone and in her session.
     */
    fun prompt(runId: String): String = """
[Zeus] SHAKEDOWN $runId. The owner started a full test run of Zeus from Telete. Silence is not fine for this one: do every step below now, then stop.
This is a test. Change nothing, propose nothing, message no one outside Agora and Iris. Writes are switched off on the phone until it ends.
1. Call each of these once, with small inputs: ${READ_TOOLS.keys.joinToString(", ")} (search_memory with query "test", k 3; read_zeus twice, area index and then area weather). read_cards should show a card titled "$CARD_TITLE".
2. ask_worker: worker iris, topic shakedown, question "Zeus shakedown: please answer this test question. What day is it where you are? One line."
3. post_agora, room agora: "Zeus shakedown: please answer this test question. Iris or Atlas, reply with one word."
4. After each step call report_check with its id, pass, fail or skip, and one short line. Ids: ${READ_TOOLS.values.joinToString(", ")}, ask-iris, agora-post, mind-turn (last, once you are done).
Keep any reply to one line. Write no memory files.
""".trim()

    /** report_check's rules: only while a run is on, only Athena's ids, pass, fail or skip, one short line. */
    fun validate(input: Map<String, Any?>, running: Boolean): Result<ShakeClaim> = runCatching {
        require(running) { "No shakedown is running." }
        val id = input["id"]?.toString()?.trim().orEmpty()
        require(id in ATHENA_IDS) { "Unknown check id \"$id\". Report one of: ${ATHENA_IDS.sorted().joinToString(", ")}." }
        val verdict = when (input["status"]?.toString()?.trim()?.lowercase()) { "pass" -> Verdict.PASS; "fail" -> Verdict.FAIL; "skip" -> Verdict.SKIP; else -> null }
            ?: throw IllegalArgumentException("status must be pass, fail or skip")
        val detail = input["detail"]?.toString().orEmpty().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(200)
        ShakeClaim(id, verdict, detail)
    }

    /** The phone's verdict wins wherever it has one; her claim is kept beside it. A check with only her word says so. */
    fun merge(phone: List<ShakeResult>, claims: Map<String, ShakeClaim>): List<ShakeResult> {
        val byId = phone.associateBy { it.id }
        return CHECKS.mapNotNull { c ->
            val claim = claims[c.id]?.let { "${it.verdict.name.lowercase()}" + if (it.detail.isNotEmpty()) ": ${it.detail}" else "" }
            val r = byId[c.id]
            when {
                r != null -> r.copy(claim = claim ?: r.claim)
                claims[c.id] != null -> ShakeResult(c.id, claims.getValue(c.id).verdict, "Her word only: " + claims.getValue(c.id).detail, claim = claim)
                else -> null
            }
        }
    }

    /** "31 of 38 passed; Spotify: not connected; …". The fallback when Athena can't write it. */
    fun summary(results: List<ShakeResult>, aborted: String? = null): String {
        val pass = results.count { it.verdict == Verdict.PASS }
        val fails = results.filter { it.verdict == Verdict.FAIL }
        val skips = results.count { it.verdict == Verdict.SKIP }
        val parts = buildList {
            add("$pass of ${results.size} passed")
            fails.take(3).forEach { r -> add("${check(r.id)?.feature ?: r.id}: ${r.detail.trimEnd('.')}") }
            if (fails.size > 3) add("${fails.size - 3} more failed")
            if (skips > 0) add("$skips skipped")
            aborted?.let { add(it) }
        }
        return parts.joinToString("; ") + "."
    }

    /** What changed since the last run, per check: fixed or broke. */
    enum class Change { FIXED, BROKE }

    fun compare(now: ShakeReport, before: ShakeReport?): Map<String, Change> {
        val old = before?.results?.associateBy { it.id } ?: return emptyMap()
        return now.results.mapNotNull { r ->
            val was = old[r.id]?.verdict ?: return@mapNotNull null
            when {
                was == Verdict.FAIL && r.verdict == Verdict.PASS -> r.id to Change.FIXED
                was == Verdict.PASS && r.verdict == Verdict.FAIL -> r.id to Change.BROKE
                else -> null
            }
        }.toMap()
    }

    /** The scorecard as Athena's summary prompt reads it: ids, verdicts and the phone's reasons only. */
    fun scorecard(results: List<ShakeResult>): String = results.joinToString("\n") { r ->
        "- ${check(r.id)?.feature ?: r.id} / ${check(r.id)?.label ?: r.id}: ${r.verdict.name.lowercase()}" + if (r.detail.isNotEmpty()) " (${r.detail.take(100)})" else ""
    }

    const val SUMMARY_SYSTEM = "You are Athena, the owner's assistant in Zeus. Write the shakedown's summary: one or two plain sentences, at most 220 characters. Start with \"N of M passed\", then the few things that matter most and why, in his words, no ids. No lists, no emojis."
}

/** The shakedown's test notifications, one per kind of channel Zeus posts on. Importance as NotificationManager's values (3 default, 4 high). */
object TestNotify {
    val CHANNELS = listOf(
        TestChannel("notify-normal", "lemnos.normal", "Pantheon and Lemnos", 3, "A normal notification"),
        TestChannel("notify-high", "moneta.fraud", "Moneta: possible fraud", 4, "A high, fraud-grade one"),
        TestChannel("notify-birthday", "birthdays", "Birthdays", 4, "A birthday one"),
        TestChannel("notify-nowbar", "nowbar", "Now Bar", 3, "A Now Bar live update"),
    )

    /** A stable id per channel, clear of the ids the app already uses. */
    fun id(c: TestChannel): Int = 74_000 + CHANNELS.indexOf(c)
}

/**
 * Runs the steps in order. Each one is timed out; before each, the clock and the spend are checked, and once
 * either is past its limit every later step is skipped with the reason. [cleanup] always runs, also when a
 * step throws, the run is cancelled, or [onResult] fails; it returns the cleanups that didn't work.
 */
object ShakeRunner {
    suspend fun run(
        steps: List<ShakeStep>,
        spentCents: () -> Long,
        cleanup: suspend () -> List<String>,
        clock: () -> Long = System::currentTimeMillis,
        deadlineMs: Long = Shakedown.DEADLINE_MS,
        capCents: Long = Shakedown.CAP_CENTS,
        onResult: (ShakeResult) -> Unit = {},
    ): ShakeOutcome {
        val start = clock()
        val base = spentCents()
        val out = ArrayList<ShakeResult>()
        var aborted: String? = null
        var problems: List<String> = emptyList()
        try {
            for (s in steps) {
                val used = spentCents() - base
                val left = deadlineMs - (clock() - start)
                if (aborted == null && left <= 0) aborted = "Out of time after ${deadlineMs / 60_000} minutes"
                if (aborted == null && used >= capCents) aborted = "Stopped at the ${cents(capCents)} cap"
                val r = when {
                    aborted != null -> ShakeResult(s.id, Verdict.SKIP, aborted)
                    used + s.estimateCents > capCents -> ShakeResult(s.id, Verdict.SKIP, "Skipped: it would pass the ${cents(capCents)} cap")
                    else -> one(s, minOf(s.timeoutMs, left), clock)
                }
                out += r
                onResult(r)
            }
        } finally {
            problems = withContext(NonCancellable) { runCatching { cleanup() }.getOrElse { listOf("Cleanup failed: ${it.message ?: it.javaClass.simpleName}") } }
        }
        return ShakeOutcome(out, aborted, problems, (spentCents() - base).coerceAtLeast(0), clock() - start)
    }

    private suspend fun one(s: ShakeStep, timeoutMs: Long, clock: () -> Long): ShakeResult {
        val start = clock()
        return try {
            ShakeResult(s.id, Verdict.PASS, withTimeout(timeoutMs) { s.run() }, clock() - start)
        } catch (e: TimeoutCancellationException) {
            ShakeResult(s.id, Verdict.FAIL, "No answer in ${timeoutMs / 1000} s", clock() - start)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: TestSkipped) {
            ShakeResult(s.id, Verdict.SKIP, e.message.orEmpty(), clock() - start)
        } catch (e: TestFailed) {
            ShakeResult(s.id, Verdict.FAIL, e.message.orEmpty(), clock() - start)
        } catch (e: Throwable) {
            ShakeResult(s.id, Verdict.FAIL, e.message?.take(160) ?: e.javaClass.simpleName, clock() - start)
        }
    }

    fun cents(c: Long): String = "$" + "%.2f".format(c / 100.0)
}
