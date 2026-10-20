package dev.phonerecall.calendar

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.AthenaViewModel
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.brain.TwoClocks
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.CalendarInfo
import dev.phonerecall.data.EventCategory
import dev.phonerecall.data.EventKinds
import dev.phonerecall.data.Relevance
import dev.phonerecall.data.RelevanceConfig
import dev.phonerecall.ui.Athena
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.ShapeSpec
import dev.phonerecall.ui.SpecShape
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.tintFor
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.LocalPlainNames
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

enum class CalView(val label: String) { AGENDA("Agenda"), DAY("Day"), WEEK("Week"), MONTH("Month") }

private const val CENTER = 10_000
private val TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val LONG_DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)
private val MONTH_DAY = DateTimeFormatter.ofPattern("MMMM d", Locale.US)
private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
private val ZONE: ZoneId get() = ZoneId.systemDefault()

private fun weekStart(d: LocalDate): LocalDate = d.with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1)
private fun at(ms: Long): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ZONE)

/** An event plus whether it passed the relevance layer. */
private data class Shown(val item: CalItem, val relevant: Boolean)

/** What the screen needs to judge and draw items. */
private class Ctx(
    val days: Map<LocalDate, List<CalItem>>,
    val calendars: Map<Long, CalendarInfo>,
    val config: RelevanceConfig,
    val birthdays: List<dev.phonerecall.data.ContactBirthday>,
    val everything: Boolean,
) {
    /** The day's items: calendar events plus contact birthdays; in Relevant mode, only relevant ones. */
    fun on(d: LocalDate): List<Shown> {
        val fromCal = days[d].orEmpty()
        val names = fromCal.filter { it.category == EventCategory.BIRTHDAY }.map { it.title.lowercase() }.toSet()
        val contact = BirthdaySource.asItems(birthdays, d, 1, ZONE).filter { it.title.lowercase() !in names }
        return (EventKinds.dedupe(fromCal, calendars, ZONE) + contact).map { Shown(it, it.calendarId == -1L || Relevance.relevant(it.title, calendars[it.calendarId], config)) }
            .filter { everything || it.relevant }
            .sortedWith(compareBy({ !it.item.allDay }, { it.item.begin }))
    }
}

