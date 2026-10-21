package dev.phonerecall.moneta

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationCompat
import com.anthropic.core.jsonMapper
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.Incoming
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.QuietHours
import dev.phonerecall.clio.Clio
import dev.phonerecall.lemnos.Agora
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.BroadcastReceiver
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.ZoneId
import java.util.concurrent.Executors

@Immutable
data class MonetaState(
    val loaded: Boolean = false,
    val txns: List<Txn> = emptyList(),
    val budgets: Map<Category, Long> = emptyMap(),
    val manual: List<Subscription> = emptyList(),
    val irisSubs: List<Subscription> = emptyList(),
    val dismissed: Set<String> = emptySet(),
    /** Charts Iris pinned. */
    val charts: List<ChartSpec> = emptyList(),
    val changes: List<MonetaChange> = emptyList(),
    val syncedAt: Long = 0,
    val irisError: String? = null,
    /** Merchants he said were his ("Yes, that's me"), normalised. */
    val known: Set<String> = emptySet(),
    /** Flags he said "No" to: each waits for him to open the bank or tell Iris. */
    val disowned: List<String> = emptyList(),
)

/**
 * One Moneta per process. The notification listener hands it every notification; only bank ones become
 * transactions. Iris's log and her ops arrive through Lemnos. Work runs on one background thread. Nothing here
 * reaches the brain, the mind or Alexandria.
 */
