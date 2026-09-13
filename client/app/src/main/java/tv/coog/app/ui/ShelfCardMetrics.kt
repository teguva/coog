package tv.coog.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Horizontal shelf card sizes from an 18-unit content band:
 * focused trailer 12u at 2:1, three unfocused peeks at 2u × 2:3 (height = ½ focused).
 * Gaps between the four cards are taken out of [contentWidth] before splitting units.
 */
data class ShelfCardMetrics(
    val unit: Dp,
    val gap: Dp,
    val heroWidth: Dp,
    val heroHeight: Dp,
    val peekWidth: Dp,
    val peekHeight: Dp,
)

/** Carousel gap between hero and peeks (and between peeks). */
val ShelfCardGap = 12.dp

fun shelfCardMetrics(contentWidth: Dp, gap: Dp = ShelfCardGap): ShelfCardMetrics {
    val usable = (contentWidth - gap * 3).coerceAtLeast(1.dp)
    val unit = usable / 18f
    val peekWidth = unit * 2f
    val peekHeight = peekWidth * 3f / 2f
    val heroHeight = peekHeight * 2f
    val heroWidth = heroHeight * 2f
    return ShelfCardMetrics(
        unit = unit,
        gap = gap,
        heroWidth = heroWidth,
        heroHeight = heroHeight,
        peekWidth = peekWidth,
        peekHeight = peekHeight,
    )
}

@Composable
fun rememberShelfCardMetrics(contentWidth: Dp, gap: Dp = ShelfCardGap): ShelfCardMetrics {
    return remember(contentWidth, gap) { shelfCardMetrics(contentWidth, gap) }
}
