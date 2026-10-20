package dev.phonerecall.lemnos

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
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

val LEMNOS_TABS = listOf("Terminal", "Desktop", "Files", "Services", "Hetzner")

/**
 * Lemnos, the forge beneath: the VPS. Opens with his fingerprint once per app session (locks again when the
 * screen goes off or after 5 minutes away). Everything here is UI-only: no model or agent can call it.
 */
@Composable
fun LemnosScreen(hub: LemnosHub, wide: Boolean, gutter: Dp, openVault: () -> Unit) {
    val s by hub.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var stoppedAt by remember { mutableLongStateOf(0L) }
    var failure by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { hub.refreshPairing() }
    // The laptop writes the certificate while this screen is open: look for it every 2 s until it arrives.
    LaunchedEffect(s.pairing) { while (s.pairing == DeviceKeys.State.WAITING) { kotlinx.coroutines.delay(2_000); hub.refreshPairing() } }
    // The laptop writes the certificate while this screen is open: look for it every 2 s until it arrives.
    LaunchedEffect(s.pairing) { while (s.pairing == DeviceKeys.State.WAITING) { kotlinx.coroutines.delay(2_000); hub.refreshPairing() } }
    LaunchedEffect(s.pairing, s.unlocked) { if (s.pairing == DeviceKeys.State.PAIRED && s.unlocked) hub.refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        stoppedAt = System.currentTimeMillis()
        if (ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false) hub.setUnlocked(false)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 5 * 60_000L) hub.setUnlocked(false)
        hub.refreshPairing()
    }
    val unlock = { failure = null; unlockPrompt(ctx, "Open ${Place.VPS.myth}", "Your server's terminal and files", { hub.setUnlocked(true) }, { failure = it }) }
    LaunchedEffect(s.pairing) { if (s.pairing == DeviceKeys.State.PAIRED && !s.unlocked) unlock() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.VPS.title(), gutter, emblem = Place.VPS, subtitle = Place.VPS.subtitle()) {
            if (s.unlocked && s.pairing == DeviceKeys.State.PAIRED) StatusWord(if (s.online == false) "Offline" else s.health?.let { "Up ${Fmt.uptime(it.uptime)}" } ?: "…", if (s.online == false) Tone.ALERT else Tone.OK)
        }
        when {
            s.pairing != DeviceKeys.State.PAIRED -> PairingPanel(s.pairing, s.error, gutter, hub::beginPairing, hub::finishPairing)
            !s.unlocked -> Column(Modifier.padding(start = gutter, end = gutter, top = Space.xxl).widthIn(max = 560.dp)) {
                StatusWord("Locked", Tone.QUIET)
                Spacer(Modifier.height(Space.m))
                Line("Your fingerprint opens the forge.", Type.headline, Athena.colors.ink2)
                failure?.let { Spacer(Modifier.height(Space.s)); Line(it, Type.small, Athena.colors.alert) }
                Spacer(Modifier.height(Space.xl))
                PrimaryAction("Unlock", unlock)
            }
            else -> {
                TextTabs(LEMNOS_TABS, tab, { tab = it }, gutter)
                s.error?.let { Line(it, Type.small, Athena.colors.alert, Modifier.padding(horizontal = gutter), maxLines = 2) }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> TerminalTab(hub, s, wide, gutter)
                        1 -> DesktopTab(hub, wide, gutter)
                        2 -> FilesTab(hub, gutter)
                        3 -> ServicesTab(hub, s, gutter)
                        else -> HetznerTab(gutter, s.health, openVault)
                    }
                }
            }
        }
    }
}

/** Not paired yet: the three steps, with what the owner (or the laptop) does at each. */
@Composable
fun PairingPanel(state: DeviceKeys.State, error: String?, gutter: Dp, onBegin: () -> Unit, onFinish: () -> Unit) {
    val c = Athena.colors
    Column(Modifier.padding(start = gutter, end = gutter, top = Space.l).widthIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        StatusWord(when (state) { DeviceKeys.State.NONE -> "Not paired"; DeviceKeys.State.WAITING -> "Waiting for the laptop"; DeviceKeys.State.CERT_ARRIVED -> "Certificate arrived"; else -> "Paired" }, if (state == DeviceKeys.State.CERT_ARRIVED) Tone.ACTIVE else Tone.QUIET)
        Spacer(Modifier.height(Space.m))
        Line("Pair this phone with Lemnos", Type.headline, c.ink)
        Spacer(Modifier.height(Space.s))
        Line("Zeus makes two keys inside this phone's Keystore. They never leave it: the server only ever sees a certificate request and a public key.", Type.small, c.ink2)
        Spacer(Modifier.height(Space.l))
        Step(1, "Make the keys", "A client key for the connection, and an approval key that signs only after your fingerprint.", state == DeviceKeys.State.NONE)
        Step(2, "Run the pair script on the laptop", "tools/pair_zeus.py reads the request over adb, the server signs it, and the certificate comes back the same way.", state == DeviceKeys.State.WAITING)
        Step(3, "Finish", "Zeus puts the certificate on its key and deletes the pairing files.", state == DeviceKeys.State.CERT_ARRIVED)
        error?.let { Spacer(Modifier.height(Space.s)); Line(it, Type.small, c.alert) }
        Spacer(Modifier.height(Space.xl))
        when (state) {
            DeviceKeys.State.NONE -> PrimaryAction("Make the keys", onBegin)
            DeviceKeys.State.WAITING -> Row(verticalAlignment = Alignment.CenterVertically) {
                Line("Waiting for the certificate.", Type.small, c.ink3)
                Spacer(Modifier.width(Space.s))
                TextAction("Make a new request", onBegin)
            }
            DeviceKeys.State.CERT_ARRIVED -> PrimaryAction("Finish pairing", onFinish)
            DeviceKeys.State.PAIRED -> Unit
        }
    }
}

