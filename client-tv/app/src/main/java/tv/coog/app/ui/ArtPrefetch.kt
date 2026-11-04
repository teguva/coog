package tv.coog.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.coog.app.data.MediaItem

/** Prefetch window around the focused shelf card (selected ± [radius]). */
internal const val ArtPrefetchRadius = 2

/** Wait for hold-scroll to settle before hitting the network. */
internal const val ArtPrefetchSettleMs = 180L

/**
 * Warm Coil disk/memory with server **thumb** art for [items] around [selectedId].
 * Landscape immersive rows prefetch backdrop thumbs for the full radius (cards show backdrops).
 * Portrait shelves prefetch poster thumbs.
 */
@Composable
internal fun PrefetchShelfThumbs(
    items: List<MediaItem>,
    selectedId: String,
    landscape: Boolean,
    enabled: Boolean = true,
    radius: Int = ArtPrefetchRadius,
    settleMs: Long = ArtPrefetchSettleMs,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val idsKey = remember(items) { items.joinToString { it.id } }
    LaunchedEffect(enabled, selectedId, server.url, server.token, idsKey, landscape) {
        if (!enabled || server.url.isBlank() || items.isEmpty()) return@LaunchedEffect
        delay(settleMs)
        val i = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val from = (i - radius).coerceAtLeast(0)
        val to = (i + radius).coerceAtMost(items.lastIndex)
        withContext(Dispatchers.IO) {
            val loader = Coil.imageLoader(context)
            for (j in from..to) {
                val item = items[j]
                val url = shelfThumbUrl(item, server, preferBackdrop = landscape)
                if (url.isBlank()) continue
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .size(if (landscape) 480 else 300)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .apply {
                        if (server.token.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${server.token}")
                        }
                    }
                    .build()
                runCatching { loader.execute(req) }
            }
        }
    }
}

/** Browse grid: warm poster thumbs ±[rowRadius] rows around [focusedIndex] after settle. */
@Composable
internal fun PrefetchBrowseThumbs(
    items: List<MediaItem>,
    focusedIndex: Int,
    columns: Int,
    enabled: Boolean = true,
    rowRadius: Int = 1,
    settleMs: Long = 150L,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val idsKey = remember(items) { items.joinToString { it.playableId().ifBlank { it.id } } }
    val cols = columns.coerceAtLeast(1)
    LaunchedEffect(enabled, focusedIndex, cols, server.url, server.token, idsKey) {
        if (!enabled || server.url.isBlank() || items.isEmpty()) return@LaunchedEffect
        delay(settleMs)
        val i = focusedIndex.coerceIn(0, items.lastIndex)
        val from = (i - rowRadius * cols).coerceAtLeast(0)
        val to = (i + rowRadius * cols).coerceAtMost(items.lastIndex)
        withContext(Dispatchers.IO) {
            val loader = Coil.imageLoader(context)
            for (j in from..to) {
                val url = shelfThumbUrl(items[j], server, preferBackdrop = false)
                if (url.isBlank()) continue
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .size(300)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .apply {
                        if (server.token.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${server.token}")
                        }
                    }
                    .build()
                runCatching { loader.execute(req) }
            }
        }
    }
}

/**
 * Same as [PrefetchBrowseThumbs] but reads focus from a ref so updating the index
 * does not recompose the Browse grid on every D-pad hop.
 */
@Composable
internal fun PrefetchBrowseThumbsRef(
    items: List<MediaItem>,
    focusedIndexRef: IntArray,
    columns: Int,
    enabled: Boolean = true,
    rowRadius: Int = 1,
    settleMs: Long = 150L,
    decodePx: Int = 300,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val idsKey = remember(items) { items.joinToString { it.playableId().ifBlank { it.id } } }
    val cols = columns.coerceAtLeast(1)
    LaunchedEffect(enabled, cols, server.url, server.token, idsKey, decodePx) {
        if (!enabled || server.url.isBlank() || items.isEmpty()) return@LaunchedEffect
        var last = -1
        while (true) {
            delay(settleMs)
            if (items.isEmpty()) continue
            val i = focusedIndexRef[0].coerceIn(0, items.lastIndex)
            if (i == last) continue
            delay(settleMs)
            if (focusedIndexRef[0] != i) continue
            last = i
            val from = (i - rowRadius * cols).coerceAtLeast(0)
            val to = (i + rowRadius * cols).coerceAtMost(items.lastIndex)
            withContext(Dispatchers.IO) {
                val loader = Coil.imageLoader(context)
                for (j in from..to) {
                    val url = shelfThumbUrl(items[j], server, preferBackdrop = false)
                    if (url.isBlank()) continue
                    val req = ImageRequest.Builder(context)
                        .data(url)
                        .size(decodePx.coerceIn(120, 300))
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .apply {
                            if (server.token.isNotBlank()) {
                                addHeader("Authorization", "Bearer ${server.token}")
                            }
                        }
                        .build()
                    runCatching { loader.execute(req) }
                }
            }
        }
    }
}

private fun shelfThumbUrl(item: MediaItem, server: CoogServer, preferBackdrop: Boolean): String {
    fun withSize(url: String, size: String): String {
        if (url.isBlank()) return ""
        val replaced = Regex("""([?&])size=[^&]*""").replace(url) { m -> "${m.groupValues[1]}size=$size" }
        if (replaced != url) return replaced
        return if (url.contains("?")) "$url&size=$size" else "$url?size=$size"
    }
    if (preferBackdrop) {
        if (item.imdbId.startsWith("tt")) {
            return server.catalogBackdropUrl(item.imdbId, "thumb")
        }
        val remote = item.backdropUrl.ifBlank { item.posterUrl }
        when {
            remote.contains("/api/v1/catalog/art/") ||
                (remote.contains("/api/v1/media/") && remote.contains("/backdrop")) ->
                return withSize(remote, "thumb")
            remote.startsWith("http") && !remote.contains("/api/v1/media/") ->
                return remote
            item.id.isNotBlank() ->
                return withSize(server.backdropUrl(item.id, item.imdbId.ifBlank { "none" }), "thumb")
        }
    }
    if (item.imdbId.startsWith("tt")) {
        return server.catalogPosterUrl(item.imdbId)
    }
    val remote = item.posterUrl
    return when {
        remote.contains("/api/v1/catalog/art/") ||
            (remote.contains("/api/v1/media/") && remote.contains("/poster")) ->
            withSize(remote, "thumb")
        remote.startsWith("http") && !remote.contains("/api/v1/media/") ->
            remote
        item.id.isNotBlank() ->
            withSize(server.posterUrl(item.id, item.imdbId.ifBlank { "none" }), "thumb")
        else -> ""
    }
}
