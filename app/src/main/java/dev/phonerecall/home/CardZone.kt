package dev.phonerecall.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.LocalPhoto
import dev.phonerecall.ui.Motion
import dev.phonerecall.ui.Radius
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.TileShapes
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.title

/** What a card can ask the screen to do. Tap, lift, drag and swipe live in the grid (HomeScreen). */
class CardCallbacks(
    val onAction: (ScoredCard, CardAction) -> Unit = { _, _ -> },
    val onTaskDone: (ScoredCard, Long) -> Unit = { _, _ -> },
    val onTaskSnooze: (ScoredCard, Long) -> Unit = { _, _ -> },
    val onCheck: (ScoredCard, String) -> Unit = { _, _ -> },
    val onPin: (ScoredCard) -> Unit = {},
    val onHide: (ScoredCard) -> Unit = {},
    val onLess: (ScoredCard) -> Unit = {},
    val onHideLike: (ScoredCard) -> Unit = {},
    val onCloseMenu: () -> Unit = {},
)

/**
 * One block on its own tinted surface, in one of four forms (hero blob, leaf, capsule, tile). A card
 * with a number gets a round badge set into a notch in its top edge. Text keeps a per-shape safe inset.
 */
@Composable
fun CardZone(
    card: ScoredCard,
    form: BlockForm,
    shape: Shape,
    lifted: Boolean,
    menuOpen: Boolean,
    notice: String?,
    cb: CardCallbacks,
    modifier: Modifier = Modifier,
) {
    val c = Athena.colors
    val k = card.content
    val hero = form.layout == BlockLayout.HERO
    val badgeR = form.badge / 2
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = badgeR)
                .heightIn(min = when (form.layout) { BlockLayout.TILE -> 124.dp; BlockLayout.CAPSULE -> 72.dp; else -> 104.dp })
                .shadow(if (lifted) 10.dp else 0.dp, shape, clip = false)
                .clip(shape)
                .background(c.block(card.tint, strong = hero))
                .border(1.dp, c.edge, shape)
                .padding(start = form.padX, end = form.padX, top = form.padTop, bottom = form.padBottom)
                .animateContentSize(Motion.settle()),
        ) {
            // Label row: what this is, then status words. It stops short of the notch.
            val notchClear = if (form.badge > 0.dp) form.notchInset + form.notch - form.padX + 4.dp else 0.dp
            Row(Modifier.padding(end = notchClear), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                // Athena's own cards carry her coin; the rest stay text-only.
                if (card.kind == CardKind.GENERATED && card.kicker == CardSource.ATHENA.label) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        dev.phonerecall.ui.AthenaAvatar(20.dp)
                        Spacer(Modifier.width(Space.s))
                        Kicker(card.kicker)
                    }
                } else {
                    // A kicker that is a place's mythic name (Eos) reads as its plain name with "Plain names" on.
                    val plain = dev.phonerecall.ui.LocalPlainNames.current
                    Kicker(if (card.kind == CardKind.MOMENT) dev.phonerecall.ui.Place.MOMENTS.title() else if (plain) dev.phonerecall.ui.Place.entries.firstOrNull { it.myth == card.kicker }?.plain ?: card.kicker else card.kicker, Modifier.weight(1f))
                }
                if (k.status != null) StatusWord(k.status, k.tone)
                if (card.pinned) StatusWord("Pinned", Tone.ACTIVE)
                if (k.sample) StatusWord("Sample", Tone.QUIET)
            }
            Spacer(Modifier.height(Space.s))
            when (form.layout) {
                BlockLayout.CAPSULE -> CapsuleBody(card, cb)
                BlockLayout.TILE -> TileBody(k)
                else -> FullBody(card, cb)
            }
            if (notice != null) {
                Spacer(Modifier.height(Space.s))
                Line(notice, Type.small, c.ink2)
            }
            if (menuOpen) Menu(card, cb)
        }
        if (form.badge > 0.dp && k.stat != null) {
            Badge(k.stat, card, form.badge, Modifier.align(Alignment.TopEnd).padding(end = form.notchInset - badgeR))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Menu(card: ScoredCard, cb: CardCallbacks) {
    val c = Athena.colors
    val k = card.content
    Spacer(Modifier.height(Space.s))
    FlowRow(Modifier.offset(x = -Space.s), verticalArrangement = Arrangement.Center) {
        TextAction(if (card.pinned) "Unpin" else "Pin", { cb.onPin(card) }, emphasis = true)
        if (!card.neverHide) TextAction("Hide", { cb.onHide(card) })
        TextAction("Less like this", { cb.onLess(card) })
        if (k.event != null && k.event.calendarId != -1L) TextAction("Hide events like this", { cb.onHideLike(card) })
        TextAction("Close", cb.onCloseMenu)
    }
    if (card.neverHideReason != null) Line(card.neverHideReason, Type.small, c.ink2)
}

/** The number badge: a circle, cookie, burst or clover, sitting in the notch. */
@Composable
private fun Badge(s: Stat, card: ScoredCard, side: Dp, modifier: Modifier) {
    val c = Athena.colors
    Column(
        modifier.size(side).clip(TileShapes.of(s.form)).background(c.tile(card.tint)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Line(s.value, if (side > 68.dp) Type.headline else Type.title, c.ink, maxLines = 1)
        if (s.label.isNotEmpty()) Line(s.label, Type.caption, c.ink2, maxLines = 1)
    }
}

/** Capsule: one strong line, one quiet line, and at most one action at the end. */
@Composable
private fun CapsuleBody(card: ScoredCard, cb: CardCallbacks) {
    val c = Athena.colors
    val k = card.content
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Line(k.title, Type.title, c.ink, maxLines = 2)
            val sub = k.facts.firstOrNull()?.let { "${it.label}: ${it.value}" } ?: k.body
            if (sub != null) Line(sub, Type.small, c.ink2, maxLines = 1)
        }
        k.actions.firstOrNull()?.let { a ->
            Spacer(Modifier.width(Space.s))
            TextAction(a.label, { cb.onAction(card, a) }, emphasis = true)
        }
    }
}

