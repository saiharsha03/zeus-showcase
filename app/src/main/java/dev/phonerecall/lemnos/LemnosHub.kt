package dev.phonerecall.lemnos

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID

/** Everything Zeus knows about Lemnos right now. Screens read it; [LemnosHub] fills it. */
data class LemnosState(
    val pairing: DeviceKeys.State = DeviceKeys.State.NONE,
    /** Lemnos opens with his fingerprint once per app session. Pantheon and Cerberus don't need it. */
    val unlocked: Boolean = false,
    val online: Boolean? = null,
    val error: String? = null,
    val health: Health? = null,
    val sessions: List<TermSession> = emptyList(),
    val slash: Map<String, List<SlashCmd>> = emptyMap(),
    val agents: List<AgentStatus> = emptyList(),
    val chat: Map<String, List<ChatMsg>> = emptyMap(),
    val jobs: List<WorkerJob> = emptyList(),
    val alerts: List<Alert> = emptyList(),
    val units: List<ServiceUnit> = emptyList(),
    val tasks: List<IrisTask> = emptyList(),
    /** The newest Agora post the owner has seen (its unread dot). */
    val agoraSeen: Long = 0,
    val syncedAt: Long? = null,
    val live: Boolean = false,
)

/**
 * One per process. All network work runs on IO through [Gateway] (mTLS, NetGate). The events socket is open
 * only while a Lemnos, Pantheon or Cerberus screen is on screen; in the background, FCM wake-ups ("sync",
 * no content) start [LemnosSyncWorker], which fetches the inbox and posts the notifications itself.
 */
