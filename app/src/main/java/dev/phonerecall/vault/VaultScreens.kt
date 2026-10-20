package dev.phonerecall.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private val stamp: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
fun stamp(at: Long): String = stamp.format(Date(at))

/** No screenshots or recents preview while this is on screen. */
@Composable
fun SecureWindow() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val w = context.activity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** Locks when the screen goes off, or after 60 s away. */
@Composable
fun GateLifecycle(gate: VaultGate) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        gate.stopped(screenOff = context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { gate.started() }
}

@Composable
fun rememberBiometric(): Biometric {
    val context = LocalContext.current
    return remember(context) { AndroidBiometric(context) }
}

/** The locked page of a fingerprint screen. */
@Composable
fun LockedPanel(gutter: Dp, line: String, failure: String?, onUnlock: () -> Unit, below: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Column(Modifier.padding(start = gutter, end = gutter, top = Space.xxl).widthIn(max = 560.dp)) {
        StatusWord("Locked", Tone.QUIET)
        Spacer(Modifier.height(Space.m))
        Line(line, Type.headline, c.ink2)
        failure?.let { Spacer(Modifier.height(Space.s)); Line(it, Type.small, c.alert) }
        Spacer(Modifier.height(Space.xl))
        PrimaryAction("Unlock", onUnlock)
        below()
    }
}

@Composable
fun LogRows(rows: List<VaultLogRow>, empty: String) {
    val c = Athena.colors
    if (rows.isEmpty()) { Line(empty, Type.small, c.ink3, Modifier.padding(vertical = Space.s)); return }
    Column {
        rows.forEach { r ->
            Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
                Line("${r.who} ${shelfAction(r.action)}: ${r.item}", Type.small, c.ink)
                Line(listOfNotNull(Shelf.of(r).word, stamp(r.at), r.why.takeIf { it.isNotBlank() }).joinToString(". "), Type.caption, c.ink3)
            }
        }
    }
}

// ---------------------------------------------------------------- Calypso: drop box

/** Drop something into Calypso. Works while locked: anyone may deposit, nobody can read back. */
@Composable
fun DropForm(onSeal: (ItemKind, String, ByteArray, String?) -> Unit, onPickFile: ((Pair<String, ByteArray>?) -> Unit) -> Unit, startOpen: Boolean = false) {
    val c = Athena.colors
    var open by remember { mutableStateOf(startOpen) }
    var kind by remember { mutableStateOf(ItemKind.SECRET) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var file by remember { mutableStateOf<Pair<String, ByteArray>?>(null) }
    var sealed by remember { mutableStateOf(false) }
    LaunchedEffect(sealed) { if (sealed) { delay(4000); sealed = false } }
    Column(Modifier.fillMaxWidth().padding(top = Space.xl)) {
        if (!open) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction("Drop something in", { open = true }, emphasis = true)
                if (sealed) StatusWord("Sealed", Tone.OK)
            }
            Line("Seal a key, note or file. Once in, only your fingerprint opens it.", Type.small, c.ink3, Modifier.padding(start = Space.s))
            return@Column
        }
        Kicker("Drop in", Modifier.padding(bottom = Space.xs))
        Row { ItemKind.entries.forEach { k -> TextAction(k.word, { kind = k; file = null }, emphasis = k == kind) } }
        UnderlineField(label, { label = it.take(Box.MAX_LABEL) }, "Name, like Passport scan", Modifier.fillMaxWidth())
        when (kind) {
            ItemKind.FILE -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = Space.xs)) {
                TextAction(if (file == null) "Choose a file" else "Change", { onPickFile { picked -> file = picked; if (label.isBlank() && picked != null) label = picked.first.take(Box.MAX_LABEL) } })
                file?.let { Line("${it.first}, ${it.second.size / 1024} KB", Type.small, c.ink2) }
            }
            ItemKind.SECRET -> UnderlineField(value, { value = it }, "The secret", Modifier.fillMaxWidth(), keyboard = KeyboardType.Password, visualTransformation = PasswordVisualTransformation())
            ItemKind.NOTE -> UnderlineField(value, { value = it }, "The note", Modifier.fillMaxWidth())
        }
        val ready = if (kind == ItemKind.FILE) file != null else value.isNotEmpty()
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.padding(top = Space.xs)) {
            TextAction("Seal it", {
                if (kind == ItemKind.FILE) file?.let { onSeal(kind, label, it.second, it.first) } else onSeal(kind, label, value.toByteArray(), null)
                label = ""; value = ""; file = null; open = false; sealed = true
            }, emphasis = true, enabled = ready)
            TextAction("Cancel", { label = ""; value = ""; file = null; open = false })
        }
    }
}

