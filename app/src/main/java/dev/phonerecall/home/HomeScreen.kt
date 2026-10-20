package dev.phonerecall.home

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.AthenaViewModel
import dev.phonerecall.Dest
import dev.phonerecall.HomeMode
import dev.phonerecall.HomeState
import dev.phonerecall.data.AvailabilityModel
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.CalendarSnapshot
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.data.HomePrefs
import dev.phonerecall.data.Learned
import dev.phonerecall.data.TaskSnapshot
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Hinge
import dev.phonerecall.ui.ItemBounds
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Motion
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ReorderState
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.ToastClearance
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.Undo
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.LocalPlainNames
import dev.phonerecall.calendar.RuleDialog
import dev.phonerecall.ui.animatedShape
import dev.phonerecall.ui.UndoToast
import dev.phonerecall.ui.liftAndDrag
import dev.phonerecall.ui.swipeAway
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.delay
import java.time.ZonedDateTime

/** Home: the adaptive board, Edit Home, or Add card. */
@Composable
fun HomeScreen(vm: AthenaViewModel, columns: Int, gutter: Dp, showOwl: Boolean, hinge: Hinge?, navigate: (Dest) -> Unit) {
    val state by vm.home.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val calendarAsk = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshCalendar() }
    val context = androidx.compose.ui.platform.LocalContext.current

    BackHandler(mode != HomeMode.BOARD) {
        if (mode == HomeMode.ADD) vm.closeAdd() else vm.setMode(HomeMode.BOARD)
    }

    when (mode) {
        HomeMode.BOARD -> {
            var menuFor by rememberSaveable { mutableStateOf<String?>(null) }
            var notice by remember { mutableStateOf<Pair<String, String>?>(null) }
            var undo by remember { mutableStateOf<Undo?>(null) }
            var ruleFor by remember { mutableStateOf<EventRef?>(null) }
            var askOther by remember { mutableStateOf<Int?>(null) }
            var odoFor by remember { mutableStateOf<String?>(null) }
            val calendars by vm.calendars.collectAsStateWithLifecycle()
            LaunchedEffect(notice) { if (notice != null) { delay(2600); notice = null } }
            val cb = remember(vm) {
                CardCallbacks(
                    onAction = { card, a ->
                        when (a.role) {
                            ActionRole.ALLOW_CALENDAR -> calendarAsk.launch(Manifest.permission.READ_CALENDAR)
                            ActionRole.OPEN_CLOSET -> { vm.action(card, a); navigate(Dest.CLOSET) }
                            ActionRole.OPEN_TASKS -> { vm.action(card, a); navigate(Dest.TASKS) }
                            ActionRole.OPEN_REVIEW -> { vm.action(card, a); vm.openReview(); navigate(Dest.TASKS) }
                            ActionRole.OPEN_OLYMPIA -> { vm.action(card, a); navigate(Dest.OLYMPIA) }
                            ActionRole.OPEN_WEATHER -> { vm.action(card, a); navigate(Dest.WEATHER) }
                            ActionRole.OPEN_ODYSSEY -> { vm.action(card, a); navigate(Dest.ODYSSEY) }
                            ActionRole.OPEN_HESTIA -> { vm.action(card, a); navigate(Dest.DEVICES) }
                            ActionRole.ALARM_LINK -> {
                                vm.action(card, a)
                                a.payload?.let { p ->
                                    runCatching {
                                        context.startActivity(android.content.Intent(if (p.startsWith("tel:")) android.content.Intent.ACTION_DIAL else android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                    }
                                }
                            }
                            ActionRole.ALARM_OTHER -> { vm.action(card, a); askOther = a.payload?.toIntOrNull() }
                            ActionRole.OPEN_ALARMS -> {
                                vm.action(card, a)
                                runCatching { dev.phonerecall.brain.AlarmWatch.get(context).openClock() }
                            }
                            ActionRole.FUEL_ODOMETER -> { vm.action(card, a); odoFor = a.payload }
                            ActionRole.CAR_SETUP -> { vm.action(card, a); dev.phonerecall.odyssey.CarNav.open = true; navigate(Dest.ODYSSEY) }
                            ActionRole.MOMENT_SNAP -> { vm.action(card, a); runCatching { context.startActivity(dev.phonerecall.moments.SnapActivity.intent(context)) } }
                            ActionRole.OPEN_ARGO -> { vm.action(card, a); navigate(Dest.TRIPS) }
                            ActionRole.ARGO -> {
                                vm.action(card, a)
                                val p = a.payload.orEmpty()
                                when {
                                    p == "vault" -> navigate(Dest.VAULT)
                                    p.startsWith("maps:") -> runCatching { context.startActivity(dev.phonerecall.argo.Argo.mapsIntent(p.removePrefix("maps:"))) }
                                }
                            }
                            ActionRole.MAPS -> {
                                vm.action(card, a)
                                a.payload?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { (lat, lng) ->
                                    runCatching { context.startActivity(dev.phonerecall.odyssey.Odyssey.mapsIntent(lat, lng)) }
                                }
                            }
                            else -> if (!vm.action(card, a)) notice = card.id to "Preview only. Actions are wired up in phase 2."
                        }
                    },
                    onTaskDone = { card, id -> vm.completeTask(id, card); undo = Undo("Done.") { vm.uncompleteTask(id) } },
                    onTaskSnooze = { card, id -> vm.snoozeTask(card, id); undo = Undo("Snoozed for 3 hours.") { vm.unsnoozeTask(id) } },
                    onCheck = { _, id -> vm.toggleBought(id); undo = Undo("Marked as bought.") { vm.toggleBought(id) } },
                    onPin = { vm.togglePin(it.id); menuFor = null },
                    onHide = { c -> vm.hide(c.id, c.learnKey); menuFor = null; undo = Undo("Hidden.") { vm.show(c.id) } },
                    onLess = { vm.lessLikeThis(it); menuFor = null },
                    onHideLike = { ruleFor = it.content.event; menuFor = null },
                    onCloseMenu = { menuFor = null },
                )
            }
            val haptics = LocalHapticFeedback.current
            var taps by remember { mutableIntStateOf(0) }
            var lastTap by remember { mutableStateOf(0L) }
            LaunchedEffect(dev.phonerecall.ui.Eggs.thunder) { if (dev.phonerecall.ui.Eggs.thunder) { delay(10_000); dev.phonerecall.ui.Eggs.thunder = false } }
            // Easter egg: between 3 and 4 AM the subtitle notices.
            val owlHour = remember { ZonedDateTime.now().hour == 3 }
            LaunchedEffect(owlHour) { if (owlHour) dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.OWLS) }
            Box(Modifier.fillMaxSize()) {
                OlympusBackdrop()
                Column(Modifier.fillMaxSize()) {
                    // The date is known before anything loads, so the header is complete on the first frame.
                    val title = state.board.title.ifEmpty { remember { Fmt.date(ZonedDateTime.now()) } }
                    val plain = LocalPlainNames.current
                    ScreenHeader(
                        if (plain) title else Place.HOME.title(), gutter, emblem = Place.HOME,
                        kicker = if (plain) null else title, subtitle = if (owlHour) "The owls are judging you." else Place.HOME.subtitle(),
                        onTitleTap = {
                            // Easter egg: seven quick taps on the title, a thunder roll.
                            val now = System.currentTimeMillis()
                            taps = if (now - lastTap < 600) taps + 1 else 1
                            lastTap = now
                            if (taps >= 7) {
                                taps = 0
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                dev.phonerecall.ui.Eggs.thunder = true
                                dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.THUNDER)
                            }
                        },
                    ) {
                        TextAction("Edit", { vm.setMode(HomeMode.EDIT) })
                    }
                    Board(
                        state, columns, gutter, hinge, menuFor, notice, cb, refreshing,
                        onRefresh = { vm.refresh() },
                        onTap = { vm.tapped(it) },
                        onMenu = { menuFor = it.id },
                        onMoved = { id, slot ->
                            val before = vm.moveCard(id, slot)
                            undo = Undo("Moved.") { vm.restorePositions(before) }
                        },
                        onSwipeHide = { c -> vm.dismiss(c); undo = Undo("Done.") { vm.undismiss(c) } },
                        onLocked = { c -> notice = c.id to (c.neverHideReason ?: "This one stays on Home.") },
                        onAdd = { vm.openAdd() },
                        header = { BrainHeader(vm, navigate) },
                    )
                }
                UndoToast(undo, gutter) { undo = null }
            }
            askOther?.let { m -> AlarmAskDialog(m, onSave = { vm.answerAlarm(m, it) }, onDismiss = { askOther = null }) }
            ruleFor?.let { r ->
                RuleDialog(r.title, calendars[r.calendarId], null, onSave = { vm.saveRule(it) }, onDismiss = { ruleFor = null })
            }
            odoFor?.let { id ->
                dev.phonerecall.odyssey.OdometerDialog(id, onConfirm = { miles, photo -> vm.confirmFillUp(id, miles, photo); odoFor = null }, onDismiss = { odoFor = null })
            }
        }
        HomeMode.EDIT -> EditHome(state, gutter, vm)
        HomeMode.ADD -> AddCard(state, gutter, vm)
    }
}

