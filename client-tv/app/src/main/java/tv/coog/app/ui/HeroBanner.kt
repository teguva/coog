package tv.coog.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogType

@Composable
fun TitleLockup(
    item: MediaItem,
    modifier: Modifier = Modifier,
    logoHeight: Dp = 72.dp,
    /** When set, logo is capped to this width and [logoHeight] (constant billboard size). */
    logoMaxWidth: Dp? = null,
    titleStyle: TextStyle = CoogType.heroTitle,
    /** false → text title only (defer logo decode while D-pad is still moving). */
    preferLogo: Boolean = true,
    /** Catalog/media logo tier — thumb for shelf cards, display for hero. */
    logoSize: String = "thumb",
) {
    val server = LocalCoogServer.current
    val density = LocalDensity.current.density
    val resolved = remember(item.id, item.logoUrl, item.imdbId, item.libraryId, server.url, logoSize) {
        resolvedLogoUrl(item, server, logoSize)
    }
    // Sticky URL: first preferLogo load sticks through hops so logos don't blink off on settle.
    var stickyLogo by remember(item.id) { mutableStateOf("") }
    if (preferLogo && resolved.isNotBlank()) stickyLogo = resolved
    val logo = stickyLogo.ifBlank { if (preferLogo) resolved else "" }
    var logoFailed by remember(logo) { mutableStateOf(false) }
    if (logo.isNotBlank() && !logoFailed) {
        val logoMod = if (logoMaxWidth != null) {
            modifier
                .height(logoHeight)
                .widthIn(max = logoMaxWidth)
        } else {
            modifier.fillMaxWidth().height(logoHeight)
        }
        val decodeH = (logoHeight.value * density).toInt().coerceIn(48, 960)
        val maxWDp = logoMaxWidth?.value ?: (logoHeight.value * 4f)
        val decodeW = (maxWDp * density).toInt().coerceIn(decodeH, 1280)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(logo)
                .size(decodeW, decodeH)
                .apply {
                    if (server.token.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${server.token}")
                    }
                }
                // No crossfade — shelf logos should appear as soon as Coil has the thumb.
                .crossfade(0)
                .build(),
            contentDescription = item.headline(),
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = logoMod,
        )
        return
    }
    Text(
        item.headline(),
        style = titleStyle.copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.65f), Offset.Zero, 16f),
        ),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = if (logoMaxWidth != null) {
            modifier.widthIn(max = logoMaxWidth)
        } else {
            modifier
        },
    )
}
