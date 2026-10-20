package dev.phonerecall.hermes

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.phonerecall.assist.AssistActivity
import dev.phonerecall.brain.rememberVoiceCapture
import dev.phonerecall.data.Prefs
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One command and what came of it. `id` restarts the Undo window. */
data class HermesTurn(val text: String, val cmd: Cmd?, val outcome: Outcome, val id: Long = System.nanoTime())

/**
 * Hermes inside a screen: Delphi, the assistant sheet, the widget's command sheet. [tryRun] says whether
 * the words were a command (they run here and nothing goes to Athena) and shows the reply.
 */
@Stable
class HermesHost internal constructor(private val context: Context, private val scope: CoroutineScope) {
    var turn by mutableStateOf<HermesTurn?>(null); private set
    var busy by mutableStateOf(false); private set
    internal var ask: ((String) -> Unit)? = null
    internal var onResult: ((HermesTurn) -> Unit)? = null

    /** True when [text] is a command. */
    fun tryRun(text: String): Boolean {
        val cmd = Commands.parse(text) ?: return false
        go(text, cmd) { Hermes.run(context, cmd) }
        return true
    }

    fun choose(ch: Choice) { val t = turn ?: return; go(t.text, t.cmd) { ch.run(context) } }

    fun undo() {
        val t = turn ?: return
        turn = null
        scope.launch(Dispatchers.IO) { Hermes.undo(context, t.outcome, t.cmd) }
    }

    /** Cancel on a confirm, or the reply closing. A call or text he cancels is logged as such. */
    fun dismiss() {
        val t = turn ?: return
        turn = null
        if (t.outcome.confirm != null) t.cmd?.let { scope.launch(Dispatchers.IO) { Hermes.log(it, "cancelled") } }
    }

    fun allow(permission: String) { ask?.invoke(permission) }

    internal fun granted() { val t = turn ?: return; t.outcome.retry?.let { cmd -> go(t.text, cmd) { Hermes.run(context, cmd) } } }

    private fun go(text: String, cmd: Cmd?, block: () -> Outcome) {
        busy = true
        scope.launch(Dispatchers.IO) {
            val o = runCatching(block).getOrElse { Outcome("That didn't work", it.message.orEmpty(), ok = false) }
            withContext(Dispatchers.Main) {
                val t = HermesTurn(text, cmd, o)
                turn = t; busy = false
                onResult?.invoke(t)
            }
        }
    }
}

/** A [HermesHost] for this screen. `onResult` sees every reply (Delphi keeps it in the transcript, voice says it). */
@Composable
fun rememberHermes(onResult: ((HermesTurn) -> Unit)? = null): HermesHost {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = remember { HermesHost(context, scope) }
    val result by rememberUpdatedState(onResult)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) host.granted() }
    host.ask = { permission.launch(it) }
    host.onResult = { result?.invoke(it) }
    return host
}

/** The reply to a command, for a live host. */
@Composable
fun HermesReply(host: HermesHost, modifier: Modifier = Modifier) {
    val t = host.turn ?: return
    HermesReplyContent(t, host.busy, host::choose, host::undo, host::allow, host::dismiss, modifier)
}

/**
 * One line of result. Undo stays for eight seconds; a call or text shows who and how with Call / Cancel;
 * a few choices; or the permission it needs. Raised surface with a 1 dp edge, like the undo toast.
 */