@Composable
private fun Step(n: Int, title: String, about: String, current: Boolean) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Kicker("$n", Modifier.width(28.dp).padding(top = 4.dp), color = if (current) c.accentInk else c.ink3)
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), if (current) c.ink else c.ink2)
            Line(about, Type.small, c.ink3)
        }
    }
}

// ---- Terminal ----

private fun label(t: TermSession): String = when (t.agent) {
    "iris" -> dev.phonerecall.ui.Workers.IRIS
    "atlas" -> dev.phonerecall.ui.Workers.ATLAS
    else -> if (t.kind == "shell") "Shell" else t.name
}

@Composable
private fun TerminalTab(hub: LemnosHub, s: LemnosState, wide: Boolean, gutter: Dp) {
    LaunchedEffect(Unit) { hub.refreshSessions() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val sessions = s.sessions
    val current = sessions.firstOrNull { it.name == selected } ?: sessions.firstOrNull { it.agent == "iris" } ?: sessions.firstOrNull()
    var status by remember { mutableStateOf("") }
    var history by remember { mutableStateOf<String?>(null) }
    val link = current?.let { rememberTermLink(it.name) { st -> status = st } }
    val actions = TermActions(
        select = { selected = it },
        newShell = { hub.runAsync("Shell") { val n = Gateway.newShell(); selected = n; hub.refreshSessions() } },
        closeShell = { n -> hub.runAsync("Shell") { Gateway.closeShell(n); if (selected == n) selected = null; hub.refreshSessions() } },
        send = { n, text -> hub.runAsync("Send") { Gateway.input(n, text, true) } },
        history = { n -> hub.runAsync("Screen") { history = Gateway.screen(n) } },
        reconnect = { link?.reconnect() },
    )
    TerminalContent(sessions, current, s.slash, status, wide, gutter, actions, link) { m -> TerminalPane(link, m) }
    history?.let { HistorySheet(current?.let(::label) ?: "", it, gutter) { history = null } }
}

class TermActions(
    val select: (String) -> Unit = {},
    val newShell: () -> Unit = {},
    val closeShell: (String) -> Unit = {},
    val send: (String, String) -> Unit = { _, _ -> },
    val history: (String) -> Unit = {},
    val reconnect: () -> Unit = {},
)

/**
 * Cover: sessions as a strip, the terminal full width. Inner: the session list beside the terminal.
 * Below the terminal: the keys a soft keyboard lacks, then slash commands and a line to type into the
 * session (sent as keystrokes, then Enter: how Claude Code and Codex take slash commands).
 */
@Composable
fun TerminalContent(
    sessions: List<TermSession>, current: TermSession?, slash: Map<String, List<SlashCmd>>, status: String, wide: Boolean, gutter: Dp,
    a: TermActions, link: TermLink? = null, pane: @Composable (Modifier) -> Unit,
) {
    val c = Athena.colors
    var draft by rememberSaveable(current?.name) { mutableStateOf("") }
    val cmds = slash[current?.kind].orEmpty()
    val bottom: @Composable () -> Unit = {
        KeysRow(link)
        if (cmds.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (cmd in cmds) TextAction(cmd.cmd, { draft = cmd.cmd + " " })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.s), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(draft, { draft = it }, if (current?.agent != null) "Type to ${label(current)}, or a slash command" else "A command line", Modifier.weight(1f), style = Type.small,
                onSubmit = { if (current != null && draft.isNotBlank()) { a.send(current.name, draft.trim()); draft = "" } })
            TextAction("Send", { if (current != null && draft.isNotBlank()) { a.send(current.name, draft.trim()); draft = "" } }, emphasis = true, enabled = current != null && draft.isNotBlank())
        }
    }
    val head: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line(current?.let(::label) ?: "No session", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 1)
                Line(listOfNotNull(current?.name, status.ifBlank { null }).joinToString("  ·  "), Type.caption, c.ink3, maxLines = 1)
            }
            if (current != null) { TextAction("History", { a.history(current.name) }); TextAction("Reconnect", a.reconnect) }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize().imePadding()) {
            Column(Modifier.width(232.dp).fillMaxHeight().padding(start = gutter).verticalScroll(rememberScrollState())) {
                Kicker("Sessions", Modifier.padding(top = Space.s, bottom = Space.xs))
                for (t in sessions) ListRow(label(t), (if (label(t) == t.name) "tmux session" else t.name) + if (t.attached > 0) "  ·  attached" else "", onClick = { a.select(t.name) }) {
                    if (t.name == current?.name) StatusWord("Open", Tone.ACTIVE)
                    else if (t.kind == "shell") TextAction("Close", { a.closeShell(t.name) })
                }
                TextAction("New shell", a.newShell, Modifier.padding(top = Space.s).offset(x = -Space.s))
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = Space.m, end = gutter)) {
                head()
                pane(Modifier.weight(1f).fillMaxWidth())
                bottom()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter - Space.s), verticalAlignment = Alignment.CenterVertically) {
                for (t in sessions) TextAction(label(t), { a.select(t.name) }, emphasis = t.name == current?.name)
                TextAction("+ Shell", a.newShell)
                if (current?.kind == "shell") TextAction("Close shell", { a.closeShell(current.name) })
            }
            head()
            pane(Modifier.weight(1f).fillMaxWidth())
            bottom()
        }
    }
}

