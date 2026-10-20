package dev.phonerecall.assist

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.FatesCard
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.isRoute
import dev.phonerecall.brain.rememberVoiceCapture
import dev.phonerecall.data.Prefs
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.voice.LiveBar
import dev.phonerecall.voice.LiveOfferRow
import dev.phonerecall.voice.LivePhase
import dev.phonerecall.voice.SheetVoice
import dev.phonerecall.voice.VoiceController
import dev.phonerecall.ui.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The assistant sheet over any app. Tapping outside closes it. */
class AssistActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val brain = AssistBrain(applicationContext)
        val voice = VoiceController(applicationContext, brain).also { this.voice = it }
        val dark = runCatching { Prefs(applicationContext).load().dark }.getOrDefault(true)
        setContent {
            AthenaTheme(dark) {
                val screen by Assistant.screen.collectAsStateWithLifecycle()
                val clip by clipFlow.collectAsStateWithLifecycle()
                val scope = rememberCoroutineScope()
                val lines = remember { mutableStateListOf<AssistLine>() }
                val proposals = remember { mutableStateListOf<Proposal>() }
                var working by remember { mutableStateOf<String?>(null) }
                var useScreen by remember { mutableStateOf(false) }
                fun refresh(id: Long) = scope.launch(Dispatchers.IO) {
                    val p = brain.proposal(id) ?: return@launch
                    withContext(Dispatchers.Main) { val i = proposals.indexOfFirst { it.id == id }; if (i >= 0) proposals[i] = p else proposals += p }
                }
                // Hermes: his own commands ("open spotify", "wake me at 6") run on the phone first; the rest go to Athena.
                val hermes = dev.phonerecall.hermes.rememberHermes { t -> if (spokenLast) voice.say(t.outcome.title) }
                val ask: (String) -> Unit = ask@{ text ->
                    spokenLast = false
                    if (hermes.tryRun(text)) { lines += AssistLine(true, text); return@ask }
                    lines += AssistLine(true, text)
                    working = "Thinking"
                    val ctx = screen.takeIf { useScreen }
                    scope.launch(Dispatchers.IO) {
                        brain.ask(text, ctx, onLine = { l -> scope.launch { lines += l; l.proposalId?.let { refresh(it) } } }, onWorking = { w -> scope.launch { working = w } })
                    }
                }
                val cb = FatesCallbacks(
                    confirm = { id, seen, done ->
                        scope.launch(Dispatchers.IO) {
                            val d = brain.confirm(id, seen); refresh(id)
                            (d as? dev.phonerecall.brain.Decision.Refused)?.let { withContext(Dispatchers.Main) { done(it.reason) } }
                        }
                    },
                    bind = { p -> brain.bind(p) },
                    reject = { id -> scope.launch(Dispatchers.IO) { brain.reject(id); refresh(id) } },
                )
                val live by voice.live.collectAsStateWithLifecycle()
                val offer by voice.offer.collectAsStateWithLifecycle()
                val say: (AssistLine) -> Unit = { l -> scope.launch { lines += l; l.proposalId?.let { refresh(it) } } }
                val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) voice.live(say) }
                val withMic: (() -> Unit) -> Unit = { go -> if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) go() else mic.launch(Manifest.permission.RECORD_AUDIO) }
                val talk = SheetVoice(
                    live = live, offer = offer, driving = voice.driving,
                    onSpoken = { text ->
                        if (hermes.tryRun(text)) { spokenLast = true; lines += AssistLine(true, text) }
                        else voice.spoken(text, say) { w -> scope.launch { working = w } }
                    },
                    onLive = { withMic { voice.live(say) } }, onStop = voice::stopLive, onDismiss = voice::dismissLive,
                    onStart = { withMic { voice.confirmLive() } }, onDecline = voice::declineLive,
                )
                AssistSheet(
                    lines = lines, proposals = proposals.associateBy { it.id }, working = working, screen = screen,
                    useScreen = useScreen, onUseScreen = { useScreen = !useScreen }, onAsk = ask, cb = cb,
                    onClose = { Assistant.close(); finish() }, talk = talk,
                    // Moments: a recipe on screen becomes one card of timers, read on the phone (nothing sent).
                    offers = offers(screen, clip, say),
                    onTimers = { text ->
                        scope.launch(Dispatchers.IO) {
                            val plan = dev.phonerecall.moments.MomentPlans.recipe(dev.phonerecall.moments.Recipe.steps(text)) ?: return@launch
                            val r = dev.phonerecall.moments.MomentsCore.propose(applicationContext, plan)
                            if (r is dev.phonerecall.brain.ProposeResult.Created) { brain.record(false, "", r.proposal); say(AssistLine(false, r.proposal.title, r.proposal.id)) }
                        }
                    },
                    listenNow = intent?.getBooleanExtra(EXTRA_LISTEN, false) == true,
                    reply = { dev.phonerecall.hermes.HermesReply(hermes, Modifier.padding(top = Space.m)) },
                )
                // The widget's command sheet hands over words that weren't a command.
                androidx.compose.runtime.LaunchedEffect(Unit) { intent?.getStringExtra(EXTRA_ASK)?.takeIf { it.isNotBlank() }?.let { intent.removeExtra(EXTRA_ASK); ask(it) } }
            }
        }
    }

    private var voice: VoiceController? = null
    /** The last command was spoken, so its result is spoken too. */
    private var spokenLast = false

    /** Copied text, read only while the sheet has focus (never in the background). */
    private val clipFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && clipFlow.value == null) clipFlow.value = runCatching {
            getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        }.getOrNull().orEmpty()
    }

    /** Kairos on the sheet: an errand in the screen's text, a tracking number or an address he copied. All read on the phone. */
    private fun offers(screen: ScreenContext?, clip: String?, say: (AssistLine) -> Unit): List<AssistOffer> {
        val app = applicationContext
        val now = java.time.ZonedDateTime.now()
        val out = ArrayList<AssistOffer>()
        fun propose(plan: dev.phonerecall.moments.MomentPlan) = kotlin.concurrent.thread {
            val r = dev.phonerecall.moments.MomentsCore.propose(app, plan)
            if (r is dev.phonerecall.brain.ProposeResult.Created) say(AssistLine(false, r.proposal.title, r.proposal.id))
        }
        if (screen != null && !screen.excluded && screen.text.isNotBlank()) {
            val airport = dev.phonerecall.moments.Signals.airportMinutes(dev.phonerecall.odyssey.OdysseyStore.state.value)
            dev.phonerecall.moments.ScreenIntents.read(screen.text, now, airport)?.let { p -> out += AssistOffer(p.title, "From ${screen.label}, as a card to confirm") { propose(p) } }
        }
        when (val c = clip?.let { dev.phonerecall.moments.Clips.read(it, now) }) {
            is dev.phonerecall.moments.Clip.Package -> out += AssistOffer("Follow your ${c.carrier} package", "${c.number}, opens ${c.carrier} in the browser") { dev.phonerecall.moments.MomentsCore.open(app, c.url) }
            is dev.phonerecall.moments.Clip.Address -> {
                out += AssistOffer("Open in Maps", c.text) { dev.phonerecall.moments.MomentsCore.open(app, c.mapsUri) }
                dev.phonerecall.moments.Clips.leavePlan(c)?.let { p -> out += AssistOffer(p.title, "A reminder, as a card to confirm") { propose(p) } }
            }
            null -> {}
        }
        return out
    }

    override fun onDestroy() {
        voice?.close()
        if (isFinishing) Assistant.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FROM_SESSION = "dev.phonerecall.FROM_SESSION"
        /** The Delphi widget's mic: open listening (the mic permission is still asked the usual way). */
        const val EXTRA_LISTEN = "dev.phonerecall.LISTEN"
        /** Words from the Ask Zeus sheet that weren't a command: asked here as if typed. */
        const val EXTRA_ASK = "dev.phonerecall.ASK"
    }
}

