package tv.coog.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.coog.app.data.InteractiveDevice

@Composable
fun DeviceBatteryIcon(
    device: InteractiveDevice,
    size: Dp = 32.dp,
    onClick: (() -> Unit)? = null,
    allowFocus: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    var failed by remember(device.deviceId, device.name, server.url) { mutableStateOf(false) }
    val iconUrl = server.deviceIconUrl(device.name, device.deviceId)
    val level = device.batteryPercent.coerceIn(0, 100)
    val showBattery = device.batterySupported && device.batteryPercent >= 0
    val ringColor = when {
        !showBattery -> Color.White.copy(alpha = 0.18f)
        level <= 20 -> Color(0xFFF87171)
        level <= 50 -> Color(0xFFF5D06A)
        else -> Color(0xFF6EE7A8)
    }
    val content = @Composable {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = this.size.minDimension * 0.09f
                val inset = stroke / 2f
                val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
                drawArc(
                    color = Color.White.copy(alpha = 0.12f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
                if (showBattery) {
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * (level / 100f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(size * 0.14f)
                    .background(Color.White.copy(alpha = 0.06f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (iconUrl.isNotBlank() && !failed) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(iconUrl)
                            .crossfade(true)
                            .apply {
                                if (server.token.isNotBlank()) {
                                    addHeader("Authorization", "Bearer ${server.token}")
                                }
                                if (server.adultSession.isNotBlank()) {
                                    addHeader("X-Coog-Adult-Session", server.adultSession)
                                }
                            }
                            .build(),
                        contentDescription = device.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(size * 0.08f),
                        onError = { failed = true },
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxSize(0.45f)
                            .background(Color(0xFF9B8FF5).copy(alpha = 0.85f), CircleShape),
                    )
                }
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.White.copy(alpha = 0.12f),
            ),
            modifier = modifier
                .size(size)
                .focusProperties { canFocus = allowFocus },
        ) {
            content()
        }
    } else {
        Box(modifier.size(size)) { content() }
    }
}