class LemnosHub private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(LemnosState(pairing = DeviceKeys.state(app)))
    val state: StateFlow<LemnosState> = _state.asStateFlow()
    private var events: WebSocket? = null
    private var watchers = 0
    private val prefs = app.getSharedPreferences("lemnos", Context.MODE_PRIVATE)

    init { _state.update { it.copy(agoraSeen = prefs.getLong("agora.seen", 0L)) } }

    val paired: Boolean get() = _state.value.pairing == DeviceKeys.State.PAIRED

    private fun io(what: String, block: suspend () -> Unit): Job = scope.launch {
        try { block(); _state.update { it.copy(online = true, error = null) } }
        catch (e: GatewayException) { dev.phonerecall.clio.Clio.error("lemnos", e); _state.update { it.copy(error = "$what: ${e.message}") } }
        catch (e: Exception) { dev.phonerecall.clio.Clio.error("lemnos", e); _state.update { it.copy(online = false, error = "$what: ${e.message ?: e.javaClass.simpleName}") } }
    }

    // ---- pairing ----
    fun refreshPairing() = _state.update { it.copy(pairing = DeviceKeys.state(app)) }

    fun beginPairing() = scope.launch {
        runCatching { DeviceKeys.beginPairing(app) }.onFailure { e -> _state.update { it.copy(error = "Pairing: ${e.message}") } }
        refreshPairing()
    }

    fun finishPairing() = scope.launch {
        runCatching { DeviceKeys.finishPairing(app) }.onFailure { e -> _state.update { it.copy(error = "Pairing: ${e.message}") } }
        refreshPairing()
        if (paired) { baselineInbox(); enablePush(); refresh(); dev.phonerecall.theogony.TheogonyHub.get(app).onPaired() }
    }

    fun unpair() = scope.launch {
        stopLive(force = true)
        DeviceKeys.unpair(app)
        FirebaseMessaging.getInstance().isAutoInitEnabled = false
        _state.value = LemnosState(pairing = DeviceKeys.State.NONE)
    }

    fun setUnlocked(v: Boolean) = _state.update { it.copy(unlocked = v) }

    /** FCM only after pairing: before that Zeus makes no Google call at all (auto-init is off in the manifest). */
    fun enablePush() {
        if (!paired) return
        FirebaseMessaging.getInstance().isAutoInitEnabled = true
        FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> registerPush(t) }
    }

    fun registerPush(token: String) {
        prefs.edit().putString("fcm", token).apply()
        if (paired) io("Push") { Gateway.registerPush(token) }
    }

    // ---- reads ----
    fun refresh() {
        if (!paired) return
        io("Lemnos") {
            val agents = Gateway.agents()
            val alerts = Gateway.alerts()
            val jobs = Gateway.jobs()
            _state.update { it.copy(agents = agents, alerts = alerts, jobs = jobs, syncedAt = System.currentTimeMillis()) }
        }
        io("Health") { val h = Gateway.health(); _state.update { it.copy(health = h) } }
        io("Metron") { dev.phonerecall.metron.MetronCore.get(app).fetch() }
    }

    fun refreshSessions() = io("Sessions") {
        val s = Gateway.sessions()
        val slash = if (_state.value.slash.isEmpty()) Gateway.slash() else _state.value.slash
        _state.update { it.copy(sessions = s, slash = slash) }
    }

    fun refreshUnits() = io("Services") { val u = Gateway.units(); _state.update { it.copy(units = u) } }
    fun refreshTasks() = io("Iris's tasks") { val t = Gateway.irisTasks(); _state.update { it.copy(tasks = t) } }

    fun loadRoom(room: String) = io("Chat") {
        val msgs = Gateway.history(room, limit = 100)
        _state.update { it.copy(chat = it.chat + (room to msgs)) }
    }

    // ---- writes (the owner's own, from his screens) ----
    fun send(room: String, text: String) = io("Send") {
        val m = Gateway.send(room, text, UUID.randomUUID().toString())
        merge(m)
        // Athena is in the group too: his words there reach her at once. In Agora she gets them on the sync, like the agents' posts.
        if (room == "group") runCatching { dev.phonerecall.brain.BrainCore.get(app).mindHost.offer(Agora.groupNote(m)) }
        if (room == Agora.ROOM) syncAgora()
    }

    fun ack(id: Long) = io("Cerberus") {
        Gateway.ack(id)
        _state.update { s -> s.copy(alerts = s.alerts.map { if (it.id == id) it.copy(ack = true) else it }) }
    }

    /**
     * A job to a worker. [JobPolicy] strips any signature unless the owner started it. One Athena or an agent
     * started waits while the worker is above 90% of a plan window (Metron); his own always goes.
     */
    fun submitJob(req: ApprovalRequest, title: String, origin: Origin, approval: String?) = io("Job") {
        if (origin != Origin.OWNER) {
            val pick = dev.phonerecall.metron.MetronCore.get(app).pick(req.executor)
            if (pick !is dev.phonerecall.metron.WorkerPick.Go) throw GatewayException(429, dev.phonerecall.metron.MetronWords.held(pick, java.time.ZonedDateTime.now()) ?: "Held")
        }
        Gateway.submitJob(JobPolicy.outgoing(req, title, origin, approval))
        val jobs = Gateway.jobs()
        _state.update { it.copy(jobs = jobs) }
    }

    fun runAsync(what: String, block: suspend () -> Unit) = io(what, block)

    /** His own request to Iris or Atlas from Horologion, signed with his fingerprint; they apply and confirm it. */
    fun requestFromOwner(r: dev.phonerecall.horologion.OwnerRequest, done: (String?) -> Unit = {}) = io("Request") {
        val result = runCatching { Gateway.submitJob(r.out, by = r.by) }
        done(result.exceptionOrNull()?.message)
        result.getOrThrow()
        val jobs = Gateway.jobs()
        _state.update { it.copy(jobs = jobs) }
    }

    private fun merge(m: ChatMsg) = _state.update { s ->
        val cur = s.chat[m.room].orEmpty()
        if (cur.any { it.id == m.id }) s else s.copy(chat = s.chat + (m.room to (cur + m).sortedBy { it.id }))
    }

    // ---- live events while a screen is open ----
    fun startLive() {
        watchers++
        if (!paired || events != null) return
        io("Live") {
            val since = prefs.getLong("events.seq", 0L)
            val ticket = Gateway.eventsTicket()
            events = Gateway.openEvents(ticket, since, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = _state.update { it.copy(live = true) }
                override fun onMessage(webSocket: WebSocket, text: String) = onEvent(JSONObject(text))
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped()
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped()
            })
        }
    }

    fun stopLive(force: Boolean = false) {
        watchers = if (force) 0 else (watchers - 1).coerceAtLeast(0)
        if (watchers == 0) { events?.close(1000, null); events = null; _state.update { it.copy(live = false) } }
    }

    private fun dropped() {
        events = null
        _state.update { it.copy(live = false) }
        if (watchers > 0) scope.launch { kotlinx.coroutines.delay(3000); if (watchers > 0 && events == null) { watchers--; startLive() } }
    }

    private fun onEvent(e: JSONObject) {
        val seq = e.optLong("seq")
        if (seq > 0) prefs.edit().putLong("events.seq", seq).apply()
        when (e.optString("type")) {
            "message" -> Gateway.chatMsg(e).also { m ->
                merge(m)
                // Iris answering one of Athena's asks (a fill-up, the lease) fills the car card at once.
                if (m.room == "iris" && m.text.contains("ask.reply")) io("Car") { dev.phonerecall.odyssey.Garage.reply(app, m.text) }
                if (m.room == Agora.ROOM && m.sender != "athena") io("Agora") { syncAgora() }
            }
            "notify", "alert" -> io("Cerberus") { val a = Gateway.alerts(); _state.update { it.copy(alerts = a) } }
            "job", "delivery", "agents" -> io("Pantheon") {
                val agents = Gateway.agents(); val jobs = Gateway.jobs()
                _state.update { it.copy(agents = agents, jobs = jobs) }
            }
        }
    }

    // ---- background wake-up: fetch over mTLS, post locally ----
    /** Blocking. Fetches what came in since the last wake-up and posts it. The FCM message itself had no content. */
    fun syncInbox(): Int {
        if (!paired) return 0
        val stored = prefs.getLong("inbox.seq", -1L)
        val (seq, items) = Gateway.inbox(stored.coerceAtLeast(0))
        prefs.edit().putLong("inbox.seq", seq).apply()
        // Iris's part of the morning brief is filed into the brief (LemnosSyncWorker), not shown as a chat reply.
        val brief = items.filter { it.kind == "reply" && dev.phonerecall.brief.BriefIris.isReply(it.body) }
        // No cursor yet (set at pairing): don't replay the server's whole history, only the newest few.
        // A long gap never floods the shade: the newest five, the rest are in Cerberus and Pantheon.
        val shown = items - brief.toSet()
        val show = if (stored < 0) shown.takeLast(3) else shown.takeLast(5)
        show.forEach { LemnosNotify.post(app, it) }
        return show.size
    }

    /**
     * Blocking. Agora posts past the cursor (the owner's and the agents', never Athena's own) go to her mind: his at once,
     * a colleague's batched unless it names something due within 48 hours. Each is offered once. The first run
     * after pairing only sets the cursor.
     */
    @Synchronized fun syncAgora(): Int {
        if (!paired) return 0
        val after = prefs.getLong("agora.after", -1L)
        if (after < 0) {
            prefs.edit().putLong("agora.after", Gateway.history(Agora.ROOM, limit = 1).maxOfOrNull { it.id } ?: 0L).apply()
            return 0
        }
        val got = Gateway.history(Agora.ROOM, after = after, limit = 200)
        val today = java.time.LocalDate.now()
        val core = dev.phonerecall.brain.BrainCore.get(app)
        val notes = got.mapNotNull { m -> Agora.toMind(m, today)?.let { m to it } }
        for ((m, n) in notes) {
            runCatching { core.mindHost.offer(n) }
            runCatching { core.audit.log(who(m.sender), "Agora → Athena", m.text.take(120)) }
        }
        // Iris's street parking rule, offered once on Kairos (qol/StreetParking).
        for (m in got) if (m.sender == "iris") runCatching { dev.phonerecall.qol.StreetParking.fromIris(app, m.text) }
        prefs.edit().putLong("agora.after", Agora.cursor(after, got)).apply()
        got.forEach(::merge)
        return notes.size
    }

    private fun who(sender: String) = when (sender) { "iris" -> dev.phonerecall.ui.Workers.IRIS; "atlas" -> dev.phonerecall.ui.Workers.ATLAS; else -> "The owner" }

    /** The owner opened Agora: everything in it now counts as seen. */
    fun agoraSeen() {
        val top = _state.value.chat[Agora.ROOM].orEmpty().maxOfOrNull { it.id } ?: return
        if (top <= _state.value.agoraSeen) return
        prefs.edit().putLong("agora.seen", top).apply()
        _state.update { it.copy(agoraSeen = top) }
    }

    /** At pairing: everything before now counts as seen. */
    private fun baselineInbox() { runCatching { prefs.edit().putLong("inbox.seq", Gateway.inbox(0).first).apply() } }

    companion object {
        @Volatile private var instance: LemnosHub? = null
        fun get(c: Context): LemnosHub = instance ?: synchronized(this) { instance ?: LemnosHub(c.applicationContext).also { instance = it } }
    }
}

