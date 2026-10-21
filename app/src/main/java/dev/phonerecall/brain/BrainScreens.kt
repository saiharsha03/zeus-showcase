package dev.phonerecall.brain

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title

/** Fates callbacks wired to the hub, with the calendar-permission ask the card may need. */
@Composable
fun rememberFatesCallbacks(hub: BrainHub, onRefused: (String) -> Unit = {}): FatesCallbacks {
    var askingFor by remember { mutableStateOf<Long?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askingFor?.let { hub.refreshNeeds(it) } }
    // Birthdays: Google's one-time consent sheet for Google Calendar events (calendar.events only).
    val context = LocalContext.current
    val askGoogle = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { askingFor?.let { hub.refreshNeeds(it) } }
    return remember(hub) {
        FatesCallbacks(
            confirm = { id, seen, done -> hub.confirm(id, seen) { r -> done(r); if (r !in Bindings.REASONS) onRefused(r) } },
            bind = { hub.bind(it) },
            ask = { p -> hub.ask.value?.let { k -> p.status == ProposalStatus.PENDING && p.steps.any { it.kind == k } } == true },
            useful = { k, yes -> hub.useful(k, yes) },
            reject = { hub.reject(it) },
            undo = { hub.undo(it) },
            toggle = { id, i -> hub.toggleStep(id, i) },
            edit = { id, i, payload, done -> hub.edit(id, i, payload, done) },
            pickCalendar = { id, i, cal -> hub.pickCalendar(id, i, cal) },
            allowCalendar = { id -> askingFor = id; ask.launch(Manifest.permission.WRITE_CALENDAR) },
            allowGoogleCalendar = { id ->
                askingFor = id
                dev.phonerecall.data.GoogleCalendarAuth.ask(context, { askGoogle.launch(IntentSenderRequest.Builder(it).build()) }) { hub.refreshNeeds(id) }
            },
            notBirthday = { hub.notBirthday(it) },
        )
    }
}

/** Athena: the chat with her (the same Athena as the heartbeat and Agora). The class keeps its old name. */
@Composable
fun DelphiScreen(hub: BrainHub, gutter: Dp, openCalypso: () -> Unit) {
    val chat by hub.chat.collectAsStateWithLifecycle()
    val proposals by hub.proposals.collectAsStateWithLifecycle()
    val settings by hub.settings.collectAsStateWithLifecycle()
    val calendars by hub.calendars.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val cb = rememberFatesCallbacks(hub) { notice = it }
    // Hermes: his own commands run on the phone first and land in the transcript; nothing goes to Athena.
    val hermes = dev.phonerecall.hermes.rememberHermes { t -> hub.hermesRan(t.text, t.outcome.title) }
    DelphiContent(
        chat, proposals.associateBy { it.id }, calendars, settings.keySet, gutter, cb,
        onSend = { hub.send(it) }, onRetry = { hub.retry() }, onNewChat = { hub.newChat() }, openCalypso = openCalypso,
        onEarlier = { hub.earlier() },
        notice = notice,
        tryCommand = hermes::tryRun,
        reply = { dev.phonerecall.hermes.HermesReply(hermes, Modifier.padding(horizontal = gutter, vertical = Space.s)) },
    )
}

