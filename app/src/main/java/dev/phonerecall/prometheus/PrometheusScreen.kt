package dev.phonerecall.prometheus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.lemnos.ApprovalRequest
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import java.text.DateFormat
import java.util.Date
import java.util.UUID

/** Prometheus's nav icon: a flame on a fennel stalk. */
val FlameIcon: ImageVector by lazy {
    val b = ImageVector.Builder("prometheus", 24.dp, 24.dp, 24f, 24f)
    for (d in listOf("M12 3.5 C14.8 6.3 16.5 8.6 16.5 11.2 C16.5 13.7 14.5 15.5 12 15.5 C9.5 15.5 7.5 13.7 7.5 11.2 C7.5 9.6 8.3 8.3 9.4 7.2 C9.5 8.8 10.3 9.8 11.2 10.2 C10.8 7.8 11.2 5.6 12 3.5 Z", "M12 15.5 V20.5", "M9.5 20.5 H14.5")) {
        b.addPath(PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }
    b.build()
}

/** What the page can ask for. Each one is a tap; nothing on this page runs by itself. */
class PrometheusCalls(
    val run: () -> Unit = {},
    val approve: (Long) -> Unit = {},
    val reject: (Long) -> Unit = {},
    val undo: (Long) -> Unit = {},
    val quote: () -> Unit = {},
    val runNotes: () -> Unit = {},
    val dropQuote: () -> Unit = {},
    val propose: (String) -> Unit = {},
    val refresh: () -> Unit = {},
    val cancel: (String) -> Unit = {},
    val check: (String) -> Unit = {},
    val install: (String) -> Unit = {},
    val cloudMode: (Boolean) -> Unit = {},
    val cloudStart: (String, Boolean) -> Unit = { _, _ -> },
    val cloudRefresh: () -> Unit = {},
    /** Only after his fingerprint (the screen asks). */
    val cloudMerge: (String) -> Unit = {},
    val cloudCancel: (String) -> Unit = {},
    val cloudCheck: (String) -> Unit = {},
)

@Composable
fun PrometheusScreen(gutter: Dp, wide: Boolean) {
    val ctx = LocalContext.current
    val core = remember { PrometheusCore.get(ctx) }
    val s by core.state.collectAsStateWithLifecycle()
    var signError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { core.load(); core.refreshCloud() }
    // While the page shows and a cloud request is moving, look again every 30 s. Leaving the page stops it.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            if (core.state.value.cloud.any { it.stage.active }) core.refreshCloud()
        }
    }
    val calls = remember(core) {
        PrometheusCalls(
            run = core::run, approve = core::approve, reject = core::reject, undo = core::undo,
            quote = core::quote, runNotes = core::runNotes, dropQuote = core::dropQuote,
            propose = { problem ->
                val id = "p" + UUID.randomUUID().toString().replace("-", "").take(12)
                // His fingerprint signs the exact request (kind prometheus.request); Lemnos refuses anything unsigned.
                DeviceKeys.approve(
                    ctx, ApprovalRequest(id, "prometheus.request", "zeus-gateway", mapOf("id" to id, "problem" to problem)), "Propose an app change",
                    onToken = { tok -> signError = null; core.submitChange(id, problem, tok) }, onFail = { signError = it },
                )
            },
            refresh = core::refreshChanges, cancel = core::cancelChange, check = core::check, install = core::install,
            cloudMode = core::setCloudMode, cloudStart = core::startCloud, cloudRefresh = core::refreshCloud,
            cloudMerge = { id -> fingerprint(ctx, "Merge and release", "Merges the PR CI passed and builds a signed release", { signError = null; core.mergeAndRelease(id) }, { signError = it }) },
            cloudCancel = core::cancelCloud, cloudCheck = core::checkCloud,
        )
    }
    PrometheusContent(gutter, wide, s.copy(message = signError ?: s.message), calls)
}

private val stamp: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

/** Strong biometrics, or the device PIN as the fallback, before a merge and release. */
private fun fingerprint(c: android.content.Context, title: String, subtitle: String, onOk: () -> Unit, onFail: (String) -> Unit) {
    android.hardware.biometrics.BiometricPrompt.Builder(c).setTitle(title).setSubtitle(subtitle)
        .setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        .build()
        .authenticate(android.os.CancellationSignal(), c.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        })
}

