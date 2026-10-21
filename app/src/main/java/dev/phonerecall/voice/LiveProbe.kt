package dev.phonerecall.voice

import android.content.Context
import dev.phonerecall.brain.KeyVault
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.TlsGuard
import dev.phonerecall.brain.ToolOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Why a Live check didn't pass: [skip] when it couldn't run at all (today's minutes are used). */
class LiveCheckFailed(message: String, val skip: Boolean = false) : Exception(message)

/**
 * The shakedown's Live checks. [textTurn]: a session with no mic and no speaker, one typed turn, the first
 * answer timed, then closed. [micTest]: a real [LiveSession] for a few seconds, passing once his words come
 * back as a transcript; its tools are off. Both count their seconds in [LiveLedgerStore] like any session.
 */
object LiveProbe {
    const val TEXT_TIMEOUT_MS = 20_000L
    const val MIC_MS = 10_000L
    const val ASK = "Zeus shakedown test. Say the word ready."

    private fun guard(app: Context) = KeyGuard { listOfNotNull(geminiKey(app).read(), KeyVault(app).read()) }

    /** Returns "Answered in 1.4 s" or throws [LiveCheckFailed] with the reason. Call on IO. */
    suspend fun textTurn(app: Context, ledger: LiveLedgerStore = LiveLedgerStore(app), timeoutMs: Long = TEXT_TIMEOUT_MS): String {
        if (ledger.load().leftSeconds(LocalDate.now()) <= 0) throw LiveCheckFailed("Today's Live minutes are used", skip = true)
        val key = geminiKey(app).read() ?: throw LiveCheckFailed("No Gemini key in Calypso")
        val guard = guard(app)
        val opened = System.currentTimeMillis()
        val sentAt = AtomicLong(0)
        val spoke = AtomicLong(0)
        val done = CompletableDeferred<String>()
        NetGate.liveOpen = true
        val client = OkHttpClient.Builder().sslSocketFactory(TlsGuard.sslSocketFactory, TlsGuard.trustManager).addInterceptor(NetGate.okHttp).readTimeout(0, TimeUnit.MILLISECONDS).build()
        var socket: WebSocket? = null
        fun send(ws: WebSocket, frame: String) { if (guard.clean(frame)) ws.send(frame) else done.completeExceptionally(LiveCheckFailed("Stopped: a frame held a key")) }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val now = ZonedDateTime.now()
                send(webSocket, LiveProtocol.setup(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", Locale.US).format(now) + " (" + now.zone.id + ")"))
            }
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(webSocket, bytes.utf8())
            fun handle(ws: WebSocket, frame: String) {
                for (e in LiveProtocol.parse(frame)) when (e) {
                    LiveEvent.SetupComplete -> { sentAt.set(System.currentTimeMillis()); send(ws, LiveProtocol.text(ASK)) }
                    is LiveEvent.Audio -> { spoke.addAndGet(e.pcm.size.toLong()); answered() }
                    is LiveEvent.Said, LiveEvent.TurnComplete -> answered()
                    is LiveEvent.GoAway -> done.completeExceptionally(LiveCheckFailed("Gemini ended the session at once"))
                    else -> Unit
                }
            }
            fun answered() {
                if (sentAt.get() == 0L || done.isCompleted) return
                done.complete("Answered in ${"%.1f".format((System.currentTimeMillis() - sentAt.get()) / 1000.0)} s")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                done.completeExceptionally(LiveCheckFailed("Live closed: ${reason.take(100).ifEmpty { "code $code" }}"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                done.completeExceptionally(LiveCheckFailed(
                    if (response?.code == 401 || response?.code == 403) "Gemini refused the key" else "Couldn't connect: ${t.message?.take(80) ?: t.javaClass.simpleName}",
                ))
            }
        }
        try {
            socket = client.newWebSocket(LiveApi.handshake(key), listener)
            return withTimeoutOrNull(timeoutMs) { done.await() }
                ?: throw LiveCheckFailed(if (sentAt.get() == 0L) "Connected, but Live didn't get ready in ${timeoutMs / 1000} s" else "Asked; no reply in ${timeoutMs / 1000} s")
        } finally {
            runCatching { socket?.close(1000, null) }
            NetGate.liveOpen = false
            val open = ((System.currentTimeMillis() - opened + 999) / 1000).coerceAtLeast(1)
            ledger.update { it.plus(open, Pcm.seconds(spoke.get(), LiveApi.OUT_RATE).toLong(), LocalDate.now()) }
        }
    }

    /** Opens Live with the microphone for [MIC_MS]; passes once a transcript of what he said comes back. */
    suspend fun micTest(app: Context, ledger: LiveLedgerStore = LiveLedgerStore(app)): String {
        if (geminiKey(app).read() == null) throw LiveCheckFailed("No Gemini key in Calypso")
        val bridge = LiveToolBridge({ _, _ -> ToolOutcome("Shakedown mic test: tools are off.", isError = true) })
        val s = LiveSession(app, bridge, ledger, guard(app))
        if (!s.start()) throw LiveCheckFailed(s.ui.value.problem ?: "Live didn't start", skip = s.ui.value.problem?.contains("minutes") == true)
        try {
            // The transcript clears at each turn's end, so the last words seen are kept.
            var heard = ""
            withTimeoutOrNull(MIC_MS) {
                while (s.ui.value.phase != LivePhase.ENDED) {
                    s.ui.value.heard.takeIf { it.isNotBlank() }?.let { heard = it }
                    if (heard.isNotBlank() && s.ui.value.said.isNotBlank()) break
                    delay(200)
                }
            }
            s.ui.value.problem?.let { throw LiveCheckFailed(it) }
            if (heard.isBlank()) throw LiveCheckFailed("Live was open ${MIC_MS / 1000} s but heard nothing")
            return "Heard: \"${heard.trim().take(80)}\""
        } finally { s.stop() }
    }
}
