package dev.phonerecall.closet

import dev.phonerecall.ui.Workers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.AthenaViewModel
import dev.phonerecall.data.ClosetGroup
import dev.phonerecall.data.ClosetItem
import dev.phonerecall.home.WinterList
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.LocalPhoto
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.title
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.LocalPlainNames

private const val ANY = "Any"
private const val LIST = "LIST"

/** The closet Iris catalogued. Photos stay in the app's storage on this phone; nothing is uploaded. */
@Composable
fun ClosetScreen(vm: AthenaViewModel, wide: Boolean, gutter: Dp) {
    val closet by vm.closet.collectAsStateWithLifecycle()
    val arrangement by vm.arrangement.collectAsStateWithLifecycle()
    val outfit by vm.outfit.collectAsStateWithLifecycle()
    val venus by vm.venus.collectAsStateWithLifecycle()
    val packing by vm.packing.collectAsStateWithLifecycle()
    val c = Athena.colors
    var section by rememberSaveable { mutableStateOf(VenusSection.TODAY.name) }
    var group by rememberSaveable { mutableStateOf(ClosetGroup.TOPS.name) }
    var colour by rememberSaveable { mutableStateOf(ANY) }
    var occasion by rememberSaveable { mutableStateOf(ANY) }
    var temp by rememberSaveable { mutableStateOf(ANY) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val open = openId?.let { id -> closet.items.firstOrNull { it.id == id } }
    if (open != null) {
        BackHandler { openId = null }
        Detail(open, gutter, open.id in venus.favorites, { vm.toggleFavorite(open.id) }) { openId = null }
        return
    }
    val cb = VenusCallbacks(
        wore = { vm.wore(it) }, unwore = { vm.unwore(it) }, notToday = { vm.notToday(it) },
        favorite = { vm.toggleFavorite(it) }, open = { openId = it.id },
    )

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.CLOSET.title(), gutter, emblem = Place.CLOSET, subtitle = Place.CLOSET.subtitle()) {
            if (section == VenusSection.CLOSET.name) TextAction(if (archived) "Hide archived" else "Show archived", { archived = !archived })
        }
        if (!closet.loaded) return@Column
        if (!closet.imported) {
            Line("Closet not imported yet.", Type.body, c.ink2, Modifier.padding(horizontal = gutter, vertical = Space.xl))
            return@Column
        }
        Row(Modifier.padding(horizontal = gutter - Space.s).horizontalScroll(rememberScrollState())) {
            for (v in VenusSection.entries) Word(v.label, section == v.name) {
                section = v.name
                if (v == VenusSection.PACKING) vm.loadPacking()
            }
        }
        when (section) {
            VenusSection.TODAY.name -> { VenusToday(outfit, venus, wide, gutter, cb); return@Column }
            VenusSection.PACKING.name -> { VenusPacking(packing, gutter); return@Column }
            VenusSection.INSIGHTS.name -> { VenusInsights(closet, venus, java.time.LocalDate.now(), gutter, cb); return@Column }
        }
        Row(Modifier.padding(horizontal = gutter - Space.s).horizontalScroll(rememberScrollState())) {
            for (g in ClosetGroup.entries) {
                val n = closet.items.count { it.group == g && (archived || !it.archived) }
                Word("${g.label} $n", group == g.name) { group = g.name; colour = ANY }
            }
            Word("Shopping list", group == LIST) { group = LIST }
        }
        if (group == LIST) {
            val bought = arrangement?.bought.orEmpty()
            ShoppingList(WinterList.items.map { (id, label) -> dev.phonerecall.home.CheckItem(id, label, id in bought) }, gutter) { vm.toggleBought(it) }
            return@Column
        }
        val inGroup = closet.items.filter { it.group.name == group && (archived || !it.archived) }
        val colours = listOf(ANY) + inGroup.groupingBy { it.colour }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12)
        val occasions = listOf(ANY, "office", "home", "outing", "party", "date", "impress", "hosting")
        FilterRow("Colour", colours, colour, gutter) { colour = it }
        FilterRow("Occasion", occasions, occasion, gutter) { occasion = it }
        FilterRow("Temp", listOf(ANY, "warm", "cool", "neutral"), temp, gutter) { temp = it }
        val shown = inGroup.filter {
            (colour == ANY || it.colour == colour) && (occasion == ANY || occasion in it.occasions) && (temp == ANY || it.temp == temp)
        }
        if (shown.isEmpty()) {
            Line("Nothing matches. Try Any on one of the filters.", Type.body, c.ink2, Modifier.padding(horizontal = gutter, vertical = Space.xl))
            return@Column
        }
        val cell = if (wide) 132.dp else 104.dp
        val px = with(LocalDensity.current) { cell.roundToPx() }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cell),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.s, bottom = Space.xxl),
            horizontalArrangement = Arrangement.spacedBy(Space.blockGap),
            verticalArrangement = Arrangement.spacedBy(Space.blockGap),
        ) {
            items(shown, key = { it.id }) { item -> Tile(item, px) { openId = item.id } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Line("Photos stay on this phone.", Type.small, c.ink3, Modifier.padding(top = Space.l))
            }
        }
    }
}

