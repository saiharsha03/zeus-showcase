package dev.phonerecall.brain

import dev.phonerecall.data.MyAccounts
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The notification sense. The system binds it once the owner grants notification access (Themis, or
 * Settings > Notifications > Device and app notifications). It only reads; it never cancels, replies
 * to or changes anything. Each post and removal goes to the senses thread; nothing runs here.
 */
class SensesListener : NotificationListenerService() {

    /** The morning brief's fallback trigger: the first unlock after 5 AM. Lives as long as the listener is bound. */
    private var unlock: dev.phonerecall.brief.BriefUnlockReceiver? = null

    override fun onListenerConnected() {
        SensesWork.schedule(this)
        dev.phonerecall.rhythms.RhythmsWork.schedule(this)
        if (unlock == null) unlock = dev.phonerecall.brief.BriefUnlockReceiver().also {
            runCatching { registerReceiver(it, android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED) }
        }
    }

    override fun onListenerDisconnected() {
        unlock?.let { runCatching { unregisterReceiver(it) } }
        unlock = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Rhythms: WhatsApp's call lines only (ongoing ones too, which the senses skip). Never message text.
        dev.phonerecall.rhythms.Rhythms.notification(applicationContext, sbn)
        val n = incoming(this, sbn) ?: return
        // Moneta reads bank alerts into transactions on its own thread; the senses keep only "a bank alert arrived".
        dev.phonerecall.moneta.MonetaCore.get(this).capture(n)
        BrainCore.get(this).run { ingest(n) }
        // Odyssey: a payment or receipt line during a stay names the place (on the phone only).
        dev.phonerecall.odyssey.Odyssey.notification(applicationContext, n)
        // Moments: a frozen or bake item in a receipt, by name only, for tonight's "Frozen pizza tonight?".
        dev.phonerecall.moments.MomentsCore.notification(applicationContext, n)
        // Argo: a city and a date in a notification is a hint for Athena to look into (only those two are kept).
        dev.phonerecall.argo.Argo.notification(applicationContext, n)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        val r = when (reason) {
            // Tapped, or cleared by the app because he opened the chat or replied from the shade.
            REASON_CLICK, REASON_APP_CANCEL, REASON_APP_CANCEL_ALL -> Response.ENGAGED
            REASON_CANCEL, REASON_CANCEL_ALL -> Response.DISMISSED
            else -> Response.IGNORED
        }
        if (r == Response.IGNORED) return
        val at = System.currentTimeMillis()
        BrainCore.get(this).run { removed(sbn.key, r, at) }
    }

    companion object {
        private val LABELS = mapOf(
            "com.whatsapp" to "WhatsApp", "com.google.android.apps.messaging" to "Messages", "com.samsung.android.messaging" to "Messages",
            "com.google.android.dialer" to "Phone", "com.samsung.android.dialer" to "Phone", "com.android.server.telecom" to "Phone",
            "com.google.android.gm" to "Gmail", "com.microsoft.office.outlook" to "Outlook", "com.microsoft.teams" to "Teams", "com.Slack" to "Slack",
            "com.instagram.android" to "Instagram", "org.telegram.messenger" to "Telegram",
            "com.google.android.projection.gearhead" to "Android Auto", "com.google.android.apps.maps" to "Google Maps",
            "com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music", "com.waze" to "Waze",
        )

        fun label(context: Context, pkg: String): String = LABELS[pkg] ?: try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }

        /** The parts of a notification the senses use. Ongoing, foreground-service and group-summary notifications are skipped. */
        fun incoming(context: Context, sbn: StatusBarNotification): Incoming? {
            val no = sbn.notification ?: return null
            // Telegram is where Iris and the bots talk to him: never a sense.
            if (sbn.packageName in AppKinds.TELEGRAM) return null
            // Temu: its app and its emails are never a sense (his rule).
            if (sbn.packageName in AppKinds.IGNORED) return null
            if (sbn.isOngoing || no.flags and Notification.FLAG_GROUP_SUMMARY != 0 || no.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return null
            if (no.category == Notification.CATEGORY_PROGRESS || no.category == Notification.CATEGORY_TRANSPORT || no.category == Notification.CATEGORY_SERVICE) return null
            val ex = no.extras ?: return null
            var title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            var sender: String? = null
            val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.let(MyAccounts::untag)
            val group = ex.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
            // Chat apps: the newest message and who sent it.
            runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(no) }.getOrNull()?.messages?.lastOrNull()?.let { m ->
                m.text?.toString()?.let { text = it }
                sender = m.person?.name?.toString()
                if (title.isBlank()) title = sender.orEmpty()
            }
            // A family member's mail may be on this phone too. Gmail names the account in the sub text: keep only his (fail closed).
            // Other mail apps (Outlook is work) are dropped only when they name an account that isn't his.
            val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            if (sbn.packageName == "com.google.android.gm" && !MyAccounts.gmailMine(sub)) return null
            if (sbn.packageName in AppKinds.MAIL && MyAccounts.notMine(sub)) return null
            if (sbn.packageName in AppKinds.MAIL && AppKinds.ignoredSender(title, ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text)) return null
            title = MyAccounts.untag(title)
            sender = sender?.let(MyAccounts::untag)
            if (title.isBlank() && text.isBlank()) return null
            return Incoming(
                key = sbn.key, pkg = sbn.packageName, app = label(context, sbn.packageName), title = title, text = text,
                at = sbn.postTime, sender = sender, conversation = conversation?.takeIf { group }, isGroup = group,
            )
        }
    }
}

/** Hourly, battery permitting: the 48 h purge, task hygiene, empty sections. No network. */
class HygieneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        BrainCore.get(applicationContext).run { hygiene() }.get()
        return Result.success()
    }
}

/** One batched Haiku call for notifications the rules couldn't settle, and captures still unsorted. */
class TriageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        BrainCore.get(applicationContext).run { triageBatch(); retryUnsorted() }.get()
        return Result.success()
    }
}

object SensesWork {
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "zeus-hygiene", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HygieneWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }

    /** At most one waiting; KEEP means a burst of notifications still makes one call. */
    fun triageSoon(context: Context, delayMs: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            "zeus-triage", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<TriageWorker>()
                .setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build())
                .build(),
        )
    }
}