/**
 * The board. Cover screen: one column by score. Inner screen: two columns, the top block across both.
 * Long-press lifts a block to drag it anywhere; swipe sideways hides it; pull down refreshes.
 * In book posture the column gap sits on the hinge so no block is split by the fold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Board(
    state: HomeState,
    columns: Int,
    gutter: Dp,
    hinge: Hinge?,
    menuFor: String?,
    notice: Pair<String, String>?,
    cb: CardCallbacks,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onTap: (ScoredCard) -> Unit,
    onMenu: (ScoredCard) -> Unit,
    onMoved: (String, Int) -> Unit,
    onSwipeHide: (ScoredCard) -> Unit,
    onLocked: (ScoredCard) -> Unit,
    onAdd: () -> Unit,
    header: @Composable () -> Unit = {},
) {
    if (!state.ready) return // the header is already up; blocks arrive in a few ms, no shimmer
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val grid = rememberLazyStaggeredGridState()
    var boardX by remember { mutableFloatStateOf(0f) }
    var boardW by remember { mutableIntStateOf(0) }
    val reorder = remember(grid) {
        ReorderState(
            scope,
            bounds = { grid.layoutInfo.visibleItemsInfo.map { ItemBounds(it.key, it.offset, it.size) } },
            viewportHeight = { grid.layoutInfo.viewportSize.height },
            scrollBy = { grid.scrollBy(it) },
            edgePx = with(density) { 72.dp.toPx() },
        )
    }
    // Once the board reflects a drop, let go of the live drag order.
    LaunchedEffect(state.board.cards) { reorder.settle() }
    // New cards arriving above the first visible one would otherwise push the top out of view
    // (the grid keeps its anchor). If he has not scrolled away, keep the board at the top.
    val topIds = state.board.cards.take(3).map { it.id }
    LaunchedEffect(topIds) {
        if (reorder.dragging == null && grid.firstVisibleItemIndex <= 2) grid.scrollToItem(0)
    }

    val byId = state.board.cards.associateBy { it.id }
    val shown: List<ScoredCard> = reorder.order?.mapNotNull { byId[it as String] } ?: state.board.cards

    var padStart = gutter
    var padEnd = gutter
    if (hinge != null && columns == 2 && boardW > 0) {
        val shift = with(density) { (2f * (hinge.center - boardX) - boardW).toDp() }
        if (shift > 0.dp) padStart += shift else padEnd -= shift
    }
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        state = pull,
        modifier = Modifier.fillMaxSize(),
        indicator = { PullWord(pull, refreshing) },
    ) {
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                boardX = it.positionInWindow().x
                boardW = it.size.width
            },
            state = grid,
            contentPadding = PaddingValues(start = padStart, end = padEnd, top = Space.xs, bottom = ToastClearance),
            verticalItemSpacing = Space.blockGap,
            horizontalArrangement = Arrangement.spacedBy(Space.blockGap),
            userScrollEnabled = reorder.dragging == null,
        ) {
            item(key = "brain-header", span = StaggeredGridItemSpan.FullLine) { header() }
            items(
                shown,
                key = { it.id },
                contentType = { it.size },
                // Two lanes on both screens. Cover: tiles pair up, everything else spans. Inner: the hero spans, the rest pair up.
                span = {
                    val l = CardShapes.layout(it)
                    if (l == BlockLayout.HERO || (columns == 1 && l != BlockLayout.TILE)) StaggeredGridItemSpan.FullLine else StaggeredGridItemSpan.SingleLane
                },
            ) { card ->
                // Gesture handlers live across recompositions; read the latest card and order through these.
                val cardNow by rememberUpdatedState(card)
                val shownNow by rememberUpdatedState(shown)
                var liftIndex by remember { mutableIntStateOf(-1) }
                val lifted = reorder.dragging == card.id
                val scale by animateFloatAsState(if (lifted) 1.03f else 1f, Motion.settle(), label = "lift")
                val swipe = remember(card.id) { Animatable(0f) }
                var width by remember { mutableIntStateOf(1) }
                val form = CardShapes.form(card, shown.indexOf(card))
                val shape = animatedShape(form.spec, lifted)
                CardZone(
                    card,
                    form,
                    shape,
                    lifted,
                    menuOpen = menuFor == card.id,
                    notice = notice?.takeIf { it.first == card.id }?.second,
                    cb = cb,
                    modifier = Modifier
                        // State changes only: blocks slide to new places; nothing fades in. The lifted block follows the finger.
                        .then(if (lifted) Modifier.zIndex(1f) else Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.place, fadeOutSpec = null))
                        .onSizeChanged { width = it.width }
                        .graphicsLayer {
                            val t = reorder.translation(card.id)
                            translationX = t.x + swipe.value
                            translationY = t.y
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - (kotlin.math.abs(swipe.value) / width).coerceIn(0f, 1f) * 0.6f
                        }
                        .semantics {
                            onClick(label = "Open") { onTap(card); true }
                            onLongClick(label = "Card options") { onMenu(card); true }
                        }
                        .swipeAway(
                            card.id, swipe, { width.toFloat() }, locked = card.neverHide, scope = scope,
                            onLockedRelease = { onLocked(cardNow) },
                            onAway = { onSwipeHide(cardNow) },
                        )
                        .liftAndDrag(
                            card.id, haptics,
                            onTap = { onTap(cardNow) },
                            onLift = {
                                liftIndex = shownNow.indexOfFirst { it.id == card.id }
                                reorder.start(card.id, shownNow.map { it.id })
                            },
                            onDrag = { reorder.dragBy(it) },
                            onDrop = {
                                val moved = reorder.end()
                                if (moved != null && moved.second != liftIndex && moved.second >= 0) onMoved(card.id, moved.second)
                                else reorder.settle()
                            },
                            onMenu = { onMenu(cardNow) },
                        ),
                )
            }
            item(key = "add-card", span = StaggeredGridItemSpan.FullLine) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onAdd).padding(vertical = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AthenaIcons.Plus, contentDescription = null, Modifier.size(20.dp), tint = Athena.colors.ink2)
                    Spacer(Modifier.width(Space.s))
                    Line("Add card", Type.action, Athena.colors.ink2)
                }
            }
        }
    }
}

/**
 * Above the blocks: one quiet "Ask Athena" line that opens Delphi, then every pending proposal as a
 * Fates card. Pending cards sit outside the grid, so they can't be swiped away or hidden.
 */