@Composable
fun PrometheusContent(gutter: Dp, wide: Boolean, s: PrometheusState, calls: PrometheusCalls) {
    val c = Athena.colors
    val waiting = s.tweaks.filter { it.status == TweakStatus.PROPOSED }
    val applied = s.tweaks.filter { it.status == TweakStatus.APPLIED }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.IMPROVE.title(), gutter, subtitle = Place.IMPROVE.subtitle()) { StatusWord(s.working ?: "Manual only", if (s.working != null) Tone.ACTIVE else Tone.QUIET) }
        val left: @Composable () -> Unit = {
            Column {
                RunPanel(s, calls)
                s.message?.let { Line(it, Type.small, c.alert, Modifier.padding(top = Space.s)) }
                Kicker("Waiting for you", Modifier.padding(top = Space.xl, bottom = Space.s))
                if (waiting.isEmpty()) Line("Nothing waiting. Suggestions appear here after a run.", Type.body, c.ink2)
                waiting.forEach { TweakCard(it, calls) }
                Kicker("Applied", Modifier.padding(top = Space.xl, bottom = Space.xs))
                if (applied.isEmpty()) Line("Nothing applied yet.", Type.small, c.ink3)
                applied.forEach { AppliedRow(it, calls) }
            }
        }
        val right: @Composable () -> Unit = {
            Column {
                NotesPanel(s, calls)
                AppChanges(s, calls)
                LogPanel(s.log)
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Space.xxl)) {
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { left() } }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { right() } }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) { item { left() }; item { right() } }
        }
    }
}

@Composable
private fun RunPanel(s: PrometheusState, calls: PrometheusCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        Line("Reads how you use Zeus and suggests small changes. It runs only when you tap Run, and nothing changes until you approve it.", Type.body, c.ink2, Modifier.widthIn(max = 560.dp))
        Row(Modifier.padding(top = Space.l), verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("Run", { if (s.working == null) calls.run() })
            Spacer(Modifier.width(Space.l))
            val last = s.runs.firstOrNull { it.kind == "tuning" }
            Line(last?.let { "Last run ${stamp.format(Date(it.at))}. ${it.note}." } ?: "Never run.", Type.small, c.ink3, Modifier.weight(1f))
        }
        Line("On the phone, no model. It reads card taps and dismissals, Less like this, snoozes, and what you confirm or turn down in Fates.", Type.small, c.ink3, Modifier.padding(top = Space.s).widthIn(max = 560.dp))
    }
}

@Composable
private fun Block(strong: Boolean = false, content: @Composable () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.fillMaxWidth().padding(bottom = Space.blockGap).clip(shape).background(c.block(Tint.BRAIN, strong)).border(1.dp, c.edge, shape).padding(Space.blockPad)) { content() }
}

@Composable
private fun TweakCard(t: Tweak, calls: PrometheusCalls) {
    val c = Athena.colors
    Block(strong = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(t.kind.label + if (t.source != "local") ", from ${t.source}" else "", Modifier.weight(1f))
            StatusWord("Waiting", Tone.ACTIVE)
        }
        Line(title(t), Type.title, c.ink, Modifier.padding(top = Space.s))
        if (t.kind == TweakKind.NOTE) Diff(t.before, t.value) else Facts(t)
        Line(t.why, Type.small, c.ink2, Modifier.padding(top = Space.s))
        Row(Modifier.padding(top = Space.s)) {
            TextAction("Approve", { calls.approve(t.id) }, emphasis = true)
            TextAction("Not now", { calls.reject(t.id) })
        }
    }
}

@Composable
private fun Facts(t: Tweak) {
    when (t.kind) {
        TweakKind.CARD_WEIGHT -> { FactRow("Card", Analyst.name(t.target)); FactRow("Weight", "${signed(t.before)} to ${signed(t.value)} points") }
        TweakKind.AUTO_APPROVE -> { FactRow("Kind", dev.phonerecall.brain.ProposalKind.of(t.target)?.label ?: t.target); FactRow("Tier", "2, your phone, reversible") }
        TweakKind.NOTE -> Unit
    }
    FactRow("Undo", "Any time, from Applied")
}

/** A note change as a diff: the old line struck out in alert, the new one in ok. */
@Composable
private fun Diff(before: String, after: String) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.s).clip(RoundedCornerShape(Radius.blockSmall)).background(c.surface).padding(Space.m)) {
        if (before.isNotEmpty()) Row { Line("-", Type.small, c.alert, Modifier.width(16.dp)); Line(before, Type.small, c.alert) }
        if (after.isNotEmpty()) Row { Line("+", Type.small, c.ok, Modifier.width(16.dp)); Line(after, Type.small, c.ok) }
    }
}

