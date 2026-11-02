package tv.coog.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val CoogBgDeep = Color(0xFF070708)
val CoogBgMid = Color(0xFF0E0E10)
val CoogBgSoft = Color(0xFF161618)
val CoogOnSurface = Color(0xFFFFFFFF)
val CoogMuted = Color(0xFF7A7A80)
val CoogGlass = Color(0x14FFFFFF)
val CoogFocus = Color(0xFFFFFFFF)
val CoogText = Color.White
val CoogTextSecondary = Color.White.copy(alpha = 0.72f)
val CoogTextMuted = Color.White.copy(alpha = 0.48f)
val CoogCached = Color(0xFF8EE4A8)
val CoogDanger = Color(0xFFFF8B8B)
val CoogLocal = Color(0xFF5EE09A)
val CoogPartial = CoogLocal
val CoogFetch = Color(0xFF6EC8FF)
val CoogReady = Color(0xFF7DFFCE)
val CoogTheatre = Color(0xFFFFC46B)
val CoogSoon = Color(0xFFB9A6FF)
val CoogMarkOn = Color(0xFF101214)

object CoogType {
    val heroTitle = TextStyle(
        color = CoogText,
        fontSize = 42.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp,
        lineHeight = 46.sp,
    )
    val heroTagline = TextStyle(
        color = Color.White.copy(alpha = 0.86f),
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 22.sp,
    )
    val heroPlot = TextStyle(
        color = CoogTextSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
    )
    val chip = TextStyle(
        color = Color.White.copy(alpha = 0.92f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    )
    val shelfTitle = TextStyle(
        color = Color.White.copy(alpha = 0.86f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    )
    val cardTitle = TextStyle(
        color = CoogText,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    )
    val cardYear = TextStyle(
        color = CoogTextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 14.sp,
    )
    val screenTitle = TextStyle(
        color = CoogText,
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        lineHeight = 32.sp,
    )
}

private val CoogDark = darkColorScheme(
    primary = CoogFocus,
    onPrimary = Color(0xFF121214),
    secondary = Color(0xFFD0D0D4),
    surface = CoogBgMid,
    onSurface = CoogOnSurface,
    surfaceVariant = CoogBgSoft,
    onSurfaceVariant = Color(0xB8FFFFFF),
    background = CoogBgDeep,
    onBackground = CoogOnSurface,
    error = CoogDanger,
    border = Color(0x1FFFFFFF),
)

@Composable
fun CoogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CoogDark, content = content)
}
