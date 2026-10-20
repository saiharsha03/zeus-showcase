package dev.phonerecall.rhythms

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.ceil
import kotlin.math.roundToInt

/** When a person is usually up, in their zone: from [fromMin] to [toMin]; asleep the rest of the day. */
data class AwakeWindow(val fromMin: Int, val toMin: Int)

/**
 * The awake window, from the times their WhatsApp notifications arrived (a message or a call from them
 * means they were up). Times only. Half-hour bins; the longest quiet stretch of four hours or more is sleep.
 */
object Awake {
    private const val BIN = 30
    const val MIN_DAYS = 7
    const val MIN_SAMPLES = 20

    fun learn(times: List<Long>, zone: ZoneId, now: Long): AwakeWindow? {
        val recent = times.filter { it in (now - Habits.WINDOW_DAYS * Habits.DAY)..now }.map { Instant.ofEpochMilli(it).atZone(zone) }
        val days = recent.map { it.toLocalDate() }.toSet().size
        if (days < MIN_DAYS || recent.size < MIN_SAMPLES) return null
        val bins = 1440 / BIN
        val perBin = IntArray(bins)
        for ((bin, list) in recent.groupBy { (it.hour * 60 + it.minute) / BIN }) perBin[bin] = list.map { it.toLocalDate() }.toSet().size
        val threshold = maxOf(2, ceil(days * 0.1).toInt())
        val quiet = BooleanArray(bins) { perBin[it] < threshold }
        if (quiet.all { it } || quiet.none { it }) return null
        // The longest circular run of quiet bins.
        var bestStart = -1; var bestLen = 0
        for (s in 0 until bins) {
            if (!quiet[s] || quiet[(s - 1 + bins) % bins]) continue
            var n = 0
            while (n < bins && quiet[(s + n) % bins]) n++
            if (n > bestLen) { bestLen = n; bestStart = s }
        }
        if (bestLen * BIN < 4 * 60) return null
        return AwakeWindow(((bestStart + bestLen) % bins) * BIN, bestStart * BIN)
    }

    fun asleep(w: AwakeWindow, at: ZonedDateTime): Boolean {
        val m = at.hour * 60 + at.minute
        return if (w.fromMin <= w.toMin) m !in w.fromMin until w.toMin else m in w.toMin until w.fromMin
    }

    /** "usually up 6:30 AM–10:30 PM IST". */
    fun line(w: AwakeWindow, abbr: String): String = "usually up ${Habits.range(w.fromMin, w.toMin)} $abbr"
}

/** Weather extremes where home is, from Open-Meteo's daily forecast. Only these ever become a card. */
object HomeWeather {
    /** IMD's heatwave line for the plains. */
    const val HEAT_C = 42.0
    /** IMD's "heavy rain": 64.5 mm or more in a day. */
    const val HEAVY_RAIN_MM = 64.5

    fun url(lat: Double, lon: Double) =
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&daily=temperature_2m_max,precipitation_sum&timezone=auto&forecast_days=3"

    fun extremes(json: String): List<HomeAlert> {
        val daily = runCatching { dev.phonerecall.mind.stateJson.readTree(json).get("daily") }.getOrNull() ?: return emptyList()
        val dates = daily.get("time") ?: return emptyList()
        val out = ArrayList<HomeAlert>()
        for (i in 0 until dates.size()) {
            val d = dates.get(i).asText()
            daily.get("temperature_2m_max")?.get(i)?.takeIf { it.isNumber }?.asDouble()?.let { if (it >= HEAT_C) out += HomeAlert(d, "heat", it) }
            daily.get("precipitation_sum")?.get(i)?.takeIf { it.isNumber }?.asDouble()?.let { if (it >= HEAVY_RAIN_MM) out += HomeAlert(d, "rain", it) }
        }
        return out
    }

    /** The card's words: "Check on home" and "44°C in Pune today, a heatwave." */
    fun words(a: HomeAlert, city: String, today: LocalDate): Pair<String, String> {
        val day = runCatching { LocalDate.parse(a.date) }.getOrNull()
        val whenText = when (day) { today -> "today"; today.plusDays(1) -> "tomorrow"; null -> a.date; else -> day.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase) }
        val body = if (a.kind == "heat") "${a.value.roundToInt()}°C in $city $whenText, a heatwave." else "Heavy rain in $city $whenText, ${a.value.roundToInt()} mm."
        return "Check on home" to body
    }
}

/** Things to tell a person, matched to the person he calls. */
object Bookmarks {
    /** The key for [person]: a learned call habit or call whose name matches, else the name's own key. */
    fun keyFor(d: RhythmsData, person: String): Pair<String, String> {
        val names = d.callHabits.map { it.key to it.name } + d.calls.map { it.key to it.name }
        val best = names.distinct().maxByOrNull { dev.phonerecall.calls.CallRouting.matchScore(person, it.second) }
        return if (best != null && dev.phonerecall.calls.CallRouting.matchScore(person, best.second) >= 0.92f) best else dev.phonerecall.brain.People.keyOf(person) to person.trim()
    }

    fun open(d: RhythmsData, key: String) = d.bookmarks.filter { it.key == key && !it.done }

    fun add(d: RhythmsData, person: String, note: String, now: Long): Pair<RhythmsData, Long> {
        val (key, name) = keyFor(d, person)
        val b = Bookmark(d.nextId, key, name, note.trim().take(200), now)
        return d.copy(bookmarks = d.bookmarks + b, nextId = d.nextId + 1) to b.id
    }
}
