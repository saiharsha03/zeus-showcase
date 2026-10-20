package dev.phonerecall.lemnos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.Workers
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import java.util.UUID

/** Room ids on Lemnos, and how Zeus names them. */
val ROOMS = listOf("group" to "Everyone", "iris" to Workers.IRIS, "atlas" to Workers.ATLAS, Agora.ROOM to dev.phonerecall.ui.Place.AGORA.myth)
private fun roomName(id: String) = ROOMS.firstOrNull { it.first == id }?.second ?: id
private fun who(sender: String) = when (sender) { "owner" -> "You"; "iris" -> Workers.IRIS; "atlas" -> Workers.ATLAS; "athena" -> "Athena"; "system" -> "Lemnos"; else -> sender.replaceFirstChar { it.uppercase() } }

/**
 * Where a chat line goes. A line starting with "/" is a slash command for the agent's own CLI, so it never goes over
 * the channel (where it would just be text): in a 1:1 room it becomes a keystroke injection into that agent's tmux
 * session, after a confirm and the Lemnos fingerprint; in the group it is refused (which terminal would it go to?).
 */
sealed class ChatRoute {
    data class Chat(val text: String) : ChatRoute()
    data class Terminal(val agent: String, val session: String, val command: String) : ChatRoute()
    data class Refused(val hint: String) : ChatRoute()

    companion object {
        /** Tmux sessions on Lemnos, when /api/agents hasn't answered yet. */
        private val SESSIONS = mapOf("iris" to "claude-auto", "atlas" to "codex")

        fun of(room: String, text: String, agents: List<AgentStatus> = emptyList()): ChatRoute {
            val t = text.trim()
            if (!t.startsWith("/")) return Chat(t)
            if (room == "group" || room == Agora.ROOM) return Refused("Slash commands go into one terminal. Open ${Workers.IRIS} or ${Workers.ATLAS} and send it there.")
            val session = agents.firstOrNull { it.id == room }?.session ?: SESSIONS[room] ?: return Refused("No terminal for $room.")
            return Terminal(room, session, t.lineSequence().first().take(400))
        }
    }
}

/** One worker's state in words: process, channel, and whether a reply is owed. */
fun workerStatus(a: AgentStatus?): Pair<String, Tone> = when {
    a == null -> "Not set up" to Tone.QUIET
    !a.alive -> "Down" to Tone.ALERT
    a.channel == "not-enabled" -> "Channel off" to Tone.ACTIVE
    a.channel == "offline" -> "Channel offline" to Tone.ALERT
    a.busy -> "Thinking" to Tone.ACTIVE
    else -> "Ready" to Tone.OK
}

private fun channelLine(a: AgentStatus?): String = when (a?.channel) {
    null -> "Not on Lemnos yet"
    "connected" -> "Zeus channel connected"
    "not-enabled" -> "Zeus channel off until her next restart; messages wait"
    "offline" -> "Zeus channel enabled but not connected"
    else -> "Typed into ${a.session} (tmux); replies from the notify hook"
}

/**
 * Pantheon: the workers live from Lemnos (Iris, Atlas; Echo once he is on the VPS), the jobs Athena gave
 * them, Iris's open tasks, and the chats. Cover: the list, then a chat full screen. Inner: both side by side.
 * Task done and notes are the owner's own actions here and are signed with his fingerprint.
 */
