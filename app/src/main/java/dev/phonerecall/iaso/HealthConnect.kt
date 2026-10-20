package dev.phonerecall.iaso

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.reflect.KClass

/**
 * The permissions Iaso asks for, and no others. The Galaxy Watch reaches Health Connect through Samsung
 * Health, so these cover it. Writes are only the weights and meals Zeus logs.
 */
object HcPermissions {
    val READ: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )
    val WRITE_WEIGHT = HealthPermission.getWritePermission(WeightRecord::class)
    val WRITE_NUTRITION = HealthPermission.getWritePermission(NutritionRecord::class)
    val WRITE: Set<String> = setOf(WRITE_WEIGHT, WRITE_NUTRITION)
    val ALL = READ + WRITE

    fun status(granted: Set<String>): HcStatus = when {
        READ.none { it in granted } -> HcStatus.NOT_PERMITTED
        ALL.all { it in granted } -> HcStatus.READY
        else -> HcStatus.PARTIAL
    }
}

/** The slice of Health Connect Iaso uses. The app has [RealHc]; the tests have a fake. */
interface HcClient {
    suspend fun granted(): Set<String>
    suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T>
    suspend fun insert(records: List<Record>)
}

class RealHc(private val hc: HealthConnectClient) : HcClient {
    override suspend fun granted(): Set<String> = hc.permissionController.getGrantedPermissions()

    override suspend fun <T : Record> read(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val out = ArrayList<T>()
        var token: String? = null
        // A week of heart rate from a watch is a few pages; stop at ten so a runaway source can't stall the screen.
        repeat(10) {
            val r = hc.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, to), pageToken = token))
            out += r.records
            token = r.pageToken
            if (token.isNullOrEmpty()) return out
        }
        return out
    }

    override suspend fun insert(records: List<Record>) { hc.insertRecords(records) }

    companion object {
        /** The SDK status as Iaso's words, before any client is made. */
        fun sdk(c: Context): HcStatus? = when (runCatching { HealthConnectClient.getSdkStatus(c) }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)) {
            HealthConnectClient.SDK_AVAILABLE -> null
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcStatus.UPDATE_NEEDED
            else -> HcStatus.NOT_INSTALLED
        }

        fun of(c: Context): RealHc? = if (sdk(c) == null) runCatching { RealHc(HealthConnectClient.getOrCreate(c)) }.getOrNull() else null
    }
}

/**
 * Health Connect records into Iaso's days. Pure; tested with a fake client.
 *
 * Steps and active calories can arrive from more than one app (Samsung Health, the phone, a gym app).
 * Adding them all would double count, so each day takes the single source with the largest total.
 */
