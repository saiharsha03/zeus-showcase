package dev.phonerecall.setup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import com.anthropic.models.messages.MessageParam
import com.fasterxml.jackson.core.type.TypeReference
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.AthenaEngine
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Sealer
import dev.phonerecall.brain.ToolBox
import dev.phonerecall.brain.TurnSink
import dev.phonerecall.clio.Clio
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import dev.phonerecall.mind.AskHub
import dev.phonerecall.mind.BackgroundReads
import dev.phonerecall.mind.GatewayAskTransport
import dev.phonerecall.mind.HeartbeatDigest
import dev.phonerecall.mind.MindNote
import dev.phonerecall.mind.TestMode
import dev.phonerecall.mind.TestProbe
import dev.phonerecall.mind.heartbeat
import dev.phonerecall.mind.long
import dev.phonerecall.mind.stateJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** What the Shakedown page reads. [live] fills in while a run goes; [history] is newest first, the latest report at 0. */
data class ShakeState(
    val running: Boolean = false,
    val stage: String = "",
    val startedAt: Long = 0,
    val live: List<ShakeResult> = emptyList(),
    val history: List<ShakeReport> = emptyList(),
    /** The mic test is running; [micResult] is its last outcome. */
    val mic: Boolean = false,
    val micResult: ShakeResult? = null,
)

/**
 * The shakedown on the phone: Athena gets the SHAKEDOWN prompt as an urgent note and works through her
 * tools while the phone runs its own checks; her report_check answers come back through [TestMode]. Every
 * test artifact is removed at the end, whatever happened. The report goes to Clio and to
 * files/clio/shakedown-latest.json.
 */
