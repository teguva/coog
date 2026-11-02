package tv.coog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import tv.coog.app.data.FunscriptPoint
import tv.coog.app.data.FunscriptPreview
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogTextSecondary
import tv.coog.app.ui.theme.CoogType
import kotlin.math.max
import kotlin.math.min

/** Lucifie / OFS heatmap palette (Funplay / funscript-utils). */
private val HeatmapColors = listOf(
    Color(0f, 0f, 0f),
    Color(30 / 255f, 144 / 255f, 255 / 255f),
    Color(34 / 255f, 139 / 255f, 34 / 255f),
    Color(255 / 255f, 215 / 255f, 0f),
    Color(220 / 255f, 20 / 255f, 60 / 255f),
    Color(147 / 255f, 112 / 255f, 219 / 255f),
    Color(37 / 255f, 22 / 255f, 122 / 255f),
)
private const val ColorStep = 120f
private val HeatmapChartHeight = 48.dp

@Composable
fun FunscriptBar(
    preview: FunscriptPreview?,
    loading: Boolean = false,
    syncHint: String? = null,
    modifier: Modifier = Modifier,
) {
    if (!loading && (preview == null || preview.points.isEmpty()) && syncHint.isNullOrBlank()) {
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 72.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Funscript heatmap", style = CoogType.shelfTitle, color = Color.White)
            val meta = preview?.actionCount?.takeIf { it > 0 }?.let { "$it actions" }.orEmpty()
            if (meta.isNotBlank()) {
                Text(meta, style = CoogType.cardYear, color = CoogTextSecondary)
            }
        }
        when {
            loading && preview == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HeatmapChartHeight)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp)),
                )
            }
            preview != null && preview.points.isNotEmpty() -> {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(HeatmapChartHeight)
                        .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp)),
                ) {
                    val smoothed = prepareSmoothedPoints(preview.points, radius = 5)
                    if (smoothed.isEmpty()) return@Canvas
                    val n = smoothed.size
                    val colW = size.width / n
                    val barW = (colW * 0.85f).coerceAtLeast(1f)
                    val radius = (barW / 2f).coerceAtMost(size.height / 2f)
                    val xWindow = max(12, min(28, n / 16))
                    val yWindow = 10
                    val speedWindow = ArrayDeque<Double>()
                    val posWindow = ArrayDeque<Double>()
                    for (i in 0 until n) {
                        val point = smoothed[i]
                        val speed = if (point.speed > 0) point.speed else point.strength
                        speedWindow.addLast(speed)
                        posWindow.addLast(point.pos)
                        while (speedWindow.size > xWindow) speedWindow.removeFirst()
                        while (posWindow.size > yWindow) posWindow.removeFirst()
                        val avgSpeed = speedWindow.average()
                        val color = heatmapColor(avgSpeed.toFloat()).copy(alpha = 0.72f)
                        val sorted = posWindow.sorted()
                        val split = max(1, sorted.size / 2)
                        val avgBottom = sorted.take(split).average()
                        val avgTop = sorted.drop(split).ifEmpty { sorted }.average()
                        val bottom = min(avgBottom, point.posMin)
                        val top = max(avgTop, point.posMax)
                        val yBottom = size.height - (bottom / 100.0).toFloat().coerceIn(0f, 1f) * size.height
                        val yTop = size.height - (top / 100.0).toFloat().coerceIn(0f, 1f) * size.height
                        val barH = max(barW.coerceAtMost(size.height), yBottom - yTop)
                        val x = i * colW + (colW - barW) / 2f
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, yTop),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(radius, radius),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Slow", style = CoogType.cardYear, color = CoogTextMuted)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .height(6.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        heatmapColor(0f),
                                        heatmapColor(90f),
                                        heatmapColor(210f),
                                        heatmapColor(420f),
                                        heatmapColor(600f),
                                    ),
                                ),
                                RoundedCornerShape(99.dp),
                            ),
                    )
                    Text("Intense", style = CoogType.cardYear, color = CoogTextMuted)
                }
                Text(
                    "Color = speed · height = stroke range",
                    style = CoogType.cardYear,
                    color = CoogTextMuted,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        if (!syncHint.isNullOrBlank()) {
            Text(syncHint, style = CoogType.cardYear, color = CoogTextMuted)
        }
    }
}

private fun prepareSmoothedPoints(points: List<FunscriptPoint>, radius: Int): List<FunscriptPoint> {
    if (radius <= 0 || points.isEmpty()) return points
    fun smooth(values: List<Double>): List<Double> {
        return values.indices.map { i ->
            val start = max(0, i - radius)
            val end = min(values.lastIndex, i + radius)
            var sum = 0.0
            var count = 0
            for (j in start..end) {
                sum += values[j]
                count++
            }
            sum / count
        }
    }
    val speeds = smooth(points.map { if (it.speed > 0) it.speed else it.strength })
    val positions = smooth(points.map { it.pos })
    val posMins = smooth(points.map { it.posMin })
    val posMaxs = smooth(points.map { it.posMax })
    return points.mapIndexed { i, p ->
        p.copy(
            speed = speeds[i],
            strength = speeds[i],
            pos = positions[i],
            posMin = posMins[i],
            posMax = posMaxs[i],
        )
    }
}

private fun heatmapColor(intensity: Float): Color {
    if (intensity <= 0f) return HeatmapColors[0]
    if (intensity > 5f * ColorStep) return HeatmapColors[6]
    val value = intensity + ColorStep / 2f
    val index = (value / ColorStep).toInt().coerceIn(0, HeatmapColors.lastIndex)
    val next = min(index + 1, HeatmapColors.lastIndex)
    val t = ((value - index * ColorStep) / ColorStep).coerceIn(0f, 1f)
    val a = HeatmapColors[index]
    val b = HeatmapColors[next]
    return Color(
        red = a.red + (b.red - a.red) * t,
        green = a.green + (b.green - a.green) * t,
        blue = a.blue + (b.blue - a.blue) * t,
        alpha = 1f,
    )
}
