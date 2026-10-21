package dev.phonerecall.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.TlsGuard
import dev.phonerecall.brain.SealedSecret
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Calypso's Gemini key slot. Read only by [LiveSession.start], for the handshake header to Gemini. */
fun geminiKey(c: Context) = SealedSecret(c, "gemini")

enum class LivePhase(val word: String) { CONNECTING("Connecting"), LISTENING("Listening"), SPEAKING("Speaking"), THINKING("Looking it up"), ENDED("Ended") }

/** What the Live bar draws. */
data class LiveUi(
    val phase: LivePhase,
    val seconds: Long = 0,
    val leftSeconds: Long = 0,
    val heard: String = "",
    val said: String = "",
    val level: Float = 0f,
    val problem: String? = null,
)

/**
 * One Gemini Live conversation: mic at 16 kHz into the socket, Gemini's 24 kHz voice out to an
 * AudioTrack, barge-in (Gemini's own voice detection sends `interrupted`; the queued audio is dropped),
 * function calls into [LiveToolBridge]. NetGate lets the Live path through only while this is open.
 * It stops by itself at the day's cap.
 */
class LiveSession(
    private val app: Context,
    private val bridge: LiveToolBridge,
    private val ledger: LiveLedgerStore,
    private val guard: KeyGuard,
) {
    private val _ui = MutableStateFlow(LiveUi(LivePhase.CONNECTING))
    val ui: StateFlow<LiveUi> = _ui

    private val running = AtomicBoolean(false)
    private val ready = AtomicBoolean(false)
    private val spokeBytes = AtomicLong(0)
    private var startedAt = 0L
    private var socket: WebSocket? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val tools = Executors.newSingleThreadExecutor()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    private var audio: AudioManager? = null
    private var oldMode = AudioManager.MODE_NORMAL

    private fun set(f: (LiveUi) -> LiveUi) { _ui.value = f(_ui.value) }

    /** Call on IO. False (with a problem on the bar) when it can't start. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return fail("Zeus needs the microphone for Live.")
        val left = ledger.load().leftSeconds(LocalDate.now())
        if (left <= 0) return fail("Today's Live minutes are used.")
        val key = geminiKey(app).read() ?: return fail("Add the Gemini key in Calypso first.")
        startedAt = System.currentTimeMillis()
        set { it.copy(phase = LivePhase.CONNECTING, leftSeconds = left) }
        NetGate.liveOpen = true
        val client = OkHttpClient.Builder().sslSocketFactory(TlsGuard.sslSocketFactory, TlsGuard.trustManager).addInterceptor(NetGate.okHttp).pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        // The key goes in this one header, to this one host. It is never in a frame, a log line or a model's context.
        socket = client.newWebSocket(LiveApi.handshake(key), listener)
        ticker.scheduleAtFixedRate({ tick() }, 1, 1, TimeUnit.SECONDS)
        return true
    }

    private fun fail(why: String): Boolean {
        set { it.copy(phase = LivePhase.ENDED, problem = why) }
        running.set(false)
        return false
    }

    private fun send(frame: String) {
        if (!guard.clean(frame)) { Log.e(TAG, "refused a frame that contained a key"); stop("Stopped: a frame held a key."); return }
        socket?.send(frame)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val now = ZonedDateTime.now()
            send(LiveProtocol.setup(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a", Locale.US).format(now) + " (" + now.zone.id + "). " + dev.phonerecall.brain.TwoClocks(now.zone).contextLine(now.toInstant())))
        }
        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { stop(if (code == 1000) null else "Live closed: ${reason.take(120).ifEmpty { "code $code" }}") }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "socket failed: ${t.javaClass.simpleName}")
            stop(if (response?.code == 401 || response?.code == 403) "Gemini refused the key. Check it in Calypso." else "Live lost the connection.")
        }
    }

    private fun handle(frame: String) {
        for (e in LiveProtocol.parse(frame)) when (e) {
            LiveEvent.SetupComplete -> { ready.set(true); startAudio(); set { it.copy(phase = LivePhase.LISTENING) } }
            is LiveEvent.Audio -> { spokeBytes.addAndGet(e.pcm.size.toLong()); playQueue.offer(e.pcm); set { it.copy(phase = LivePhase.SPEAKING) } }
            is LiveEvent.Heard -> set { it.copy(heard = (it.heard + e.text).takeLast(400)) }
            is LiveEvent.Said -> set { it.copy(said = (it.said + e.text).takeLast(600)) }
            LiveEvent.Interrupted -> { playQueue.clear(); track?.run { pause(); flush(); play() }; set { it.copy(phase = LivePhase.LISTENING, said = "") } }
            LiveEvent.TurnComplete -> set { it.copy(phase = LivePhase.LISTENING, heard = "") }
            is LiveEvent.Calls -> {
                set { it.copy(phase = LivePhase.THINKING) }
                tools.execute { runCatching { send(bridge.answerAll(e.calls)) }.onFailure { Log.w(TAG, "tool call failed: ${it.javaClass.simpleName}") } }
            }
            is LiveEvent.Cancelled -> Unit
            is LiveEvent.GoAway -> stop("Gemini ended the session (its time limit).")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        audio = app.getSystemService(AudioManager::class.java)?.also { oldMode = it.mode; it.mode = AudioManager.MODE_IN_COMMUNICATION }
        val inMin = AudioRecord.getMinBufferSize(LiveApi.IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // Voice communication: the platform's echo canceller keeps Gemini's own voice out of the mic, so he can talk over it.
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, LiveApi.IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, Pcm.FRAME_BYTES * 4))
        if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true }
        record = r
        val outMin = AudioTrack.getMinBufferSize(LiveApi.OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(LiveApi.OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(outMin, 9_600)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        t.play()
        r.startRecording()
        Thread({
            val buf = ShortArray(Pcm.FRAME_BYTES / 2)
            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                set { it.copy(level = Pcm.level(buf, n)) }
                send(LiveProtocol.audio(Pcm.shortsToBytes(buf, n)))
            }
        }, "zeus-live-mic").start()
        Thread({
            while (running.get()) {
                val pcm = playQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                runCatching { t.write(pcm, 0, pcm.size) }
            }
        }, "zeus-live-speaker").start()
    }

    private fun tick() {
        if (!running.get()) return
        val open = (System.currentTimeMillis() - startedAt) / 1000
        val left = ledger.load().leftSeconds(LocalDate.now()) - open
        set { it.copy(seconds = open, leftSeconds = left.coerceAtLeast(0)) }
        if (left <= 0) stop("Today's Live minutes are used.")
    }

    /** Ends the session, records its minutes, closes NetGate's Live door. Safe to call twice. */
    fun stop(problem: String? = null) {
        if (!running.compareAndSet(true, false)) return
        NetGate.liveOpen = false
        runCatching { if (ready.get()) socket?.send(LiveProtocol.audioEnd()) }
        runCatching { socket?.close(1000, null) }
        socket = null
        ticker.shutdownNow(); tools.shutdownNow()
        runCatching { record?.stop() }; runCatching { record?.release() }; record = null
        playQueue.clear()
        runCatching { track?.stop() }; runCatching { track?.release() }; track = null
        audio?.mode = oldMode
        val open = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000 else 0
        val spoke = Pcm.seconds(spokeBytes.get(), LiveApi.OUT_RATE).toLong()
        if (open > 0) ledger.update { it.plus(open, spoke, LocalDate.now()) }
        set { it.copy(phase = LivePhase.ENDED, level = 0f, problem = problem) }
    }

    private companion object { const val TAG = "Zeus-Live" }
}