/** Delphi without the hub, so the screenshot test can draw a fixed conversation. */
@Composable
fun DelphiContent(
    chat: ChatState,
    proposals: Map<Long, Proposal>,
    calendars: List<CalendarChoice>,
    keySet: Boolean,
    gutter: Dp,
    cb: FatesCallbacks,
    onSend: (String) -> Unit,
    onRetry: () -> Unit,
    onNewChat: () -> Unit,
    openCalypso: () -> Unit,
    /** Loads the next older page of the history. */
    onEarlier: () -> Unit = {},
    notice: String? = null,
    /** Runs the words as a Hermes command; false when they aren't one. */
    tryCommand: (String) -> Boolean = { false },
    reply: (@Composable () -> Unit)? = null,
) {
    val c = Athena.colors
    var draft by rememberSaveable { mutableStateOf(ChatSeed.take().orEmpty()) }
    val busy = chat.working != null
    val list = rememberLazyListState()
    val count = chat.lines.size + (if (chat.streaming.isNotEmpty()) 1 else 0) + (if (busy || chat.error != null) 1 else 0)
    LaunchedEffect(count, chat.streaming.length / 80) { if (count > 0) list.scrollToItem(count - 1) }
    val command = remember(draft) { dev.phonerecall.hermes.Commands.parse(draft) != null }
    val send: () -> Unit = {
        if (draft.isNotBlank()) {
            if (command && tryCommand(draft.trim())) draft = ""
            // He can send while she works: it queues and goes next.
            else if (keySet) { onSend(draft); draft = "" }
        }
    }

    Box(Modifier.fillMaxSize()) {
    DelphiBackdrop()
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(Place.ORACLE.title(), gutter, emblem = Place.ORACLE, subtitle = Place.ORACLE.subtitle()) {
            if (chat.lines.isNotEmpty()) TextAction("New chat", onNewChat, enabled = !busy)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (chat.loaded && chat.lines.isEmpty() && !busy) {
                // Empty state: one sentence in Athena's voice. No suggestions.
                // Her helmeted profile, large and centred, then one sentence.
                Column(Modifier.fillMaxWidth().padding(horizontal = gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Space.xxl))
                    // Easter egg: a long press on her owl-helmed profile, and Athena says something of her own.
                    var owl by remember { mutableStateOf(-1) }
                    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
                    dev.phonerecall.ui.ArtImage(
                        dev.phonerecall.R.drawable.athena_profile, 168.dp,
                        Modifier.combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = {
                            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            owl++
                            dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.ATHENA_OWL)
                        }),
                    )
                    Spacer(Modifier.height(Space.xl))
                    if (owl >= 0) Line(dev.phonerecall.ui.Eggs.athenaLine(owl), Type.body, c.ink2, Modifier.widthIn(max = 420.dp).padding(bottom = Space.m))
                    if (keySet) Line("What do you need?", Type.headline, c.ink2)
                    else {
                        Line("Add your Anthropic key in ${Place.VAULT.title()} first.", Type.headline, c.ink2, Modifier.widthIn(max = 420.dp))
                        Spacer(Modifier.height(Space.m))
                        TextAction("Open ${Place.VAULT.title()}", openCalypso, emphasis = true)
                    }
                }
            } else {
                LazyColumn(
                    state = list,
                    contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.s, bottom = Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    if (chat.earlier) item(key = "earlier") { TextAction("Show earlier", onEarlier) }
                    itemsIndexed(chat.lines, key = { _, it -> it.id }) { i, line ->
                        when (line.kind) {
                            LineKind.USER -> UserLine(line.text)
                            // Her coin starts each run of her lines.
                            LineKind.ATHENA -> AthenaLine(line.text, first = chat.lines.getOrNull(i - 1)?.kind != LineKind.ATHENA, lineId = line.id)
                            // Which path answered: a quiet caption under her line, no label.
                            LineKind.NOTE -> if (isRoute(line.text)) Line(line.text, Type.caption, c.ink3, Modifier.padding(start = 40.dp))
                            else Row(verticalAlignment = Alignment.Top) {
                                Kicker("Zeus", Modifier.width(56.dp).padding(top = 3.dp))
                                Line(line.text, Type.small, c.ink3, Modifier.widthIn(max = 560.dp))
                            }
                            LineKind.ERROR -> Line(line.text, Type.small, c.alert)
                            // Where a chat or one of her daily sessions began: a thin rule, the history stays one scroll.
                            LineKind.DIVIDER -> Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).height(1.dp).background(c.rule))
                                Line(line.text, Type.caption, c.ink3, Modifier.padding(horizontal = Space.s))
                                Box(Modifier.weight(1f).height(1.dp).background(c.rule))
                            }
                            LineKind.MEMORY -> dev.phonerecall.oneiroi.MemoryView.decode(line.text)?.let { dev.phonerecall.oneiroi.MemoryAnswerCard(it, Modifier.widthIn(max = 620.dp)) }
                            LineKind.PROPOSAL -> proposals[line.proposalId]?.let {
                                FatesCard(it, cb, Modifier.widthIn(max = 620.dp), calendars)
                            }
                        }
                    }
                    if (chat.streaming.isNotEmpty()) item(key = "streaming") { AthenaLine(chat.streaming, first = chat.lines.lastOrNull()?.kind != LineKind.ATHENA) }
                    if (busy) item(key = "working") { StatusWord(chat.working ?: "Thinking", Tone.ACTIVE) }
                    chat.error?.let { e ->
                        item(key = "error") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Line(e, Type.small, c.alert, Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(Space.s))
                                TextAction("Retry", onRetry, emphasis = true)
                            }
                        }
                    }
                }
            }
        }
        notice?.let { Line(it, Type.small, c.ink2, Modifier.padding(horizontal = gutter)) }
        reply?.invoke()
        Row(
            Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Space.s, bottom = Space.l).widthIn(max = 720.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Commands work without a key: they never reach Athena.
            UnderlineField(draft, { draft = it }, if (keySet) "Ask Athena" else "Add a key first, or give a command", Modifier.weight(1f), onSubmit = send)
            Spacer(Modifier.width(Space.s))
            TextAction(if (command) "Run" else "Ask", send, emphasis = true, enabled = draft.isNotBlank() && (command || keySet))
        }
    }
    }
}

