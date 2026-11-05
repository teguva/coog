package tv.coog.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import tv.coog.app.BuildConfig

/**
 * Clickable surface that uses Material ripple on phone/tablet and TV Surface on Leanback.
 * TV Material3 clickables are focus-first and unreliable under touch / gesture nav.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoogClickable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(12.dp),
    color: Color = Color.Transparent,
    /** TV focus zoom — 1f for dense lists/actions where scale breaks D-pad geometry. */
    focusedScale: Float = 1.06f,
    content: @Composable () -> Unit,
) {
    if (BuildConfig.TOUCH_CLIENT) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(color)
                .combinedClickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            content = { content() },
        )
    } else {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            enabled = enabled,
            shape = ClickableSurfaceDefaults.shape(shape = shape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = color,
                focusedContainerColor = color,
                pressedContainerColor = color,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
            modifier = modifier,
        ) {
            content()
        }
    }
}
