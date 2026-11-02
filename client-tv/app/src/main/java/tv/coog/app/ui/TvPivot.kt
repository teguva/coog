package tv.coog.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberPinBringIntoViewSpec(pin: Dp): BringIntoViewSpec {
    val pinPx = with(LocalDensity.current) { pin.toPx() }
    return remember(pinPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(
                offset: Float,
                size: Float,
                containerSize: Float,
            ): Float = offset - pinPx
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotBringIntoView(
    pin: Dp,
    content: @Composable () -> Unit,
) {
    val spec = rememberPinBringIntoViewSpec(pin)
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