class MonetaCore private constructor(private val app: Context) {
    val store = MonetaStore(app, KeystoreSealer("zeus.aegis.moneta"))
    val ops = MonetaOps(store)
    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "zeus-moneta").apply { isDaemon = true } }
    private val _state = MutableStateFlow(MonetaState())
    val state: StateFlow<MonetaState> = _state.asStateFlow()
    /** Banks re-post an alert when it updates: the same text within 10 minutes counts once. */
    private val recent = LinkedHashMap<Int, Long>()

    private fun bg(block: () -> Unit) { serial.execute { runCatching(block) } }

    fun load() = bg { reload() }

    private fun reload() {
        _state.value = MonetaState(
            true, store.txns(System.currentTimeMillis() - 800 * DAY), store.budgets(), store.manualSubs(), store.irisSubs(), store.dismissed(),
            store.charts().mapNotNull { ChartSpecs.parse(it).getOrNull() }, store.changes(), store.long(SYNCED), _state.value.irisError,
            store.known(), store.disowned(),
        )
        // Its own widget: percents and counts only (WidgetContent.money).
        runCatching { dev.phonerecall.widgets.WidgetBus.section(app, dev.phonerecall.widgets.WKind.MONEY) }
    }

    fun capture(n: Incoming) = bg {
        val t = BankParser.parse(n.pkg, n.app, n.title, n.text, n.at) ?: return@bg
        val h = (n.pkg + "|" + n.title + "|" + n.text).hashCode()
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > 10 * 60_000L }
        if (recent.put(h, now) != null) return@bg
        val (row, folded) = Reconcile.insert(store, ops, t)
        if (folded) Clio.info("moneta", "folded a ${t.via?.word ?: "bank"} alert", t.bank.orEmpty())
        reload()
        changed()
        // A second alert for a purchase already seen asks nothing new.
        if (folded) return@bg
        val s = _state.value
        val u = Money.unusual(s.txns, now, ZoneId.systemDefault(), s.dismissed, known = s.known, travelling = { MonetaHooks.trips?.tripAt(it) != null })
            .firstOrNull { it.txn.ref == row.ref } ?: return@bg
        if (u.key in store.asked()) return@bg
        store.addAsked(u.key)
        Clio.info("moneta", "flagged: ${u.rule}", listOfNotNull(u.txn.bank, "fraud-grade".takeIf { u.fraud }).joinToString(", "))
        Alerts.ask(app, u)
    }

    /** "Yes, that's me" on a flag: fine, and the merchant is his from now on. */
    fun mine(key: String, merchant: String) = bg { MonetaFlags.mine(store, key, merchant); Clio.info("moneta", "said it was his"); reload() }

    /** "No" on a flag: the card to open the bank's app or tell Iris waits on Moneta's page. */
    fun disown(key: String) = bg { MonetaFlags.disown(store, key); Clio.warn("moneta", "said it wasn't his"); reload() }

    /** Posts "The owner says he didn't make ..." in Agora for Iris, on his tap. Never the amount. */
    fun tellIris(key: String) = bg {
        val t = MonetaFlags.txnFor(store, key) ?: return@bg
        runCatching { Gateway.send(Agora.ROOM, MonetaFlags.irisNote(t, ZoneId.systemDefault()), "moneta-no-${key.hashCode().toUInt()}") }
            .onSuccess { MonetaFlags.settle(store, key); Clio.info("moneta", "told Iris about a charge he didn't make") }
            .onFailure { Clio.error("moneta", it) }
        reload()
    }

    fun settle(key: String) = bg { MonetaFlags.settle(store, key); reload() }

    /** His own edits take the same path as Iris's: applied at once, logged, undoable. */
    fun edit(op: MonetaOp) = bg { runCatching { ops.apply(op, MonetaOps.WHO_OWNER) }; reload(); changed() }
    fun undo(change: MonetaChange) = bg { ops.undo(change.id); reload(); changed() }
    fun dismiss(u: Unusual) = bg { store.dismiss(u.key); reload() }
    fun dismissKey(key: String) = bg { store.dismiss(key); reload() }

    fun addManual(t: Txn) = edit(MonetaOp.Add("me:${System.currentTimeMillis()}", TxnFields(t.at, t.cents, t.merchant, t.category.takeIf { it != Category.OTHER }, TxnKind.SPEND)))
    fun setBudget(c: Category, cents: Long?) = edit(MonetaOp.Budget(c, cents))
    fun addSubscription(s: Subscription) = edit(MonetaOp.SubAdd(s))

    /** Background: Iris's log since the cursor (the whole log once a day, to see her edits and deletes), then her ops. */
    fun syncIris() = bg { syncNow() }

    /** Blocking, for the Lemnos sync worker (an FCM wake-up). */
    fun syncIrisBlocking() { serial.submit { runCatching { syncNow() } }.get() }

    private fun syncNow() {
        if (!DeviceKeys.paired(app)) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val full = now - store.long(FULL) > Ledger.FULL_EVERY
        val r = runCatching {
            var since = if (full) 0L else store.long(CURSOR)
            val rows = ArrayList<Txn>()
            var subs: List<Subscription>? = null
            for (page in 0 until 40) {
                val p = Ledger.parse(Gateway.irisLedger(since, 500), zone)
                rows += p.rows; subs = p.subs; since = p.next
                if (!p.more) break
            }
            Ledger.merge(store, rows, full)?.let { store.logChange(now, MonetaOps.WHO_IRIS, "her log: $it", null); store.setLong(DIRTY, 1) }
            subs?.let { store.saveIrisSubs(it) }
            store.setLong(CURSOR, since)
            if (full) store.setLong(FULL, now)
            val envs = Gateway.monetaOps()
            if (envs.isNotEmpty()) { Gateway.monetaAck(ops.applyEnvelopes(envs)); store.setLong(DIRTY, 1) }
            store.setLong(SYNCED, now)
        }
        _state.value = _state.value.copy(irisError = r.exceptionOrNull()?.let { "Iris's log didn't come: ${it.message ?: it.javaClass.simpleName}" })
        reload()
        publish(full && r.isSuccess)
    }

    /** Something in Moneta changed: Iris's view goes out once the last one is 10 minutes old (after this task). */
    private fun changed() {
        store.setLong(DIRTY, 1)
        bg { publish(false) }
    }

    /**
     * Moneta's view for Iris ([MonetaView]) through Lemnos: after a change at most every 10 minutes, and on the daily
     * full read. Runs on Moneta's thread; a failure waits for the next change or sync.
     */
    private fun publish(full: Boolean) {
        val now = System.currentTimeMillis()
        if (!DeviceKeys.paired(app) || !MonetaView.due(store.long(DIRTY) != 0L, store.long(VIEWED), now, full)) return
        runCatching {
            val zone = ZoneId.systemDefault()
            val txns = store.txns(now - 120 * DAY)
            val flagged = Money.unusual(txns, now, zone, store.dismissed(), known = store.known(), travelling = { MonetaHooks.trips?.tripAt(it) != null })
                .filter { it.fraud }.map { it.txn.ref }.toSet()
            Gateway.monetaView(MonetaView.build(txns, subscriptions(), store.budgets(), flagged, now, zone))
        }.onSuccess { n ->
            store.setLong(VIEWED, now); store.setLong(DIRTY, 0)
            Clio.info("moneta", "shared Moneta with Iris", "$n rows")
        }.onFailure { Clio.error("moneta", it) }
    }

    /** Every subscription he has, with the next renewal (seen on his cards, Iris's, added). */
    fun subscriptions(s: MonetaState = _state.value): List<Subscription> =
        Money.subscriptions(Money.recurring(s.txns, System.currentTimeMillis(), ZoneId.systemDefault()), s.irisSubs, s.manual)

    companion object {
        private const val DAY = 86_400_000L
        private const val CURSOR = "ledger.cursor"
        private const val FULL = "ledger.full"
        private const val SYNCED = "ledger.synced"
        private const val VIEWED = "view.published"
        private const val DIRTY = "view.dirty"
        @Volatile private var instance: MonetaCore? = null
        fun get(context: Context): MonetaCore = instance ?: synchronized(this) {
            instance ?: MonetaCore(context.applicationContext).also { instance = it }
        }
    }

    /**
     * "Did you make this?" for a flagged charge, with Yes and No. Hidden on the lock screen entirely (its public
     * version says only "Zeus"); unlocked, it names the merchant, amount, bank and last four. Fraud-grade flags ring
     * at once on a high channel; the others stay silent in quiet hours or while a nap alarm is pending.
     */
    object Alerts {
        const val CHANNEL = "moneta"
        const val FRAUD_CHANNEL = "moneta.fraud"
        const val TITLE = "Did you make this?"
        const val ACTION_YES = "dev.phonerecall.moneta.YES"
        const val EXTRA_KEY = "dev.phonerecall.moneta.KEY"
        const val EXTRA_MERCHANT = "dev.phonerecall.moneta.MERCHANT"
        /** On the "No" tap's activity intent: the flag he disowned. */
        const val EXTRA_NO = "dev.phonerecall.moneta.NO"
        /** On the "No" tap: the bank whose app opens. */
        const val EXTRA_BANK = "dev.phonerecall.moneta.BANK"
        const val TAG = "moneta"

        fun text(u: Unusual): String = "${u.txn.merchant} ${Money.fmt(u.txn.cents)} on ${u.txn.bank ?: "your card"}" + (u.txn.last4?.let { " ••$it" } ?: "")

        /** Whether it may make a sound now. */
        fun rings(u: Unusual, now: ZonedDateTime, napQuiet: Boolean): Boolean = u.fraud || !(QuietHours.quiet(now) || napQuiet)

        fun id(key: String): Int = key.hashCode()

        fun build(context: Context, u: Unusual, ring: Boolean): Notification {
            val yes = PendingIntent.getBroadcast(
                context, id(u.key), Intent(context, MonetaActionReceiver::class.java).setAction(ACTION_YES).putExtra(EXTRA_KEY, u.key).putExtra(EXTRA_MERCHANT, u.txn.merchant),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            fun open(no: Boolean) = PendingIntent.getActivity(
                context, id(u.key) + if (no) 1 else 2,
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "MONEY").apply { if (no) putExtra(EXTRA_NO, u.key).putExtra(EXTRA_BANK, u.txn.bank) },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, if (u.fraud) FRAUD_CHANNEL else CHANNEL)
                .setSmallIcon(R.drawable.ic_owl)
                .setContentTitle(TITLE)
                .setContentText(text(u))
                .setStyle(NotificationCompat.BigTextStyle().bigText("${text(u)}. ${u.reason}."))
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_owl).setContentTitle("Zeus").build())
                .setPriority(if (u.fraud) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setSilent(!ring)
                .setContentIntent(open(false))
                .addAction(0, "Yes, that's me", yes)
                .addAction(0, "No", open(true))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
        }

        fun ask(context: Context, u: Unusual) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Moneta", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A charge worth a second look. Nothing on the lock screen."
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
            nm.createNotificationChannel(NotificationChannel(FRAUD_CHANNEL, "Moneta: possible fraud", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A charge shaped like card fraud. Rings at once, even in quiet hours. Nothing on the lock screen."
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
            val ring = rings(u, ZonedDateTime.now(), dev.phonerecall.brain.AlarmWatch.get(context).napQuiet())
            nm.notify(TAG, id(u.key), build(context, u, ring))
        }

        fun cancel(context: Context, key: String) { context.getSystemService(NotificationManager::class.java).cancel(TAG, id(key)) }
    }
}