@Composable
private fun BrainHeader(vm: AthenaViewModel, navigate: (Dest) -> Unit) {
    val c = Athena.colors
    val proposals by vm.brain.proposals.collectAsStateWithLifecycle()
    val calendars by vm.brain.calendars.collectAsStateWithLifecycle()
    val pending = proposals.filter { it.status == dev.phonerecall.brain.ProposalStatus.PENDING }
    val cb = dev.phonerecall.brain.rememberFatesCallbacks(vm.brain)
    var capturing by rememberSaveable { mutableStateOf(false) }
    var kept by remember { mutableStateOf(false) }
    LaunchedEffect(kept) { if (kept) { delay(2200); kept = false } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.blockGap)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { navigate(Dest.DELPHI) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            dev.phonerecall.ui.AthenaAvatar(24.dp)
            Spacer(Modifier.width(Space.m))
            Line("Ask Athena", Type.body, c.ink3, Modifier.weight(1f))
            if (pending.size > 1) StatusWord("${pending.size} waiting", Tone.ACTIVE)
            // Quick capture into Parnassus, without leaving Olympus.
            Box(
                Modifier.size(44.dp).clickable(role = Role.Button) { capturing = !capturing }
                    .semantics { contentDescription = if (capturing) "Close quick capture" else "Quick capture" },
                contentAlignment = Alignment.Center,
            ) { Icon(AthenaIcons.Plus, contentDescription = null, Modifier.size(22.dp), tint = if (capturing) c.accentInk else c.ink2) }
        }
        if (capturing) dev.phonerecall.brain.QuickCapture({ vm.brain.capture(it) }) { capturing = false; kept = true }
        if (kept) Line("Kept in ${Place.RAMBLER.title()}.", Type.small, c.ink2)
        // Every pending proposal shows, newest first; none can be hidden while it waits.
        for (p in pending) dev.phonerecall.brain.FatesCard(p, cb, calendars = calendars)
        dev.phonerecall.music.NowPlayingCard(vm.brain)
        // Iaso's week: drawn here, outside the board, so it never reaches the brain, the Now Bar or a widget.
        dev.phonerecall.iaso.IasoWeekCard { navigate(Dest.HEALTH) }
    }
}

