package tv.coog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.coog.app.data.CatalogGenre
import tv.coog.app.data.CatalogMood
import tv.coog.app.data.CoogApi
import tv.coog.app.data.JobItem
import tv.coog.app.data.MediaItem
import tv.coog.app.ui.theme.CoogBgDeep
import tv.coog.app.ui.theme.CoogTextMuted
import tv.coog.app.ui.theme.CoogType

/**
 * Opaque chip fills matching NavPill *look* on the dark dock (white@10% / @22% over #121214),
 * but solid so posters do not show through Sort/Filters.
 */
private val BrowseChipIdle = Color(0xFF2A2A2C)
private val BrowseChipActive = Color(0xFF464648)
private val BrowseChipFocusedContent = Color(0xFF121214)

/** We own grid scrolling — default focus bring-into-view jumps first/last rows. */
@OptIn(ExperimentalFoundationApi::class)
private val BrowseNoBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float = 0f
}

private data class SortChip(val id: String, val label: String)

private data class DecadeChip(val label: String, val yearMin: Int, val yearMax: Int)

private data class RatingChip(val label: String, val min: Double)

private data class BrowseGenreChip(
    val key: String,
    val name: String,
    val movieId: Int = 0,
    val seriesId: Int = 0,
)

private data class BrowseHudRow(
    val id: String,
    val label: String,
    val checked: Boolean,
    val checkable: Boolean = true,
    val section: Boolean = false,
)

private data class BrowseSummaryChip(
    val id: String,
    val label: String,
    val onClear: () -> Unit,
)

private val SortChips = listOf(
    SortChip("trending", "Recommended"),
    SortChip("popular", "Popular"),
    SortChip("new", "New"),
    SortChip("rating", "Top rated"),
)

private val TypeChips = listOf(
    SortChip("all", "All"),
    SortChip("movie", "Movies"),
    SortChip("series", "Series"),
)

private val SourceChips = listOf(
    SortChip("all", "All sources"),
    SortChip("stream", "Stream"),
    SortChip("local", "Library"),
)

private val DecadeChips = listOf(
    DecadeChip("Any year", 0, 0),
    DecadeChip("2020s", 2020, 2029),
    DecadeChip("2010s", 2010, 2019),
    DecadeChip("2000s", 2000, 2009),
    DecadeChip("1990s", 1990, 1999),
    DecadeChip("1980s", 1980, 1989),
)

private val RatingChips = listOf(
    RatingChip("Any rating", 0.0),
    RatingChip("6+", 6.0),
    RatingChip("7+", 7.0),
    RatingChip("8+", 8.0),
)

private enum class BrowseFocusRow { Toolbar, Shelf }

private enum class BrowseMenu { None, Sort, Filters }

private val BrowseHudBg = Color(0xE6121214)
private val BrowsePosterShape = RoundedCornerShape(12.dp)

/**
 * Soft caps — high enough for long Browse holds; Lazy grid + thumb decode keep Coil bounded.
 * ~20 cards/TMDB page → 36 pages ≈ 720 raw, capped by items.
 */
private const val BrowseMaxItems = 480
private const val BrowseMaxPage = 36
/**
 * Prefetch ahead of focus. Thumb warm runs off the fetch critical path so the next
 * network page can start as soon as the current batch is buffered (not after Coil).
 */
private const val BrowseCommitSettleMs = 280L
/** Near the end after KeyUp, commit a bit sooner. */
private const val BrowseCommitLastRowsMs = 160L
/**
 * Start network prefetch / allow hold-commit this far from the end (~12 rows @ 9 cols).
 * Must exceed fastest hold coverage across one RTT (~0.5–1s → ~10–18 rows).
 */
private const val BrowsePrefetchRemaining = 108
/**
 * While holding Down, append as soon as remaining is within the prefetch window.
 * Industry pattern: grow the list ahead of the edge so the hold controller never stalls.
 */
private const val BrowseHoldCommitRemaining = BrowsePrefetchRemaining
/** Delay after first KeyDown before hold-accelerate kicks in (taps stay single-step). */
private const val BrowseHoldArmMs = 340L
/**
 * Hold cadence — floor near measured MiTV frame p50 (~40–57ms after opts).
 * Stay above one frame of work so hops do not stack on Main.
 */
private const val BrowseHoldIntervalStartMs = 140L
private const val BrowseHoldIntervalMinMs = 100L
/**
 * TMDB pages per browse hop. Keep modest so each mid-hold commit is a small
 * SnapshotStateList addAll (large replaces stalled Main ~700–1000ms on MiTV).
 */
private const val BrowsePageCount = 2
/** Keep this many pages buffered ahead (fetch while a batch awaits commit). */
private const val BrowsePrefetchDepth = 3
/** Soft-end wait poll — stay alive until KeyUp / true EOF (never abandon the hold). */
private const val BrowseHoldWallPollMs = 60L
/** Animated vertical hop for a single D-pad tap only (hold-scroll stays instant). */
private const val BrowseRowAnimMs = 500

/** Mutable hub so D-pad hops don't feed focusedKey back into the grid as compose state. */
private class BrowseFocusHub {
    var index: Int = 0
    var key: String? = null
    var focusCard: (Int) -> Unit = {}
    @Volatile
    var lastHopAtMs: Long = 0L
    /** True while DirectionUp/Down KeyDown is held — blocks grid list commits. */
    @Volatile
    var holdingVertical: Boolean = false

    fun noteHop() {
        lastHopAtMs = android.os.SystemClock.elapsedRealtime()
    }

    fun msSinceHop(): Long {
        val at = lastHopAtMs
        if (at == 0L) return Long.MAX_VALUE
        return android.os.SystemClock.elapsedRealtime() - at
    }
}

/**
 * Selection index lived as state inside [BrowsePosterGrid] so every hop recomposed all
 * visible tiles. Keep the state object stable; pass [index] as [BrowsePosterTile] `showRing`
 * so only the prev/next tile recomposes (layoutInfo overlay was offset from the card).
 */
private class BrowseSelectionState {
    var index by mutableIntStateOf(-1)
}

/**
 * Prefetch page queue kept off Compose state — writing pendingBatch as `mutableStateOf`
 * recomposed the whole Lazy grid mid-hold (~900ms hops on MiTV).
 * Depth > 1 pipelines the next network hop while a batch awaits commit.
 */
private class BrowsePrefetchBuffer {
    data class Page(val page: Int, val items: List<MediaItem>)

    private val queue = ArrayDeque<Page>(BrowsePrefetchDepth)
    @Volatile
    var inFlight: Boolean = false
    /** Next logical browse page to fetch (1-based; page 1 is the initial commit). */
    @Volatile
    var nextPage: Int = 2

    @Synchronized
    fun clear() {
        queue.clear()
        inFlight = false
        nextPage = 2
    }

    @Synchronized
    fun depth(): Int = queue.size

    @Synchronized
    fun hasBatch(): Boolean = queue.isNotEmpty()

    @Synchronized
    fun offer(page: Int, items: List<MediaItem>) {
        queue.addLast(Page(page, items))
    }

    @Synchronized
    fun poll(): Page? = queue.removeFirstOrNull()
}