/** The session's scrollback as plain text, selectable for copying. */
@Composable
private fun HistorySheet(title: String, text: String, gutter: Dp, onClose: () -> Unit) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Space.s, top = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Kicker("History"); Line(title, Type.title, c.ink) }
            TextAction("Close", onClose, emphasis = true)
        }
        val scroll = rememberScrollState()
        LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(horizontal = gutter, vertical = Space.s)) {
            Text(text.trimEnd(), style = Mono, color = c.ink)
        }
    }
}

// ---- Services ----

@Composable
private fun ServicesTab(hub: LemnosHub, s: LemnosState, gutter: Dp) {
    LaunchedEffect(Unit) { hub.refreshUnits() }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<UnitDetail?>(null) }
    LaunchedEffect(open) { detail = null; open?.let { u -> hub.runAsync("Logs") { detail = Gateway.unit(u) } } }
    val u = open
    if (u != null) UnitContent(u, detail, gutter, onBack = { open = null }, onAction = { act ->
        hub.runAsync("Service") { Gateway.unitAction(u, act); detail = Gateway.unit(u); hub.refreshUnits() }
    })
    else ServicesContent(s.units, gutter) { open = it }
}

@Composable
fun ServicesContent(units: List<ServiceUnit>, gutter: Dp, onOpen: (String) -> Unit) {
    var timers by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = gutter - Space.s)) {
            TextAction("Services", { timers = false }, emphasis = !timers)
            TextAction("Timers", { timers = true }, emphasis = timers)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
            val rows = units.filter { (it.kind == "timer") == timers }
                .sortedWith(compareBy({ it.active == "active" && !it.controllable }, { it.unit }))
            if (rows.isEmpty()) Line("Nothing yet.", Type.small, Athena.colors.ink3, Modifier.padding(vertical = Space.l))
            for (r in rows) ListRow(r.unit.removeSuffix(".service").removeSuffix(".timer"), r.description, titleStyle = Type.small, onClick = { onOpen(r.unit) }) {
                StatusWord(r.sub.ifBlank { r.active }, when (r.active) { "active" -> Tone.OK; "failed" -> Tone.ALERT; else -> Tone.QUIET })
            }
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

@Composable
fun UnitContent(unit: String, d: UnitDetail?, gutter: Dp, onBack: () -> Unit, onAction: (String) -> Unit) {
    val c = Athena.colors
    var confirm by remember(unit) { mutableStateOf<String?>(null) }
    LaunchedEffect(confirm) { if (confirm != null) { kotlinx.coroutines.delay(4000); confirm = null } }
    Column(Modifier.fillMaxSize().padding(horizontal = gutter)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextAction("Back", onBack)
            Spacer(Modifier.width(Space.s))
            Line(unit, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
        }
        if (d == null) { Line("Reading", Type.small, c.ink3); return@Column }
        FactRow("State", "${d.state["ActiveState"].orEmpty()} (${d.state["SubState"].orEmpty()})", d.state["Result"]?.takeIf { it != "success" }, Tone.ALERT)
        d.state["ActiveEnterTimestamp"]?.takeIf { it.isNotBlank() }?.let { FactRow("Since", it) }
        if (d.controllable) Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            for (act in listOf("restart", "start", "stop")) {
                val word = act.replaceFirstChar { it.uppercase() }
                TextAction(if (confirm == act) "Tap again to $act" else word, { if (confirm == act) { onAction(act); confirm = null } else confirm = act }, emphasis = confirm == act)
            }
        } else Line("Read only here. Restarts are allowed only for the services on the helper's list.", Type.caption, c.ink3, Modifier.padding(vertical = Space.xs))
        Kicker("Journal", Modifier.padding(top = Space.m, bottom = Space.xs))
        val scroll = rememberScrollState()
        LaunchedEffect(d.logs) { scroll.scrollTo(scroll.maxValue) }
        SelectionContainer(Modifier.weight(1f).fillMaxWidth().background(c.block(dev.phonerecall.ui.Tint.SERVER)).verticalScroll(scroll).padding(Space.s)) {
            Text(d.logs.ifBlank { "(empty)" }, style = Mono, color = c.ink)
        }
    }
}
