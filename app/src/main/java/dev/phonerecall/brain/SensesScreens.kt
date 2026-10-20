package dev.phonerecall.brain

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.Radius
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything phase 2b's screens can ask for. Each lands in BrainHub. */
class SensesCallbacks(
    val capture: (String) -> Unit = {},
    val undoCapture: (Long) -> Unit = {},
    val openSection: (Long) -> Unit = {},
    val newSection: (String) -> Unit = {},
    val undoChange: (Long) -> Unit = {},
    val addItem: (Long, String) -> Unit = { _, _ -> },
    val toggleItem: (SectionItem) -> Unit = {},
    val setNote: (SectionItem, String) -> Unit = { _, _ -> },
    val rename: (Long, String) -> Unit = { _, _ -> },
    val hide: (Long) -> Unit = {},
    val show: (Long) -> Unit = {},
    val pin: (Long, Boolean) -> Unit = { _, _ -> },
    val setTier: (String, Tier?) -> Unit = { _, _ -> },
    val setRelation: (String, Relation?) -> Unit = { _, _ -> },
    val setCalypso: (String, Boolean) -> Unit = { _, _ -> },
    val setZone: (String, String?) -> Unit = { _, _ -> },
    val readContacts: () -> Unit = {},
    val restore: (Long) -> Unit = {},
)

fun hubCallbacks(hub: BrainHub, openSection: (Long) -> Unit = {}) = SensesCallbacks(
    capture = { hub.capture(it) }, undoCapture = { hub.undoCapture(it) }, openSection = openSection,
    newSection = { hub.newSection(it) }, undoChange = { hub.undoChange(it) }, addItem = { id, t -> hub.addItem(id, t) },
    toggleItem = { hub.toggleItem(it) }, setNote = { i, n -> hub.setItemNote(i, n) }, rename = { id, n -> hub.renameSection(id, n) },
    hide = { hub.hideSection(it) }, show = { hub.showSection(it) }, pin = { id, on -> hub.pinSection(id, on) },
    setTier = { k, t -> hub.setTier(k, t) }, setRelation = { k, r -> hub.setRelation(k, r) }, setCalypso = { k, on -> hub.setCalypso(k, on) }, setZone = { k, z -> hub.setZone(k, z) },
    readContacts = { hub.refreshPeople() }, restore = { hub.restoreFromLethe(it) },
)

private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.US)
private fun day(ms: Long) = DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun dayTime(ms: Long) = DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

// ---- voice ----

/** On-device speech (SpeechRecognizer, offline preferred). Partial words show as he speaks. */
class VoiceCapture(val start: () -> Unit, val stop: () -> Unit, val listening: Boolean, val problem: String?)

@Composable
fun rememberVoiceCapture(onText: (String, Boolean) -> Unit): VoiceCapture {
    val ctx = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val emit by rememberUpdatedState(onText)
    val recognizer = remember {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) null
        else runCatching {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else SpeechRecognizer.createSpeechRecognizer(ctx)
        }.getOrNull()
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listening = true; problem = null }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { listening = false }
            override fun onError(error: Int) {
                listening = false
                problem = if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) "Didn't catch that." else "Speech isn't available right now."
            }
            override fun onResults(results: Bundle?) {
                listening = false
                results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { emit(it, true) }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { emit(it, false) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { recognizer?.destroy() }
    }
    val listen = {
        if (recognizer == null) problem = "Speech isn't available on this phone."
        else recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) listen() else problem = "Zeus needs the microphone to listen." }
    return VoiceCapture(
        start = {
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen() else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
        stop = { recognizer?.stopListening(); listening = false },
        listening = listening,
        problem = problem,
    )
}

// ---- Parnassus ----

