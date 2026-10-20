package dev.phonerecall.mind

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.brain.BrainHub
import dev.phonerecall.brain.MindState
import dev.phonerecall.brain.Section
import dev.phonerecall.brain.SpendState
import dev.phonerecall.memory.MemoryHit
import dev.phonerecall.memory.Shield
import dev.phonerecall.memory.Source
import dev.phonerecall.places.Place
import dev.phonerecall.places.Places
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import dev.phonerecall.ui.Place as UiPlace

private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a", Locale.US)
private fun day(ms: Long) = DAY.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun dayTime(ms: Long) = DAY_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
private fun usd(cents: Long) = "$" + String.format(Locale.US, "%.2f", cents / 100.0)

/** A plain row under a hairline: title, one line, a trailing word or action. */
@Composable
private fun Row2(title: String, about: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Line(title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 2)
            if (about != null) Line(about, Type.small, c.ink3, Modifier.widthIn(max = 540.dp))
        }
        Spacer(Modifier.width(Space.m))
        trailing()
    }
}

// ---- Alexandria ----

@Composable
fun AlexandriaScreen(hub: BrainHub, gutter: Dp) {
    val m by hub.mind.collectAsStateWithLifecycle()
    val hits by hub.hits.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) hub.addDocument(uri) { notice = it } }
    LaunchedEffect(Unit) { hub.reindexMemory() }
    AlexandriaContent(
        m, hits, query, gutter, notice,
        onQuery = { query = it }, onSearch = { hub.searchMemory(query) },
        onAdd = { runCatching { pick.launch(arrayOf("application/pdf", "text/plain", "text/markdown", "text/csv")) } },
        onRemove = { hub.removeDocument(it) },
        onIndexNow = { hub.indexMemoryNow() },
    )
}