/** A curated calendar, drawn in Athena's own blocks. Read-only. */
@Composable
fun CalendarScreen(vm: AthenaViewModel, wide: Boolean, gutter: Dp) {
    val home by vm.home.collectAsStateWithLifecycle()
    val cal by vm.calendarDays.collectAsStateWithLifecycle()
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val config by vm.relevance.collectAsStateWithLifecycle()
    val birthdays by vm.birthdays.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val view = picked?.let { CalView.valueOf(it) } ?: if (wide) CalView.WEEK else CalView.AGENDA
    var everything by rememberSaveable { mutableStateOf(false) }
    var focusDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val focus = LocalDate.ofEpochDay(focusDay)
    var detail by remember { mutableStateOf<CalItem?>(null) }
    var ruleFor by remember { mutableStateOf<CalItem?>(null) }
    var todayTick by remember { mutableIntStateOf(0) }
    val ctx = Ctx(cal.days, calendars, config, birthdays, everything)

    detail?.let { item ->
        BackHandler { detail = null }
        EventDetail(item, calendars[item.calendarId], gutter, onRule = { ruleFor = item }) { detail = null }
        ruleFor?.let { r -> RuleDialog(r.title, calendars[r.calendarId], null, onSave = { vm.saveRule(it) }, onDismiss = { ruleFor = null }) }
        return
    }

    val c = Athena.colors
    val open: (CalItem) -> Unit = { detail = it }
    val rule: (CalItem) -> Unit = { if (it.calendarId != -1L) ruleFor = it }
    Column(Modifier.fillMaxSize()) {
        val title = when (view) {
            CalView.AGENDA -> "Calendar"
            CalView.DAY -> if (focus == LocalDate.now()) "Today" else focus.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
            CalView.WEEK, CalView.MONTH -> MONTH_TITLE.format(if (view == CalView.WEEK) weekStart(focus) else focus)
        }
        val plain = LocalPlainNames.current
        val period = if (view == CalView.DAY) "$title, ${MONTH_DAY.format(focus)}" else title
        val today = LocalDate.now()
        // Easter eggs: up, up, down, down on the header shows today in Attic numerals; a laurel on his birthday.
        val attic = dev.phonerecall.ui.Eggs.atticDay == today.toEpochDay()
        val birthday = remember(cal.days, today) { dev.phonerecall.ui.Eggs.birthdayToday(cal.days[today].orEmpty(), today, ZONE) }
        LaunchedEffect(birthday) { if (birthday) dev.phonerecall.ui.Eggs.find(dev.phonerecall.ui.Eggs.Egg.LAUREL) }
        var swipes by remember { mutableStateOf(emptyList<Boolean>()) }
        val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx() }
        Box(Modifier.pointerInput(Unit) {
            var dy = 0f
            detectVerticalDragGestures(
                onDragStart = { dy = 0f },
                onDragEnd = {
                    if (kotlin.math.abs(dy) >= swipePx) {
                        val (h, hit) = dev.phonerecall.ui.Eggs.swipe(swipes, up = dy < 0)
                        swipes = h
                        if (hit) dev.phonerecall.ui.Eggs.attic(LocalDate.now())
                    }
                },
            ) { _, d -> dy += d }
        }) {
            ScreenHeader(
                if (plain) title else Place.CALENDAR.title(), gutter,
                emblem = Place.CALENDAR,
                kicker = if (attic) dev.phonerecall.ui.Eggs.atticDate(today)
                    else if (plain) (if (view == CalView.DAY) MONTH_DAY.format(focus) else null) else period.takeIf { view != CalView.AGENDA },
                subtitle = Place.CALENDAR.subtitle(),
            ) {
                if (birthday) androidx.compose.material3.Icon(AthenaIcons.Laurel, contentDescription = "Laurel", Modifier.size(22.dp), tint = c.accentInk)
                TextAction("Today", { focusDay = LocalDate.now().toEpochDay(); todayTick++ })
            }
        }
        // The views, then the filter, as plain words; the chosen one is ink with a saffron underline.
        Row(Modifier.padding(horizontal = gutter - Space.s).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            for (v in CalView.entries) ChoiceWord(v.label, v == view) { picked = v.name }
            Spacer(Modifier.width(Space.l))
            ChoiceWord("Relevant", !everything, quiet = true) { everything = false }
            ChoiceWord("Everything", everything, quiet = true) { everything = true }
        }
        Spacer(Modifier.height(Space.s))

        if (!home.ready) return@Column
        if (!home.board.calendarAllowed) {
            Column(Modifier.padding(horizontal = gutter, vertical = Space.xl)) {
                Line("Allow calendar access to see your days here. It is read live and never copied.", Type.body, c.ink2, Modifier.widthIn(max = 560.dp))
                Spacer(Modifier.height(Space.l))
                PrimaryAction("Allow calendar", { ask.launch(Manifest.permission.READ_CALENDAR) })
            }
            return@Column
        }
        key(view, todayTick) {
            when (view) {
                CalView.AGENDA -> Agenda(vm, ctx, focus, gutter, open, rule)
                CalView.DAY -> Paged(CENTER + ChronoUnit.DAYS.between(LocalDate.now(), focus).toInt(), { focusDay = LocalDate.now().plusDays((it - CENTER).toLong()).toEpochDay() }) { page ->
                    val d = LocalDate.now().plusDays((page - CENTER).toLong())
                    LaunchedEffect(d) { vm.ensureCalendar(d.minusDays(1), d.plusDays(1)) }
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                        DayFlow(d, ctx.on(d), showHeader = false, open = open, rule = rule)
                        Spacer(Modifier.height(Space.xxl))
                    }
                }
                CalView.WEEK -> {
                    val thisWeek = weekStart(LocalDate.now())
                    var selected by rememberSaveable { mutableLongStateOf(focus.toEpochDay()) }
                    Paged(CENTER + ChronoUnit.WEEKS.between(thisWeek, weekStart(focus)).toInt(), {
                        val start = thisWeek.plusWeeks((it - CENTER).toLong())
                        focusDay = start.toEpochDay()
                        if (LocalDate.ofEpochDay(selected) !in (0..6).map { d -> start.plusDays(d.toLong()) }) selected = if (LocalDate.now() in (0..6).map { d -> start.plusDays(d.toLong()) }) LocalDate.now().toEpochDay() else start.toEpochDay()
                    }) { page ->
                        val start = thisWeek.plusWeeks((page - CENTER).toLong())
                        LaunchedEffect(start) { vm.ensureCalendar(start, start.plusDays(6)) }
                        val dates = (0..6).map { start.plusDays(it.toLong()) }
                        if (wide) WeekColumns(dates, ctx, gutter, open, rule)
                        else WeekStrip(dates, ctx, gutter, LocalDate.ofEpochDay(selected), { selected = it.toEpochDay() }, open, rule)
                    }
                }
                CalView.MONTH -> {
                    val thisMonth = YearMonth.now()
                    Paged(CENTER + ChronoUnit.MONTHS.between(thisMonth, YearMonth.from(focus)).toInt(), { focusDay = thisMonth.plusMonths((it - CENTER).toLong()).atDay(1).toEpochDay() }) { page ->
                        val ym = thisMonth.plusMonths((page - CENTER).toLong())
                        LaunchedEffect(ym) { vm.ensureCalendar(ym.atDay(1).minusDays(7), ym.atEndOfMonth().plusDays(14)) }
                        Month(ym, ctx, gutter) { day -> focusDay = day.toEpochDay(); picked = CalView.DAY.name }
                    }
                }
            }
        }
    }
    ruleFor?.let { r -> RuleDialog(r.title, calendars[r.calendarId], null, onSave = { vm.saveRule(it) }, onDismiss = { ruleFor = null }) }
}

