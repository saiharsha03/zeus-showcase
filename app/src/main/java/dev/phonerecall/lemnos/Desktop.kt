package dev.phonerecall.lemnos

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The keys a soft keyboard lacks. Modifiers are sticky: they hold for the next key. */
val DESK_KEYS: List<Pair<String, Int>> = listOf(
    "Esc" to Keysym.ESCAPE, "Tab" to Keysym.TAB, "Ctrl" to Keysym.CONTROL_L, "Alt" to Keysym.ALT_L, "Super" to Keysym.SUPER_L,
    "←" to Keysym.LEFT, "↑" to Keysym.UP, "↓" to Keysym.DOWN, "→" to Keysym.RIGHT, "Home" to Keysym.HOME, "End" to Keysym.END,
    "PgUp" to Keysym.PAGE_UP, "PgDn" to Keysym.PAGE_DOWN, "Del" to Keysym.DELETE, "Enter" to Keysym.RETURN,
) + (1..12).map { "F$it" to Keysym.f(it) }

/** What the tab says about the desktop's cost, from the unit's numbers. */
fun desktopNote(d: DesktopStatus?): String = when {
    d == null -> "Reading"
    d.running -> "Using ${Fmt.bytes(d.memory)} of its ${Fmt.bytes(d.memoryMax)} cap. Stops itself ${d.idleMinutes} min after the last viewer leaves."
    else -> "Off. While open it takes about 0.3 to 0.6 GB of Lemnos's RAM (never more than ${Fmt.bytes(d.memoryMax)}), and it stops itself ${d.idleMinutes} min after you leave."
}

class DesktopActions(
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val connect: () -> Unit = {},
    val fullScreen: () -> Unit = {},
    val keyboard: () -> Unit = {},
    val fitDesktop: () -> Unit = {},
    val zoomFit: () -> Unit = {},
    val paste: () -> Unit = {},
    val copyBack: () -> Unit = {},
    val key: (Int) -> Unit = {},
)

@Composable
fun DesktopTab(hub: LemnosHub, wide: Boolean, gutter: Dp) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<DesktopStatus?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var line by remember { mutableStateOf("") }
    var clip by remember { mutableStateOf<String?>(null) }
    var full by rememberSaveable { mutableStateOf(false) }
    var want by rememberSaveable { mutableStateOf(false) }      // he asked to view; reconnect when he comes back
    val sticky = remember { mutableStateListOf<Int>() }
    var size by remember { mutableStateOf<String?>(null) }
    val link = remember { DesktopLink({ line = it }, { clip = it }) { w, h -> size = "$w×$h" } }
    var view by remember { mutableStateOf<DesktopView?>(null) }

    suspend fun refresh() { runCatching { withContext(Dispatchers.IO) { Gateway.desktop() } }.onSuccess { status = it } }
    fun act(word: String, block: suspend () -> Unit) = scope.launch {
        busy = word
        try { block() } catch (e: Exception) { line = e.message ?: "Failed" } finally { busy = null }
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(if (status?.running == true) 5_000 else 15_000) } }
    LaunchedEffect(status?.ready, want) { if (status?.ready == true && want) link.open() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { link.close(); line = "Disconnected while Zeus is in the background" }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (want && status?.ready == true) link.open() }
    DisposableEffect(link) { onDispose { link.close() } }

    val actions = DesktopActions(
        start = { act("Starting") { status = withContext(Dispatchers.IO) { Gateway.desktopAction("start") }; want = true } },
        stop = { act("Stopping") { want = false; link.close(); status = withContext(Dispatchers.IO) { Gateway.desktopAction("stop") }; line = "" } },
        connect = { want = true; link.open() },
        fullScreen = { full = !full },
        keyboard = { view?.showKeyboard(true) },
        fitDesktop = { view?.let { v -> val (w, h) = v.idealSize(); if (link.rfb?.resizeDesktop(w, h) != true) line = "This desktop can't change size" } },
        zoomFit = { view?.fit() },
        paste = {
            val t = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
            if (t.isNullOrEmpty()) line = "Nothing on the phone's clipboard" else { link.paste(t); line = "Clipboard sent: paste with Ctrl+V on the desktop" }
        },
        key = { ks ->
            if (ks in DesktopView.MODIFIERS) { if (ks in sticky) { sticky.remove(ks); link.sticky.remove(ks) } else { sticky.add(ks); link.sticky.add(ks) } }
            else link.tap(ks)
        },
        copyBack = { clip?.let { ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Lemnos desktop", it)) }; clip = null },
    )
    val pane: @Composable (Modifier, Boolean) -> Unit = { m, interactive ->
        AndroidView(
            factory = { c -> DesktopView(c, link).also { v -> link.view = v; view = v; v.onStickyCleared = { sticky.clear() } } },
            update = { v -> v.interactive = interactive; link.view = v; view = v },
            modifier = m,
        )
    }

    if (full) Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).imePadding().navigationBarsPadding()) {
            pane(Modifier.weight(1f).fillMaxWidth(), wide)
            Row(Modifier.fillMaxWidth().background(Athena.colors.surface).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                TextAction("Exit full screen", { full = false }, emphasis = true)
                TextAction("Whole desktop", actions.zoomFit)
                if (wide) { TextAction("Keyboard", actions.keyboard); TextAction("Paste", actions.paste) }
            }
            if (wide) Box(Modifier.background(Athena.colors.surface)) { DeskKeysRow(sticky, actions.key) }
        }
    }
    DesktopContent(status, line == "Live", busy, line.takeIf { it != "Live" }.orEmpty(), clip, wide, gutter, actions, sticky.toSet(), size) { m -> if (!full) pane(m, wide) else Box(m.background(Athena.colors.block(dev.phonerecall.ui.Tint.SERVER))) }
}

