package tv.coog.app.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
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

/**
 * Prefetch window around the focused shelf card after settle.
 * Alder warms ±1 only when not held — keep this tight so Coil stays free.
 */
internal const val ArtPrefetchRadius = 2

/** Neighbor rows wait briefly so the focused row's warm wins the Ahead pool. */
internal const val ArtPrefetchSettleMs = 80L

/**
 * How aggressively a Home row warms Coil.
 *
 * Mirrors Alder: focused row loads a screenful (+ a bit); neighbours only what would
 * be on-screen next; far rows idle. Warming uses Ahead priority + low concurrency so
 * it never starves on-screen decode.
 */
internal enum class HomeArtPrefetch {
    /** Active row — warm around selection (visible + ahead). */
    Focused,

    /** Prev/next peek row — warm only the visible strip from its selection. */
    Neighbor,

    /** Off-screen — skip. */
    Off,
}

/** ~screenful of 16:9 Home cards (plus one past the edge, like Alder FIRST). */
internal const val HomeVisibleSlots = 5

/** Extra cards ahead of focus on the active row (Alder FIRST≈10 total). */
internal const val HomeFocusedAhead = 5

/**
 * Warm Coil disk/memory with server **thumb** art for [items] around [selectedId].
 *
 * Landscape rows: backdrop thumb + logo for each slot in the window.
 * [alsoPosters] — Continue: also warm poster thumbs for peek cards in the window.
 * [tier] — focus-aware budget (Focused / Neighbor / Off).
 *
 * Focused warms immediately (no settle gate) so Home-open / first Continue hop
 * hit memory cache; neighbors stagger slightly.
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
    tier: HomeArtPrefetch = HomeArtPrefetch.Focused,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val idsKey = remember(items) {
        "${items.size}:${items.firstOrNull()?.id.orEmpty()}:${items.lastOrNull()?.id.orEmpty()}"
    }
    val itemsState = rememberUpdatedState(items)
    LaunchedEffect(
        enabled,
        selectedId,
        server.url,
        server.token,
        idsKey,
        landscape,
        alsoPosters,
        radius,
        tier,
        settleMs,
    ) {
        if (!enabled || tier == HomeArtPrefetch.Off || server.url.isBlank()) return@LaunchedEffect
        // Never fight a horizontal hold glide / Browse hold.
        if (BrowseScrollGate.holding) return@LaunchedEffect
        // Focused: start now (Home open). Neighbor: short stagger.
        when (tier) {
            HomeArtPrefetch.Focused -> Unit
            HomeArtPrefetch.Neighbor -> delay(settleMs)
            HomeArtPrefetch.Off -> return@LaunchedEffect
        }
        if (BrowseScrollGate.holding) return@LaunchedEffect
        val live = itemsState.value
        if (live.isEmpty()) return@LaunchedEffect
        val i = live.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        val (from, to) = when (tier) {
            HomeArtPrefetch.Focused -> {
                val a = (i - HomeVisibleSlots / 2).coerceAtLeast(0)
                val b = (i + HomeFocusedAhead).coerceAtMost(live.lastIndex)
                a to b
            }
            HomeArtPrefetch.Neighbor -> {
                val a = i.coerceIn(0, live.lastIndex)
                val b = (a + HomeVisibleSlots - 1).coerceAtMost(live.lastIndex)
                a to b
            }
            HomeArtPrefetch.Off -> return@LaunchedEffect
        }
        warmHomeShelfRange(
            context = context,
            server = server,
            items = live,
            from = from,
            to = to,
            landscape = landscape,
            alsoPosters = alsoPosters,
            concurrency = if (tier == HomeArtPrefetch.Focused) 3 else 2,
        )
    }
}

/**
 * Home-open bootstrap: Continue + first discovery strip (backdrop/logo[/poster])
 * before the user holds Right.
 */
internal suspend fun warmHomeOpenThumbs(
    context: android.content.Context,
    server: CoogServer,
    continueItems: List<MediaItem>,
    firstDiscovery: List<MediaItem>,
) {
    if (server.url.isBlank()) return
    if (continueItems.isNotEmpty()) {
        val to = (HomeVisibleSlots + HomeFocusedAhead - 1).coerceAtMost(continueItems.lastIndex)
        warmHomeShelfRange(
            context = context,
            server = server,
            items = continueItems,
            from = 0,
            to = to,
            landscape = true,
            alsoPosters = true,
            concurrency = 4,
        )
    }
    if (firstDiscovery.isNotEmpty()) {
        val to = (HomeVisibleSlots - 1).coerceAtMost(firstDiscovery.lastIndex)
        warmHomeShelfRange(
            context = context,
            server = server,
            items = firstDiscovery,
            from = 0,
            to = to,
            landscape = true,
            alsoPosters = false,
            concurrency = 2,
        )
    }
}

