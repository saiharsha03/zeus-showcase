package dev.phonerecall

import dev.phonerecall.ui.Workers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.calendar.CalendarScreen
import dev.phonerecall.closet.ClosetScreen
import dev.phonerecall.home.HomeScreen
import dev.phonerecall.settings.SettingsScreen
import dev.phonerecall.tasks.TasksScreen
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.Hinge
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.emblem
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** `listed` false: reached from another screen only (Lethe, from Praxis), not from More or the rail. */
enum class Dest(val place: Place, val icon: ImageVector, val phase: Int?, val line: String, val onCoverBar: Boolean, val listed: Boolean = true) {
    HOME(Place.HOME, AthenaIcons.Home, null, "", true),
    DELPHI(Place.ORACLE, AthenaIcons.Oracle, null, "", true),
    CALENDAR(Place.CALENDAR, AthenaIcons.Calendar, null, "", true),
    TASKS(Place.TASKS, AthenaIcons.Tasks, null, "", true),
    CLOSET(Place.CLOSET, AthenaIcons.Closet, null, "", false),
    FATES(Place.PROPOSALS, AthenaIcons.Fates, null, "", false),
    PARNASSUS(Place.RAMBLER, AthenaIcons.Quill, null, "", false),
    PEOPLE(Place.PEOPLE, AthenaIcons.People, null, "", false),
    LETHE(Place.EXPIRED, AthenaIcons.River, null, "", false, listed = false),
    // Phase 2c: Alexandria (memory search) and Praxis's Long jobs.
    MEMORY(Place.MEMORY, AthenaIcons.Quill, null, "", false),
    JOBS(Place.TASKS, AthenaIcons.Tasks, null, "", false, listed = false),
    AGENTS(Place.AGENTS, AthenaIcons.Agents, null, "", false),
    // Phase 3: the VPS (Lemnos) and its watchers (Cerberus).
    VPS(Place.VPS, AthenaIcons.Server, null, "", false),
    ALERTS(Place.ALERTS, AthenaIcons.Alerts, null, "", false),
    OLYMPIA(Place.SPORTS, AthenaIcons.Laurel, null, "", false),
    // Aeolia: BetweenWeather's page in a WebView, with its illustrated scenes (and the local-only Samsung pack when installed).
    WEATHER(Place.WEATHER, AthenaIcons.Weather, null, "", false),
    // Phase 5: Odyssey (timeline, places, commutes, drives).
    ODYSSEY(Place.PLACES, dev.phonerecall.odyssey.RoadIcon, null, "", false),
    HEALTH(Place.HEALTH, dev.phonerecall.iaso.IasoIcon, null, "", false),
    // Phase 6: Hestia (lights and plugs through the Google Home APIs).
    DEVICES(Place.DEVICES, AthenaIcons.Devices, null, "", false),
    // Calypso: one screen, two shelves (Sealed and Shielded, which was Aegis).
    VAULT(Place.VAULT, AthenaIcons.Vault, null, "", false),
    MONEY(Place.MONEY, AthenaIcons.Coin, null, "", false),
    TRIPS(Place.TRIPS, dev.phonerecall.argo.ShipIcon, null, "", false),
    // The last phase: self-improvement, manual trigger only.
    IMPROVE(Place.IMPROVE, dev.phonerecall.prometheus.FlameIcon, null, "", false),
    SETTINGS(Place.SETTINGS, AthenaIcons.Settings, null, "", false),
    THEOGONY(Place.SEED, AthenaIcons.Oracle, null, "", false, listed = false),
    // Metron: plan usage and Athena's spend, from Themis (and its widget, cards and alerts).
    USAGE(Place.USAGE, AthenaIcons.Settings, null, "", false, listed = false),
    // Horologion: every job, skill, hook and setting, from Themis.
    HOROLOGION(Place.HOROLOGION, AthenaIcons.Settings, null, "", false, listed = false),
    // Telete: in the rail and More while anything is left, then only from Themis.
    SETUP(Place.SETUP, AthenaIcons.Check, null, "", false);

    companion object {
        /** The rail's and More's destinations: Telete only while something is left. */
        fun shown(setupLeft: Boolean): List<Dest> = entries.filter { it.listed && (it != SETUP || setupLeft) }
    }
}

