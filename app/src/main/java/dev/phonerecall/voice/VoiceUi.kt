package dev.phonerecall.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

private fun clock(s: Long) = "%d:%02d".format(Locale.US, s / 60, s % 60)

/**
 * The Live session bar: a saffron dot that grows with his voice, what Gemini is doing, the time used
 * and left today, and Stop. While driving it is the whole sheet: bigger, one line, one action.
 */
@Composable
fun LiveBar(ui: LiveUi, onStop: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, driving: Boolean = false) {
    val c = Athena.colors
    val ended = ui.phase == LivePhase.ENDED
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.blockSmall)).background(c.raised)
            .padding(horizontal = Space.m, vertical = if (driving) Space.l else Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                val dot = if (ended) 10.dp else (12f + 14f * (ui.level * 4f).coerceIn(0f, 1f)).dp
                Box(Modifier.size(dot).clip(CircleShape).background(if (ended) c.ink3 else c.accent))
            }
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusWord(if (ended) "Live ended" else "Live", if (ended) Tone.QUIET else Tone.ACTIVE)
                    if (!ended) { Spacer(Modifier.width(Space.s)); Line(ui.phase.word, Type.small, c.ink2, maxLines = 1) }
                }
                Line(
                    if (ended) "${clock(ui.seconds)} used" else "${clock(ui.seconds)} · ${ui.leftSeconds / 60} min left today",
                    Type.caption, c.ink3, maxLines = 1,
                )
            }
            if (ended) TextAction("Hide", onDismiss) else if (!driving) TextAction("Stop", onStop, emphasis = true)
        }
        // The newest words: a running transcript shows its tail, never a cut-off head.
        val line = (ui.problem ?: ui.said.ifBlank { ui.heard }).trim()
        val room = if (driving) 90 else 150
        val shown = if (line.length > room) "…" + line.takeLast(room).substringAfter(' ') else line
        if (shown.isNotBlank()) Line(shown, if (driving) Type.title else Type.body, if (ui.problem != null) c.ink2 else c.ink, Modifier.padding(top = Space.xs), maxLines = 3)
        if (driving && !ended) PrimaryAction("Stop", onStop, Modifier.padding(top = Space.m).fillMaxWidth().heightIn(min = 56.dp))
    }
}

/** First Live of the day: what it costs, and one Start. */
@Composable
fun LiveOfferRow(offer: LiveOffer, onStart: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    val c = Athena.colors
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.blockSmall)).background(c.raised).padding(horizontal = Space.m, vertical = Space.s)) {
        Line("Start a Live conversation?", Type.action, c.ink)
        Line(
            "Gemini Live answers out loud and you can talk over it. About 2¢ a minute at paid rates; on the free tier Google may use the conversation. ${offer.leftMinutes} of ${offer.capMinutes} min left today. Asked once a day.",
            Type.small, c.ink3, Modifier.widthIn(max = 520.dp),
        )
        Row {
            TextAction("Start Live", onStart, Modifier.padding(end = Space.s), emphasis = true)
            TextAction("Not now", onDecline)
        }
    }
}

// ---- Calypso ----

/** Calypso's Gemini key row (Calypso is already unlocked when this shows). Last four only. */
@Composable
fun GeminiKeyRow() {
    val ctx = LocalContext.current
    val c = Athena.colors
    val scope = rememberCoroutineScope()
    var masked by remember { mutableStateOf<String?>(null) }
    var set by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val load = { scope.launch { val v = withContext(Dispatchers.IO) { geminiKey(ctx).read() }; set = v != null; masked = v?.let { "•".repeat(12) + it.takeLast(4) } } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(confirm) { if (confirm) { kotlinx.coroutines.delay(4000); confirm = false } }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("Gemini key", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                Line("Live voice conversations. Read only by the voice client and sent only to generativelanguage.googleapis.com while Live is open. No model or agent ever sees it.", Type.small, c.ink3)
            }
            StatusWord(if (set) "Set" else "Not set", if (set) Tone.OK else Tone.QUIET)
        }
        if (set) FactRow("Key", masked ?: "Reading")
        if (entering) {
            UnderlineField(draft, { draft = it.trim() }, "Gemini key", Modifier.fillMaxWidth(), keyboard = KeyboardType.Password, visualTransformation = PasswordVisualTransformation())
            Row {
                TextAction("Save", { if (draft.length >= 30) scope.launch { withContext(Dispatchers.IO) { geminiKey(ctx).save(draft) }; draft = ""; entering = false; load() } }, emphasis = true, enabled = draft.length >= 30)
                TextAction("Cancel", { draft = ""; entering = false })
            }
        } else Row {
            TextAction(if (set) "Rotate" else "Add key", { entering = true }, emphasis = !set)
            if (set) TextAction(if (confirm) "Tap again to delete" else "Delete", {
                if (confirm) scope.launch { withContext(Dispatchers.IO) { geminiKey(ctx).delete() }; confirm = false; load() } else confirm = true
            })
        }
    }
}

// ---- Themis ----

/** Themis, under Athena's spend: Live's minutes and estimated spend, and the daily cap. */
@Composable
fun LiveSpendRows() {
    val ctx = LocalContext.current
    val store = remember { LiveLedgerStore(ctx) }
    var ledger by remember { mutableStateOf(store.load()) }
    LiveSpendContent(ledger) { cap -> ledger = store.update { it.copy(capMinutes = cap) } }
}

@Composable
fun LiveSpendContent(ledger: LiveLedger, onCap: (Int) -> Unit) {
    val c = Athena.colors
    val today = LocalDate.now()
    val usd = "$" + String.format(Locale.US, "%.2f", ledger.spendMicros / 1_000_000.0)
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("Gemini Live", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Line("Separate from Athena's cap. ${ledger.inSeconds / 60} min this month; $usd at paid rates. Free tier for now, so Google may use these conversations.", Type.small, c.ink3, Modifier.widthIn(max = 540.dp))
            }
            Spacer(Modifier.width(Space.m))
            StatusWord("${(ledger.capMinutes * 60L - ledger.leftSeconds(today)) / 60} of ${ledger.capMinutes} min today", if (ledger.leftSeconds(today) <= 0) Tone.ALERT else Tone.QUIET)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Line("Live minutes a day", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
            for (cap in LiveLedger.CAPS) {
                val on = cap == ledger.capMinutes
                Column(Modifier.heightIn(min = 44.dp).clickable(role = Role.RadioButton) { onCap(cap) }.padding(horizontal = Space.m), verticalArrangement = Arrangement.Center) {
                    Line("$cap", Type.action, if (on) c.ink else c.ink3)
                    Box(Modifier.padding(top = Space.xs).width(18.dp).height(2.dp).background(if (on) c.accent else androidx.compose.ui.graphics.Color.Transparent))
                }
            }
        }
    }
}

/** What the assistant sheet needs for voice. Null in the sheet means no voice controls (screenshot previews). */
class SheetVoice(
    val live: LiveUi? = null,
    val offer: LiveOffer? = null,
    val driving: Boolean = false,
    /** A final recognizer result: the router decides on-device, Delphi or Live. */
    val onSpoken: (String) -> Unit = {},
    val onLive: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onDecline: () -> Unit = {},
)
