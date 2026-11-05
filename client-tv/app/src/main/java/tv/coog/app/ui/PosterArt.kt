package tv.coog.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import android.graphics.Bitmap
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Scale
import tv.coog.app.data.MediaItem

/** Flat plate behind AsyncImage — no per-title gradient while art loads. */
private val ArtPlaceholder = Color.White.copy(alpha = 0.06f)

enum class ArtKind { Poster, Backdrop, Still }

/**
 * Server art tiers (`?size=`):
 * - **thumb** — row / unfocused cards (poster ≤300, backdrop ≤480 long edge)
 * - **display** — hero / trailer stage (backdrop capped by preferredBackdropMax)
 */
@Composable
fun PosterArt(
    item: MediaItem,
    modifier: Modifier = Modifier,
    kind: ArtKind = ArtKind.Poster,
    mark: CardMark? = null,
    matchPercent: Int? = null,
    showMatch: Boolean = false,
    marksSize: MarkSize = MarkSize.Compact,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = if (kind == ArtKind.Backdrop) Alignment.CenterEnd else Alignment.Center,
    serverFallback: Boolean = true,
    /**
     * true → size=display (hero / trailer stage).
     * false → size=thumb only for shelf cards (no progressive display upgrade).
     */
    preferDisplay: Boolean = false,
    /** Coil crossfade; 0 for Browse tiles during rapid D-pad scroll. */
    crossfadeMs: Int = -1,
    /** When > 0, caps poster/still decode width (px) for tight Browse cards. */
    decodeMaxPx: Int = 0,
) {
    val server = LocalCoogServer.current
    var failed by remember(item.id, kind, server.url, item.posterUrl, item.backdropUrl, preferDisplay) {
        mutableStateOf(false)
    }
    val resolvedCrossfade = if (crossfadeMs >= 0) {
        crossfadeMs
    } else if (preferDisplay) {
        120
    } else {
        80
    }
    val cacheKey = item.imdbId.ifBlank { "none" }
    val remote = when (kind) {
        ArtKind.Poster -> item.posterUrl
        ArtKind.Backdrop, ArtKind.Still -> item.backdropUrl.ifBlank { item.posterUrl }
    }
    // Invent Coog catalog URLs only when JSON has no paint-ready remote (CDN or rewritten).
    val catalogRemote = if (item.imdbId.startsWith("tt") && server.url.isNotBlank()) {
        when (kind) {
            ArtKind.Poster -> server.catalogPosterUrl(item.imdbId)
            ArtKind.Backdrop, ArtKind.Still ->
                server.catalogBackdropUrl(item.imdbId, if (preferDisplay) "display" else "thumb")
        }
    } else {
        ""
    }
    // Trust server rewrite: warm Coog/media URLs or Stremio-style CDN; never force cold /catalog/art.
    val baseUrl = when {
        remote.startsWith("http") -> remote
        !serverFallback -> ""
        remote.isBlank() && catalogRemote.isNotBlank() -> catalogRemote
        kind == ArtKind.Poster -> server.posterUrl(item.id, cacheKey)
        else -> server.backdropUrl(item.id, cacheKey)
    }
    val canTier = baseUrl.contains("/api/v1/catalog/art/") ||
        baseUrl.contains("/api/v1/media/") && (baseUrl.contains("/poster") || baseUrl.contains("/backdrop"))
    val thumbUrl = if (canTier) artSizeUrl(baseUrl, "thumb") else ""
    val displayUrl = if (canTier) artSizeUrl(baseUrl, "display") else baseUrl
    // Shelves stay on thumb; heroes show thumb first then upgrade to display.
    val primaryUrl = when {
        preferDisplay -> displayUrl.ifBlank { baseUrl }
        else -> thumbUrl.ifBlank { displayUrl.ifBlank { baseUrl } }
    }
    val progressiveThumb = preferDisplay && canTier && thumbUrl.isNotBlank() && thumbUrl != primaryUrl
    val (decodeW, decodeH) = rememberArtPixels(
        kind,
        preferDisplay,
        server.backdropDisplayMax,
        decodeMaxPx = decodeMaxPx,
    )
    val (thumbW, thumbH) = rememberArtPixels(
        kind,
        preferDisplay = false,
        server.backdropDisplayMax,
        decodeMaxPx = decodeMaxPx,
    )
    Box(modifier = modifier.background(ArtPlaceholder)) {
        if (progressiveThumb && !failed) {
            AsyncImage(
                model = artRequest(
                    thumbUrl,
                    server.token,
                    thumbW,
                    thumbH,
                    crossfadeMs = 60,
                    thumbMemory = true,
                ),
                contentDescription = null,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (primaryUrl.isNotBlank() && !failed) {
            AsyncImage(
                model = artRequest(
                    primaryUrl,
                    server.token,
                    decodeW,
                    decodeH,
                    crossfadeMs = if (progressiveThumb) resolvedCrossfade.coerceAtLeast(60) else resolvedCrossfade,
                    // Display/hero plates keep full color; shelf thumbs use RGB_565.
                    thumbMemory = !preferDisplay,
                ),
                contentDescription = item.headline(),
                contentScale = contentScale,
                alignment = alignment,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (failed || server.url.isBlank()) {
            if (kind == ArtKind.Poster) {
                Text(
                    text = item.monogram(),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f),
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        CardMarks(
            mark = mark,
            matchPercent = matchPercent,
            size = marksSize,
            showMatch = showMatch,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        )
    }
}

private fun artSizeUrl(url: String, size: String): String {
    if (url.isBlank()) return url
    val replaced = Regex("""([?&])size=[^&]*""").replace(url) { m -> "${m.groupValues[1]}size=$size" }
    if (replaced != url) return replaced
    return if (url.contains("?")) "$url&size=$size" else "$url?size=$size"
}

@Composable
private fun artRequest(
    url: String,
    token: String,
    decodeW: Int,
    decodeH: Int,
    crossfadeMs: Int = 80,
    /** Shelf/Browse thumbs — half the heap vs ARGB_8888; cuts MiTV GC stalls. */
    thumbMemory: Boolean = true,
): ImageRequest {
    return ImageRequest.Builder(LocalContext.current)
        .data(url)
        .size(decodeW, decodeH)
        .scale(Scale.FILL)
        .apply {
            if (token.isNotBlank()) {
                addHeader("Authorization", "Bearer $token")
            }
            if (thumbMemory) {
                bitmapConfig(Bitmap.Config.RGB_565)
            }
        }
        .crossfade(crossfadeMs.coerceAtLeast(0))
        .build()
}

@Composable
private fun rememberArtPixels(
    kind: ArtKind,
    preferDisplay: Boolean,
    backdropDisplayMax: Int = 1920,
    decodeMaxPx: Int = 0,
): Pair<Int, Int> {
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    val maxW = backdropDisplayMax.coerceIn(960, 3840)
    val cap = decodeMaxPx.coerceAtLeast(0)
    return remember(kind, preferDisplay, config.screenWidthDp, config.screenHeightDp, density, maxW, cap) {
        when (kind) {
            ArtKind.Poster -> {
                // Match on-screen Browse/Continue peek width — oversizing fed MiTV GC (logs).
                val raw = (config.screenWidthDp * 0.14f * density).toInt().coerceIn(120, 220)
                val w = if (cap > 0) raw.coerceAtMost(cap).coerceAtLeast(120) else raw
                w to (w * 1.5f).toInt()
            }
            ArtKind.Still -> {
                // Card-sized landscape (~server backdrop thumb ≤480).
                val raw = (config.screenWidthDp * 0.22f * density).toInt().coerceIn(240, 480)
                val w = if (cap > 0) raw.coerceAtMost(cap).coerceAtLeast(200) else raw
                w to (w * 9 / 16)
            }
            ArtKind.Backdrop -> {
                if (preferDisplay) {
                    val screenW = (config.screenWidthDp * density).toInt()
                    val w = screenW.coerceIn(960, maxW)
                    val h = (config.screenHeightDp * 0.62f * density).toInt()
                        .coerceIn(420, (maxW * 9 / 16).coerceAtLeast(540))
                    w to h
                } else {
                    // Match server backdrop thumb long-edge ≤480 for horizontal cards.
                    val raw = (config.screenWidthDp * 0.28f * density).toInt().coerceIn(280, 480)
                    val w = if (cap > 0) raw.coerceAtMost(cap).coerceAtLeast(240) else raw
                    w to (w * 9 / 16)
                }
            }
        }
    }
}
