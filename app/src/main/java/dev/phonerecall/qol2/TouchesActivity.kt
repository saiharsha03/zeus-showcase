package dev.phonerecall.qol2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.data.Prefs
import dev.phonerecall.settings.SettingRow
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Small touches: a switch per touch, and what he keeps for them (timers, borrowed things, read-later links,
 * notes for leaving, day folders, appointment files, car dates, the charger plug, cancellation proofs).
 * Also the two pickers the cards open: photos to share with family, and a receipt for Drive.
 */
class TouchesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val dark = runCatching { Prefs(app).load().dark }.getOrDefault(true)
        val mode = intent.getStringExtra(EXTRA_MODE)
        thread { Touches.load(app) }
        setContent {
            AthenaTheme(dark) {
                Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
                    when (mode) {
                        MODE_PHOTOS -> PhotosFlow(intent.getLongExtra(EXTRA_FROM, 0), intent.getLongExtra(EXTRA_TO, 0)) { finish() }
                        MODE_RECEIPT -> ReceiptFlow(intent.getStringExtra(EXTRA_NAME) ?: "Receipt") { finish() }
                        else -> TouchesScreen()
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_FROM = "from"
        private const val EXTRA_TO = "to"
        private const val EXTRA_NAME = "name"
        private const val MODE_PHOTOS = "photos"
        private const val MODE_RECEIPT = "receipt"
        fun intent(c: Context): Intent = Intent(c, TouchesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        fun photos(c: Context, from: Long, to: Long): Intent = intent(c).putExtra(EXTRA_MODE, MODE_PHOTOS).putExtra(EXTRA_FROM, from).putExtra(EXTRA_TO, to)
        fun receipt(c: Context, name: String): Intent = intent(c).putExtra(EXTRA_MODE, MODE_RECEIPT).putExtra(EXTRA_NAME, name)
    }
}

private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
private fun day(ms: Long) = DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

/**
 * The photo picker can't open on a date range without the media permission Zeus doesn't hold, so it names the
 * days and opens on recent photos; what he picks goes to the share sheet, where he chooses who gets them.
 */
@Composable
private fun PhotosFlow(from: Long, to: Long, done: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) {
            val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { ctx.startActivity(Intent.createChooser(send, "Send to family")) }
            dev.phonerecall.clio.Clio.info(Touches.AREA, "photos shared", "${uris.size}")
        }
        done()
    }
    LaunchedEffect(Unit) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    Column(Modifier.fillMaxSize().padding(Space.gutterNarrow), verticalArrangement = Arrangement.Center) {
        Line("Photos from ${day(from)}${if (day(from) != day(to)) " to ${day(to)}" else ""}", Type.title)
    }
}

/** One receipt photo to Drive's upload sheet (Drive keeps the folder he chose last); without Drive, the share sheet. */
@Composable
private fun ReceiptFlow(name: String, done: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, name).putExtra(Intent.EXTRA_SUBJECT, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val drive = Intent(send).setPackage("com.google.android.apps.docs")
            if (runCatching { ctx.startActivity(drive) }.isFailure) runCatching { ctx.startActivity(Intent.createChooser(send, "Save receipt")) }
            dev.phonerecall.clio.Clio.info(Touches.AREA, "receipt to drive")
        }
        done()
    }
    LaunchedEffect(Unit) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    Column(Modifier.fillMaxSize().padding(Space.gutterNarrow), verticalArrangement = Arrangement.Center) { Line("Pick the $name receipt", Type.title) }
}