@Composable
fun DeskKeysRow(sticky: Collection<Int>, onKey: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for ((label, ks) in DESK_KEYS) TextAction(if (ks in sticky) "$label on" else label, { onKey(ks) }, emphasis = ks in sticky)
    }
}

/**
 * Stopped: what it is and what it costs, and Start. Running: a status line, the actions, the picture, and (unfolded)
 * the keys row. Folded, the cover is for looking: pan and zoom, nothing typed or clicked.
 */
@Composable
fun DesktopContent(
    d: DesktopStatus?, live: Boolean, busy: String?, line: String, clip: String?, wide: Boolean, gutter: Dp, a: DesktopActions,
    sticky: Set<Int> = emptySet(), size: String? = null, pane: @Composable (Modifier) -> Unit,
) {
    val c = Athena.colors
    if (d?.running != true) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, top = Space.l).widthIn(max = 560.dp)) {
            StatusWord(busy ?: if (d == null) "Reading" else "Off", if (busy != null) Tone.ACTIVE else Tone.QUIET)
            Spacer(Modifier.height(Space.m))
            Line("Lemnos as a computer", Type.headline, c.ink)
            Spacer(Modifier.height(Space.s))
            Line("An XFCE desktop on the server, as its own user. It can't see the agents' files; it lives behind the same certificate as the terminal and has no open port.", Type.small, c.ink2)
            Spacer(Modifier.height(Space.s))
            Line(desktopNote(d), Type.small, c.ink3)
            Spacer(Modifier.height(Space.l))
            Line(if (wide) "Tap to click, hold to right-click, hold then move to drag, two fingers to scroll or zoom. A Bluetooth mouse and keyboard work as they would on a laptop."
                 else "On the cover screen it's view only: pan and pinch to zoom. Unfold for the mouse and keyboard.", Type.caption, c.ink3)
            if (line.isNotBlank() && busy == null) { Spacer(Modifier.height(Space.s)); Line(line, Type.small, c.alert, maxLines = 3) }
            Spacer(Modifier.height(Space.xl))
            if (busy == null && d != null) PrimaryAction("Start the desktop", a.start)
        }
        return
    }
    Column(Modifier.fillMaxSize().then(if (wide) Modifier.imePadding() else Modifier)) {
        Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter - Space.s), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusWord(busy ?: if (live) "Live" else if (d.ready) "Running" else "Starting", if (live) Tone.OK else Tone.ACTIVE)
                    Spacer(Modifier.padding(start = Space.s))
                    Line(listOfNotNull(if (wide) "Mouse and keyboard" else "View only", size?.takeIf { live }).joinToString("  ·  "), Type.caption, c.ink3, maxLines = 1)
                }
                Line(line.ifBlank { desktopNote(d) }, Type.caption, c.ink3, maxLines = 2)
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter - Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (!live) TextAction("Connect", a.connect, emphasis = true)
            TextAction("Full screen", a.fullScreen, emphasis = live)
            TextAction("Whole desktop", a.zoomFit)
            if (wide) {
                TextAction("Keyboard", a.keyboard)
                TextAction("Fit to screen", a.fitDesktop)
                TextAction("Paste", a.paste)
            }
            if (clip != null) TextAction("Copy desktop text", a.copyBack, emphasis = true)
            TextAction("Stop", a.stop)
        }
        pane(Modifier.weight(1f).fillMaxWidth().padding(horizontal = if (wide) gutter else 0.dp))
        if (wide) Box(Modifier.padding(horizontal = gutter - Space.s)) { DeskKeysRow(sticky, a.key) }
    }
}

/** Screenshot tests have no VNC server: a drawn stand-in of an XFCE desktop (panel, a terminal, a file window). */
@Composable
fun DesktopPreviewPane(modifier: Modifier) {
    Canvas(modifier.background(Color(0xFF15191D))) {
        val dw = 1600f; val dh = 1200f
        val s = minOf(size.width / dw, size.height / dh)
        val ox = (size.width - dw * s) / 2; val oy = (size.height - dh * s) / 2
        fun r(x: Float, y: Float, w: Float, h: Float, col: Color) = drawRect(col, Offset(ox + x * s, oy + y * s), Size(w * s, h * s))
        r(0f, 0f, dw, dh, Color(0xFF1C2228))
        r(0f, 0f, dw, 28f, Color(0xFF2B3036))
        r(8f, 6f, 90f, 16f, Color(0xFF4A525A)); r(1460f, 6f, 130f, 16f, Color(0xFF4A525A))
        // a terminal
        r(120f, 110f, 820f, 520f, Color(0xFF3A4048)); r(122f, 140f, 816f, 488f, Color(0xFF0F1215))
        for (i in 0 until 14) r(140f, 160f + i * 30f, (180 + (i * 137) % 520).toFloat(), 12f, if (i % 4 == 0) Color(0xFF8FC27A) else Color(0xFFCFC8BC))
        // a file manager
        r(700f, 380f, 760f, 520f, Color(0xFF3A4048)); r(702f, 410f, 756f, 488f, Color(0xFFEDEAE4))
        r(702f, 410f, 170f, 488f, Color(0xFFDAD5CC))
        for (i in 0 until 8) { r(900f + (i % 4) * 130f, 440f + (i / 4) * 140f, 70f, 56f, Color(0xFFE8A33D)); r(890f + (i % 4) * 130f, 506f + (i / 4) * 140f, 90f, 10f, Color(0xFF6B6259)) }
        // the pointer
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(ox + 1010f * s, oy + 300f * s); lineTo(ox + 1010f * s, oy + 330f * s); lineTo(ox + 1018f * s, oy + 323f * s); lineTo(ox + 1030f * s, oy + 322f * s); close()
        }
        drawPath(p, Color.White)
    }
}
