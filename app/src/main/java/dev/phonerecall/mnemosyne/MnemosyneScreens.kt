package dev.phonerecall.mnemosyne

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import dev.phonerecall.vault.AndroidBiometric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// ---- what the page can do (the screen wires these; the shots pass nothing) ----

class MnCalls(
    val back: () -> Unit = {},
    val setUp: () -> Unit = {},
    val backUpNow: () -> Unit = {},
    val toggleCalypso: () -> Unit = {},
    val addOlder: () -> Unit = {},
    val showPhrase: () -> Unit = {},
    val restore: () -> Unit = {},
    val dismissRestored: () -> Unit = {},
)

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
    b >= 1L shl 10 -> "${b shr 10} KB"
    else -> "$b bytes"
}

private fun whenText(t: Long, now: Long = System.currentTimeMillis()): String {
    val d = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t))
    val days = ((now - t) / 86_400_000L)
    return when {
        t > now -> if (t - now < 86_400_000L) "Tonight, $d" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t)) + ", $d"
        days < 1 && java.time.LocalDate.now() == java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate() -> "Today, $d"
        days < 2 -> "Yesterday, $d"
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(t)) + ", $d"
    }
}

@Composable
private fun MnRow(title: String, about: String? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true, trailing: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule)
            .then(if (onClick != null && enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), if (enabled) c.ink else c.ink3, maxLines = 1)
            if (about != null) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 520.dp))
        }
        Spacer(Modifier.width(Space.m))
        trailing()
    }
}

@Composable private fun Section(t: String) = Kicker(t, Modifier.padding(top = Space.xxl, bottom = Space.s))

/** Themis → Mnemosyne. */
@Composable
fun MnemosyneContent(gutter: Dp, wide: Boolean, s: MnState, calls: MnCalls) {
    val c = Athena.colors
    val vaultName = Place.VAULT.title()
    val healthName = Place.HEALTH.title()
    val devicesName = Place.DEVICES.title()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.BACKUP.title(), gutter, subtitle = Place.BACKUP.subtitle()) { TextAction("Done", calls.back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl), modifier = Modifier.widthIn(max = if (wide) 720.dp else Dp.Unspecified)) {
            s.restored?.let { r ->
                item { Section("After the restore") }
                item {
                    Line(
                        "Brought back from ${r.backupId.take(8).let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}" }}: ${r.dbs} databases, ${r.prefs} settings files, ${r.files} files" +
                            (if (r.calypso > 0) ", ${r.calypso} ${Place.VAULT.title()} items" else "") + ". Every store is sealed again under this phone's own keys.",
                        Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp),
                    )
                }
                val todo = buildList {
                    add("Spotify" to "Sign in again in Themis → Music. Tokens are never backed up.")
                    add("Anthropic key and Hetzner token" to "Add them again in $vaultName.")
                    add("Health Connect" to "Allow $healthName again when it asks. Android keeps that permission per phone.")
                    add("Google Home" to "Open $devicesName and allow your home again.")
                    add("Permissions" to "Notification senses, calendar and contacts: allow them again in Themis.")
                    add("Watch" to "Pair the Galaxy Watch with this phone; the $healthName tile follows.")
                    if (r.calypsoLost > 0) add("${r.calypsoLost} $vaultName items stayed behind" to "They were never opened while Include $vaultName was on, so no backup could carry them.")
                    if (r.failed.isNotEmpty()) add("${r.failed.size} parts didn't come across" to r.failed.take(3).joinToString())
                }
                for ((t, a) in todo) item(key = "todo-$t") { MnRow(t, a) { StatusWord("To do", Tone.ACTIVE) } }
                item { MnRow("Done with the list", null, onClick = calls.dismissRestored) { StatusWord("Hide", Tone.QUIET) } }
            }

            if (!s.setUp) {
                item {
                    Line(
                        "Every night, while the phone charges on Wi-Fi, Zeus locks a copy of everything it keeps and sends it to the storage box behind Lemnos. " +
                            "It is locked with a recovery phrase only you hold. Lemnos only ever sees the locked copy.",
                        Type.body, c.ink2, Modifier.padding(top = Space.s, bottom = Space.l).widthIn(max = 560.dp),
                    )
                }
                item { PrimaryAction("Set up backups", calls.setUp) }
                item { Section("New phone") }
                item { MnRow("Restore from a backup", if (s.paired) "Pick a backup and type your 24 words" else "Pair Lemnos first, then come back here", calls.restore, enabled = s.paired) }
                item { Warning() }
                return@LazyColumn
            }

            item { Section("Status") }
            item {
                val l = s.last
                val (word, tone) = when {
                    s.running != null -> s.running to Tone.ACTIVE
                    l == null -> "Not yet" to Tone.QUIET
                    l.ok && l.error == null -> "Done" to Tone.OK
                    l.ok -> "Partial" to Tone.ALERT
                    else -> "Failed" to Tone.ALERT
                }
                MnRow("Last backup", l?.let { whenText(it.at) + if (it.ok) ", ${size(it.size)}" else "" } ?: "None yet") { StatusWord(word, tone) }
            }
            s.last?.error?.let { e -> item { Line(e, Type.small, c.alert, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp)) } }
            item { MnRow("Next backup", (s.nextRun?.let { whenText(it) } ?: "Not scheduled") + ". Waits for charging, Wi-Fi and the phone at rest.") }
            item { MnRow("Back up now", if (s.paired) "Locks and sends a copy now" else "Pair Lemnos first", calls.backUpNow, enabled = s.running == null && s.paired) { if (s.running != null) StatusWord(s.running, Tone.ACTIVE) } }
            item { MnRow("On Lemnos", s.remote?.let { r -> if (r.isEmpty()) "No backups yet" else "${r.size} kept: the last 14 nights and 4 weekly. Newest ${whenText(r.first().created).replaceFirstChar { it.lowercase() }}, ${size(r.first().size)}." } ?: s.remoteError ?: "Checking") }

            item { Section(Place.VAULT.title()) }
            item {
                MnRow("Include ${Place.VAULT.title()}", "Off by default. On, its items can be brought back on a new phone with your phrase.", calls.toggleCalypso) {
                    StatusWord(if (s.calypso) "On" else "Off", if (s.calypso) Tone.ACTIVE else Tone.QUIET)
                }
            }
            if (s.calypso) {
                item {
                    val left = s.calypsoTotal - s.calypsoEscrowed
                    MnRow(
                        "${s.calypsoEscrowed} of ${s.calypsoTotal} items in backups",
                        if (left == 0) "New items join as they arrive and when you open them." else "Items from ${dev.phonerecall.ui.Workers.IRIS} and ${dev.phonerecall.ui.Workers.ATLAS} join the first time you open them. Add the other $left now, one fingerprint each.",
                        if (left > 0) calls.addOlder else null,
                    ) { if (left > 0) StatusWord("Add", Tone.ACTIVE) }
                }
            }

            item { Section("Recovery phrase") }
            item { MnRow("Show recovery phrase", "24 words and a QR code, behind your fingerprint", calls.showPhrase) }
            item { MnRow("Restore from a backup", "On a new phone, after pairing Lemnos", calls.restore, enabled = s.paired) }
            item { Warning() }
        }
    }
}