@Composable
private fun ChoiceWord(label: String, on: Boolean, quiet: Boolean = false, onClick: () -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = Space.s), contentAlignment = Alignment.Center) {
        Line(
            label, if (quiet) Type.small else Type.action, if (on) c.ink else c.ink3,
            Modifier.drawBehind {
                if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) }
            },
        )
    }
}

/** A horizontal pager over days, weeks or months. Swipe moves one page; the settled page sets the focus. */
@Composable
private fun Paged(initial: Int, onSettled: (Int) -> Unit, page: @Composable (Int) -> Unit) {
    val state = rememberPagerState(initialPage = initial) { CENTER * 2 }
    LaunchedEffect(state) { snapshotFlow { state.settledPage }.collect { onSettled(it) } }
    HorizontalPager(state, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { page(it) }
}

private fun iconFor(cat: EventCategory): ImageVector? = when (cat) {
    EventCategory.BIRTHDAY -> AthenaIcons.Cake
    EventCategory.TRAVEL -> AthenaIcons.Plane
    EventCategory.OOO -> AthenaIcons.Off
    else -> null
}

/** Important blocks (one-off, sport, travel, birthdays) get the big-corner shape; routine ones a calm round one. */
private fun shapeFor(e: CalItem): SpecShape {
    val important = !e.recurring || e.category in setOf(EventCategory.SPORT, EventCategory.TRAVEL, EventCategory.BIRTHDAY)
    if (!important) return SpecShape(ShapeSpec.uniform(14f))
    val big = Math.floorMod(e.eventId.hashCode(), 4)
    return SpecShape(ShapeSpec(List(4) { if (it == big) 30f else 14f }, List(4) { if (it == big) ShapeSpec.SMOOTH else ShapeSpec.ROUND }))
}

// ---- One day, as a calm flow of blocks ----

@Composable
private fun DayHeader(d: LocalDate) {
    val c = Athena.colors
    val today = LocalDate.now()
    val big = when (d) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
    }
    Row(Modifier.fillMaxWidth().padding(top = Space.xl, bottom = Space.s), verticalAlignment = Alignment.Bottom) {
        Line(big, Type.headline.copy(fontWeight = FontWeight.Bold), if (d == today) c.accentInk else c.ink, maxLines = 1)
        Spacer(Modifier.width(Space.m))
        Line(MONTH_DAY.format(d), Type.body, c.ink2, Modifier.padding(bottom = 3.dp), maxLines = 1)
    }
}

/** A band across the day for out of office (Outlook status or an OOO title). */
@Composable
private fun OooBand(e: CalItem, compact: Boolean = false) {
    val c = Athena.colors
    val shape = RoundedCornerShape(if (compact) Radius.blockSmall else Radius.block)
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (compact) 36.dp else 52.dp).clip(shape).background(c.block(Tint.OOO, strong = true)).border(1.dp, c.edge, shape)
            .padding(horizontal = if (compact) Space.s else Space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AthenaIcons.Off, contentDescription = null, Modifier.size(if (compact) 16.dp else 20.dp), tint = c.ink)
        Spacer(Modifier.width(Space.s))
        Line(if (compact) "Off" else "Out of office", if (compact) Type.caption else Type.action, c.ink, maxLines = 1)
        if (!compact && !e.title.equals("Out of office", true)) {
            Spacer(Modifier.width(Space.s))
            Line(e.title, Type.small, c.ink2, Modifier.weight(1f), maxLines = 1)
        }
    }
}