@Composable
fun CatalogBrowseScreen(
    jobs: List<JobItem>,
    library: List<MediaItem> = emptyList(),
    onOpen: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)? = null,
    libraryMenuOpen: Boolean = false,
) {
    val server = LocalCoogServer.current
    val context = LocalContext.current
    val inset = catalogInset()
    val scope = rememberCoroutineScope()
    val sortFocus = LocalBrowseContentFocus.current ?: remember { FocusRequester() }
    val filtersFocus = remember { FocusRequester() }
    val shelfFocus = remember { FocusRequester() }
    val hudFocus = remember { FocusRequester() }
    val enterRail = LocalEnterRail.current
    val browseActive = LocalBrowseActive.current
    val reportContentFocusRow = LocalReportContentFocusRow.current
    var browseWasActive by remember { mutableStateOf(browseActive) }
    var focusRow by remember { mutableStateOf(BrowseFocusRow.Toolbar) }
    // Compose-observable hold flag — chrome canFocus must recompose when hold starts/ends.
    var shelfHolding by remember { mutableStateOf(false) }
    val railFocused = LocalNavBarFocused.current
    val railFocusedState = rememberUpdatedState(railFocused)
    var wasRailFocused by remember { mutableStateOf(railFocused) }
    LaunchedEffect(focusRow, browseActive, shelfHolding, railFocused) {
        if (!browseActive || railFocused) {
            // Never collapse the dock while the Browse pill is highlighted — that fought
            // leaveRail/enterRail and made focus jump between chrome and cards.
            reportContentFocusRow(0)
            return@LaunchedEffect
        }
        // Hide AppShell top dock while the poster grid owns focus (Home pattern).
        // shelfHolding covers mid-hold focus loss when the focused card leaves composition.
        val hideDock = focusRow == BrowseFocusRow.Shelf || shelfHolding
        reportContentFocusRow(if (hideDock) 1 else 0)
    }
    // Rail ↔ Browse content handoff: keep Toolbar while on the pill; land on Sort/Filters
    // when Down leaves the rail (never yank focus onto a random card).
    LaunchedEffect(railFocused, browseActive) {
        if (!browseActive) {
            wasRailFocused = railFocused
            return@LaunchedEffect
        }
        if (railFocused) {
            focusRow = BrowseFocusRow.Toolbar
            shelfHolding = false
            wasRailFocused = true
            return@LaunchedEffect
        }
        if (wasRailFocused) {
            wasRailFocused = false
            focusRow = BrowseFocusRow.Toolbar
            kotlinx.coroutines.yield()
            if (railFocusedState.value) return@LaunchedEffect
            runCatching { sortFocus.requestFocus() }
        }
    }
    var browseMenu by remember { mutableStateOf(BrowseMenu.None) }
    var menuSel by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf("trending") }
    var typeFilter by remember { mutableStateOf("all") }
    var sourceFilter by remember { mutableStateOf("all") }
    var selectedGenreKeys by remember { mutableStateOf(listOf<String>()) }
    var genreChips by remember { mutableStateOf(listOf(BrowseGenreChip("all", "All"))) }
    var moods by remember { mutableStateOf<List<CatalogMood>>(emptyList()) }
    var mood by remember { mutableStateOf("") }
    var yearMin by remember { mutableStateOf(0) }
    var yearMax by remember { mutableStateOf(0) }
    var minRating by remember { mutableStateOf(0.0) }
    // SnapshotStateList so mid-hold append is addAll — not a whole-list state replace.
    val items = remember { mutableStateListOf<MediaItem>() }
    var loading by remember { mutableStateOf(true) }
    var browsePage by remember { mutableIntStateOf(1) }
    var endReached by remember { mutableStateOf(false) }
    var browseGen by remember { mutableIntStateOf(0) }
    /** Bumped once per mid-hold page append so the grid can re-pin without per-chunk thrash. */
    var browseHoldCommitGen by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val focusHub = remember { BrowseFocusHub() }
    val prefetchBuf = remember { BrowsePrefetchBuffer() }
    val nearEndCommitJob = remember { arrayOf<Job?>(null) }

    fun goRail(event: KeyEvent): Boolean {
        if (event.key != Key.DirectionUp) return false
        if (event.type == KeyEventType.KeyDown) enterRail()
        return event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
    }

    fun moveVertical(from: BrowseFocusRow, event: KeyEvent): Boolean {
        if (browseMenu != BrowseMenu.None) return false
        val up = event.key == Key.DirectionUp
        val down = event.key == Key.DirectionDown
        if (!up && !down) return false
        if (event.type != KeyEventType.KeyDown && event.type != KeyEventType.KeyUp) return false
        if (event.type == KeyEventType.KeyUp) return true
        if (event.nativeKeyEvent.repeatCount > 0) return true
        val target = when {
            up && from == BrowseFocusRow.Toolbar -> {
                enterRail()
                return true
            }
            // Top grid row → Sort/Filters first; another Up from toolbar enters the Browse pill.
            up && from == BrowseFocusRow.Shelf -> BrowseFocusRow.Toolbar
            down && from == BrowseFocusRow.Toolbar -> {
                if (items.isEmpty()) return true
                BrowseFocusRow.Shelf
            }
            else -> return false
        }
        val ok = when (target) {
            BrowseFocusRow.Toolbar -> runCatching { sortFocus.requestFocus() }.getOrDefault(false)
            BrowseFocusRow.Shelf -> {
                val idx = focusHub.key?.let { key ->
                    items.indexOfFirst { it.playableId().ifBlank { it.id } == key }
                }?.takeIf { it >= 0 } ?: focusHub.index.coerceIn(0, items.lastIndex)
                focusHub.focusCard(idx)
                true
            }
        }
        if (ok) focusRow = target
        return true
    }

    LaunchedEffect(browseActive, railFocused, items.isNotEmpty()) {
        val returning = browseActive && !browseWasActive
        browseWasActive = browseActive
        // Stay on the nav rail when the user only highlights Browse — don't steal focus.
        if (!returning || railFocused || items.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.yield()
        // Rail may have taken focus again during yield (tab highlight).
        if (railFocusedState.value) return@LaunchedEffect
        // Down-from-rail handoff owns Sort focus; this path is enterTab / cold start only.
        if (wasRailFocused) return@LaunchedEffect
        val idx = focusHub.key?.let { key ->
            items.indexOfFirst { it.playableId().ifBlank { it.id } == key }
        }?.takeIf { it >= 0 } ?: 0
        focusHub.focusCard(idx)
        focusRow = BrowseFocusRow.Shelf
    }

    var prevType by remember { mutableStateOf(typeFilter) }
    LaunchedEffect(typeFilter, server.url, server.token) {
        val kindChanged = prevType != typeFilter
        prevType = typeFilter
        val api = CoogApi(server.url, server.token)
        genreChips = when (typeFilter) {
            "all" -> {
                val movies = runCatching { api.catalogGenres("movie") }.getOrDefault(emptyList())
                val series = runCatching { api.catalogGenres("series") }.getOrDefault(emptyList())
                mergeBrowseGenreChips(movies, series)
            }
            "series" -> {
                val series = runCatching { api.catalogGenres("series") }.getOrDefault(emptyList())
                listOf(BrowseGenreChip("all", "All")) + series.mapNotNull { g ->
                    val name = g.name.trim()
                    if (name.isEmpty() || g.id <= 0) null
                    else BrowseGenreChip(name.lowercase(), name, seriesId = g.id)
                }
            }
            else -> {
                val movies = runCatching { api.catalogGenres("movie") }.getOrDefault(emptyList())
                listOf(BrowseGenreChip("all", "All")) + movies.mapNotNull { g ->
                    val name = g.name.trim()
                    if (name.isEmpty() || g.id <= 0) null
                    else BrowseGenreChip(name.lowercase(), name, movieId = g.id)
                }
            }
        }
        moods = runCatching {
            api.catalogMoods(if (typeFilter == "series") "series" else "movie")
        }.getOrDefault(emptyList())
        if (kindChanged) {
            selectedGenreKeys = emptyList()
            mood = ""
        }
    }

    val showGenres = sourceFilter != "local"
    fun genreIdsFor(forKind: String): List<Int> {
        if (!showGenres || selectedGenreKeys.isEmpty()) return emptyList()
        return selectedGenreKeys.mapNotNull { key ->
            val chip = genreChips.find { it.key == key } ?: return@mapNotNull null
            val id = if (forKind == "series") chip.seriesId else chip.movieId
            id.takeIf { it > 0 }
        }
    }

    val libraryRev = remember(library) {
        var h = library.size
        for (item in library) h = 31 * h + item.playableId().hashCode()
        h
    }

    suspend fun fetchBrowsePage(page: Int): List<MediaItem> =
        loadBrowseItems(
            typeFilter = typeFilter,
            sourceFilter = sourceFilter,
            sort = sort,
            movieGenreIds = genreIdsFor("movie"),
            seriesGenreIds = genreIdsFor("series"),
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            library = library,
            api = CoogApi(server.url, server.token),
            page = page,
        )

    LaunchedEffect(
        typeFilter, sourceFilter, sort, selectedGenreKeys, yearMin, yearMax, mood, minRating,
        libraryRev, server.url, server.token,
    ) {
        val gen = ++browseGen
        error = null
        browsePage = 1
        endReached = false
        prefetchBuf.clear()
        if (items.isEmpty()) loading = true
        try {
            if (sourceFilter == "local") {
                val local = fetchBrowsePage(1)
                if (gen != browseGen) return@LaunchedEffect
                items.clear()
                items.addAll(local)
                browsePage = 1
                endReached = true
                focusHub.index = 0
                focusHub.key = null
                return@LaunchedEffect
            }
            // Commit page 1 only — page 2 warms into prefetchBuf (no grid growth yet).
            val first = fetchBrowsePage(1)
            if (gen != browseGen) return@LaunchedEffect
            items.clear()
            items.addAll(first.take(BrowseMaxItems))
            browsePage = 1
            endReached = first.isEmpty() || items.size >= BrowseMaxItems
            focusHub.index = 0
            focusHub.key = null
        } catch (e: Exception) {
            if (gen == browseGen && items.isEmpty()) {
                error = e.message ?: "Could not load Browse"
            }
        } finally {
            if (gen == browseGen) loading = false
        }
    }

    /** Quiet settle before appending — shorter when already on the last rows / holding. */
    fun nearEndSettleMs(remaining: Int, holding: Boolean): Long = when {
        holding && remaining <= BrowseHoldCommitRemaining -> 0L
        remaining <= 9 -> 80L
        remaining <= 12 -> BrowseCommitLastRowsMs
        else -> BrowseCommitSettleMs
    }

    /**
     * Append a buffered page into the grid. [forceWhileHolding] allows growth during a
     * Down hold so the edge never catches the focus index.
     * Mid-hold: chunk addAll across frames so one ~70-card commit cannot stall Main ~1s.
     */
    suspend fun commitPendingBrowse(
        minQuietMs: Long = BrowseCommitSettleMs,
        forceWhileHolding: Boolean = false,
    ) {
        if (focusHub.holdingVertical && !forceWhileHolding) return
        if (!forceWhileHolding && focusHub.msSinceHop() < minQuietMs) return
        val pending = prefetchBuf.poll() ?: return
        val room = (BrowseMaxItems - items.size).coerceAtLeast(0)
        if (room == 0) {
            endReached = true
            return
        }
        val toAdd = items.newBrowseItems(pending.items.take(room))
        if (toAdd.isEmpty()) {
            endReached = true
            browsePage = pending.page
            return
        }
        CoogJankLog.mark(
            "browse-commit",
            "add=${toAdd.size} at=${items.size} hold=${if (forceWhileHolding) 1 else 0}",
        )
        // Chunk large addAll on hold and idle/KeyUp — a ~70-card commit stalled Main ~260–300ms.
        if (toAdd.size > 18) {
            var i = 0
            while (i < toAdd.size) {
                val end = (i + 12).coerceAtMost(toAdd.size)
                items.addAll(toAdd.subList(i, end))
                i = end
                awaitFrame()
            }
        } else {
            items.addAll(toAdd)
        }
        browsePage = pending.page
        endReached = items.size >= BrowseMaxItems || browsePage >= BrowseMaxPage
        // One absolute re-pin after the whole append — not per chunk (that spiked p99).
        if (forceWhileHolding) browseHoldCommitGen += 1
    }

    /** Network-only: fill [prefetchBuf] without Compose state (no grid recomposition). */
    fun prefetchBrowsePage() {
        if (sourceFilter == "local" || endReached || prefetchBuf.inFlight) return
        // Allow warm page-2 right after page-1 assign (loading may still be true briefly).
        if (loading && items.isEmpty()) return
        if (prefetchBuf.depth() >= BrowsePrefetchDepth) return
        if (items.size >= BrowseMaxItems) {
            endReached = true
            return
        }
        val nextPage = prefetchBuf.nextPage
        if (nextPage > BrowseMaxPage) {
            endReached = true
            return
        }
        val gen = browseGen
        prefetchBuf.inFlight = true
        scope.launch {
            try {
                val batch = fetchBrowsePage(nextPage)
                if (gen != browseGen) return@launch
                if (batch.isEmpty()) {
                    endReached = true
                } else {
                    prefetchBuf.offer(nextPage, batch)
                    prefetchBuf.nextPage = nextPage + 1
                    // Warm Coil in parallel — must not block the next network fetch.
                    scope.launch {
                        // Don't decode a whole page while the hold scroll is already
                        // bringing tiles on-screen — that double-loads Coil/GC.
                        while (focusHub.holdingVertical) delay(80)
                        if (gen != browseGen) return@launch
                        warmBrowsePosterThumbs(context, server, batch, decodePx = 140)
                    }
                }
            } catch (_: Exception) {
                // Keep posters; next near-end hop can retry.
            } finally {
                if (gen == browseGen) {
                    prefetchBuf.inFlight = false
                    // Pipeline: fill depth while a batch sits awaiting commit.
                    if (!endReached && prefetchBuf.depth() < BrowsePrefetchDepth) {
                        prefetchBrowsePage()
                    }
                }
            }
        }
    }

    /**
     * Wait for an in-flight page (if any), then commit — including mid-hold when close
     * to the wall. Chains while focus stays near the end (last-row dwell + long holds).
     */
    fun scheduleNearEndCommit(settleMs: Long) {
        // Do not cancel an active waiter — every near-end hop used to restart it and delay appends.
        if (nearEndCommitJob[0]?.isActive == true) return
        nearEndCommitJob[0] = scope.launch {
            var settle = settleMs
            while (true) {
                while (!prefetchBuf.hasBatch() && prefetchBuf.inFlight) {
                    delay(20)
                    if (endReached) return@launch
                }
                if (!prefetchBuf.hasBatch()) return@launch
                // Hold + near wall → append immediately.
                // Otherwise wait for KeyUp quiet settle.
                while (prefetchBuf.hasBatch()) {
                    val holding = focusHub.holdingVertical
                    when {
                        // Mid-hold: drain every buffered page so focus never waits on the wall.
                        holding -> commitPendingBrowse(0L, forceWhileHolding = true)
                        focusHub.msSinceHop() >= settle -> commitPendingBrowse(0L)
                        else -> {
                            delay(20)
                            if (endReached) return@launch
                            continue
                        }
                    }
                    break
                }
                if (!endReached) prefetchBrowsePage()
                val rem = (items.size - focusHub.index).coerceAtLeast(0)
                val holding = focusHub.holdingVertical
                if (endReached) return@launch
                // While holding, keep the commit loop alive until the buffer is topped up.
                if (!holding && rem > BrowsePrefetchRemaining) return@launch
                if (holding && rem > BrowsePrefetchRemaining &&
                    prefetchBuf.depth() >= BrowsePrefetchDepth && !prefetchBuf.inFlight
                ) {
                    return@launch
                }
                settle = nearEndSettleMs(rem, holding)
                prefetchBrowsePage()
            }
        }
    }

    fun onBrowseNearEnd(focusIndex: Int) {
        val remaining = (items.size - focusIndex).coerceAtLeast(0)
        val holding = focusHub.holdingVertical
        // Mid-hold: keep fetching even farther from the edge so append stays ahead of focus.
        if (!holding && remaining > BrowsePrefetchRemaining) return
        prefetchBrowsePage()
        // Always schedule — even while inFlight — so hold/last-row still commits
        // when the page arrives (no further hops required).
        if (holding || prefetchBuf.hasBatch() || prefetchBuf.inFlight) {
            scheduleNearEndCommit(nearEndSettleMs(remaining, holding))
        }
    }

    // Always keep one page buffered ahead whenever the grid has room.
    LaunchedEffect(items.size, browsePage, endReached, sourceFilter, loading) {
        if (sourceFilter == "local" || endReached || items.isEmpty() || loading) return@LaunchedEffect
        prefetchBrowsePage()
    }

    fun toggleGenreKey(key: String) {
        if (key == "all" || key.isBlank()) {
            selectedGenreKeys = emptyList()
            return
        }
        selectedGenreKeys = selectedGenreKeys.toMutableList().also { list ->
            if (key in list) list.remove(key)
            else if (list.size < 3) list.add(key)
        }
    }

    fun clearAllFilters() {
        typeFilter = "all"
        sourceFilter = "all"
        selectedGenreKeys = emptyList()
        yearMin = 0
        yearMax = 0
        mood = ""
        minRating = 0.0
    }

    val sortLabel = SortChips.find { it.id == sort }?.label ?: "Recommended"
    val summaryChips = buildList {
        if (typeFilter != "all") {
            val label = TypeChips.find { it.id == typeFilter }?.label ?: typeFilter
            add(BrowseSummaryChip("type", label) { typeFilter = "all" })
        }
        if (sourceFilter != "all") {
            val label = SourceChips.find { it.id == sourceFilter }?.label ?: sourceFilter
            add(BrowseSummaryChip("source", label) { sourceFilter = "all" })
        }
        selectedGenreKeys.forEach { key ->
            val name = genreChips.find { it.key == key }?.name ?: key
            add(BrowseSummaryChip("genre-$key", name) {
                selectedGenreKeys = selectedGenreKeys.filterNot { it == key }
            })
        }
        if (yearMin > 0 || yearMax > 0) {
            val label = DecadeChips.find { it.yearMin == yearMin && it.yearMax == yearMax }?.label
                ?: "$yearMin–$yearMax"
            add(BrowseSummaryChip("decade", label) {
                yearMin = 0
                yearMax = 0
            })
        }
        if (mood.isNotBlank()) {
            val label = moods.find { it.id == mood }?.label?.ifBlank { mood } ?: mood
            add(BrowseSummaryChip("mood", label) { mood = "" })
        }
        if (minRating > 0) {
            val label = RatingChips.find { it.min == minRating }?.label ?: "${minRating.toInt()}+"
            add(BrowseSummaryChip("rating", label) { minRating = 0.0 })
        }
    }
    val filterCount = summaryChips.size

    val sortRows = SortChips.map { chip ->
        BrowseHudRow(id = "sort-${chip.id}", label = chip.label, checked = sort == chip.id)
    }
    val filterRows = buildList {
        add(BrowseHudRow("sec-type", "Type", checked = false, checkable = false, section = true))
        TypeChips.forEach { chip ->
            add(BrowseHudRow("type-${chip.id}", chip.label, checked = typeFilter == chip.id))
        }
        add(BrowseHudRow("sec-source", "Source", checked = false, checkable = false, section = true))
        SourceChips.forEach { chip ->
            add(BrowseHudRow("source-${chip.id}", chip.label, checked = sourceFilter == chip.id))
        }
        if (showGenres) {
            add(BrowseHudRow("sec-genre", "Genre", checked = false, checkable = false, section = true))
            genreChips.forEach { chip ->
                val selected = if (chip.key == "all") {
                    selectedGenreKeys.isEmpty()
                } else {
                    chip.key in selectedGenreKeys
                }
                add(BrowseHudRow("genre-${chip.key}", chip.name.ifBlank { "All" }, checked = selected))
            }
        }
        add(BrowseHudRow("sec-decade", "Year", checked = false, checkable = false, section = true))
        DecadeChips.forEach { chip ->
            add(
                BrowseHudRow(
                    "decade-${chip.label}",
                    chip.label,
                    checked = yearMin == chip.yearMin && yearMax == chip.yearMax,
                ),
            )
        }
        if (showGenres && moods.isNotEmpty()) {
            add(BrowseHudRow("sec-mood", "Mood", checked = false, checkable = false, section = true))
            add(BrowseHudRow("mood-any", "Any mood", checked = mood.isBlank()))
            moods.forEach { m ->
                add(
                    BrowseHudRow(
                        "mood-${m.id}",
                        m.label.ifBlank { m.id },
                        checked = mood == m.id,
                    ),
                )
            }
        }
        add(BrowseHudRow("sec-rating", "Rating", checked = false, checkable = false, section = true))
        RatingChips.forEach { chip ->
            add(BrowseHudRow("rating-${chip.label}", chip.label, checked = minRating == chip.min))
        }
        if (filterCount > 0) {
            add(BrowseHudRow("sec-clear", "Reset", checked = false, checkable = false, section = true))
            add(BrowseHudRow("clear-all", "Clear all filters", checked = false, checkable = false))
        }
    }
    val activeRows = when (browseMenu) {
        BrowseMenu.Sort -> sortRows
        BrowseMenu.Filters -> filterRows
        BrowseMenu.None -> emptyList()
    }
    val actionableRows = activeRows.filterNot { it.section }

    fun closeMenu() {
        val restore = browseMenu
        browseMenu = BrowseMenu.None
        menuSel = 0
        focusRow = BrowseFocusRow.Toolbar
        when (restore) {
            BrowseMenu.Sort -> runCatching { sortFocus.requestFocus() }
            BrowseMenu.Filters -> runCatching { filtersFocus.requestFocus() }
            BrowseMenu.None -> Unit
        }
    }

    fun openMenu(menu: BrowseMenu) {
        browseMenu = menu
        menuSel = when (menu) {
            BrowseMenu.Sort -> SortChips.indexOfFirst { it.id == sort }.coerceAtLeast(0)
            BrowseMenu.Filters -> 0
            BrowseMenu.None -> 0
        }
        focusRow = BrowseFocusRow.Toolbar
        scope.launch {
            awaitFrame()
            runCatching { hudFocus.requestFocus() }
        }
    }

    fun activateMenuRow(index: Int) {
        val row = actionableRows.getOrNull(index) ?: return
        when (browseMenu) {
            BrowseMenu.Sort -> {
                val chip = SortChips.getOrNull(index) ?: return
                sort = chip.id
                closeMenu()
            }
            BrowseMenu.Filters -> when {
                row.id.startsWith("type-") -> typeFilter = row.id.removePrefix("type-")
                row.id.startsWith("source-") -> sourceFilter = row.id.removePrefix("source-")
                row.id.startsWith("genre-") -> toggleGenreKey(row.id.removePrefix("genre-"))
                row.id.startsWith("decade-") -> {
                    val chip = DecadeChips.find { "decade-${it.label}" == row.id } ?: return
                    yearMin = chip.yearMin
                    yearMax = chip.yearMax
                }
                row.id == "mood-any" -> mood = ""
                row.id.startsWith("mood-") -> {
                    val id = row.id.removePrefix("mood-")
                    mood = if (mood == id) "" else id
                }
                row.id.startsWith("rating-") -> {
                    val chip = RatingChips.find { "rating-${it.label}" == row.id } ?: return
                    minRating = chip.min
                }
                row.id == "clear-all" -> clearAllFilters()
            }
            BrowseMenu.None -> Unit
        }
    }

    fun moveMenu(delta: Int) {
        if (actionableRows.isEmpty()) return
        menuSel = (menuSel + delta).coerceIn(0, actionableRows.lastIndex)
    }

    LaunchedEffect(browseMenu, actionableRows.size) {
        if (browseMenu == BrowseMenu.None) return@LaunchedEffect
        if (menuSel > actionableRows.lastIndex) menuSel = actionableRows.lastIndex.coerceAtLeast(0)
    }

    BackHandler(enabled = browseMenu != BrowseMenu.None) {
        closeMenu()
    }

    fun toolbarMod(
        requester: FocusRequester? = null,
    ): Modifier = Modifier
        .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .focusProperties {
            up = FocusRequester.Cancel
            down = FocusRequester.Cancel
            canFocus = browseMenu == BrowseMenu.None
        }
        .onPreviewKeyEvent { event ->
            moveVertical(BrowseFocusRow.Toolbar, event) || goRail(event)
        }

    val showSummary = summaryChips.isNotEmpty() && browseMenu == BrowseMenu.None
    // Sort/Filters (+ optional summary) — grid contentPadding clears this when at top.
    val browseChromeH = 4.dp + 32.dp + (if (showSummary) 4.dp + 32.dp else 0.dp) + 8.dp
    // Collapse the dock-sized gap when the top menu hides (shelf focus).
    val dockInsetTarget = if (focusRow == BrowseFocusRow.Shelf) 0.dp else topBarHeight()
    val dockInset by animateDpAsState(
        targetValue = dockInsetTarget,
        animationSpec = tween(durationMillis = 240),
        label = "browseDockInset",
    )
    val gridTopPad = dockInset + browseChromeH

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CoogBgDeep)
            .clipToBounds()
            .onPreviewKeyEvent { event ->
                if (browseMenu == BrowseMenu.None) return@onPreviewKeyEvent false
                if (event.key == Key.Back || event.key == Key.Escape) {
                    return@onPreviewKeyEvent false
                }
                val up = event.key == Key.DirectionUp
                val down = event.key == Key.DirectionDown
                val left = event.key == Key.DirectionLeft
                val center = event.key == Key.DirectionCenter || event.key == Key.Enter
                if (event.type == KeyEventType.KeyUp) {
                    return@onPreviewKeyEvent up || down || left || center
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (event.nativeKeyEvent.repeatCount > 0 && (up || down)) {
                    if (up) moveMenu(-1) else moveMenu(1)
                    return@onPreviewKeyEvent true
                }
                when {
                    up -> {
                        moveMenu(-1)
                        true
                    }
                    down -> {
                        moveMenu(1)
                        true
                    }
                    left -> {
                        closeMenu()
                        true
                    }
                    center -> {
                        activateMenuRow(menuSel)
                        true
                    }
                    else -> false
                }
            },
    ) {
        when {
            error != null && items.isEmpty() -> {
                Text(
                    error.orEmpty(),
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = inset, end = inset, top = gridTopPad + 12.dp),
                )
            }
            loading && items.isEmpty() -> {
                Text(
                    "One moment.",
                    style = CoogType.heroTagline,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = inset, end = inset, top = gridTopPad + 12.dp),
                )
            }
            items.isEmpty() -> {
                Text(
                    "Nothing matches these filters.",
                    style = CoogType.heroPlot,
                    color = CoogTextMuted,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = inset, end = inset, top = gridTopPad + 12.dp),
                )
            }
            else -> {
                BrowsePosterGrid(
                    items = items,
                    jobs = jobs,
                    library = library,
                    inset = inset,
                    firstFocus = shelfFocus,
                    libraryMenuOpen = libraryMenuOpen,
                    hudOpen = browseMenu != BrowseMenu.None,
                    endReached = endReached,
                    loadingMore = false,
                    contentTopPad = gridTopPad,
                    holdCommitGen = browseHoldCommitGen,
                    onNearEnd = { index -> onBrowseNearEnd(index) },
                    onVerticalHold = { holding ->
                        focusHub.holdingVertical = holding
                        shelfHolding = holding
                        focusHub.noteHop()
                        // Keep main dock hidden for the whole hold — Compose focus can leave the
                        // off-screen card and land on Sort/Filters without this.
                        if (holding) focusRow = BrowseFocusRow.Shelf
                        if (!holding) onBrowseNearEnd(focusHub.index)
                    },
                    onOpen = onOpen,
                    onManageLibrary = onManageLibrary,
                    onSelection = { index, key ->
                        focusHub.index = index
                        focusHub.key = key
                        focusHub.noteHop()
                        if (items.size - index <= BrowsePrefetchRemaining) {
                            onBrowseNearEnd(index)
                        }
                        // Avoid focusRow state write on every hold hop (grid already owns focus).
                        if (!focusHub.holdingVertical && focusRow != BrowseFocusRow.Shelf) {
                            focusRow = BrowseFocusRow.Shelf
                        }
                    },
                    onBindFocusCard = { focusHub.focusCard = it },
                    onExitUp = {
                        if (browseMenu != BrowseMenu.None) return@BrowsePosterGrid true
                        val ok = runCatching { sortFocus.requestFocus() }.getOrDefault(false)
                        if (ok) focusRow = BrowseFocusRow.Toolbar
                        ok
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .focusProperties { canFocus = browseMenu == BrowseMenu.None },
                )
            }
        }

        // Chrome over the grid — no gradient scrim (that read as a top-edge shadow).
        // While holding Down/Up on the shelf, chrome must not be focusable or focus restoration
        // after a disposed card will flip focusRow back to Toolbar and re-show the main dock.
        val chromeCanFocus = browseMenu == BrowseMenu.None && !shelfHolding
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(top = dockInset)
                .zIndex(2f)
                .focusProperties { canFocus = chromeCanFocus }
                .padding(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = inset, end = inset, top = 4.dp),
            ) {
                FilterChip(
                    label = "Sort · $sortLabel",
                    selected = browseMenu == BrowseMenu.Sort,
                    elevated = true,
                    onClick = {
                        if (browseMenu == BrowseMenu.Sort) closeMenu() else openMenu(BrowseMenu.Sort)
                    },
                    onFocused = { focusRow = BrowseFocusRow.Toolbar },
                    modifier = toolbarMod(
                        requester = if (browseActive) sortFocus else null,
                    ),
                )
                FilterChip(
                    label = if (filterCount > 0) "Filters · $filterCount" else "Filters",
                    selected = browseMenu == BrowseMenu.Filters || filterCount > 0,
                    elevated = true,
                    onClick = {
                        if (browseMenu == BrowseMenu.Filters) closeMenu() else openMenu(BrowseMenu.Filters)
                    },
                    onFocused = { focusRow = BrowseFocusRow.Toolbar },
                    modifier = toolbarMod(requester = filtersFocus),
                )
            }
            if (showSummary) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(horizontal = inset),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(summaryChips, key = { it.id }) { chip ->
                        FilterChip(
                            label = chip.label,
                            selected = true,
                            elevated = true,
                            onClick = chip.onClear,
                            onFocused = { focusRow = BrowseFocusRow.Toolbar },
                            modifier = toolbarMod(),
                        )
                    }
                }
            }
        }

        if (browseMenu != BrowseMenu.None) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .zIndex(4f)
                    .focusRequester(hudFocus)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        // Catcher owns focus; outer Box also handles menu keys.
                        false
                    },
            )
            BrowseHudPanel(
                icon = if (browseMenu == BrowseMenu.Sort) Icons.Outlined.SwapVert else Icons.Outlined.FilterList,
                title = if (browseMenu == BrowseMenu.Sort) "Sort" else "Filters",
                rows = activeRows,
                menuSel = menuSel,
                onActivate = { index ->
                    menuSel = index
                    activateMenuRow(index)
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .zIndex(5f)
                    .padding(end = 36.dp, top = topBarHeight() + 24.dp, bottom = 48.dp),
            )
        }
    }
}