@Composable
private fun Warning() {
    Line(
        "If the phrase is lost, the backups can't be opened by anyone, Lemnos and Zeus included. Keep it written down somewhere safe, away from the phone.",
        Type.small, Athena.colors.alert, Modifier.padding(top = Space.xl).widthIn(max = 560.dp),
    )
}

// ---- the phrase ----

enum class PhraseStep { SHOW, CHECK, VIEW }

/** The QR of the phrase: black on white whatever the theme, since scanners need the contrast. */
@Composable
fun PhraseQr(text: String, side: Dp) {
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    }
    Box(Modifier.size(side).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.width / m.width
            for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@Composable
private fun WordGrid(words: List<String>, columns: Int) {
    val c = Athena.colors
    val rows = (words.size + columns - 1) / columns
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        for (col in 0 until columns) Column(Modifier.weight(1f)) {
            for (r in 0 until rows) {
                val i = col * rows + r
                if (i < words.size) Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).topRule(c.rule), verticalAlignment = Alignment.CenterVertically) {
                    Line("${i + 1}", Type.caption, c.ink3, Modifier.width(28.dp))
                    Line(words[i], Type.body.copy(fontWeight = FontWeight.Medium), c.ink)
                }
            }
        }
    }
}

/** First enable (SHOW then CHECK) or "Show recovery phrase" (VIEW). Shown under FLAG_SECURE by the caller. */
@Composable
fun PhraseContent(
    gutter: Dp, wide: Boolean, words: List<String>, step: PhraseStep, positions: List<Int>,
    answers: List<String> = listOf("", "", ""), problem: String? = null,
    onAnswer: (Int, String) -> Unit = { _, _ -> }, onWrote: () -> Unit = {}, onCheck: () -> Unit = {}, back: () -> Unit = {},
) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(if (step == PhraseStep.CHECK) "Check the phrase" else "Recovery phrase", gutter, kicker = Place.BACKUP.title()) {
            TextAction(if (step == PhraseStep.VIEW) "Done" else "Cancel", back)
        }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl), modifier = Modifier.widthIn(max = if (wide) 760.dp else Dp.Unspecified)) {
            if (step == PhraseStep.CHECK) {
                item {
                    Line("Type these three words from what you wrote down.", Type.body, c.ink2, Modifier.padding(top = Space.s, bottom = Space.l))
                }
                for ((k, pos) in positions.withIndex()) item(key = "q$pos") {
                    Kicker("Word $pos", Modifier.padding(top = Space.m))
                    UnderlineField(answers[k], { onAnswer(k, it) }, "word $pos", Modifier.fillMaxWidth(), keyboard = KeyboardType.Password, visualTransformation = VisualTransformation.None)
                }
                problem?.let { item { Line(it, Type.small, c.alert, Modifier.padding(top = Space.m)) } }
                item { PrimaryAction("Check", onCheck, Modifier.padding(top = Space.xl)) }
                return@LazyColumn
            }
            item {
                Line(
                    if (step == PhraseStep.SHOW) "Write these 24 words down, in order, on paper. This is the only time Zeus shows them without your fingerprint. Anyone with them can open your backups."
                    else "Anyone with these words can open your backups. Screenshots are blocked on this screen.",
                    Type.body, c.ink2, Modifier.padding(top = Space.s, bottom = Space.l).widthIn(max = 560.dp),
                )
            }
            if (wide) item {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xxl), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) { WordGrid(words, 3) }
                    Column { PhraseQr(words.joinToString(" "), 200.dp); Line("Scan on the new phone", Type.caption, c.ink3, Modifier.padding(top = Space.s)) }
                }
            } else {
                item { WordGrid(words, 2) }
                item {
                    Column(Modifier.padding(top = Space.xl)) {
                        PhraseQr(words.joinToString(" "), 184.dp)
                        Line("The same words as a QR code, to scan on the new phone. Keep it with the paper, or don't print it at all.", Type.caption, c.ink3, Modifier.padding(top = Space.s).widthIn(max = 320.dp))
                    }
                }
            }
            if (step == PhraseStep.SHOW) item { PrimaryAction("I wrote it down", onWrote, Modifier.padding(top = Space.xl)) }
            item { Warning() }
        }
    }
}