@Composable
fun AlexandriaContent(
    m: MindState,
    hits: List<MemoryHit>,
    query: String,
    gutter: Dp,
    notice: String?,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onIndexNow: () -> Unit = {},
) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(UiPlace.MEMORY.title(), gutter, subtitle = UiPlace.MEMORY.subtitle()) { TextAction("Add a document", onAdd) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UnderlineField(query, onQuery, "Search everything kept here", Modifier.weight(1f), onSubmit = onSearch)
                    Spacer(Modifier.width(Space.s))
                    TextAction("Search", onSearch, emphasis = true, enabled = query.isNotBlank())
                }
            }
            notice?.let { n -> item { Line(n, Type.small, c.alert, Modifier.padding(top = Space.s)) } }
            if (hits.isNotEmpty()) {
                item { Kicker("Found", Modifier.padding(top = Space.l, bottom = Space.xs)) }
                for (h in hits) item(key = "hit-${h.docId}") {
                    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Line("[${h.n}]", Type.small.copy(fontWeight = FontWeight.Medium), c.accentInk, Modifier.width(32.dp))
                            Line(h.title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
                            Kicker(h.source.label)
                        }
                        Line(h.snippet, Type.small, if (h.shield == Shield.AEGIS) c.ink3 else c.ink2, Modifier.padding(start = 32.dp, top = Space.xxs))
                        if (h.at > 0) Line(day(h.at), Type.caption, c.ink3, Modifier.padding(start = 32.dp, top = Space.xxs))
                    }
                }
            }
            item { Kicker("Kept here", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            if (m.memoryStatus.isNotEmpty()) item {
                Row(Modifier.padding(bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Line(m.memoryStatus, Type.small, c.ink2, Modifier.weight(1f))
                    Spacer(Modifier.width(Space.s))
                    TextAction("Index now", onIndexNow, enabled = !m.indexing)
                }
            }
            for (s in Source.entries) item(key = "src-${s.name}") {
                val n = m.memoryCounts[s] ?: 0
                FactRow(s.label, if (n == 0) "Nothing yet" else "$n " + if (n == 1) "item" else "items", if (s == Source.NOTIFICATION && n > 0) "Shielded" else null, Tone.QUIET, labelWidth = 120.dp)
            }
            item {
                val vectors = m.memoryVectors
                FactRow(
                    "Search", if (vectors > 0) "Words and meaning: BM25 with EmbeddingGemma, ${m.memoryChunks} passages, $vectors embedded" else "Words only (BM25), ${m.memoryChunks} passages. Meaning search turns on when EmbeddingGemma is on the phone.",
                    if (vectors > 0) "Hybrid" else "BM25", if (vectors > 0) Tone.OK else Tone.QUIET, labelWidth = 120.dp,
                )
            }
            item {
                Line(
                    "Sealed things in ${UiPlace.VAULT.title()} are never kept here. Notification lines are shielded: Athena can use them, the screen shows them after your fingerprint.",
                    Type.small, c.ink3, Modifier.padding(top = Space.s).widthIn(max = 560.dp),
                )
            }
            item { Kicker("Documents you added", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            if (m.documents.isEmpty()) item { Line("None yet. PDFs, text and Markdown files are copied into Zeus and read on the phone.", Type.small, c.ink2) }
            for (d in m.documents) item(key = "doc-${d.id}") {
                Row2(d.name, "Added ${day(d.addedAt)}") { TextAction("Remove", { onRemove(d.id) }) }
            }
        }
    }
}

// ---- Places (Themis → Places) ----

@Composable
fun PlacesScreen(hub: BrainHub, gutter: Dp, back: () -> Unit) {
    val ctx = LocalContext.current
    val m by hub.mind.collectAsStateWithLifecycle()
    val senses by hub.senses.collectAsStateWithLifecycle()
    var fine by remember { mutableStateOf(Places.canFine(ctx)) }
    var background by remember { mutableStateOf(Places.canBackground(ctx)) }
    var problem by remember { mutableStateOf<String?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { fine = Places.canFine(ctx); background = Places.canBackground(ctx); if (fine && background) hub.registerGeofences() }
    val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> fine = r[Manifest.permission.ACCESS_FINE_LOCATION] == true }
    // Android sends "all the time" to system settings; the screen explains first.
    val askBackground = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { background = it; if (it) hub.registerGeofences() }
    PlacesContent(
        m.places, m.here, senses.sections.filter { !it.hidden }, fine, background, gutter, problem,
        onAllowFine = { askFine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
        onAllowBackground = { askBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
        onHere = { name, sections ->
            problem = null
            Places.here(ctx, { lat, lng ->
                hub.savePlace(Place(id = "p" + System.currentTimeMillis(), name = name.trim(), lat = lat, lng = lng, sections = sections))
            }, { problem = it })
        },
        onToggleList = { p, id -> hub.savePlace(p.copy(sections = if (id in p.sections) p.sections - id else p.sections + id)) },
        onRemove = { hub.removePlace(it) },
        back = back,
    )
}

@Composable
fun PlacesContent(
    places: List<Place>,
    here: String?,
    sections: List<Section>,
    fine: Boolean,
    background: Boolean,
    gutter: Dp,
    problem: String?,
    onAllowFine: () -> Unit,
    onAllowBackground: () -> Unit,
    onHere: (String, List<Long>) -> Unit,
    onToggleList: (Place, Long) -> Unit,
    onRemove: (String) -> Unit,
    back: () -> Unit,
) {
    val c = Athena.colors
    var name by rememberSaveable { mutableStateOf("") }
    var picked by remember { mutableStateOf(sections.firstOrNull { it.name.equals("Groceries", true) }?.let { listOf(it.id) } ?: emptyList()) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(UiPlace.PLACES.title(), gutter, kicker = UiPlace.SETTINGS.title(), subtitle = UiPlace.PLACES.subtitle()) { TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line(
                    "Name a store and Zeus shows the right list when you walk in: a notification, and the home-screen widget switches to that list while you are there. Your location stays on the phone. Athena only hears \"near Walmart\", never where you have been.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.m).widthIn(max = 560.dp),
                )
            }
            item {
                Row2("Precise location", "Used once when you tap I'm here now, and by the geofences.", onClick = if (fine) null else onAllowFine) {
                    StatusWord(if (fine) "Allowed" else "Allow", if (fine) Tone.OK else Tone.ACTIVE)
                }
            }
            item {
                Row2(
                    "All the time", "Needed so a store can wake Zeus when you arrive. Android asks on its own page: choose Allow all the time. No tracking runs in between.",
                    onClick = if (!fine || background) null else onAllowBackground,
                ) { StatusWord(if (background) "Allowed" else if (fine) "Allow" else "After precise", if (background) Tone.OK else if (fine) Tone.ACTIVE else Tone.QUIET) }
            }
            item { Kicker("Add a place", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            item { UnderlineField(name, { name = it }, "Walmart, Main St", Modifier.fillMaxWidth()) }
            item {
                Column(Modifier.padding(top = Space.s)) {
                    Line("Lists for this place", Type.small, c.ink3)
                    Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (sections.isEmpty()) Line("Make a list in Parnassus first, like Groceries.", Type.small, c.ink2)
                        for (s in sections.take(6)) {
                            val on = s.id in picked
                            Line(
                                s.name, Type.action, if (on) c.ink else c.ink3,
                                Modifier.clip(CircleShape).background(if (on) c.raised else c.surface).clickable(role = Role.Checkbox) { picked = if (on) picked - s.id else picked + s.id }
                                    .padding(horizontal = Space.m, vertical = Space.s),
                            )
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(top = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    PrimaryAction("I'm here now", { if (name.isNotBlank()) { onHere(name, picked); name = "" } })
                    Spacer(Modifier.width(Space.m))
                    Line("Saves where you are standing, as a 150 m circle.", Type.small, c.ink3, Modifier.weight(1f))
                }
            }
            problem?.let { p -> item { Line(p, Type.small, c.alert, Modifier.padding(top = Space.s)) } }
            item { Kicker("Your places", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
            if (places.isEmpty()) item { Line("None yet.", Type.body, c.ink2) }
            for (p in places) item(key = p.id) {
                Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Line(p.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
                        if (p.id == here) StatusWord("You're here", Tone.ACTIVE)
                        TextAction("Remove", { onRemove(p.id) })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        for (s in sections.take(6)) {
                            val on = s.id in p.sections
                            Line(s.name, Type.small, if (on) c.accentInk else c.ink3, Modifier.clickable(role = Role.Checkbox) { onToggleList(p, s.id) }.padding(end = Space.s, top = Space.xs, bottom = Space.xs))
                        }
                    }
                    Line(if (p.pregen != null) "Offline text ready" else "Offline text: written by Nano next time Zeus is open", Type.caption, c.ink3)
                }
            }
        }
    }
}

// ---- Long jobs (Praxis) ----

private fun statusTone(s: String) = when (s) { "done" -> Tone.OK; "failed" -> Tone.ALERT; "cancelled" -> Tone.QUIET; else -> Tone.ACTIVE }
private fun statusWord(s: String) = when (s) { "queued" -> "Sent"; "started" -> "Working"; "progress" -> "Working"; "done" -> "Done"; "failed" -> "Failed"; else -> "Cancelled" }

/** A protégé's coin (drawable coin_<name>), or their initial on a plain coin when there is no artwork. */
@Composable
fun ProtegeCoin(name: String?, size: Dp = 32.dp) {
    val c = Athena.colors
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val id = remember(name) { if (name == null) 0 else ctx.resources.getIdentifier("coin_" + name.lowercase(), "drawable", ctx.packageName) }
    if (id != 0) return dev.phonerecall.ui.ArtImage(id, size)
    Box(Modifier.size(size).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
        Line((name ?: "A").take(1), Type.action, c.accentInk)
    }
}

@Composable
fun LongJobsScreen(hub: BrainHub, gutter: Dp, back: () -> Unit) {
    val m by hub.mind.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { hub.refreshMind() }
    LongJobsContent(m.jobs, gutter, onCancel = { hub.cancelJob(it) }, back = back)
}

@Composable
fun LongJobsContent(jobs: List<LongJob>, gutter: Dp, onCancel: (String) -> Unit, back: () -> Unit) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Long jobs", gutter, kicker = UiPlace.TASKS.title(), subtitle = "Athena's circle, working in the background") { TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line(
                    "Ask in Delphi, like \"research winter tires and come back tomorrow\". Starting a job needs no confirmation. Anything a job wants to do at the end comes back to you as a proposal.",
                    Type.small, c.ink2, Modifier.padding(bottom = Space.m).widthIn(max = 560.dp),
                )
            }
            if (jobs.isEmpty()) item { Line("No long jobs yet.", Type.body, c.ink2) }
            for (j in jobs) item(key = j.id) {
                Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProtegeCoin(j.protege)
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Line(j.title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 2)
                            Line((j.protege ?: "Athena is choosing") + ", started " + dayTime(j.startedAt), Type.small, c.ink3, maxLines = 1)
                        }
                        StatusWord(statusWord(j.status), statusTone(j.status))
                    }
                    j.progress?.let { Line(it, Type.small, c.ink2, Modifier.padding(start = 44.dp, top = Space.xs)) }
                    j.why?.takeIf { j.protege == Protege.ANNABETH.display }?.let { Line("Why Annabeth: $it", Type.caption, c.ink3, Modifier.padding(start = 44.dp, top = Space.xxs)) }
                    if (j.status == "done" && j.resultTitle != null) {
                        Column(Modifier.padding(start = 44.dp, top = Space.s).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(dev.phonerecall.ui.Radius.blockSmall)).background(c.raised).padding(Space.m)) {
                            Line(j.resultTitle, Type.title, c.ink)
                            j.resultBody?.let { Line(it, Type.small, c.ink2) }
                        }
                    }
                    if (j.status !in setOf("done", "failed", "cancelled")) Row(Modifier.padding(start = 36.dp)) { TextAction("Cancel", { onCancel(j.id) }) }
                }
            }
        }
    }
}