@Composable
private fun FreeGap(minutes: Long) {
    val c = Athena.colors
    val h = minutes / 60
    val m = minutes % 60
    val text = "Free " + if (h == 0L) "$m min" else if (m < 15) "$h h" else "$h h ${m / 15 * 15} min"
    Row(Modifier.fillMaxWidth().padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(Space.l))
        Kicker(text, color = c.ink3)
    }
}

@Composable
private fun NowLine(now: ZonedDateTime) {
    val c = Athena.colors
    val accent = c.accent
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Kicker("Now ${TIME.format(now)}", color = c.accentInk)
        Spacer(Modifier.width(Space.s))
        Box(Modifier.weight(1f).height(2.dp).background(accent))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventBlock(s: Shown, open: (CalItem) -> Unit, rule: (CalItem) -> Unit, compact: Boolean = false) {
    val c = Athena.colors
    val e = s.item
    val clocks = remember { TwoClocks.now() }
    val haptics = LocalHapticFeedback.current
    val shape = if (compact) SpecShape(ShapeSpec.uniform(10f)) else shapeFor(e)
    val minutes = ((e.end - e.begin) / 60_000).coerceAtLeast(0)
    // Height follows duration, with room to breathe: half a dp a minute, up to three hours.
    val height = if (e.allDay || compact) (if (compact) 44.dp else 56.dp) else 68.dp + (minutes.coerceAtMost(180) * 0.5f).dp
    val now = System.currentTimeMillis()
    val live = !e.allDay && e.begin <= now && e.end > now
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .alpha(if (s.relevant) 1f else 0.45f)
            .clip(shape)
            .background(c.block(tintFor(e.category), strong = live))
            .border(1.dp, c.edge, shape)
            .combinedClickable(
                role = Role.Button,
                onClick = { open(e) },
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); rule(e) },
                onLongClickLabel = "Hide events like this",
            )
            .padding(horizontal = if (compact) Space.s else 18.dp, vertical = if (compact) 6.dp else 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val time = when {
                e.allDay -> "All day"
                compact -> TIME.format(at(e.begin))
                else -> "${TIME.format(at(e.begin))} to ${TIME.format(at(e.end))}"
            }
            Line(time, if (compact) Type.caption else Type.small.copy(fontWeight = FontWeight.Medium), c.ink2, Modifier.weight(1f), maxLines = 1)
            iconFor(e.category)?.let { Icon(it, contentDescription = e.category.label, Modifier.size(if (compact) 14.dp else 18.dp), tint = c.ink2) }
            if (live && !compact) { Spacer(Modifier.width(Space.s)); StatusWord("On now", Tone.ACTIVE) }
            if (!s.relevant && !compact) { Spacer(Modifier.width(Space.s)); StatusWord("Hidden", Tone.QUIET) }
        }
        Line(e.title, if (compact) Type.caption.copy(fontWeight = FontWeight.Medium) else Type.title, c.ink, maxLines = if (compact) 2 else 3)
        if (!compact) e.place?.let { Line(it, Type.small, c.ink2, maxLines = 1) }
        if (!compact) (clocks.dayStart(e) ?: clocks.bothFor(e))?.let { Line(it, Type.small, c.ink3, maxLines = 1) }
    }
}

/** The day as blocks with free time between them and a saffron now line. */
@Composable
private fun DayFlow(d: LocalDate, items: List<Shown>, showHeader: Boolean, open: (CalItem) -> Unit, rule: (CalItem) -> Unit) {
    val c = Athena.colors
    val today = LocalDate.now()
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L - System.currentTimeMillis() % 60_000L); now = ZonedDateTime.now() } }
    if (showHeader) DayHeader(d)
    val ooo = items.firstOrNull { it.item.category == EventCategory.OOO }
    if (ooo != null) { OooBand(ooo.item); Spacer(Modifier.height(Space.s)) }
    val allDay = items.filter { it.item.allDay && it.item.category != EventCategory.OOO }
    val timed = items.filter { !it.item.allDay && it.item.category != EventCategory.OOO }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        for (s in allDay) EventBlock(s, open, rule)
    }
    if (allDay.isNotEmpty() && timed.isNotEmpty()) Spacer(Modifier.height(Space.s))
    if (timed.isEmpty() && allDay.isEmpty() && ooo == null) {
        Line(if (d == today) "Nothing on today. Free all day." else "Free all day.", Type.body, c.ink2, Modifier.padding(vertical = Space.m))
    }
    val nowMs = now.toInstant().toEpochMilli()
    var cursor: Long? = if (d == today) nowMs else null
    var nowDrawn = d != today
    for (s in timed) {
        val e = s.item
        if (!nowDrawn && e.begin > nowMs) { NowLine(now); nowDrawn = true }
        cursor?.let { from -> val gap = (e.begin - from) / 60_000; if (gap >= 45) FreeGap(gap) }
        EventBlock(s, open, rule)
        Spacer(Modifier.height(Space.s))
        cursor = maxOf(cursor ?: e.end, e.end)
    }
    if (!nowDrawn && timed.isNotEmpty()) NowLine(now)
}