@Composable
fun ParnassusScreen(hub: BrainHub, gutter: Dp, openSection: (Long) -> Unit) {
    val s by hub.senses.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    val voice = rememberVoiceCapture { text, final -> if (final) { hub.capture(text, CaptureKind.VOICE); draft = "" } else draft = text }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> if (bmp != null) { hub.capturePhoto(bmp, draft); draft = "" } }
    ParnassusContent(
        s, gutter, hubCallbacks(hub, openSection), draft, { draft = it },
        onKeep = { hub.capture(draft); draft = "" },
        onSpeak = { if (voice.listening) voice.stop() else voice.start() },
        onPhoto = { runCatching { photo.launch(null) } },
        listening = voice.listening, problem = voice.problem,
    )
}

@Composable
fun ParnassusContent(
    s: SensesState,
    gutter: Dp,
    cb: SensesCallbacks,
    draft: String,
    onDraft: (String) -> Unit,
    onKeep: () -> Unit,
    onSpeak: () -> Unit,
    onPhoto: () -> Unit,
    listening: Boolean = false,
    problem: String? = null,
) {
    val c = Athena.colors
    var naming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val visible = s.sections.filter { !it.hidden }
    val athenaChanges = s.changes.filter { it.actor == Actor.ATHENA && !it.undone }.take(8)
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(Place.RAMBLER.title(), gutter, subtitle = Place.RAMBLER.subtitle())
        Column(Modifier.padding(horizontal = gutter).widthIn(max = 720.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(draft, onDraft, if (listening) "Listening" else "Say it, type it, share it", Modifier.weight(1f), onSubmit = { if (draft.isNotBlank()) onKeep() })
                Spacer(Modifier.width(Space.s))
                TextAction("Keep", onKeep, emphasis = true, enabled = draft.isNotBlank())
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextAction(if (listening) "Stop" else "Speak", onSpeak, Modifier.offset(x = -Space.s))
                TextAction("Photo", onPhoto)
                Spacer(Modifier.weight(1f))
                if (listening) StatusWord("Listening", Tone.ACTIVE)
            }
            problem?.let { Line(it, Type.small, c.ink2) }
        }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    Kicker("Sections", Modifier.weight(1f))
                    TextAction(if (naming) "Cancel" else "New section", { naming = !naming; newName = "" })
                }
            }
            if (naming) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(newName, { newName = it }, "Name, like Groceries", Modifier.weight(1f), onSubmit = { cb.newSection(newName); naming = false })
                    TextAction("Make", { cb.newSection(newName); naming = false }, emphasis = true, enabled = newName.isNotBlank())
                }
            }
            if (visible.isEmpty() && !naming) item { Line("Lists appear here when you keep something that belongs in one.", Type.small, c.ink2, Modifier.padding(vertical = Space.s)) }
            items(visible, key = { "s${it.id}" }) { sec ->
                val open = s.items[sec.id].orEmpty().count { !it.done }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule).clickable(role = Role.Button) { cb.openSection(sec.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AthenaIcons.List, contentDescription = null, Modifier.size(22.dp), tint = c.ink2)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Line(sec.name, Type.body.copy(fontWeight = FontWeight.Medium), c.ink, maxLines = 1)
                        Line(if (open == 1) "1 open" else "$open open", Type.small, c.ink3, maxLines = 1)
                    }
                    when {
                        sec.pinned -> StatusWord("Pinned", Tone.ACTIVE)
                        sec.createdBy == Actor.ATHENA -> StatusWord("Made by Athena", Tone.QUIET)
                    }
                }
            }
            item { Kicker("Kept", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            if (s.loaded && s.captures.isEmpty()) item { Line("Say it, and Athena files it.", Type.body, c.ink2, Modifier.padding(vertical = Space.s)) }
            items(s.captures, key = { "c${it.id}" }) { cap -> CaptureRow(cap, cb) }
            if (athenaChanges.isNotEmpty()) {
                item { Kicker("Athena changed", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
                items(athenaChanges, key = { "ch${it.id}" }) { ch ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).topRule(c.rule), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                            Line(ch.summary, Type.small, c.ink)
                            Line(dayTime(ch.at), Type.small, c.ink3)
                        }
                        TextAction("Undo", { cb.undoChange(ch.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureRow(cap: Capture, cb: SensesCallbacks) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Line(cap.text.ifBlank { if (cap.kind == CaptureKind.PHOTO) "A photo" else "Empty" }, Type.body, c.ink, maxLines = 3)
            Spacer(Modifier.height(Space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (cap.status) {
                    CaptureStatus.SORTING -> StatusWord("Sorting", Tone.ACTIVE)
                    CaptureStatus.UNSORTED -> StatusWord("Not sorted", Tone.QUIET)
                    CaptureStatus.SORTED -> StatusWord(cap.place?.let { "In $it" } ?: "Kept here", if (cap.place != null) Tone.OK else Tone.QUIET)
                }
                Spacer(Modifier.width(Space.s))
                Line(
                    listOfNotNull(
                        when (cap.kind) { CaptureKind.VOICE -> "Spoken"; CaptureKind.PHOTO -> "Photo"; CaptureKind.LINK -> "Link"; CaptureKind.TEXT -> null },
                        dayTime(cap.at),
                    ).joinToString(", "),
                    Type.small, c.ink3, maxLines = 1,
                )
            }
            cap.outcome?.takeIf { cap.status != CaptureStatus.SORTING }?.let { Line(it, Type.small, c.ink2, Modifier.padding(top = Space.xxs)) }
        }
        if (cap.undo != null) TextAction("Undo", { cb.undoCapture(cap.id) })
    }
}

// ---- a dynamic section ----

@Composable
fun SectionScreen(hub: BrainHub, id: Long, gutter: Dp, back: () -> Unit) {
    val s by hub.senses.collectAsStateWithLifecycle()
    val sec = s.sections.firstOrNull { it.id == id }
    if (sec == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Section", gutter) { TextAction("Back", back) }
            if (s.loaded) Line("This section is gone.", Type.body, Athena.colors.ink2, Modifier.padding(horizontal = gutter))
        }
        return
    }
    SectionContent(sec, s.items[id].orEmpty(), gutter, hubCallbacks(hub), back)
}

@Composable
fun SectionContent(sec: Section, items: List<SectionItem>, gutter: Dp, cb: SensesCallbacks, back: () -> Unit) {
    val c = Athena.colors
    var draft by rememberSaveable(sec.id) { mutableStateOf("") }
    var renaming by remember(sec.id) { mutableStateOf(false) }
    var name by remember(sec.id) { mutableStateOf(sec.name) }
    var noteFor by remember(sec.id) { mutableStateOf<Long?>(null) }
    val open = items.filter { !it.done }
    val done = items.filter { it.done }
    val add = { if (draft.isNotBlank()) { cb.addItem(sec.id, draft); draft = "" } }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(sec.name, gutter, kicker = if (sec.place == "venus") Place.CLOSET.title() else "Section", onTitleTap = { renaming = true; name = sec.name }) {
            TextAction(if (sec.pinned) "Unpin" else "Pin", { cb.pin(sec.id, !sec.pinned) })
            TextAction(if (sec.hidden) "Show" else "Hide", { if (sec.hidden) cb.show(sec.id) else cb.hide(sec.id) })
            TextAction("Back", back)
        }
        if (renaming) Row(Modifier.padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(name, { name = it }, "Name", Modifier.weight(1f), onSubmit = { cb.rename(sec.id, name); renaming = false })
            TextAction("Save", { cb.rename(sec.id, name); renaming = false }, emphasis = true, enabled = name.isNotBlank())
            TextAction("Cancel", { renaming = false })
        }
        Row(Modifier.padding(horizontal = gutter).widthIn(max = 720.dp), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(draft, { draft = it }, "Add to ${sec.name}", Modifier.weight(1f), onSubmit = add)
            Spacer(Modifier.width(Space.s))
            TextAction("Add", add, emphasis = draft.isNotBlank(), enabled = draft.isNotBlank())
        }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.m, bottom = Space.xxxl)) {
            if (open.isEmpty()) item { Line("Nothing on ${sec.name}.", Type.body, c.ink2, Modifier.padding(vertical = Space.m)) }
            items(open, key = { it.id }) { ItemRow(it, noteFor == it.id, cb, onNote = { noteFor = if (noteFor == it.id) null else it.id }) }
            if (done.isNotEmpty()) {
                item { Kicker("Done", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
                items(done, key = { it.id }) { ItemRow(it, false, cb, onNote = {}) }
            }
            item {
                Column(Modifier.padding(top = Space.xl)) {
                    FactRow("Made by", sec.createdBy.word + ", " + day(sec.createdAt))
                    FactRow("When empty", if (sec.pinned) "Stays, it's pinned" else "Hides itself after 14 days. Nothing is deleted.")
                }
            }
        }
    }
}

@Composable
private fun ItemRow(i: SectionItem, editingNote: Boolean, cb: SensesCallbacks, onNote: () -> Unit) {
    val c = Athena.colors
    var note by remember(i.id) { mutableStateOf(i.note) }
    Column(Modifier.fillMaxWidth().topRule(c.rule)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 52.dp).clickable(role = Role.Checkbox) { cb.toggleItem(i) }, verticalAlignment = Alignment.CenterVertically) {
                val box = RoundedCornerShape(Radius.forSize(20.dp))
                Box(Modifier.size(20.dp).clip(box).border(1.5.dp, if (i.done) c.accent else c.ink3, box), contentAlignment = Alignment.Center) {
                    if (i.done) Icon(AthenaIcons.Check, contentDescription = null, Modifier.size(16.dp), tint = c.accentInk)
                }
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                    Line(i.text, Type.body, if (i.done) c.ink3 else c.ink)
                    if (i.note.isNotBlank() && !editingNote) Line(i.note, Type.small, c.ink2)
                    i.until?.takeIf { !i.done }?.let { Line("Relevant until ${day(it - 1)}", Type.small, c.ink3) }
                }
            }
            if (!i.done) TextAction(if (editingNote) "Close" else "Note", onNote)
        }
        if (editingNote) Row(Modifier.padding(start = 32.dp, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(note, { note = it }, "A note", Modifier.weight(1f), onSubmit = { cb.setNote(i, note); onNote() })
            TextAction("Save", { cb.setNote(i, note); onNote() }, emphasis = true)
        }
    }
}

