package dev.phonerecall.lemnos

import android.annotation.SuppressLint
import android.graphics.Color as AColor
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/**
 * One live terminal: the mTLS WebSocket to `tmux attach` on Lemnos, bridged to xterm.js in a WebView.
 * The page itself never touches the network; it only sees bytes handed over here.
 */
class TermLink(private val session: String, private val onStatus: (String) -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ws: WebSocket? = null
    @Volatile var web: WebView? = null
    @Volatile private var cols = 80
    @Volatile private var rows = 24
    @Volatile private var started = false
    /** The keys row's Ctrl: the next letter typed becomes its control code. */
    @Volatile var ctrl = false
    /** Background, text and accent as CSS colours, from the app's tokens (set by [TerminalPane]). */
    @Volatile var theme = Triple("#1C2228", "#F2ECE2", "#E8A33D")

    private fun js(call: String) { web?.let { w -> w.post { w.evaluateJavascript(call, null) } } }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    /** Called by the page once it has measured itself, so tmux attaches at the right size. */
    fun start() {
        if (started) return
        started = true
        onStatus("Connecting")
        scope.launch {
            try {
                val ticket = Gateway.termTicket(session)
                ws = Gateway.openTerminal(ticket, cols, rows, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) = onStatus("Live")
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                        if (o.optString("t") == "history") js("zh('${b64(o.optString("d").toByteArray(Charsets.UTF_8))}')")
                    }
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = js("zw('${b64(bytes.toByteArray())}')")
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onStatus("Closed"); started = false }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { onStatus("Dropped: ${t.message ?: t.javaClass.simpleName}"); started = false }
                })
            } catch (e: Exception) { onStatus("Couldn't attach: ${e.message}"); started = false }
        }
    }

    fun send(data: String) {
        var d = data
        if (ctrl && d.length == 1 && d[0].lowercaseChar() in 'a'..'z') { d = (d[0].uppercaseChar().code - 64).toChar().toString(); ctrl = false }
        ws?.send(JSONObject().put("t", "in").put("d", d).toString())
    }

    fun resize(c: Int, r: Int) {
        cols = c; rows = r
        if (ws == null) start() else ws?.send(JSONObject().put("t", "resize").put("cols", c).put("rows", r).toString())
    }

    fun reconnect() { close(); js("zclear()"); start() }

    fun close() { ws?.close(1000, null); ws = null; started = false }

    /** What the page calls. */
    inner class Bridge {
        @JavascriptInterface fun ready() { val (bg, fg, accent) = theme; js("zinit({bg:'$bg',fg:'$fg',accent:'$accent',sel:'rgba(232,163,61,0.3)',size:12})") }
        @JavascriptInterface fun input(d: String) = send(d)
        @JavascriptInterface fun resize(c: Int, r: Int) = this@TermLink.resize(c, r)
    }
}

/** The special keys the soft keyboard lacks. Sent as the bytes a terminal would send. */
val TERM_KEYS = listOf(
    "Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C",
    "^C" to "\u0003", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "Enter" to "\r",
)

/** The xterm.js pane. [preview] draws fixed lines instead (screenshot tests have no WebView). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalPane(link: TermLink?, modifier: Modifier = Modifier, preview: List<String>? = null) {
    val c = Athena.colors
    val bg = c.block(dev.phonerecall.ui.Tint.SERVER)
    fun css(x: androidx.compose.ui.graphics.Color) = "#%06X".format(x.toArgb() and 0xFFFFFF)
    link?.theme = Triple(css(bg), css(c.ink), css(c.accent))
    Box(modifier.background(bg)) {
        if (preview != null || link == null) {
            Column(Modifier.padding(Space.s)) {
                for (l in preview.orEmpty()) Text(l, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp), color = c.ink, maxLines = 1)
            }
            return@Box
        }
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AColor.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.blockNetworkLoads = true      // the page loads its own assets only
                    settings.allowFileAccess = false       // android_asset still works
                    settings.allowContentAccess = false
                    webViewClient = WebViewClient()
                    addJavascriptInterface(link.Bridge(), "Zeus")
                    link.web = this
                    loadUrl("file:///android_asset/lemnos/term.html")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun KeysRow(link: TermLink?, modifier: Modifier = Modifier) {
    var ctrl by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for ((label, bytes) in TERM_KEYS) {
            if (label == "Ctrl") TextAction(if (ctrl) "Ctrl on" else "Ctrl", { ctrl = !ctrl; link?.ctrl = ctrl }, emphasis = ctrl)
            else TextAction(label, { link?.send(bytes); if (ctrl) { ctrl = false; link?.ctrl = false } })
        }
    }
}

@Composable
fun rememberTermLink(session: String, onStatus: (String) -> Unit): TermLink {
    val link = remember(session) { TermLink(session, onStatus) }
    DisposableEffect(link) { onDispose { link.close(); link.web?.destroy(); link.web = null } }
    return link
}
