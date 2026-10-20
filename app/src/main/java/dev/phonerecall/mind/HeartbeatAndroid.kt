package dev.phonerecall.mind

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Different
import dev.phonerecall.clio.Clio
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Athena's heartbeat: one periodic worker, hourly, network required. The gate inside decides whether she is asked. */
object HeartbeatWork {
    fun schedule(c: Context) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(
            "zeus-heartbeat", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HeartbeatWorker>(60, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    /** The digest from the phone's own state: derived lines only (the whole message is sanitized before it goes). */
    fun digest(app: Context, core: BrainCore, since: Long, now: ZonedDateTime): HeartbeatDigest {
        val host = core.mindHost
        val zone = now.zone
        val ms = now.toInstant().toEpochMilli()
        val notes = HeartbeatNotes.since(host.kv, since).map { "${time.format(Instant.ofEpochMilli(it.at).atZone(zone))}: ${it.text}" + if (it.high) " (urgent)" else "" }
        val calendar = runCatching { BackgroundReads(app, core).relevantEvents(ms, ms + 6 * 3_600_000L) }.onFailure { Clio.error("heartbeat", it) }.getOrDefault(emptyList())
            .take(12).map { "${if (it.allDay) "all day" else time.format(Instant.ofEpochMilli(it.begin).atZone(zone))}: ${it.title}" }
        val jobs = host.locked { jobs() }.filter { it.status !in setOf("done", "failed", "cancelled") }.map { "${it.id}: ${it.title} (${it.status})" }
        val asks = runCatching { AskHub.get(app).pending() }.getOrDefault(emptyList()).filter { it.status == "waiting" }.map { "${it.worker}: ${it.topic} (waiting)" }
        val trip = runCatching {
            dev.phonerecall.argo.Travel.current(dev.phonerecall.argo.ArgoStore.load(app).trips, ms, zone)?.let { (t, m) -> "${t.place}: ${m.phase.name.lowercase()}" }
        }.getOrNull()
        return HeartbeatDigest(notes, calendar, jobs, asks, runCatching { Different.current(now) }.getOrDefault(emptyList()), trip, dev.phonerecall.places.PlaceStore(app).current())
    }
}

class HeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = Clio.work(this) { runWork() }

    private fun runWork(): Result {
        val app = applicationContext
        val started = System.currentTimeMillis()
        val core = BrainCore.get(app)
        if (!core.vault.has()) return Result.success()
        val host = core.mindHost
        // A scheduled run may be waiting on a phone tool: answer it first, whatever the gate says.
        runCatching { host.locked { answerScheduled() } }.onFailure { Clio.error("mind", it) }
        val store = HeartbeatStore(host.kv)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var digest: HeartbeatDigest? = null
        val why = HeartbeatGate.skip(now, store.on, host.spentCents(), host.capCents(), store.dayCents(zone, started), store.lastReal) {
            HeartbeatWork.digest(app, core, store.lastBeat ?: (started - 3_600_000L), now).also { digest = it }.changedSince(store.fingerprint)
        }
        if (why != null) {
            Clio.info("heartbeat", "heartbeat: $why")
            // Quiet hours and off leave the cursor alone, so the night's notes reach the first morning beat.
            if (why != "quiet hours" && why != "off") store.beat(started, why)
            core.changed()
            return Result.success()
        }
        val d = digest ?: return Result.success()
        Clio.clean = host.mind.sanitizer()
        val r = runCatching { core.spend.check(); host.locked { heartbeat(d, started) { host.spentCents() } } }
        val outcome = r.fold({ it.outcome }, { e -> if (e is dev.phonerecall.brain.SpendCapReached) "cap reached" else "failed: " + dev.phonerecall.brain.AthenaEngine.plainError(e) })
        r.onSuccess { store.real(started, d.fingerprint(), it.cents, zone) }.onFailure { if (it !is dev.phonerecall.brain.SpendCapReached) Clio.error("heartbeat", it) }
        store.beat(started, outcome)
        val cents = r.getOrNull()?.cents ?: 0
        Clio.info("mind", "athena turn", "trigger heartbeat · $outcome · $cents cents · ${d.notes.size} new notes")
        WakeLog.add(host.kv, Wakeup(started, "heartbeat: $outcome", System.currentTimeMillis() - started, if (r.isSuccess) 1 else 0))
        core.changed()
        return Result.success()
    }
}
