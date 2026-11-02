package tv.coog.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Theaters
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import tv.coog.app.ui.theme.CoogDanger
import tv.coog.app.ui.theme.CoogFetch
import tv.coog.app.ui.theme.CoogLocal
import tv.coog.app.ui.theme.CoogMarkOn
import tv.coog.app.ui.theme.CoogReady
import tv.coog.app.ui.theme.CoogSoon
import tv.coog.app.ui.theme.CoogTheatre

enum class MarkSize { Compact, Comfort }

@Composable
fun CardMarks(
    mark: CardMark?,
    modifier: Modifier = Modifier,
    matchPercent: Int? = null,
    size: MarkSize = MarkSize.Compact,
    showMatch: Boolean = false,
) {
    if (mark == null && !(showMatch && matchPercent != null && matchPercent > 0)) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mark != null) StatusMark(mark = mark, size = size)
        if (showMatch && matchPercent != null && matchPercent > 0) {
            MatchMark(percent = matchPercent, size = size)
        }
    }
}

@Composable
fun StatusMark(
    mark: CardMark,
    modifier: Modifier = Modifier,
    size: MarkSize = MarkSize.Compact,
) {
    if (mark.kind == CardMarkKind.Partial) {
        val compact = size == MarkSize.Compact
        Row(
            modifier = modifier
                .height(if (compact) 18.dp else 22.dp)
                .background(mark.tone(), RoundedCornerShape(50))
                .padding(horizontal = if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                mark.shortLabel,
                color = CoogMarkOn,
                fontSize = if (compact) 9.sp else 11.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
        return
    }
    if (mark.isRing()) {
        TransferRing(mark = mark, size = size, modifier = modifier)
        return
    }
    val tone = mark.tone()
    val icon = mark.icon()
    if (size == MarkSize.Compact) {
        Box(
            modifier = modifier
                .size(22.dp)
                .background(tone, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = mark.shortLabel, tint = CoogMarkOn, modifier = Modifier.size(13.dp))
        }
        return
    }
    Row(
        modifier = modifier
            .height(22.dp)
            .background(tone, RoundedCornerShape(50))
            .padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = CoogMarkOn, modifier = Modifier.size(12.dp))
        Text(
            mark.shortLabel,
            color = CoogMarkOn,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
        )
    }
}

@Composable
fun MatchMark(
    percent: Int,
    modifier: Modifier = Modifier,
    size: MarkSize = MarkSize.Compact,
) {
    val color = matchTone(percent)
    val ring = if (size == MarkSize.Compact) 22.dp else 28.dp
    val stroke = if (size == MarkSize.Compact) 2.2.dp else 2.6.dp
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ProgressRing(
            progress = (percent / 100f).coerceIn(0.04f, 1f),
            color = color,
            ringSize = ring,
            stroke = stroke,
        ) {
            Text(
                percent.toString(),
                color = Color.White,
                fontSize = if (size == MarkSize.Compact) 8.sp else 10.sp,
                fontWeight = FontWeight.Bold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
        if (size == MarkSize.Comfort) {
            Text(
                "Match",
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
            )
        }
    }
}

@Composable
fun TransferRing(
    mark: CardMark,
    modifier: Modifier = Modifier,
    size: MarkSize = MarkSize.Compact,
) {
    val ring = if (size == MarkSize.Compact) 26.dp else 40.dp
    val stroke = if (size == MarkSize.Compact) 2.4.dp else 3.2.dp
    val pct = (mark.progress * 100).toInt().coerceIn(0, 99)
    val indeterminate = mark.kind == CardMarkKind.Fetching || (mark.progress <= 0f && mark.kind != CardMarkKind.Paused)
    ProgressRing(
        progress = mark.progress.coerceIn(0f, 1f),
        color = mark.tone(),
        ringSize = ring,
        stroke = stroke,
        indeterminate = indeterminate,
        modifier = modifier,
    ) {
        when {
            mark.kind == CardMarkKind.Ready && pct <= 0 -> {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = "Ready",
                    tint = CoogReady,
                    modifier = Modifier.size(if (size == MarkSize.Compact) 12.dp else 18.dp),
                )
            }
            mark.kind == CardMarkKind.Paused && pct <= 0 -> {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = "Paused",
                    tint = Color.White,
                    modifier = Modifier.size(if (size == MarkSize.Compact) 11.dp else 16.dp),
                )
            }
            pct > 0 -> {
                Text(
                    pct.toString(),
                    color = Color.White,
                    fontSize = if (size == MarkSize.Compact) 8.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                )
            }
            else -> {
                Icon(
                    Icons.Outlined.Download,
                    contentDescription = mark.shortLabel,
                    tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(if (size == MarkSize.Compact) 11.dp else 16.dp),
                )
            }
        }
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    ringSize: Dp = 26.dp,
    stroke: Dp = 2.4.dp,
    indeterminate: Boolean = false,
    content: @Composable () -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(280),
        label = "ring-progress",
    )
    val inf = rememberInfiniteTransition(label = "ring-spin")
    val spin by inf.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "ring-angle",
    )
    Box(
        modifier = modifier.size(ringSize),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .then(if (indeterminate) Modifier.rotate(spin) else Modifier),
        ) {
            val strokePx = Stroke(width = stroke.toPx(), cap = StrokeCap.Round)
            drawCircle(color = Color.Black.copy(alpha = 0.58f))
            drawArc(
                color = Color.White.copy(alpha = 0.18f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = strokePx,
            )
            val sweep = if (indeterminate) 84f else 360f * animated.coerceAtLeast(0.03f)
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = strokePx,
            )
        }
        content()
    }
}

fun matchTone(percent: Int): Color = when {
    percent >= 70 -> CoogLocal
    percent >= 45 -> CoogTheatre
    else -> Color(0xFFFF9B6B)
}

fun CardMark.tone(): Color = when (kind) {
    CardMarkKind.Local, CardMarkKind.Partial -> CoogLocal
    CardMarkKind.Fetching, CardMarkKind.Downloading -> CoogFetch
    CardMarkKind.Ready -> CoogReady
    CardMarkKind.Paused -> Color(0xFFD8D8DE)
    CardMarkKind.Theatrical -> CoogTheatre
    CardMarkKind.ComingSoon -> CoogSoon
    CardMarkKind.Failed -> CoogDanger
}

fun CardMark.icon(): ImageVector = when (kind) {
    CardMarkKind.Local, CardMarkKind.Partial -> Icons.Filled.Check
    CardMarkKind.Theatrical -> Icons.Outlined.Theaters
    CardMarkKind.ComingSoon -> Icons.Outlined.Schedule
    CardMarkKind.Failed -> Icons.Outlined.ErrorOutline
    CardMarkKind.Paused -> Icons.Filled.Pause
    CardMarkKind.Ready -> Icons.Filled.PlayArrow
    CardMarkKind.Fetching, CardMarkKind.Downloading -> Icons.Outlined.Download
}

fun CardMark.isRing(): Boolean = when (kind) {
    CardMarkKind.Fetching, CardMarkKind.Downloading, CardMarkKind.Ready, CardMarkKind.Paused -> true
    else -> false
}