/** The "More" slot on the cover bar is not a destination of its own; it is a list of the rest. */
private const val MORE = "more"
/** The rail's search: places, features and settings rows. */
private const val SEARCH = "search"
/** A dynamic section's destination is "section:<id>". */
private const val SECTION = "section:"

/**
 * The app shell. Narrow (cover screen): content over a bottom bar with four destinations and More.
 * Wide (inner screen): a side rail with every destination. `wide` comes from the window size class,
 * so folding swaps them live.
 */
@Composable
fun Shell(vm: AthenaViewModel, wide: Boolean, hinge: Hinge?) {
    var destName by rememberSaveable { mutableStateOf(Dest.HOME.name) }
    val telete = dev.phonerecall.setup.TeleteHub.get(androidx.compose.ui.platform.LocalContext.current)
    val tele by telete.state.collectAsStateWithLifecycle()
    // Unknown (-1, before the first check) counts as left, so a new phone sees Telete.
    val setupLeft = tele.left != 0
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { telete.refresh() }
    val sectionId = destName.takeIf { it.startsWith(SECTION) }?.removePrefix(SECTION)?.toLongOrNull()
    val dest = if (destName == MORE || destName == SEARCH || sectionId != null) null else Dest.entries.firstOrNull { it.name == destName } ?: Dest.HOME
    var shelfName by rememberSaveable { mutableStateOf(dev.phonerecall.vault.Shelf.SEALED.name) }
    val shelf = dev.phonerecall.vault.Shelf.valueOf(shelfName)
    // Calypso from another screen or the rail is about keys, so it opens on Sealed.
    val go: (Dest) -> Unit = { if (it == Dest.VAULT) shelfName = dev.phonerecall.vault.Shelf.SEALED.name; destName = it.name }
    val openSection: (Long) -> Unit = { destName = SECTION + it }
    BackHandler(dest != Dest.HOME) {
        destName = when {
            sectionId != null -> Dest.PARNASSUS.name
            dest == Dest.LETHE || dest == Dest.JOBS -> Dest.TASKS.name
            dest == Dest.USAGE || dest == Dest.HOROLOGION -> Dest.SETTINGS.name
            dest == Dest.SETUP && !setupLeft -> Dest.SETTINGS.name
            else -> Dest.HOME.name
        }
    }
    // Theogony first: the seed on first launch (paired), or the interview when it is due.
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    LaunchedEffect(Unit) { if (dev.phonerecall.theogony.TheogonyHub.get(appContext).onLaunch()) destName = Dest.THEOGONY.name }
    // Themis search: the settings' meaning vectors, from the cache (or made once when EmbeddingGemma is on the phone).
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { dev.phonerecall.settings.ThemisIndex.warm(appContext) } } }
    // Until it's done, the conversation sits in the rail and More: "Getting to know you · N left".
    val knowing by dev.phonerecall.theogony.TheogonyHub.get(appContext).state.collectAsStateWithLifecycle()
    val knowLeft = knowing.left
    val homeRequests by vm.homeRequests.collectAsStateWithLifecycle()
    LaunchedEffect(homeRequests) { if (homeRequests > 0) destName = Dest.HOME.name }
    val destRequests by vm.destRequests.collectAsStateWithLifecycle()
    LaunchedEffect(destRequests) {
        if (destRequests.first > 0) {
            val want = destRequests.second
            val toShelf = dev.phonerecall.vault.Shelf.forDest(want)
            when {
                want.startsWith(SECTION) -> destName = want
                // The old Aegis destination opens Calypso on Shielded.
                toShelf != null -> { shelfName = toShelf.name; destName = Dest.VAULT.name }
                else -> Dest.entries.firstOrNull { it.name == want }?.let { destName = it.name }
            }
        }
    }
    val senses by vm.brain.senses.collectAsStateWithLifecycle()
    // Dynamic sections join More and the rail in registry order. The cover bar stays at four and More.
    val sections = senses.sections.filter { !it.hidden }
    // Unfolding while on More: the rail shows everything, so More has no meaning there.
    LaunchedEffect(wide) { if (wide && destName == MORE) destName = Dest.HOME.name }
    val gutter = if (wide) Space.gutterWide else Space.gutterNarrow
    val c = Athena.colors

    // A match from the sidebar's search: a place opens; a setting opens Themis on its row, lit.
    val pick: (dev.phonerecall.settings.SettingEntry) -> Unit = { e ->
        if (e.dest != null) Dest.entries.firstOrNull { it.name == e.dest }?.let(go)
        else { dev.phonerecall.settings.ThemisNav.row = e.row; go(Dest.SETTINGS) }
    }
    val content: @Composable (Modifier) -> Unit = { m ->
        Box(m) {
            if (destName == SEARCH) { SearchScreen(gutter, pick); return@Box }
            if (sectionId != null) { dev.phonerecall.brain.SectionScreen(vm.brain, sectionId, gutter) { go(Dest.PARNASSUS) }; return@Box }
            when (dest) {
                null -> MoreScreen(gutter, go, sections, openSection, setupLeft, pick, knowLeft)
                Dest.HOME -> HomeScreen(vm, columns = if (wide) 2 else 1, gutter = gutter, showOwl = !wide, hinge = hinge, navigate = go)
                Dest.CALENDAR -> CalendarScreen(vm, wide, gutter)
                Dest.TASKS -> TasksScreen(vm, wide, gutter, openLethe = { go(Dest.LETHE) }, openJobs = { go(Dest.JOBS) })
                Dest.JOBS -> dev.phonerecall.mind.LongJobsScreen(vm.brain, gutter) { go(Dest.TASKS) }
                Dest.MEMORY -> dev.phonerecall.mind.AlexandriaScreen(vm.brain, gutter)
                Dest.AGENTS -> {
                    val m by vm.brain.mind.collectAsStateWithLifecycle()
                    dev.phonerecall.lemnos.PantheonScreen(vm.lemnos, m.circle, wide, gutter, m.jobs)
                }
                Dest.VPS -> dev.phonerecall.lemnos.LemnosScreen(vm.lemnos, wide, gutter) { go(Dest.VAULT) }
                Dest.ALERTS -> dev.phonerecall.lemnos.CerberusScreen(vm.lemnos, gutter)
                Dest.CLOSET -> ClosetScreen(vm, wide, gutter)
                Dest.OLYMPIA -> dev.phonerecall.olympia.OlympiaScreen(wide, gutter)
                Dest.WEATHER -> dev.phonerecall.aeolia.AeoliaScreen(gutter)
                Dest.ODYSSEY -> dev.phonerecall.odyssey.OdysseyScreen(wide, gutter) { go(Dest.VAULT) }
                Dest.HEALTH -> dev.phonerecall.iaso.IasoScreen(wide, gutter)
                Dest.DEVICES -> dev.phonerecall.hestia.HestiaScreen(wide, gutter)
                Dest.SETTINGS -> SettingsScreen(vm, gutter, go)
                Dest.USAGE -> {
                    val m by vm.brain.mind.collectAsStateWithLifecycle()
                    dev.phonerecall.metron.MetronScreen(vm.lemnos, m.circle, wide, gutter) { go(Dest.SETTINGS) }
                }
                Dest.HOROLOGION -> dev.phonerecall.horologion.HorologionScreen(vm.brain, vm.lemnos, wide, gutter) { go(Dest.SETTINGS) }
                Dest.SETUP -> dev.phonerecall.setup.TeleteScreen(vm.brain, gutter, wide, go)
                Dest.DELPHI -> dev.phonerecall.brain.DelphiScreen(vm.brain, gutter) { go(Dest.VAULT) }
                Dest.FATES -> dev.phonerecall.brain.FatesScreen(vm.brain, gutter)
                Dest.VAULT -> dev.phonerecall.brain.CalypsoScreen(vm.brain, gutter, wide, shelf) { shelfName = it.name }
                Dest.MONEY -> dev.phonerecall.moneta.MonetaScreen(gutter, wide)
                Dest.TRIPS -> dev.phonerecall.argo.ArgoScreen(gutter, wide) { go(Dest.VAULT) }
                Dest.IMPROVE -> dev.phonerecall.prometheus.PrometheusScreen(gutter, wide)
                Dest.PARNASSUS -> dev.phonerecall.brain.ParnassusScreen(vm.brain, gutter, openSection)
                Dest.PEOPLE -> dev.phonerecall.brain.PeopleScreen(vm.brain, gutter)
                Dest.LETHE -> dev.phonerecall.brain.LetheScreen(vm.brain, gutter) { go(Dest.TASKS) }
                Dest.THEOGONY -> dev.phonerecall.theogony.TheogonyScreen(gutter, wide) { go(Dest.HOME) }
                else -> ComingSoon(dest, gutter)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavRail(dest, go, sections, sectionId, openSection, setupLeft, destName == SEARCH, knowLeft) { destName = SEARCH }
                content(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                content(Modifier.weight(1f).fillMaxWidth())
                BottomBar(dest, go) { destName = MORE }
            }
        }
    }
}