@Composable
fun PantheonScreen(hub: LemnosHub, circle: Map<String, Long>, wide: Boolean, gutter: Dp, athena: List<dev.phonerecall.mind.LongJob> = emptyList()) {
    val s by hub.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val usage by remember { dev.phonerecall.metron.MetronCore.get(ctx) }.usage.collectAsStateWithLifecycle()
    var room by rememberSaveable { mutableStateOf<String?>(null) }
    var taskNote by remember { mutableStateOf<String?>(null) }
    val paired = s.pairing == DeviceKeys.State.PAIRED
    DisposableEffect(paired) { if (paired) hub.startLive(); onDispose { if (paired) hub.stopLive() } }
    LaunchedEffect(paired) { if (paired) { hub.refresh(); hub.refreshTasks(); hub.loadRoom(Agora.ROOM) } }
    LaunchedEffect(room, paired) { room?.let { if (paired) hub.loadRoom(it) } }
    val open = room ?: if (wide) "group" else null
    var slash by remember { mutableStateOf<ChatRoute?>(null) }    // a pending confirm, or a refusal hint
    var typed by remember { mutableStateOf<String?>(null) }
    val send: (String, String) -> Unit = { r, text ->
        typed = null
        when (val route = ChatRoute.of(r, text, s.agents)) {
            is ChatRoute.Chat -> { slash = null; hub.send(r, route.text) }
            else -> slash = route
        }
    }
    val inject: (ChatRoute.Terminal) -> Unit = { t ->
        val go = {
            hub.runAsync("Terminal") { Gateway.input(t.session, t.command, true); typed = "Typed ${t.command} into ${roomName(t.agent)}'s terminal." }
            slash = null
        }
        // The same gate as Lemnos: his fingerprint once per app session.
        if (s.unlocked) go() else unlockPrompt(ctx, "Open ${dev.phonerecall.ui.Place.VPS.myth}", "To type into ${roomName(t.agent)}'s terminal", { hub.setUnlocked(true); go() }, { typed = it })
    }
    val banner: @Composable () -> Unit = { SlashBanner(slash, typed, onConfirm = inject, onCancel = { slash = null }) }
    LaunchedEffect(open, paired) { if (wide && paired && open != null) hub.loadRoom(open) }
    // Agora's unread dot clears while it is open.
    LaunchedEffect(open, s.chat[Agora.ROOM]) { if (open == Agora.ROOM) hub.agoraSeen() }

    val taskActions = TaskActions(
        done = { t ->
            DeviceKeys.approve(ctx, ApprovalRequest("task-done-${t.id}-${UUID.randomUUID().toString().take(8)}", "iris.task_done", "zeus-gateway", mapOf("task_id" to t.id)), "Mark done: ${t.title}",
                onToken = { tok -> hub.runAsync("Iris's task") { Gateway.irisDone(t.id, tok); hub.refreshTasks() } }, onFail = { taskNote = it })
        },
        note = { t, text ->
            DeviceKeys.approve(ctx, ApprovalRequest("task-note-${t.id}-${UUID.randomUUID().toString().take(8)}", "iris.task_note", "zeus-gateway", mapOf("task_id" to t.id, "text" to text)), "Note on: ${t.title}",
                onToken = { tok -> hub.runAsync("Iris's task") { Gateway.irisNote(t.id, text, tok); hub.refreshTasks() } }, onFail = { taskNote = it })
        },
    )
    val list: @Composable (Modifier) -> Unit = { m ->
        dev.phonerecall.mind.PantheonContent(gutter, circle, dev.phonerecall.home.SampleData.workers, m,
            live = if (!paired) null else ({ liveItems(s, open, { room = it }, taskActions, taskNote, athena, usage = usage) }))
    }
    if (wide && open != null) {
        Row(Modifier.fillMaxSize()) {
            list(Modifier.weight(1f))
            ChatPane(open, s.chat[open].orEmpty(), s.agents, gutter, Modifier.weight(1.25f).statusBarsPadding(), onBack = null, banner = banner, jobs = s.jobs) { send(open, it) }
        }
    } else if (open != null) {
        ChatPane(open, s.chat[open].orEmpty(), s.agents, gutter, Modifier.statusBarsPadding(), onBack = { room = null; slash = null; typed = null }, banner = banner, jobs = s.jobs) { send(open, it) }
    } else list(Modifier)
}

/** Iris, Atlas and Athena's own jobs, each with its ledger. */
fun ledgers(s: LemnosState, athena: List<dev.phonerecall.mind.LongJob>, now: Long): List<Triple<String, String, Ledger>> =
    listOf("iris" to Workers.IRIS, "atlas" to Workers.ATLAS).map { (id, name) -> Triple(id, name, AgentLedger.worker(id, s.jobs, s.chat[id].orEmpty(), now)) } +
        Triple("athena", "Athena's jobs", AgentLedger.athena(athena, now))

class TaskActions(val done: (IrisTask) -> Unit = {}, val note: (IrisTask, String) -> Unit = { _, _ -> })