// ---- Agenda ----

@Composable
private fun Agenda(vm: AthenaViewModel, ctx: Ctx, from: LocalDate, gutter: Dp, open: (CalItem) -> Unit, rule: (CalItem) -> Unit) {
    var span by rememberSaveable { mutableIntStateOf(30) }
    val start = minOf(from, LocalDate.now())
    LaunchedEffect(start, span) { vm.ensureCalendar(start, start.plusDays(span.toLong())) }
    val list = rememberLazyListState()
    val today = LocalDate.now()
    // Today always; other days only when something is on.
    val dates = (0 until span).map { start.plusDays(it.toLong()) }.filter { it == today || ctx.on(it).isNotEmpty() }
    LaunchedEffect(list, span) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            // A month more near the end, up to a year ahead.
            if (span < 365 && list.layoutInfo.totalItemsCount > 0 && last >= list.layoutInfo.totalItemsCount - 2) span += 30
        }
    }
    LazyColumn(state = list, contentPadding = PaddingValues(start = gutter, end = gutter, bottom = Space.xxxl)) {
        items(dates.size, key = { dates[it].toEpochDay() }) { i ->
            val d = dates[i]
            Column { DayFlow(d, ctx.on(d), showHeader = true, open = open, rule = rule) }
        }
    }
}

// ---- Week ----

/** Inner screen: seven columns of compact blocks. */
@Composable
private fun WeekColumns(dates: List<LocalDate>, ctx: Ctx, gutter: Dp, open: (CalItem) -> Unit, rule: (CalItem) -> Unit) {
    val c = Athena.colors
    val today = LocalDate.now()
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for (d in dates) {
            val items = ctx.on(d)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Kicker(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US), color = if (d == today) c.accentInk else c.ink3)
                Line("${d.dayOfMonth}", Type.headline.copy(fontWeight = if (d == today) FontWeight.Bold else FontWeight.Medium), if (d == today) c.accentInk else c.ink, maxLines = 1)
                items.firstOrNull { it.item.category == EventCategory.OOO }?.let { OooBand(it.item, compact = true) }
                for (s in items.filter { it.item.category != EventCategory.OOO }) EventBlock(s, open, rule, compact = true)
                if (items.isEmpty()) Line("Free", Type.caption, c.ink3)
            }
        }
    }
}

/** Cover screen: a strip of seven days, then the chosen day's blocks. */
@Composable
private fun WeekStrip(dates: List<LocalDate>, ctx: Ctx, gutter: Dp, selected: LocalDate, select: (LocalDate) -> Unit, open: (CalItem) -> Unit, rule: (CalItem) -> Unit) {
    val c = Athena.colors
    val today = LocalDate.now()
    val accent = c.accent
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = gutter - Space.xs)) {
            for (d in dates) {
                val on = d == selected
                val busy = ctx.on(d).isNotEmpty()
                Column(
                    Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Tab) { select(d) }.padding(vertical = Space.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Kicker(d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US), color = if (d == today) c.accentInk else c.ink3)
                    Line(
                        "${d.dayOfMonth}", Type.title.copy(fontWeight = if (on || d == today) FontWeight.Bold else FontWeight.Normal),
                        if (d == today) c.accentInk else if (on) c.ink else c.ink2,
                        Modifier.drawBehind { if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) } },
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (busy) Icon(AthenaIcons.Mark, contentDescription = null, Modifier.size(12.dp), tint = c.ink3)
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
            DayFlow(selected, ctx.on(selected), showHeader = true, open = open, rule = rule)
            Spacer(Modifier.height(Space.xxl))
        }
    }
}