private fun signed(v: String) = v.toIntOrNull()?.let { if (it > 0) "+$it" else "$it" } ?: v

private fun title(t: Tweak): String = when (t.kind) {
    TweakKind.CARD_WEIGHT -> (if ((t.value.toIntOrNull() ?: 0) < (t.before.toIntOrNull() ?: 0)) "Move ${Analyst.name(t.target)} down" else "Move ${Analyst.name(t.target)} up")
    TweakKind.AUTO_APPROVE -> "Auto-approve ${(dev.phonerecall.brain.ProposalKind.of(t.target)?.label ?: t.target).lowercase()}"
    TweakKind.NOTE -> when { t.before.isEmpty() -> "Add a note for Athena"; t.value.isEmpty() -> "Remove a note"; else -> "Reword a note" }
}

@Composable
private fun AppliedRow(t: Tweak, calls: PrometheusCalls) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(title(t), Type.body, c.ink, maxLines = 2)
            Line("${t.kind.label}, ${t.decidedAt?.let { stamp.format(Date(it)) } ?: ""}", Type.small, c.ink3, maxLines = 1)
        }
        TextAction("Undo", { calls.undo(t.id) })
    }
}

@Composable
private fun NotesPanel(s: PrometheusState, calls: PrometheusCalls) {
    val c = Athena.colors
    Kicker("Notes for Athena", Modifier.padding(top = Space.xl, bottom = Space.s))
    if (s.notes.isEmpty()) Line("No standing notes. Approved notes join Delphi's instructions.", Type.small, c.ink3)
    s.notes.forEach { Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) { Line(it, Type.small, c.ink) } }
    val q = s.quote
    if (q == null) {
        Line("Asks ${dev.phonerecall.brain.BrainModel.SONNET.label} to suggest edits from your thumbs and corrections in Delphi. You see the cost first.", Type.small, c.ink3, Modifier.padding(top = Space.m).widthIn(max = 560.dp))
        TextAction("Check the cost", calls.quote)
    } else {
        Block {
            Kicker("Before anything is sent")
            Line("About ${q.usd}", Type.title, c.ink, Modifier.padding(top = Space.xs))
            FactRow("Model", q.model.label)
            FactRow("Sends", "About ${q.inputTokens} tokens: your notes, Fates counts, thumbs and up to 10 short corrections")
            FactRow("Reply", "Up to ${q.maxOutput} tokens")
            Row {
                TextAction("Run for ${q.usd}", calls.runNotes, emphasis = true)
                TextAction("Cancel", calls.dropQuote)
            }
        }
    }
}