@Composable
private fun Word(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Athena.colors
    val accent = c.accent
    Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Tab, onClick = onClick).padding(horizontal = Space.s), contentAlignment = Alignment.Center) {
        Line(
            text, Type.action, if (on) c.ink else c.ink3,
            Modifier.drawBehind { if (on) { val w = 2.dp.toPx(); drawLine(accent, Offset(0f, size.height + w * 2), Offset(size.width, size.height + w * 2), w) } },
            maxLines = 1,
        )
    }
}

/** LABEL then the options as plain words, one line that scrolls sideways. */
@Composable
private fun FilterRow(label: String, options: List<String>, selected: String, gutter: Dp, onPick: (String) -> Unit) {
    val c = Athena.colors
    Row(Modifier.fillMaxWidth().padding(start = gutter), verticalAlignment = Alignment.CenterVertically) {
        Kicker(label, Modifier.widthIn(min = 72.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (o in options) {
                val on = o == selected
                Box(Modifier.heightIn(min = 40.dp).clickable(role = Role.RadioButton) { onPick(o) }.padding(horizontal = Space.s), contentAlignment = Alignment.Center) {
                    Line(o.replaceFirstChar { it.uppercase() }, Type.small, if (on) c.accentInk else c.ink2, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun Tile(item: ClosetItem, px: Int, onClick: () -> Unit) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.blockSmall)
    Column(
        Modifier.clip(shape).background(c.block(Tint.NEUTRAL)).border(1.dp, c.edge, shape).clickable(role = Role.Button, onClick = onClick),
    ) {
        Picture(item, px, Modifier.fillMaxWidth().aspectRatio(0.8f), inset = 6.dp)
        Column(Modifier.padding(horizontal = Space.s, vertical = Space.s)) {
            Line(item.name, Type.caption, c.ink, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                // No render yet: his own photo stands in, and says so.
                if (item.render == null && item.photos.isNotEmpty()) StatusWord("Photo", Tone.QUIET)
                if (item.render == null && item.photos.isEmpty()) StatusWord("No picture", Tone.QUIET)
                if (item.archived) StatusWord("Archived", Tone.QUIET)
            }
        }
    }
}

/**
 * The item's picture. A render is fitted by its aspect onto the manifest's background colour, so white
 * and black studio shots sit cleanly in the block; a photo is cropped to fill.
 */
@Composable
internal fun Picture(item: ClosetItem, px: Int, modifier: Modifier, inset: Dp = 0.dp, showOwn: Boolean = false, ownIndex: Int = 0) {
    val c = Athena.colors
    val render = item.render?.takeIf { !showOwn }
    val path = render ?: item.photos.getOrNull(ownIndex) ?: item.photos.firstOrNull()
    val bg = if (render != null) item.renderBg?.let { androidx.compose.ui.graphics.Color(it) } ?: c.raised else c.raised
    Box(modifier.background(bg), contentAlignment = Alignment.Center) {
        if (path == null) {
            androidx.compose.material3.Icon(dev.phonerecall.ui.AthenaIcons.Garment, contentDescription = null, Modifier.fillMaxSize(0.4f), tint = c.ink3)
        } else {
            if (render != null) LocalPhoto(path, px, RoundedCornerShape(0.dp), Modifier.fillMaxSize().padding(inset), ContentScale.Fit)
            else LocalPhoto(path, px, RoundedCornerShape(0.dp), Modifier.fillMaxSize(), ContentScale.Crop)
        }
    }
}

/** Venus's sections: today's outfit first, then the closet grid, packing and insights. */
enum class VenusSection(val label: String) { TODAY("Today"), CLOSET("Closet"), PACKING("Packing"), INSIGHTS("Insights") }

@Composable
private fun Detail(item: ClosetItem, gutter: Dp, favorite: Boolean, onFavorite: () -> Unit, back: () -> Unit) {
    val c = Athena.colors
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val side = minOf(screenW - gutter * 2, 520.dp)
    val px = with(LocalDensity.current) { side.roundToPx() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(item.name.replaceFirstChar { it.uppercase() }, gutter, kicker = item.group.label) {
            TextAction(if (favorite) "Favorite" else "Add to favorites", onFavorite, emphasis = favorite)
            TextAction("Back", back)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, bottom = Space.xxl).widthIn(max = 640.dp)) {
            var own by rememberSaveable(item.id) { mutableStateOf(item.render == null) }
            if (item.render != null || item.photos.isNotEmpty()) {
                val shape = RoundedCornerShape(Radius.block)
                if (own && item.photos.size > 1) {
                    val pager = rememberPagerState { item.photos.size }
                    HorizontalPager(pager, Modifier.widthIn(max = side).aspectRatio(0.8f).clip(shape), pageSpacing = Space.s) { p ->
                        Picture(item, px, Modifier.fillMaxSize(), showOwn = true, ownIndex = p)
                    }
                    Kicker("Photo ${pager.currentPage + 1} of ${item.photos.size}", Modifier.padding(top = Space.s))
                } else {
                    Picture(item, px, Modifier.widthIn(max = side).fillMaxWidth().aspectRatio(0.8f).clip(shape), inset = 12.dp, showOwn = own)
                }
                // Switch between the render and his own photos (the source of truth for condition).
                if (item.render != null && item.photos.isNotEmpty()) {
                    Row(Modifier.padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Word("Render", !own) { own = false }
                        Word("Own photo", own) { own = true }
                    }
                }
                Line(
                    when {
                        item.render == null && item.photos.isEmpty() -> "No picture yet. Send ${Workers.IRIS} a photo and she can add it."
                        item.render == null -> "No render yet. This is your own photo."
                        own -> "Your own photo: the real condition and colour."
                        else -> "A studio view of the item, not an exact record. Your own photos show its real condition."
                    },
                    Type.small, c.ink3, Modifier.padding(top = Space.xs, bottom = Space.m),
                )
            }
            FactRow("Colour", item.colour)
            FactRow("Kind", item.kind.replaceFirstChar { it.uppercase() })
            if (item.description.isNotEmpty()) FactRow("What", item.description.replaceFirstChar { it.uppercase() })
            if (item.occasions.isNotEmpty()) FactRow("Occasions", item.occasions.joinToString(", ") { it.replaceFirstChar { ch -> ch.uppercase() } })
            FactRow("Formality", "${item.formality} of 5")
            if (item.temp.isNotEmpty()) FactRow("Temp", item.temp.replaceFirstChar { it.uppercase() })
            if (item.size.isNotEmpty()) FactRow("Size", item.size)
            if (item.brandGuess.isNotEmpty()) {
                // The brand is a visual guess, so it is shown muted and labelled.
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
                    Kicker("Brand", Modifier.widthIn(min = 92.dp).padding(top = 4.dp))
                    Line("${item.brandGuess} (guess)", Type.small, c.ink3)
                }
            }
            if (item.archived) StatusWord("Archived, out of rotation", Tone.QUIET, Modifier.padding(top = Space.s))
            if (item.notes.isNotEmpty()) {
                Spacer(Modifier.height(Space.m))
                Kicker("Notes")
                Spacer(Modifier.height(Space.xs))
                Line(item.notes, Type.body, c.ink2)
            }
        }
    }
}

@Composable
private fun ShoppingList(items: List<dev.phonerecall.home.CheckItem>, gutter: Dp, toggle: (String) -> Unit) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, top = Space.m, bottom = Space.xxl).widthIn(max = 640.dp)) {
        Line("What the closet is missing for the winter. Tap a line once it is bought.", Type.small, c.ink2, Modifier.padding(bottom = Space.m))
        for (i in items) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).topRule(c.rule).clickable(role = Role.Checkbox) { toggle(i.id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Line(i.label, Type.body, if (i.checked) c.ink3 else c.ink, Modifier.weight(1f))
                if (i.checked) StatusWord("Bought", Tone.OK)
            }
        }
    }
}

