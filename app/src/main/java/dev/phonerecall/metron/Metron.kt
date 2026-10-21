package dev.phonerecall.metron

import dev.phonerecall.brain.SpendState
import dev.phonerecall.mind.Metis
import dev.phonerecall.mind.Protege
import dev.phonerecall.mind.stateJson
import dev.phonerecall.ui.Workers
import dev.phonerecall.voice.LiveLedger
import java.time.Instant
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Metron (Greek "measure"): how much of Iris's and Atlas's plans is used, and what Athena has spent of her
 * monthly cap. The workers' windows come from Lemnos (GET /api/usage); the spend from the phone's own
 * ledgers. Everything here is pure, so the thresholds, the projection and the routing rule are tested.
 */

/** One plan window: "5h", "7d", "7d Opus". [usedPct] is null when it reset since the last reading. [resetsAt] in epoch seconds. */
data class UsageWindow(val label: String = "", val usedPct: Double? = null, val resetsAt: Long? = null) {
    /** "5-hour", "7-day Opus". */
    val words: String get() = label.replace(Regex("^(\\d+)h"), "$1-hour").replace(Regex("^(\\d+)d"), "$1-day")
}

data class AgentUsage(
    val id: String = "",
    val name: String = "",
    val model: String? = null,
    val windows: List<UsageWindow> = emptyList(),
    /** When Lemnos last read it, epoch seconds. */
    val updated: Long? = null,
    val limitReached: Boolean = false,
    val note: String? = null,
) {
    /** The fullest window that has a number. */
    val worst: UsageWindow? get() = windows.filter { it.usedPct != null }.maxByOrNull { it.usedPct!! }
}

/** What Lemnos said, and when the phone asked (ms). */
data class UsageSnapshot(val agents: List<AgentUsage> = emptyList(), val fetchedAt: Long = 0) {
    fun agent(id: String): AgentUsage? = agents.firstOrNull { it.id == id }
}

object UsageParse {
    /** GET /api/usage's body. Unknown or broken fields are dropped, never guessed. */
    fun parse(json: String, fetchedAt: Long): UsageSnapshot {
        val root = stateJson.readTree(json)
        val agents = root.path("agents").mapNotNull { a ->
            val id = a.path("id").asText("").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            AgentUsage(
                id = id,
                name = a.path("name").asText("").ifEmpty { id.replaceFirstChar { it.uppercase() } },
                model = a.path("model").takeIf { it.isTextual }?.asText(),
                windows = a.path("windows").mapNotNull { w ->
                    val label = w.path("label").asText("").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    UsageWindow(label, w.path("used_pct").takeIf { it.isNumber }?.asDouble()?.coerceIn(0.0, 100.0), w.path("resets_at").takeIf { it.isNumber }?.asLong())
                },
                updated = a.path("updated").takeIf { it.isNumber }?.asLong(),
                limitReached = a.path("limit_reached").asBoolean(false),
                note = a.path("note").takeIf { it.isTextual }?.asText(),
            )
        }
        return UsageSnapshot(agents, fetchedAt)
    }
}

/** Colour steps for a bar: plain below 80%, warm from 80%, alert from 95%. */
enum class Level { OK, WARN, HOT;
    companion object {
        const val WARN_PCT = 80.0
        const val HOT_PCT = 95.0
        fun of(pct: Double?): Level = when { pct == null -> OK; pct >= HOT_PCT -> HOT; pct >= WARN_PCT -> WARN; else -> OK }
    }
}

// ---- Athena's spend ----

data class BudgetLine(val label: String, val micros: Long, val note: String? = null)

/**
 * This month's spend against the cap, split by who spent it. Delphi (with the phone's quick Haiku calls),
 * Athena's own background mind, each protégé, and Metis (both paths) add up to the total. Gemini Live is
 * shown beside it: it is outside the cap.
 */
data class Budget(
    val capUsd: Int,
    val spentMicros: Long,
    val lines: List<BudgetLine>,
    val activeSeconds: Long,
    val liveSeconds: Long,
    val liveMicros: Long,
    val projectedMicros: Long,
    val month: String,
) {
    val capMicros: Long get() = capUsd * 1_000_000L
    val pct: Double get() = if (capMicros <= 0) 0.0 else spentMicros * 100.0 / capMicros
    val projectedPct: Double get() = if (capMicros <= 0) 0.0 else projectedMicros * 100.0 / capMicros
}

object MetronBudget {
    /** Spend so far scaled to the whole month. At least a day of the month counts as gone, so a first-morning call doesn't project wildly. */
    fun projectMonthEnd(spentMicros: Long, now: ZonedDateTime): Long {
        val start = now.toLocalDate().withDayOfMonth(1).atStartOfDay(now.zone)
        val end = start.plusMonths(1)
        val total = end.toEpochSecond() - start.toEpochSecond()
        val gone = (now.toEpochSecond() - start.toEpochSecond()).coerceIn(86_400L, total)
        return (spentMicros.toDouble() * total / gone).toLong()
    }