object HcMapping {
    private val AWAKE = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED)

    fun localDate(i: Instant, zone: ZoneId): LocalDate = i.atZone(zone).toLocalDate()

    private fun <R> perDayBestSource(records: List<R>, zone: ZoneId, at: (R) -> Instant, origin: (R) -> String, value: (R) -> Double): Map<LocalDate, Double> =
        records.groupBy { localDate(at(it), zone) }.mapValues { (_, rs) -> rs.groupBy(origin).values.maxOf { g -> g.sumOf(value) } }

    fun steps(records: List<StepsRecord>, zone: ZoneId): Map<LocalDate, Long> =
        perDayBestSource(records, zone, { it.startTime }, { it.metadata.dataOrigin.packageName }, { it.count.toDouble() }).mapValues { it.value.toLong() }

    fun activeKcal(records: List<ActiveCaloriesBurnedRecord>, zone: ZoneId): Map<LocalDate, Double> =
        perDayBestSource(records, zone, { it.startTime }, { it.metadata.dataOrigin.packageName }, { it.energy.inKilocalories })

    /** One night per wake-up date: the longest session that ends that day (naps lose to the night). */
    fun sleep(records: List<SleepSessionRecord>, zone: ZoneId): List<SleepNight> =
        records.groupBy { localDate(it.endTime, zone) }.map { (date, rs) ->
            val s = rs.maxBy { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() }
            val inBed = (s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 60_000L
            val awake = s.stages.filter { it.stage in AWAKE }.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L }
            fun stage(t: Int) = s.stages.filter { it.stage == t }.takeIf { it.isNotEmpty() }?.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L }
            SleepNight(date, s.startTime.toEpochMilli(), s.endTime.toEpochMilli(), (inBed - awake).coerceAtLeast(0), stage(SleepSessionRecord.STAGE_TYPE_DEEP), stage(SleepSessionRecord.STAGE_TYPE_REM))
        }.sortedBy { it.date }

    /** The lowest resting reading of each day (watches write several). */
    fun resting(records: List<RestingHeartRateRecord>, zone: ZoneId): Map<LocalDate, Long> =
        records.groupBy { localDate(it.time, zone) }.mapValues { (_, rs) -> rs.minOf { it.beatsPerMinute } }

    fun avgHr(records: List<HeartRateRecord>, zone: ZoneId): Map<LocalDate, Long> =
        records.flatMap { it.samples }.groupBy { localDate(it.time, zone) }.mapValues { (_, s) -> Math.round(s.map { it.beatsPerMinute }.average()) }

    /** Today's samples averaged per hour, for the trend line. */
    fun hourly(records: List<HeartRateRecord>, today: LocalDate, zone: ZoneId): List<HrPoint> =
        records.flatMap { it.samples }.filter { localDate(it.time, zone) == today }
            .groupBy { it.time.atZone(zone).hour }.toSortedMap()
            .map { (h, s) -> HrPoint(today.atTime(h, 30).atZone(zone).toInstant().toEpochMilli(), Math.round(s.map { it.beatsPerMinute }.average())) }

    fun workouts(records: List<ExerciseSessionRecord>, day: LocalDate, zone: ZoneId): List<Workout> =
        records.filter { localDate(it.startTime, zone) == day }.sortedBy { it.startTime }.map {
            Workout(it.title?.takeIf { t -> t.isNotBlank() } ?: exerciseName(it.exerciseType), it.startTime.toEpochMilli(), (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L)
        }

    fun exerciseMinutes(records: List<ExerciseSessionRecord>, zone: ZoneId): Map<LocalDate, Long> =
        records.groupBy { localDate(it.startTime, zone) }.mapValues { (_, rs) -> rs.sumOf { (it.endTime.toEpochMilli() - it.startTime.toEpochMilli()) / 60_000L } }

    fun weights(records: List<WeightRecord>, ownPackage: String): List<WeightPoint> =
        records.map { WeightPoint(it.time.toEpochMilli(), it.weight.inKilograms, mine = it.metadata.dataOrigin.packageName == ownPackage) }.sortedBy { it.at }

    fun exerciseName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walk"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "Run"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Ride"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swim"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hike"
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET -> "Cricket"
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER -> "Football"
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON -> "Badminton"
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL -> "Elliptical"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
        else -> "Workout"
    }

    /** Seven days back through now. A read that isn't permitted is skipped, not an error. */
    suspend fun report(hc: HcClient, granted: Set<String>, now: ZonedDateTime, ownPackage: String): HealthReport {
        val zone = now.zone
        val today = now.toLocalDate()
        val from = today.minusDays(6).atStartOfDay(zone).toInstant()
        // Sleep that ended this morning started yesterday evening.
        val sleepFrom = today.minusDays(7).atTime(12, 0).atZone(zone).toInstant()
        val to = now.toInstant()
        suspend fun <T : Record> r(type: KClass<T>, start: Instant = from): List<T> =
            if (HealthPermission.getReadPermission(type) in granted) runCatching { hc.read(type, start, to) }.getOrDefault(emptyList()) else emptyList()
        val steps = steps(r(StepsRecord::class), zone)
        val kcal = activeKcal(r(ActiveCaloriesBurnedRecord::class), zone)
        val ex = r(ExerciseSessionRecord::class)
        val exMin = exerciseMinutes(ex, zone)
        val hr = r(HeartRateRecord::class)
        val avg = avgHr(hr, zone)
        val rest = resting(r(RestingHeartRateRecord::class), zone)
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { d ->
            DayStat(d, steps[d], kcal[d], exMin[d], rest[d], avg[d])
        }
        return HealthReport(
            days = days,
            sleep = sleep(r(SleepSessionRecord::class, sleepFrom), zone),
            todayHr = hourly(hr, today, zone),
            workoutsToday = workouts(ex, today, zone),
            // Weight moves slowly: ninety days for the trend.
            weights = weights(r(WeightRecord::class, today.minusDays(90).atStartOfDay(zone).toInstant()), ownPackage),
            readAt = now.toInstant().toEpochMilli(),
        )
    }

    fun weightRecord(p: WeightPoint, zone: ZoneId): WeightRecord {
        val t = Instant.ofEpochMilli(p.at)
        return WeightRecord(t, zone.rules.getOffset(t), Mass.kilograms(p.kg), Metadata.manualEntry())
    }

    /** A meal as Health Connect nutrition: a two-minute interval at the time he ate. */
    fun nutritionRecord(m: Meal, zone: ZoneId): NutritionRecord {
        val t = Instant.ofEpochMilli(m.at)
        val end = t.plusSeconds(120)
        return NutritionRecord(
            startTime = t, startZoneOffset = zone.rules.getOffset(t), endTime = end, endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(clientRecordId = "zeus-meal-${m.id}"),
            name = m.dish.take(100),
            energy = Energy.kilocalories(m.kcal.toDouble()),
            protein = Mass.grams(m.proteinG), totalCarbohydrate = Mass.grams(m.carbsG), totalFat = Mass.grams(m.fatG),
            mealType = when (m.type) {
                MealType.BREAKFAST -> androidx.health.connect.client.records.MealType.MEAL_TYPE_BREAKFAST
                MealType.LUNCH -> androidx.health.connect.client.records.MealType.MEAL_TYPE_LUNCH
                MealType.DINNER -> androidx.health.connect.client.records.MealType.MEAL_TYPE_DINNER
                MealType.SNACK -> androidx.health.connect.client.records.MealType.MEAL_TYPE_SNACK
            },
        )
    }
}
