package dev.phonerecall.closet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.data.ClosetItem
import dev.phonerecall.data.ClosetSnapshot
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaIcons
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What Venus's Today, Packing and Insights call back with. */
class VenusCallbacks(
    val wore: (List<String>) -> Unit = {},
    val unwore: (List<String>) -> Unit = {},
    val notToday: (List<String>) -> Unit = {},
    val favorite: (String) -> Unit = {},
    val open: (ClosetItem) -> Unit = {},
)

private fun occasionWord(o: Occasion) = when (o) {
    Occasion.OFFICE -> "Office day"
    Occasion.REMOTE -> "Remote day"
    Occasion.WEEKEND -> "Weekend"
    Occasion.OFF -> "Day off"
    Occasion.DINNER -> "Evening plan"
    Occasion.PARTY -> "Dressed up"
    Occasion.TEMPLE -> "Temple"
    Occasion.FESTIVAL -> "Festival"
}

/** Today's outfit: the flat-lay, the pieces, Wore it and Not today, then two alternates. */
@Composable
fun VenusToday(plan: OutfitPlan?, prefs: VenusPrefs, wide: Boolean, gutter: Dp, cb: VenusCallbacks) {
    val c = Athena.colors
    if (plan == null) {
        Line("No outfit yet: Venus needs tops in the closet.", Type.body, c.ink2, Modifier.padding(horizontal = gutter, vertical = Space.xl))
        return
    }
    val reason = prefs.polished(plan.main.key, plan.date.toEpochDay()) ?: plan.main.reason
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, top = Space.s, bottom = Space.xxl)) {
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
                FlatLay(plan.main, Modifier.weight(1f).widthIn(max = 440.dp), cb)
                Column(Modifier.weight(1f)) {
                    Summary(plan, reason, prefs, cb)
                    Alternates(plan, cb)
                }
            }
        } else {
            FlatLay(plan.main, Modifier.fillMaxWidth(), cb)
            Spacer(Modifier.height(Space.l))
            Summary(plan, reason, prefs, cb)
            Alternates(plan, cb)
        }
    }
}

@Composable
private fun Summary(plan: OutfitPlan, reason: String, prefs: VenusPrefs, cb: VenusCallbacks) {
    val c = Athena.colors
    val o = plan.main
    Kicker(occasionWord(plan.occasion))
    Spacer(Modifier.height(Space.xs))
    Line(o.title.replaceFirstChar { it.uppercase() }, Type.title, c.ink)
    Spacer(Modifier.height(Space.xs))
    Line(reason, Type.body, c.ink2)
    Spacer(Modifier.height(Space.m))
    o.traditional?.let { PieceRow("Wear", it, null, false, null) }
    for ((role, item) in o.pieces) {
        PieceRow(role.label, item.name.replaceFirstChar { it.uppercase() }, item, item.id in prefs.favorites, cb)
    }
    Spacer(Modifier.height(Space.m))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        if (plan.wearing) {
            StatusWord("Wearing", Tone.OK, Modifier.padding(end = Space.s))
            TextAction("Undo", { cb.unwore(o.ids) })
        } else {
            PrimaryAction("Wore it", { cb.wore(o.ids) })
            // Skips the top for today; tomorrow it is back. A festival's traditional pick stays.
            o.top?.let { t -> TextAction("Not today", { cb.notToday(listOf(t.id)) }) }
        }
    }
}

@Composable
private fun PieceRow(role: String, name: String, item: ClosetItem?, fav: Boolean, cb: VenusCallbacks?) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).topRule(c.rule)
            .then(if (item != null && cb != null) Modifier.clickable(role = Role.Button) { cb.open(item) } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Kicker(role, Modifier.width(84.dp))
        Line(name, Type.small, c.ink, Modifier.weight(1f), maxLines = 1)
        // Favorites are set on the item's page (tap the row); here they only show.
        if (fav) StatusWord("Favorite", Tone.ACTIVE)
    }
}

/**
 * The pieces laid out like a flat-lay: two staggered columns on one block, each piece on its own
 * render background so white and black studio shots both sit cleanly.
 */
