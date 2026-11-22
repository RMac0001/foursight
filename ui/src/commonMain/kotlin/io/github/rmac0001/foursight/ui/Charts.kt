package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rmac0001.foursight.core.query.Format
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One point on a chart. A null [y] is a gap: drawn as a dotted bridge, never as 0. */
data class ChartPoint(
    val x: Int,
    val xLabel: String,
    val y: Double?,
    /** Shown on hover or tap: the date, the source and the capture time (spec: interactive charts). */
    val detail: String,
)

data class ChartSeries(
    val name: String,
    val color: Color,
    val points: List<ChartPoint>,
)

private const val LEFT = 56f
private const val RIGHT_PAD = 8f

/** Maps an x value to its pixel position for a canvas [width]; shared by drawing and hover lookup. */
private class XScale(
    val minX: Int,
    val maxX: Int,
    val width: Float,
) {
    val right get() = width - RIGHT_PAD

    fun px(x: Int): Float =
        if (maxX ==
            minX
        ) {
            (LEFT + right) / 2
        } else {
            LEFT + (x - minX).toFloat() / (maxX - minX) * (right - LEFT)
        }
}

/**
 * A line chart drawn in FourSight's own code (Roger's decision, November 22, 2026). Gaps are dotted
 * bridges between the nearest real points, plus a hollow marker on the axis; hovering or tapping
 * shows that day's value, source and capture time for every series. All series share one scale.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
    height: Dp = 200.dp,
    showLegend: Boolean = true,
) {
    val all = series.flatMap { it.points }
    if (all.none { it.y != null }) {
        EmptyState("No numbers in this range yet.")
        return
    }
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val xs = all.map { it.x }.distinct().sorted()
    var width by remember { mutableIntStateOf(0) }
    var hovered by remember(series) { mutableStateOf<Int?>(null) }
    val scale = XScale(xs.first(), xs.last(), width.toFloat())
    val values = all.mapNotNull { it.y }
    var lo = values.min()
    var hi = values.max()
    if (hi == lo) {
        lo -= 1.0
        hi += 1.0
    }
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .onSizeChanged { width = it.width }
                .pointerInput(series, width) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            val x =
                                e.changes
                                    .firstOrNull()
                                    ?.position
                                    ?.x
                            hovered =
                                if (e.type == PointerEventType.Exit || x == null) {
                                    null
                                } else {
                                    xs.minByOrNull { abs(scale.px(it) - x) }
                                }
                        }
                    }
                },
        ) {
            val bottom = size.height - 24f
            val top = 8f

            fun py(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
            // Light horizontal gridlines (the Power BI look) and the baseline.
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                val y = top + (bottom - top) * f
                drawLine(axisColor.copy(alpha = 0.5f), Offset(LEFT, y), Offset(scale.right, y))
            }
            drawLine(axisColor, Offset(LEFT, bottom), Offset(scale.right, bottom))
            listOf(lo to bottom, hi to top).forEach { (v, y) ->
                drawText(
                    measurer,
                    Format.number(v, decimals),
                    Offset(0f, y - 8f),
                    TextStyle(fontSize = 10.sp, color = labelColor),
                )
            }
            val labelled = all.distinctBy { it.x }.sortedBy { it.x }
            listOf(labelled.first(), labelled[labelled.size / 2], labelled.last()).distinct().forEach { p ->
                val x = min(max(scale.px(p.x) - 30f, LEFT), scale.right - 60f)
                drawText(measurer, p.xLabel, Offset(x, bottom + 4f), TextStyle(fontSize = 10.sp, color = labelColor))
            }
            series.forEach { s ->
                val sorted = s.points.sortedBy { it.x }
                val real = sorted.filter { it.y != null }
                // A soft fill under a lone series' solid stretches; never under a gap.
                if (series.size == 1 && real.size > 1) {
                    real.zipWithNext().forEach { (a, b) ->
                        val gap = sorted.any { it.x > a.x && it.x < b.x && it.y == null }
                        if (!gap) {
                            val area =
                                Path().apply {
                                    moveTo(scale.px(a.x), bottom)
                                    lineTo(scale.px(a.x), py(a.y!!))
                                    lineTo(scale.px(b.x), py(b.y!!))
                                    lineTo(scale.px(b.x), bottom)
                                    close()
                                }
                            drawPath(area, s.color.copy(alpha = 0.12f))
                        }
                    }
                }
                real.zipWithNext().forEach { (a, b) ->
                    val gap = sorted.any { it.x > a.x && it.x < b.x && it.y == null }
                    drawLine(
                        s.color,
                        Offset(scale.px(a.x), py(a.y!!)),
                        Offset(scale.px(b.x), py(b.y!!)),
                        strokeWidth = 2.5f,
                        pathEffect = if (gap) PathEffect.dashPathEffect(floatArrayOf(6f, 6f)) else null,
                    )
                }
                real.forEach { p -> drawCircle(s.color, 3.5f, Offset(scale.px(p.x), py(p.y!!))) }
                sorted.filter { it.y == null }.forEach { p ->
                    drawCircle(labelColor, 3f, Offset(scale.px(p.x), bottom), style = Stroke(1.5f))
                }
            }
            hovered?.let { hx ->
                val x = scale.px(hx)
                drawLine(
                    labelColor,
                    Offset(x, top),
                    Offset(x, bottom),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f)),
                )
            }
        }
        val lines =
            hovered?.let { x ->
                series.mapNotNull { s ->
                    s.points.firstOrNull { it.x == x }?.let { p ->
                        "${s.name}: ${p.y?.let { Format.number(it, decimals) } ?: "missing"} (${p.detail})"
                    }
                }
            } ?: listOf("Hover over or tap the chart to see a day's numbers.")
        Column(Modifier.padding(top = 4.dp)) { lines.forEach { Muted(it) } }
        if (showLegend) Legend(series)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Legend(series: List<ChartSeries>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(s.color, RoundedCornerShape(2.dp)))
                Text(" ${s.name}", style = MaterialTheme.typography.labelMedium)
            }
        }
        Muted("Dotted line or hollow dot: a gap (no capture), never filled in")
    }
}

/** A horizontal bar for one group of a breakdown. [max] is the full-width value. */
@Composable
fun BarRow(
    label: String,
    value: Double?,
    max: Double,
    shown: String,
    color: Color,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(180.dp))
        val f = if (value == null || max <= 0) 0f else (value / max).toFloat().coerceIn(0f, 1f)
        Canvas(Modifier.weight(1f).height(14.dp)) { drawRect(color, size = size.copy(width = size.width * f)) }
        Text(shown, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(150.dp).padding(start = 8.dp))
    }
}
