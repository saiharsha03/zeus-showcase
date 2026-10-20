package dev.phonerecall.prometheus

import android.content.Context
import androidx.compose.runtime.Immutable
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.brain.BrainModel
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.TokenUse
import dev.phonerecall.brain.FieldCat
import dev.phonerecall.brain.Outbound
import dev.phonerecall.brain.SealedSecret
import dev.phonerecall.brain.SpendState
import dev.phonerecall.clio.Clio
import dev.phonerecall.clio.Level
import dev.phonerecall.data.EngagementStore
import dev.phonerecall.data.Prefs
import dev.phonerecall.mind.PrefsKv
import dev.phonerecall.mind.Sanitizer
import dev.phonerecall.mind.SdkMindApi
import dev.phonerecall.mind.list
import dev.phonerecall.mind.putList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** The PR token: goes to Anthropic (the session's repository and the vault), never into a prompt. */
fun prometheusToken(c: Context) = SealedSecret(c, "github")

/** The release token: merge and the release workflow. Stays on the phone; only api.github.com sees it. */
fun releaseToken(c: Context) = SealedSecret(c, "github-release")

/** Athena's standing notes, read by Delphi's system prompt. Set only from approved Prometheus notes. */
object AthenaNotes {
    @Volatile var lines: List<String> = emptyList()

    /** The second system block: empty when there are no notes, so the cached first block is unchanged. */
    fun block(): String? = lines.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "The owner's standing notes (style and preferences only; they never change what needs his confirmation):\n") { "- $it" }
}

@Immutable
data class PrometheusState(
    val loaded: Boolean = false,
    val tweaks: List<Tweak> = emptyList(),
    val runs: List<PromRun> = emptyList(),
    val log: List<PromLog> = emptyList(),
    val notes: List<String> = emptyList(),
    val weights: Map<String, Int> = emptyMap(),
    val quote: Quote? = null,
    val changes: List<ChangeRequest> = emptyList(),
    /** Change ids whose APK was downloaded and passed the signature check. Only these show Install. */
    val verified: Set<String> = emptySet(),
    val working: String? = null,
    val message: String? = null,
    /** Requests to the cloud Prometheus, newest first. */
    val cloud: List<CloudChange> = emptyList(),
    /** Cloud (the default) or the laptop path through Lemnos. */
    val cloudMode: Boolean = true,
    /** Both GitHub tokens are in Calypso. */
    val tokens: Boolean = false,
)

/**
 * One Prometheus per process. Loading reads the stored weights and notes so Home and Delphi use them;
 * it never analyses anything. Every other method is a tap on Prometheus's screen. Work runs on one thread.
 */