    /** [circle] is the mind's per-agent cents this month (protégé agent names and Metis's thread). */
    fun of(spend: SpendState, circle: Map<String, Long>, live: LiveLedger?, now: ZonedDateTime): Budget {
        val s = spend.current(YearMonth.from(now))
        val metisTotal = maxOf((circle[Metis.THREAD] ?: 0L) * 10_000L, s.advisorMicros)
        val metisMind = (metisTotal - s.advisorMicros).coerceAtLeast(0)
        val proteges = Protege.entries.map { p -> p to (circle[p.agentName] ?: 0L) * 10_000L }
        val athenaMind = (s.mindMicros - metisMind - proteges.sumOf { it.second }).coerceAtLeast(0)
        val lines = listOf(
            BudgetLine("Quick calls", (s.messagesMicros - s.advisorMicros).coerceAtLeast(0), "the phone's own calls: triage, arrivals, reflect"),
            BudgetLine("Athena's mind", athenaMind, "background sessions"),
        ) + proteges.map { (p, m) -> BudgetLine(p.display, m, p.role.lowercase()) } + BudgetLine(Metis.DISPLAY, metisTotal, "advisor")
        val l = live?.on(now.toLocalDate())
        return Budget(
            capUsd = s.capUsd, spentMicros = s.spentMicros, lines = lines, activeSeconds = s.activeSeconds,
            liveSeconds = if (l != null && l.month == YearMonth.from(now).toString()) l.inSeconds else 0,
            liveMicros = if (l != null && l.month == YearMonth.from(now).toString()) l.spendMicros else 0,
            projectedMicros = projectMonthEnd(s.spentMicros, now), month = YearMonth.from(now).toString(),
        )
    }
}

// ---- alerts: once per window ----

data class MetronAlert(val key: String, val title: String, val body: String, val dest: String = "USAGE", val untilMs: Long)

object MetronAlerts {
    /** Budget steps for the monthly cap. Plan windows alert once, at [Level.WARN_PCT]. */
    val BUDGET_STEPS = listOf(80, 95)

    /**
     * The alerts not raised yet. A plan window's key carries its reset time, so the next window can alert
     * again; the budget's carries the month and the step. [fired] holds the keys already raised.
     */
    fun due(usage: UsageSnapshot?, budget: Budget?, fired: Set<String>, now: ZonedDateTime): List<MetronAlert> {
        val ms = now.toInstant().toEpochMilli()
        val out = ArrayList<MetronAlert>()
        for (a in usage?.agents.orEmpty()) for (w in a.windows) {
            val pct = w.usedPct ?: continue
            if (pct < Level.WARN_PCT) continue
            val key = "w:${a.id}:${w.label}:${w.resetsAt ?: 0}"
            if (key in fired) continue
            val reset = w.resetsAt?.let { " It resets ${MetronWords.reset(it, now)}." }.orEmpty()
            out += MetronAlert(key, "${a.name} at ${pct.toInt()}% of the ${w.words} window", "Zeus holds new jobs for ${a.name} above 90%.$reset",
                untilMs = w.resetsAt?.let { it * 1000 } ?: (ms + 6 * 3_600_000L))
        }
        if (budget != null) {
            val step = BUDGET_STEPS.lastOrNull { budget.pct >= it }
            val key = step?.let { "b:${budget.month}:$it" }
            // One alert per step; jumping straight past 95% raises only the 95% one (and marks 80% as said).
            if (key != null && key !in fired) {
                val endOfMonth = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(now.zone).toInstant().toEpochMilli()
                out += MetronAlert(key, "Athena has used ${budget.pct.toInt()}% of this month's cap",
                    "${MetronWords.usd(budget.spentMicros)} of $${budget.capUsd}. At this pace, ${MetronWords.usd(budget.projectedMicros)} by month end.", untilMs = endOfMonth)
            }
        }
        return out
    }

    /** Keys to remember after raising [raised]: a budget step also covers the steps under it. */
    fun remember(fired: Set<String>, raised: List<MetronAlert>): Set<String> {
        val more = raised.flatMap { a ->
            if (!a.key.startsWith("b:")) listOf(a.key) else {
                val (month, step) = a.key.removePrefix("b:").split(':').let { it[0] to it[1].toInt() }
                BUDGET_STEPS.filter { it <= step }.map { "b:$month:$it" }
            }
        }
        // Old windows and months fall away; the newest 80 keys are plenty.
        return (fired + more).toList().takeLast(80).toSet()
    }
}

// ---- routing: who may take a job ----

sealed class WorkerPick {
    data class Go(val worker: String) : WorkerPick()
    /** [instead] takes it because [busy] is above the limit. */
    data class Instead(val worker: String, val busy: String, val why: String) : WorkerPick()
    /** Everyone who could take it is above the limit: hold it until [untilSec] (epoch seconds, when known). */
    data class Wait(val worker: String, val untilSec: Long?, val why: String) : WorkerPick()
}