// ---- restore ----

enum class RestoreStep { PICK, PHRASE, WORKING, DONE, FAILED }

data class RestoreUi(
    val step: RestoreStep = RestoreStep.PICK,
    val backups: List<RemoteBackup>? = null,
    val listError: String? = null,
    val picked: RemoteBackup? = null,
    val words: String = "",
    val progress: String = "",
    val problem: String? = null,
    val done: Restored? = null,
)

class RestoreCalls(
    val back: () -> Unit = {}, val pick: (RemoteBackup) -> Unit = {}, val words: (String) -> Unit = {}, val scan: () -> Unit = {},
    val start: () -> Unit = {}, val restart: () -> Unit = {}, val retry: () -> Unit = {},
)

@Composable
fun RestoreContent(gutter: Dp, wide: Boolean, r: RestoreUi, calls: RestoreCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Restore", gutter, kicker = Place.BACKUP.title()) { if (r.step != RestoreStep.WORKING) TextAction(if (r.step == RestoreStep.DONE) "Later" else "Cancel", calls.back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl), modifier = Modifier.widthIn(max = if (wide) 720.dp else Dp.Unspecified)) {
            when (r.step) {
                RestoreStep.PICK -> {
                    item { Line("Pick the backup to bring back. What's on this phone now is replaced store by store; nothing is merged.", Type.body, c.ink2, Modifier.padding(top = Space.s, bottom = Space.l).widthIn(max = 560.dp)) }
                    val list = r.backups
                    when {
                        list == null && r.listError != null -> item { Line(r.listError, Type.small, c.alert) }
                        list == null -> item { Line("Asking Lemnos", Type.small, c.ink3) }
                        list.isEmpty() -> item { Line("Lemnos has no backups yet.", Type.small, c.ink3) }
                        else -> for ((i, b) in list.withIndex()) item(key = b.id) {
                            MnRow(whenText(b.created), "${size(b.size)}, ${if (b.kind == "manual") "made by hand" else "nightly"}", { calls.pick(b) }) { if (i == 0) StatusWord("Newest", Tone.OK) }
                        }
                    }
                }
                RestoreStep.PHRASE, RestoreStep.FAILED -> {
                    r.picked?.let { item { MnRow(whenText(it.created), size(it.size)) { StatusWord("Picked", Tone.QUIET) } } }
                    item { Kicker("Your 24 words", Modifier.padding(top = Space.xl)) }
                    item {
                        WordsField(r.words, calls.words)
                    }
                    item {
                        val n = Phrase.normalize(r.words).size
                        Line("$n of 24 words. The first four letters of each are enough.", Type.caption, c.ink3, Modifier.padding(top = Space.xs))
                    }
                    item { TextAction("Scan the QR code instead", calls.scan, Modifier.padding(top = Space.s), emphasis = true) }
                    r.problem?.let { item { Line(it, Type.small, c.alert, Modifier.padding(top = Space.m).widthIn(max = 560.dp)) } }
                    item { PrimaryAction("Restore", calls.start, Modifier.padding(top = Space.xl)) }
                    item { Warning() }
                }
                RestoreStep.WORKING -> {
                    item { Line(r.progress.ifEmpty { "Starting" }, Type.title, c.ink, Modifier.padding(top = Space.xl)) }
                    item { Line("Downloading, checking every chunk, then sealing each store under this phone's keys. Keep Zeus open.", Type.small, c.ink3, Modifier.padding(top = Space.s).widthIn(max = 560.dp)) }
                }
                RestoreStep.DONE -> {
                    val d = r.done
                    item { Line("Ready to restart", Type.title, c.ink, Modifier.padding(top = Space.l)) }
                    item {
                        Line(
                            "${d?.dbs ?: 0} databases, ${d?.prefs ?: 0} settings files and ${d?.files ?: 0} files are checked and sealed for this phone" +
                                (if ((d?.calypso ?: 0) > 0) ", with ${d?.calypso} ${Place.VAULT.title()} items" else "") + ". Zeus restarts to move them in.",
                            Type.body, c.ink2, Modifier.padding(top = Space.s, bottom = Space.l).widthIn(max = 560.dp),
                        )
                    }
                    item { PrimaryAction("Restart Zeus", calls.restart) }
                    item { Line("Afterwards: sign in to Spotify again, allow Health Connect and Google Home, and add the Anthropic key in ${Place.VAULT.title()}. ${Place.BACKUP.title()} lists it all.", Type.small, c.ink3, Modifier.padding(top = Space.l).widthIn(max = 560.dp)) }
                }
            }
        }
    }
}