/** Tile: small, pairs with its neighbour. A big value if there is one, then a few words. */
@Composable
private fun TileBody(k: CardContent) {
    val c = Athena.colors
    if (k.lead != null) Line(k.lead, Type.headline, c.ink, maxLines = 1)
    Line(k.title, if (k.placeholder) Type.small else Type.body, if (k.placeholder) c.ink2 else c.ink, maxLines = 4)
}

@Composable
private fun FullBody(card: ScoredCard, cb: CardCallbacks) {
    val c = Athena.colors
    val k = card.content
    val hero = card.size == CardSize.HERO
    if (k.lead != null) {
        Line(k.lead, if (hero) Type.display else Type.headline, c.ink, maxLines = 1)
        Spacer(Modifier.height(if (hero) Space.xs else Space.xxs))
    }
    Line(k.title, if (hero) Type.headline else Type.title, c.ink)
    if (k.body != null) {
        Spacer(Modifier.height(Space.xs))
        Line(k.body, Type.body, c.ink2)
    }
    if (k.photos.isNotEmpty()) {
        Spacer(Modifier.height(Space.m))
        val side = if (hero) 84.dp else 68.dp
        val px = with(LocalDensity.current) { side.roundToPx() }
        val shape = RoundedCornerShape(Radius.forSize(side))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (p in k.photos.take(4)) {
                Box(Modifier.size(side).clip(shape).background(p.bg?.let { androidx.compose.ui.graphics.Color(it) } ?: c.raised)) {
                    LocalPhoto(
                        p.path, px, shape, Modifier.fillMaxSize().padding(if (p.fit) 4.dp else 0.dp),
                        if (p.fit) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                    )
                }
            }
        }
    }
    val facts = if (card.size == CardSize.NORMAL) k.facts.take(4) else k.facts
    if (facts.isNotEmpty()) {
        Spacer(Modifier.height(Space.s))
        for (f in facts) FactRow(f.label, f.value, f.status, f.tone)
    }
    if (k.tasks.isNotEmpty()) {
        Spacer(Modifier.height(Space.xs))
        for (t in k.tasks) TaskRow(t, onDone = { cb.onTaskDone(card, t.id) }, onSnooze = { cb.onTaskSnooze(card, t.id) })
    }
    if (k.checks.isNotEmpty()) {
        Spacer(Modifier.height(Space.s))
        val lines = if (card.size == CardSize.NORMAL) k.checks.take(3) else k.checks
        for (item in lines) CheckRow(item) { cb.onCheck(card, item.id) }
        if (lines.size < k.checks.size) Line("${k.checks.size - lines.size} more", Type.small, c.ink3, Modifier.padding(top = Space.xs))
    }
    if (k.actions.isNotEmpty()) {
        Spacer(Modifier.height(Space.s))
        Row(Modifier.offset(x = -Space.s), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            k.actions.forEachIndexed { i, a -> TextAction(a.label, { cb.onAction(card, a) }, emphasis = i == 0) }
        }
    }
}

@Composable
private fun TaskRow(t: TaskItem, onDone: () -> Unit, onSnooze: () -> Unit) {
    val c = Athena.colors
    Column(Modifier.fillMaxWidth().padding(top = Space.s)) {
        Line(t.title, Type.body, c.ink)
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                t.due == "Overdue" -> StatusWord(t.due, Tone.ALERT)
                t.dueToday -> StatusWord(t.due, Tone.ACTIVE)
                else -> Kicker(t.due)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.offset(x = Space.s)) {
                TextAction("Done", onDone, emphasis = true)
                TextAction("Snooze", onSnooze)
            }
        }
    }
}

/** A compact list line with a drawn checkbox. The whole line toggles. */
@Composable
private fun CheckRow(item: CheckItem, onToggle: () -> Unit) {
    val c = Athena.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable(role = Role.Checkbox, onClick = onToggle).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val box = RoundedCornerShape(5.dp)
        Box(
            Modifier.size(18.dp).clip(box)
                .then(if (item.checked) Modifier.background(c.accent) else Modifier.border(1.5.dp, c.ink2, box)),
            contentAlignment = Alignment.Center,
        ) {
            if (item.checked) androidx.compose.material3.Icon(dev.phonerecall.ui.AthenaIcons.Check, contentDescription = null, Modifier.size(14.dp), tint = c.onAccent)
        }
        Spacer(Modifier.width(Space.m))
        Line(item.label, Type.body, if (item.checked) c.ink3 else c.ink, Modifier.weight(1f), maxLines = 1)
    }
}
