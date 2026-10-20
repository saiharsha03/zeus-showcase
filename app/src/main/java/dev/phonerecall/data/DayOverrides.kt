package dev.phonerecall.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

/**
 * Day types he set himself ("WFH today?" > Yes). They win over the calendar in AvailabilityModel, so
 * Home, the brain's read_availability and the agents all see the same day. Dates only; past days drop.
 */
object DayOverrides {
    private const val FILE = "availability_overrides"
    private val _state = MutableStateFlow<Map<LocalDate, DayType>>(emptyMap())
    val state: StateFlow<Map<LocalDate, DayType>> = _state.asStateFlow()
    @Volatile private var loaded = false

    fun load(c: Context): Map<LocalDate, DayType> {
        if (loaded) return _state.value
        val today = LocalDate.now()
        val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        _state.value = sp.all.mapNotNull { (k, v) ->
            val d = runCatching { LocalDate.parse(k) }.getOrNull() ?: return@mapNotNull null
            val t = runCatching { DayType.valueOf(v.toString()) }.getOrNull() ?: return@mapNotNull null
            (d to t).takeIf { !d.isBefore(today) }
        }.toMap()
        loaded = true
        return _state.value
    }

    fun set(c: Context, day: LocalDate, type: DayType) {
        val today = LocalDate.now()
        val next = load(c).filterKeys { !it.isBefore(today) } + (day to type)
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply { next.forEach { (d, t) -> putString(d.toString(), t.name) } }.apply()
        _state.value = next
    }
}
