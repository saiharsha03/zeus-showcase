package dev.phonerecall.voice

import android.content.Context
import java.time.LocalDate
import java.time.YearMonth

/**
 * Live's daily minutes and this month's spend. The cap is minutes of open session per day (Themis,
 * default 30); a session stops when it reaches it. Spend is estimated at the paid rates from
 * ai.google.dev/gemini-api/docs/pricing (10-19): $0.005 a minute of audio in, $0.018 a minute out.
 * On the free tier it costs nothing, but Google may use those conversations.
 */
data class LiveLedger(
    val day: String,
    val secondsToday: Long = 0,
    val confirmedDay: String? = null,
    val month: String = YearMonth.parse(day.take(7)).toString(),
    val inSeconds: Long = 0,
    val outSeconds: Long = 0,
    val capMinutes: Int = DEFAULT_CAP_MINUTES,
) {
    /** A new day zeroes today's minutes and the confirm; a new month zeroes the spend. */
    fun on(today: LocalDate): LiveLedger {
        var l = this
        if (today.toString() != day) l = l.copy(day = today.toString(), secondsToday = 0)
        if (YearMonth.from(today).toString() != month) l = l.copy(month = YearMonth.from(today).toString(), inSeconds = 0, outSeconds = 0)
        return l
    }

    fun leftSeconds(today: LocalDate): Long = (capMinutes * 60L - on(today).secondsToday).coerceAtLeast(0)
    fun confirmedToday(today: LocalDate): Boolean = confirmedDay == today.toString()
    fun confirm(today: LocalDate): LiveLedger = on(today).copy(confirmedDay = today.toString())

    /** One session's open seconds (audio in streams the whole time) and seconds of audio Gemini spoke. */
    fun plus(open: Long, spoke: Long, today: LocalDate): LiveLedger = on(today).let {
        it.copy(secondsToday = it.secondsToday + open, inSeconds = it.inSeconds + open, outSeconds = it.outSeconds + spoke)
    }

    /** This month at the paid rates, in micro-dollars. */
    val spendMicros: Long get() = (inSeconds * IN_MICROS_PER_MIN + outSeconds * OUT_MICROS_PER_MIN + 59) / 60

    companion object {
        const val DEFAULT_CAP_MINUTES = 30
        val CAPS = listOf(15, 30, 60)
        const val IN_MICROS_PER_MIN = 5_000L
        const val OUT_MICROS_PER_MIN = 18_000L
    }
}

/** Live's ledger in app-private prefs. */
class LiveLedgerStore(context: Context) {
    private val sp = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

    @Synchronized fun load(today: LocalDate = LocalDate.now()): LiveLedger = LiveLedger(
        day = sp.getString("day", today.toString())!!, secondsToday = sp.getLong("today", 0), confirmedDay = sp.getString("confirmed", null),
        month = sp.getString("month", YearMonth.from(today).toString())!!, inSeconds = sp.getLong("in", 0), outSeconds = sp.getLong("out", 0),
        capMinutes = sp.getInt("cap", LiveLedger.DEFAULT_CAP_MINUTES),
    ).on(today)

    @Synchronized fun save(l: LiveLedger) {
        sp.edit().putString("day", l.day).putLong("today", l.secondsToday).putString("confirmed", l.confirmedDay).putString("month", l.month)
            .putLong("in", l.inSeconds).putLong("out", l.outSeconds).putInt("cap", l.capMinutes).apply()
    }

    @Synchronized fun update(f: (LiveLedger) -> LiveLedger): LiveLedger = f(load()).also { save(it) }
}