private suspend fun warmHomeShelfRange(
    context: android.content.Context,
    server: CoogServer,
    items: List<MediaItem>,
    from: Int,
    to: Int,
    landscape: Boolean,
    alsoPosters: Boolean,
    concurrency: Int,
) {
    if (to < from || items.isEmpty()) return
    withContext(Dispatchers.IO) {
        val loader = context.coogThumbLoader()
        val gate = Semaphore(concurrency.coerceIn(1, 4))
        coroutineScope {
            val jobs = buildList {
                for (j in from..to) {
                    if (BrowseScrollGate.holding) break
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

/** Shelf/Home warm — RGB_565 keeps heap small; not shared with Browse HW tiles. */
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
        .coogPriority(CoogArtPriority.Ahead)
        .apply {
            if (token.isNotBlank()) {
                addHeader("Authorization", "Bearer $token")
            }
        }
        .build()
}

/**
 * Browse warm must match [BrowsePosterArt]: same size + HARDWARE so Coil memory hits.
 */
@Composable
internal fun PrefetchBrowseThumbs(
    items: List<MediaItem>,
    focusedIndex: Int,
    columns: Int,
    enabled: Boolean = true,
    rowsBehind: Int = 1,
    rowsAhead: Int = 2,
    settleMs: Long = 90L,
    decodePx: Int = 300,
) {
    val focusedIndexRef = remember { intArrayOf(focusedIndex) }
    focusedIndexRef[0] = focusedIndex
    PrefetchBrowseThumbsRef(
        items = items,
        focusedIndexRef = focusedIndexRef,
        columns = columns,
        enabled = enabled,
        rowsBehind = rowsBehind,
        rowsAhead = rowsAhead,
        settleMs = settleMs,
        decodePx = decodePx,
    )
}

/**
 * Same as [PrefetchBrowseThumbs] but reads focus from a ref so updating the index
 * does not recompose the Browse grid on every D-pad hop.
 *
 * Hold = no neighbor warm (Alder). Settle warms ±[rowsBehind]/[rowsAhead].
 */
@Composable
internal fun PrefetchBrowseThumbsRef(
    items: List<MediaItem>,
    focusedIndexRef: IntArray,
    columns: Int,
    enabled: Boolean = true,
    rowsBehind: Int = 1,
    rowsAhead: Int = 2,
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
        while (true) {
            if (itemsState.value.isEmpty()) {
                delay(settleMs)
                continue
            }
            val holding = holdingRef?.getOrNull(0) == true
            // Mid-hold: idle — on-screen tiles request their own art; don't fight decode.
            if (holding) {
                delay(holdSettleMs.coerceAtLeast(90L))
                lastIndex = focusedIndexRef[0]
                continue
            }
            delay(settleMs)
            val live = itemsState.value
            if (live.isEmpty()) continue
            val i = focusedIndexRef[0].coerceIn(0, live.lastIndex)
            val moved = i != lastIndex
            val grew = live.size != lastSize
            if (!moved && !grew) continue
            if (moved) {
                delay(settleMs)
                if (focusedIndexRef[0] != i) continue
                if (holdingRef?.getOrNull(0) == true) continue
            }
            lastIndex = i
            lastSize = live.size
            val list = itemsState.value
            if (list.isEmpty()) continue
            val from = (i - rowsBehind * cols).coerceAtLeast(0)
            val to = (i + rowsAhead * cols).coerceAtMost(list.lastIndex)
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
        val loader = context.coogThumbLoader()
        val size = decodePx.coerceIn(120, 280)
        val gate = Semaphore(2)
        coroutineScope {
            (start..end).map { j ->
                async {
                    val url = shelfThumbUrl(items[j], server, preferBackdrop = false)
                    if (url.isBlank()) return@async
                    gate.withPermit {
                        runCatching {
                            loader.execute(browseWarmRequest(context, url, size, server.token))
                        }
                    }
                }
            }.awaitAll()
        }
    }
}

/**
 * Browse warm must match [BrowsePosterArt]: same size + HARDWARE so Coil memory hits.
 */
private fun browseWarmRequest(
    context: android.content.Context,
    url: String,
    size: Int,
    token: String,
): ImageRequest {
    val w = size.coerceIn(120, 280)
    val h = (w * 1.5f).toInt()
    return ImageRequest.Builder(context)
        .data(url)
        .size(w, h)
        .allowHardware(true)
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .coogPriority(CoogArtPriority.Ahead)
        .apply {
            if (token.isNotBlank()) {
                addHeader("Authorization", "Bearer $token")
            }
        }
        .build()
}

/**
 * Alder Images.sized: right-sized CDN bytes before decode.
 * Coog catalog/media ?size= URLs are left alone.
 */
internal fun cdnSizedCardUrl(url: String): String {
    if (url.isBlank()) return url
    if (url.contains("/api/v1/catalog/art/") || url.contains("/api/v1/media/")) return url
    val tmdb = Regex("""(image\.tmdb\.org/t/p/)(original|w\d+)/""")
    if (tmdb.containsMatchIn(url)) {
        return tmdb.replace(url, "$1w780/")
    }
    val metahub = Regex("""(images\.metahub\.space/(?:poster|background)/)(small|medium)/""")
    if (metahub.containsMatchIn(url)) {
        return metahub.replace(url, "$1large/")
    }
    return url
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
                return cdnSizedCardUrl(remote)
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