@Composable
fun FlatLay(o: OutfitPick, modifier: Modifier, cb: VenusCallbacks? = null) {
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.block)
    val gap = Space.s
    Box(modifier.clip(shape).background(c.block(Tint.NEUTRAL)).border(1.dp, c.edge, shape).padding(Space.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                if (o.top != null) Piece(o.top, 0.8f, cb) else if (o.traditional != null) Unpictured(o.traditional, 0.8f)
                o.shoes?.let { Piece(it, 1.25f, cb) }
            }
            Column(Modifier.weight(1f).padding(top = if (o.layer == null && o.accessory == null) Space.xl else 0.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                val small = o.layer ?: o.accessory
                small?.let { Piece(it, 1.25f, cb) }
                o.bottom?.let { Piece(it, 0.8f, cb) }
                if (o.layer != null && o.accessory != null) Piece(o.accessory, 1.6f, cb)
            }
        }
    }
}

@Composable
private fun Piece(item: ClosetItem, aspect: Float, cb: VenusCallbacks?) {
    val px = with(LocalDensity.current) { 220.dp.roundToPx() }
    Picture(
        item, px,
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(Radius.blockSmall))
            .then(if (cb != null) Modifier.clickable(role = Role.Button) { cb.open(item) } else Modifier),
        inset = 6.dp,
    )
}

/** A piece with no picture (the festival kurta): the garment outline and its name, never an invented image. */
@Composable
private fun Unpictured(name: String, aspect: Float) {
    val c = Athena.colors
    Column(
        Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(Radius.blockSmall)).background(c.raised).padding(Space.m),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(AthenaIcons.Garment, contentDescription = null, Modifier.size(56.dp), tint = c.accentInk)
        Spacer(Modifier.height(Space.s))
        Line(name, Type.small, c.ink)
    }
}

@Composable
private fun Alternates(plan: OutfitPlan, cb: VenusCallbacks) {
    val c = Athena.colors
    if (plan.alternates.isEmpty()) return
    Spacer(Modifier.height(Space.xl))
    Kicker("Or")
    for (alt in plan.alternates) {
        Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
            Thumbs(listOfNotNull(alt.top, alt.bottom, alt.shoes), 44.dp)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Line(alt.title.replaceFirstChar { it.uppercase() }, Type.small.copy(fontWeight = Type.action.fontWeight), c.ink, maxLines = 2)
                // Only what differs from the main line: the weather clause, or nothing.
                alt.reason.substringAfter(". ", "").takeIf { it.isNotEmpty() && !plan.main.reason.endsWith(it) }?.let { Line(it, Type.caption, c.ink3, maxLines = 2) }
            }
            if (!plan.wearing) TextAction("Wear this", { cb.wore(alt.ids) })
        }
    }
}

@Composable
private fun Thumbs(items: List<ClosetItem>, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        for (i in items) Picture(i, px, Modifier.size(size).clip(RoundedCornerShape(Radius.forSize(size))), inset = 3.dp)
    }
}

private val TRIP_DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/** Packing lists for trips on the calendar. */
@Composable
fun VenusPacking(packs: List<TripPack>?, gutter: Dp) {
    val c = Athena.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, top = Space.s, bottom = Space.xxl).widthIn(max = 640.dp)) {
        when {
            packs == null -> return@Column
            packs.isEmpty() -> {
                Line("No trips on the calendar in the next three weeks.", Type.body, c.ink2, Modifier.padding(vertical = Space.l))
                Line("A flight, hotel or \"trip to\" entry shows up here with a list from your closet.", Type.small, c.ink3)
                return@Column
            }
        }
        for (p in packs.orEmpty()) {
            val t = p.trip
            Kicker(if (t.days == 1) TRIP_DAY.format(t.start) else "${TRIP_DAY.format(t.start)} to ${TRIP_DAY.format(t.end)}", Modifier.padding(top = Space.l))
            Line(t.title, Type.title, c.ink, Modifier.padding(top = Space.xs))
            Line(if (t.days == 1) "1 day" else "${t.days} days", Type.small, c.ink2, Modifier.padding(bottom = Space.s))
            for (l in p.lines) {
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).topRule(c.rule).padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(l.label, Type.body, c.ink, maxLines = 1)
                        val sub = l.note ?: l.items.joinToString(", ") { it.name }.takeIf { it.isNotEmpty() }
                        sub?.let { Line(it, Type.caption, c.ink3, maxLines = 2) }
                    }
                    if (l.items.isNotEmpty()) { Spacer(Modifier.width(Space.s)); Thumbs(l.items.take(3), 36.dp) }
                    Spacer(Modifier.width(Space.m))
                    Line("${l.count}", Type.title, c.ink2)
                }
            }
        }
    }
}