/**
 * The sheet: Athena's coin, what she said, any proposals as Fates cards, one field with Speak.
 * "Use what's on screen" is off every time it opens and names the app it would read.
 */
@Composable
fun AssistSheet(
    lines: List<AssistLine>,
    proposals: Map<Long, Proposal>,
    working: String?,
    screen: ScreenContext?,
    useScreen: Boolean,
    onUseScreen: () -> Unit,
    onAsk: (String) -> Unit,
    cb: FatesCallbacks,
    onClose: () -> Unit,
    voice: Boolean = true,
    talk: SheetVoice? = null,
    listenNow: Boolean = false,
    onTimers: ((String) -> Unit)? = null,
    offers: List<AssistOffer> = emptyList(),
    /** Hermes's reply to a command, above the field. */
    reply: (@Composable () -> Unit)? = null,
) {
    val c = Athena.colors
    var draft by remember { mutableStateOf("") }
    // Spoken words go to the voice router (on-device, Delphi or Live); typed words go to Delphi as before.
    val capture = if (voice) rememberVoiceCapture { text, final -> if (final) { talk?.onSpoken?.invoke(text) ?: onAsk(text); draft = "" } else draft = text } else null
    val live = talk?.live
    val liveOn = live != null && live.phase != LivePhase.ENDED
    val minimal = talk?.driving == true
    androidx.compose.runtime.LaunchedEffect(Unit) { if (listenNow) capture?.start() }
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
                dev.phonerecall.ui.AthenaAvatar(32.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Line("Athena", Type.title, c.ink)
                    Line(if (lines.isEmpty()) "Ask anything. Actions come back as cards to confirm." else "Here, over ${screen?.label ?: "your app"}", Type.small, c.ink3, maxLines = 1)
                }
                TextAction("Close", onClose)
            }
            talk?.offer?.let { LiveOfferRow(it, talk.onStart, talk.onDecline, Modifier.padding(top = Space.m)) }
            if (talk != null && live != null) LiveBar(live, talk.onStop, talk.onDismiss, Modifier.padding(top = Space.m), driving = minimal)
            if (minimal && talk != null) {
                // Driving: the last thing she said, big, and one action. Replies are spoken.
                if (!liveOn) lines.lastOrNull { !it.mine && it.proposalId == null && !it.note }?.let { Line(it.text, Type.title, c.ink, Modifier.padding(top = Space.m), maxLines = 3) }
                Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    if (capture != null && !liveOn) TextAction(if (capture.listening) "Stop" else "Speak", { if (capture.listening) capture.stop() else capture.start() }, emphasis = true)
                    Spacer(Modifier.weight(1f))
                    if (!liveOn) TextAction("Live", talk.onLive)
                }
                Kicker("Driving: replies are spoken", Modifier.padding(top = Space.xs))
                return@Column
            }
            if (lines.isNotEmpty() || working != null) {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (l in lines) when {
                        l.proposalId != null -> proposals[l.proposalId]?.let { FatesCard(it, cb, compact = false) }
                        l.mine -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Line(l.text, Type.body, c.ink, Modifier.widthIn(max = 480.dp).clip(RoundedCornerShape(Radius.blockSmall)).background(c.raised).padding(horizontal = Space.m, vertical = Space.s))
                        }
                        l.note && isRoute(l.text) -> Line(l.text, Type.caption, c.ink3)
                        l.note -> Line(l.text, Type.small, c.ink2)
                        else -> Line(l.text, Type.body, c.ink)
                    }
                    if (working != null) StatusWord(working, Tone.ACTIVE)
                }
            }
            reply?.invoke()
            Spacer(Modifier.height(Space.m))
            // Screen context: never sent unless he turns it on for this question.
            val canRead = screen != null && !screen.excluded && screen.text.isNotBlank()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(enabled = canRead, onClick = onUseScreen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Line("Use what's on screen", Type.action, if (canRead) c.ink else c.ink3)
                    Line(
                        when {
                            screen == null -> "Nothing to read. Allow \"Use text from screen\" for Zeus in Settings to use it."
                            screen.excluded -> "${screen.label} is never read."
                            screen.text.isBlank() -> "${screen.label} shows no text."
                            useScreen -> "The text of ${screen.label} goes with your next question."
                            else -> "Off. Tap to send ${screen.label}'s text with your next question."
                        },
                        Type.small, c.ink3,
                    )
                }
                StatusWord(if (useScreen && canRead) "On" else "Off", if (useScreen && canRead) Tone.ACTIVE else Tone.QUIET)
            }
            val recipe = remember(screen) { if (canRead && onTimers != null) dev.phonerecall.moments.Recipe.steps(screen!!.text).takeIf { dev.phonerecall.moments.Recipe.looksLikeRecipe(screen.text) }.orEmpty() else emptyList() }
            if (recipe.isNotEmpty() && onTimers != null) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { onTimers(screen!!.text) }, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line("Timers for these steps", Type.action, c.ink)
                        Line(recipe.take(3).joinToString(", ") { "${it.label.lowercase()} ${dev.phonerecall.moments.Span.words(it.seconds)}" } + if (recipe.size > 3) " and ${recipe.size - 3} more" else "", Type.small, c.ink3, maxLines = 1)
                    }
                    StatusWord("${recipe.size} steps", Tone.ACTIVE)
                }
            }
            for (o in offers) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = o.onTap), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(o.label, Type.action, c.ink, maxLines = 1)
                        Line(o.detail, Type.small, c.ink3, maxLines = 1)
                    }
                    StatusWord(dev.phonerecall.ui.Place.MOMENTS.title(), Tone.ACTIVE)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(draft, { draft = it }, if (capture?.listening == true) "Listening" else "Ask Athena", Modifier.weight(1f), onSubmit = { if (draft.isNotBlank()) { onAsk(draft.trim()); draft = "" } })
                Spacer(Modifier.width(Space.s))
                if (capture != null && !liveOn) TextAction(if (capture.listening) "Stop" else "Speak", { if (capture.listening) capture.stop() else capture.start() })
                if (talk != null && !liveOn) TextAction("Live", talk.onLive)
                TextAction("Ask", { if (draft.isNotBlank()) { onAsk(draft.trim()); draft = "" } }, emphasis = true, enabled = draft.isNotBlank() && working == null)
            }
            capture?.problem?.let { Line(it, Type.small, c.ink3) }
            Kicker(if (liveOn) "Live: your voice goes to Gemini until you stop" else "Voice is recognized on the phone", Modifier.padding(top = Space.xs))
        }
    }
}

/** One Kairos offer on the sheet. */
class AssistOffer(val label: String, val detail: String, val onTap: () -> Unit)