@Composable
private fun BrowseHudPanel(
    icon: ImageVector,
    title: String,
    rows: List<BrowseHudRow>,
    menuSel: Int,
    onActivate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val actionable = remember(rows) { rows.mapIndexedNotNull { i, row -> if (row.section) null else i } }
    LaunchedEffect(menuSel, rows.size) {
        val rowIndex = actionable.getOrNull(menuSel) ?: return@LaunchedEffect
        runCatching { listState.scrollToItem(rowIndex + 1) }
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .width(268.dp)
            .heightIn(max = 420.dp)
            .background(BrowseHudBg, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "browse-hud-header") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(
                    title,
                    style = CoogType.chip.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    ),
                )
            }
        }
        itemsIndexed(rows, key = { _, row -> row.id }) { index, row ->
            if (row.section) {
                Text(
                    row.label.uppercase(),
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
                )
            } else {
                val actionIndex = actionable.indexOf(index)
                BrowseHudListRow(
                    label = row.label,
                    checked = row.checked,
                    checkable = row.checkable,
                    highlighted = actionIndex == menuSel,
                    onClick = { if (actionIndex >= 0) onActivate(actionIndex) },
                )
            }
        }
    }
}

@Composable
private fun BrowseHudListRow(
    label: String,
    checked: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
    checkable: Boolean = true,
) {
    val fg = when {
        highlighted -> Color.White
        checked -> Color.White.copy(alpha = 0.92f)
        else -> Color.White.copy(alpha = 0.55f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .background(
                if (highlighted) Color.White.copy(alpha = 0.08f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp)
            .focusProperties { canFocus = false },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(14.dp), contentAlignment = Alignment.Center) {
            if (checkable && checked) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp),
                )
            }
        }
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (highlighted || checked) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowsePosterGrid(
    items: List<MediaItem>,
    jobs: List<JobItem>,
    library: List<MediaItem>,
    inset: Dp,
    firstFocus: FocusRequester,
    libraryMenuOpen: Boolean,
    hudOpen: Boolean,
    endReached: Boolean,
    loadingMore: Boolean,
    contentTopPad: Dp,
    holdCommitGen: Int = 0,
    onNearEnd: (Int) -> Unit,
    onVerticalHold: (Boolean) -> Unit = {},
    onOpen: (MediaItem) -> Unit,
    onManageLibrary: ((MediaItem) -> Unit)?,
    onSelection: (Int, String) -> Unit,
    onBindFocusCard: ((Int) -> Unit) -> Unit,
    onExitUp: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val density = LocalDensity.current
    // Match Home Continue peeks: HomeCardHeight × 2:3.
    val cardHeight = HomeCardHeight
    val cardWidth = cardHeight * 2f / 3f
    val gap = ShelfCardGap
    val rowScrollPx = with(density) { (cardHeight + gap).toPx() }
    // Decode at on-screen card pixels (≈1×). Cap tight — oversized decode + ARGB caused GC jank.
    val decodeMaxPx = (cardWidth.value * density.density).toInt().coerceIn(120, 160)
    val contentWidthDp = widthDp - (inset.value * 2f)
    val columns = max(
        1,
        ((contentWidthDp + gap.value) / (cardWidth.value + gap.value)).toInt(),
    )
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val jobsRev = jobsMarkRevision(jobs)
    val localKeys = remember(library) { library.localCatalogKeys() }
    val firstKey = items.firstOrNull()?.let { it.playableId().ifBlank { it.id } }.orEmpty()
    // Incremental marks — full rebuild on jobs/catalog identity; append only marks new suffix.
    var marksByKey by remember { mutableStateOf<Map<String, CardMark?>>(emptyMap()) }
    val marksJobsRev = remember { intArrayOf(Int.MIN_VALUE) }
    val marksFirstKey = remember { arrayOf("") }
    val marksSize = remember { intArrayOf(0) }
    LaunchedEffect(items, jobsRev, localKeys, firstKey) {
        fun markFor(item: MediaItem): CardMark? =
            item.cardMark(jobs, library)
                ?: item.catalogMatchKey().takeIf { it in localKeys }?.let {
                    CardMark(CardMarkKind.Local)
                }
        val catalogChanged = marksFirstKey[0] != firstKey
        if (marksJobsRev[0] != jobsRev || catalogChanged) {
            marksJobsRev[0] = jobsRev
            marksFirstKey[0] = firstKey
            marksByKey = items.associate { item ->
                item.playableId().ifBlank { item.id } to markFor(item)
            }
            marksSize[0] = items.size
            return@LaunchedEffect
        }
        if (items.size <= marksSize[0]) {
            if (items.size < marksSize[0]) {
                marksByKey = items.associate { item ->
                    item.playableId().ifBlank { item.id } to markFor(item)
                }
                marksSize[0] = items.size
            }
            return@LaunchedEffect
        }
        val next = marksByKey.toMutableMap()
        for (i in marksSize[0] until items.size) {
            val item = items[i]
            val key = item.playableId().ifBlank { item.id }
            next[key] = markFor(item)
        }
        marksSize[0] = items.size
        marksByKey = next
    }
    // Fixed pool — never recreate on page append.
    val focusRequesters = remember { List(BrowseMaxItems) { FocusRequester() } }
    val focusedIndexRef = remember { intArrayOf(-1) }
    val firstKeyRef = remember { arrayOf("") }
    val selectionCb = rememberUpdatedState(onSelection)
    val nearEndCb = rememberUpdatedState(onNearEnd)
    val holdCb = rememberUpdatedState(onVerticalHold)
    val exitUpCb = rememberUpdatedState(onExitUp)
    val openCb = rememberUpdatedState(onOpen)
    val manageCb = rememberUpdatedState(onManageLibrary)
    val endReachedState = rememberUpdatedState(endReached)
    val itemCountState = rememberUpdatedState(items.size)
    // Hold job outlives a composition — always read the latest list after append.
    val itemsState = rememberUpdatedState(items)
    val moveJob = remember { arrayOf<Job?>(null) }
    val holdJob = remember { arrayOf<Job?>(null) }
    val pendingFocus = remember { intArrayOf(-1) }
    val holdingRef = remember { booleanArrayOf(false) }
    /** True only after hold arm delay — continuous hops stay instant; first tap can animate. */
    val holdScrollingRef = remember { booleanArrayOf(false) }
    val holdDirRef = remember { intArrayOf(0) } // +1 / -1 step
    val holdAxisRef = remember { intArrayOf(0) } // 0 = row (Up/Down), 1 = column (Left/Right)
    // Last index we scroll-anchored to — mid-hold uses scrollBy(row) instead of scrollToItem.
    val scrollAnchorRef = remember { intArrayOf(-1) }
    // Selection chrome — [BrowsePosterTile] gets showRing from this index (2 tiles/hop).
    val selection = remember { BrowseSelectionState() }
    // Pending key for deferred parent callback flush on KeyUp.
    val pendingSelectionKey = remember { arrayOf("") }
    var gridHolding by remember { mutableStateOf(false) }
    // Always-composed focus sink so LazyGrid disposing the focused card cannot jump to chrome.
    val holdFocusLock = remember { FocusRequester() }
    val railFocused = LocalNavBarFocused.current
    val railFocusedState = rememberUpdatedState(railFocused)
    // Only react to catalog identity changes — NOT itemKeys.size.
    // Size growth mid-hold must not requestFocus (that cancels continuous scroll).
    LaunchedEffect(firstKey, railFocused) {
        if (items.isEmpty()) return@LaunchedEffect
        if (firstKeyRef[0].isNotEmpty() && firstKeyRef[0] != firstKey) {
            focusedIndexRef[0] = 0
            pendingFocus[0] = 0
            scrollAnchorRef[0] = -1
            selection.index = 0
        }
        firstKeyRef[0] = firstKey
        // Highlighting Browse in the dock must not yank focus onto card 0.
        if (railFocusedState.value || holdingRef[0]) return@LaunchedEffect
        if (focusedIndexRef[0] !in items.indices) focusedIndexRef[0] = 0
        val i = focusedIndexRef[0]
        awaitFrame()
        if (railFocusedState.value) return@LaunchedEffect
        runCatching { focusRequesters.getOrNull(i)?.requestFocus() }
    }

    fun flushSelectionToParent(index: Int, key: String) {
        if (!holdingRef[0]) {
            CoogJankLog.hop("browse", "i=$index key=${key.take(24)} n=${itemCountState.value}")
        }
        selectionCb.value(index, key)
    }

    fun noteSelection(index: Int, key: String) {
        focusedIndexRef[0] = index
        pendingSelectionKey[0] = key
        if (selection.index != index) selection.index = index
        // Parent callback (focusHub / near-end / focusRow) deferred mid-hold.
        // Skip hop log mid-hold — Log + Choreographer watch contended with scroll on Main.
        if (holdingRef[0]) return
        flushSelectionToParent(index, key)
    }

    /**
     * Keep the focused grid row in the **second** viewport slot (previous row peeks above).
     * Row 0 stays at the top under Sort/Filters.
     *
     * Single D-pad tap: [animateScrollBy] (~[BrowseRowAnimMs]). Continuous hold: instant
     * pitch [scrollBy] + residual snap (original path). Avoid [scrollToItem] mid-hold.
     */
    suspend fun pinFocusToSecondRow(index: Int, forceAbsolute: Boolean = false) {
        if (index < 0) return
        val row = index / columns
        val targetY = if (row <= 0) 0f else rowScrollPx
        val pinIndex = (row - 1).coerceAtLeast(0) * columns
        val holding = holdingRef[0]
        val continuous = holdScrollingRef[0]
        val rowSpec = tween<Float>(BrowseRowAnimMs, easing = FastOutSlowInEasing)

        fun visibleOf(i: Int) =
            gridState.layoutInfo.visibleItemsInfo.find { it.index == i }

        suspend fun correctVisible(itemY: Int) {
            val delta = itemY - targetY
            if (kotlin.math.abs(delta) <= 2f) return
            runCatching { gridState.scroll { scrollBy(delta) } }
        }

        // Continuous hold: original instant pitch + residual.
        if (continuous && !forceAbsolute) {
            val anchor = scrollAnchorRef[0]
            if (anchor >= 0) {
                val fromPin = ((anchor / columns) - 1).coerceAtLeast(0)
                val pinRow = (row - 1).coerceAtLeast(0)
                val rowDelta = pinRow - fromPin
                if (rowDelta != 0) {
                    runCatching { gridState.scroll { scrollBy(rowDelta * rowScrollPx) } }
                }
                visibleOf(index)?.let { correctVisible(it.offset.y) }
                return
            }
        }

        visibleOf(index)?.let { item ->
            val delta = item.offset.y - targetY
            if (kotlin.math.abs(delta) <= 2f) return
            // Single-step tap (not mid continuous hold): animate one row.
            if (!continuous && !forceAbsolute) {
                val nearOneRow = kotlin.math.abs(kotlin.math.abs(delta) - rowScrollPx) <= rowScrollPx * 0.35f
                if (nearOneRow) {
                    runCatching { gridState.animateScrollBy(delta, rowSpec) }
                } else {
                    runCatching { gridState.scroll { scrollBy(delta) } }
                }
            } else {
                correctVisible(item.offset.y)
            }
            return
        }
        if (holding && !forceAbsolute) {
            val fromPin = gridState.firstVisibleItemIndex / columns
            val pinRow = (row - 1).coerceAtLeast(0)
            val rowDelta = pinRow - fromPin
            if (rowDelta != 0) {
                if (!continuous && kotlin.math.abs(rowDelta) == 1) {
                    runCatching { gridState.animateScrollBy(rowDelta * rowScrollPx, rowSpec) }
                } else {
                    runCatching { gridState.scroll { scrollBy(rowDelta * rowScrollPx) } }
                }
            }
            visibleOf(index)?.let { correctVisible(it.offset.y) }
            return
        }
        // KeyUp / absolute settle — snap (hold already scrolled; don't add 500ms lag).
        runCatching { gridState.scrollToItem(pinIndex) }
        visibleOf(index)?.let { item -> correctVisible(item.offset.y) }
    }

    /** Immediate scroll + focus — latest pending wins without cancel thrash. */
    fun moveFocusTo(index: Int) {
        val list = itemsState.value
        if (index !in list.indices) return
        val item = list[index]
        val key = item.playableId().ifBlank { item.id }
        noteSelection(index, key)
        pendingFocus[0] = index
        if (moveJob[0]?.isActive == true) return
        moveJob[0] = scope.launch {
            while (true) {
                val i = pendingFocus[0]
                val live = itemsState.value
                if (i !in live.indices) {
                    // Page not committed yet — wait briefly instead of aborting the move job.
                    awaitFrame()
                    if (pendingFocus[0] != i) continue
                    if (i !in itemsState.value.indices) return@launch
                }
                val target = focusRequesters.getOrNull(i) ?: return@launch
                val holding = holdingRef[0]
                pinFocusToSecondRow(i)
                scrollAnchorRef[0] = i
                if (!holding) {
                    runCatching { target.requestFocus() }
                }
                if (pendingFocus[0] == i) return@launch
            }
        }
    }

    fun stepOneColumn(delta: Int): Boolean {
        val count = itemCountState.value
        if (count <= 0) return false
        val base = maxOf(
            focusedIndexRef[0].coerceAtLeast(0),
            pendingFocus[0].coerceAtLeast(0),
        )
        val row = base / columns
        val col = base % columns
        val nextCol = col + delta
        if (nextCol !in 0 until columns) return false
        val next = row * columns + nextCol
        if (next !in 0 until count) return false
        moveFocusTo(next)
        return true
    }

    fun focusCard(index: Int) {
        moveFocusTo(index)
    }

    /** One grid row in the same column from the current pending/focused index. */
    fun stepOneRow(deltaRows: Int): Boolean {
        val count = itemCountState.value
        if (count <= 0) return false
        val base = maxOf(
            focusedIndexRef[0].coerceAtLeast(0),
            pendingFocus[0].coerceAtLeast(0),
        )
        // Never coerce onto another column (incomplete last row must stay put on Down).
        val next = base + deltaRows * columns
        if (next !in 0 until count) {
            if (deltaRows > 0 && !endReachedState.value) {
                nearEndCb.value(base)
            }
            return false
        }
        moveFocusTo(next)
        if (deltaRows > 0 && count - next <= BrowsePrefetchRemaining) {
            nearEndCb.value(next)
        }
        return true
    }

    fun stopHoldScroll() {
        holdJob[0]?.cancel()
        holdJob[0] = null
        holdDirRef[0] = 0
        holdAxisRef[0] = 0
        holdScrollingRef[0] = false
    }

    /**
     * After arm delay, advance on a ramping timer while the key stays down.
     * [horizontal] = Left/Right column hops; otherwise Up/Down row hops.
     * Vertical soft end: keep polling until KeyUp or true EOF.
     * Horizontal edge: idle until KeyUp (no page fetch to wait on).
     */
    fun startHoldScroll(delta: Int, horizontal: Boolean = false) {
        holdDirRef[0] = delta
        holdAxisRef[0] = if (horizontal) 1 else 0
        if (holdJob[0]?.isActive == true) return
        holdJob[0] = scope.launch(Dispatchers.Default) {
            delay(BrowseHoldArmMs)
            if (!holdingRef[0]) return@launch
            holdScrollingRef[0] = true
            // Drop any in-flight single-tap 500ms animation before continuous hops.
            withContext(Dispatchers.Main.immediate) {
                moveJob[0]?.cancel()
                moveJob[0] = null
            }
            var interval = BrowseHoldIntervalStartMs
            var wallWaits = 0
            var lastCount = itemCountState.value
            while (holdingRef[0] && holdDirRef[0] != 0) {
                val startedNs = System.nanoTime()
                val horizontalHold = holdAxisRef[0] == 1
                val step = holdDirRef[0]
                val advanced = withContext(Dispatchers.Main.immediate) {
                    if (horizontalHold) stepOneColumn(step) else stepOneRow(step)
                }
                if (!advanced) {
                    if (horizontalHold) {
                        // End of row — stay put until KeyUp; do not kill the hold job.
                        delay(BrowseHoldWallPollMs)
                        continue
                    }
                    // Holding Up at row 0 — idle until KeyUp (soft-end wait is Down-only).
                    if (step < 0) {
                        delay(BrowseHoldWallPollMs)
                        continue
                    }
                    if (endReachedState.value) {
                        withContext(Dispatchers.Main.immediate) {
                            val i = maxOf(focusedIndexRef[0], pendingFocus[0])
                            if (i >= 0) pinFocusToSecondRow(i, forceAbsolute = true)
                        }
                        break
                    }
                    val countNow = itemCountState.value
                    withContext(Dispatchers.Main.immediate) {
                        nearEndCb.value(
                            maxOf(focusedIndexRef[0], pendingFocus[0]).coerceAtLeast(0),
                        )
                        if (wallWaits % 4 == 0) {
                            val i = maxOf(focusedIndexRef[0], pendingFocus[0])
                            if (i >= 0) pinFocusToSecondRow(i, forceAbsolute = true)
                        }
                    }
                    if (countNow > lastCount) {
                        lastCount = countNow
                        wallWaits = 0
                        continue
                    }
                    wallWaits++
                    if (wallWaits == 1 || wallWaits % 8 == 0) {
                        CoogJankLog.mark("browse-wall", "wait=$wallWaits n=$countNow")
                    }
                    delay(BrowseHoldWallPollMs)
                    continue
                }
                wallWaits = 0
                lastCount = itemCountState.value
                val elapsed = (System.nanoTime() - startedNs) / 1_000_000L
                val wait = (interval - elapsed).coerceAtLeast(0L)
                if (wait > 0L) delay(wait)
                interval = (interval * 88L / 100L).coerceAtLeast(BrowseHoldIntervalMinMs)
            }
        }
    }

    DisposableEffect(Unit) {
        onBindFocusCard { focusCard(it) }
        onDispose {
            stopHoldScroll()
            onBindFocusCard { }
        }
    }

    PrefetchBrowseThumbsRef(
        items = items,
        focusedIndexRef = focusedIndexRef,
        columns = columns,
        enabled = true,
        rowsBehind = 1,
        // ±2–3 rows ahead is enough; ±4×cols (~45 executes) contended with hold scroll decode.
        rowsAhead = 2,
        settleMs = 100L,
        holdSettleMs = 40L,
        holdingRef = holdingRef,
        decodePx = decodeMaxPx,
    )

    // One absolute re-pin after a finished mid-hold page commit (not per addAll chunk).
    LaunchedEffect(holdCommitGen) {
        if (holdCommitGen == 0) return@LaunchedEffect
        awaitFrame()
        if (!holdingRef[0]) return@LaunchedEffect
        val i = maxOf(focusedIndexRef[0], pendingFocus[0], selection.index)
        if (i >= 0) pinFocusToSecondRow(i, forceAbsolute = true)
    }

    fun armHold(delta: Int, horizontal: Boolean = false) {
        holdingRef[0] = true
        // First KeyDown hop animates; continuous hops flip this after [BrowseHoldArmMs].
        holdScrollingRef[0] = false
        gridHolding = true
        holdCb.value(true)
        startHoldScroll(delta, horizontal = horizontal)
        // After canFocus flips, park focus on the always-composed sink so KeyUp still arrives
        // here when poster cells leave the LazyGrid window.
        scope.launch {
            awaitFrame()
            runCatching { holdFocusLock.requestFocus() }
        }
    }

    fun releaseHold() {
        holdingRef[0] = false
        holdScrollingRef[0] = false
        gridHolding = false
        stopHoldScroll()
        holdCb.value(false)
        val i = pendingFocus[0].takeIf { it >= 0 } ?: focusedIndexRef[0]
        if (i in itemsState.value.indices) {
            val item = itemsState.value[i]
            val key = pendingSelectionKey[0].ifBlank {
                item.playableId().ifBlank { item.id }
            }
            flushSelectionToParent(i, key)
            // Absolute pin on KeyUp — hold path only did pitch scrollBy.
            scope.launch {
                pinFocusToSecondRow(i, forceAbsolute = true)
                scrollAnchorRef[0] = i
                runCatching { focusRequesters.getOrNull(i)?.requestFocus() }
            }
        }
    }

    fun onGridDpadKey(event: KeyEvent): Boolean {
        if (libraryMenuOpen || hudOpen) return false
        val down = event.key == Key.DirectionDown
        val up = event.key == Key.DirectionUp
        val left = event.key == Key.DirectionLeft
        val right = event.key == Key.DirectionRight
        if (!down && !up && !left && !right) return false
        if (event.type == KeyEventType.KeyUp) {
            releaseHold()
            return true
        }
        if (event.type != KeyEventType.KeyDown) return false
        val repeat = event.nativeKeyEvent.repeatCount
        if (left || right) {
            if (repeat == 0) {
                stepOneColumn(if (right) +1 else -1)
                armHold(if (right) +1 else -1, horizontal = true)
            }
            return true
        }
        if (down) {
            if (repeat == 0) {
                // First press still moves focus (menu hide + border); then arm hold.
                if (!stepOneRow(+1) && !endReachedState.value) {
                    nearEndCb.value(focusedIndexRef[0].coerceAtLeast(0))
                }
                armHold(+1)
            }
            return true
        }
        if (repeat == 0) {
            val base = maxOf(
                focusedIndexRef[0],
                pendingFocus[0],
                selection.index,
            ).coerceAtLeast(0)
            if (base - columns < 0) {
                holdingRef[0] = false
                gridHolding = false
                stopHoldScroll()
                holdCb.value(false)
                return exitUpCb.value()
            }
            stepOneRow(-1)
            armHold(-1)
        }
        return true
    }

    Box(modifier = modifier.padding(horizontal = inset)) {
        // Focus sink: mid-hold we park focus here so disposing poster cells cannot restore onto
        // Sort/Filters (that re-showed the main dock and cleared the selection border).
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(holdFocusLock)
                .focusable()
                .focusProperties { canFocus = gridHolding }
                .onPreviewKeyEvent { onGridDpadKey(it) },
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides BrowseNoBringIntoView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusProperties { canFocus = !hudOpen },
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(top = contentTopPad, bottom = 24.dp),
            ) {
                itemsIndexed(
                    items,
                    key = { index, item ->
                        item.playableId().ifBlank { item.id }.ifBlank { "item-$index" }
                    },
                    contentType = { _, _ -> "browse-poster" },
                ) { index, item ->
                    val key = item.playableId().ifBlank { item.id }
                    val requester = focusRequesters[index]
                    // Boolean showRing (not shared state in draw) — only the prev/next tile
                    // recompose on hop; layoutInfo overlay was offset from the card.
                    BrowsePosterTile(
                        item = item,
                        title = item.headline(),
                        subtitle = item.year.takeIf { it > 0 }?.toString().orEmpty(),
                        mark = marksByKey[key],
                        showRing = selection.index == index,
                        width = cardWidth,
                        height = cardHeight,
                        decodeMaxPx = decodeMaxPx,
                        onClick = { openCb.value(item) },
                        onLongClick = manageCb.value
                            ?.takeIf { item.canManageLibrary() }
                            ?.let { manage -> { manage(item) } },
                        onFocused = { noteSelection(index, key) },
                        modifier = Modifier
                            .focusRequester(requester)
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .focusProperties {
                                canFocus = !hudOpen
                                // Block geometric focus search — it skipped columns/rows.
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            }
                            .onPreviewKeyEvent { onGridDpadKey(it) },
                    )
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                        Text(
                            "Loading more…",
                            style = CoogType.cardYear,
                            color = CoogTextMuted,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowsePosterTile(
    item: MediaItem,
    title: String,
    subtitle: String,
    mark: CardMark?,
    showRing: Boolean,
    width: Dp,
    height: Dp,
    decodeMaxPx: Int,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val focusLabel = if (subtitle.isNotBlank()) "$title ($subtitle)" else title
    // TV D-pad focus requires tv.material3 Surface — foundation focusable does not light up.
    // Ring in tile coords (aligned). Idle border/fill removed (B1) — less issue-draw per frame.
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = BrowsePosterShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .width(width)
            .height(height)
            .onFocusChanged {
                if (it.isFocused) onFocused()
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(BrowsePosterShape)
                .then(
                    if (showRing) {
                        Modifier.border(
                            width = 3.dp,
                            color = Color.White,
                            shape = BrowsePosterShape,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            PosterArt(
                item = item,
                kind = ArtKind.Poster,
                preferDisplay = false,
                crossfadeMs = 0,
                decodeMaxPx = decodeMaxPx,
                mark = mark,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    mark: CardMark? = null,
    onLongClick: (() -> Unit)? = null,
    /** Kept for call sites; colors always match AppShell nav pills. */
    elevated: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE")
    val elevatedUnused = elevated
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            // Same recipe as AppShell NavPill.
            containerColor = if (selected) BrowseChipActive else BrowseChipIdle,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = BrowseChipFocusedContent,
            pressedContainerColor = Color.White.copy(alpha = 0.92f),
            pressedContentColor = BrowseChipFocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
        modifier = modifier
            .height(32.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            },
    ) {
        Box(
            modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    label,
                    style = CoogType.chip.merge(
                        TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    ),
                    color = if (focused) BrowseChipFocusedContent else Color.White,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
                if (mark != null) {
                    StatusMark(mark = mark, size = MarkSize.Compact)
                }
            }
        }
    }
}


private suspend fun loadBrowseItems(
    typeFilter: String,
    sourceFilter: String,
    sort: String,
    movieGenreIds: List<Int>,
    seriesGenreIds: List<Int>,
    yearMin: Int,
    yearMax: Int,
    mood: String,
    minRating: Double,
    library: List<MediaItem>,
    api: CoogApi,
    page: Int = 1,
): List<MediaItem> {
    if (sourceFilter == "local") {
        return filterLocalLibrary(library, typeFilter, yearMin, yearMax, minRating)
    }
    // Two TMDB pages per hop so hold-scroll stays ahead of a single RTT.
    val pageCount = BrowsePageCount
    val catalog = when (typeFilter) {
        "movie" -> api.catalogBrowse(
            kind = "movie",
            sort = sort,
            genreIds = movieGenreIds,
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            page = page,
            pageCount = pageCount,
        )
        "series" -> api.catalogBrowse(
            kind = "series",
            sort = sort,
            genreIds = seriesGenreIds,
            yearMin = yearMin,
            yearMax = yearMax,
            mood = mood,
            minRating = minRating,
            page = page,
            pageCount = pageCount,
        )
        else -> coroutineScope {
            val movies = async {
                if (seriesGenreIds.isNotEmpty() && movieGenreIds.isEmpty()) {
                    emptyList()
                } else {
                    api.catalogBrowse(
                        kind = "movie",
                        sort = sort,
                        genreIds = movieGenreIds,
                        yearMin = yearMin,
                        yearMax = yearMax,
                        mood = mood,
                        minRating = minRating,
                        page = page,
                        pageCount = pageCount,
                    )
                }
            }
            val series = async {
                if (movieGenreIds.isNotEmpty() && seriesGenreIds.isEmpty()) {
                    emptyList()
                } else {
                    api.catalogBrowse(
                        kind = "series",
                        sort = sort,
                        genreIds = seriesGenreIds,
                        yearMin = yearMin,
                        yearMax = yearMax,
                        mood = mood,
                        minRating = minRating,
                        page = page,
                        pageCount = pageCount,
                    )
                }
            }
            zipBrowseResults(movies.await(), series.await())
        }
    }
    val filtered = if (sourceFilter != "stream") {
        catalog
    } else {
        val localKeys = library.localCatalogKeys()
        catalog.filter { item ->
            !item.isLocal() && item.catalogMatchKey() !in localKeys
        }
    }
    return filtered.dedupeBrowseKeys()
}

private fun mergeBrowseGenreChips(
    movies: List<CatalogGenre>,
    series: List<CatalogGenre>,
): List<BrowseGenreChip> {
    val byKey = linkedMapOf<String, BrowseGenreChip>()
    for (g in movies) {
        val name = g.name.trim()
        if (name.isEmpty() || g.id <= 0) continue
        val key = name.lowercase()
        byKey[key] = BrowseGenreChip(key, name, movieId = g.id)
    }
    for (g in series) {
        val name = g.name.trim()
        if (name.isEmpty() || g.id <= 0) continue
        val key = name.lowercase()
        val prev = byKey[key]
        byKey[key] = if (prev != null) {
            prev.copy(seriesId = g.id)
        } else {
            BrowseGenreChip(key, name, seriesId = g.id)
        }
    }
    return listOf(BrowseGenreChip("all", "All")) + byKey.values.sortedBy { it.name.lowercase() }
}

private fun MediaItem.catalogMatchKey(): String {
    val imdb = imdbId.trim()
    if (imdb.isNotBlank()) return "imdb:$imdb"
    if (tmdbId > 0) return "tmdb:$kind:$tmdbId"
    return "id:${playableId().ifBlank { id }}"
}

private fun List<MediaItem>.localCatalogKeys(): Set<String> {
    val keys = HashSet<String>()
    for (item in this) {
        if (!item.isLocal()) continue
        val imdb = item.imdbId.trim()
        if (imdb.isNotBlank()) keys += "imdb:$imdb"
        if (item.tmdbId > 0) {
            val kind = when (item.kind) {
                "episode", "series" -> "series"
                else -> item.kind.ifBlank { "movie" }
            }
            keys += "tmdb:$kind:${item.tmdbId}"
        }
    }
    return keys
}

private fun filterLocalLibrary(
    library: List<MediaItem>,
    typeFilter: String,
    yearMin: Int,
    yearMax: Int,
    minRating: Double,
): List<MediaItem> {
    val browse = library.libraryBrowseItems()
    return browse.filter { item ->
        val typeOk = when (typeFilter) {
            "movie" -> item.kind == "movie"
            "series" -> item.kind == "series" || item.kind == "episode"
            else -> true
        }
        if (!typeOk) return@filter false
        if (yearMin > 0 && item.year > 0 && item.year < yearMin) return@filter false
        if (yearMax > 0 && item.year > 0 && item.year > yearMax) return@filter false
        if (minRating > 0 && item.rating > 0 && item.rating < minRating) return@filter false
        true
    }
}

private fun zipBrowseResults(movies: List<MediaItem>, series: List<MediaItem>): List<MediaItem> {
    val out = ArrayList<MediaItem>(movies.size + series.size)
    val max = maxOf(movies.size, series.size)
    for (i in 0 until max) {
        if (i < movies.size) out += movies[i]
        if (i < series.size) out += series[i]
    }
    return out.dedupeBrowseKeys()
}

private fun List<MediaItem>.dedupeBrowseKeys(): List<MediaItem> {
    if (size < 2) return this
    val seen = HashSet<String>(size * 2)
    return filter { item ->
        val keys = browseDedupeKeys(item)
        if (keys.isEmpty()) return@filter true
        if (keys.any { it in seen }) return@filter false
        seen.addAll(keys)
        true
    }
}

/** New cards from [batch] not already present (dedupe keys); does not mutate. */
private fun List<MediaItem>.newBrowseItems(batch: List<MediaItem>): List<MediaItem> {
    if (batch.isEmpty()) return emptyList()
    val seen = HashSet<String>((size + batch.size) * 2)
    for (item in this) {
        val keys = browseDedupeKeys(item)
        if (keys.isNotEmpty()) seen.addAll(keys)
    }
    val out = ArrayList<MediaItem>(batch.size)
    for (item in batch) {
        val keys = browseDedupeKeys(item)
        if (keys.isNotEmpty() && keys.any { it in seen }) continue
        if (keys.isNotEmpty()) seen.addAll(keys)
        out.add(item)
    }
    return out
}

private fun browseDedupeKeys(item: MediaItem): List<String> {
    val out = ArrayList<String>(3)
    val imdb = item.imdbId.trim().lowercase()
    if (imdb.isNotBlank()) out.add("imdb:$imdb")
    if (item.tmdbId > 0) {
        val kind = when (item.kind) {
            "episode", "series" -> "series"
            else -> item.kind.ifBlank { "movie" }
        }
        out.add("tmdb:$kind:${item.tmdbId}")
    }
    val playable = item.playableId().ifBlank { item.id }.trim()
    if (playable.isNotBlank()) out.add("id:$playable")
    return out
}