/** Phone notifications for what Lemnos sends: high severity breaks through, the rest is quiet. */
object LemnosNotify {
    private const val HIGH = "cerberus.high"
    private const val NORMAL = "lemnos.normal"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(HIGH, "Cerberus: urgent", NotificationManager.IMPORTANCE_HIGH).apply { description = "Server alerts that need you now" })
        nm.createNotificationChannel(NotificationChannel(NORMAL, "Pantheon and Lemnos", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Replies from the workers and other server news" })
    }

    fun post(c: Context, item: InboxItem) {
        channels(c)
        val dest = if (item.kind == "alert") "ALERTS" else "AGENTS"
        val open = PendingIntent.getActivity(
            c, item.id.toInt(), Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, dest).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(c, if (item.severity == "high") HIGH else NORMAL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(item.title.ifBlank { if (item.kind == "alert") "Cerberus" else "Pantheon" })
            .setContentText(item.body.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.body.take(600)))
            // Lock screen: the title only; the text stays behind the unlock.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        if (item.severity != "high" && dev.phonerecall.brain.AlarmWatch.get(c).napQuiet()) return // a nap alarm holds the non-urgent ones
        runCatching { c.getSystemService(NotificationManager::class.java).notify("lemnos", item.id.toInt(), n) }
    }
}