/** An item the owner opened, shown for 30 s. */
class OpenedItem(val item: SealedItem, val opened: Opened)

/** Calypso's sealed items (unlocked), without Android: for the screen and the screenshot test. */
@Composable
fun SealedContent(
    items: List<SealedItem>,
    opened: Map<String, OpenedItem>,
    failure: String?,
    onOpen: (SealedItem) -> Unit,
    onClose: (SealedItem) -> Unit,
    onCopy: (OpenedItem) -> Unit,
    onSave: (OpenedItem) -> Unit,
    onMove: (SealedItem) -> Unit,
    onDelete: (SealedItem) -> Unit,
) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(top = Space.l, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Kicker("Items", Modifier.weight(1f))
            StatusWord(if (items.isEmpty()) "Empty" else "${items.size} inside", Tone.QUIET)
        }
        failure?.let { Line(it, Type.small, c.alert, Modifier.padding(bottom = Space.s)) }
        if (items.isEmpty()) Line("Nothing sealed yet. Iris, Atlas, Athena and Zeus can drop things in; only you can take them out.", Type.small, c.ink3)
        items.forEach { it ->
            val o = opened[it.id]
            Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(it.label, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 2)
                        Line("${it.kind.word}, from ${it.from}, ${stamp(it.at)}", Type.small, c.ink3)
                    }
                    StatusWord(if (o != null) "Open" else "Sealed", if (o != null) Tone.ACTIVE else Tone.QUIET)
                }
                if (o != null) {
                    Spacer(Modifier.height(Space.s))
                    when (it.kind) {
                        ItemKind.SECRET -> FactRow("Value", mask(o.opened.text))
                        ItemKind.NOTE -> Line(o.opened.text, Type.body, c.ink, Modifier.padding(vertical = Space.xs))
                        ItemKind.FILE -> FactRow("File", "${o.opened.name ?: it.label}, ${o.opened.data.size / 1024} KB")
                    }
                    Line("Closes by itself in 30 s.", Type.caption, c.ink3)
                }
                FlowRow(Modifier.padding(top = Space.xs)) {
                    if (o == null) TextAction("Open", { onOpen(it) }, emphasis = true)
                    else {
                        if (it.kind == ItemKind.FILE) TextAction("Save a copy", { onSave(o) }) else TextAction("Copy", { onCopy(o) })
                        TextAction("Close", { onClose(it) })
                    }
                    if (it.kind != ItemKind.FILE) TextAction("Move to Shielded", { onMove(it) })
                    TextAction("Delete", { onDelete(it) })
                }
            }
        }
    }
}

/** A secret on screen: the first four and the last four, the rest hidden. */
fun mask(v: String): String = if (v.length < 12) "•".repeat(8) else v.take(4) + "•".repeat(10) + v.takeLast(4)

private fun readPicked(context: Context, uri: Uri): Pair<String, ByteArray>? = runCatching {
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "file"
    val bytes = context.contentResolver.openInputStream(uri)?.use { s ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() <= Box.MAX_BYTES) { val k = s.read(buf); if (k < 0) break; out.write(buf, 0, k) }
        out.toByteArray()
    } ?: return null
    if (bytes.size > Box.MAX_BYTES) null else name to bytes
}.getOrNull()