/** The 24 words, typed or scanned: several lines, no autocorrect or suggestions (the keyboard learns nothing). */
@Composable
private fun WordsField(value: String, onValue: (String) -> Unit) {
    val c = Athena.colors
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onValue, minLines = 3,
        textStyle = Type.body.copy(color = c.ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Password, autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = Space.s).drawBehindRule(c.rule),
        decorationBox = { inner ->
            Box(Modifier.padding(vertical = Space.m)) {
                if (value.isEmpty()) Line("abandon ability able about ...", Type.body, c.ink3)
                inner()
            }
        },
    )
}

private fun Modifier.drawBehindRule(color: Color) = this.then(androidx.compose.ui.Modifier.drawBehind {
    val w = 1.dp.toPx()
    drawLine(color, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
})

// ---- the live screen ----

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** No screenshots, no recents thumbnail, while a phrase is on screen. */
@Composable
private fun Secure() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val w = ctx.activity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

@Composable
fun MnemosyneScreen(gutter: Dp, wide: Boolean, back: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val s by Mnemosyne.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf("main") }
    var words by remember { mutableStateOf<List<String>>(emptyList()) }
    var entropy by remember { mutableStateOf<ByteArray?>(null) }
    var positions by remember { mutableStateOf(emptyList<Int>()) }
    var answers by remember { mutableStateOf(listOf("", "", "")) }
    var problem by remember { mutableStateOf<String?>(null) }
    var restore by remember { mutableStateOf(RestoreUi()) }
    val bio = remember { AndroidBiometric(ctx) }
    val vaultName = Place.VAULT.title()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Mnemosyne.refresh(app) }; Mnemosyne.loadRemote(app) }
    fun close() { words = emptyList(); entropy?.fill(0); entropy = null; answers = listOf("", "", ""); problem = null; page = "main" }

    when (page) {
        "setup", "check", "view" -> {
            Secure()
            BackHandler { close() }
            PhraseContent(
                gutter, wide, words, when (page) { "setup" -> PhraseStep.SHOW; "check" -> PhraseStep.CHECK; else -> PhraseStep.VIEW }, positions, answers, problem,
                onAnswer = { i, v -> answers = answers.toMutableList().also { it[i] = v } },
                onWrote = { positions = Phrase.checkPositions(); page = "check" },
                onCheck = {
                    val ok = positions.withIndex().all { (i, p) -> Phrase.normalize(answers[i]).singleOrNull()?.let { a -> a == words[p - 1] || (a.length >= 4 && words[p - 1].startsWith(a.take(4))) } == true }
                    if (!ok) { problem = "Not quite. Check those words against your paper."; return@PhraseContent }
                    val e = entropy ?: return@PhraseContent
                    scope.launch {
                        withContext(Dispatchers.IO) { Mnemosyne.finishSetup(app, e) }
                        close()
                        Mnemosyne.run(app, "manual")
                    }
                },
                back = { close() },
            )
        }
        "restore" -> {
            Secure()
            BackHandler(enabled = restore.step != RestoreStep.WORKING) { restore = RestoreUi(); page = "main" }
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    runCatching { Mnemosyne.list() }.onSuccess { l -> restore = restore.copy(backups = l) }.onFailure { e -> restore = restore.copy(listError = e.message ?: "Lemnos didn't answer.") }
                }
            }
            RestoreContent(gutter, wide, restore, RestoreCalls(
                back = { restore = RestoreUi(); page = "main" },
                pick = { b -> restore = restore.copy(picked = b, step = RestoreStep.PHRASE) },
                words = { w -> restore = restore.copy(words = w, problem = null) },
                scan = {
                    val opts = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder().setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE).build()
                    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(ctx, opts).startScan()
                        .addOnSuccessListener { code -> code.rawValue?.let { restore = restore.copy(words = it, problem = null) } }
                        .addOnFailureListener { e -> restore = restore.copy(problem = "The scanner didn't start: ${e.message}") }
                },
                start = {
                    val b = restore.picked ?: return@RestoreCalls
                    try { Phrase.decode(restore.words) } catch (e: Phrase.Invalid) { restore = restore.copy(problem = e.message); return@RestoreCalls }
                    restore = restore.copy(step = RestoreStep.WORKING, progress = "Starting")
                    scope.launch {
                        try {
                            val d = Mnemosyne.restore(app, b.id, restore.words) { p -> restore = restore.copy(progress = p) }
                            restore = restore.copy(step = RestoreStep.DONE, done = d, words = "")
                        } catch (e: Exception) {
                            restore = restore.copy(step = RestoreStep.FAILED, problem = e.message ?: e.javaClass.simpleName)
                        }
                    }
                },
                restart = { Mnemosyne.restart(ctx) },
            ))
        }
        else -> MnemosyneContent(gutter, wide, s, MnCalls(
            back = back,
            setUp = { val e = Mnemosyne.newPhrase(); entropy = e; words = Phrase.encode(e); page = "setup" },
            backUpNow = { scope.launch { Mnemosyne.run(app, "manual"); Mnemosyne.loadRemote(app) } },
            toggleCalypso = {
                bio.authenticate(if (s.calypso) "Leave $vaultName out of backups" else "Include $vaultName in backups", "Anyone with your phrase could then open its items", null, {
                    scope.launch(Dispatchers.IO) { Mnemosyne.setCalypso(app, !s.calypso) }
                }, {})
            },
            addOlder = {
                val core = dev.phonerecall.vault.VaultCore.get(app)
                fun next(ids: List<String>) {
                    val id = ids.firstOrNull() ?: return run { Mnemosyne.refresh(app) }
                    core.calypso.open(id, bio, { _, _ -> next(ids.drop(1)) }, { Mnemosyne.refresh(app) })
                }
                scope.launch { next(withContext(Dispatchers.IO) { Mnemosyne.unescrowed(app) }) }
            },
            showPhrase = {
                val u = runCatching { Mnemosyne.phraseUnwrapper() }.getOrNull() ?: return@MnCalls
                bio.authenticate("Show recovery phrase", "Only you should see these words", u, {
                    runCatching { Mnemosyne.phraseWords(app, u) }.onSuccess { words = it; page = "view" }
                }, {})
            },
            restore = { restore = RestoreUi(); page = "restore" },
            dismissRestored = { Mnemosyne.dismissRestored(app) },
        ))
    }
}