/** Laundry, rarely worn, favorites: what the wear log says. */
@Composable
fun VenusInsights(closet: ClosetSnapshot, prefs: VenusPrefs, today: LocalDate, gutter: Dp, cb: VenusCallbacks) {
    val c = Athena.colors
    val laundry = Insights.laundry(closet, prefs, today)
    val rare = Insights.rarelyWorn(closet, prefs, today)
    val favs = closet.items.filter { it.id in prefs.favorites && !it.archived }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = gutter, end = gutter, top = Space.s, bottom = Space.xxl).widthIn(max = 640.dp)) {
        Kicker("Laundry", Modifier.padding(top = Space.m))
        Line(
            when (laundry.wornThisWeek.size) { 0 -> "Nothing logged this week."; 1 -> "1 piece worn this week."; else -> "${laundry.wornThisWeek.size} pieces worn this week." },
            Type.title, c.ink, Modifier.padding(top = Space.xs),
        )
        Line(
            "${laundry.cleanTops} tops not worn this week, ${laundry.cleanOfficeTops} of them office-ready." +
                if (laundry.cleanOfficeTops < 3) " Laundry before the next office day." else "",
            Type.small, c.ink2, Modifier.padding(bottom = Space.s),
        )
        if (laundry.wornThisWeek.isNotEmpty()) ThumbGrid(laundry.wornThisWeek, cb)

        Kicker("Rarely worn", Modifier.padding(top = Space.xl))
        if (rare.isEmpty()) {
            Line(
                if (Insights.loggedSince(prefs) == null) "Tap Wore it for a week and this fills in." else "Everything has been out in the last 30 days.",
                Type.small, c.ink2, Modifier.padding(top = Space.xs),
            )
        } else {
            Line("${rare.size} pieces not worn in 30 days. Venus brings them forward on the right days.", Type.small, c.ink2, Modifier.padding(top = Space.xs, bottom = Space.s))
            ItemStrip(rare.take(8), cb)
        }

        Kicker("Favorites", Modifier.padding(top = Space.xl))
        if (favs.isEmpty()) Line("None yet. Add one from Today or an item's page.", Type.small, c.ink2, Modifier.padding(top = Space.xs))
        else ItemStrip(favs, cb)
    }
}

/** Pictures only, six to a row: the laundry pile at a glance. Tap one to open it. */
@Composable
private fun ThumbGrid(items: List<ClosetItem>, cb: VenusCallbacks) {
    val px = with(LocalDensity.current) { 56.dp.roundToPx() }
    Column(Modifier.padding(top = Space.xs), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        for (row in items.chunked(6)) Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (i in row) Picture(
                i, px, Modifier.size(48.dp).clip(RoundedCornerShape(Radius.forSize(48.dp))).clickable(role = Role.Button) { cb.open(i) }, inset = 3.dp,
            )
        }
    }
}

@Composable
private fun ItemStrip(items: List<ClosetItem>, cb: VenusCallbacks) {
    val c = Athena.colors
    for (i in items) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).topRule(c.rule).clickable(role = Role.Button) { cb.open(i) }.padding(vertical = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbs(listOf(i), 40.dp)
            Spacer(Modifier.width(Space.m))
            Line(i.name.replaceFirstChar { it.uppercase() }, Type.small, c.ink, Modifier.weight(1f), maxLines = 1)
        }
    }
}
