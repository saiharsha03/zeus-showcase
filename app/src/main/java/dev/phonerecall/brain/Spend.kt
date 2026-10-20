package dev.phonerecall.brain

import java.time.YearMonth
import java.time.ZoneId

/**
 * The models Athena can think with, and their prices in dollars per million tokens (claude-api skill,
 * model table cached 2026-10-06). Cache reads are their own rate; a 5-minute cache write is 1.25x input.
 * Haiku 5.5 has a second rate card for prompts over 100K tokens.
 */
enum class BrainModel(
    val id: String,
    val label: String,
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val longInput: Double = input,
    val longOutput: Double = output,
) {
    HAIKU("claude-haiku-5-5", "Haiku 5.5", 0.10, 0.50, 0.01, longInput = 0.50, longOutput = 2.50),
    SONNET("claude-sonnet-5-5", "Sonnet 5.5", 2.00, 10.00, 0.20),
    OPUS("claude-opus-5-5", "Opus 5.5", 4.00, 20.00, 0.20);

    /** Sonnet and Opus 5.5 take the server-side refusal fallback ("default" form, Claude API only). */
    val serverFallback: Boolean get() = this != HAIKU

    companion object {
        val DEFAULT = SONNET
        fun of(id: String?): BrainModel? = entries.firstOrNull { it.id == id }
    }
}

/** The token counts from one response's `usage`. */
data class TokenUse(val input: Long, val output: Long, val cacheRead: Long = 0, val cacheWrite: Long = 0)

object Pricing {
    private const val LONG_PROMPT = 100_000L

    /** Cost of one response in micro-dollars (1 dollar = 1,000,000), rounded up. */
    fun costMicros(model: BrainModel, u: TokenUse): Long {
        val prompt = u.input + u.cacheRead + u.cacheWrite
        val long = prompt > LONG_PROMPT
        val inRate = if (long) model.longInput else model.input
        val outRate = if (long) model.longOutput else model.output
        val readRate = if (long && model == BrainModel.HAIKU) inRate * 0.1 else model.cacheRead
        // Rates are dollars per 1M tokens, so tokens x rate is already micro-dollars.
        val micros = u.input * inRate + u.output * outRate + u.cacheRead * readRate + u.cacheWrite * inRate * 1.25
        return kotlin.math.ceil(micros).toLong()
    }
}

/**
 * This month's spend for all of Athena: Delphi and the phone's Haiku calls (Messages API), plus the
 * Managed Agents mind (`mindMicros`, from session usage.list_cost, which includes running time) and its
 * active seconds. One cap covers the total. A new month starts at zero.
 */
data class SpendState(
    val month: String,
    val spentMicros: Long,
    val capUsd: Int = DEFAULT_CAP_USD,
    val mindMicros: Long = 0,
    val activeSeconds: Long = 0,
    /** Delphi's Metis consultations (advisor tokens at Fable prices), part of spentMicros. */
    val advisorMicros: Long = 0,
) {
    val spentUsd: Double get() = spentMicros / 1_000_000.0
    val messagesMicros: Long get() = spentMicros - mindMicros

    fun current(now: YearMonth): SpendState = if (now.toString() == month) this else copy(month = now.toString(), spentMicros = 0, mindMicros = 0, activeSeconds = 0, advisorMicros = 0)

    /** Calls are allowed only while this month's spend is under the cap. */
    fun allows(now: YearMonth): Boolean = current(now).spentMicros < capUsd * 1_000_000L

    fun plus(micros: Long, now: YearMonth): SpendState = current(now).let { it.copy(spentMicros = it.spentMicros + micros) }

    fun plusMind(micros: Long, seconds: Long, now: YearMonth): SpendState =
        current(now).let { it.copy(spentMicros = it.spentMicros + micros, mindMicros = it.mindMicros + micros, activeSeconds = it.activeSeconds + seconds) }

    fun plusAdvisor(micros: Long, now: YearMonth): SpendState = current(now).let { it.copy(spentMicros = it.spentMicros + micros, advisorMicros = it.advisorMicros + micros) }

    companion object {
        /** Phase 2c: $100 a month for the whole of Athena. */
        const val DEFAULT_CAP_USD = 100
        fun thisMonth(zone: ZoneId = ZoneId.systemDefault()): YearMonth = YearMonth.now(zone)
    }
}

class SpendCapReached(val capUsd: Int) : RuntimeException("Monthly cap of $$capUsd reached")