/** Praxis's own strip: open jobs only, one line each. Tapping opens Long jobs. */
@Composable
fun LongJobsZone(jobs: List<LongJob>, gutter: Dp, open: () -> Unit) {
    val c = Athena.colors
    val live = jobs.filter { it.status !in setOf("done", "failed", "cancelled") }
    if (live.isEmpty()) return
    Column(Modifier.padding(horizontal = gutter).clickable(role = Role.Button, onClick = open).padding(vertical = Space.s)) {
        Kicker("Long jobs")
        for (j in live.take(3)) Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
            Line(j.protege ?: "Athena", Type.small.copy(fontWeight = FontWeight.Medium), c.ink2, Modifier.width(92.dp))
            Line(j.title, Type.small, c.ink, Modifier.weight(1f), maxLines = 1)
            StatusWord(statusWord(j.status), statusTone(j.status))
        }
    }
}

// ---- Pantheon: Athena's circle and the external workers ----

@Composable
fun PantheonContent(
    gutter: Dp, circle: Map<String, Long>, workers: List<dev.phonerecall.home.SampleData.Worker>,
    modifier: Modifier = Modifier,
    /** Phase 3: the live workers, jobs and tasks from Lemnos, shown first. Null keeps the static roster. */
    live: (androidx.compose.foundation.lazy.LazyListScope.() -> Unit)? = null,
) {
    val c = Athena.colors
    Column(modifier.fillMaxSize()) {
        ScreenHeader(UiPlace.AGENTS.title(), gutter, emblem = UiPlace.AGENTS, subtitle = UiPlace.AGENTS.subtitle())
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            live?.invoke(this)
            item { Kicker("Athena's circle", Modifier.padding(top = if (live != null) Space.xl else Space.s, bottom = Space.xs)) }
            item {
                Line("They work inside Athena's background sessions and share her monthly cap. They never act: what they find comes back through her as cards and proposals.", Type.small, c.ink2, Modifier.padding(bottom = Space.s).widthIn(max = 560.dp))
            }
            item {
                // Easter egg: three quick taps on her coin, and the myth shows for a moment.
                var taps by remember { mutableStateOf(0) }
                var lastTap by remember { mutableStateOf(0L) }
                var myth by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(myth) { if (myth) { kotlinx.coroutines.delay(6_000); myth = false } }
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clickable(interactionSource = null, indication = null) {
                        val now = System.currentTimeMillis()
                        taps = dev.phonerecall.ui.Eggs.metisTaps(taps, lastTap, now)
                        lastTap = now
                        if (taps >= 3) { taps = 0; myth = true; dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.METIS) }
                    }) { ProtegeCoin(Metis.DISPLAY) }
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Line(Metis.DISPLAY, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        Line(
                            if (myth) dev.phonerecall.ui.Eggs.METIS_LINE else "Advisor. Athena consults her on hard decisions. Her advice stays with Athena (encrypted).",
                            Type.small, if (myth) c.accentInk else c.ink3, Modifier.widthIn(max = 540.dp),
                        )
                    }
                    Spacer(Modifier.width(Space.m))
                    StatusWord(usd(circle[Metis.THREAD] ?: 0) + " this month", Tone.QUIET)
                }
            }
            for (p in Protege.entries) item(key = p.name) {
                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
                    ProtegeCoin(p.display)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Line(p.display, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        Line(p.role + ", " + p.model.removePrefix("claude-").replace('-', ' ').replace(" 5 5", " 5.5").replaceFirstChar { it.uppercase() }, Type.small, c.ink3, maxLines = 1)
                    }
                    StatusWord(usd(circle[p.agentName] ?: 0), Tone.QUIET)
                }
            }
            if (live == null) {
                item { Kicker("Workers", Modifier.padding(top = Space.xl, bottom = Space.xs)) }
                for (w in workers) item(key = w.name) {
                    Row2(w.name, w.role.replaceFirstChar { it.uppercase() }) { StatusWord("Not paired", Tone.QUIET) }
                }
            }
        }
    }
}

