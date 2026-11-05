package tv.coog.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import coil.Coil
import coil.request.CachePolicy
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import tv.coog.app.data.MediaItem

/** Prefetch window around the focused shelf card (selected ± [radius]). */
internal const val ArtPrefetchRadius = 4

/** Wait for hold-scroll to settle before hitting the network. */
internal const val ArtPrefetchSettleMs = 180L

/**
 * Warm Coil disk/memory with server **thumb** art for [items] around [selectedId].
 * Landscape immersive rows prefetch backdrop thumbs for the full radius (cards show backdrops).
 * Portrait shelves prefetch poster thumbs.
 * [alsoPosters] — Continue row peeks are posters while the settled billboard is backdrop.
 */
@Composable
internal fun PrefetchShelfThumbs(
    items: List<MediaItem>,
    selectedId: String,
    landscape: Boolean,
    enabled: Boolean = true,
    alsoPosters: Boolean = false,
    radius: Int = ArtPrefetchRadius,
    settleMs: Long = ArtPrefetchSettleMs,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val idsKey = remember(items) { items.joinToString { it.id } }
    LaunchedEffect(enabled, selectedId, server.url, server.token, idsKey, landscape, alsoPosters) {
        if (!enabled || server.url.isBlank() || items.isEmpty()) return@LaunchedEffect
        delay(settleMs)
        val i = items.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val from = (i - radius).coerceAtLeast(0)
        val to = (i + radius).coerceAtMost(items.lastIndex)
        withContext(Dispatchers.IO) {
            val loader = Coil.imageLoader(context)
            val gate = Semaphore(3)
            coroutineScope {
                val jobs = buildList {
                    for (j in from..to) {
                        val item = items[j]
                        val primary = shelfThumbUrl(item, server, preferBackdrop = landscape)
                        if (primary.isNotBlank()) {
                            add(
                                async {
                                    gate.withPermit {
                                        runCatching {
                                            loader.execute(
                                                thumbRequest(
                                                    context,
                                                    primary,
                                                    if (landscape) 480 else 300,
                                                    server.token,
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        if (alsoPosters && landscape) {
                            val poster = shelfThumbUrl(item, server, preferBackdrop = false)
                            if (poster.isNotBlank() && poster != primary) {
                                add(
                                    async {
                                        gate.withPermit {
                                            runCatching {
                                                loader.execute(
                                                    thumbRequest(context, poster, 300, server.token),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        // Landscape billboards also show logo thumbs — warm with the plate.
                        if (landscape) {
                            val logo = shelfLogoThumbUrl(item, server)
                            if (logo.isNotBlank()) {
                                add(
                                    async {
                                        gate.withPermit {
                                            runCatching {
                                                loader.execute(
                                                    thumbRequest(context, logo, 320, server.token),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }
    }
}

private fun thumbRequest(
    context: android.content.Context,
    url: String,
    size: Int,
    token: String,
): ImageRequest {
    return ImageRequest.Builder(context)
        .data(url)
        .size(size)
        .bitmapConfig(Bitmap.Config.RGB_565)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .apply {
            if (token.isNotBlank()) {
                addHeader("Authorization", "Bearer $token")
            }
        }
        .build()
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
            val gate = Semaphore(3)
            coroutineScope {
                (from..to).map { j ->
                    async {
                        val url = shelfThumbUrl(items[j], server, preferBackdrop = false)
                        if (url.isBlank()) return@async
                        gate.withPermit {
                            runCatching {
                                loader.execute(thumbRequest(context, url, 160, server.token))
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }
}

/**
 * Same as [PrefetchBrowseThumbs] but reads focus from a ref so updating the index
 * does not recompose the Browse grid on every D-pad hop.
 *
 * Prefetches [rowsBehind] above and [rowsAhead] below the focus (bias down for hold-scroll).
 * [holdingRef] shortens settle so ahead-of-scroll thumbs warm during a continuous Down hold.
 */
@Composable
internal fun PrefetchBrowseThumbsRef(
    items: List<MediaItem>,
    focusedIndexRef: IntArray,
    columns: Int,
    enabled: Boolean = true,
    rowsBehind: Int = 1,
    rowsAhead: Int = 3,
    settleMs: Long = 90L,
    holdSettleMs: Long = 40L,
    holdingRef: BooleanArray? = null,
    decodePx: Int = 300,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val itemsState = rememberUpdatedState(items)
    val cols = columns.coerceAtLeast(1)
    // Do not key on items.size — every append used to cancel/restart the warm loop mid-hold.
    LaunchedEffect(enabled, cols, server.url, server.token, decodePx, rowsBehind, rowsAhead) {
        if (!enabled || server.url.isBlank()) return@LaunchedEffect
        var lastIndex = -1
        var lastSize = -1
        var lastWarmAt = 0L
        while (true) {
            if (itemsState.value.isEmpty()) {
                delay(settleMs)
                continue
            }
            val holding = holdingRef?.getOrNull(0) == true
            // Mid-hold: poll slower and warm less — Coil.execute competed with on-screen decode.
            delay(if (holding) holdSettleMs.coerceAtLeast(90L) else settleMs)
            val live = itemsState.value
            if (live.isEmpty()) continue
            val i = focusedIndexRef[0].coerceIn(0, live.lastIndex)
            val now = android.os.SystemClock.elapsedRealtime()
            val moved = i != lastIndex
            val grew = live.size != lastSize
            // During hold, only re-warm when focus advanced ≥1 row or list grew (throttled).
            val rowsMoved = if (lastIndex < 0) Int.MAX_VALUE else kotlin.math.abs(i - lastIndex) / cols
            val holdRefresh = holding && grew && now - lastWarmAt >= 280L
            val holdMovedEnough = holding && moved && rowsMoved >= 1 && now - lastWarmAt >= 120L
            if (holding) {
                if (!holdRefresh && !holdMovedEnough) continue
            } else if (!moved && !grew) {
                continue
            }
            if (!holding && moved) {
                delay(settleMs)
                if (focusedIndexRef[0] != i) continue
            }
            lastIndex = i
            lastSize = live.size
            lastWarmAt = now
            val list = itemsState.value
            if (list.isEmpty()) continue
            // Hold: bias one row ahead only — visible tiles already request their own art.
            val ahead = if (holding) 1 else rowsAhead
            val behind = if (holding) 0 else rowsBehind
            val from = (i - behind * cols).coerceAtLeast(0)
            val to = (i + ahead * cols).coerceAtMost(list.lastIndex)
            warmBrowsePosterThumbs(
                context = context,
                server = server,
                items = list,
                from = from,
                to = to,
                decodePx = decodePx,
            )
        }
    }
}

/** Warm Coil for a pending Browse page before it is committed into the grid. */
internal suspend fun warmBrowsePosterThumbs(
    context: android.content.Context,
    server: CoogServer,
    items: List<MediaItem>,
    from: Int = 0,
    to: Int = items.lastIndex,
    decodePx: Int = 180,
) {
    if (server.url.isBlank() || items.isEmpty()) return
    val start = from.coerceIn(0, items.lastIndex)
    val end = to.coerceIn(0, items.lastIndex)
    if (end < start) return
    withContext(Dispatchers.IO) {
        val loader = Coil.imageLoader(context)
        val size = decodePx.coerceIn(120, 160)
        val gate = Semaphore(3)
        coroutineScope {
            (start..end).map { j ->
                async {
                    val url = shelfThumbUrl(items[j], server, preferBackdrop = false)
                    if (url.isBlank()) return@async
                    gate.withPermit {
                        runCatching {
                            loader.execute(thumbRequest(context, url, size, server.token))
                        }
                    }
                }
            }.awaitAll()
        }
    }
}

private fun shelfLogoThumbUrl(item: MediaItem, server: CoogServer): String {
    val remote = item.logoUrl.trim()
    when {
        remote.contains("/api/v1/catalog/art/") ||
            (remote.contains("/api/v1/media/") && remote.contains("/logo")) -> {
            val replaced = Regex("""([?&])size=[^&]*""").replace(remote) { m ->
                "${m.groupValues[1]}size=thumb"
            }
            return if (replaced != remote) replaced
            else if (remote.contains("?")) "$remote&size=thumb" else "$remote?size=thumb"
        }
        remote.startsWith("http") -> return remote
        item.imdbId.startsWith("tt") && server.url.isNotBlank() ->
            return server.catalogLogoUrl(item.imdbId, "thumb")
        item.id.isNotBlank() && server.url.isNotBlank() ->
            return server.logoUrl(item.id, item.imdbId.ifBlank { "none" }, "thumb")
        else -> return ""
    }
}

/** Browse/Discover poster URL — same thumb resolver Stremio MetaItem uses (plain URL → Coil). */
internal fun browsePosterThumbUrl(item: MediaItem, server: CoogServer): String =
    shelfThumbUrl(item, server, preferBackdrop = false)

private fun shelfThumbUrl(item: MediaItem, server: CoogServer, preferBackdrop: Boolean): String {
    fun withSize(url: String, size: String): String {
        if (url.isBlank()) return ""
        val replaced = Regex("""([?&])size=[^&]*""").replace(url) { m -> "${m.groupValues[1]}size=$size" }
        if (replaced != url) return replaced
        return if (url.contains("?")) "$url&size=$size" else "$url?size=$size"
    }
    fun paintReady(remote: String, inventCatalog: () -> String, inventMedia: () -> String): String {
        when {
            remote.contains("/api/v1/catalog/art/") ||
                (remote.contains("/api/v1/media/") &&
                    (remote.contains("/poster") || remote.contains("/backdrop") || remote.contains("/artwork"))) ->
                return withSize(remote, "thumb")
            remote.startsWith("http") ->
                return remote
            remote.isBlank() && item.imdbId.startsWith("tt") ->
                return inventCatalog()
            item.id.isNotBlank() ->
                return withSize(inventMedia(), "thumb")
            else -> return ""
        }
    }
    return if (preferBackdrop) {
        paintReady(
            item.backdropUrl.ifBlank { item.posterUrl },
            inventCatalog = { server.catalogBackdropUrl(item.imdbId, "thumb") },
            inventMedia = { server.backdropUrl(item.id, item.imdbId.ifBlank { "none" }) },
        )
    } else {
        paintReady(
            item.posterUrl,
            inventCatalog = { server.catalogPosterUrl(item.imdbId) },
            inventMedia = { server.posterUrl(item.id, item.imdbId.ifBlank { "none" }) },
        )
    }
}
