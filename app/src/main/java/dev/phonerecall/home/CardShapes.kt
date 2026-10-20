package dev.phonerecall.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.ShapeSpec
import dev.phonerecall.ui.Tint

/** How a block is laid out on the board. */
enum class BlockLayout {
    /** The top block: an organic blob with a scalloped bottom, across the full width. */
    HERO,
    /** A content block: a leaf (one big corner, the opposite one tight), alternating per card. */
    FULL,
    /** A short single-purpose block: a capsule with fully rounded ends. */
    CAPSULE,
    /** A small block that pairs up with the next one: a squircle tile. */
    TILE,
}

/**
 * A block's form: its layout, its shape and the safe padding that keeps text clear of its curves.
 * `badge` is the diameter of a round number badge set into a notch in the top edge (0 = none).
 */
data class BlockForm(
    val layout: BlockLayout,
    val spec: ShapeSpec,
    val padX: Dp,
    val padTop: Dp,
    val padBottom: Dp,
    val badge: Dp = 0.dp,
    val notchInset: Dp = 0.dp,
) {
    val notch: Dp get() = if (badge > 0.dp) badge / 2 + 6.dp else 0.dp
}

/**
 * Shapes by kind, stable per card and position, so the board feels designed rather than random.
 * Every form is the same parametric family, so blocks morph when they change size or place.
 */
object CardShapes {
    private val capsuleKinds = setOf(CardKind.COMMUTE, CardKind.SECURITY, CardKind.VPS, CardKind.MUSIC)

    fun layout(card: ScoredCard): BlockLayout = when {
        card.size == CardSize.HERO -> BlockLayout.HERO
        card.kind in capsuleKinds && !card.content.placeholder && card.content.tasks.isEmpty() -> BlockLayout.CAPSULE
        card.size == CardSize.COMPACT -> BlockLayout.TILE
        else -> BlockLayout.FULL
    }

    fun form(card: ScoredCard, index: Int): BlockForm {
        val hasBadge = card.content.stat != null
        return when (layout(card)) {
            BlockLayout.HERO -> {
                val badge = if (hasBadge) 72.dp else 0.dp
                BlockForm(
                    BlockLayout.HERO,
                    ShapeSpec(listOf(56f, 24f, 56f, 24f), listOf(ShapeSpec.SMOOTH, ShapeSpec.ROUND, ShapeSpec.SMOOTH, ShapeSpec.ROUND), scallop = 6f,
                        notch = if (hasBadge) 42f else 0f, notchInset = 70f),
                    padX = 28.dp, padTop = 26.dp, padBottom = 34.dp, badge = badge, notchInset = 70.dp,
                )
            }
            BlockLayout.FULL -> {
                // Leaf: one corner 48 dp, the opposite 8 dp, the other two 16 dp. Which corner leads is one of
                // four, fixed per card (with the position as a tiebreak), so neighbours differ in both layouts.
                val alert = card.tint == Tint.ALERT
                val lead = Math.floorMod(card.id.hashCode() + index, 4)
                val r = if (alert) listOf(16f, 24f, 48f, 8f)
                else MutableList(4) { 16f }.also { it[lead] = 48f; it[(lead + 2) % 4] = 8f }
                val k = r.mapIndexed { i, v -> if (alert && i == 1) ShapeSpec.CUT else if (v >= 40f) ShapeSpec.SMOOTH else ShapeSpec.ROUND }
                val inset = if (r[1] >= 40f) 90f else 62f
                BlockForm(
                    BlockLayout.FULL,
                    ShapeSpec(r, k, notch = if (hasBadge) 38f else 0f, notchInset = inset),
                    padX = 22.dp, padTop = 20.dp, padBottom = 20.dp, badge = if (hasBadge) 64.dp else 0.dp, notchInset = inset.dp,
                )
            }
            BlockLayout.CAPSULE -> BlockForm(BlockLayout.CAPSULE, ShapeSpec.uniform(100f), padX = 30.dp, padTop = 14.dp, padBottom = 14.dp)
            BlockLayout.TILE -> BlockForm(BlockLayout.TILE, ShapeSpec.uniform(34f, ShapeSpec.SMOOTH), padX = 18.dp, padTop = 18.dp, padBottom = 18.dp)
        }
    }
}