@Composable
private fun TouchesScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val app = ctx.applicationContext
    val p by Touches.prefs.collectAsStateWithLifecycle()
    fun change(f: (TouchPrefs) -> TouchPrefs) = thread { Touches.update(app) { f(it) } }
    fun toast(s: String) = Toast.makeText(app, s, Toast.LENGTH_SHORT).show()
    val gutter = Space.gutterNarrow
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
        ScreenHeader("Small touches", gutter, subtitle = "One helper card each, from what Zeus already knows")
        Column(Modifier.padding(horizontal = gutter).widthIn(max = 640.dp)) {
            Section("Switches")
            for (t in Touch.entries) {
                val on = t !in p.off
                SettingRow(t.label, t.about, onClick = { thread { Touches.setOn(app, t, !on) } }) { StatusWord(if (on) "On" else "Off", if (on) Tone.ACTIVE else Tone.QUIET) }
            }
            SettingRow("Match mode: Do not disturb too", "Also turns on the phone's Do not disturb for the match (needs Do not disturb access)", onClick = { change { it.copy(matchDnd = !it.matchDnd) } }) {
                StatusWord(if (p.matchDnd) "On" else "Off", if (p.matchDnd) Tone.ACTIVE else Tone.QUIET)
            }

            Section("Quick timers")
            for (s in p.timers) SettingRow("${s.label}, ${s.minutes} min", null) { TextAction("Remove", { change { q -> q.copy(timers = q.timers - s) } }) }
            var tl by remember { mutableStateOf("") }
            var tm by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnderlineField(tl, { tl = it }, "Laundry", Modifier.weight(1f))
                Spacer(Modifier.padding(Space.xs))
                UnderlineField(tm, { tm = it.filter(Char::isDigit).take(3) }, "45", Modifier.weight(0.4f), keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
                TextAction("Save", {
                    val m = tm.toIntOrNull()
                    if (tl.isNotBlank() && m != null && m in 1..600) { change { it.copy(timers = it.timers + SavedTimer(tl.trim(), m)) }; tl = ""; tm = "" }
                })
            }

            Section("Borrowed")
            for (b in p.borrowed.filter { !it.returned }) SettingRow("${b.who} has your ${b.what}", b.due?.let { "Due ${DAY.format(LocalDate.parse(it))}" } ?: "Since ${day(b.since)}") {
                TextAction("Returned", { change { q -> q.copy(borrowed = q.borrowed.map { if (it.id == b.id) it.copy(returned = true) else it }) } })
            }
            var bt by remember { mutableStateOf("") }
            Entry(bt, { bt = it }, "Jamie has my controller till Nov 2") {
                val b = TouchRules.parseBorrowed(bt, LocalDate.now(), System.currentTimeMillis())
                if (b == null) toast("Try \"Jamie has my controller\"") else { change { it.copy(borrowed = it.borrowed + b) }; bt = "" }
            }

            Section("Read later")
            Line("Share a link to Zeus and pick Read later.", Type.small, Athena.colors.ink3)
            for (l in p.links.filter { it.openedAt == null }.sortedByDescending { it.savedAt }) SettingRow(l.title.ifBlank { l.url }, TouchRules.host(l.url), onClick = {
                Touches.open(ctx, l.url); thread { Touches.readLink(app, l.id) }
            }) { TextAction("Remove", { change { q -> q.copy(links = q.links.filter { it.id != l.id }) } }) }

            Section("When I leave here")
            for (n in p.leave) SettingRow(n.text, "Leaving ${n.place.ifEmpty { "that place" }}") { TextAction("Cancel", { change { q -> q.copy(leave = q.leave - n) } }) }
            var lt by remember { mutableStateOf("") }
            Entry(lt, { lt = it }, "Take the umbrella back") {
                if (lt.isNotBlank()) thread {
                    val n = Touches.leaveNote(app, lt)
                    (ctx as? android.app.Activity)?.runOnUiThread { if (n == null) toast("Places doesn't know where you are yet") else { toast("Reminds you as you leave ${n.place.ifEmpty { "here" }}"); lt = "" } }
                }
            }

            Section("Day folder")
            PinEntry(dateHint = "Tomorrow, or Nov 2", onPin = { date, label, uri -> change { it.copy(pins = it.pins + Pin("p${System.currentTimeMillis()}", label, uri, date = date)) } }, toast = ::toast)
            for (pin in p.pins.filter { it.date != null && it.date >= LocalDate.now().toString() }.sortedBy { it.date }) SettingRow(pin.label, pin.date?.let { DAY.format(LocalDate.parse(it)) }) {
                TextAction("Remove", { change { q -> q.copy(pins = q.pins - pin) } })
            }

            Section("Appointment packet")
            Packets(p, onAttach = { key, label, uri -> change { it.copy(pins = it.pins + Pin("p${System.currentTimeMillis()}", label, uri, event = key)) } }, onRemove = { pin -> change { q -> q.copy(pins = q.pins - pin) } })

            Section("Car")
            DateRow("Registration expires", p.registration) { d -> change { it.copy(registration = d) } }
            DateRow("Inspection due", p.inspection) { d -> change { it.copy(inspection = d) } }

            Section("Charger plug")
            val home by dev.phonerecall.hestia.HestiaCore.get(app).state.collectAsStateWithLifecycle()
            val plugs = home.devices.filter { it.kind == dev.phonerecall.hestia.DeviceKind.PLUG }
            if (plugs.isEmpty()) Line("No smart plugs in ${dev.phonerecall.ui.Place.DEVICES.myth} yet.", Type.small, Athena.colors.ink3)
            for (d in plugs) SettingRow(d.name, null, onClick = { change { it.copy(charger = if (it.charger == d.id) null else d.id) } }) {
                if (p.charger == d.id) StatusWord("Charger", Tone.ACTIVE)
            }

            Section("Cancellation proofs")
            if (p.proofs.isEmpty()) Line("Cancellation notices for subscriptions land here.", Type.small, Athena.colors.ink3)
            for (pr in p.proofs.sortedByDescending { it.at }) SettingRow(pr.service, "${day(pr.at)} · ${pr.line}".take(140)) { TextAction("Remove", { change { q -> q.copy(proofs = q.proofs - pr) } }) }
            Spacer(Modifier.height(Space.xxxl))
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(Space.xl))
    Kicker(title, Modifier.padding(bottom = Space.s))
}