// ---- People (Harmonia) ----

@Composable
fun PeopleScreen(hub: BrainHub, gutter: Dp) {
    val s by hub.senses.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var contactsOk by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contactsOk = it; if (it) hub.refreshPeople() }
    LaunchedEffect(contactsOk) { if (contactsOk) hub.refreshPeople() }
    PeopleContent(s.people, gutter, hubCallbacks(hub), contactsOk, onAllowContacts = { ask.launch(Manifest.permission.READ_CONTACTS) })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeopleContent(people: List<Person>, gutter: Dp, cb: SensesCallbacks, contactsOk: Boolean, onAllowContacts: () -> Unit, open: String? = null) {
    val c = Athena.colors
    var openKey by remember { mutableStateOf(open) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.PEOPLE.title(), gutter, subtitle = Place.PEOPLE.subtitle()) {
            if (!contactsOk) TextAction("Allow Contacts", onAllowContacts, emphasis = true)
        }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line(
                    "Learned from how fast you answer each person. Your choice always wins. Athena only ever sees labels like family or manager, and this list stays on the phone.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.m).widthIn(max = 560.dp),
                )
            }
            if (people.isEmpty()) item { Line("People show up here as their messages and calls arrive.", Type.body, c.ink2) }
            for (tier in Tier.entries) {
                val group = people.filter { it.tier == tier }
                if (group.isEmpty()) continue
                item(key = "t-${tier.name}") { Kicker(tier.label, Modifier.padding(top = Space.l, bottom = Space.xs)) }
                items(group, key = { it.key }) { p ->
                    val expanded = openKey == p.key
                    Column(Modifier.fillMaxWidth().topRule(c.rule)) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable(role = Role.Button) { openKey = if (expanded) null else p.key },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = Space.s)) {
                                Line(p.name, Type.body.copy(fontWeight = FontWeight.Medium), c.ink, maxLines = 1)
                                Line(relationLine(p), Type.small, c.ink3, maxLines = 1)
                            }
                            when {
                                p.calypso -> StatusWord("Sealed", Tone.QUIET)
                                p.pinned != null -> StatusWord("Your choice", Tone.ACTIVE)
                                p.learned -> StatusWord("Learned", Tone.OK)
                                else -> StatusWord("Learning", Tone.QUIET)
                            }
                        }
                        if (expanded) PersonEditor(p, cb)
                    }
                }
            }
        }
    }
}