@Composable
fun HermesReplyContent(
    turn: HermesTurn,
    busy: Boolean,
    onChoice: (Choice) -> Unit,
    onUndo: () -> Unit,
    onAllow: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    undoMs: Long = 8_000L,
) {
    val c = Athena.colors
    val o = turn.outcome
    var undoShown by remember(turn.id) { mutableStateOf(o.undo != null) }
    LaunchedEffect(turn.id) { if (o.undo != null) { delay(undoMs); undoShown = false } }
    val shape = RoundedCornerShape(Radius.blockSmall)
    Column(
        modifier.fillMaxWidth().widthIn(max = 620.dp).clip(shape).background(c.raised).border(1.dp, c.edge, shape)
            .padding(start = Space.l, end = Space.xs, top = Space.s, bottom = Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = Space.xs)) {
                Line(o.title, Type.body, if (o.ok) c.ink else c.alert, maxLines = 2)
                if (o.detail.isNotBlank()) Line(o.detail, Type.small, c.ink3, maxLines = 3)
            }
            Spacer(Modifier.width(Space.s))
            when {
                busy -> StatusWord("Working", Tone.ACTIVE)
                undoShown -> TextAction("Undo", onUndo, emphasis = true)
                o.confirm == null && o.needs == null && o.choices.isEmpty() -> TextAction("Close", onDismiss)
            }
        }
        o.body?.let { Line(it, Type.small, c.ink2, Modifier.padding(top = Space.xs, bottom = Space.s, end = Space.m)) }
        if (!busy && (o.confirm != null || o.needs != null || o.choices.isNotEmpty())) {
            Row(Modifier.padding(top = Space.xs, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                o.confirm?.let { PrimaryAction(it.label, { onChoice(it) }); Spacer(Modifier.width(Space.s)) }
                o.needs?.let { PrimaryAction("Allow", { onAllow(it) }); Spacer(Modifier.width(Space.s)) }
                for (ch in o.choices.take(3)) TextAction(ch.label, { onChoice(ch) })
                Spacer(Modifier.weight(1f))
                TextAction(if (o.confirm != null) "Cancel" else "Close", onDismiss)
            }
        }
    }
}

/**
 * The widget's command sheet: one field and the mic over whatever is on screen. A command runs here;
 * anything else goes to Athena's sheet as a question.
 */
@Composable
fun HermesSheet(
    turn: HermesTurn?,
    busy: Boolean,
    onSubmit: (String) -> Unit,
    onChoice: (Choice) -> Unit,
    onUndo: () -> Unit,
    onAllow: (String) -> Unit,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    voice: Boolean = true,
    listenNow: Boolean = false,
    focusNow: Boolean = false,
) {
    val c = Athena.colors
    var draft by remember { mutableStateOf("") }
    val submit = { if (draft.isNotBlank()) { onSubmit(draft.trim()); draft = "" } }
    val capture = if (voice) rememberVoiceCapture { text, final -> if (final) { onSubmit(text); draft = "" } else draft = text } else null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (listenNow) capture?.start()
        else if (focusNow) runCatching { focus.requestFocus() }
    }
    Box(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 640.dp)
                .clip(RoundedCornerShape(topStart = Radius.block, topEnd = Radius.block))
                .background(c.surface)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Kicker("Zeus", Modifier.weight(1f), color = c.accentInk)
                TextAction("Close", onClose)
            }
            turn?.let { HermesReplyContent(it, busy, onChoice, onUndo, onAllow, onDismiss, Modifier.padding(top = Space.s, bottom = Space.s)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(
                    draft, { draft = it }, if (capture?.listening == true) "Listening" else "Open, call, set an alarm, or ask",
                    Modifier.weight(1f).focusRequester(focus), onSubmit = submit,
                )
                Spacer(Modifier.width(Space.s))
                if (capture != null) TextAction(if (capture.listening) "Stop" else "Speak", { if (capture.listening) capture.stop() else capture.start() })
                TextAction("Go", submit, emphasis = true, enabled = draft.isNotBlank() && !busy)
            }
            capture?.problem?.let { Line(it, Type.small, c.ink3) }
            Kicker("Runs on the phone. Calls and texts ask first.", Modifier.padding(top = Space.xs))
        }
    }
}

/** The command sheet the Ask Zeus widget opens. Translucent, over any app; tapping outside closes it. */
class HermesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = runCatching { Prefs(applicationContext).load().dark }.getOrDefault(true)
        val listen = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true
        setContent {
            AthenaTheme(dark) {
                val host = rememberHermes()
                val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(Unit) { if (listen && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) mic.launch(Manifest.permission.RECORD_AUDIO) }
                HermesSheet(
                    host.turn, host.busy,
                    onSubmit = { text ->
                        // Not a command: Athena's sheet takes it as a question, the same as typing it there.
                        if (!host.tryRun(text)) {
                            startActivity(Intent(this, AssistActivity::class.java).putExtra(AssistActivity.EXTRA_ASK, text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }
                    },
                    onChoice = host::choose, onUndo = host::undo, onAllow = host::allow, onDismiss = host::dismiss, onClose = { finish() },
                    listenNow = listen && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                    focusNow = !listen,
                )
            }
        }
    }

    companion object {
        const val EXTRA_LISTEN = "dev.phonerecall.hermes.LISTEN"
    }
}