// ---- Themis: spend and the mind ----

/** Themis's Athena spend rows: the total against the cap, then the breakdown. */
@Composable
fun SpendRows(spend: SpendState, m: MindState) {
    val c = Athena.colors
    val cap = spend.capUsd * 100L
    val total = spend.spentMicros / 10_000
    Column {
        Row2("Spend this month", "All of Athena: Delphi and the phone's quick calls, plus her background mind and circle. She stops at the cap; one request already running may finish.") {
            StatusWord(usd(total) + " of $" + spend.capUsd, if (total >= cap) Tone.ALERT else Tone.QUIET)
        }
        Column(Modifier.fillMaxWidth().padding(bottom = Space.s)) {
            FactRow("Quick", usd((spend.messagesMicros - spend.advisorMicros) / 10_000) + " (Delphi, triage, arrivals)", labelWidth = 104.dp)
            // Metis's share counts both paths: Delphi's advisor tool and her consultations in the background.
            val metis = m.circle[Metis.THREAD] ?: 0
            FactRow("Metis", usd(metis) + " in advisor consultations", if (!TopTierCaps.allowed(metis, m.circle[Protege.ANNABETH.agentName] ?: 0).metis) "At cap" else null, Tone.ALERT, labelWidth = 104.dp)
            FactRow("Background", usd(spend.mindMicros / 10_000) + ", ${spend.activeSeconds / 60} min active, the circle below included", labelWidth = 104.dp)
            for (p in Protege.entries) {
                val cents = m.circle[p.agentName] ?: 0
                if (cents > 0 || p == Protege.ANNABETH) FactRow(p.display, usd(cents), if (p == Protege.ANNABETH && !TopTierCaps.allowed(m.circle[Metis.THREAD] ?: 0, cents).annabeth) "At cap" else null, Tone.ALERT, labelWidth = 104.dp)
            }
            Line("Metis and Annabeth share at most $30 a month, Metis at most $15. Above that Athena decides alone and uses the Sonnet circle.", Type.caption, c.ink3, Modifier.padding(top = Space.xs).widthIn(max = 520.dp))
        }
    }
}

