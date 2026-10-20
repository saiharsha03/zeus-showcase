package dev.phonerecall.metron

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.SenseCard
import dev.phonerecall.mind.stateJson
import dev.phonerecall.voice.LiveLedgerStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZonedDateTime

/**
 * One per process. Keeps the last usage reading (so Metron, the widget and the routing rule work offline),
 * raises each alert once (an Olympus card plus one notification), and answers read_usage.
 * Lemnos is read when Pantheon or Metron opens, on an FCM wake-up and on the mind's 30-minute sync.
 */
class MetronCore private constructor(private val app: Context) {
    private val sp = app.getSharedPreferences("metron", Context.MODE_PRIVATE)
    private val _usage = MutableStateFlow(load())
    val usage: StateFlow<UsageSnapshot?> = _usage.asStateFlow()

    private fun load(): UsageSnapshot? = sp.getString("usage", null)?.let { runCatching { stateJson.readValue(it, UsageSnapshot::class.java) }.getOrNull() }

    fun budget(now: ZonedDateTime = ZonedDateTime.now()): Budget {
        val core = BrainCore.get(app)
        val circle = runCatching { core.mindHost.locked { circleSpend() } }.getOrDefault(emptyMap())
        return MetronBudget.of(core.spend.state.value, circle, runCatching { LiveLedgerStore(app).load(now.toLocalDate()) }.getOrNull(), now)
    }

    /** A fresh reading from Lemnos. Blocking; call on IO. */
    fun fetch(): UsageSnapshot = UsageParse.parse(dev.phonerecall.lemnos.Gateway.usage(), System.currentTimeMillis()).also { onUsage(it) }

    /** Keeps a reading, then checks the thresholds. */
    fun onUsage(u: UsageSnapshot) {
        _usage.value = u
        sp.edit().putString("usage", stateJson.writeValueAsString(u)).apply()
        check()
        runCatching { dev.phonerecall.widgets.WidgetBus.metron(app) }
    }

    /** Raises what passed a threshold since the last check. Also called after spend changes (the mind's sync). */
    @Synchronized fun check(now: ZonedDateTime = ZonedDateTime.now()) {
        val fired = sp.getStringSet("fired", emptySet()).orEmpty()
        val due = MetronAlerts.due(_usage.value, runCatching { budget(now) }.getOrNull(), fired, now)
        if (due.isEmpty()) return
        sp.edit().putStringSet("fired", MetronAlerts.remember(fired, due)).apply()
        val core = BrainCore.get(app)
        val ms = now.toInstant().toEpochMilli()
        for (a in due) {
            runCatching { core.store.upsertCard(SenseCard("metron:${a.key}", a.title, a.body, "METRON", ms, a.untilMs)) }
            Notify.post(app, a)
            core.audit.log("Metron", "alert", a.title)
        }
        runCatching { core.changed() }
    }

    /** The routing rule's view: may [worker] take a job now? */
    fun pick(worker: String, alternatives: List<String> = emptyList()): WorkerPick =
        UsageRouting.pick(worker, _usage.value, System.currentTimeMillis() / 1000, alternatives)

    /** read_usage: the workers' windows, the cap and the rule, in words. */
    fun words(now: ZonedDateTime = ZonedDateTime.now()): String = MetronWords.describe(_usage.value, runCatching { budget(now) }.getOrNull(), now)

    private object Notify {
        private const val CHANNEL = "metron"

        fun post(c: Context, a: MetronAlert) {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Metron: plan and budget limits", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a worker's plan window or Athena's monthly cap passes a threshold, once each"
            })
            val open = PendingIntent.getActivity(
                c, a.key.hashCode(), Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, a.dest).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(a.title)
                .setContentText(a.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.body))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify("metron", a.key.hashCode(), n) }
        }
    }

    companion object {
        @Volatile private var instance: MetronCore? = null
        fun get(c: Context): MetronCore = instance ?: synchronized(this) { instance ?: MetronCore(c.applicationContext).also { instance = it } }
    }
}
