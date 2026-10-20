package dev.phonerecall.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import com.anthropic.core.jsonMapper
import dev.phonerecall.assist.AssistBrain
import dev.phonerecall.assist.AssistLine
import dev.phonerecall.brain.KeyVault
import dev.phonerecall.brain.Nano
import dev.phonerecall.brain.ToolOutcome
import dev.phonerecall.music.MusicCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The Live offer the sheet shows the first time each day. */
data class LiveOffer(val capMinutes: Int, val leftMinutes: Long)

/**
 * The sheet's voice: the router, the on-device path (parser, Nano, TextToSpeech) and Gemini Live.
 * Spoken input gets spoken replies; while driving every reply is spoken and the sheet stays minimal.
 */
class VoiceController(private val app: Context, private val brain: AssistBrain) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ledger = LiveLedgerStore(app)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var session: LiveSession? = null
    private var watch: Job? = null

    private val _live = MutableStateFlow<LiveUi?>(null)
    val live: StateFlow<LiveUi?> = _live
    private val _offer = MutableStateFlow<LiveOffer?>(null)
    val offer: StateFlow<LiveOffer?> = _offer

    val driving: Boolean get() = runCatching { MusicCore.get(app).driving }.getOrDefault(false)

    private fun gate(): LiveGate {
        val today = LocalDate.now()
        val l = ledger.load(today)
        return LiveGate(geminiKey(app).has(), l.confirmedToday(today), l.leftSeconds(today))
    }

    /** One final recognizer result. `say` adds a line to the sheet. */
    fun spoken(text: String, say: (AssistLine) -> Unit, working: (String?) -> Unit) {
        say(AssistLine(true, text))
        when (val r = VoiceRouter.route(text, gate())) {
            is Route.Quick -> scope.launch { quick(text, r.command, say) }
            Route.Brain -> scope.launch {
                // Nano first, on the phone, for commands the parser didn't know. Otherwise Delphi's brain, as typed text.
                val guess = if (Nano.foreground() && text.split(' ').size <= 10) QuickCommands.fromNano(Nano.generate("voice-intent", QuickCommands.nanoPrompt(text), maxTokens = 80, temperature = 0f)) else null
                if (guess != null) quick(text, guess, say)
                else brain.ask(text, null, onLine = { l -> say(l); if (!l.mine && !l.note && l.proposalId == null) speak(l.text) }, onWorking = working)
            }
            Route.Live -> startLive()
            Route.ConfirmLive -> offerLive()
            is Route.LiveBlocked -> { say(AssistLine(false, r.why, note = true)); speak(r.why) }
        }
    }

    private fun quick(heard: String, c: QuickCommand, say: (AssistLine) -> Unit) {
        brain.record(true, heard)
        val reply = when (c) {
            is QuickCommand.Propose -> {
                val o = brain.tools.run(c.tool, c.input, brain.chatId())
                o.proposal?.let { p -> brain.record(false, p.title, p); say(AssistLine(false, p.title, p.id)) }
                spokenFor(o)
            }
            QuickCommand.WhatsNext -> nextEvent(brain.tools.run("read_calendar", mapOf("days" to 2), null).text, LocalDateTime.now())
        }
        brain.record(false, reply)
        say(AssistLine(false, reply))
        speak(reply)
    }

    fun offerLive() { val l = ledger.load(); _offer.value = LiveOffer(l.capMinutes, l.leftSeconds(LocalDate.now()) / 60) }
    fun declineLive() { _offer.value = null }
    fun confirmLive() { ledger.update { it.confirm(LocalDate.now()) }; _offer.value = null; startLive() }

    /** The sheet's Live button and "let's talk" both land here once confirmed today. */
    fun live(say: (AssistLine) -> Unit) = when (val r = VoiceRouter.live(gate())) {
        Route.Live -> startLive()
        Route.ConfirmLive -> offerLive()
        is Route.LiveBlocked -> say(AssistLine(false, r.why, note = true))
        else -> Unit
    }

    private fun startLive() {
        if (session != null) return
        tts?.stop()
        val chat = brain.chatId()
        val bridge = LiveToolBridge(brain.tools, chat) { p -> brain.record(false, p.title, p) }
        // Both keys are checked against every outgoing frame; neither is ever put in one.
        val s = LiveSession(app, bridge, ledger, KeyGuard { listOfNotNull(geminiKey(app).read(), KeyVault(app).read()) })
        session = s
        watch = scope.launch { s.ui.collect { u -> _live.value = u; if (u.phase == LivePhase.ENDED) { session = null } } }
        scope.launch { s.start() }
    }

    fun stopLive() { scope.launch { session?.stop() } }
    fun dismissLive() { if (session == null) _live.value = null }

    /** Says one line (Hermes's result for a spoken command). */
    fun say(text: String) = speak(text)

    private fun speak(text: String) {
        // The phone's own TextToSpeech engine (on-device voices).
        val t = tts ?: TextToSpeech(app) { ok ->
            ttsReady = ok == TextToSpeech.SUCCESS
            if (ttsReady) { tts?.language = Locale.US; pending?.let { p -> pending = null; speak(p) } }
        }.also { tts = it }
        if (!ttsReady) { pending = text; return }
        t.speak(text.take(400), TextToSpeech.QUEUE_FLUSH, null, "zeus-voice")
    }
    @Volatile private var pending: String? = null

    fun close() {
        session?.stop()
        tts?.shutdown(); tts = null
        scope.cancel()
    }

    companion object {
        /** A short spoken line for a quick command's outcome. Never says a card is done when it is waiting. */
        fun spokenFor(o: ToolOutcome): String {
            val p = o.proposal ?: return if (o.isError) "I couldn't do that: " + o.text.removePrefix("Not proposed: ").substringBefore(". Fix").take(140) else o.text.take(200)
            return when (o.decision) {
                is dev.phonerecall.brain.Decision.Done -> "Done: ${p.title}."
                is dev.phonerecall.brain.Decision.Failed -> "That was auto-approved but failed."
                else -> "On a card for you: ${p.title}."
            }
        }

        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        private val SPOKEN = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** The next event from read_calendar's JSON, as one spoken line. */
        fun nextEvent(json: String, now: LocalDateTime): String {
            val events = runCatching { jsonMapper().readTree(json).path("events") }.getOrNull() ?: return "I couldn't read your calendar."
            for (e in events) {
                if (e.path("all_day").asBoolean(false)) continue
                val start = runCatching { LocalDateTime.parse(e.path("start").asText(), ISO) }.getOrNull() ?: continue
                if (start.isBefore(now)) continue
                val day = when (start.toLocalDate()) { now.toLocalDate() -> "at"; now.toLocalDate().plusDays(1) -> "tomorrow at"; else -> "on ${start.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }} at" }
                val place = e.path("place").asText("").takeIf { it.isNotBlank() }?.let { ", at $it" }.orEmpty()
                return "Next: ${e.path("title").asText("an event")} $day ${SPOKEN.format(start)}$place."
            }
            return "Nothing else on your calendar today or tomorrow."
        }
    }
}