class ShakedownHub private constructor(private val app: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sp = app.getSharedPreferences("shakedown", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(ShakeState(history = loadHistory()))
    val state: StateFlow<ShakeState> = _state.asStateFlow()
    private var job: Job? = null

    /** One run's bookkeeping: what Athena reported and which tools she called. */
    private class Live(val id: String, val start: Long) : TestProbe {
        val claims = ConcurrentHashMap<String, ShakeClaim>()
        val tools = ConcurrentHashMap<String, Pair<Boolean, String>>()
        @Volatile var firstTool = 0L
        @Volatile var ended = false
        val cleanups = CopyOnWriteArrayList<Pair<String, () -> Unit>>()
        val clean = dev.phonerecall.mind.Sanitizer()

        override fun report(input: Map<String, Any?>): String = Shakedown.validate(input, !ended).fold(
            { c -> claims[c.id] = c.copy(detail = clean.line(c.detail).orEmpty()); "Recorded ${c.id}." + if (Shakedown.check(c.id)?.by == By.ATHENA) " The phone judges what it can see itself." else "" },
            { "Not recorded: ${it.message}" },
        )

        override fun seen(name: String, input: Map<String, Any?>, isError: Boolean, text: String) {
            if (firstTool == 0L) firstTool = System.currentTimeMillis()
            tools[name] = isError to text
        }

        fun after(what: String, undo: () -> Unit) { cleanups += what to undo }
    }

    init { AskHub.on(Shakedown.ASK_OWNER) { _, _ -> } }

    fun start() {
        if (job?.isActive == true) return
        val live = Live("s" + java.lang.Long.toString(System.currentTimeMillis(), 36), System.currentTimeMillis())
        _state.update { it.copy(running = true, stage = "Starting", startedAt = live.start, live = emptyList()) }
        Clio.info("setup", "shakedown started", live.id)
        job = scope.launch {
            val core = BrainCore.get(app)
            // Anthropic (Delphi and the mind) plus Live at its paid rates: one cap for the whole run.
            val spent = { core.mindHost.spentCents() + dev.phonerecall.voice.LiveLedgerStore(app).load().spendMicros / 10_000 }
            var outcome: ShakeOutcome? = null
            try {
                outcome = ShakeRunner.run(steps(live), spent, { cleanup(live) }) { r ->
                    _state.update { s -> s.copy(live = s.live + r, stage = Shakedown.check(r.id)?.label ?: r.id) }
                }
            } finally {
                withContext(NonCancellable) { finish(live, outcome) }
            }
        }
    }

    fun stop() { job?.cancel() }

    /** The mic test, only when he taps it: its result joins the latest report. */
    fun micTest() {
        if (_state.value.mic || _state.value.running) return
        _state.update { it.copy(mic = true) }
        scope.launch {
            val start = System.currentTimeMillis()
            val r = try {
                ShakeResult("live-mic", Verdict.PASS, gemini { dev.phonerecall.voice.LiveProbe.micTest(app) }, System.currentTimeMillis() - start)
            } catch (e: TestSkipped) { ShakeResult("live-mic", Verdict.SKIP, e.message.orEmpty()) } catch (e: TestFailed) { ShakeResult("live-mic", Verdict.FAIL, e.message.orEmpty(), System.currentTimeMillis() - start) } catch (e: Exception) { ShakeResult("live-mic", Verdict.FAIL, e.message?.take(160) ?: e.javaClass.simpleName) }
            Clio.log("setup", if (r.verdict == Verdict.PASS) dev.phonerecall.clio.Level.INFO else dev.phonerecall.clio.Level.WARN, "shakedown mic test ${r.verdict.name.lowercase()}", r.detail.take(120))
            val h = _state.value.history
            h.firstOrNull()?.let { rep -> save(listOf(rep.copy(results = rep.results.filter { it.id != r.id } + r)) + h.drop(1)) }
            _state.update { it.copy(mic = false, micResult = r) }
        }
    }

    /** A Live check's reasons as the runner's: skipped when it couldn't run, failed otherwise. */
    private suspend fun gemini(block: suspend () -> String): String = try { block() } catch (e: dev.phonerecall.voice.LiveCheckFailed) {
        if (e.skip) throw TestSkipped(e.message.orEmpty()) else throw TestFailed(e.message.orEmpty())
    }

    /** His answer to "did it appear?" on the latest report. */
    fun answer(id: String, yes: Boolean) = scope.launch {
        val h = _state.value.history
        val r = h.firstOrNull() ?: return@launch
        val next = r.copy(seen = r.seen + (id to yes))
        save(listOf(next) + h.drop(1))
        Clio.info("setup", "shakedown $id ${if (yes) "appeared" else "didn't appear"}")
    }

    private fun finish(live: Live, outcome: ShakeOutcome?) {
        live.ended = true
        TestMode.probe = null
        val stopped = outcome == null
        val results = Shakedown.merge(outcome?.results ?: _state.value.live, live.claims)
        val aborted = if (stopped) "Stopped by you" else outcome?.aborted
        val problems = outcome?.cleanup ?: runCatching { cleanup(live) }.getOrDefault(listOf("Cleanup didn't finish"))
        val summary = if (stopped) Shakedown.summary(results, aborted) else athenaSummary(results, aborted, (outcome?.spentCents ?: 0))
        val report = ShakeReport(
            live.id, live.start, System.currentTimeMillis() - live.start, results,
            TestNotify.CHANNELS.associate { it.check to null }, summary, (outcome?.spentCents ?: 0), aborted, problems,
        )
        save(listOf(report) + _state.value.history)
        _state.update { it.copy(running = false, stage = "", live = emptyList()) }
        Clio.info("setup", "shakedown done", summary)
        for (r in results.filter { it.verdict == Verdict.FAIL }) Clio.warn("setup", "shakedown ${r.id} failed", r.detail.take(160))
        problems.forEach { Clio.warn("setup", "shakedown cleanup", it) }
    }

    /** Athena's own words when there is headroom; the plain summary otherwise. */
    private fun athenaSummary(results: List<ShakeResult>, aborted: String?, used: Long): String {
        val plain = Shakedown.summary(results, aborted)
        if (used + 1 > Shakedown.CAP_CENTS) return plain
        val m = BrainCore.get(app).haiku() ?: return plain
        return runCatching {
            m.complete("shakedown", Shakedown.SUMMARY_SYSTEM, Shakedown.scorecard(results) + (aborted?.let { "\nThe run ended early: $it" } ?: ""), 120)
                .lines().firstOrNull { it.isNotBlank() }?.trim()?.take(260)
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: plain
    }

    private fun save(h: List<ShakeReport>) {
        val keep = h.take(HISTORY)
        sp.edit().putString("history", stateJson.writeValueAsString(keep)).apply()
        _state.update { it.copy(history = keep) }
        keep.firstOrNull()?.let { r -> runCatching { File(File(app.filesDir, "clio").apply { mkdirs() }, EXPORT).writeText(stateJson.writerWithDefaultPrettyPrinter().writeValueAsString(r)) } }
    }

    private fun loadHistory(): List<ShakeReport> =
        sp.getString("history", null)?.let { runCatching { stateJson.readValue(it, object : TypeReference<List<ShakeReport>>() {}) }.getOrNull() }.orEmpty()

    /** Everything a run made, removed; then the notifications. Returns what couldn't be undone. */
    private fun cleanup(live: Live): List<String> {
        TestMode.probe = null
        live.ended = true
        val problems = ArrayList<String>()
        for ((what, undo) in live.cleanups.reversed()) runCatching(undo).onFailure { problems += "$what: ${it.message ?: it.javaClass.simpleName}" }
        live.cleanups.clear()
        val nm = app.getSystemService(NotificationManager::class.java)
        TestNotify.CHANNELS.forEach { runCatching { nm.cancel(TestNotify.id(it)) } }
        val core = BrainCore.get(app)
        // Belt and braces: nothing tagged as the test outlives the run.
        core.db.cards(System.currentTimeMillis()).filter { it.title.startsWith(Shakedown.TAG) }.forEach { core.db.deleteCard(it.id) }
        if (core.db.cards(System.currentTimeMillis()).any { it.title.startsWith(Shakedown.TAG) }) problems += "A test card is still on Olympus"
        core.db.all().filter { it.status == ProposalStatus.PENDING && it.proposer == Shakedown.TAG }.forEach { core.fates.reject(it.id) }
        core.changed()
        return problems
    }

    // ---- the steps, in the order they run ----

    private fun steps(live: Live): List<ShakeStep> {
        val core = BrainCore.get(app)
        val host = core.mindHost
        val tele = TeleteHub.get(app)
        fun telete(id: String) = suspend { tele.tests().first { it.id == id }.run() }
        fun athena() { if (live.firstTool == 0L) throw TestSkipped("Athena didn't start the shakedown") }
        fun tool(name: String, extra: (String) -> String? = { null }): suspend () -> String = {
            athena()
            val (err, text) = live.tools[name] ?: throw TestFailed("She didn't call $name")
            if (err) throw TestFailed("It answered with an error: ${text.take(120)}")
            extra(text)?.let { throw TestFailed(it) }
            "Answered (${text.length} characters)"
        }
        val out = ArrayList<ShakeStep>()
        out += ShakeStep("card") {
            val now = System.currentTimeMillis()
            val id = core.db.addCard(Shakedown.CARD_TITLE, "Placed by the shakedown; removed when it ends.", now, now + 3_600_000L)
            live.after("Test card") { core.db.deleteCard(id) }
            core.changed()
            if (core.db.cards(now).none { it.id == id }) throw TestFailed("The card didn't save")
            "Placed; removed when the run ends"
        }
        out += ShakeStep("high-note", timeoutMs = 95_000L) {
            if (!core.vault.has()) throw TestFailed("No Anthropic key in Calypso")
            runCatching { core.spend.check() }.onFailure { throw TestFailed("This month's cap is reached") }
            TestMode.probe = live
            val sentBefore = host.kv.long("batch.last") ?: 0L
            val t0 = System.currentTimeMillis()
            host.offer(MindNote(t0, Shakedown.prompt(live.id), high = true, key = "shakedown-${live.id}"))
            while (live.firstTool == 0L && (host.kv.long("batch.last") ?: 0L) <= sentBefore) {
                if (System.currentTimeMillis() - t0 > 90_000L) throw TestFailed("The urgent note didn't go out in 90 s")
                delay(1_000)
            }
            "Went at once (${(System.currentTimeMillis() - t0 + 500) / 1000} s to her first call)"
        }
        for (c in TestNotify.CHANNELS) out += ShakeStep(c.check) { notify(c) }
        out += ShakeStep("live", timeoutMs = 25_000L, estimateCents = 1) { gemini { dev.phonerecall.voice.LiveProbe.textTurn(app) } }
        out += ShakeStep("hermes", run = telete("hermes"))
        out += ShakeStep("praxis", run = telete("praxis"))
        out += ShakeStep("chronos-read") {
            if (!TeleteHubPerms.granted(app, Manifest.permission.READ_CALENDAR)) throw TestFailed("Calendar isn't allowed")
            val now = System.currentTimeMillis()
            "${BackgroundReads(app, core).relevantEvents(now, now + 7 * DAY).size} events in the next week"
        }
        out += ShakeStep("chronos-event") { calendarEvent(live) }
        out += ShakeStep("google-birthdays") {
            if (dev.phonerecall.data.GoogleCalendarAuth(app).token() == null) throw TestFailed("Not allowed yet") else "Allowed (nothing written)"
        }
        out += ShakeStep("argo") {
            val d = dev.phonerecall.argo.ArgoStore.load(app)
            "${dev.phonerecall.argo.Argo.read(app).size} trips, ${d.inquiries.size} open lines of evidence"
        }
        out += ShakeStep("moneta") { moneta() }
        out += ShakeStep("odyssey-fix", timeoutMs = 35_000L, run = telete("odyssey-fix"))
        out += ShakeStep("odyssey-drive") { driveDryRun() }
        out += ShakeStep("weather", run = telete("weather"))
        out += ShakeStep("tomtom", run = telete("tomtom"))
        out += ShakeStep("spotify", run = telete("spotify"))
        out += ShakeStep("hestia", run = telete("hestia"))
        out += ShakeStep("iaso") { iaso() }
        out += ShakeStep("alexandria") {
            val search = telete("alexandria")()
            val dims = dev.phonerecall.memory.GemmaEmbedder.get(app)?.embed("Zeus shakedown test")?.size
            search + if (dims != null) "; embedding works ($dims dimensions)" else "; by words only (EmbeddingGemma isn't loaded)"
        }
        out += ShakeStep("oneiroi") { "${dev.phonerecall.oneiroi.Oneiroi.get(app).facts.live().size} live facts read" }
        out += ShakeStep("clio") {
            val r = telete("clio")()
            val f = Clio.get()?.export() ?: throw TestFailed("Clio isn't running")
            if (!f.isFile || f.length() == 0L) throw TestFailed("The export came out empty")
            "$r; exported ${f.length() / 1024} KB"
        }
        out += ShakeStep("mnemosyne", timeoutMs = 60_000L) { mnemosyne() }
        out += ShakeStep("lemnos") {
            paired()
            if (!Gateway.ping()) throw TestFailed("The gateway didn't answer")
            val s = Gateway.sessions().firstOrNull() ?: return@ShakeStep "Gateway up; no terminal session to ticket"
            if (Gateway.termTicket(s.name, readOnly = true).isBlank()) throw TestFailed("No terminal ticket came back")
            "Gateway up; a read-only terminal ticket was issued (not opened)"
        }
        out += ShakeStep("cerberus") {
            paired()
            val week = System.currentTimeMillis() / 1000.0 - 7 * 86_400
            val a = Gateway.alerts().filter { it.ts >= week }
            "${a.size} alerts this week, ${a.count { !it.ack }} not acknowledged"
        }
        out += ShakeStep("metron") {
            val m = dev.phonerecall.metron.MetronCore.get(app)
            if (DeviceKeys.paired(app)) runCatching { m.fetch() }.onFailure { throw TestFailed("Lemnos didn't send usage: ${it.message}") }
            m.words().lines().first().take(140)
        }
        out += ShakeStep("widgets") {
            val r = telete("widgets")()
            dev.phonerecall.widgets.WidgetBus.refreshFromDisk(app, force = true)
            "$r; refreshed"
        }
        // Athena's turn: keep driving her session until she ends it (the flush worker may have started it).
        out += ShakeStep("mind-turn", timeoutMs = 150_000L, estimateCents = 15) {
            athena()
            val sid = host.locked { currentSession() } ?: throw TestFailed("No session this month")
            var calls = 0
            while (true) {
                val r = host.locked { drive(sid) }
                calls += r.toolCalls
                host.locked { account(sid) }
                if (r.stop == "budget_reached") throw TestFailed("Her session hit its budget")
                if (r.stop == "terminated") throw TestFailed("Her session ended")
                if (live.claims.containsKey("mind-turn") || (r.stop != null && r.stop != "requires_action" && r.toolCalls == 0)) break
                delay(2_000)
            }
            "${live.tools.size} tools called, done in ${(System.currentTimeMillis() - live.start) / 1000} s"
        }
        for ((name, id) in Shakedown.READ_TOOLS) out += ShakeStep(id, run = if (id == "tool-cards") tool(name) { t -> if (Shakedown.CARD_TITLE in t) null else "read_cards answered without the test card" } else tool(name))
        out += ShakeStep("ask-iris") {
            athena()
            val p = myAsk(live) ?: throw TestFailed(live.tools["ask_worker"]?.second?.take(120)?.let { "Not asked: $it" } ?: "She didn't ask Iris")
            "Asked (ask ${p.id})"
        }
        out += ShakeStep("agora-post") {
            athena(); paired()
            agoraPost(live)?.let { "In Agora (#${it.id})" + if (it.kind == "held") ", held while agent-to-agent is paused" else "" }
                ?: throw TestFailed(live.tools["post_agora"]?.second?.take(120) ?: "She didn't post")
        }
        out += ShakeStep("iris-reply", timeoutMs = Shakedown.IRIS_MS + 10_000L) {
            val p = myAsk(live) ?: throw TestSkipped("Nothing was asked")
            var reply: String? = null
            while (reply == null) {
                reply = runCatching { GatewayAskTransport.replyTo("iris", p.messageId) }.getOrNull()
                if (reply == null && System.currentTimeMillis() - p.sentAt > Shakedown.IRIS_MS) throw TestFailed("Asked; no answer in ${Shakedown.IRIS_MS / 1000} s")
                if (reply == null) delay(5_000)
            }
            "Iris: " + dev.phonerecall.mind.AskEnvelope.parse(reply, p.id).lines().joinToString(" ").take(100)
        }
        out += ShakeStep("agora-answer", timeoutMs = Shakedown.AGORA_MS + 10_000L) {
            val post = agoraPost(live) ?: throw TestSkipped("Nothing was posted")
            var m: dev.phonerecall.lemnos.ChatMsg? = null
            while (m == null) {
                m = Gateway.history("agora", after = post.id, limit = 50).firstOrNull { it.sender == "iris" || it.sender == "atlas" }
                if (m == null && System.currentTimeMillis() - (post.ts * 1000).toLong() > Shakedown.AGORA_MS) throw TestFailed("No answer in ${Shakedown.AGORA_MS / 1000} s" + if (post.kind == "held") " (agent-to-agent is paused)" else "")
                if (m == null) delay(5_000)
            }
            "${m.sender.replaceFirstChar { it.uppercase() }} answered: ${m.text.lines().first().take(80)}"
        }
        out += ShakeStep("group") {
            paired()
            Gateway.history("group", limit = 1)
            val n = dev.phonerecall.lemnos.Agora.groupNote(dev.phonerecall.lemnos.ChatMsg(0, "group", "owner", Shakedown.TAG, 0.0, "text", null, null))
            if (!n.high) throw TestFailed("A group message wouldn't reach her at once")
            "The group chat loads and your messages reach her at once (nothing posted)"
        }
        out += ShakeStep("scheduled") {
            val last = host.locked { book.jobs().mapNotNull { j -> lastRun(j.name)?.let { j.name to it } } }.maxByOrNull { it.second.first }
                ?: throw TestSkipped("No scheduled run seen yet")
            val (name, run) = last
            if (System.currentTimeMillis() - run.first > 26 * 3_600_000L) throw TestSkipped("No scheduled run in the last day (last: $name)")
            if (run.second != "ok") throw TestFailed("$name's last run: ${run.second}")
            "$name ran ${(System.currentTimeMillis() - run.first) / 3_600_000L} h ago; its tool calls were answered"
        }
        out += ShakeStep("heartbeat", timeoutMs = 90_000L, estimateCents = 6) {
            if (!core.vault.has()) throw TestSkipped("No Anthropic key")
            val digest = HeartbeatDigest(notes = listOf("Zeus shakedown ${live.id}: a test beat. Nothing changed; reply ${dev.phonerecall.mind.HeartbeatPrompt.OK}."))
            val r = host.locked { heartbeat(digest, System.currentTimeMillis()) { host.spentCents() } }
            if (r.outcome.startsWith("no session") || r.outcome == "paused at cap") throw TestFailed(r.outcome.replaceFirstChar { it.uppercase() })
            "Outcome: ${r.outcome}, ${ShakeRunner.cents(r.cents)}"
        }
        out += ShakeStep("delphi", timeoutMs = 60_000L, estimateCents = 3) {
            if (!core.vault.has()) throw TestSkipped("No Anthropic key")
            val engine = AthenaEngine(core.gateway, ToolBox(BackgroundReads(app, core), core.fates), core.delphiSpend)
            val history = mutableListOf(MessageParam.builder().role(MessageParam.Role.USER).content("Zeus shakedown test from Telete: reply with the one word ready. Call no tools.").build())
            var said = ""
            engine.run(history, BrainModel.HAIKU, null, object : TurnSink { override fun assistant(text: String) { if (text.isNotBlank()) said = text } })
            if (said.isBlank()) throw TestFailed("She answered with nothing") else "She said: ${said.take(60)}"
        }
        out += ShakeStep("proposal") { proposal(live) }
        return out
    }

    private fun paired() { if (!DeviceKeys.paired(app)) throw TestSkipped("Lemnos isn't paired") }

    private fun myAsk(live: Live) = AskHub.get(app).pending().lastOrNull { it.owner == Shakedown.ASK_OWNER && it.sentAt >= live.start }

    private fun agoraPost(live: Live) = runCatching {
        Gateway.history("agora", limit = 50).lastOrNull { it.sender == "athena" && it.ts * 1000 >= live.start && "Zeus shakedown" in it.text }
    }.getOrNull()

    private fun notify(c: TestChannel): String {
        if (!TeleteHubPerms.granted(app, Manifest.permission.POST_NOTIFICATIONS)) throw TestFailed("Notifications aren't allowed")
        val nm = app.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(c.channel) == null) nm.createNotificationChannel(NotificationChannel(c.channel, c.name, c.importance))
        if (nm.getNotificationChannel(c.channel)?.importance == NotificationManager.IMPORTANCE_NONE) throw TestFailed("${c.name} is turned off in Android's settings")
        val open = PendingIntent.getActivity(app, TestNotify.id(c), Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "SETUP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(app, c.channel)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("${Shakedown.TAG}: ${c.title.replaceFirstChar { it.lowercase() }}")
            .setContentText("From the shakedown. It goes away by itself in a minute.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(Shakedown.NOTIFY_MS)
        when (c.check) {
            "notify-high" -> b.setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
            "notify-birthday" -> b.setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER)
            // The same promotion extras as the real Now Bar (SDK 35 build, so set as platform extras).
            "notify-nowbar" -> b.setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
                .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true); putString("android.shortCriticalText", "Test") })
        }
        nm.notify(TestNotify.id(c), b.build())
        if (nm.activeNotifications.none { it.id == TestNotify.id(c) }) throw TestFailed("Android didn't show it")
        return "Posted on ${c.name}. Did you see it? Answer below."
    }

    private fun calendarEvent(live: Live): String {
        if (!TeleteHubPerms.granted(app, Manifest.permission.WRITE_CALENDAR)) throw TestSkipped("Calendar writes aren't allowed")
        val cal = dev.phonerecall.brain.AndroidLocalState.writable(app).let { l -> l.firstOrNull { '@' in it.account } ?: l.firstOrNull() } ?: throw TestFailed("No calendar of yours takes events")
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).plusDays(1).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        val uri = app.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, cal.id)
            put(CalendarContract.Events.TITLE, "${Shakedown.TAG} event")
            put(CalendarContract.Events.DTSTART, at)
            put(CalendarContract.Events.DTEND, at + 5 * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            put(CalendarContract.Events.HAS_ALARM, 0)
        }) ?: throw TestFailed("The calendar didn't take the event")
        val delete = { app.contentResolver.delete(uri, null, null); Unit }
        live.after("Test calendar event") { delete() }
        delete()
        val gone = app.contentResolver.query(uri, arrayOf(CalendarContract.Events.DELETED), null, null, null)?.use { c -> !c.moveToFirst() || c.getInt(0) == 1 } ?: true
        if (!gone) throw TestFailed("Made it, but it wasn't deleted")
        return "Made and deleted in your calendar"
    }

    /** Two alerts for one purchase into a throwaway in-memory store: the second must fold into the first. */
    private fun moneta(): String {
        val store = dev.phonerecall.moneta.MonetaStore(app, PlainSealer, null)
        try {
            val ops = dev.phonerecall.moneta.MonetaOps(store)
            val now = System.currentTimeMillis()
            val text = "You made a $12.34 transaction with WHOLEFDS SPR 10234 on Oct 19, 2026 at 6:12 PM ET."
            val a = dev.phonerecall.moneta.BankParser.parse("com.chase.sig.android", "", "Chase Sapphire", text, now) ?: throw TestFailed("The sample didn't parse")
            val b = dev.phonerecall.moneta.BankParser.parse("com.chase.sig.android", "", "Chase Sapphire", text, now + 60_000L) ?: throw TestFailed("The second sample didn't parse")
            dev.phonerecall.moneta.Reconcile.insert(store, ops, a)
            val (_, folded) = dev.phonerecall.moneta.Reconcile.insert(store, ops, b)
            val shown = store.txns(0).count { it.mergedInto == null }
            if (!folded || shown != 1) throw TestFailed("The repeat alert wasn't folded ($shown rows)")
            return "Parsed ${a.merchant}, ${a.category.name.lowercase()}; the repeat folded into it (sandbox, nothing kept)"
        } finally { store.close() }
    }

    private fun driveDryRun(): String {
        val svc = runCatching { app.packageManager.getServiceInfo(ComponentName(app, dev.phonerecall.odyssey.DriveService::class.java), 0) }.getOrNull() ?: throw TestFailed("The drive service isn't in this build")
        if (!TeleteHubPerms.granted(app, Manifest.permission.ACCESS_FINE_LOCATION)) throw TestFailed("Location isn't allowed, so it couldn't start")
        if (!TeleteHubPerms.granted(app, Manifest.permission.ACTIVITY_RECOGNITION)) throw TestFailed("Physical activity isn't allowed, so drives aren't noticed")
        val on = runCatching { dev.phonerecall.odyssey.OdysseyStore.load(app).settings.on }.getOrDefault(false)
        return "${svc.name.substringAfterLast('.')} would start and stop (dry run, not started)" + if (on) "" else "; Odyssey is off"
    }

    private suspend fun iaso(): String {
        val hc = dev.phonerecall.iaso.RealHc.of(app) ?: throw TestFailed("Health Connect isn't on the phone")
        val granted = hc.granted()
        if (androidx.health.connect.client.permission.HealthPermission.getReadPermission(androidx.health.connect.client.records.StepsRecord::class) !in granted) throw TestFailed("Steps aren't allowed in Health Connect")
        val now = java.time.Instant.now()
        hc.read(androidx.health.connect.client.records.StepsRecord::class, now.minusSeconds(7 * 86_400L), now)
        return "Steps read from Health Connect (nothing kept or shown)"
    }

    /** The backup built into a counter and thrown away: nothing written, nothing sent. */
    private fun mnemosyne(): String {
        dev.phonerecall.mnemosyne.Mnemosyne.refresh(app)
        val last = dev.phonerecall.mnemosyne.Mnemosyne.state.value.last
        val sink = Counter()
        val report = dev.phonerecall.mnemosyne.Exporter(dev.phonerecall.mnemosyne.Layout(app), dev.phonerecall.mnemosyne.KeystoreBank(), null)
            .export(sink, mapOf("id" to "dry-run", "created" to System.currentTimeMillis(), "kind" to "dry-run"))
        val bad = report.parts.filter { it.error != null }.map { it.name }
        if (bad.isNotEmpty()) throw TestFailed("Couldn't read: ${bad.joinToString(", ")}")
        val lastWords = when {
            last == null -> "no backup has run yet"
            last.ok -> "the last one worked ${(System.currentTimeMillis() - last.at) / 3_600_000L} h ago"
            else -> "the last one failed: ${last.error.orEmpty().take(60)}"
        }
        if (last != null && !last.ok) throw TestFailed("Dry run fine (${sink.n / 1024} KB); $lastWords")
        return "Dry run: ${report.parts.size} parts, ${sink.n / 1024} KB, nothing uploaded; $lastWords"
    }

    private fun proposal(live: Live): String {
        val core = BrainCore.get(app)
        val fates = core.fates
        val step = RawStep(ProposalKind.CREATE_CARD, mapOf("title" to "${Shakedown.TAG} proposal", "body" to "Undone at once by the shakedown.", "hours" to "1"))
        val created = when (val r = fates.propose(listOf(step), "${Shakedown.TAG} proposal", null, proposer = Shakedown.TAG, onRequest = true)) {
            is ProposeResult.Invalid -> throw TestFailed("Not proposed: ${r.reason}")
            is ProposeResult.Created -> r
        }
        val id = created.proposal.id
        live.after("Test proposal") {
            val p = core.db.get(id) ?: return@after
            if (p.status == ProposalStatus.PENDING) fates.reject(id) else if (p.status == ProposalStatus.CONFIRMED && p.undoable) fates.undo(id)
        }
        val done = created.decision ?: fates.confirm(id, fates.bind(created.proposal))
        if (done !is Decision.Done) throw TestFailed("Confirm didn't run: ${(done as? Decision.Failed)?.reason ?: (done as? Decision.Refused)?.reason ?: done.javaClass.simpleName}")
        val undone = fates.undo(id)
        if (undone !is Decision.Undone || undone.proposal.outcome != "Undone") throw TestFailed("Confirmed, but the undo didn't take")
        if (core.db.cards(System.currentTimeMillis()).any { it.title == "${Shakedown.TAG} proposal" }) throw TestFailed("Undone, but its card is still there")
        core.changed()
        return "Proposed, confirmed and undone (#$id)"
    }

    private class Counter : OutputStream() {
        var n = 0L
        override fun write(b: Int) { n++ }
        override fun write(b: ByteArray, off: Int, len: Int) { n += len }
    }

    /** The sandbox store needs no Keystore key: nothing in it outlives the check. */
    private object PlainSealer : Sealer {
        override fun seal(plain: String) = plain
        override fun open(sealed: String) = sealed
        override fun sealBytes(plain: ByteArray) = plain
        override fun openBytes(sealed: ByteArray) = sealed
    }

    companion object {
        private const val DAY = 86_400_000L
        const val HISTORY = 10
        const val EXPORT = "shakedown-latest.json"

        @Volatile private var instance: ShakedownHub? = null
        fun get(c: Context): ShakedownHub = instance ?: synchronized(this) { instance ?: ShakedownHub(c.applicationContext).also { instance = it } }
    }
}

private object TeleteHubPerms {
    fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
}