/** What his answers to a flag do, on the store alone (the receiver and the page both come here). */
object MonetaFlags {
    private val day = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)

    /** The charge a flag's key names ("<at>:<cents>:<merchant>", or "declines:<at>:<merchant>"). */
    fun txnFor(store: MonetaStore, key: String): Txn? {
        val parts = key.removePrefix("declines:").removePrefix("up:").split(':')
        val at = parts.getOrNull(0)?.toLongOrNull() ?: return null
        return store.txns(at).firstOrNull { it.at == at && it.mergedInto == null }
    }

    fun mine(store: MonetaStore, key: String, merchant: String) {
        store.dismiss(key)
        if (merchant.isNotBlank()) store.addKnown(merchant)
    }

    fun disown(store: MonetaStore, key: String) {
        store.dismiss(key)
        if (key !in store.disowned()) store.saveDisowned(store.disowned() + key)
    }

    fun settle(store: MonetaStore, key: String) = store.saveDisowned(store.disowned() - key)

    /** What Iris reads in Agora: merchant, day, bank and last four. Never the amount. */
    fun irisNote(t: Txn, zone: ZoneId): String =
        "The owner says he didn't make ${t.merchant} ${day.format(Instant.ofEpochMilli(t.at).atZone(zone))} on ${t.bank ?: "his card"}" + (t.last4?.let { " ••$it" } ?: "")
}