/** App changes: the cloud Prometheus by default, the laptop path as the fallback. */
@Composable
private fun AppChanges(s: PrometheusState, calls: PrometheusCalls) {
    Row(Modifier.padding(top = Space.xl, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Kicker("App changes", Modifier.weight(1f))
        TextAction("Cloud", { calls.cloudMode(true) }, emphasis = s.cloudMode)
        TextAction("Laptop", { calls.cloudMode(false) }, emphasis = !s.cloudMode)
    }
    if (s.cloudMode) CloudPanel(s, calls) else ChangesPanel(s, calls)
}

@Composable
private fun CloudPanel(s: PrometheusState, calls: PrometheusCalls) {
    val c = Athena.colors
    var problem by rememberSaveable { mutableStateOf("") }
    var clio by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.padding(bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Line("Prometheus in the cloud", Type.body, c.ink, Modifier.weight(1f))
        TextAction("Refresh", calls.cloudRefresh)
    }
    Line(
        "Report a bug or ask for a change. Prometheus works on a new branch and opens a PR; CI builds and tests it. " +
            "When CI passes, your fingerprint merges it and starts a signed release. Install checks the hash and the signing key first.",
        Type.small, c.ink3, Modifier.widthIn(max = 560.dp),
    )
    if (!s.tokens) Line("Add both GitHub tokens in Calypso first: GitHub (Prometheus) and GitHub release.", Type.small, c.alert, Modifier.padding(top = Space.xs))
    UnderlineField(problem, { problem = it.take(2000) }, "What's wrong, or what should change?", Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextAction(if (clio) "Clio warnings attached" else "Attach Clio warnings", { clio = !clio }, emphasis = clio)
        Line("Last 2 hours, cleaned of names and private details", Type.caption, c.ink3, Modifier.weight(1f))
    }
    TextAction("Send to Prometheus", { if (problem.isNotBlank()) { calls.cloudStart(problem.trim(), clio); problem = ""; clio = false } }, emphasis = true, enabled = problem.isNotBlank() && s.tokens && s.working == null)
    Line("Up to $${Forge.BUDGET_CENTS / 100} a request, from this month's cap.", Type.caption, c.ink3)
    s.cloud.forEach { CloudCard(it, it.id in s.verified, calls) }
}

@Composable
private fun CloudCard(r: CloudChange, verified: Boolean, calls: PrometheusCalls) {
    val c = Athena.colors
    val uri = LocalUriHandler.current
    Spacer(Modifier.height(Space.s))
    Block(strong = r.stage == CloudStage.READY || (r.stage == CloudStage.PR_OPEN && r.ci == Ci.GREEN)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(stamp.format(Date(r.created)), Modifier.weight(1f))
            StatusWord(r.stage.word, when (r.stage) {
                CloudStage.READY, CloudStage.INSTALLED -> Tone.OK
                CloudStage.FAILED, CloudStage.CLOSED -> Tone.ALERT
                CloudStage.CANCELLED -> Tone.QUIET
                else -> Tone.ACTIVE
            })
        }
        Line(r.problem, Type.body, c.ink, Modifier.padding(top = Space.s), maxLines = 4)
        if (r.progress.isNotEmpty()) Line(r.progress, Type.small, c.ink2, Modifier.padding(top = Space.s), maxLines = 6)
        Spacer(Modifier.height(Space.s))
        if (r.sessionStatus.isNotEmpty() && r.stage.active) FactRow("Prometheus", if (r.agentBusy) "Working" else "Done for now")
        r.prNumber?.let { FactRow("PR", "#$it ${r.prTitle.orEmpty()}".trim()) }
        if (r.stage == CloudStage.PR_OPEN) FactRow("CI", r.headSha?.let { "${r.ci.word} on ${it.take(7)}" } ?: r.ci.word, null, when (r.ci) { Ci.GREEN -> Tone.OK; Ci.RED -> Tone.ALERT; else -> Tone.QUIET })
        r.versionName?.let { FactRow("Version", it) }
        if (r.apkBytes > 0) FactRow("APK", "%.1f MB, SHA-256 %s".format(r.apkBytes / 1_048_576.0, r.apkSha256?.take(12)))
        if (r.spentCents > 0) FactRow("Spent", "$" + "%.2f".format(r.spentCents / 100.0))
        r.error?.let { Line(it, Type.small, c.alert, Modifier.padding(top = Space.s)) }
        FlowRow {
            when {
                r.stage == CloudStage.READY && verified -> TextAction("Install v${r.versionName}", { calls.install(r.id) }, emphasis = true)
                r.stage == CloudStage.READY -> TextAction("Download and check v${r.versionName}", { calls.cloudCheck(r.id) }, emphasis = true)
                r.stage == CloudStage.MERGED -> TextAction("Release", { calls.cloudMerge(r.id) }, emphasis = true)
                CloudFlow.mergeBlocked(r) == null -> TextAction("Merge and release", { calls.cloudMerge(r.id) }, emphasis = true)
            }
            r.prUrl?.let { u -> TextAction("Open PR", { uri.openUri(u) }) }
            if (r.stage == CloudStage.PR_OPEN && r.ci == Ci.RED) r.ciUrl?.let { u -> TextAction("Open CI", { uri.openUri(u) }) }
            r.releaseUrl?.let { u -> TextAction("Open release", { uri.openUri(u) }) }
            if (r.stage in setOf(CloudStage.STARTING, CloudStage.WORKING) || (r.stage == CloudStage.PR_OPEN && r.agentBusy)) TextAction("Stop", { calls.cloudCancel(r.id) })
        }
        if (r.stage == CloudStage.READY) Line(if (verified) "Same signing key as this app. Android asks you to confirm." else "Checked against the release's SHA-256 and this app's signing key before Install appears.", Type.small, c.ink3)
    }
}

