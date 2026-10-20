package dev.phonerecall.iaso

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * Iaso's view of his health. Everything here stays on the phone: it is never put on the lock screen, the
 * Now Bar or the watch, or on any widget but Iaso's own (only when he places it; steps, the weight trend
 * and sleep), never sent to Iris or Lemnos, and the brain sees at most the one derived line from
 * [IasoBrain] when he has turned sharing on.
 */
enum class HcStatus {
    /** Health Connect isn't on this phone (or this Android can't run it). */
    NOT_INSTALLED,
    /** Installed, but too old for this app; the Play Store has the update. */
    UPDATE_NEEDED,
    /** Available, but he hasn't allowed any of Iaso's reads yet. */
    NOT_PERMITTED,
    /** Some reads allowed, some not. Iaso shows what it can. */
    PARTIAL,
    READY,
}

/** One local day. Null means "no data", which is different from zero. */
@Immutable
data class DayStat(
    val date: LocalDate,
    val steps: Long? = null,
    val activeKcal: Double? = null,
    val exerciseMin: Long? = null,
    val restingHr: Long? = null,
    val avgHr: Long? = null,
)

@Immutable
data class SleepNight(
    /** The local date he woke up on. */
    val date: LocalDate,
    val start: Long,
    val end: Long,
    /** In bed minus the awake and out-of-bed stages. */
    val asleepMin: Long,
    val deepMin: Long? = null,
    val remMin: Long? = null,
)

@Immutable data class Workout(val name: String, val start: Long, val minutes: Long)

/** A heart-rate sample averaged into one bucket (an hour of today). */
@Immutable data class HrPoint(val at: Long, val bpm: Long)

/** A weight reading in kilograms. `mine` is one Zeus logged. */
@Immutable data class WeightPoint(val at: Long, val kg: Double, val mine: Boolean = false)

enum class MealType(val label: String) { BREAKFAST("Breakfast"), LUNCH("Lunch"), DINNER("Dinner"), SNACK("Snack") }

/** A meal he confirmed. Calories and macros are what he saved, which may differ from Claude's guess. */
@Immutable
data class Meal(
    val id: Long,
    val at: Long,
    val type: MealType,
    val dish: String,
    val portion: String,
    val kcal: Int,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    /** True once Health Connect has the NutritionRecord. */
    val inHc: Boolean = false,
    /** Where the numbers came from: a photo estimate he checked, or typed by hand. */
    val fromPhoto: Boolean = false,
)

/** What Health Connect said, mapped. Seven days back, today last. */
@Immutable
data class HealthReport(
    val days: List<DayStat> = emptyList(),
    val sleep: List<SleepNight> = emptyList(),
    val todayHr: List<HrPoint> = emptyList(),
    val workoutsToday: List<Workout> = emptyList(),
    val weights: List<WeightPoint> = emptyList(),
    val readAt: Long = 0L,
) {
    fun day(d: LocalDate): DayStat? = days.firstOrNull { it.date == d }
    fun lastNight(today: LocalDate): SleepNight? = sleep.lastOrNull { it.date == today || it.date == today.minusDays(1) }
}

/** Daily goals for the rings. Fixed for now; editing them is a later ask. */
object Goals {
    const val STEPS = 8_000L
    const val SLEEP_MIN = 450L
    const val ACTIVE_KCAL = 500.0
    /** A night under this reads as "short". */
    const val SHORT_SLEEP_MIN = 360L
}

/** Everything Iaso's screens draw. */
@Immutable
data class IasoState(
    val status: HcStatus = HcStatus.NOT_PERMITTED,
    val report: HealthReport = HealthReport(),
    val meals: List<Meal> = emptyList(),
    val localWeights: List<WeightPoint> = emptyList(),
    /** Glasses of water today, from the phone or the watch. Kept on the phone only. */
    val water: Map<LocalDate, Int> = emptyMap(),
    /** Off by default. On: [IasoBrain.line] may give Athena one derived line. */
    val shareWithBrain: Boolean = false,
    val loaded: Boolean = false,
    val reading: Boolean = false,
    val error: String? = null,
) {
    /** Health Connect's weights plus his own that aren't there yet, deduplicated, oldest first. */
    val weights: List<WeightPoint> get() = WeightTrend.merge(report.weights, localWeights)

    fun mealsOn(d: LocalDate, zone: java.time.ZoneId): List<Meal> =
        meals.filter { java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == d }.sortedBy { it.at }
}
