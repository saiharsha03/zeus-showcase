package dev.phonerecall.iaso

import android.content.Context
import androidx.health.connect.client.records.StepsRecord
import java.time.Instant
import java.time.LocalDate

/**
 * The two derived facts the small touches may read (qol2/): whether last night was short, and when the step
 * source last wrote. No minutes, counts or sources leave; neither reaches the Now Bar or a widget.
 */
object IasoGlance {
    /** A night under five hours, from what Iaso already read. Null when there is no night. */
    fun shortNight(today: LocalDate): Boolean? = Iaso.peek()?.state?.value?.report?.lastNight(today)?.let { it.asleepMin in 1 until 300 }

    /** Under 1,500 steps so far today, from what Iaso already read (qol/Steps). The count itself never leaves. */
    fun quietOnFoot(today: LocalDate): Boolean? = Iaso.peek()?.state?.value?.report?.days?.firstOrNull { it.date == today }?.steps?.let { it < 1_500 }

    @Volatile private var cached: Pair<Long, Long?>? = null

    /** When steps were last written to Health Connect (the newest record's end), read at most every 30 minutes. Blocking. */
    fun stepsSyncedAt(c: Context, now: Long = System.currentTimeMillis()): Long? {
        cached?.takeIf { now - it.first < 30 * 60_000L }?.let { return it.second }
        val hc = RealHc.of(c) ?: return null
        val at = runCatching {
            kotlinx.coroutines.runBlocking {
                if (androidx.health.connect.client.permission.HealthPermission.getReadPermission(StepsRecord::class) !in hc.granted()) return@runBlocking null
                hc.read(StepsRecord::class, Instant.ofEpochMilli(now - 2 * 86_400_000L), Instant.ofEpochMilli(now)).maxOfOrNull { it.endTime.toEpochMilli() }
            }
        }.getOrNull()
        cached = now to at
        return at
    }
}