/** Themis's mind rows: set up, schedules, last sync, wakeups. */
@Composable
fun MindRows(m: MindState, keySet: Boolean, openRemembered: () -> Unit, openPlaces: () -> Unit, openAssistant: () -> Unit, openMemoryCare: () -> Unit = {}, toggleHeartbeat: () -> Unit = {}, openHorologion: () -> Unit = {}) {
    val c = Athena.colors
    Column {
        Row2(
            "Athena's mind",
            if (!keySet) "Set up once your key is in ${UiPlace.VAULT.title()}." else if (m.setUp) "Runs on Anthropic's Managed Agents with her circle. Last sync ${m.lastSync?.let { dayTime(it) } ?: "not yet"}." else "Sets itself up on the next sync.",
        ) { StatusWord(if (m.setUp) "Running" else if (keySet) "Next sync" else "Needs key", if (m.setUp) Tone.OK else Tone.QUIET) }
        m.metisNote?.let { Line(it, Type.small, c.alert, Modifier.padding(bottom = Space.s)) }
        Column(Modifier.padding(bottom = Space.s)) {
            FactRow("Wakeups", "${m.wakeupsToday.size} today" + (m.wakeupsToday.firstOrNull()?.let { ", last: ${it.what}" } ?: ""), labelWidth = 104.dp)
        }
        // Her own hourly look at what changed; the phone skips it when nothing did.
        Row2("Heartbeat", m.heartbeat.line, onClick = toggleHeartbeat) { StatusWord(if (m.heartbeat.on) "On" else "Off", if (m.heartbeat.on) Tone.OK else Tone.QUIET) }
        Row2(UiPlace.HOROLOGION.title(), "Every job and its hour, every skill, hook and setting: Athena, Iris, Atlas and the server. Change what's safe.", onClick = openHorologion)
        Row2(UiPlace.DREAMS.title(), "What she knows, with ids you can fix by number. Tidied every night.", onClick = openMemoryCare)
        Row2("What Athena remembers", "Her long-term memory, read-only here. Forget any file.", onClick = openRemembered)
        Row2(UiPlace.PLACES.title(), "Stores that bring up your list when you arrive", onClick = openPlaces) { StatusWord("${m.places.size}", Tone.QUIET) }
        Row2("Default assistant", "Make Zeus the side-key assistant: Settings, Apps, Default apps, Digital assistant app.", onClick = openAssistant)
    }
}