object UsageRouting {
    /** Don't hand a worker a job above this share of any window. */
    const val LIMIT_PCT = 90.0

    /** The windows that block a worker now, or empty. A worker with no reading is never blocked. */
    fun blocking(a: AgentUsage?, nowSec: Long): List<UsageWindow> =
        a?.windows.orEmpty().filter { w -> (w.usedPct ?: 0.0) >= LIMIT_PCT && (w.resetsAt == null || w.resetsAt > nowSec) }
            .ifEmpty { if (a?.limitReached == true) listOf(UsageWindow("limit", 100.0, null)) else emptyList() }

    /**
     * [want] is the worker the job is for; [alternatives] are others who can do the same job, in order.
     * Under the limit: [want]. Over it: the first alternative under it. Everyone over: wait for [want]'s reset.
     */
    fun pick(want: String, usage: UsageSnapshot?, nowSec: Long, alternatives: List<String> = emptyList()): WorkerPick {
        val hot = blocking(usage?.agent(want), nowSec)
        if (hot.isEmpty()) return WorkerPick.Go(want)
        val name = usage?.agent(want)?.name ?: want
        val why = "$name is at ${hot.maxOf { it.usedPct ?: 0.0 }.toInt()}% of the ${hot.maxBy { it.usedPct ?: 0.0 }.words} window"
        alternatives.firstOrNull { blocking(usage?.agent(it), nowSec).isEmpty() }?.let { return WorkerPick.Instead(it, want, why) }
        val until = hot.mapNotNull { it.resetsAt }.maxOrNull()
        return WorkerPick.Wait(want, until, why)
    }
}

// ---- words: read_usage, cards, Delphi ----

object MetronWords {
    private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY_TIME = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)

    fun usd(micros: Long): String = "$" + String.format(Locale.US, "%.2f", micros / 1_000_000.0)

    /** "at 4:10 PM" today, "Fri 3:00 PM" otherwise. */
    fun reset(epochSec: Long, now: ZonedDateTime): String {
        val t = Instant.ofEpochSecond(epochSec).atZone(now.zone)
        return if (t.toLocalDate() == now.toLocalDate()) "at ${TIME.format(t)}" else DAY_TIME.format(t)
    }

    fun window(w: UsageWindow, now: ZonedDateTime): String =
        "${w.words} window " + (w.usedPct?.let { "${it.toInt()}% used" } ?: "reset since the last reading") + (w.resetsAt?.let { ", resets ${reset(it, now)}" }.orEmpty())

    fun agent(a: AgentUsage?, name: String, now: ZonedDateTime): String = when {
        a == null -> "$name: no reading from Lemnos yet."
        a.windows.isEmpty() -> "$name${a.model?.let { " ($it)" }.orEmpty()}: no plan windows reported yet."
        else -> "$name${a.model?.let { " ($it)" }.orEmpty()}: " + a.windows.joinToString("; ") { window(it, now) } + "."
    }

    /** What read_usage answers: the workers' windows, the cap, and the rule. Plain sentences. */
    fun describe(usage: UsageSnapshot?, budget: Budget?, now: ZonedDateTime): String = buildString {
        if (usage == null) append("The workers' plan usage hasn't been read from Lemnos yet. ")
        else {
            append(agent(usage.agent("iris"), Workers.IRIS, now)).append(' ')
            append(agent(usage.agent("atlas"), Workers.ATLAS, now)).append(' ')
            val age = (now.toInstant().toEpochMilli() - usage.fetchedAt) / 60_000
            if (age >= 30) append("That reading is ${age / 60} h ${age % 60} min old. ")
        }
        if (budget != null) {
            append("Athena's cap: ${budget.pct.toInt()}% of $${budget.capUsd} used this month (${usd(budget.spentMicros)}), on pace for ${budget.projectedPct.toInt()}% by month end. ")
            budget.lines.filter { it.micros > 0 }.sortedByDescending { it.micros }.take(4).takeIf { it.isNotEmpty() }?.let { top ->
                append("Most of it: ").append(top.joinToString(", ") { "${it.label} ${usd(it.micros)}" }).append(". ")
            }
        }
        append("Rule: don't hand a job to a worker above ${UsageRouting.LIMIT_PCT.toInt()}% of any window; give it to another worker who can do it, or wait for the reset.")
    }.trim()

    /** One line for a held job: "Iris is at 93% of the 5-hour window. Held until 4:10 PM." */
    fun held(p: WorkerPick, now: ZonedDateTime): String? = when (p) {
        is WorkerPick.Go -> null
        is WorkerPick.Instead -> "${p.why}, so it goes to ${p.worker}."
        is WorkerPick.Wait -> "${p.why}. Held until " + (p.untilSec?.let { reset(it, now).removePrefix("at ") } ?: "it resets") + "."
    }
}
