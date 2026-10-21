package dev.phonerecall.horologion

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.WorkManager

/**
 * The phone's own background jobs (WorkManager), as Horologion shows them. Three intervals may change, within
 * bounds that keep the battery and the features sane: the mind sync (15 to 120 minutes), the subconscious sweep
 * (1 to 7 days) and the Mnemosyne backup (daily or weekly). A change re-enqueues that job with UPDATE.
 */
object PhoneJobs {
    data class Spec(val id: String, val name: String, val what: String, val defaultMinutes: Long, val constraints: String, val bounds: Bounds? = null)

    val SPECS = listOf(
        Spec("zeus-mind-sync", "Mind sync", "Sends Athena's batch, answers her runs, pulls results and memory", 30, "network", Bounds(15, 120, listOf(15, 30, 60, 120))),
        Spec("zeus-oneiroi-subconscious", "Subconscious", "Sweeps memory for what is stale or doubled", 48 * 60, "", Bounds(1440, 7 * 1440, (1L..7L).map { it * 1440 })),
        Spec("mnemosyne.nightly", "Mnemosyne backup", "Encrypted backup of this phone to the storage box, from 3 AM", 1440, "charging, Wi-Fi, idle", Bounds(1440, 10_080, listOf(1440L, 10_080L))),
        Spec("zeus-heartbeat", "Heartbeat", "Athena's hourly look at what changed; skipped when nothing did (on or off in Everything else)", 60, "network"),
        Spec("zeus-hygiene", "Hygiene", "Clears expired cards, proposals and old notification lines", 60, "battery not low"),
        Spec("zeus-rhythms", "Rhythms", "Learns when he opens apps and calls home", 6 * 60, "battery not low"),
        Spec("olympia-fixtures", "Fixtures", "Refreshes his teams' matches and tonight's big ones, with where to watch", 12 * 60, "network"),
        Spec("aeolia-check", "Weather", "Checks the forecast and alerts", 30, "network"),
        Spec("zeus-argo", "Argo", "Ticks his trips: leave-by, gates, the day after", 15, ""),
        Spec("zeus-odyssey-roads", "Roads", "Traffic on his usual drives", 15, "network, battery not low"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("horologion", Context.MODE_PRIVATE)

    /** The interval a job is enqueued with: Horologion's choice when it is in bounds, else the default. */
    fun minutes(c: Context, id: String): Long {
        val s = SPECS.firstOrNull { it.id == id } ?: return 15
        val v = prefs(c).getLong("every.$id", s.defaultMinutes)
        return if (s.bounds?.ok(v) == true) v else s.defaultMinutes
    }

    /** Sets a new interval and re-enqueues the job with UPDATE. False when it is out of bounds or not changeable. */
    fun set(c: Context, id: String, minutes: Long): Boolean {
        val s = SPECS.firstOrNull { it.id == id } ?: return false
        if (s.bounds?.ok(minutes) != true) return false
        prefs(c).edit().putLong("every.$id", minutes).apply()
        when (id) {
            "zeus-mind-sync" -> dev.phonerecall.mind.MindWork.schedule(c, ExistingPeriodicWorkPolicy.UPDATE)
            "zeus-oneiroi-subconscious" -> dev.phonerecall.oneiroi.OneiroiWork.schedule(c, ExistingPeriodicWorkPolicy.UPDATE)
            "mnemosyne.nightly" -> dev.phonerecall.mnemosyne.Mnemosyne.schedule(c, policy = ExistingPeriodicWorkPolicy.UPDATE)
        }
        return true
    }

    /** Every job with its interval, state and next run as WorkManager has them. Blocking. */
    fun all(c: Context): List<PhoneJob> {
        val wm = WorkManager.getInstance(c)
        return SPECS.map { s ->
            val info = runCatching { wm.getWorkInfosForUniqueWork(s.id).get().firstOrNull() }.getOrNull()
            PhoneJob(s.id, s.name, s.what, minutes(c, s.id), s.constraints, info?.nextScheduleTimeMillis?.takeIf { it in 1 until Long.MAX_VALUE }, info?.state?.name ?: "not scheduled", s.bounds)
        }
    }
}
