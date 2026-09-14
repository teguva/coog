package tv.coog.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.tv.material3.Text
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogType

@Composable
fun MediaLoadingScreen(
    item: MediaItem?,
    title: String,
    modifier: Modifier = Modifier,
    backdrop: Boolean = true,
) {
    val pulse = rememberInfiniteTransition(label = "mediaLoad")
    val alpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "logoAlpha",
    )
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
        label = "logoScale",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(CoogBgDeep),
        contentAlignment = Alignment.Center,
    ) {
        if (backdrop && item != null) {
            PosterArt(
                item = item,
                kind = ArtKind.Backdrop,
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.42f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.58f)),
                        ),
                    ),
            )
        }
        LoadingLockup(
            item = item,
            title = title,
            modifier = Modifier
                .alpha(alpha)
                .scale(scale)
                .padding(horizontal = 48.dp)
                .widthIn(max = 520.dp)
                .heightIn(max = 220.dp),
        )
    }
}

@Composable
private fun LoadingLockup(
    item: MediaItem?,
    title: String,
    modifier: Modifier = Modifier,
) {
    val server = LocalCoogServer.current
    var logoFailed by remember(item?.id, item?.logoUrl, server.url) { mutableStateOf(false) }
    val logo = item?.let { resolvedLogoUrl(it, server) }.orEmpty()
    val label = item?.headline()?.ifBlank { title } ?: title
    if (logo.isNotBlank() && !logoFailed) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(logo)
                    .apply {
                        if (server.token.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${server.token}")
                        }
                    }
                    .crossfade(180)
                    .build(),
                contentDescription = label,
                contentScale = ContentScale.Fit,
                onError = { logoFailed = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 180.dp)
                    .alpha(0.22f),
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(logo)
                    .apply {
                        if (server.token.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${server.token}")
                        }
                    }
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onError = { logoFailed = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp, max = 180.dp),
            )
        }
        return
    }
    Text(
        label,
        style = CoogType.heroTitle.copy(
            shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 2f), 18f),
        ),
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

internal fun resolvedLogoUrl(item: MediaItem, server: CoogServer): String {
    val direct = item.logoUrl.trim()
    if (direct.startsWith("http")) return direct
    if (direct.isNotBlank()) {
        // Relative API path from older payloads.
        return if (direct.startsWith("/")) {
            server.url.trimEnd('/') + direct
        } else {
            direct
        }
    }
    if (server.url.isBlank()) return ""
    val disk = item.diskMediaId()
    if (disk.isNotBlank()) {
        return server.logoUrl(disk, item.imdbId.ifBlank { "none" })
    }
    val imdb = item.imdbId.trim()
    if (imdb.startsWith("tt")) {
        val catalogId = item.id.takeIf { it.startsWith("catalog:") }
            ?: if (item.kind == "episode" || item.kind == "series") {
                "catalog:$imdb:1:1"
            } else {
                "catalog:$imdb"
            }
        return server.logoUrl(catalogId, imdb)
    }
    val id = item.playableId().ifBlank { item.id }
    if (id.isBlank()) return ""
    return server.logoUrl(id, item.imdbId.ifBlank { "none" })
}
