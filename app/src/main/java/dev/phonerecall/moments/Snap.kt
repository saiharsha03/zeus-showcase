package dev.phonerecall.moments

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.phonerecall.R
import dev.phonerecall.brain.FatesCallbacks
import dev.phonerecall.brain.FatesCard
import dev.phonerecall.brain.Proposal
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.data.Prefs
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** "Snap the box": photo, on-device text, the rules, one bundled timer plan. */
sealed class SnapState {
    data object Idle : SnapState()
    data object Reading : SnapState()
    data class Found(val read: CookRead, val chosen: Int, val hot: Boolean, val byAthena: Boolean = false) : SnapState()
    data class Nothing(val text: String, val asking: Boolean = false, val problem: String? = null) : SnapState()
    data class Proposed(val proposal: Proposal) : SnapState()
    /** A receipt: Moneta's manual entry, prefilled. He fixes and confirms; nothing is saved before that. */
    data class Receipt(val draft: ReceiptDraft) : SnapState()
    data class Done(val words: String) : SnapState()
}

class SnapCalls(
    val camera: () -> Unit = {}, val pick: () -> Unit = {}, val retake: () -> Unit = {}, val close: () -> Unit = {},
    val choose: (Int) -> Unit = {}, val hot: (Boolean) -> Unit = {}, val make: () -> Unit = {}, val askAthena: () -> Unit = {},
    val fates: FatesCallbacks = FatesCallbacks(),
    val saveReceipt: (String, String) -> Unit = { _, _ -> },
)

/** The plan the Found state would make: the chosen variant, the oven already hot or not. */
fun SnapState.Found.plan(): MomentPlan = MomentPlans.cook(read.variants[chosen], read.name, preheatFirst = !hot)

@Composable
fun SnapContent(s: SnapState, gutter: Dp, calls: SnapCalls) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        ScreenHeader("Snap the box", gutter, kicker = dev.phonerecall.ui.Place.MOMENTS.title(), subtitle = dev.phonerecall.ui.Place.MOMENTS.subtitle()) { TextAction("Close", calls.close) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.blockGap)) {
                when (s) {
                    SnapState.Idle -> Block {
                        Line("Timers from the instructions", Type.title, c.ink)
                        Spacer(Modifier.height(Space.xs))
                        Line("Take a photo of the cooking instructions. Zeus reads them on the phone and makes one card with the timers. Nothing is sent.", Type.small, c.ink2)
                        Spacer(Modifier.height(Space.m))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrimaryAction("Take a photo", calls.camera)
                            Spacer(Modifier.width(Space.s))
                            TextAction("Pick a photo", calls.pick)
                        }
                    }
                    SnapState.Reading -> Block {
                        StatusWord("Reading the box", Tone.ACTIVE)
                        Spacer(Modifier.height(Space.xs))
                        Line("On the phone, a second or two.", Type.small, c.ink2)
                    }
                    is SnapState.Found -> FoundBlock(s, calls)
                    is SnapState.Nothing -> Block {
                        Line("No cooking times on this one", Type.title, c.ink)
                        Spacer(Modifier.height(Space.xs))
                        if (s.text.isBlank()) Line("Zeus couldn't read any text. Try closer, with the instructions flat and lit.", Type.small, c.ink2)
                        else {
                            Line("Zeus read the box but found no times it understands. Athena can read this text for you (it is sent once, through your cap).", Type.small, c.ink2)
                            Spacer(Modifier.height(Space.s))
                            Line(s.text.lines().filter { it.isNotBlank() }.take(4).joinToString(" "), Type.small, c.ink3, maxLines = 3)
                        }
                        s.problem?.let { Line(it, Type.small, c.alert, Modifier.padding(top = Space.xs)) }
                        Spacer(Modifier.height(Space.s))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (s.text.isNotBlank()) TextAction(if (s.asking) "Asking Athena" else "Ask Athena", calls.askAthena, emphasis = true, enabled = !s.asking)
                            TextAction("Retake", calls.retake)
                        }
                    }
                    is SnapState.Receipt -> ReceiptBlock(s.draft, calls)
                    is SnapState.Done -> Block {
                        Line(s.words, Type.title, c.ink)
                        Spacer(Modifier.height(Space.s))
                        TextAction("Snap another", calls.retake)
                    }
                    is SnapState.Proposed -> {
                        FatesCard(s.proposal, calls.fates)
                        TextAction("Snap another box", calls.retake)
                    }
                }
            }
        }
    }
}

@Composable
private fun Block(content: @Composable () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.block(Tint.BRAIN, strong = false)).border(1.dp, c.edge, shape).padding(Space.blockPad)) { content() }
}