@Composable
fun RememberedPage(hub: BrainHub, gutter: Dp, back: () -> Unit) {
    val m by hub.mind.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { hub.loadRemembered() }
    RememberedContent(m.remembered, m.rememberedError, gutter, onForget = { hub.forget(it) }, back = back)
}

@Composable
fun RememberedContent(files: List<MemoryFile>?, error: String?, gutter: Dp, onForget: (MemoryFile) -> Unit, back: () -> Unit) {
    val c = Athena.colors
    var open by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("What Athena remembers", gutter, kicker = UiPlace.SETTINGS.title()) { TextAction("Back", back) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
            item {
                Line("Athena keeps these files on Anthropic's side, by path. Zeus never writes names, numbers, money, health, sealed things or where you go. Forget deletes the file for good.", Type.small, c.ink2, Modifier.padding(bottom = Space.m).widthIn(max = 560.dp))
            }
            error?.let { e -> item { Line(e, Type.small, c.alert) } }
            if (files == null) item { StatusWord("Loading", Tone.ACTIVE) }
            else if (files.isEmpty() && error == null) item { Line("Nothing yet. Her memory starts on the first sync.", Type.body, c.ink2) }
            for ((folder, group) in files.orEmpty().groupBy { it.path.trim('/').substringBefore('/') }) {
                item(key = "folder-$folder") { Kicker("/$folder/", Modifier.padding(top = Space.l, bottom = Space.xs)) }
                for (f in group) item(key = f.id) {
                    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                        Row(Modifier.clickable { open = if (open == f.id) null else f.id }, verticalAlignment = Alignment.CenterVertically) {
                            Line(f.path.trim('/').substringAfter('/'), Type.body.copy(fontWeight = Type.action.fontWeight), c.ink, Modifier.weight(1f), maxLines = 1)
                            Line("${f.size} B", Type.caption, c.ink3)
                            TextAction("Forget this", { onForget(f) })
                        }
                        val text = f.content.orEmpty()
                        if (open == f.id || text.length < 240) Line(text.ifBlank { "(empty)" }, Type.small, c.ink2)
                        else Line(text.take(200) + "…", Type.small, c.ink2)
                    }
                }
            }
        }
    }
}