/** "Yes, that's me" from the flag notification. "No" opens Moneta instead (MainActivity hands the key to [MonetaCore.disown]). */
class MonetaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(MonetaCore.Alerts.EXTRA_KEY) ?: return
        MonetaCore.Alerts.cancel(context, key)
        if (intent.action == MonetaCore.Alerts.ACTION_YES) MonetaCore.get(context).mine(key, intent.getStringExtra(MonetaCore.Alerts.EXTRA_MERCHANT).orEmpty())
    }
}

/** Renewals in Chronos: "Renews: Netflix", all day, names only (no amounts). */
object MonetaCalendar {
    const val ID = -8L

    fun items(subs: List<Subscription>, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<CalItem> = subs.mapNotNull { s ->
        val d = s.next ?: return@mapNotNull null
        val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
        if (start < from || start >= to) return@mapNotNull null
        CalItem(
            eventId = -((s.name.hashCode().toLong() and 0x7fffffffL) + 10_000_000L), calendarId = ID, title = "Renews: ${s.name}",
            begin = start, end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), allDay = true, category = EventCategory.PERSONAL,
            calendarName = "Moneta", place = null, notes = null, recurring = false,
        )
    }

    /** For Chronos' month reads (on IO). */
    fun items(context: Context, from: Long, to: Long): List<CalItem> = runCatching {
        val core = MonetaCore.get(context)
        val s = core.state.value.takeIf { it.loaded } ?: MonetaState(true, core.store.txns(System.currentTimeMillis() - 400 * 86_400_000L), manual = core.store.manualSubs(), irisSubs = core.store.irisSubs())
        items(core.subscriptions(s), from, to)
    }.getOrDefault(emptyList())
}