/** The drop box row for Calypso's locked page and its unlocked list. */
@Composable
fun CalypsoDrop(onSealed: () -> Unit = {}) {
    val context = LocalContext.current
    val vault = remember { VaultCore.get(context) }
    val scope = rememberCoroutineScope()
    var pickThen by remember { mutableStateOf<((Pair<String, ByteArray>?) -> Unit)?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val then = pickThen ?: return@rememberLauncherForActivityResult
        pickThen = null
        scope.launch { then(uri?.let { withContext(Dispatchers.IO) { readPicked(context, it) } }) }
    }
    DropForm(
        onSeal = { kind, label, data, name -> scope.launch(Dispatchers.IO) { vault.calypso.drop.deposit(Agent.OWNER, kind, label, data, name); withContext(Dispatchers.Main) { onSealed() } } },
        onPickFile = { then -> pickThen = then; picker.launch(arrayOf("*/*")) },
    )
}

/**
 * Calypso's sealed items, for the unlocked page: each open is its own fingerprint, bound to that item's key.
 * `onLog` gets the Sealed rows for the vault's one log; `onChange` tells the other shelf something moved.
 */
@Composable
fun CalypsoSealed(changes: Int = 0, onLog: (List<VaultLogRow>) -> Unit = {}, onChange: () -> Unit = {}) {
    val context = LocalContext.current
    val vault = remember { VaultCore.get(context) }
    val bio = rememberBiometric()
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<SealedItem>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    val opened = remember { mutableStateMapOf<String, OpenedItem>() }
    var saving by remember { mutableStateOf<OpenedItem?>(null) }
    var moving by remember { mutableStateOf<SealedItem?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val o = saving ?: return@rememberLauncherForActivityResult
        saving = null
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(o.opened.data) } }
            vault.calypso.note(o.item.label, "saved a copy")
        }
    }
    LaunchedEffect(bump, changes) {
        withContext(Dispatchers.IO) {
            // Take in what Iris and Atlas dropped on Lemnos, then list.
            runCatching { vault.syncDrops() }
            val i = vault.calypso.items(); val l = vault.calypso.log()
            withContext(Dispatchers.Main) { items = i; onLog(l) }
        }
    }
    // Opened items close by themselves.
    LaunchedEffect(opened.keys.toSet()) { if (opened.isNotEmpty()) { delay(30_000L); opened.clear() } }
    val refresh: () -> Unit = { bump++; onChange() }
    Column {
        SealedContent(
            items, opened, failure,
            onOpen = { s -> failure = null; vault.calypso.open(s.id, bio, { item, o -> opened[item.id] = OpenedItem(item, o); refresh() }, { failure = it }) },
            onClose = { opened.remove(it.id) },
            onCopy = { o -> SecureClipboard.copy(context, o.item.label, o.opened.text); scope.launch(Dispatchers.IO) { vault.calypso.note(o.item.label, "copied (clears in 30 s)") }; refresh() },
            onSave = { o -> saving = o; saver.launch(o.opened.name ?: o.item.label) },
            onMove = { moving = it },
            onDelete = { s -> vault.calypso.delete(s.id, bio, { opened.remove(s.id); refresh() }, { failure = it }) },
        )
        moving?.let { m ->
            MoveToShielded(m.label, onMove = { name, sc ->
                moving = null
                vault.calypso.moveToAegis(m.id, vault.aegis, name, sc, bio, { opened.remove(m.id); refresh() }, { failure = it })
            }, onCancel = { moving = null })
        }
        CalypsoDrop(onSealed = refresh)
    }
}

@Composable
private fun MoveToShielded(label: String, onMove: (String, AegisScope) -> Unit, onCancel: () -> Unit) {
    val c = Athena.colors
    var name by remember { mutableStateOf(label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)) }
    var scope by remember { mutableStateOf(AegisScope.ATHENA) }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Kicker("Move to Shielded")
        Line("Agents will be able to use it (every use is logged). Your fingerprint opens it first.", Type.small, c.ink3)
        UnderlineField(name, { name = it.take(60) }, "Name agents use, like delivery_address", Modifier.fillMaxWidth())
        Row { AegisScope.entries.forEach { s -> TextAction(s.word, { scope = s }, emphasis = s == scope) } }
        Row {
            TextAction("Move", { if (name.isNotBlank()) onMove(name.trim(), scope) }, emphasis = true, enabled = name.isNotBlank())
            TextAction("Cancel", onCancel)
        }
    }
}

