package dev.phonerecall.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One parametric family for every block, so any block shape can morph into any other.
 *
 * - `r`, `k`: per-corner radius (dp) and roundness (0 = a straight cut, ~0.55 a circular arc, ~0.86
 *   with a big radius a squircle). A radius past half the short side makes a capsule end. Order:
 *   top-left, top-right, bottom-right, bottom-left.
 * - `scallop`: depth (dp) of a scalloped bottom edge (the hero's).
 * - `notch`: radius (dp) of a round bite out of the top edge, centred `notchInset` dp from the right,
 *   where a circular number badge sits.
 */
@Immutable
data class ShapeSpec(
    val r: List<Float>,
    val k: List<Float>,
    val scallop: Float = 0f,
    val notch: Float = 0f,
    val notchInset: Float = 0f,
) {
    fun lerp(to: ShapeSpec, t: Float) = ShapeSpec(
        r.indices.map { r[it] + (to.r[it] - r[it]) * t },
        k.indices.map { k[it] + (to.k[it] - k[it]) * t },
        scallop + (to.scallop - scallop) * t,
        notch + (to.notch - notch) * t,
        notchInset + (to.notchInset - notchInset) * t,
    )

    companion object {
        const val ROUND = 0.5523f
        const val SMOOTH = 0.6f
        const val CUT = 0f
        fun uniform(radius: Float, k: Float = ROUND) = ShapeSpec(List(4) { radius }, List(4) { k })
        fun corners(tl: Float, tr: Float, br: Float, bl: Float, k: Float = ROUND) = ShapeSpec(listOf(tl, tr, br, bl), List(4) { k })
    }
}

/** Draws a [ShapeSpec]. Radii are clamped so opposite corners never overlap. */
class SpecShape(private val s: ShapeSpec) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val d = density.density
        val max = min(w, h) / 2f
        val r = s.r.map { (it * d).coerceIn(0f, max) }
        val k = s.k.map { it.coerceIn(0f, 1f) }
        val (tl, tr, br, bl) = r
        val depth = (s.scallop * d).coerceIn(0f, h / 4f)
        val p = Path().apply {
            moveTo(0f, tl)
            cubicTo(0f, tl * (1 - k[0]), tl * (1 - k[0]), 0f, tl, 0f)
            lineTo(w - tr, 0f)
            cubicTo(w - tr * (1 - k[1]), 0f, w, tr * (1 - k[1]), w, tr)
            lineTo(w, h - br)
            cubicTo(w, h - br * (1 - k[2]), w - br * (1 - k[2]), h, w - br, h)
            // Bottom edge: straight, or a row of shallow scallops between the bottom corners.
            val span = (w - br) - bl
            if (depth > 0.5f && span > 40f * d) {
                val n = max(2, (span / (46f * d)).roundToInt())
                val seg = span / n
                var x = w - br
                repeat(n) {
                    val nx = x - seg
                    quadraticTo(x - seg / 2f, h - depth * 2f, nx, h)
                    x = nx
                }
            } else lineTo(bl, h)
            cubicTo(bl * (1 - k[3]), h, 0f, h - bl * (1 - k[3]), 0f, h - bl)
            close()
        }
        if (s.notch <= 0.5f) return Outline.Generic(p)
        val nr = s.notch * d
        val cx = w - s.notchInset * d
        val bite = Path().apply { addOval(Rect(Offset(cx, 0f), nr)) }
        return Outline.Generic(Path.combine(PathOperation.Difference, p, bite))
    }
}

/**
 * The shape a block should have now, animated: a new size, place or the lift morphs it with the same
 * critically damped spring as everything else. A lifted block drifts toward a rounder shape.
 */
@Composable
fun animatedShape(target: ShapeSpec, lifted: Boolean): Shape {
    val goal = if (lifted) target.lerp(ShapeSpec.uniform(36f, ShapeSpec.SMOOTH).copy(notch = target.notch, notchInset = target.notchInset), 0.6f) else target
    val r0 by animateFloatAsState(goal.r[0], Motion.settle(), label = "r0")
    val r1 by animateFloatAsState(goal.r[1], Motion.settle(), label = "r1")
    val r2 by animateFloatAsState(goal.r[2], Motion.settle(), label = "r2")
    val r3 by animateFloatAsState(goal.r[3], Motion.settle(), label = "r3")
    val k0 by animateFloatAsState(goal.k[0], Motion.settle(), label = "k0")
    val k1 by animateFloatAsState(goal.k[1], Motion.settle(), label = "k1")
    val k2 by animateFloatAsState(goal.k[2], Motion.settle(), label = "k2")
    val k3 by animateFloatAsState(goal.k[3], Motion.settle(), label = "k3")
    val sc by animateFloatAsState(goal.scallop, Motion.settle(), label = "scallop")
    val no by animateFloatAsState(goal.notch, Motion.settle(), label = "notch")
    val ni by animateFloatAsState(goal.notchInset, Motion.settle(), label = "notchInset")
    return remember(r0, r1, r2, r3, k0, k1, k2, k3, sc, no, ni) {
        SpecShape(ShapeSpec(listOf(r0, r1, r2, r3), listOf(k0, k1, k2, k3), sc, no, ni))
    }
}

/** Number tile forms, after BetweenWeather's metric tiles. */
enum class TileForm { CIRCLE, COOKIE, BURST, CLOVER, SQUIRCLE }

/** A RoundedPolygon from graphics-shapes, drawn as a Compose shape scaled to the tile. */
private class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = Path()
        val cubics = polygon.cubics
        if (cubics.isNotEmpty()) {
            val sx = size.width
            val sy = size.height
            p.moveTo(cubics[0].anchor0X * sx, cubics[0].anchor0Y * sy)
            for (c in cubics) p.cubicTo(c.control0X * sx, c.control0Y * sy, c.control1X * sx, c.control1Y * sy, c.anchor1X * sx, c.anchor1Y * sy)
            p.close()
        }
        return Outline.Generic(p)
    }
}

object TileShapes {
    /** A nine-scallop cookie: counts ("2 due"). */
    val cookie: Shape = PolygonShape(
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.8f, rounding = CornerRounding(0.35f), innerRounding = CornerRounding(0.35f)).normalized(),
    )
    /** A twelve-point soft burst: time left ("46 min"). */
    val burst: Shape = PolygonShape(
        RoundedPolygon.star(numVerticesPerRadius = 12, innerRadius = 0.82f, rounding = CornerRounding(0.18f), innerRounding = CornerRounding(0.18f)).normalized(),
    )
    /** A four-leaf clover: a day ("Today"). */
    val clover: Shape = PolygonShape(
        RoundedPolygon.star(numVerticesPerRadius = 4, innerRadius = 0.42f, rounding = CornerRounding(0.95f), innerRounding = CornerRounding(0.25f)).normalized(),
    )
    val squircle: Shape = SpecShape(ShapeSpec.uniform(30f, ShapeSpec.SMOOTH))

    fun of(f: TileForm): Shape = when (f) {
        TileForm.CIRCLE -> CircleShape
        TileForm.COOKIE -> cookie
        TileForm.BURST -> burst
        TileForm.CLOVER -> clover
        TileForm.SQUIRCLE -> squircle
    }
}