@Composable
private fun FoundBlock(s: SnapState.Found, calls: SnapCalls) {
    val c = Athena.colors
    val v = s.read.variants[s.chosen]
    val plan = s.plan()
    Block {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker(if (s.byAthena) "Read by Athena" else "Read from the box", Modifier.weight(1f))
            StatusWord(v.method.label, Tone.ACTIVE)
        }
        Spacer(Modifier.height(Space.s))
        Line(s.read.name ?: "Your box", Type.title, c.ink)
        Line(listOfNotNull(v.temp?.text, v.cook.text).joinToString(" · "), Type.headline, c.ink)
        if (s.read.variants.size > 1) {
            Spacer(Modifier.height(Space.xs))
            Row(Modifier.padding(start = 0.dp), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                s.read.variants.forEachIndexed { i, o -> TextAction(o.method.label, { calls.choose(i) }, emphasis = i == s.chosen) }
            }
        }
        Spacer(Modifier.height(Space.s))
        if (v.preheat) FactRow("Preheat", "${v.method.label}, about ${v.method.preheatMin} min")
        FactRow("Cook", v.cook.text + if (v.cook.range) ", check at the low end" else "")
        v.turn?.let { FactRow("Midway", "${it.word} at ${Span.words(it.at)}") }
        v.stand?.let { FactRow("Let stand", Span.words(it)) }
        if (v.others.isNotEmpty()) FactRow("Also on box", v.others.joinToString(", ") { it.text })
        if (v.preheat) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).topRule(c.rule).clickable(role = Role.Switch) { calls.hot(!s.hot) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Line("The ${v.method.label.lowercase()}'s already hot", Type.body, c.ink, Modifier.weight(1f))
                StatusWord(if (s.hot) "Yes" else "No", if (s.hot) Tone.ACTIVE else Tone.QUIET)
            }
        }
        Spacer(Modifier.height(Space.s))
        Kicker("Timers, from when you confirm")
        for (step in plan.steps) {
            Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.Top) {
                Line(Span.words(step.args.getValue("seconds").toInt()), Type.small, c.ink2, Modifier.width(92.dp))
                Line(step.args["label"].orEmpty(), Type.small, c.ink, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("Make the timers", calls.make)
            Spacer(Modifier.width(Space.s))
            TextAction("Retake", calls.retake)
        }
    }
}