private fun relationLine(p: Person): String {
    val rel = p.relation?.let { r -> r.word.replaceFirstChar { it.uppercase() } + if (p.confirmed) "" else ", not confirmed" }
    val habit = p.stats.ewmaMinutes?.takeIf { p.stats.engaged > 0 }?.let { m ->
        "usually answers in " + when { m < 1 -> "under a minute"; m < 90 -> "${m.toInt()} min"; else -> "${(m / 60).toInt()} h" }
    }
    return listOfNotNull(rel, habit, if (p.work) "work".takeIf { p.relation == null } else null).joinToString(", ").ifEmpty { "No relation set" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonEditor(p: Person, cb: SensesCallbacks) {
    val c = Athena.colors
    Column(Modifier.padding(start = Space.s, bottom = Space.m)) {
        Kicker("Priority", Modifier.padding(top = Space.xs))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            for (t in Tier.entries) Word(t.label, p.pinned == t) { cb.setTier(p.key, t) }
            Word("Learn it", p.pinned == null) { cb.setTier(p.key, null) }
        }
        Kicker("Relation", Modifier.padding(top = Space.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            for (r in Relation.entries) Word(r.word.replaceFirstChar { it.uppercase() }, p.relation == r && p.confirmed) { cb.setRelation(p.key, r) }
            Word("None", p.relation == null) { cb.setRelation(p.key, null) }
        }
        Kicker("Lives", Modifier.padding(top = Space.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Word("Here", p.zone == null) { cb.setZone(p.key, null) }
            Word(TwoClocks.CHOICES.first().second, p.zone == TwoClocks.INDIA.id) { cb.setZone(p.key, TwoClocks.INDIA.id) }
            if (TwoClocks.secondZone != TwoClocks.INDIA) Word(TwoClocks.now().label(TwoClocks.secondZone), p.zone == TwoClocks.secondZone.id) { cb.setZone(p.key, TwoClocks.secondZone.id) }
        }
        FactRow("Seen", if (p.stats.n == 0) "No replies counted yet" else "${p.stats.n} notifications, answered ${p.stats.engaged}")
        FactRow("Athena sees", p.label)
        TextAction(if (p.calypso) "Unseal from Calypso" else "Seal in Calypso", { cb.setCalypso(p.key, !p.calypso) }, Modifier.offset(x = -Space.s))
        if (p.calypso) Line("Their notifications are never stored.", Type.small, c.ink2)
    }
}

/** An option as a plain word: chosen is full ink with a saffron underline. No pills, no chips. */
@Composable
private fun Word(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    Box(Modifier.heightIn(min = 40.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = Space.xs), contentAlignment = Alignment.Center) {
        Line(
            text, Type.small.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal), if (on) c.ink else c.ink3,
            Modifier.then(if (on) Modifier.topRuleBelow(accent) else Modifier),
        )
    }
}

/** The saffron underline under a chosen word. */
private fun Modifier.topRuleBelow(color: androidx.compose.ui.graphics.Color): Modifier = this.drawBehind {
    val w = 2.dp.toPx()
    drawLine(color, Offset(0f, size.height + w), Offset(size.width, size.height + w), w)
}

// ---- Lethe ----

@Composable
fun LetheScreen(hub: BrainHub, gutter: Dp, back: () -> Unit) {
    val s by hub.senses.collectAsStateWithLifecycle()
    LetheContent(s.lethe, gutter, hubCallbacks(hub), back)
}

/**
 * Lethe, the river of forgetting: what hygiene closed, each with its reason and Undo. Opening it
 * sends one slow ripple across the header (the easter egg), then it's still.
 */
@Composable
fun LetheContent(rows: List<LetheRow>, gutter: Dp, cb: SensesCallbacks, back: () -> Unit, ripple: Float? = null) {
    val c = Athena.colors
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    val thisWeek = rows.count { !it.restored && it.at >= weekAgo }
    androidx.compose.runtime.LaunchedEffect(Unit) { dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.LETHE) }
    Box(Modifier.fillMaxSize()) {
        LetheRipple(Modifier.fillMaxWidth().height(220.dp), ripple)
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(Place.EXPIRED.title(), gutter, subtitle = Place.EXPIRED.subtitle()) { TextAction(Place.TASKS.title(), back) }
            Line("What's forgotten here was finished.", Type.small, c.ink3, Modifier.padding(horizontal = gutter))
            LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.l, bottom = Space.xxxl)) {
                if (thisWeek > 0) item { FactRow("This week", if (thisWeek == 1) "1 closed for you" else "$thisWeek closed for you") }
                if (rows.isEmpty()) item { Line("Nothing has gone to Lethe yet.", Type.body, c.ink2, Modifier.padding(top = Space.m)) }
                items(rows, key = { it.id }) { r ->
                    Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Line(r.title, Type.body, if (r.restored) c.ink3 else c.ink)
                            Line(r.reason, Type.small, c.ink2)
                            Line((if (r.refType == "task") "Praxis task" else "List item") + ", closed " + day(r.at), Type.small, c.ink3)
                        }
                        if (r.restored) StatusWord("Restored", Tone.QUIET, Modifier.padding(top = Space.s)) else TextAction("Undo", { cb.restore(r.id) })
                    }
                }
            }
        }
    }
}