@Composable
private fun ChangesPanel(s: PrometheusState, calls: PrometheusCalls) {
    val c = Athena.colors
    var problem by rememberSaveable { mutableStateOf("") }
    Row(Modifier.padding(bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Line("On the laptop", Type.body, c.ink, Modifier.weight(1f))
        TextAction("Refresh", calls.refresh)
    }
    Line("Describe a problem. Your fingerprint signs it, Lemnos queues it, and the laptop builds and tests it when you run Prometheus there. Installs only through Android's own dialog.", Type.small, c.ink3, Modifier.widthIn(max = 560.dp))
    UnderlineField(problem, { problem = it.take(1000) }, "What should change?", Modifier.fillMaxWidth())
    TextAction("Propose an app change", { if (problem.isNotBlank()) { calls.propose(problem.trim()); problem = "" } }, emphasis = true, enabled = problem.isNotBlank())
    s.changes.forEach { ChangeCard(it, it.id in s.verified, calls) }
}

@Composable
private fun ChangeCard(r: ChangeRequest, verified: Boolean, calls: PrometheusCalls) {
    val c = Athena.colors
    Spacer(Modifier.height(Space.s))
    Block(strong = r.status == ChangeStatus.READY) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(stamp.format(Date(r.created)), Modifier.weight(1f))
            StatusWord(r.status.word, when (r.status) { ChangeStatus.READY -> Tone.OK; ChangeStatus.FAILED -> Tone.ALERT; ChangeStatus.BUILDING -> Tone.ACTIVE; else -> Tone.QUIET })
        }
        Line(r.problem, Type.body, c.ink, Modifier.padding(top = Space.s), maxLines = 4)
        if (r.summary.isNotEmpty()) Line(r.summary, Type.small, c.ink2, Modifier.padding(top = Space.s), maxLines = 8)
        if (r.status == ChangeStatus.READY || r.status == ChangeStatus.FAILED || r.status == ChangeStatus.INSTALLED) {
            Spacer(Modifier.height(Space.s))
            FactRow("Diff", "${r.filesChanged} files, +${r.insertions} -${r.deletions}")
            FactRow("Tests", r.testsLine.ifEmpty { "${r.testsPassed} passed, ${r.testsFailed} failed" }, if (r.testsFailed == 0) "Pass" else "Fail", if (r.testsFailed == 0) Tone.OK else Tone.ALERT)
            r.versionName?.let { FactRow("Version", "$it (${r.versionCode})") }
            if (r.apkBytes > 0) FactRow("APK", "%.1f MB, SHA-256 %s".format(r.apkBytes / 1_048_576.0, r.apkSha256?.take(12)))
        }
        r.error?.let { Line(it, Type.small, c.alert, Modifier.padding(top = Space.s)) }
        FlowRow {
            when {
                r.installable && verified -> TextAction("Install", { calls.install(r.id) }, emphasis = true)
                r.installable -> TextAction("Download and check", { calls.check(r.id) }, emphasis = true)
                r.status == ChangeStatus.QUEUED -> TextAction("Cancel", { calls.cancel(r.id) })
            }
        }
        if (r.installable) Line(if (verified) "Signed with the same key as this app. Android asks you to confirm." else "The signature is checked against this app before Install appears.", Type.small, c.ink3)
    }
}

@Composable
private fun LogPanel(log: List<PromLog>) {
    val c = Athena.colors
    Kicker("Log", Modifier.padding(top = Space.xl, bottom = Space.xs))
    if (log.isEmpty()) Line("Every run, suggestion, approval, undo and install is written here.", Type.small, c.ink3)
    log.take(14).forEach { l ->
        Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.Top) {
            Kicker(l.action, Modifier.width(110.dp).padding(top = 3.dp))
            Column(Modifier.weight(1f)) {
                Line(l.detail, Type.small, c.ink, maxLines = 3)
                Line(stamp.format(Date(l.at)), Type.caption, c.ink3)
            }
        }
    }
}

/** Calypso's two GitHub rows (docs/PROMETHEUS.md). Last four only, like the other keys. */
@Composable
fun GitHubKeyRows() {
    dev.phonerecall.odyssey.KeyRow(
        "GitHub (Prometheus)",
        "Fine-grained token for your-github-user/zeus: contents and pull requests read/write, actions read. Goes to Anthropic for Prometheus's sessions (the repository and its vault), never into a prompt.",
        "github", 40,
    )
    dev.phonerecall.odyssey.KeyRow(
        "GitHub release",
        "Fine-grained token kept on this phone: merges a green PR and starts the release workflow (contents, pull requests and actions read/write). Goes only to api.github.com.",
        "github-release", 40,
    )
}