/** The live part of Pantheon, inside its list. */
fun LazyListScope.liveItems(s: LemnosState, open: String?, onRoom: (String) -> Unit, tasks: TaskActions, taskNote: String?, athena: List<dev.phonerecall.mind.LongJob> = emptyList(), now: Long = System.currentTimeMillis(), usage: dev.phonerecall.metron.UsageSnapshot? = null) {
    item { Kicker("Workers", Modifier.padding(top = Space.s, bottom = Space.xs)) }
    for ((id, name, role) in listOf(Triple("iris", Workers.IRIS, "Mail, calendar, owner.db"), Triple("atlas", Workers.ATLAS, "Server and code"), Triple("echo", Workers.ECHO, "Phone calls"))) {
        val a = s.agents.firstOrNull { it.id == id }
        item(key = "w-$id") {
            val (word, tone) = workerStatus(a)
            Column {
                ListRow(name, "$role. ${channelLine(a)}", onClick = if (a != null) ({ onRoom(id) }) else null) {
                    Column(horizontalAlignment = Alignment.End) {
                        StatusWord(word, tone)
                        if (open == id) Line("Open", Type.caption, Athena.colors.accentInk)
                    }
                }
                // Metron: how much of the plan is used, 5-hour and 7-day.
                dev.phonerecall.metron.CompactUsage(usage?.agent(id))
            }
        }
    }
    item(key = "w-group") { ListRow("Everyone", "${Workers.IRIS}, ${Workers.ATLAS} and Athena with you", onClick = { onRoom("group") }) { if (open == "group") StatusWord("Open", Tone.ACTIVE) } }
    item(key = "w-agora") {
        val unread = Agora.unread(s.chat[Agora.ROOM].orEmpty(), s.agoraSeen)
        ListRow(dev.phonerecall.ui.Place.AGORA.title(), "${dev.phonerecall.ui.Place.AGORA.motto}: ${Workers.IRIS}, ${Workers.ATLAS} and Athena coordinate; you can steer", onClick = { onRoom(Agora.ROOM) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (open == Agora.ROOM) StatusWord("Open", Tone.ACTIVE)
                else if (unread) Box(Modifier.size(8.dp).clip(CircleShape).background(Athena.colors.accentInk))
            }
        }
    }

    // The ledger: asked, answered and overdue this week. Silence shows here as a status.
    item { Kicker("Ledger", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
    for ((id, name, l) in ledgers(s, athena, now)) item(key = "l-$id") {
        val (word, tone) = AgentLedger.status(l)
        ListRow(name, AgentLedger.line(l), titleStyle = Type.small, onClick = if (id != "athena") ({ onRoom(id) }) else null) { StatusWord(word, tone) }
    }

    val jobs = s.jobs.take(8)
    if (jobs.isNotEmpty()) {
        item { Kicker("Jobs", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
        items(jobs, key = { "j-" + it.jobId }) { j ->
            ListRow(j.title, "${who(j.to)}  ·  ${if (j.approved) "approved by you" else "a suggestion"}  ·  ${Fmt.ago(j.updated)}" + (j.result?.let { "\n" + it.lineSequence().first().take(140) } ?: ""), titleStyle = Type.small) {
                StatusWord(j.status, when (j.status) { "done" -> Tone.OK; "failed" -> Tone.ALERT; "sent", "delivered", "working" -> Tone.ACTIVE; else -> Tone.QUIET })
            }
        }
    }

    val open5 = s.tasks.sortedWith(compareBy<IrisTask>({ !(it.due != null && it.due < java.time.LocalDate.now().toString()) }, { it.priority != "high" }, { !it.waitingOnYou }, { it.due ?: "9999" }))
    if (open5.isNotEmpty()) {
        item { Kicker("${Workers.IRIS}'s open tasks", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
        taskNote?.let { item { Line(it, Type.caption, Athena.colors.alert) } }
        items(open5.take(12), key = { "t-${it.id}" }) { t -> TaskRow(t, tasks) }
    }
}

@Composable
private fun TaskRow(t: IrisTask, a: TaskActions) {
    val c = Athena.colors
    var noting by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val overdue = t.due != null && t.due < java.time.LocalDate.now().toString()
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line(t.title, Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 2)
                Line(listOfNotNull(who(t.owner ?: "iris"), t.due?.let { if (overdue) "overdue $it" else "due $it" }, t.project, if (t.waitingOnYou) "waiting on you" else null).joinToString("  ·  "), Type.caption, if (overdue) c.alert else c.ink3, maxLines = 1)
            }
            if (t.priority == "high") StatusWord("High", Tone.ALERT)
        }
        if (t.notes.isNotBlank()) Line(t.notes, Type.caption, c.ink3, maxLines = 1)
        Row(Modifier.offset(x = -Space.s)) {
            TextAction("Done", { a.done(t) })
            TextAction(if (noting) "Cancel" else "Add note", { noting = !noting })
        }
        if (noting) Row(verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(text, { text = it.take(1000) }, "A note for ${Workers.IRIS}", Modifier.weight(1f), style = Type.small)
            TextAction("Sign", { if (text.isNotBlank()) { a.note(t, text.trim()); noting = false; text = "" } }, emphasis = true, enabled = text.isNotBlank())
        }
    }
}

/**
 * A room. The owner's lines are marked "You"; workers' lines carry their name. Their text is shown as text:
 * nothing in it is clickable or runnable.
 */
@Composable
fun ChatPane(room: String, msgs: List<ChatMsg>, agents: List<AgentStatus>, gutter: Dp, modifier: Modifier = Modifier, onBack: (() -> Unit)?,
             banner: @Composable () -> Unit = {}, jobs: List<WorkerJob> = emptyList(), now: Long = System.currentTimeMillis(), onSend: (String) -> Unit) {
    val c = Athena.colors
    var draft by rememberSaveable(room) { mutableStateOf("") }
    val agent = agents.firstOrNull { it.id == room }
    Column(modifier.fillMaxHeight().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = gutter - Space.s, end = gutter, top = Space.m), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) TextAction("Back", onBack)
            Column(Modifier.weight(1f).padding(start = Space.s)) {
                Line(roomName(room), Type.title, c.ink)
                Line(when (room) { "group" -> "Both answer; Atlas adds or passes"; Agora.ROOM -> "Where the agents meet. You read it and can steer"; else -> channelLine(agent) }, Type.caption, c.ink3, maxLines = 1)
            }
            if (agent != null) workerStatus(agent).let { (w, t) -> StatusWord(w, t) }
        }
        // This agent's ledger, under its name: overdue in the alert colour.
        if (room == "iris" || room == "atlas") {
            val l = AgentLedger.worker(room, jobs, msgs, now)
            Row(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Space.xxs), verticalAlignment = Alignment.CenterVertically) {
                Line(AgentLedger.line(l), Type.caption, c.ink2, Modifier.weight(1f), maxLines = 1)
                AgentLedger.status(l).let { (w, t) -> StatusWord(w, t) }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(horizontal = gutter, vertical = Space.s)) {
            if (msgs.isEmpty()) item { Line("Nothing here yet.", Type.small, c.ink3, Modifier.padding(vertical = Space.l)) }
            items(msgs.asReversed(), key = { it.id }) { m -> Message(m) }
        }
        Column(Modifier.padding(horizontal = gutter)) { banner() }
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(draft, { draft = it }, "Message ${roomName(room)}", Modifier.weight(1f), onSubmit = { if (draft.isNotBlank()) { onSend(draft.trim()); draft = "" } })
            TextAction("Send", { if (draft.isNotBlank()) { onSend(draft.trim()); draft = "" } }, emphasis = true, enabled = draft.isNotBlank())
        }
    }
}