// ---------------------------------------------------------------- Shielded (Aegis)

/** The Shielded shelf's items (unlocked), without Android: for the screen and the screenshot test. `values` are the ones he chose to show. */
@Composable
fun ShieldedContent(
    items: List<AegisItem>,
    values: Map<String, String>,
    onShow: (AegisItem) -> Unit,
    onHide: (AegisItem) -> Unit,
    onRevoke: (AegisItem) -> Unit,
    onScope: (AegisItem) -> Unit,
    onToSealed: (AegisItem) -> Unit,
    onDelete: (AegisItem) -> Unit,
    onAdd: (String, String, String, AegisScope) -> Unit,
) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(top = Space.l, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Kicker("Items", Modifier.weight(1f))
            StatusWord(if (items.isEmpty()) "Empty" else "${items.size} inside", Tone.QUIET)
        }
        if (items.isEmpty()) Line("Nothing yet. Add your delivery address, loyalty numbers, Wi-Fi details or sizes.", Type.small, c.ink3)
        items.forEach { a ->
            val shown = values[a.name]
            Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(a.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        if (a.note.isNotBlank()) Line(a.note, Type.small, c.ink3, maxLines = 2)
                    }
                    StatusWord(if (a.revoked) "Agents off" else a.scope.word, if (a.revoked) Tone.ALERT else Tone.QUIET)
                }
                FactRow("Value", shown ?: "••••••••")
                FactRow("Used", when (a.uses) { 0 -> "Not yet"; 1 -> "Once"; else -> "${a.uses} times" })
                FlowRow {
                    TextAction(if (shown == null) "Show" else "Hide", { if (shown == null) onShow(a) else onHide(a) })
                    TextAction(if (a.revoked) "Allow agents" else "Revoke agents", { onRevoke(a) })
                    TextAction(if (a.scope == AegisScope.ATHENA) "Share with workers" else "Athena only", { onScope(a) })
                    TextAction("Move to Sealed", { onToSealed(a) })
                    TextAction("Delete", { onDelete(a) })
                }
            }
        }
        AegisAdd(onAdd)
    }
}

@Composable
private fun AegisAdd(onAdd: (String, String, String, AegisScope) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var scope by remember { mutableStateOf(AegisScope.ATHENA) }
    Column(Modifier.fillMaxWidth().padding(top = Space.m)) {
        if (!open) { TextAction("Add an item", { open = true }, emphasis = true); return@Column }
        UnderlineField(name, { name = it.take(60) }, "Name, like delivery_address", Modifier.fillMaxWidth())
        UnderlineField(value, { value = it }, "Value", Modifier.fillMaxWidth())
        UnderlineField(note, { note = it.take(160) }, "What it is for agents (optional)", Modifier.fillMaxWidth())
        Row { AegisScope.entries.forEach { s -> TextAction(s.word, { scope = s }, emphasis = s == scope) } }
        Row {
            TextAction("Save", { onAdd(name.trim(), value, note, scope); name = ""; value = ""; note = ""; open = false }, emphasis = true, enabled = name.isNotBlank() && value.isNotEmpty())
            TextAction("Cancel", { open = false })
        }
    }
}

/** Shielded's state, kept by the vault screen so switching shelves keeps its lock. Its gate is its own, as before. */
class ShieldedShelf(val gate: VaultGate = VaultGate()) {
    var items by mutableStateOf<List<AegisItem>>(emptyList())
    var log by mutableStateOf<List<VaultLogRow>>(emptyList())
    val values = mutableStateMapOf<String, String>()

    fun unlock(bio: Biometric) = gate.unlock(bio, "Open Shielded", "Shielded items are behind your fingerprint")
}

/** Loads Shielded while its gate is open; locks with the screen off or after 60 s away. `changes` reloads it. */
@Composable
fun rememberShielded(changes: Int): ShieldedShelf {
    val context = LocalContext.current
    val vault = remember { VaultCore.get(context) }
    val s = remember { ShieldedShelf() }
    GateLifecycle(s.gate)
    LaunchedEffect(s.gate.unlocked, changes) {
        if (!s.gate.unlocked) { s.values.clear(); s.items = emptyList(); s.log = emptyList(); return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            val i = vault.aegis.items(); val l = vault.aegis.log()
            withContext(Dispatchers.Main) { s.items = i; s.log = l }
        }
    }
    // Values on screen hide again after a minute.
    LaunchedEffect(s.values.keys.toSet()) { if (s.values.isNotEmpty()) { delay(60_000L); s.values.clear() } }
    return s
}