/** A route caption ("Quick, Haiku 5.5", "Sonnet 5.5, Athena consulted Metis", "Handed to Athena's circle"). */
fun isRoute(text: String): Boolean = text.startsWith("Quick, ") || text.startsWith("Handed to ") || BrainModel.entries.any { text == it.label || text.startsWith(it.label + ",") }

/**
 * Delphi's generated background: drawn only when a drawable named bg_delphi exists (the temple at dusk,
 * docs/art/raw/zeus2), with the same treatment as Olympus's peak: cropped behind the header, fading through a scrim.
 */
@Composable
private fun DelphiBackdrop() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val id = remember { ctx.resources.getIdentifier("bg_delphi", "drawable", ctx.packageName) }
    if (id == 0) return
    val c = Athena.colors
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        dev.phonerecall.ui.ArtImage(
            id, 420.dp, Modifier.matchParentSize(),
            shape = androidx.compose.ui.graphics.RectangleShape, alignment = androidx.compose.ui.BiasAlignment(0f, -0.8f), sized = false,
        )
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to c.surface.copy(alpha = if (c.isDark) 0.30f else 0.55f),
                    0.6f to c.surface.copy(alpha = if (c.isDark) 0.55f else 0.75f),
                    1f to c.surface,
                ),
            ),
        )
    }
}

/** One of his lines: right-aligned on a raised block. Shared with Theogony's conversation. */
@Composable
fun UserLine(text: String) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Line(
            text, Type.body, c.ink,
            Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(Radius.blockSmall)).background(c.raised)
                .padding(horizontal = Space.m, vertical = Space.s),
        )
    }
}

/** One of Athena's lines: her coin on the first of a run, the text aligned after it. Shared with Theogony's conversation. */
@Composable
fun AthenaLine(text: String, first: Boolean, lineId: Long? = null) {
    // A long press offers Good / Off: Delphi feedback that only Prometheus reads, when he runs it.
    var rate by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column {
        Row(
            if (lineId == null) Modifier else Modifier.combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { rate = !rate }),
            verticalAlignment = Alignment.Top,
        ) {
            if (first) dev.phonerecall.ui.AthenaAvatar(28.dp) else Spacer(Modifier.width(28.dp))
            Spacer(Modifier.width(Space.m))
            Line(text, Type.body, Athena.colors.ink, Modifier.widthIn(max = 600.dp).padding(top = 2.dp))
        }
        if (rate && lineId != null) Row(Modifier.padding(start = 40.dp)) {
            TextAction("Good", { dev.phonerecall.prometheus.PrometheusCore.get(ctx).feedback(lineId, true); rate = false })
            TextAction("Off", { dev.phonerecall.prometheus.PrometheusCore.get(ctx).feedback(lineId, false); rate = false })
        }
    }
}

/** Fates: the proposals inbox. Waiting ones first, then what was decided. */
@Composable
fun FatesScreen(hub: BrainHub, gutter: Dp) {
    val proposals by hub.proposals.collectAsStateWithLifecycle()
    val calendars by hub.calendars.collectAsStateWithLifecycle()
    var notice by remember { mutableStateOf<String?>(null) }
    val cb = rememberFatesCallbacks(hub) { notice = it }
    FatesContent(proposals, calendars, gutter, cb, notice)
}

@Composable
fun FatesContent(proposals: List<Proposal>, calendars: List<CalendarChoice>, gutter: Dp, cb: FatesCallbacks, notice: String? = null) {
    val c = Athena.colors
    val pending = proposals.filter { it.status == ProposalStatus.PENDING || it.status == ProposalStatus.RUNNING }
    val earlier = proposals.filter { it !in pending }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.PROPOSALS.title(), gutter, emblem = Place.PROPOSALS, subtitle = Place.PROPOSALS.subtitle())
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl), verticalArrangement = Arrangement.spacedBy(Space.blockGap)) {
            notice?.let { n -> item { Line(n, Type.small, c.alert) } }
            item { Kicker("Waiting for you", Modifier.padding(top = Space.s)) }
            if (pending.isEmpty()) item { Line("Nothing is waiting for you.", Type.body, c.ink2) }
            items(pending, key = { it.id }) { FatesCard(it, cb, Modifier.widthIn(max = 620.dp), calendars) }
            if (earlier.isNotEmpty()) item { Kicker("Earlier", Modifier.padding(top = Space.l)) }
            items(earlier, key = { it.id }) { p ->
                // Undo stays reachable on things done automatically; the rest is history.
                FatesCard(p, cb, Modifier.widthIn(max = 620.dp), compact = !p.undoable)
            }
        }
    }
}

/** A first line for the chat, set by another screen just before it opens Delphi (Hestia's "Edit by chat"). */
object ChatSeed {
    @Volatile var text: String? = null
    fun take(): String? = text.also { text = null }
}