/**
 * Olympus only: the peak above the clouds behind the header, the top ~220 dp cropped to the summit,
 * fading into the page through a scrim so the title stays legible in both themes.
 */
@Composable
private fun OlympusBackdrop() {
    val c = Athena.colors
    Box(Modifier.fillMaxWidth().height(220.dp)) {
        dev.phonerecall.ui.ArtImage(
            dev.phonerecall.R.drawable.bg_olympus_dusk, 420.dp, Modifier.matchParentSize(),
            shape = androidx.compose.ui.graphics.RectangleShape, alignment = androidx.compose.ui.BiasAlignment(0f, -0.8f), sized = false,
        )
        Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to c.surface.copy(alpha = if (c.isDark) 0.30f else 0.55f),
                    0.6f to c.surface.copy(alpha = if (c.isDark) 0.55f else 0.75f),
                    1f to c.surface,
                ),
            ),
        )
    }
}

/** The pull indicator: one state word that follows the pull. No spinner, no bounce. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.PullWord(state: PullToRefreshState, refreshing: Boolean) {
    val f = state.distanceFraction
    if (!refreshing && f <= 0f) return
    val word = when {
        refreshing -> "Refreshing"
        f >= 1f -> "Release to refresh"
        else -> "Pull to refresh"
    }
    val y = with(LocalDensity.current) { (if (refreshing) 1f else f.coerceAtMost(1f)) * 28.dp.toPx() }
    Box(
        Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = y; alpha = if (refreshing) 1f else f.coerceIn(0f, 1f) }
            .zIndex(2f),
    ) { StatusWord(word, if (refreshing || f >= 1f) Tone.ACTIVE else Tone.QUIET) }
}

/** The Add card picker: every kind, one line each. Kinds already on Home say so. */
@Composable
private fun AddCard(state: HomeState, gutter: Dp, vm: AthenaViewModel) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Add card", gutter) { PrimaryAction("Done", { vm.closeAdd() }) }
        LazyColumn(contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxl)) {
            items(state.board.catalogue, key = { it.id }) { row ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).topRule(c.rule),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = Space.m)) {
                        Line(row.title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        Line(row.about, Type.small, c.ink3)
                    }
                    if (row.onHome) {
                        StatusWord("On Home", Tone.QUIET)
                        Spacer(Modifier.width(Space.xs))
                        TextAction("Remove", { vm.hide(row.id, null) })
                    } else {
                        TextAction("Add", { vm.show(row.id) }, emphasis = true)
                    }
                }
            }
        }
    }
}