@Composable
private fun ReceiptBlock(d: ReceiptDraft, calls: SnapCalls) {
    val c = Athena.colors
    var merchant by remember(d) { mutableStateOf(d.merchant) }
    var amount by remember(d) { mutableStateOf("%d.%02d".format(d.cents / 100, d.cents % 100)) }
    Block {
        Kicker("A receipt, for ${dev.phonerecall.ui.Place.MONEY.title()}")
        Spacer(Modifier.height(Space.s))
        Line("Check it, then add it", Type.title, c.ink)
        Line("Read on the phone. Nothing is saved until you add it.", Type.small, c.ink2)
        Kicker("Merchant", Modifier.padding(top = Space.m))
        dev.phonerecall.ui.UnderlineField(merchant, { merchant = it }, "Merchant", Modifier.fillMaxWidth())
        Kicker("Total", Modifier.padding(top = Space.m))
        dev.phonerecall.ui.UnderlineField(amount, { amount = it }, "0.00", Modifier.fillMaxWidth(), keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
        d.date?.let { FactRow("Date", java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", java.util.Locale.US).format(it)) }
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PrimaryAction("Add to ${dev.phonerecall.ui.Place.MONEY.title()}", { calls.saveReceipt(merchant, amount) })
            Spacer(Modifier.width(Space.s))
            TextAction("Retake", calls.retake)
        }
    }
}

/** What a box, sign, ticket or receipt photo becomes: cooking first, then a receipt, then an errand. */
fun classify(text: String, now: java.time.ZonedDateTime, airportMinutes: Int? = null): Any? {
    val read = CookParser.parse(text)
    if (read.found) return found(read)
    Receipts.read(text, now.toLocalDate())?.let { return SnapState.Receipt(it) }
    return ScreenIntents.read(text, now, airportMinutes)
}

/** The Found state for a read, oven first. */
fun found(read: CookRead, byAthena: Boolean = false): SnapState.Found =
    SnapState.Found(read, read.variants.indexOf(read.best).coerceAtLeast(0), hot = false, byAthena = byAthena)

/** The box photo's one cache file, handed to the camera app. Deleted once it has been read. */
class BoxPhotoProvider : FileProvider(R.xml.moments_paths)

class SnapActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val dark = runCatching { Prefs(app).load().dark }.getOrDefault(true)
        setContent {
            AthenaTheme(dark) {
                val scope = rememberCoroutineScope()
                var state by remember { mutableStateOf<SnapState>(SnapState.Idle) }
                var boxText by remember { mutableStateOf("") }
                var shot by rememberSaveable { mutableStateOf<String?>(null) }
                fun readUri(u: Uri, delete: Boolean) {
                    state = SnapState.Reading
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            runCatching { MomentsCore.read(app, u) }.getOrDefault("").also { if (delete) runCatching { app.contentResolver.delete(u, null, null) } }
                        }
                        boxText = text
                        val airport = Signals.airportMinutes(dev.phonerecall.odyssey.OdysseyStore.state.value)
                        state = when (val r = withContext(Dispatchers.Default) { classify(text, java.time.ZonedDateTime.now(), airport) }) {
                            is SnapState -> r
                            is MomentPlan -> when (val p = withContext(Dispatchers.IO) { MomentsCore.propose(app, r) }) {
                                is ProposeResult.Created -> SnapState.Proposed(p.proposal)
                                is ProposeResult.Invalid -> SnapState.Nothing(text, problem = p.reason)
                            }
                            else -> SnapState.Nothing(text)
                        }
                    }
                }
                val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
                    val u = shot?.let(Uri::parse)
                    if (ok && u != null) readUri(u, delete = true) else state = SnapState.Idle
                }
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u -> if (u != null) readUri(u, delete = false) }
                fun refresh(id: Long) = scope.launch {
                    val p = withContext(Dispatchers.IO) { dev.phonerecall.brain.BrainCore.get(app).db.get(id) } ?: return@launch
                    state = SnapState.Proposed(p)
                }
                val calls = SnapCalls(
                    camera = {
                        val f = File(File(app.cacheDir, "box").apply { mkdirs() }, "box.jpg")
                        val u = FileProvider.getUriForFile(app, "${app.packageName}.moments.photos", f)
                        shot = u.toString()
                        runCatching { camera.launch(u) }.onFailure { state = SnapState.Nothing("", problem = "No camera app to take the photo.") }
                    },
                    pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    retake = { state = SnapState.Idle },
                    close = { finish() },
                    choose = { i -> (state as? SnapState.Found)?.let { state = it.copy(chosen = i) } },
                    hot = { h -> (state as? SnapState.Found)?.let { state = it.copy(hot = h) } },
                    make = {
                        val f = state as? SnapState.Found
                        if (f != null) scope.launch {
                            when (val r = withContext(Dispatchers.IO) { MomentsCore.propose(app, f.plan()) }) {
                                is ProposeResult.Created -> state = SnapState.Proposed(r.proposal)
                                is ProposeResult.Invalid -> state = SnapState.Nothing(boxText, problem = r.reason)
                            }
                        }
                    },
                    askAthena = {
                        val n = state as? SnapState.Nothing
                        if (n != null) {
                            state = n.copy(asking = true, problem = null)
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { MomentsCore.askAthena(app, n.text) } }
                                val read = r.getOrNull()
                                state = when {
                                    read != null -> found(read, byAthena = true)
                                    r.isFailure -> n.copy(asking = false, problem = dev.phonerecall.brain.AthenaEngine.plainError(r.exceptionOrNull()!!))
                                    dev.phonerecall.brain.BrainCore.get(app).haiku() == null -> n.copy(asking = false, problem = "Add the Anthropic key in Calypso first.")
                                    else -> n.copy(asking = false, problem = "Athena found no cooking times either.")
                                }
                            }
                        }
                    },
                    saveReceipt = { merchant, amount ->
                        val r = state as? SnapState.Receipt
                        val cents = amount.trim().removePrefix("$").replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 100) }
                        if (r != null && cents != null && cents > 0 && merchant.isNotBlank()) {
                            val at = r.draft.date?.atTime(12, 0)?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
                            dev.phonerecall.moneta.MonetaCore.get(app).addManual(
                                dev.phonerecall.moneta.Txn(at = at, cents = cents, kind = dev.phonerecall.moneta.TxnKind.SPEND, merchant = merchant.trim().take(40),
                                    category = dev.phonerecall.moneta.Category.OTHER, source = dev.phonerecall.moneta.Source.MANUAL),
                            )
                            state = SnapState.Done("Added to Moneta: ${merchant.trim()}, $${"%.2f".format(cents / 100.0)}")
                        }
                    },
                    fates = FatesCallbacks(
                        confirm = { id, seen, done -> scope.launch(Dispatchers.IO) {
                            val d = dev.phonerecall.brain.BrainCore.get(app).let { val r = it.fates.confirm(id, seen); it.changed(); r }; refresh(id)
                            (d as? dev.phonerecall.brain.Decision.Refused)?.let { withContext(Dispatchers.Main) { done(it.reason) } }
                        } },
                        bind = { pr -> dev.phonerecall.brain.BrainCore.get(app).fates.bind(pr) },
                        reject = { id -> scope.launch(Dispatchers.IO) { dev.phonerecall.brain.BrainCore.get(app).let { it.fates.reject(id); it.changed() }; refresh(id) } },
                        undo = { id -> scope.launch(Dispatchers.IO) { dev.phonerecall.brain.BrainCore.get(app).let { it.fates.undo(id); it.changed() }; refresh(id) } },
                        toggle = { id, i -> scope.launch(Dispatchers.IO) { dev.phonerecall.brain.BrainCore.get(app).let { it.fates.toggle(id, i); it.changed() }; refresh(id) } },
                    ),
                )
                BackHandler(state !is SnapState.Idle) { state = SnapState.Idle }
                Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { SnapContent(state, Space.gutterNarrow, calls) }
            }
        }
    }

    companion object {
        fun intent(c: Context): Intent = Intent(c, SnapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