/** Three thin rings spreading from the title and fading, once (~1.8 s), never repeating. */
@Composable
private fun LetheRipple(modifier: Modifier, fixed: Float?) {
    val progress = remember { Animatable(fixed ?: 0f) }
    LaunchedEffect(fixed) { if (fixed == null) progress.animateTo(1f, tween(1800, easing = LinearOutSlowInEasing)) }
    val ink = Athena.colors.ink
    Canvas(modifier) {
        val p = progress.value
        if (p >= 1f) return@Canvas
        val center = Offset(size.width * 0.28f, size.height * 0.42f)
        for (k in 0 until 3) {
            val t = ((p - k * 0.16f) / (1f - k * 0.16f)).coerceIn(0f, 1f)
            if (t <= 0f) continue
            drawCircle(ink.copy(alpha = 0.16f * (1f - t)), radius = size.width * 0.7f * t, center = center, style = Stroke(1.2.dp.toPx()))
        }
    }
}

// ---- worker jobs in Praxis ----

/** Jobs Parnassus routed to a worker. Stored only; they go out when the phase 3 channel exists. */
@Composable
fun WorkerJobsZone(jobs: List<WorkerJob>, gutter: Dp) {
    if (jobs.isEmpty()) return
    val c = Athena.colors
    Column(Modifier.padding(horizontal = gutter).widthIn(max = 720.dp)) {
        Kicker("Waiting on workers", Modifier.padding(top = Space.s, bottom = Space.xs))
        for (j in jobs.take(5)) {
            Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.s)) {
                Line(j.job, Type.small, c.ink, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Line(j.owner.display, Type.small.copy(fontWeight = FontWeight.Medium), c.ink2)
                    Spacer(Modifier.width(Space.s))
                    StatusWord("Waiting for phase 3 channel", Tone.QUIET)
                }
            }
        }
        if (jobs.size > 5) Line("${jobs.size - 5} more in Parnassus", Type.small, c.ink3)
    }
}

/** Olympus's quick capture: one line, kept in Parnassus. */
@Composable
fun QuickCapture(onKeep: (String) -> Unit, onDone: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    val keep = { if (draft.isNotBlank()) { onKeep(draft); draft = ""; onDone() } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        UnderlineField(draft, { draft = it }, "Keep it in ${Place.RAMBLER.title()}", Modifier.weight(1f), onSubmit = keep)
        Spacer(Modifier.width(Space.s))
        TextAction("Keep", keep, emphasis = true, enabled = draft.isNotBlank())
    }
}