@Composable
private fun Entry(value: String, onValue: (String) -> Unit, hint: String, onAdd: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        UnderlineField(value, onValue, hint, Modifier.weight(1f), onSubmit = onAdd)
        TextAction("Add", onAdd)
    }
}

@Composable
private fun DateRow(label: String, value: String?, onSet: (String?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let { DAY.format(LocalDate.parse(it)) }.orEmpty()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Line(label, Type.small, Athena.colors.ink2, Modifier.weight(0.8f))
        UnderlineField(text, { text = it }, "Mar 14", Modifier.weight(1f), onSubmit = { onSet(TouchRules.dateOf(text, LocalDate.now())?.toString()) })
        TextAction("Save", { onSet(if (text.isBlank()) null else TouchRules.dateOf(text, LocalDate.now())?.toString()) })
    }
}

/** A label and either a link or a file (picked with the system's file picker, kept readable across restarts). */
@Composable
private fun PinEntry(dateHint: String?, onPin: (String?, String, String) -> Unit, toast: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var date by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    fun dateIso(): String? = if (dateHint == null) null else TouchRules.dateOf(date, LocalDate.now())?.toString()
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val d = dateIso()
            if (dateHint != null && d == null) toast("Which day?") else { onPin(d, label.ifBlank { "File" }, uri.toString()); label = "" }
        }
    }
    Column {
        if (dateHint != null) UnderlineField(date, { date = it }, dateHint, Modifier.fillMaxWidth())
        UnderlineField(label, { label = it }, "What it is", Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            UnderlineField(link, { link = it }, "A link (or pick a file)", Modifier.weight(1f))
            TextAction("Pin link", {
                val d = dateIso()
                val url = TouchParse.firstUrl(link)
                when {
                    dateHint != null && d == null -> toast("Which day?")
                    url == null -> toast("That isn't a link")
                    else -> { onPin(d, label.ifBlank { TouchRules.host(url) }, url); label = ""; link = "" }
                }
            })
            TextAction("File", { file.launch(arrayOf("*/*")) })
        }
    }
}

/** The next week's appointments, each with the files he attached and one to add. */
@Composable
private fun Packets(p: TouchPrefs, onAttach: (String, String, String) -> Unit, onRemove: (Pin) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var events by remember { mutableStateOf<List<dev.phonerecall.data.CalItem>>(emptyList()) }
    LaunchedEffect(Unit) {
        events = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val src = dev.phonerecall.data.CalendarSource(ctx)
                val now = System.currentTimeMillis()
                src.readRange(now, now + 7 * 86_400_000L, src.calendars().associateBy { it.id }).filter { !it.allDay }.sortedBy { it.begin }.take(12)
            }.getOrDefault(emptyList())
        }
    }
    var forKey by remember { mutableStateOf<String?>(null) }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = forKey
        if (uri != null && k != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: "File"
            onAttach(k, name.take(40), uri.toString())
        }
    }
    if (events.isEmpty()) Line("No appointments in the next week.", Type.small, Athena.colors.ink3)
    for (e in events) {
        val pins = p.pins.filter { it.event == e.key }
        SettingRow(e.title, "${day(e.begin)}, ${dev.phonerecall.home.Fmt.time(e.begin, java.time.ZonedDateTime.now())}" + if (pins.isNotEmpty()) " · ${pins.size} attached" else "") {
            TextAction("Attach", { forKey = e.key; file.launch(arrayOf("*/*")) })
        }
        for (pin in pins) Row(Modifier.padding(start = Space.l), verticalAlignment = Alignment.CenterVertically) {
            Line(pin.label, Type.small, Athena.colors.ink2, Modifier.weight(1f))
            TextAction("Remove", { onRemove(pin) })
        }
    }
}