// ---- Month ----

/** A calm grid: the date and at most two glyphs a day (off, birthday, travel, something on). */
@Composable
private fun Month(ym: YearMonth, ctx: Ctx, gutter: Dp, openDay: (LocalDate) -> Unit) {
    val c = Athena.colors
    val first = weekStart(ym.atDay(1))
    val today = LocalDate.now()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
        Row(Modifier.fillMaxWidth().padding(bottom = Space.s)) {
            for (i in 0..6) Kicker(first.plusDays(i.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US), Modifier.weight(1f))
        }
        for (w in 0 until 6) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0..6) {
                    val d = first.plusDays((w * 7 + i).toLong())
                    val inMonth = YearMonth.from(d) == ym
                    val items = ctx.on(d).filter { it.relevant }.map { it.item }
                    val glyphs = buildList {
                        if (items.any { it.category == EventCategory.OOO }) add(AthenaIcons.Off)
                        if (items.any { it.category == EventCategory.BIRTHDAY }) add(AthenaIcons.Cake)
                        if (items.any { it.category == EventCategory.TRAVEL }) add(AthenaIcons.Plane)
                        if (items.any { it.category !in setOf(EventCategory.OOO, EventCategory.BIRTHDAY, EventCategory.TRAVEL) }) add(AthenaIcons.Mark)
                    }.take(2)
                    val isToday = d == today
                    val shape = RoundedCornerShape(Radius.blockSmall)
                    Column(
                        Modifier.weight(1f).aspectRatio(0.82f).clip(shape)
                            .then(if (isToday) Modifier.background(c.block(Tint.CALENDAR, strong = true)) else Modifier)
                            .clickable(role = Role.Button) { openDay(d) }.padding(top = Space.s),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Line(
                            "${d.dayOfMonth}", Type.title.copy(fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal),
                            when { isToday -> c.accentInk; inMonth -> c.ink; else -> c.ink3 }, maxLines = 1,
                        )
                        Spacer(Modifier.height(Space.xs))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (g in glyphs) Icon(g, contentDescription = null, Modifier.size(14.dp), tint = if (inMonth) c.ink2 else c.ink3)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(Space.l))
    }
}

// ---- Detail ----

@Composable
private fun EventDetail(item: CalItem, cal: CalendarInfo?, gutter: Dp, onRule: () -> Unit, back: () -> Unit) {
    val c = Athena.colors
    val ctx = LocalContext.current
    val b = at(item.begin)
    val e = at(item.end)
    val whenText = if (item.allDay) "${LONG_DATE.format(b)}, all day"
    else if (b.toLocalDate() == e.toLocalDate()) "${LONG_DATE.format(b)}, ${TIME.format(b)} to ${TIME.format(e)}"
    else "${LONG_DATE.format(b)} ${TIME.format(b)} to ${LONG_DATE.format(e)} ${TIME.format(e)}"
    val notes = item.notes?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("[ \\t]+"), " ")?.trim()?.take(1500)
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(item.title, gutter, kicker = item.category.label) { TextAction("Back", back) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = Space.xxl).widthIn(max = 640.dp)) {
            FactRow("When", whenText)
            if (item.calendarName.isNotEmpty()) FactRow("Calendar", item.calendarName + (cal?.let { ", ${it.kind.label.lowercase()}" } ?: ""))
            item.place?.let { FactRow("Where", it) }
            TwoClocks.now().let { k -> (k.bothFor(item) ?: k.dayStart(item))?.let { FactRow("Both clocks", it) } }
            if (!notes.isNullOrEmpty()) {
                Spacer(Modifier.height(Space.m))
                Kicker("Notes")
                Spacer(Modifier.height(Space.xs))
                Line(notes, Type.body, c.ink2, maxLines = 30)
            }
            Spacer(Modifier.height(Space.xl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.calendarId != -1L) PrimaryAction("Open in Calendar", {
                    val intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, item.eventId))
                        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, item.begin)
                        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, item.end)
                    try { ctx.startActivity(intent) } catch (_: ActivityNotFoundException) {
                        failed = true
                        scope.launch { delay(3000); failed = false }
                    }
                })
                Spacer(Modifier.width(Space.s))
                if (item.calendarId != -1L) TextAction("Hide events like this", onRule)
            }
            if (failed) Line("No calendar app could open it.", Type.small, c.ink3, Modifier.padding(top = Space.s))
        }
    }
}

