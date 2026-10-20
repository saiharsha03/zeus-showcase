package dev.phonerecall.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a lazy item is right now, in viewport coordinates. */
data class ItemBounds(val key: Any, val offset: IntOffset, val size: IntSize)

/**
 * Drag to reorder inside a lazy list or grid. The lifted item follows the finger; when its centre
 * crosses another item, the two swap places in [order] and the lazy layout animates the rest out of
 * the way. Near the top or bottom edge it scrolls.
 */
@Stable
class ReorderState(
    private val scope: CoroutineScope,
    private val bounds: () -> List<ItemBounds>,
    private val viewportHeight: () -> Int,
    private val scrollBy: suspend (Float) -> Float,
    private val edgePx: Float,
) {
    var dragging by mutableStateOf<Any?>(null)
        private set
    /** The live order while dragging (and until the caller's data catches up), else null. */
    var order by mutableStateOf<List<Any>?>(null)
        private set
    private val working = mutableStateListOf<Any>()
    private var start = IntOffset.Zero
    private var total by mutableStateOf(Offset.Zero)
    private var lastSwap = 0L
    private var auto: Job? = null

    fun start(key: Any, current: List<Any>) {
        val b = bounds().firstOrNull { it.key == key } ?: return
        working.clear(); working.addAll(current)
        order = working
        dragging = key
        start = b.offset
        total = Offset.Zero
        auto?.cancel()
        auto = scope.launch {
            while (dragging != null) {
                val c = centre()
                val h = viewportHeight()
                val step = when {
                    c == null -> 0f
                    c.y < edgePx -> -(edgePx - c.y) / 4f
                    c.y > h - edgePx -> (c.y - (h - edgePx)) / 4f
                    else -> 0f
                }
                if (step != 0f) { scrollBy(step); swapIfOver() }
                delay(16)
            }
        }
    }

    fun dragBy(d: Offset) {
        if (dragging == null) return
        total += d
        swapIfOver()
    }

    private fun centre(): Offset? {
        val b = bounds().firstOrNull { it.key == dragging } ?: return null
        return Offset(start.x + total.x + b.size.width / 2f, start.y + total.y + b.size.height / 2f)
    }

    private fun swapIfOver() {
        val key = dragging ?: return
        val now = System.currentTimeMillis()
        if (now - lastSwap < 90) return
        val c = centre() ?: return
        val target = bounds().firstOrNull { b ->
            b.key != key && b.key in working &&
                c.x >= b.offset.x && c.x <= b.offset.x + b.size.width && c.y >= b.offset.y && c.y <= b.offset.y + b.size.height
        } ?: return
        val from = working.indexOf(key)
        val to = working.indexOf(target.key)
        if (from < 0 || to < 0) return
        working.add(to, working.removeAt(from))
        lastSwap = now
    }

    /** How far to draw the lifted item from where the layout put it. */
    fun translation(key: Any): Offset {
        if (key != dragging) return Offset.Zero
        val b = bounds().firstOrNull { it.key == key } ?: return Offset.Zero
        return Offset(start.x + total.x - b.offset.x, start.y + total.y - b.offset.y)
    }

    /** Ends the drag. Returns the key and its new index, or null if nothing moved. */
    fun end(): Pair<Any, Int>? {
        val key = dragging ?: return null
        dragging = null
        auto?.cancel()
        total = Offset.Zero
        return key to working.indexOf(key)
    }

    /** Call when the caller's data reflects the drop. */
    fun settle() { if (dragging == null) order = null }
}

/**
 * One gesture for a block: tap; long-press (300 ms, haptic) lifts it; moving after the lift drags;
 * releasing without moving opens the menu. Anything else (a scroll, a swipe) cancels quietly.
 */
fun Modifier.liftAndDrag(
    key: Any,
    haptics: HapticFeedback,
    onTap: () -> Unit,
    onLift: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onMenu: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val slop = viewConfiguration.touchSlop
        // 0 = released (tap), 1 = cancelled, null = held: a long press.
        val early: Int? = withTimeoutOrNull(300L) {
            var r = 1
            while (true) {
                val e = awaitPointerEvent()
                val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                if (ch.isConsumed) break
                if (!ch.pressed) { r = 0; break }
                if ((ch.position - down.position).getDistance() > slop) break
            }
            r
        }
        when (early) {
            0 -> onTap()
            1 -> Unit
            else -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onLift()
                var moved = false
                var sum = Offset.Zero
                while (true) {
                    val e = awaitPointerEvent()
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val d = ch.positionChange()
                    sum += d
                    if (!moved && sum.getDistance() > slop) moved = true
                    if (moved) onDrag(d)
                    ch.consume()
                }
                if (moved) onDrop() else { onDrop(); onMenu() }
            }
        }
    }
}

/**
 * Swipe sideways. Past 35% of the width it flies off and calls [onAway]; otherwise, or when [locked]
 * (never-hide cards), it springs back, critically damped. Locked items move with resistance.
 */
fun Modifier.swipeAway(
    key: Any,
    offset: Animatable<Float, *>,
    widthPx: () -> Float,
    locked: Boolean,
    scope: CoroutineScope,
    onLockedRelease: () -> Unit = {},
    onAway: () -> Unit,
): Modifier = pointerInput(key, locked) {
    detectHorizontalDragGestures(
        onDragEnd = {
            val w = widthPx()
            val v = offset.value
            scope.launch {
                if (!locked && kotlin.math.abs(v) > w * 0.35f) {
                    offset.animateTo(if (v > 0) w * 1.1f else -w * 1.1f, Motion.settle())
                    onAway()
                } else {
                    offset.animateTo(0f, Motion.settle())
                    if (locked) onLockedRelease()
                }
            }
        },
        onDragCancel = { scope.launch { offset.animateTo(0f, Motion.settle()) } },
        onHorizontalDrag = { change, dx ->
            change.consume()
            scope.launch { offset.snapTo(offset.value + dx * if (locked) 0.3f else 1f) }
        },
    )
}

/** What an undo toast says and does. `id` makes each one distinct so the timer restarts. */
data class Undo(val message: String, val id: Long = System.nanoTime(), val undo: () -> Unit)

/** "Moved." with Undo. Raised surface, 1 dp edge, no shadow glow. Gone after four seconds. */
@Composable
fun BoxScope.UndoToast(undo: Undo?, gutter: androidx.compose.ui.unit.Dp, onGone: () -> Unit) {
    LaunchedEffect(undo?.id) { if (undo != null) { delay(4000); onGone() } }
    if (undo == null) return
    val c = Athena.colors
    val shape = RoundedCornerShape(Radius.forSize(56.dp))
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(start = gutter, end = gutter, bottom = Space.l)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.edge, shape)
            .padding(start = Space.l, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Line(undo.message, Type.body, c.ink, Modifier.weight(1f).padding(vertical = Space.m), maxLines = 2)
        Spacer(Modifier.width(Space.s))
        TextAction("Undo", { undo.undo(); onGone() }, emphasis = true)
    }
}

/** Bottom padding for lists so the last item clears the toast. */
val ToastClearance = 88.dp