@Composable
private fun Message(m: ChatMsg) {
    val c = Athena.colors
    val mine = m.sender == "owner"
    Column(Modifier.fillMaxWidth().padding(vertical = Space.s).padding(start = if (mine) 40.dp else 0.dp, end = if (mine) 0.dp else 24.dp), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(who(m.sender), color = if (mine) c.accentInk else c.ink3)
            Spacer(Modifier.width(Space.s))
            Line(Fmt.ago(m.ts), Type.caption, c.ink3)
            if (m.kind == "job") { Spacer(Modifier.width(Space.s)); StatusWord("Job", Tone.ACTIVE) }
            if (m.kind == "notice") { Spacer(Modifier.width(Space.s)); StatusWord("Note", Tone.QUIET) }
            if (m.kind == "held") { Spacer(Modifier.width(Space.s)); StatusWord("Not relayed", Tone.QUIET) }
        }
        Spacer(Modifier.height(Space.xxs))
        SelectionContainer { Line(m.text, Type.body, if (m.kind == "pass") c.ink3 else c.ink, Modifier.widthIn(max = 620.dp)) }
    }
}

/** The confirm for a slash command ("Type /compact into Iris's terminal?"), a refusal hint, or what was just typed. */
@Composable
fun SlashBanner(route: ChatRoute?, typed: String?, onConfirm: (ChatRoute.Terminal) -> Unit, onCancel: () -> Unit) {
    val c = Athena.colors
    when (route) {
        is ChatRoute.Terminal -> Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
            Kicker("Slash command", color = c.accentInk)
            Line("Type ${route.command} into ${roomName(route.agent)}'s terminal?", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
            Line("It goes into ${route.session} as keystrokes, then Enter, the same as typing it in ${dev.phonerecall.ui.Place.VPS.myth}. Not sent as a message.", Type.caption, c.ink3)
            Row(Modifier.offset(x = -Space.s)) {
                TextAction("Type it", { onConfirm(route) }, emphasis = true)
                TextAction("Cancel", onCancel)
            }
        }
        is ChatRoute.Refused -> Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Line(route.hint, Type.small, c.ink2, Modifier.weight(1f))
            TextAction("OK", onCancel)
        }
        else -> typed?.let { Line(it, Type.caption, c.ok, Modifier.padding(vertical = Space.xs)) }
    }
}