// ---- previews (Android Studio) ----

private fun previewState(sample: Boolean, sports: dev.phonerecall.olympia.SportsCard? = null, weather: dev.phonerecall.aeolia.WeatherState? = null, morning: dev.phonerecall.brief.MorningBrief? = null, alarm: dev.phonerecall.brain.AlarmCards.Card? = null, moment: dev.phonerecall.moments.MomentCard? = null, trip: dev.phonerecall.argo.TripHome? = null, at: ZonedDateTime? = null): HomeState {
    val now = at ?: ZonedDateTime.now().withHour(8).withMinute(20)
    val ms = { t: ZonedDateTime -> t.toInstant().toEpochMilli() }
    fun ev(id: Long, title: String, h: Int, m: Int, mins: Long, cat: EventCategory, recurring: Boolean = true) = CalItem(
        id, 1, title, ms(now.withHour(h).withMinute(m)), ms(now.withHour(h).withMinute(m).plusMinutes(mins)), false, cat, "Work", null, null, recurring,
    )
    val cal = CalendarSnapshot(
        allowed = true, loaded = true,
        events = listOf(
            ev(1, "Team sync", 9, 0, 30, EventCategory.WORK),
            ev(2, "1:1 with Taylor", 14, 0, 30, EventCategory.WORK),
            ev(3, "Mexico City Grand Prix - Race", 15, 0, 120, EventCategory.SPORT, recurring = false),
        ),
    )
    val board = CardEngine.build(
        BoardInputs(
            now, cal, AvailabilityModel.range(now.toLocalDate(), 9, cal.events, now.zone), sample, HomePrefs(), Learned(), TaskSnapshot(loaded = true), ClosetSnapshot(loaded = true),
            if (sample) SampleData.generated(now) else emptyList(), true, sports = sports, weather = weather, morning = morning, alarm = alarm, moment = moment, trip = trip,
        ),
    )
    return HomeState(ready = true, board = board)
}