/** FCM: the message says only "sync". Anything else is dropped unread; the content comes from Lemnos over mTLS. */
class ZeusMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) = LemnosHub.get(this).registerPush(token)

    override fun onMessageReceived(message: RemoteMessage) {
        if (!SyncPush.isSync(message.data)) return
        val req = OneTimeWorkRequestBuilder<LemnosSyncWorker>().setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST).build()
        WorkManager.getInstance(this).enqueueUniqueWork("lemnos.sync", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }
}

class LemnosSyncWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result = try {
        // The Anthropic webhook's push: a scheduled run waits on a phone tool. Answer it before anything else.
        dev.phonerecall.brain.BrainCore.get(applicationContext).let { core ->
            if (core.vault.has()) runCatching { core.mindHost.locked { answerScheduled() } }.onFailure { dev.phonerecall.clio.Clio.error("mind", it) }
        }
        LemnosHub.get(applicationContext).syncInbox()
        if (LemnosHub.get(applicationContext).paired) runCatching { dev.phonerecall.metron.MetronCore.get(applicationContext).fetch() }.onFailure { dev.phonerecall.clio.Clio.error("lemnos", it) }
        // Iris's money log and her Moneta ops ride the same wake-up.
        runCatching { dev.phonerecall.moneta.MonetaCore.get(applicationContext).syncIrisBlocking() }.onFailure { dev.phonerecall.clio.Clio.error("lemnos", it) }
        // And her part of today's morning brief, if it was asked for and hasn't arrived yet.
        runCatching { dev.phonerecall.brief.BriefCore.pollIris(applicationContext) }.onFailure { dev.phonerecall.clio.Clio.error("lemnos", it) }
        // And Iris's trip.info for Argo.
        runCatching { dev.phonerecall.argo.Argo.syncIris(applicationContext) }.onFailure { dev.phonerecall.clio.Clio.error("argo", it) }
        // And Agora, the agents' room: what Iris, Atlas and the owner said there reaches Athena.
        runCatching { LemnosHub.get(applicationContext).syncAgora() }
        Result.success()
    } catch (e: Exception) {
        dev.phonerecall.clio.Clio.error("lemnos", e)
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    override suspend fun getForegroundInfo(): androidx.work.ForegroundInfo {
        LemnosNotify.channels(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, "lemnos.normal").setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Checking Lemnos").setSilent(true).build()
        return androidx.work.ForegroundInfo(7311, n)
    }
}