/** The grid with given items, for the screenshot test. */
@Composable
internal fun ClosetGridPreview(items: List<ClosetItem>, gutter: Dp, dark: Boolean = true) {
    dev.phonerecall.ui.AthenaTheme(dark) {
        val px = with(LocalDensity.current) { 104.dp.roundToPx() }
        Column(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            ScreenHeader(dev.phonerecall.ui.Place.CLOSET.myth, gutter, emblem = dev.phonerecall.ui.Place.CLOSET, subtitle = dev.phonerecall.ui.Place.CLOSET.motto)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = Space.s, bottom = Space.xxl),
                horizontalArrangement = Arrangement.spacedBy(Space.blockGap),
                verticalArrangement = Arrangement.spacedBy(Space.blockGap),
            ) { items(items, key = { it.id }) { item -> Tile(item, px) {} } }
        }
    }
}

/** Venus with a given section and data, for the screenshot test. */
@Composable
internal fun VenusPreview(
    section: VenusSection, closet: dev.phonerecall.data.ClosetSnapshot, plan: OutfitPlan?, prefs: VenusPrefs,
    packs: List<TripPack>?, wide: Boolean, gutter: Dp, dark: Boolean, today: java.time.LocalDate,
) {
    dev.phonerecall.ui.AthenaTheme(dark) {
        Column(Modifier.fillMaxSize().background(Athena.colors.surface)) {
            ScreenHeader(Place.CLOSET.myth, gutter, emblem = Place.CLOSET, subtitle = Place.CLOSET.motto)
            Row(Modifier.padding(horizontal = gutter - Space.s)) { for (v in VenusSection.entries) Word(v.label, section == v) {} }
            when (section) {
                VenusSection.TODAY -> VenusToday(plan, prefs, wide, gutter, VenusCallbacks())
                VenusSection.PACKING -> VenusPacking(packs, gutter)
                VenusSection.INSIGHTS -> VenusInsights(closet, prefs, today, gutter, VenusCallbacks())
                VenusSection.CLOSET -> Unit
            }
        }
    }
}