/** Olympus with only what the senses made: notification cards and the proposals under the header. Screenshot test only. */
@Composable
internal fun PreviewSenseCards(cards: List<GeneratedCard>, proposals: List<dev.phonerecall.brain.Proposal>, gutter: Dp) {
    AthenaTheme(true) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            OlympusBackdrop()
            Column(Modifier.fillMaxSize()) {
                val now = ZonedDateTime.now().withHour(21).withMinute(48)
                val cal = CalendarSnapshot(allowed = true, loaded = true, events = emptyList())
                val board = CardEngine.build(
                    BoardInputs(
                        now, cal, AvailabilityModel.range(now.toLocalDate(), 9, cal.events, now.zone), false, HomePrefs(), Learned(),
                        TaskSnapshot(loaded = true), ClosetSnapshot(loaded = true), cards, false,
                    ),
                )
                ScreenHeader(Place.HOME.title(), gutter, emblem = Place.HOME, kicker = board.title, subtitle = Place.HOME.subtitle()) { TextAction("Edit", {}) }
                Board(
                    HomeState(ready = true, board = board), 1, gutter, null, null, null, CardCallbacks(), false, {}, {}, {}, { _, _ -> }, {}, {}, {},
                    header = {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.blockGap)) {
                            for (p in proposals) dev.phonerecall.brain.FatesCard(p, dev.phonerecall.brain.FatesCallbacks())
                        }
                    },
                )
            }
        }
    }
}

/** Olympus with only Odyssey's cards (no samples), at a fixed time. Screenshot test only. */
@Composable
internal fun PreviewOdysseyBoard(odyssey: dev.phonerecall.odyssey.OdysseyHome, now: ZonedDateTime, columns: Int, gutter: Dp, dark: Boolean) {
    AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            OlympusBackdrop()
            Column(Modifier.fillMaxSize()) {
                val cal = CalendarSnapshot(allowed = true, loaded = true, events = emptyList())
                val board = CardEngine.build(
                    BoardInputs(
                        now, cal, AvailabilityModel.range(now.toLocalDate(), 9, cal.events, now.zone), false, HomePrefs(), Learned(),
                        TaskSnapshot(loaded = true), ClosetSnapshot(loaded = true), emptyList(), false, odyssey = odyssey,
                    ),
                )
                ScreenHeader(board.title, gutter, emblem = Place.HOME) { TextAction("Edit", {}) }
                Board(HomeState(ready = true, board = board.copy(cards = board.cards.filter { it.kind in setOf(CardKind.SNOW_BAND, CardKind.WFH, CardKind.FUEL, CardKind.LEASE) })), columns, gutter, null, null, null, CardCallbacks(), false, {}, {}, {}, { _, _ -> }, {}, {}, {})
            }
        }
    }
}

/** The board with sample data, for previews and the screenshot test. */
@Composable
internal fun PreviewBoard(columns: Int, gutter: Dp, sample: Boolean, dark: Boolean = true, sports: dev.phonerecall.olympia.SportsCard? = null, weather: dev.phonerecall.aeolia.WeatherState? = null, morning: dev.phonerecall.brief.MorningBrief? = null, alarm: dev.phonerecall.brain.AlarmCards.Card? = null, moment: dev.phonerecall.moments.MomentCard? = null, trip: dev.phonerecall.argo.TripHome? = null, at: ZonedDateTime? = null) {
    AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) {
          OlympusBackdrop()
          Column(Modifier.fillMaxSize()) {
            val s = previewState(sample, sports, weather, morning, alarm, moment, trip, at)
            ScreenHeader(s.board.title, gutter, emblem = Place.HOME) { TextAction("Edit", {}) }
            Board(s, columns, gutter, null, null, null, CardCallbacks(), false, {}, {}, {}, { _, _ -> }, {}, {}, {})
          }
        }
    }
}

@Preview(name = "Cover, sample", widthDp = 384, heightDp = 3200)
@Composable private fun CoverPreview() = PreviewBoard(1, Space.gutterNarrow, sample = true)

@Preview(name = "Inner, sample", widthDp = 760, heightDp = 1200)
@Composable private fun InnerPreview() = PreviewBoard(2, Space.gutterWide, sample = true)

@Preview(name = "Cover, light", widthDp = 384, heightDp = 3200)
@Composable private fun CoverLightPreview() = PreviewBoard(1, Space.gutterNarrow, sample = true, dark = false)