/** The Shielded shelf, unlocked. `onChange` reloads both shelves and the log. */
@Composable
fun ShieldedItems(s: ShieldedShelf, onChange: () -> Unit) {
    val context = LocalContext.current
    val vault = remember { VaultCore.get(context) }
    val scope = rememberCoroutineScope()
    fun io(block: () -> Unit) { scope.launch(Dispatchers.IO) { block(); withContext(Dispatchers.Main) { onChange() } } }
    ShieldedContent(
        s.items, s.values,
        onShow = { a -> scope.launch(Dispatchers.IO) { val v = vault.aegis.reveal(a.name); withContext(Dispatchers.Main) { v?.let { s.values[a.name] = it }; onChange() } } },
        onHide = { s.values.remove(it.name) },
        onRevoke = { a -> io { vault.aegis.revoke(a.name, !a.revoked) } },
        onScope = { a -> io { vault.aegis.scope(a.name, if (a.scope == AegisScope.ATHENA) AegisScope.AGENTS else AegisScope.ATHENA) } },
        onToSealed = { a -> s.values.remove(a.name); io { vault.aegis.moveToCalypso(a.name, vault.calypso.drop) } },
        onDelete = { a -> s.values.remove(a.name); io { vault.aegis.remove(a.name) } },
        onAdd = { n, v, note, sc -> io { vault.aegis.put(n, v, note, sc) } },
    )
}

// ---------------------------------------------------------------- Calypso: one screen, two shelves

/**
 * Calypso's frame without Android: the header, the shelf tabs, then the shelf in view. A locked shelf
 * shows its own locked page; an open one shows its items and the one log (rows of shelves still locked
 * stay out of it). Wide puts the log in a second column.
 */
@Composable
fun VaultContent(
    gutter: Dp,
    wide: Boolean,
    shelf: Shelf,
    onShelf: (Shelf) -> Unit,
    unlocked: Boolean,
    failure: String?,
    onUnlock: () -> Unit,
    log: List<VaultLogRow>,
    /** The other shelf, when it is locked: its rows are not in the log. */
    otherLocked: Boolean,
    /** Under Unlock on the locked page (Sealed: the drop box, so deposits work while locked). */
    locked: @Composable () -> Unit,
    items: @Composable () -> Unit,
) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.VAULT.title(), gutter, emblem = Place.VAULT, subtitle = Place.VAULT.subtitle())
        dev.phonerecall.lemnos.TextTabs(Shelf.entries.map { it.word }, shelf.ordinal, { onShelf(Shelf.entries[it]) }, gutter)
        Line(shelf.about, Type.small, c.ink3, Modifier.padding(start = gutter, end = gutter, top = Space.xs).widthIn(max = 560.dp))
        if (!unlocked) {
            LockedPanel(gutter, if (shelf == Shelf.SEALED) "Your fingerprint opens it." else "Hidden from people. Your fingerprint shows it.", failure, onUnlock, locked)
            return@Column
        }
        val other = Shelf.entries.first { it != shelf }
        val history: @Composable () -> Unit = {
            Column {
                // Wide: level with the items' kicker in the first column.
                Kicker("Log", Modifier.padding(top = if (wide) Space.l else Space.xl, bottom = if (wide) Space.s else Space.xs))
                LogRows(log.take(40), "Every deposit, open, copy, move and agent use shows here, with who, when and why.")
                if (otherLocked) Line("${other.word} rows show once ${other.word} is unlocked.", Type.caption, c.ink3, Modifier.padding(top = Space.s))
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize().padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                LazyColumn(Modifier.weight(1.2f), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { items() } }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = Space.xxxl)) { item { history() } }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) { item { items() }; item { history() } }
        }
    }
}
