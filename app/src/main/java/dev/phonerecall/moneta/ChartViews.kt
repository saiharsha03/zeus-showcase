package dev.phonerecall.moneta

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.topRule

/**
 * Categorical slots, in fixed order, stepped for each surface (validated with the dataviz checker against
 * Athena's own surfaces: dark passes every check; light needs labels or the table view, which every chart has).
 */
object ChartColors {
    private val DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map { Color(it) }
    private val LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948).map { Color(it) }

    /** Colour follows the entity: series take slots by name, so a filter never repaints the survivors. "Other" is muted ink. */
    @Composable
    fun of(data: ChartData): Map<String, Color> {
        val c = Athena.colors
        val slots = if (c.isDark) DARK else LIGHT
        val named = data.series.map { it.name }.filter { it != ChartEngine.OTHER }.sorted()
        return data.series.associate { s -> s.name to if (s.name == ChartEngine.OTHER) c.ink3 else slots[named.indexOf(s.name).coerceAtLeast(0) % slots.size] }
    }

    /** Donut and single-series bars colour each slice by its label instead. */
    @Composable
    fun ofLabels(labels: List<String>): Map<String, Color> {
        val c = Athena.colors
        val slots = if (c.isDark) DARK else LIGHT
        val named = labels.filter { it != ChartEngine.OTHER }.sorted()
        return labels.associateWith { if (it == ChartEngine.OTHER) c.ink3 else slots[named.indexOf(it).coerceAtLeast(0) % slots.size] }
    }
}

private fun fmt(v: Long, m: Metric) = if (m == Metric.COUNT) "$v" else Money.fmt(v, whole = true)

/** Round, compact axis maximum: 0 / 250 / 500 rather than 0 / 237 / 474. */
private fun niceMax(v: Long): Long {
    if (v <= 0) return 1
    var step = 1L
    while (step * 10 <= v) step *= 10
    return listOf(1, 2, 5, 10).map { it * step }.first { it >= v }
}

private fun compact(v: Long, m: Metric): String {
    if (m == Metric.COUNT) return "$v"
    val d = v / 100
    return if (d >= 1000) "$${"%.1f".format(d / 1000.0).removeSuffix(".0")}K" else "$$d"
}

/** One chart: title, legend (two or more series), the drawing, a tap readout, and a table view. */
@Composable
fun ChartCard(spec: ChartSpec, data: ChartData, kicker: String? = null, onRemove: (() -> Unit)? = null) {
    val c = Athena.colors
    var table by remember(spec.id) { mutableStateOf(spec.type == ChartType.TABLE) }
    var picked by remember(spec.id, data) { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        kicker?.let { Kicker(it, Modifier.padding(bottom = Space.xs)) }
        Line(spec.title, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
        spec.subtitle?.let { Line(it, Type.small, c.ink3) }
        if (data.empty) {
            Line("Nothing to draw yet.", Type.small, c.ink3, Modifier.padding(vertical = Space.m))
        } else {
            // Only a donut colours by slice; a single series of bars or a line is one colour.
            val colors = if (spec.type == ChartType.DONUT) ChartColors.ofLabels(data.labels) else ChartColors.of(data)
            if (data.series.size >= 2 && !table) Legend(data.series.map { it.name }, colors)
            Spacer(Modifier.height(Space.s))
            when {
                table -> DataTable(data)
                spec.type == ChartType.DONUT -> Donut(data, colors)
                !spec.query.by.time && spec.type != ChartType.LINE -> HBars(data, colors, stacked = spec.type == ChartType.STACKED || data.series.size == 1)
                spec.type == ChartType.LINE -> TimeChart(data, colors, line = true, picked) { picked = it }
                else -> TimeChart(data, colors, line = false, picked, stacked = spec.type == ChartType.STACKED) { picked = it }
            }
            picked?.takeIf { !table && spec.query.by.time }?.let { i ->
                Line(
                    data.labels[i] + ": " + data.series.joinToString(", ") { s -> (if (data.series.size > 1) s.name + " " else "") + fmt(s.values[i], data.metric) },
                    Type.small, c.ink2, Modifier.padding(top = Space.xs),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!data.empty && spec.type != ChartType.TABLE) TextAction(if (table) "Chart" else "Table", { table = !table })
            onRemove?.let { TextAction("Remove", it) }
        }
    }
}

@Composable
private fun Legend(names: List<String>, colors: Map<String, Color>) {
    FlowRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        names.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colors[n] ?: Athena.colors.ink3))
                Spacer(Modifier.width(Space.xs))
                Line(n, Type.caption, Athena.colors.ink2, maxLines = 1)
            }
        }
    }
}

/**
 * Lines or columns over time, one baseline, hairline grid, labels at the first, middle and last points.
 * `ticks` are marks on the baseline at a fraction of the width (Odyssey's drive events on its speed line);
 * `peakLabel` names the first series' highest point ("Max 61").
 */
