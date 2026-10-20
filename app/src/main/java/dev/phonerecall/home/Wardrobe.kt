package dev.phonerecall.home

import dev.phonerecall.data.FESTIVAL_WORDS
import java.time.LocalDate
import java.time.Month

/** A festival day with its outfit. Suggested unprompted on the day. */
data class Festival(val date: LocalDate, val name: String, val outfit: String, val itemId: String? = null)

object Festivals {
    // Dates for 2026. Phase 2 can ask Iris for these instead.
    val all = listOf(
        Festival(LocalDate.of(2026, 10, 20), "Dussehra", "Blue festive kurta"),
        Festival(LocalDate.of(2026, 11, 8), "Diwali", "A kurta: try the maroon one before the day"),
    )
    fun on(d: LocalDate) = all.firstOrNull { it.date == d }

    /** The outfit hint for a festival card: Iris's note if there is one, else a kurta. */
    fun hint(title: String, d: LocalDate): String? {
        on(d)?.let { return it.outfit }
        val t = title.lowercase()
        return if (FESTIVAL_WORDS.split('|').any { t.contains(it) }) "A kurta" else null
    }
}

/**
 * Is it cold enough for a layer? Phase 1 uses the month as a proxy for a Buffalo winter.
 * Venus's outfit rules use Aeolia's forecast instead (closet/Outfits.kt); this stays for card timing.
 */
fun interface ColdSignal {
    fun level(date: LocalDate): Int // 0 mild, 1 cool (layer), 2 cold (layer and winter boot)
}

val MonthCold = ColdSignal { d ->
    when (d.month) {
        Month.DECEMBER, Month.JANUARY, Month.FEBRUARY, Month.MARCH -> 2
        Month.OCTOBER, Month.NOVEMBER -> 1
        else -> 0
    }
}

/** A sample winter shopping list. No prices, no stores. */
object WinterList {
    val items = listOf(
        "beanie" to "Beanie",
        "gloves" to "Touchscreen gloves",
        "scarf" to "Scarf",
        "thermals" to "Thermal tops",
        "sweaters" to "Two office sweaters or quarter-zips",
        "longsleeve" to "Plain long-sleeve tees",
        "socks" to "Wool socks",
    )
}