/** Selected: full ink and a short saffron rule under the label. Others: quiet ink. No pill indicator. */
@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onSelect: () -> Unit, modifier: Modifier, plain: String = label, emblem: Place? = null) {
    val c = Athena.colors
    val tint = if (selected) c.ink else c.ink3
    val accent = c.accent
    Column(
        // The myth name is shown; the plain word is what a screen reader says.
        modifier.selectable(selected, role = Role.Tab, onClick = onSelect).semantics(mergeDescendants = true) { contentDescription = plain }.padding(vertical = Space.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The inner rail shows the coin emblems; the cover bar keeps line icons (photos read badly at 24 dp).
        if (emblem?.emblem != null) dev.phonerecall.ui.Emblem(emblem, 28.dp, alpha = if (selected) 1f else 0.6f)
        else Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = tint)
        Spacer(Modifier.height(Space.xs))
        Line(
            label, Type.caption, tint,
            Modifier.drawBehind {
                if (selected) {
                    val w = 2.dp.toPx()
                    val y = size.height + w * 1.5f
                    drawLine(accent, Offset(size.width * 0.2f, y), Offset(size.width * 0.8f, y), w)
                }
            },
            maxLines = 1,
        )
        Spacer(Modifier.height(Space.xs))
    }
}

@Composable
private fun BottomBar(dest: Dest?, onSelect: (Dest) -> Unit, onMore: () -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface).topRule(c.rule).navigationBarsPadding().height(68.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (d in Dest.entries.filter { it.onCoverBar }) {
            NavItem(d.place.title(), d.icon, d == dest, { onSelect(d) }, Modifier.weight(1f).fillMaxHeight(), plain = d.place.plain)
        }
        NavItem("More", AthenaIcons.More, dest == null || !dest.onCoverBar, onMore, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun NavRail(dest: Dest?, onSelect: (Dest) -> Unit, sections: List<dev.phonerecall.brain.Section>, sectionId: Long?, openSection: (Long) -> Unit, setupLeft: Boolean, searching: Boolean, knowLeft: Int, onSearch: () -> Unit) {
    val c = Athena.colors
    val rule = c.rule
    Column(
        Modifier
            .fillMaxHeight()
            .width(88.dp)
            .drawBehind { drawLine(rule, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(top = Space.l, bottom = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        dev.phonerecall.ui.AthenaAvatar(32.dp)
        Spacer(Modifier.height(Space.l))
        NavItem("Search", AthenaIcons.Search, searching, onSearch, Modifier.fillMaxWidth().height(68.dp))
        if (knowLeft > 0) NavItem("$knowLeft left", Dest.THEOGONY.icon, dest == Dest.THEOGONY, { onSelect(Dest.THEOGONY) }, Modifier.fillMaxWidth().height(68.dp), plain = "${Place.SEED.plain}, $knowLeft left", emblem = Place.SEED)
        for (d in Dest.shown(setupLeft)) NavItem(d.place.title(), d.icon, d == dest, { onSelect(d) }, Modifier.fillMaxWidth().height(68.dp), plain = d.place.plain, emblem = d.place)
        for (s in sections) NavItem(s.name, AthenaIcons.List, s.id == sectionId, { openSection(s.id) }, Modifier.fillMaxWidth().height(68.dp))
    }
}

/** The cover bar's More: the rest of the destinations as plain rows. */
@Composable
private fun MoreScreen(gutter: Dp, onSelect: (Dest) -> Unit, sections: List<dev.phonerecall.brain.Section>, openSection: (Long) -> Unit, setupLeft: Boolean, pick: (dev.phonerecall.settings.SettingEntry) -> Unit, knowLeft: Int = 0) {
    val c = Athena.colors
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("More", gutter)
        SearchField(query, gutter) { query = it }
        if (query.isNotBlank()) { SearchHits(query, gutter) { query = ""; pick(it) }; return@Column }
        Column(Modifier.padding(horizontal = gutter).verticalScroll(rememberScrollState())) {
            if (knowLeft > 0) Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onSelect(Dest.THEOGONY) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dev.phonerecall.ui.Emblem(Place.SEED, 32.dp)
                Spacer(Modifier.width(Space.l))
                Column(Modifier.weight(1f)) {
                    Line("${Place.SEED.title()} · $knowLeft left", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                    Line("Athena has a few questions, or skip them", Type.small, c.ink3, maxLines = 1)
                }
            }
            for (d in Dest.shown(setupLeft).filter { !it.onCoverBar }) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule).clickable(role = Role.Button) { onSelect(d) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (d.place.emblem != null) dev.phonerecall.ui.Emblem(d.place, 32.dp)
                    else Icon(d.icon, contentDescription = null, Modifier.size(24.dp), tint = c.ink2)
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f)) {
                        Line(d.place.title(), Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        d.place.subtitle()?.let { Line(it, Type.small, c.ink3, maxLines = 1) }
                    }
                    if (d.phase != null) Kicker("Phase ${d.phase}")
                }
            }
            for (s in sections) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).topRule(c.rule).clickable(role = Role.Button) { openSection(s.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AthenaIcons.List, contentDescription = null, Modifier.size(24.dp), tint = c.ink2)
                    Spacer(Modifier.width(Space.l))
                    Column(Modifier.weight(1f)) {
                        Line(s.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        Line(if (s.createdBy == dev.phonerecall.brain.Actor.ATHENA) "A section Athena made" else "Your section", Type.small, c.ink3, maxLines = 1)
                    }
                    if (s.pinned) Kicker("Pinned")
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, gutter: Dp, onQuery: (String) -> Unit) =
    dev.phonerecall.ui.UnderlineField(query, { onQuery(it.take(60)) }, "Search Zeus: Moneta, sleep, where I park", Modifier.fillMaxWidth().padding(horizontal = gutter), style = Type.small)

/** Places, features and settings rows: Greek names, plain words and meaning, in one list. */
@Composable
private fun SearchHits(query: String, gutter: Dp, pick: (dev.phonerecall.settings.SettingEntry) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val (found, meaning) = dev.phonerecall.settings.rememberSearch(query, dev.phonerecall.settings.ThemisRegistry.all, dev.phonerecall.settings.ThemisIndex.vectors) { q ->
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.phonerecall.settings.ThemisIndex.embed(ctx, q) }
    }
    dev.phonerecall.settings.SearchHits(found, meaning, gutter, pick)
}

/** The rail's Search: the field, then the matches (or a short hint while it is empty). */
@Composable
private fun SearchScreen(gutter: Dp, pick: (dev.phonerecall.settings.SettingEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Search", gutter)
        SearchField(query, gutter) { query = it }
        if (query.isNotBlank()) SearchHits(query, gutter) { query = ""; pick(it) }
        else Line("Every place by its Greek or plain name, and every setting in ${Place.SETTINGS.title()}.", Type.small, Athena.colors.ink3, Modifier.padding(horizontal = gutter, vertical = Space.m))
    }
}

/** Destinations not built yet: the header, the phase, one sentence. */
@Composable
private fun ComingSoon(d: Dest, gutter: Dp) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(d.place.title(), gutter, emblem = d.place, subtitle = d.place.subtitle())
        Column(Modifier.padding(start = gutter, end = gutter, top = Space.xxl).widthIn(max = 560.dp)) {
            Kicker("Phase ${d.phase}", color = c.accentInk)
            Spacer(Modifier.height(Space.m))
            Line(d.line, Type.headline, c.ink2)
            if (d == Dest.AGENTS) {
                // The roster, so the names are familiar before they move in.
                Spacer(Modifier.height(Space.xl))
                for (w in dev.phonerecall.home.SampleData.workers) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Line(w.name, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                            Line(w.role.replaceFirstChar { it.uppercase() }, Type.small, c.ink3)
                        }
                        dev.phonerecall.ui.StatusWord(if (w.active) "Active" else "Not set up yet", if (w.active) dev.phonerecall.ui.Tone.OK else dev.phonerecall.ui.Tone.QUIET)
                    }
                }
            }
        }
    }
}