@Composable
internal fun TimeChart(
    data: ChartData, colors: Map<String, Color>, line: Boolean, picked: Int?, stacked: Boolean = false,
    ticks: List<Pair<Float, Color>> = emptyList(), refLabel: (String) -> String = { "Budget $it" }, peakLabel: ((String) -> String)? = null,
    onPick: (Int?) -> Unit,
) {
    val c = Athena.colors
    val measurer = rememberTextMeasurer()
    val n = data.labels.size
    val peak = if (stacked) (0 until n).maxOf { i -> data.series.sumOf { it.values[i] } } else data.series.maxOf { s -> s.values.maxOrNull() ?: 0 }
    val top = niceMax(maxOf(peak, data.reference ?: 0))
    val axis = Type.caption.copy(color = c.ink3)
    Canvas(
        Modifier.fillMaxWidth().height(168.dp).pointerInput(n) {
            detectTapGestures { p ->
                val left = 40.dp.toPx(); val w = size.width - left
                val i = if (n <= 1) 0 else (((p.x - left) / w) * (if (line) n - 1 else n) - if (line) 0f else 0.5f).let { Math.round(it) }.coerceIn(0, n - 1)
                onPick(if (picked == i) null else i)
            }
        },
    ) {
        val left = 40.dp.toPx(); val bottom = size.height - 20.dp.toPx(); val topY = 8.dp.toPx()
        val w = size.width - left; val h = bottom - topY
        fun y(v: Long) = bottom - h * (v.toFloat() / top)
        val hair = 1.dp.toPx()
        for (k in 0..2) {
            val v = top * k / 2
            drawLine(c.rule, Offset(left, y(v)), Offset(size.width, y(v)), hair)
            drawText(measurer, compact(v, data.metric), Offset(0f, y(v) - 8.dp.toPx()), axis)
        }
        val xs = (0 until n).map { i -> if (line) left + if (n == 1) w / 2 else w * i / (n - 1) else left + w * (i + 0.5f) / n }
        listOf(0, n / 2, n - 1).distinct().forEach { i ->
            val t = measurer.measure(data.labels[i], axis)
            drawText(t, topLeft = Offset((xs[i] - t.size.width / 2).coerceIn(left, size.width - t.size.width), bottom + 4.dp.toPx()))
        }
        picked?.let { drawLine(c.ink3, Offset(xs[it], topY), Offset(xs[it], bottom), hair) }
        if (line) {
            data.series.forEach { s ->
                val col = colors[s.name] ?: c.ink
                val path = Path().apply { s.values.forEachIndexed { i, v -> if (i == 0) moveTo(xs[i], y(v)) else lineTo(xs[i], y(v)) } }
                if (data.series.size == 1 && n > 1) {
                    val area = Path().apply { addPath(path); lineTo(xs.last(), bottom); lineTo(xs.first(), bottom); close() }
                    drawPath(area, col.copy(alpha = 0.10f))
                }
                drawPath(path, col, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                val end = Offset(xs.last(), y(s.values.last()))
                drawCircle(c.surface, 6.dp.toPx(), end)
                drawCircle(col, 4.dp.toPx(), end)
            }
        } else {
            val bw = minOf(24.dp.toPx(), w / n * 0.6f / if (stacked) 1 else data.series.size)
            val gap = 2.dp.toPx()
            for (i in 0 until n) {
                var base = bottom
                data.series.forEachIndexed { si, s ->
                    val v = s.values[i]
                    if (v <= 0) return@forEachIndexed
                    val col = colors[s.name] ?: c.ink
                    val x = if (stacked) xs[i] - bw / 2 else xs[i] - bw * data.series.size / 2 + bw * si
                    val hh = h * (v.toFloat() / top)
                    val topOf = base - hh
                    val isTop = !stacked || data.series.drop(si + 1).none { it.values[i] > 0 }
                    val r = if (isTop) CornerRadius(4.dp.toPx()) else CornerRadius.Zero
                    drawPath(Path().apply {
                        addRoundRect(RoundRect(x + if (stacked) 0f else gap / 2, topOf + (if (stacked && base != bottom) gap else 0f), x + bw - if (stacked) 0f else gap / 2, base, r, r, CornerRadius.Zero, CornerRadius.Zero))
                    }, col)
                    if (stacked) base = topOf
                }
            }
        }
        if (line && peakLabel != null) data.series.firstOrNull()?.let { s ->
            val i = s.values.indices.maxByOrNull { s.values[it] } ?: return@let
            val p = Offset(xs[i], y(s.values[i]))
            val t = measurer.measure(peakLabel(compact(s.values[i], data.metric)), axis.copy(color = c.ink))
            val pad = 4.dp.toPx()
            val left0 = (p.x - t.size.width / 2 - pad).coerceIn(left, size.width - t.size.width - 2 * pad)
            val top0 = (p.y - t.size.height - 3 * pad).coerceAtLeast(0f)
            drawRoundRect(c.raised, Offset(left0, top0), Size(t.size.width + 2 * pad, t.size.height + pad), CornerRadius(6.dp.toPx()))
            drawText(t, topLeft = Offset(left0 + pad, top0 + pad / 2))
            drawCircle(c.surface, 5.dp.toPx(), p)
            drawCircle(colors[s.name] ?: c.ink, 3.dp.toPx(), p)
        }
        for ((f, col) in ticks) {
            val x = left + w * f.coerceIn(0f, 1f)
            drawLine(col, Offset(x, bottom - 8.dp.toPx()), Offset(x, bottom), 2.dp.toPx(), cap = StrokeCap.Round)
        }
        data.reference?.let { ref ->
            drawLine(c.ink2, Offset(left, y(ref)), Offset(size.width, y(ref)), hair)
            val t = measurer.measure(refLabel(compact(ref, data.metric)), axis.copy(color = c.ink2))
            drawText(t, topLeft = Offset(size.width - t.size.width, y(ref) - t.size.height - 2.dp.toPx()))
        }
    }
}

/** Categories, merchants or cards as labelled horizontal bars, value at the tip. Splits stack along each bar. */
@Composable
private fun HBars(data: ChartData, colors: Map<String, Color>, stacked: Boolean) {
    val c = Athena.colors
    val totals = data.labels.indices.map { i -> data.series.sumOf { it.values[i] } }
    val peak = if (stacked) totals.max() else data.series.maxOf { it.values.max() }
    data.labels.forEachIndexed { i, label ->
        Column(Modifier.fillMaxWidth().padding(vertical = Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Line(label, Type.small, c.ink, Modifier.weight(1f), maxLines = 1)
                Line(if (data.series.size == 1 || stacked) fmt(totals[i], data.metric) else data.series.joinToString("  ") { fmt(it.values[i], data.metric) }, Type.small, c.ink2)
            }
            if (stacked) {
                Row(Modifier.fillMaxWidth().padding(top = 3.dp).height(8.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    data.series.forEach { s ->
                        val f = s.values[i].toFloat() / peak.coerceAtLeast(1)
                        if (f > 0f) Box(Modifier.weight(f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors[s.name] ?: c.ink3))
                    }
                    val rest = 1f - totals[i].toFloat() / peak.coerceAtLeast(1)
                    if (rest > 0.001f) Spacer(Modifier.weight(rest))
                }
            } else {
                data.series.forEach { s ->
                    Row(Modifier.fillMaxWidth().padding(top = 3.dp).height(6.dp)) {
                        val f = (s.values[i].toFloat() / peak.coerceAtLeast(1)).coerceIn(0.005f, 1f)
                        Box(Modifier.weight(f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors[s.name] ?: c.ink3))
                        if (f < 1f) Spacer(Modifier.weight(1f - f))
                    }
                }
            }
        }
    }
}

/** A ring with 2 px gaps between slices, the total in the middle, and a legend with each slice's amount and share. */
@Composable
private fun Donut(data: ChartData, colors: Map<String, Color>) {
    val c = Athena.colors
    val measurer = rememberTextMeasurer()
    val values = data.labels.indices.map { i -> data.series.sumOf { it.values[i] } }
    val total = values.sum().coerceAtLeast(1)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.l)) {
        Canvas(Modifier.size(132.dp)) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val r = size.minDimension / 2 - inset
            val gapDeg = Math.toDegrees((2.dp.toPx() / r).toDouble()).toFloat()
            var start = -90f
            values.forEachIndexed { i, v ->
                val sweep = 360f * v / total
                if (sweep > gapDeg) drawArc(colors[data.labels[i]] ?: c.ink3, start + gapDeg / 2, sweep - gapDeg, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                start += sweep
            }
            val t = measurer.measure(fmt(values.sum(), data.metric), Type.body.copy(color = c.ink))
            drawText(t, topLeft = Offset(center.x - t.size.width / 2, center.y - t.size.height / 2))
        }
        Column(Modifier.weight(1f)) {
            data.labels.forEachIndexed { i, l ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colors[l] ?: c.ink3))
                    Spacer(Modifier.width(Space.xs))
                    Line(l, Type.caption, c.ink2, Modifier.weight(1f), maxLines = 1)
                    Line("${fmt(values[i], data.metric)}  ${values[i] * 100 / total}%", Type.caption, c.ink2)
                }
            }
        }
    }
}

/** The numbers behind any chart. */
@Composable
private fun DataTable(data: ChartData) {
    val c = Athena.colors
    Column {
        if (data.series.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Spacer(Modifier.weight(1f))
            data.series.forEach { Line(it.name, Type.caption, c.ink3, Modifier.width(76.dp), maxLines = 1) }
        }
        data.labels.forEachIndexed { i, l ->
            Row(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = 3.dp)) {
                Line(l, Type.small, c.ink, Modifier.weight(1f), maxLines = 1)
                data.series.forEach { Line(fmt(it.values[i], data.metric), Type.small, c.ink2, Modifier.width(76.dp), maxLines = 1) }
            }
        }
        data.reference?.let { Line("Budget ${fmt(it, data.metric)}", Type.caption, c.ink3, Modifier.padding(top = Space.xs)) }
    }
}