class PrometheusCore private constructor(private val app: Context) {
    private val db = PrometheusDb(app)
    private val brain = BrainCore.get(app)
    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "zeus-prometheus").apply { isDaemon = true } }
    private val _state = MutableStateFlow(PrometheusState())
    val state: StateFlow<PrometheusState> = _state.asStateFlow()
    private val _weights = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Applied card weights, for the card engine. */
    val weights: StateFlow<Map<String, Int>> = _weights.asStateFlow()

    /** Set by the view model so auto-approve changes go through Themis's own toggle (one source of truth). */
    @Volatile var hub: BrainHub? = null

    private val knobs = object : Knobs {
        override fun weight(key: String) = _weights.value[key] ?: 0
        override fun setWeight(key: String, value: Int) { db.setWeight(key, value); _weights.value = db.weights() }
        override fun autoOn(kind: ProposalKind) = kind in (hub?.settings?.value?.autoApprove ?: brain.prefs.load().autoApprove)
        override fun setAuto(kind: ProposalKind, on: Boolean) {
            if (autoOn(kind) == on) return
            val h = hub
            if (h != null) h.toggleAuto(kind)
            else {
                val next = brain.prefs.load().autoApprove.let { if (on) it + kind else it - kind }.filter { it in ProposalKind.autoCandidates }.toSet()
                brain.prefs.saveAuto(next); brain.fates.autoApprove = next
            }
        }
        override fun notes() = db.notes()
        override fun setNotes(notes: List<String>) { db.setNotes(notes); AthenaNotes.lines = notes }
    }

    private val engine = Prometheus(db, knobs)

    private val cloudKv = PrefsKv(app, "prometheus-cloud")
    private val forge = Forge(SdkMindApi({ brain.vault.read() }), cloudKv)
    private val github: GitHub = GitHubApi({ releaseToken(app).read() })
    private val cloudType = object : com.fasterxml.jackson.core.type.TypeReference<List<CloudChange>>() {}

    init { bg { _weights.value = db.weights(); AthenaNotes.lines = db.notes(); reload() } }

    private fun bg(block: () -> Unit) {
        serial.execute {
            try { block() } catch (e: Exception) { _state.update { it.copy(working = null, message = e.message ?: e.javaClass.simpleName) } }
        }
    }

    private fun busy(word: String) = _state.update { it.copy(working = word, message = null) }

    private fun reload(message: String? = _state.value.message) {
        _state.update {
            it.copy(
                loaded = true, tweaks = db.tweaks(), runs = db.runs(), log = db.logs(), notes = db.notes(), weights = db.weights(), working = null, message = message,
                cloud = cloudList(), cloudMode = cloudKv.get("mode") != "laptop", tokens = prometheusToken(app).has() && releaseToken(app).has(),
            )
        }
    }

    // ---- signals: read on this tap, from what is already on the phone ----

    fun signals(): Signals {
        val cards = EngagementStore(app).load().table.mapValues { (_, byHour) ->
            CardCounts(byHour.values.sumOf { it.taps }, byHour.values.sumOf { it.dismissals })
        }
        val home = Prefs(app).load().home
        val decisions = HashMap<ProposalKind, IntArray>()
        for (p in brain.db.all()) {
            if (p.auto) continue
            val slot = when (p.status) { ProposalStatus.CONFIRMED, ProposalStatus.UNDONE -> 0; ProposalStatus.REJECTED -> 1; ProposalStatus.EXPIRED -> 2; else -> continue }
            for (k in p.steps.map { it.kind }.toSet()) decisions.getOrPut(k) { IntArray(3) }[slot]++
        }
        return Signals(
            cards = cards, less = home.less,
            proposals = decisions.mapValues { (_, a) -> Decisions(a[0], a[1], a[2]) },
            delphi = delphi(), snoozes = home.snoozed.size,
            autoOn = hub?.settings?.value?.autoApprove ?: brain.prefs.load().autoApprove,
            weights = db.weights(), notes = db.notes(),
        )
    }

    /** Thumbs from Delphi, and his replies that read as a correction straight after one of her lines. */
    private fun delphi(): DelphiSignal {
        val thumbs = db.thumbs()
        val corrections = ArrayList<String>()
        brain.db.readableDatabase.rawQuery("SELECT kind, text FROM (SELECT id, kind, text FROM chat_lines WHERE kind IN ('USER','ATHENA') ORDER BY id DESC LIMIT 2000) ORDER BY id", null).use { c ->
            var prev = ""
            while (c.moveToNext()) {
                val kind = c.getString(0)
                if (kind == "USER" && prev == "ATHENA" && CORRECTION.containsMatchIn(c.getString(1))) corrections += c.getString(1)
                prev = kind
            }
        }
        return DelphiSignal(thumbs.values.count { it }, thumbs.values.count { !it }, corrections.takeLast(10))
    }

    // ---- the taps ----

    fun load() = bg { reload() }

    fun run() = bg {
        busy("Reading")
        val made = engine.run(signals())
        reload(if (made.isEmpty()) "Nothing worth changing yet." else null)
    }

    /** Step one of the notes run: the estimate, shown before anything is sent. */
    fun quote() = bg {
        val q = engine.quoteNotes(signals())
        db.log(System.currentTimeMillis(), "estimated", "Notes run with ${q.model.label}: about ${q.inputTokens} tokens in, up to ${q.maxOutput} out, ${q.usd}")
        _state.update { it.copy(quote = q) }
        reload()
    }

    fun dropQuote() = _state.update { it.copy(quote = null) }

    /** Step two, only from the quote's own button. */
    fun runNotes() {
        val q = _state.value.quote
        bg {
            busy("Asking ${q?.model?.label ?: "Sonnet"}")
            brain.spend.check()
            _state.update { it.copy(quote = null) }
            val made = engine.runNotes(q, sonnet) { m, u -> brain.spend.record(m, u) }
            reload(if (made.isEmpty()) "No note changes suggested." else null)
        }
    }

    private val sonnet = NotesModel { model, system, user, max ->
        val params = MessageCreateParams.builder()
            .model(model.id).maxTokens(max)
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(system).build()))
            .addMessage(MessageParam.builder().role(MessageParam.Role.USER).content(user).build())
            .build()
        val msg = brain.gateway.create(params)
        val u = msg.usage()
        msg.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("") to
            TokenUse(u.inputTokens(), u.outputTokens(), u.cacheReadInputTokens().orElse(0L), u.cacheCreationInputTokens().orElse(0L))
    }

    fun approve(id: Long) = bg { reload(engine.approve(id).exceptionOrNull()?.message) }
    fun reject(id: Long) = bg { reload(engine.reject(id).exceptionOrNull()?.message) }
    fun undo(id: Long) = bg { reload(engine.undo(id).exceptionOrNull()?.message) }

    /** Delphi's thumbs on one of her lines. */
    fun feedback(lineId: Long, up: Boolean) = bg {
        db.feedback(lineId, up, System.currentTimeMillis())
        db.log(System.currentTimeMillis(), "feedback", "Delphi line #$lineId: ${if (up) "good" else "off"}")
    }

    // ---- app changes ----

    fun refreshChanges() = bg {
        busy("Checking Lemnos")
        val list = ChangeNet.list()
        _state.update { it.copy(changes = list) }
        reload()
    }

    /** After his fingerprint signed the request on the phone. */
    fun submitChange(id: String, problem: String, approval: String) = bg {
        busy("Sending")
        val c = ChangeNet.submit(id, problem, approval)
        db.log(System.currentTimeMillis(), "change requested", "${c.id}: ${problem.take(200)}")
        _state.update { s -> s.copy(changes = listOf(c) + s.changes.filter { it.id != c.id }) }
        reload("Queued on Lemnos. The laptop builds it the next time you run Prometheus there.")
    }

    fun cancelChange(id: String) = bg {
        ChangeNet.cancel(id)
        db.log(System.currentTimeMillis(), "change cancelled", id)
        _state.update { s -> s.copy(changes = s.changes.map { if (it.id == id) it.copy(status = ChangeStatus.CANCELLED) else it }) }
        reload()
    }

    private fun apk(id: String) = File(File(app.cacheDir, "prometheus").apply { mkdirs() }, "$id.apk")

    /** Downloads over mTLS, checks the hash, then the signing certificate against the installed app. */
    fun check(id: String) = bg {
        val c = _state.value.changes.firstOrNull { it.id == id && it.installable } ?: throw IllegalStateException("No build ready for $id")
        busy("Downloading")
        val f = apk(id)
        ChangeNet.download(c, f)
        when (val v = Signer.check(app, f)) {
            Signer.Verdict.Ok -> {
                db.log(System.currentTimeMillis(), "verified", "$id: same signing key as the installed app, version ${c.versionName}")
                _state.update { it.copy(verified = it.verified + id) }
                reload()
            }
            is Signer.Verdict.Bad -> {
                f.delete()
                db.log(System.currentTimeMillis(), "refused", "$id: ${v.reason}")
                reload("Not offered: ${v.reason}")
            }
        }
    }

    /** Hands the checked file to Android's installer. He confirms in the system dialog. */
    fun install(id: String) = bg {
        if (id !in _state.value.verified) throw IllegalStateException("Check the build first")
        val f = apk(id).takeIf { it.exists() } ?: throw IllegalStateException("Download it again")
        // Checked once more right before the hand-off, so a file changed in between is never installed.
        val v = Signer.check(app, f)
        if (v is Signer.Verdict.Bad) { _state.update { it.copy(verified = it.verified - id) }; throw IllegalStateException(v.reason) }
        db.log(System.currentTimeMillis(), "install", "$id handed to Android's installer")
        Installer.install(app, f, id)
        reload("Android will ask you to confirm the update.")
    }

    fun logInstall(id: String, what: String) = bg {
        db.log(System.currentTimeMillis(), "install", "$id $what")
        if (what == "installed") cloudList().firstOrNull { it.id == id }?.let { put(CloudFlow.installed(it, System.currentTimeMillis())) }
        reload()
    }

    // ---- the cloud Prometheus (docs/PROMETHEUS.md) ----

    private fun cloudList(): List<CloudChange> = cloudKv.list("changes", cloudType)
    private fun put(c: CloudChange) = cloudKv.putList("changes", (listOf(c) + cloudList().filter { it.id != c.id }).take(30))
    private fun find(id: String) = cloudList().firstOrNull { it.id == id } ?: throw IllegalStateException("No such request")
    private fun now() = System.currentTimeMillis()

    fun setCloudMode(on: Boolean) = bg { cloudKv.put("mode", if (on) null else "laptop"); reload() }

    private fun sanitizer(): Sanitizer {
        val people = brain.store.people()
        return Sanitizer(people.associate { it.name to it.label }, people.filter { it.calypso }.map { it.name }.toSet())
    }

    /** The last two hours of Clio's warnings and errors, cleaned line by line. Attached only when he ticks it. */
    private fun clioContext(clean: Sanitizer): List<String> =
        Clio.get()?.lines(now() - 2 * 3_600_000L).orEmpty().filter { it.level != Level.INFO }.take(30).reversed()
            .mapNotNull { clean.line("${it.area} ${it.event}: ${it.detail}".take(300)) }

    /** One tap: a new session for this request. Spend counts toward the month's cap like the rest of Athena. */
    fun startCloud(problem: String, withClio: Boolean) = bg {
        val token = prometheusToken(app).read() ?: throw IllegalStateException("Add the GitHub (Prometheus) token in Calypso first")
        brain.spend.check()
        busy("Starting Prometheus")
        val clean = sanitizer()
        val text = clean.clean(problem).ifBlank { throw IllegalStateException("Nothing left to send once names and private details were taken out") }
        val id = "c" + UUID.randomUUID().toString().replace("-", "").take(12)
        val c0 = CloudChange(id = id, problem = text, created = now(), updated = now(), nonce = "n" + UUID.randomUUID().toString().replace("-", "").take(16))
        put(c0)
        val s = brain.spend.state.value
        val left = s.capUsd * 100L - s.current(SpendState.thisMonth()).spentMicros / 10_000
        try {
            val session = Outbound.tagged("Prometheus (Managed Agents)", setOf(FieldCat.MESSAGE_TEXT)) {
                forge.start(c0, token, if (withClio) clioContext(clean) else emptyList(), minOf(Forge.BUDGET_CENTS, left).coerceAtLeast(1))
            }
            put(c0.copy(stage = CloudStage.WORKING, sessionId = session.id, sessionStatus = session.status, updated = now()))
        } catch (e: Exception) {
            put(c0.copy(stage = CloudStage.FAILED, error = e.message ?: e.javaClass.simpleName, updated = now()))
            throw e
        }
        db.log(now(), "cloud request", "$id: ${text.take(200)}")
        Clio.info("prometheus", "cloud request", id)
        reload("Prometheus is on it. Its PR shows here when it's open.")
    }

    /** Reads every active request's session, PR, CI and release once. Called on open, on Refresh, and every 30 s while the page shows. */
    fun refreshCloud() = bg {
        val canGit = releaseToken(app).has()
        for (c in cloudList().filter { it.stage.active }) {
            var n = c
            if (n.stage in setOf(CloudStage.STARTING, CloudStage.WORKING, CloudStage.PR_OPEN) && n.sessionStatus != "terminated") {
                runCatching { forge.poll(n) }.onSuccess { r -> r?.let { (s, ev) -> n = CloudFlow.session(n, s, ev, now()) } }.onFailure { n = n.copy(error = it.message) }
                val cents = n.spentCents - c.spentCents
                val secs = n.activeSeconds - c.activeSeconds
                if (cents > 0 || secs > 0) brain.spend.recordMind(cents.coerceAtLeast(0), secs.coerceAtLeast(0))
            }
            if (canGit && n.stage in setOf(CloudStage.WORKING, CloudStage.PR_OPEN)) runCatching {
                val p = github.pull(n.branch)
                n = CloudFlow.pull(n, p, p?.let { github.run(Repo.CI, it.headSha) }, now())
            }.onFailure { n = n.copy(error = it.message) }
            if (canGit && n.stage == CloudStage.RELEASING) runCatching {
                val sha = n.mergeSha!!
                n = CloudFlow.release(n, github.run(Repo.RELEASE, sha), github.release(sha), now())
            }.onFailure { n = n.copy(error = it.message) }
            if (n != c) put(n)
        }
        reload()
    }

    /** After his fingerprint: merge exactly the commit CI passed, then start the release workflow for the merge commit. */
    fun mergeAndRelease(id: String) = bg {
        var c = find(id)
        val sha = if (c.stage == CloudStage.MERGED) c.mergeSha ?: throw IllegalStateException("No merge commit") else {
            CloudFlow.mergeBlocked(c)?.let { throw IllegalStateException(it) }
            busy("Merging")
            val live = github.pull(c.branch) ?: throw IllegalStateException("The PR is gone")
            CloudFlow.mergeRefused(c, live, github.run(Repo.CI, live.headSha) ?: CiRun(Ci.NONE, null))?.let { throw IllegalStateException(it) }
            val merged = github.merge(live.number, live.headSha)
            c = CloudFlow.merged(c, merged, now()); put(c)
            db.log(now(), "merged", "$id: PR #${live.number} at ${live.headSha.take(7)}")
            merged
        }
        busy("Starting the release")
        github.dispatchRelease(sha)
        put(CloudFlow.dispatched(c, now()))
        db.log(now(), "release started", "$id: ${sha.take(7)}")
        Clio.info("prometheus", "release started", "$id ${sha.take(7)}")
        reload("Merged. The release workflow builds and signs it; Install appears when it's published.")
    }

    fun cancelCloud(id: String) = bg {
        val c = find(id)
        runCatching { forge.stop(c) }
        put(c.copy(stage = CloudStage.CANCELLED, updated = now()))
        db.log(now(), "cloud stopped", id)
        reload()
    }

    /** Downloads the release's APK, then the two checks: its SHA-256, and the signer against the installed app. */
    fun checkCloud(id: String) = bg {
        val c = find(id).takeIf { it.stage == CloudStage.READY && it.assetId != null } ?: throw IllegalStateException("No release ready for $id")
        busy("Downloading")
        val f = apk(id)
        val got = github.download(c.assetId!!, f, Repo.APK_LIMIT)
        val reason = InstallGate.refuse(c.apkSha256, got, if (got.equals(c.apkSha256, ignoreCase = true)) Signer.check(app, f) else Signer.Verdict.Ok)
        if (reason != null) {
            f.delete()
            db.log(now(), "refused", "$id: $reason")
            Clio.warn("prometheus", "refused", "$id: $reason")
            reload("Not offered: $reason")
            return@bg
        }
        db.log(now(), "verified", "$id: v${c.versionName}, SHA-256 and signing key match")
        _state.update { it.copy(verified = it.verified + id) }
        reload()
    }

    companion object {
        private val CORRECTION = Regex("(?i)^\\s*(no\\b|nope|not (that|what)|that'?s (wrong|not)|wrong|actually|i (said|meant)|stop|don'?t)")

        @Volatile private var instance: PrometheusCore? = null
        fun get(context: Context): PrometheusCore = instance ?: synchronized(this) {
            instance ?: PrometheusCore(context.applicationContext).also { instance = it }
        }
    }
}
